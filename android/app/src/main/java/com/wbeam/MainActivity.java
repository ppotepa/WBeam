package com.wbeam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.wbeam.api.HostApiClient;
import com.wbeam.api.StatusListener;
import com.wbeam.api.StatusPoller;
import com.wbeam.hud.HudRenderSupport;
import com.wbeam.input.CursorOverlayController;
import com.wbeam.input.ImmersiveModeController;
import com.wbeam.startup.StartupOverlayModelBuilder;
import com.wbeam.startup.StartupOverlayController;
import com.wbeam.startup.StartupStepStyler;
import com.wbeam.startup.TransportProbeCoordinator;
import com.wbeam.stream.H264TcpPlayer;
import com.wbeam.stream.VideoTestController;
import com.wbeam.stream.StreamSessionController;
import com.wbeam.telemetry.ClientMetricsReporter;
import com.wbeam.ui.ErrorTextUtil;
import com.wbeam.ui.SettingsPayloadBuilder;
import com.wbeam.ui.StatusTextFormatter;
import com.wbeam.ui.StreamConfigResolver;
import com.wbeam.widget.FpsLossGraphView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WBeamMain";
    private static final String STATE_IDLE = "idle";
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_STREAMING = "streaming";
    private static final String STATE_ERROR = "error";

    private static final long HUD_ADB_LOG_INTERVAL_MS = 1000;
    private static final long LIVE_TEST_START_TIMEOUT_MS = 12000;
    private static final int TRANSPORT_QUEUE_MAX_FRAMES = 3;
    private static final int DECODE_QUEUE_MAX_FRAMES = 2;
    private static final int RENDER_QUEUE_MAX_FRAMES = 1;
    private static final int BANDWIDTH_TEST_MB = 64;
    private static final Pattern TRAINER_TRIAL_PROGRESS_RE =
            Pattern.compile("TRIAL\\s+[^\\[]*\\[(\\d+)/(\\d+)]");
    private static final String TEST_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4";

    private static final String[] PROFILE_OPTIONS = {
            "baseline"
    };
    /**
     * Preferred video encoder for this device.
     * HEVC hardware decode was standardised in API 21 (Android 5.0).  On older
     * devices we also check MediaCodecList at runtime — if no video/hevc decoder
     * is present we fall back to H.264 which every Android device supports.
     */
    static final String PREFERRED_VIDEO = preferredVideoEncoder();
    private static String preferredVideoEncoder() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "h264";
        }
        try {
            MediaCodecInfo[] infos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            } else {
                int count = MediaCodecList.getCodecCount();
                infos = new MediaCodecInfo[count];
                for (int i = 0; i < count; i++) infos[i] = MediaCodecList.getCodecInfoAt(i);
            }
            for (MediaCodecInfo info : infos) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if ("video/hevc".equalsIgnoreCase(type)) return "h265";
                }
            }
        } catch (Exception ignored) {
        }
        return "h264";
    }
    private static final String[] ENCODER_OPTIONS = {PREFERRED_VIDEO, "raw-png"};
    private static final String[] CURSOR_OPTIONS = {"embedded", "hidden", "metadata"};
    private static final String DEFAULT_PROFILE = "baseline";
    private static final String DEFAULT_CURSOR_MODE = "embedded";
    private static final int DEFAULT_RES_SCALE = 100;
    private static final int DEFAULT_FPS = 60;
    private static final int DEFAULT_BITRATE_MBPS = 25;

    private static final int LIVE_LOG_MAX_LINES = 80;
    private static final long SIMPLE_MENU_AUTO_HIDE_MS = 10_000L;
    private static final float DEBUG_INFO_ALPHA_IDLE = 0.55f;
    private static final float DEBUG_INFO_ALPHA_TOUCH = 0.92f;
    private static final long DEBUG_INFO_ALPHA_RESET_MS = 1300L;
    private static final long DEBUG_FPS_SAMPLE_MS = 1000L;
    private static final int DEBUG_FPS_GRAPH_POINTS = 180;
    private static final long DEBUG_OVERLAY_TOGGLE_HOLD_MS = 650L;
    private static final long PRESENT_FPS_STALE_GRACE_MS = 2500L;
    private static final long METRICS_STALE_GRACE_MS = 3000L;
    // Denser chart history so adjacent samples are visually closer at 4 Hz.
    private static final int HUD_RESOURCE_SERIES_MAX = 120;
    private static final double FPS_LOW_ANCHOR = 10.0;
    private static final long TRAINER_HUD_PAYLOAD_GRACE_MS = 2000L;

    // ── Views ──────────────────────────────────────────────────────────────────
    private View rootLayout;
    private View topBar;
    private View quickActionRow;
    private View settingsPanel;
    private View simpleMenuPanel;
    private View statusPanel;
    private View perfHudPanel;
    private View debugInfoPanel;
    private FpsLossGraphView debugFpsGraphView;
    private View preflightOverlay;
    private View debugControlsRow;
    private View statusLed;
    private View cursorOverlay;
    private TextView statusText;
    private TextView detailText;
    private TextView bpsText;
    private TextView statsText;
    private TextView perfHudText;
    private WebView perfHudWebView;
    private TextView debugInfoText;
    // ── Startup overlay ────────────────────────────────────────────────────────
    private TextView startupTitleText;
    private TextView startupSubtitleText;
    private View startupStep1Card;
    private View startupStep2Card;
    private View startupStep3Card;
    private TextView startupStep1Badge;
    private TextView startupStep1Label;
    private TextView startupStep1Detail;
    private TextView startupStep1Status;
    private TextView startupStep2Badge;
    private TextView startupStep2Label;
    private TextView startupStep2Detail;
    private TextView startupStep2Status;
    private TextView startupStep3Badge;
    private TextView startupStep3Label;
    private TextView startupStep3Detail;
    private TextView startupStep3Status;
    private TextView startupInfoText;
    private TextView startupBuildVersionText;
    private TextView liveLogText;
    private TextView resValueText;
    private TextView fpsValueText;
    private TextView bitrateValueText;
    private TextView hostHintText;

    private Spinner profileSpinner;
    private Spinner encoderSpinner;
    private Spinner cursorSpinner;
    private SeekBar resolutionSeek;
    private SeekBar fpsSeek;
    private SeekBar bitrateSeek;

    private Button settingsButton;
    private Button logButton;
    private Button settingsCloseButton;
    private Button applySettingsButton;
    private Button quickStartButton;
    private Button quickStopButton;
    private Button quickTestButton;
    private Button startButton;
    private Button stopButton;
    private Button testButton;
    private Button fullscreenButton;
    private Button cursorOverlayButton;
    private Button intraOnlyButton;
    private Button simpleModeH265Button;
    private Button simpleModeRawButton;
    private Button simpleFps30Button;
    private Button simpleFps45Button;
    private Button simpleFps60Button;
    private Button simpleFps90Button;
    private Button simpleFps120Button;
    private Button simpleFps144Button;
    private Button simpleApplyButton;

    // ── UI state ───────────────────────────────────────────────────────────────
    private boolean intraOnlyEnabled = false;
    private boolean settingsVisible = false;
    private boolean simpleMenuVisible = false;
    private String simpleMode = PREFERRED_VIDEO;
    private int simpleFps = 60;
    private boolean debugControlsVisible = false;
    private boolean debugOverlayVisible = false;
    private boolean trainerHudSessionActive = false;
    private boolean volumeUpHeld = false;
    private boolean volumeDownHeld = false;
    private boolean debugOverlayToggleArmed = false;
    private boolean liveLogVisible = false;
    private long lastHudAdbLogAt = 0L;
    private String lastHudAdbSnapshot = "";
    // startup step state constants
    private static final int SS_PENDING = 0;
    private static final int SS_ACTIVE  = 1;
    private static final int SS_OK      = 2;
    private static final int SS_ERROR   = 3;

    private boolean surfaceReady = false;
    private boolean preflightComplete = false;
    private int preflightAnimTick = 0;
    private long startupBeganAtMs = 0L;
    private boolean startupDismissed = false;
    private boolean handshakeResolved = false;
    private int controlRetryCount = 0;
    private boolean hwAvcDecodeAvailable = false;
    private String lastUiState = STATE_IDLE;
    private String lastUiInfo = "tap Settings -> Start Live";
    private long lastUiBps = 0;
    private String lastCriticalErrorInfo = "";
    private long lastCriticalErrorLogAtMs = 0L;
    private String lastStatsLine = "fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -";
    private String lastHudCompactLine = "hud: waiting for metrics";
    private String hudOverlayMode = "none";
    private String lastHudWebHtml = "";
    private long lastTrainerHudPayloadAtMs = 0L;
    private double latestTargetFps = 60.0;
    private double latestPresentFps = 0.0;
    private long latestStreamUptimeSec = 0L;
    private long latestFrameOutHost = 0L;
    private double latestStablePresentFps = 0.0;
    private long latestStablePresentFpsAtMs = 0L;
    private long lastPerfMetricsAtMs = 0L;
    private final SpannableStringBuilder liveLogBuffer = new SpannableStringBuilder();
    private long usageSampleLastRealtimeMs = 0L;
    private long usageSampleLastCpuMs = 0L;
    private double usageCpuPct = 0.0;
    private double usageMemMb = 0.0;
    private double usageGpuPct = 0.0;
    private final ArrayDeque<Double> usageCpuSeries = new ArrayDeque<>();
    private final ArrayDeque<Double> usageMemSeries = new ArrayDeque<>();
    private final ArrayDeque<Double> usageGpuSeries = new ArrayDeque<>();
    private final ArrayDeque<Double> runtimePresentSeries = new ArrayDeque<>();
    private final ArrayDeque<Double> runtimeMbpsSeries = new ArrayDeque<>();
    private final ArrayDeque<Double> runtimeDropSeries = new ArrayDeque<>();
    private final ArrayDeque<Double> runtimeLatencySeries = new ArrayDeque<>();
    private final ArrayDeque<Double> runtimeQueueSeries = new ArrayDeque<>();
    private long runtimeDropPrevCount = -1L;
    private long runtimeDropPrevAtMs = 0L;

    // ── Daemon state (updated via StatusPoller.Callbacks) ──────────────────────
    private boolean daemonReachable = false;
    private String daemonHostName = "-";
    private String daemonService = "-";
    private String daemonBuildRevision = "-";
    private String daemonState = "IDLE";
    private String daemonLastError = "";
    private long daemonRunId = 0L;
    private long daemonUptimeSec = 0L;

    // ── Infrastructure ─────────────────────────────────────────────────────────
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private StatusPoller statusPoller;
    private StreamSessionController sessionController;
    private ClientMetricsReporter metricsReporter;
    private VideoTestController videoTestController;
    private final TransportProbeCoordinator transportProbe = new TransportProbeCoordinator();
    private StartupOverlayController startupOverlayController;
    private CursorOverlayController cursorOverlayController;
    private ImmersiveModeController immersiveModeController;

    // ── Media ──────────────────────────────────────────────────────────────────
    private Surface surface;
    private H264TcpPlayer player;
    // ── Runnables ──────────────────────────────────────────────────────────────
    private final Runnable simpleMenuAutoHideTask = this::hideSimpleMenu;
    private final Runnable debugInfoFadeTask = () -> {
        if (debugInfoPanel != null) {
            debugInfoPanel.setAlpha(DEBUG_INFO_ALPHA_IDLE);
        }
    };
    private final Runnable debugGraphSampleTask = new Runnable() {
        @Override
        public void run() {
            if (BuildConfig.DEBUG && debugFpsGraphView != null) {
                debugFpsGraphView.addSample(latestTargetFps, latestPresentFps);
            }
            uiHandler.postDelayed(this, DEBUG_FPS_SAMPLE_MS);
        }
    };
    private final Runnable debugOverlayToggleTask = () -> {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (volumeUpHeld && volumeDownHeld && !debugOverlayToggleArmed) {
            debugOverlayToggleArmed = true;
            boolean nextVisible = !debugOverlayVisible;
            setDebugOverlayVisible(nextVisible);
            Toast.makeText(
                    this,
                    nextVisible ? "Debug overlay ON" : "Debug overlay OFF",
                    Toast.LENGTH_SHORT
            ).show();
        }
    };

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setScreenAlwaysOn(true);
        bindStartupBuildVersion();
        setupSpinners();
        setupSeekbars();
        setupSurfaceCallbacks();
        setupButtons();
        loadSavedSettings();
        hwAvcDecodeAvailable = hasHardwareAvcDecoder();
        Log.i(TAG, "startup transport api_impl=" + BuildConfig.WBEAM_API_IMPL
            + " api=" + HostApiClient.API_BASE
            + " stream=tcp://" + BuildConfig.WBEAM_STREAM_HOST + ":" + BuildConfig.WBEAM_STREAM_PORT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        metricsReporter = new ClientMetricsReporter(ioExecutor, msg -> appendLiveLogWarn(msg));

        videoTestController = new VideoTestController(uiHandler, ioExecutor,
                new VideoTestController.Callbacks() {
            @Override public Surface getSurface() { return surface; }
            @Override public void stopVideoPlayer() {
                if (player != null) { player.stop(); player = null; }
            }
            @Override public String getDaemonState()     { return daemonState;     }
            @Override public boolean isDaemonReachable() { return daemonReachable; }
            @Override public void onStatus(String state, String info, long bps) {
                updateStatus(state, info, bps);
            }
            @Override public void onStatsLine(String line)  { updateStatsLine(line); }
            @Override public void logInfo(String msg)  { appendLiveLogInfo(msg);  }
            @Override public void logWarn(String msg)  { appendLiveLogWarn(msg);  }
            @Override public void logError(String msg) { appendLiveLogError(msg); }
            @Override public void onOverlayChanged() { updatePreflightOverlay(); }
            @Override public void setLiveLogVisible(boolean v) {
                liveLogVisible = v;
                if (liveLogText != null)
                    liveLogText.setVisibility(v ? View.VISIBLE : View.GONE);
            }
            @Override public void showToast(String msg, boolean longT) {
                Toast.makeText(MainActivity.this, msg,
                        longT ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
            @Override public VideoTestController.TestConfig getTestConfig() {
                int[] sz = computeScaledSize();
                return new VideoTestController.TestConfig(
                        getSelectedProfile(), getSelectedEncoder(),
                        getSelectedCursorMode(),
                        sz[0], sz[1],
                        getSelectedFps(), getSelectedBitrateMbps());
            }
        });

        statusPoller = new StatusPoller(uiHandler, ioExecutor, new StatusPoller.Callbacks() {
            @Override
            public void onDaemonStatusUpdate(boolean reachable, boolean wasReachable,
                    String hostName, String state, long runId, String lastError,
                    boolean errorChanged, long uptimeSec, String service, String buildRevision, JSONObject metrics) {
                daemonReachable = reachable;
                daemonHostName = hostName;
                daemonState = state;
                daemonLastError = lastError;
                daemonRunId = runId;
                daemonUptimeSec = uptimeSec;
                daemonService = service;
                daemonBuildRevision = (buildRevision == null || buildRevision.trim().isEmpty()) ? "-" : buildRevision.trim();
                if (!handshakeResolved && !service.equals("-")) {
                    handshakeResolved = true;
                }

                if (requiresTransportProbe()) {
                    maybeStartTransportProbe();
                }

                if (!wasReachable) {
                    Toast.makeText(MainActivity.this, "Connected to " + hostName, Toast.LENGTH_SHORT).show();
                    appendLiveLogInfo("connected to host " + hostName);
                }
                if (errorChanged && !lastError.isEmpty()) {
                    appendLiveLogError("host last_error: " + lastError);
                }
                if (!"STREAMING".equals(state)
                        && !"STARTING".equals(state)
                        && !"RECONNECTING".equals(state)) {
                    stopLiveView();
                }
                updateActionButtonsEnabled();
                updateHostHint();
                refreshStatusText();
                if (metrics != null) {
                    long frameIn = metrics.optLong("frame_in", 0);
                    long frameOut = metrics.optLong("frame_out", 0);
                    long drops = metrics.optLong("drops", 0);
                    long reconnects = metrics.optLong("reconnects", 0);
                    long bps = metrics.optLong("bitrate_actual_bps", 0);
                    String errCompact = lastError.length() > 80 ? lastError.substring(0, 80) + "..." : lastError;
                    updateStatsLine("host in/out: " + frameIn + "/" + frameOut
                            + " | drops: " + drops + " | reconnects: " + reconnects
                            + " | bitrate: " + formatBps(bps)
                            + (errCompact.isEmpty() ? "" : " | last_error: " + errCompact));
                }
                updatePerfHud(metrics);
                updatePreflightOverlay();
            }

            @Override
            public void onDaemonOffline(boolean wasReachable, Exception e) {
                daemonReachable = false;
                daemonState = "DISCONNECTED";
                stopLiveView();
                handshakeResolved = false;
                transportProbe.markWaitingForControlLink();
                updateActionButtonsEnabled();
                updateHostHint();
                updatePerfHudUnavailable();
                preflightComplete = false;
                startupDismissed = false;
                if (wasReachable) {
                    // Host was reachable and just dropped — restart retry cycle clean
                    startupBeganAtMs = SystemClock.elapsedRealtime();
                    controlRetryCount = 0;
                }
                updatePreflightOverlay();
                if (wasReachable) {
                    updateStatus(STATE_ERROR, "Host API offline: " + ErrorTextUtil.shortError(e), 0);
                    appendLiveLogError("daemon poll failed: " + ErrorTextUtil.shortError(e)
                            + " | api=" + HostApiClient.API_BASE);
                    Toast.makeText(MainActivity.this,
                            "Host API unreachable (" + ErrorTextUtil.shortError(e) + "). Check USB tethering/LAN and host IP: " + HostApiClient.API_BASE,
                            Toast.LENGTH_LONG).show();
                } else {
                    refreshStatusText();
                }
            }

            @Override
            public void onAutoStartRequired() {
                requestStartGuarded(false, true);
            }

            @Override
            public void onAutoStartFailed() {
                appendLiveLogWarn("auto-start paused after failed capture; tap Start Live to retry");
            }

            @Override
            public void ensureDecoderRunning() {
                MainActivity.this.ensureDecoderRunning();
            }
        });

        sessionController = new StreamSessionController(uiHandler, ioExecutor, new StreamSessionController.Callbacks() {
            @Override
            public void onStatus(String state, String info, long bps) {
                updateStatus(state, info, bps);
            }

            @Override
            public void onDaemonStatusJson(JSONObject status) {
                updateDaemonStateFromJson(status);
            }

            @Override
            public void ensureDecoderRunning() {
                MainActivity.this.ensureDecoderRunning();
            }

            @Override
            public void stopLiveView() {
                MainActivity.this.stopLiveView();
            }

            @Override
            public void showToast(String msg, boolean longToast) {
                Toast.makeText(MainActivity.this, msg,
                        longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }

            @Override
            public void appendLiveLogWarn(String msg) {
                MainActivity.this.appendLiveLogWarn(msg);
            }

            @Override
            public void handleApiFailure(String prefix, boolean userAction, Exception e) {
                MainActivity.this.handleApiFailure(prefix, userAction, e);
            }

            @Override
            public JSONObject buildConfigPayload() {
                return MainActivity.this.buildConfigPayload();
            }

            @Override
            public void suppressAutoStart(long durationMs) {
                if (durationMs <= 0) {
                    statusPoller.clearAutoStartSuppression();
                } else {
                    statusPoller.suppressAutoStart(durationMs);
                }
            }

            @Override
            public void recordAutoStartAttempt() {
                statusPoller.recordAutoStartAttempt();
            }

            @Override
            public void setAutoStartPending(boolean pending) {
                statusPoller.setAutoStartPending(pending);
            }
        });

        applySettings(false);
        setDebugControlsVisible(false);
        applyBuildVariantUi();
        updateStatsLine("fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -");
        updatePerfHudUnavailable();
        startupBeganAtMs = SystemClock.elapsedRealtime();
        handshakeResolved = false;
        startupDismissed = false;
        startPreflightPulse();
        updatePreflightOverlay();
        updateStatus(STATE_IDLE, "waiting for desktop connect", 0);
        statusPoller.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setScreenAlwaysOn(true);
        enforceImmersiveModeIfNeeded();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enforceImmersiveModeIfNeeded();
        }
    }

    @Override
    protected void onDestroy() {
        statusPoller.stop();
        stopPreflightPulse();
        uiHandler.removeCallbacks(simpleMenuAutoHideTask);
        uiHandler.removeCallbacks(debugInfoFadeTask);
        uiHandler.removeCallbacks(debugGraphSampleTask);
        uiHandler.removeCallbacks(debugOverlayToggleTask);
        videoTestController.release();
        stopLiveView();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (simpleMenuVisible) {
            hideSimpleMenu();
            return;
        }
        if (settingsVisible) {
            hideSettingsPanel();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (BuildConfig.DEBUG
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getRepeatCount() == 0) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    volumeUpHeld = true;
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    volumeDownHeld = true;
                }
                if (volumeUpHeld && volumeDownHeld && !debugOverlayToggleArmed) {
                    uiHandler.removeCallbacks(debugOverlayToggleTask);
                    uiHandler.postDelayed(debugOverlayToggleTask, DEBUG_OVERLAY_TOGGLE_HOLD_MS);
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (BuildConfig.DEBUG
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                volumeUpHeld = false;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                volumeDownHeld = false;
            }
            if (!volumeUpHeld || !volumeDownHeld) {
                uiHandler.removeCallbacks(debugOverlayToggleTask);
            }
            if (!volumeUpHeld && !volumeDownHeld) {
                debugOverlayToggleArmed = false;
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // View setup
    // ══════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        rootLayout = findViewById(R.id.rootLayout);
        immersiveModeController = new ImmersiveModeController(this, rootLayout);
        topBar = findViewById(R.id.topBar);
        quickActionRow = findViewById(R.id.quickActionRow);
        settingsPanel = findViewById(R.id.settingsPanel);
        simpleMenuPanel = findViewById(R.id.simpleMenuPanel);
        statusPanel = findViewById(R.id.statusPanel);
        perfHudPanel = findViewById(R.id.perfHudPanel);
        debugInfoPanel = findViewById(R.id.debugInfoPanel);
        debugFpsGraphView = findViewById(R.id.debugFpsGraph);
        preflightOverlay = findViewById(R.id.preflightOverlay);
        debugControlsRow = findViewById(R.id.debugControlsRow);
        statusLed = findViewById(R.id.statusLed);
        cursorOverlay = findViewById(R.id.cursorOverlay);

        statusText = findViewById(R.id.statusText);
        detailText = findViewById(R.id.detailText);
        bpsText = findViewById(R.id.bpsText);
        statsText = findViewById(R.id.statsText);
        perfHudText = findViewById(R.id.perfHudText);
        perfHudWebView = findViewById(R.id.perfHudWebView);
        debugInfoText = findViewById(R.id.debugInfoText);
        startupTitleText    = findViewById(R.id.startupTitle);
        startupSubtitleText = findViewById(R.id.startupSubtitle);
        startupStep1Card    = findViewById(R.id.startupStep1Row);
        startupStep2Card    = findViewById(R.id.startupStep2Row);
        startupStep3Card    = findViewById(R.id.startupStep3Row);
        startupStep1Badge   = findViewById(R.id.startupStep1Badge);
        startupStep1Label   = findViewById(R.id.startupStep1Label);
        startupStep1Detail  = findViewById(R.id.startupStep1Detail);
        startupStep1Status  = findViewById(R.id.startupStep1Status);
        startupStep2Badge   = findViewById(R.id.startupStep2Badge);
        startupStep2Label   = findViewById(R.id.startupStep2Label);
        startupStep2Detail  = findViewById(R.id.startupStep2Detail);
        startupStep2Status  = findViewById(R.id.startupStep2Status);
        startupStep3Badge   = findViewById(R.id.startupStep3Badge);
        startupStep3Label   = findViewById(R.id.startupStep3Label);
        startupStep3Detail  = findViewById(R.id.startupStep3Detail);
        startupStep3Status  = findViewById(R.id.startupStep3Status);
        startupInfoText     = findViewById(R.id.startupInfoText);
        startupBuildVersionText = findViewById(R.id.startupBuildVersion);
        if (startupInfoText != null) {
            startupInfoText.setMovementMethod(new ScrollingMovementMethod());
        }
        startupOverlayController = new StartupOverlayController(uiHandler, preflightOverlay);
        startupOverlayController.setTickListener(animTick -> {
            preflightAnimTick = animTick;
            updatePreflightOverlay();
        });
        cursorOverlayController = new CursorOverlayController(cursorOverlay, cursorOverlayButton);
        liveLogText = findViewById(R.id.liveLogText);

        resValueText = findViewById(R.id.resValueText);
        fpsValueText = findViewById(R.id.fpsValueText);
        bitrateValueText = findViewById(R.id.bitrateValueText);
        hostHintText = findViewById(R.id.hostHintText);

        profileSpinner = findViewById(R.id.profileSpinner);
        encoderSpinner = findViewById(R.id.encoderSpinner);
        cursorSpinner = findViewById(R.id.cursorSpinner);

        resolutionSeek = findViewById(R.id.resolutionSeek);
        fpsSeek = findViewById(R.id.fpsSeek);
        bitrateSeek = findViewById(R.id.bitrateSeek);

        settingsButton = findViewById(R.id.settingsButton);
        logButton = findViewById(R.id.logButton);
        settingsCloseButton = findViewById(R.id.settingsCloseButton);
        applySettingsButton = findViewById(R.id.applySettingsButton);
        quickStartButton = findViewById(R.id.quickStartButton);
        quickStopButton = findViewById(R.id.quickStopButton);
        quickTestButton = findViewById(R.id.quickTestButton);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        testButton = findViewById(R.id.testButton);
        fullscreenButton = findViewById(R.id.fullscreenButton);
        cursorOverlayButton = findViewById(R.id.cursorOverlayButton);
        intraOnlyButton     = findViewById(R.id.intraOnlyButton);
        simpleModeH265Button = findViewById(R.id.simpleModeH265Button);
        simpleModeRawButton = findViewById(R.id.simpleModeRawButton);
        simpleFps30Button = findViewById(R.id.simpleFps30Button);
        simpleFps45Button = findViewById(R.id.simpleFps45Button);
        simpleFps60Button = findViewById(R.id.simpleFps60Button);
        simpleFps90Button = findViewById(R.id.simpleFps90Button);
        simpleFps120Button = findViewById(R.id.simpleFps120Button);
        simpleFps144Button = findViewById(R.id.simpleFps144Button);
        simpleApplyButton = findViewById(R.id.simpleApplyButton);
        setupTrainerHudWebView();
    }

    private void setupTrainerHudWebView() {
        if (perfHudWebView == null) {
            return;
        }
        WebSettings settings = perfHudWebView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        perfHudWebView.setBackgroundColor(Color.TRANSPARENT);
        perfHudWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        perfHudWebView.setVerticalScrollBarEnabled(false);
        perfHudWebView.setHorizontalScrollBarEnabled(false);
        perfHudWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        perfHudWebView.setPadding(0, 0, 0, 0);
        perfHudWebView.setInitialScale(100);
    }

    private void bindStartupBuildVersion() {
        if (startupBuildVersionText == null) {
            return;
        }
        String rev = BuildConfig.WBEAM_BUILD_REV == null ? "" : BuildConfig.WBEAM_BUILD_REV.trim();
        if (rev.isEmpty()) {
            rev = "unknown";
        }
        startupBuildVersionText.setText("build " + rev);
    }

    private void applyBuildVariantUi() {
        hideSettingsPanel();
        hideSimpleMenu();
        setDebugControlsVisible(false);
        if (topBar != null) {
            topBar.setVisibility(View.GONE);
        }
        if (quickActionRow != null) {
            quickActionRow.setVisibility(View.GONE);
        }
        if (statusPanel != null) {
            statusPanel.setVisibility(View.GONE);
        }
        if (perfHudPanel != null) {
            perfHudPanel.setVisibility(View.GONE);
        }
        if (settingsButton != null) {
            settingsButton.setVisibility(View.GONE);
        }
        if (logButton != null) {
            logButton.setVisibility(View.GONE);
        }
        if (fullscreenButton != null) {
            fullscreenButton.setVisibility(View.GONE);
        }
        if (BuildConfig.DEBUG) {
            setFullscreen(true);
            if (debugFpsGraphView != null) {
                debugFpsGraphView.setCapacity(DEBUG_FPS_GRAPH_POINTS);
            }
            setDebugOverlayVisible(debugOverlayVisible);
            startDebugGraphSampling();
            refreshDebugInfoOverlay();
            return;
        }

        setFullscreen(true);
        if (debugInfoPanel != null) {
            debugInfoPanel.setVisibility(View.GONE);
        }
        stopDebugGraphSampling();
    }

    private void setupSpinners() {
        profileSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PROFILE_OPTIONS));
        encoderSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ENCODER_OPTIONS));
        cursorSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CURSOR_OPTIONS));
        encoderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateIntraOnlyButton();
                updateHostHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        cursorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                enforceCursorOverlayPolicy(false);
                updateHostHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupSeekbars() {
        resolutionSeek.setMax(50); // 50..100%
        fpsSeek.setMax(120); // 24..144 fps
        bitrateSeek.setMax(295); // 5..300 Mbps

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSettingValueLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };

        resolutionSeek.setOnSeekBarChangeListener(listener);
        fpsSeek.setOnSeekBarChangeListener(listener);
        bitrateSeek.setOnSeekBarChangeListener(listener);
    }

    private void setupSurfaceCallbacks() {
        SurfaceView preview = findViewById(R.id.previewSurface);
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                surfaceReady = surface != null && surface.isValid();
                updatePreflightOverlay();
                updateStatus(STATE_IDLE, "surface ready", 0);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
                surfaceReady = surface != null && surface.isValid();
                updatePreflightOverlay();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopLiveView();
                surface = null;
                surfaceReady = false;
                preflightComplete = false;
                updatePreflightOverlay();
                hideCursorOverlay();
                updateStatus(STATE_IDLE, "surface destroyed", 0);
            }
        });

        preview.setOnTouchListener((v, event) -> {
            if (cursorOverlayController == null || !cursorOverlayController.isOverlayEnabled()) {
                return false;
            }
            updateCursorOverlay(event.getX(), event.getY(), event.getActionMasked());
            return false;
        });

        preview.setOnGenericMotionListener((v, event) -> {
            if (cursorOverlayController == null || !cursorOverlayController.isOverlayEnabled()) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE ||
                    event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                updateCursorOverlay(event.getX(), event.getY(), event.getActionMasked());
            }
            return false;
        });
    }

    private void setupButtons() {
        if (settingsCloseButton != null) {
            settingsCloseButton.setOnClickListener(v -> hideSettingsPanel());
        }

        if (simpleMenuPanel != null) {
            simpleMenuPanel.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE
                        || event.getActionMasked() == MotionEvent.ACTION_UP) {
                    scheduleSimpleMenuAutoHide();
                }
                return false;
            });
        }

        if (debugInfoPanel != null) {
            debugInfoPanel.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_MOVE
                        || action == MotionEvent.ACTION_UP) {
                    debugInfoPanel.setAlpha(DEBUG_INFO_ALPHA_TOUCH);
                    uiHandler.removeCallbacks(debugInfoFadeTask);
                    uiHandler.postDelayed(debugInfoFadeTask, DEBUG_INFO_ALPHA_RESET_MS);
                }
                return false;
            });
        }

        if (simpleModeH265Button != null) {
            // Relabel button to match the preferred codec for this device.
            simpleModeH265Button.setText("h264".equals(PREFERRED_VIDEO) ? "H.264" : "H.265");
            simpleModeH265Button.setOnClickListener(v -> {
                simpleMode = PREFERRED_VIDEO;
                refreshSimpleMenuButtons();
                scheduleSimpleMenuAutoHide();
            });
        }
        if (simpleModeRawButton != null) {
            simpleModeRawButton.setOnClickListener(v -> {
                simpleMode = "raw-png";
                refreshSimpleMenuButtons();
                scheduleSimpleMenuAutoHide();
            });
        }

        if (simpleFps30Button != null) simpleFps30Button.setOnClickListener(v -> selectSimpleFps(30));
        if (simpleFps45Button != null) simpleFps45Button.setOnClickListener(v -> selectSimpleFps(45));
        if (simpleFps60Button != null) simpleFps60Button.setOnClickListener(v -> selectSimpleFps(60));
        if (simpleFps90Button != null) simpleFps90Button.setOnClickListener(v -> selectSimpleFps(90));
        if (simpleFps120Button != null) simpleFps120Button.setOnClickListener(v -> selectSimpleFps(120));
        if (simpleFps144Button != null) simpleFps144Button.setOnClickListener(v -> selectSimpleFps(144));

        if (simpleApplyButton != null) {
            simpleApplyButton.setOnClickListener(v -> {
                applySimpleMenuToSettings();
                requestStartGuarded(false, true);
                hideSimpleMenu();
            });
        }

        updateActionButtonsEnabled();
    }

    private void setActionButtonEnabled(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private void setDebugOverlayVisible(boolean visible) {
        debugOverlayVisible = visible;
        if (!BuildConfig.DEBUG) {
            return;
        }
        // Unified HUD mode: keep only one on-screen overlay panel.
        // Legacy debug panel (text + fps graph) stays disabled to avoid layered HUDs.
        if (debugInfoPanel != null) {
            debugInfoPanel.setVisibility(View.GONE);
        }
        if (perfHudPanel != null) {
            perfHudPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                perfHudPanel.setAlpha(0.96f);
            }
        }
    }

    private void updateActionButtonsEnabled() {
        boolean enabled = daemonReachable;
        setActionButtonEnabled(quickStartButton, enabled);
        setActionButtonEnabled(quickStopButton, enabled);
        setActionButtonEnabled(quickTestButton, enabled);
        setActionButtonEnabled(startButton, enabled);
        setActionButtonEnabled(stopButton, enabled);
        setActionButtonEnabled(testButton, enabled);
    }

    private void setDebugControlsVisible(boolean visible) {
        debugControlsVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (debugControlsRow != null) {
            debugControlsRow.setVisibility(visibility);
        }
        if (testButton != null) {
            testButton.setVisibility(visibility);
        }
    }

    private void toggleLiveLogPanel() {
        liveLogVisible = !liveLogVisible;
        if (liveLogText != null) {
            liveLogText.setVisibility(liveLogVisible ? View.VISIBLE : View.GONE);
        }
        if (logButton != null) {
            logButton.setText(liveLogVisible ? "Log ON" : "Log");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Settings
    // ══════════════════════════════════════════════════════════════════════════

    private void loadSavedSettings() {
        setSpinnerSelection(profileSpinner, PROFILE_OPTIONS, DEFAULT_PROFILE);
        setSpinnerSelection(encoderSpinner, ENCODER_OPTIONS, PREFERRED_VIDEO);
        setSpinnerSelection(cursorSpinner, CURSOR_OPTIONS, DEFAULT_CURSOR_MODE);
        resolutionSeek.setProgress(clamp(DEFAULT_RES_SCALE, 50, 100) - 50);
        fpsSeek.setProgress(clamp(DEFAULT_FPS, 24, 144) - 24);
        bitrateSeek.setProgress(clamp(DEFAULT_BITRATE_MBPS, 5, 300) - 5);
        if (cursorOverlayController != null) {
            cursorOverlayController.resetEnabledDefault();
        }
        enforceCursorOverlayPolicy(false);
        intraOnlyEnabled = false;
        updateIntraOnlyButton();
        updateSettingValueLabels();
        simpleMode = PREFERRED_VIDEO;
        simpleFps = DEFAULT_FPS;
        refreshSimpleMenuButtons();
    }

    private void applySettings(boolean userAction) {
        updateSettingValueLabels();
        updateHostHint();
    }

    private void updateSettingValueLabels() {
        int scale = getResolutionScalePercent();
        int fps = getSelectedFps();
        int bitrate = getSelectedBitrateMbps();
        int[] sz = computeScaledSize();

        resValueText.setText(scale + "% (" + sz[0] + "x" + sz[1] + ")");
        fpsValueText.setText(String.valueOf(fps));
        bitrateValueText.setText(bitrate + " Mbps");
    }

    private void updateIntraOnlyButton() {
        if (intraOnlyButton == null) return;
        String encoder = getSelectedEncoder();
        boolean supportsIntra = "h265".equals(encoder);
        if (!supportsIntra) {
            intraOnlyEnabled = false;
        }
        intraOnlyButton.setEnabled(supportsIntra);
        intraOnlyButton.setAlpha(supportsIntra ? 1.0f : 0.45f);
        intraOnlyButton.setText(intraOnlyEnabled
                ? "All-Intra: ON  \u2014 zero artifacts (HEVC only)"
            : (supportsIntra ? "All-Intra: OFF" : "All-Intra: N/A"));
        int buttonColor = intraOnlyEnabled ? 0xFF16A34A : 0xFF374151;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intraOnlyButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(buttonColor));
        } else {
            intraOnlyButton.setBackgroundColor(buttonColor);
        }
    }

    private void updateHostHint() {
        StreamConfigResolver.Resolved cfg = effectiveStreamConfig();
        String daemonStateUi = effectiveDaemonState(
                daemonState, latestPresentFps, latestStreamUptimeSec, latestFrameOutHost);
        hostHintText.setText(StatusTextFormatter.buildHostHintText(
                daemonReachable,
                HostApiClient.API_BASE,
                daemonHostName,
                daemonStateUi,
                daemonService,
                getSelectedProfile(),
                cfg.width,
                cfg.height,
                cfg.fps,
                cfg.bitrateMbps,
                getSelectedEncoder(),
                intraOnlyEnabled,
                getSelectedCursorMode()
        ));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Core helpers (called by callbacks and internally)
    // ══════════════════════════════════════════════════════════════════════════

    private void updateDaemonStateFromJson(JSONObject status) {
        if (status == null) return;
        daemonReachable = true;
        daemonHostName = status.optString("host_name", daemonHostName);
        daemonState = status.optString("state", "IDLE").toUpperCase(Locale.US);
        daemonLastError = status.optString("last_error", daemonLastError);
        daemonRunId = status.optLong("run_id", daemonRunId);
        daemonUptimeSec = status.optLong("uptime", daemonUptimeSec);
        updateActionButtonsEnabled();
        updateHostHint();
        refreshStatusText();
        updatePreflightOverlay();
    }

    private void ensureDecoderRunning() {
        if (surface == null || !surface.isValid()) {
            return;
        }
        if (player != null && player.isRunning()) {
            return;
        }
        startLiveView();
    }

    private JSONObject buildConfigPayload() {
        return SettingsPayloadBuilder.buildPayload(
                getSelectedProfile(),
                getSelectedEncoder(),
                getSelectedCursorMode(),
                effectiveStreamConfig(),
                intraOnlyEnabled,
                isLegacyAndroidDevice()
        );
    }

    private void handleApiFailure(String prefix, boolean userAction, Exception e) {
        String reason = ErrorTextUtil.shortError(e);
        updateStatus(STATE_ERROR, prefix + ": " + reason, 0);
        appendLiveLogError(prefix + ": " + reason);
        Log.e(TAG, prefix + ": " + reason, e);
        if (userAction) {
            Toast.makeText(this,
                    "Host API unreachable (" + reason + "). Check USB tethering/LAN and host IP: " + HostApiClient.API_BASE,
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isBuildMismatch() {
        if (!daemonReachable || !handshakeResolved) {
            return false;
        }
        if ("local".equalsIgnoreCase(BuildConfig.WBEAM_API_IMPL)) {
            return false;
        }
        String hostRev = daemonBuildRevision == null ? "" : daemonBuildRevision.trim();
        String appRev = BuildConfig.WBEAM_BUILD_REV == null ? "" : BuildConfig.WBEAM_BUILD_REV.trim();
        if (hostRev.isEmpty() || "-".equals(hostRev) || appRev.isEmpty()) {
            return false;
        }
        return !hostRev.equals(appRev);
    }

    private void requestStartGuarded(boolean userAction, boolean ensureViewer) {
        if (isBuildMismatch()) {
            String msg = "Build mismatch: app=" + BuildConfig.WBEAM_BUILD_REV
                    + " host=" + daemonBuildRevision
                    + " (redeploy APK or rebuild host)";
            updateStatus(STATE_ERROR, msg, 0);
            appendLiveLogError(msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }
        sessionController.requestStart(userAction, ensureViewer);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stream / media
    // ══════════════════════════════════════════════════════════════════════════

    private void startLiveView() {
        videoTestController.release();
        if (surface == null || !surface.isValid()) {
            updateStatus(STATE_ERROR, "surface not ready yet", 0);
            return;
        }
        if (player != null && player.isRunning()) {
            updateStatus(STATE_STREAMING, "already running", 0);
            return;
        }

        StreamConfigResolver.Resolved cfg = effectiveStreamConfig();
        long frameUs = Math.max(1L, 1_000_000L / Math.max(1, cfg.fps));
        SurfaceView preview = findViewById(R.id.previewSurface);
        Log.i(TAG, String.format(Locale.US,
                "startLiveView: cfg=%dx%d@%dfps view=%dx%d surfaceValid=%s",
                cfg.width, cfg.height, cfg.fps,
                preview.getWidth(), preview.getHeight(),
                surface != null && surface.isValid()));
        // Keep the Surface buffer sized from layout so video can scale to fill the screen.
        // setFixedSize(stream_w, stream_h) causes a "small centered video" effect whenever the
        // stream resolution differs from the view size (default config scales stream size).
        preview.getHolder().setSizeFromLayout();
        try {
            Rect frame = preview.getHolder().getSurfaceFrame();
            if (frame != null) {
                Log.i(TAG, String.format(Locale.US,
                        "startLiveView: surfaceFrame=%dx%d",
                        frame.width(), frame.height()));
            }
        } catch (Exception ignored) {
        }

        player = new H264TcpPlayer(
                surface,
                new StatusListener() {
                    @Override
                    public void onStatus(String state, String info, long bps) {
                        runOnUiThread(() -> updateStatus(state, info, bps));
                    }

                    @Override
                    public void onStats(String line) {
                        runOnUiThread(() -> updateStatsLine(line));
                    }

                    @Override
                    public void onClientMetrics(ClientMetricsSample metrics) {
                        metricsReporter.push(metrics);
                    }
                },
                cfg.width,
                cfg.height,
                frameUs
        );
        player.start();
        hideSettingsPanel();
    }

    private void stopLiveView() {
        if (player != null) {
            player.stop();
            player = null;
        }
        videoTestController.release();
        hideCursorOverlay();
        updateStatsLine("fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -");
        updateStatus(STATE_IDLE, "stopped", 0);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - settings panel
    // ══════════════════════════════════════════════════════════════════════════

    private void toggleSettingsPanel() {
        if (settingsVisible) {
            hideSettingsPanel();
        } else {
            showSettingsPanel();
        }
    }

    private void showSettingsPanel() {
        if (settingsVisible) {
            return;
        }
        settingsVisible = true;
        settingsPanel.setVisibility(View.VISIBLE);
        settingsPanel.post(() -> {
            settingsPanel.setTranslationY(-settingsPanel.getHeight());
            settingsPanel.setAlpha(0f);
            settingsPanel.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .start();
        });
    }

    private void hideSettingsPanel() {
        if (!settingsVisible) {
            return;
        }
        settingsVisible = false;
        settingsPanel.animate()
                .translationY(-settingsPanel.getHeight())
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> {
                    settingsPanel.setVisibility(View.GONE);
                    settingsPanel.setAlpha(1f);
                    settingsPanel.setTranslationY(0f);
                })
                .start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - fullscreen / cursor
    // ══════════════════════════════════════════════════════════════════════════

    private void toggleFullscreen() {
        boolean current = immersiveModeController != null && immersiveModeController.isFullscreen();
        setFullscreen(!current);
    }

    private void setFullscreen(boolean enable) {
        if (immersiveModeController != null) {
            immersiveModeController.setFullscreen(
                    enable,
                    BuildConfig.DEBUG,
                    topBar,
                    statusPanel,
                    this::hideSettingsPanel
            );
        }
    }

    private void enforceImmersiveModeIfNeeded() {
        if (immersiveModeController != null) {
            immersiveModeController.enforceImmersiveModeIfNeeded();
        }
    }

    private void setScreenAlwaysOn(boolean enable) {
        if (immersiveModeController != null) {
            immersiveModeController.setScreenAlwaysOn(enable);
        }
    }

    private void selectSimpleFps(int fps) {
        simpleFps = clamp(fps, 30, 144);
        refreshSimpleMenuButtons();
        scheduleSimpleMenuAutoHide();
    }

    private void showSimpleMenu() {
        if (simpleMenuPanel == null) {
            return;
        }
        simpleMode = "raw-png".equals(getSelectedEncoder()) ? "raw-png" : PREFERRED_VIDEO;
        simpleFps = clamp(getSelectedFps(), 30, 144);
        simpleMenuVisible = true;
        refreshSimpleMenuButtons();
        simpleMenuPanel.setVisibility(View.VISIBLE);
        scheduleSimpleMenuAutoHide();
    }

    private void hideSimpleMenu() {
        if (simpleMenuPanel == null) {
            return;
        }
        simpleMenuVisible = false;
        uiHandler.removeCallbacks(simpleMenuAutoHideTask);
        simpleMenuPanel.setVisibility(View.GONE);
    }

    private void toggleSimpleMenu() {
        if (simpleMenuVisible) {
            hideSimpleMenu();
        } else {
            showSimpleMenu();
        }
    }

    private void scheduleSimpleMenuAutoHide() {
        if (!simpleMenuVisible || simpleMenuPanel == null) {
            return;
        }
        uiHandler.removeCallbacks(simpleMenuAutoHideTask);
        uiHandler.postDelayed(simpleMenuAutoHideTask, SIMPLE_MENU_AUTO_HIDE_MS);
    }

    private void applySimpleMenuToSettings() {
        String selectedEncoder = "raw-png".equals(simpleMode) ? "raw-png" : PREFERRED_VIDEO;
        int selectedFps = clamp(simpleFps, 30, 144);

        setSpinnerSelection(encoderSpinner, ENCODER_OPTIONS, selectedEncoder);
        fpsSeek.setProgress(clamp(selectedFps, 24, 144) - 24);

        updateIntraOnlyButton();
        updateSettingValueLabels();
        updateHostHint();
    }

    private void refreshSimpleMenuButtons() {
        if (simpleMenuPanel == null) {
            return;
        }

        setSimpleModeSelected(simpleModeH265Button, PREFERRED_VIDEO.equals(simpleMode));
        setSimpleModeSelected(simpleModeRawButton, "raw-png".equals(simpleMode));

        setSimpleFpsSelected(simpleFps30Button, simpleFps == 30);
        setSimpleFpsSelected(simpleFps45Button, simpleFps == 45);
        setSimpleFpsSelected(simpleFps60Button, simpleFps == 60);
        setSimpleFpsSelected(simpleFps90Button, simpleFps == 90);
        setSimpleFpsSelected(simpleFps120Button, simpleFps == 120);
        setSimpleFpsSelected(simpleFps144Button, simpleFps == 144);
    }

    private void setSimpleModeSelected(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setAlpha(selected ? 1f : 0.75f);
    }

    private void setSimpleFpsSelected(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setAlpha(selected ? 1f : 0.75f);
    }

    private void toggleCursorOverlayMode() {
        if (cursorOverlayController == null) {
            return;
        }
        cursorOverlayController.toggleForCursorMode(getSelectedCursorMode());
        enforceCursorOverlayPolicy(true);
    }

    private void enforceCursorOverlayPolicy(boolean persist) {
        if (cursorOverlayController != null) {
            cursorOverlayController.applyPolicy(getSelectedCursorMode());
        }
        if (persist) {
            updateHostHint();
        }
    }

    private void updateCursorOverlay(float x, float y, int action) {
        if (cursorOverlayController != null) {
            cursorOverlayController.updateOverlay(x, y, action);
        }
    }

    private void hideCursorOverlay() {
        if (cursorOverlayController != null) {
            cursorOverlayController.hideOverlay();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - status text
    // ══════════════════════════════════════════════════════════════════════════

    private void updateStatus(String state, String info, long bps) {
        lastUiState = state == null ? STATE_IDLE : state;
        lastUiInfo = info == null ? "-" : info;
        lastUiBps = bps;
        refreshStatusText();
        refreshDebugInfoOverlay();
        if (STATE_ERROR.equals(lastUiState) && ErrorTextUtil.isCriticalUiInfo(lastUiInfo)) {
            long now = SystemClock.elapsedRealtime();
            boolean same = lastUiInfo.equals(lastCriticalErrorInfo);
            boolean stale = (now - lastCriticalErrorLogAtMs) > 30_000L;
            if (!same || stale) {
                lastCriticalErrorInfo = lastUiInfo;
                lastCriticalErrorLogAtMs = now;
                String line = "status=" + lastUiState + " info=" + lastUiInfo + " bps=" + bps;
                appendLiveLogError(line);
                Log.e(TAG, line);
            }
        }
    }

    private void appendLiveLogInfo(String line) {
        appendLiveLog("I", line);
    }

    private void appendLiveLogWarn(String line) {
        appendLiveLog("W", line);
    }

    private void appendLiveLogError(String line) {
        appendLiveLog("E", line);
    }

    private void appendLiveLog(String level, String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        Runnable task = () -> {
            if (liveLogText == null) {
                return;
            }

            int color = Color.parseColor("#9CA3AF");
            if ("I".equals(level)) {
                color = Color.parseColor("#166534");
            } else if ("W".equals(level)) {
                color = Color.parseColor("#F59E0B");
            } else if ("E".equals(level)) {
                color = Color.parseColor("#7F1D1D");
            }

            String text = "[" + level + "] " + line + "\n";
            int start = liveLogBuffer.length();
            liveLogBuffer.append(text);
            liveLogBuffer.setSpan(
                    new ForegroundColorSpan(color),
                    start,
                    liveLogBuffer.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            int linesToTrim = countLines(liveLogBuffer) - LIVE_LOG_MAX_LINES;
            while (linesToTrim > 0) {
                int newline = liveLogBuffer.toString().indexOf('\n');
                if (newline < 0) {
                    break;
                }
                liveLogBuffer.delete(0, newline + 1);
                linesToTrim--;
            }

            liveLogText.setText(liveLogBuffer);
            liveLogText.setVisibility(liveLogVisible ? View.VISIBLE : View.GONE);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            runOnUiThread(task);
        }
    }

    private int countLines(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private void refreshStatusText() {
        int color = ledColorForState(lastUiState);
        String daemonStateUi = effectiveDaemonState(
                daemonState, latestPresentFps, latestStreamUptimeSec, latestFrameOutHost);

        statusText.setText(lastUiState.toUpperCase(Locale.US));
        detailText.setText(StatusTextFormatter.buildTransportDetail(
                lastUiInfo,
                daemonReachable,
                daemonHostName,
                daemonStateUi
        ));
        bpsText.setText("throughput: " + StatusTextFormatter.formatBps(lastUiBps));

        if (statusLed.getBackground() instanceof GradientDrawable) {
            GradientDrawable drawable = (GradientDrawable) statusLed.getBackground().mutate();
            drawable.setColor(color);
        } else {
            statusLed.setBackgroundColor(color);
        }
    }

    private void updateStatsLine(String line) {
        lastStatsLine = line == null || line.trim().isEmpty()
            ? "fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -"
            : line;
        statsText.setText(lastStatsLine);
        refreshDebugInfoOverlay();
    }

    private void startDebugGraphSampling() {
        uiHandler.removeCallbacks(debugGraphSampleTask);
        uiHandler.post(debugGraphSampleTask);
    }

    private void stopDebugGraphSampling() {
        uiHandler.removeCallbacks(debugGraphSampleTask);
    }

    private boolean showHudWebHtml(String modeTag, String html) {
        if (perfHudWebView == null || html == null) {
            return false;
        }
        if (!modeTag.equals(hudOverlayMode) || !html.equals(lastHudWebHtml)) {
            perfHudWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            lastHudWebHtml = html;
        }
        hudOverlayMode = modeTag;
        perfHudWebView.setVisibility(View.VISIBLE);
        if (perfHudText != null) {
            perfHudText.setVisibility(View.GONE);
        }
        return true;
    }

    private void showHudTextOnly(String modeTag, String text, String colorHex) {
        hudOverlayMode = modeTag;
        lastHudWebHtml = "";
        if (perfHudWebView != null) {
            perfHudWebView.setVisibility(View.GONE);
        }
        if (perfHudText != null) {
            perfHudText.setText(text == null ? "" : text);
            perfHudText.setTextColor(Color.parseColor(colorHex));
            perfHudText.setVisibility(View.VISIBLE);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - perf HUD
    // ══════════════════════════════════════════════════════════════════════════

    private void updatePerfHudUnavailable() {
        if (perfHudText == null) {
            return;
        }
        latestTargetFps = getSelectedFps();
        latestPresentFps = 0.0;
        latestStreamUptimeSec = 0L;
        latestFrameOutHost = 0L;
        lastHudCompactLine = "hud: offline | waiting metrics";
        showHudTextOnly("offline", "HUD OFFLINE\nwaiting for host metrics...", "#FCA5A5");
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
        refreshDebugInfoOverlay();
        emitHudDebugAdb("state=offline waiting_metrics=1");
    }

    private void updatePerfHud(JSONObject metrics) {
        if (perfHudText == null) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (metrics == null) {
            if (daemonReachable
                    && lastPerfMetricsAtMs > 0L
                    && (nowMs - lastPerfMetricsAtMs) <= METRICS_STALE_GRACE_MS) {
                emitHudDebugAdb("state=metrics_stale grace=1");
                return;
            }
            updatePerfHudUnavailable();
            return;
        }
        lastPerfMetricsAtMs = nowMs;
        String connectionMode = metrics.optString("connection_mode", "live")
                .trim()
                .toLowerCase(Locale.US);
        boolean isTrainingConnection = "training".equals(connectionMode);
        JSONObject trainerHudJson = metrics.optJSONObject("trainer_hud_json");
        boolean trainerHudFromJson = trainerHudJson != null && trainerHudJson.length() > 0;
        String trainerHudText = metrics.optString("trainer_hud_text", "");
        boolean trainerHudFromText = trainerHudText != null && !trainerHudText.trim().isEmpty();
        boolean trainerHudFlag = metrics.optBoolean("trainer_hud_active", false);
        if (isTrainingConnection && (trainerHudFromJson || trainerHudFromText)) {
            lastTrainerHudPayloadAtMs = nowMs;
        }
        boolean trainerHudActive =
                isTrainingConnection && (trainerHudFlag || trainerHudFromJson || trainerHudFromText);
        if (!isTrainingConnection && (trainerHudFlag || trainerHudFromJson || trainerHudFromText)) {
            emitHudDebugAdb("trainer_payload_ignored connection_mode=" + connectionMode);
        }
        if (trainerHudActive && !trainerHudSessionActive) {
            trainerHudSessionActive = true;
            if (BuildConfig.DEBUG && !debugOverlayVisible) {
                setDebugOverlayVisible(true);
            }
        } else if (!trainerHudActive && trainerHudSessionActive) {
            trainerHudSessionActive = false;
        }

        if (isTrainingConnection && trainerHudFromJson) {
            renderTrainerHudOverlayJson(trainerHudJson);
            return;
        }
        if (isTrainingConnection && trainerHudFromText) {
            renderTrainerHudOverlay(trainerHudText);
            return;
        }
        if (isTrainingConnection && trainerHudActive) {
            if (lastTrainerHudPayloadAtMs > 0L
                    && (nowMs - lastTrainerHudPayloadAtMs) <= TRAINER_HUD_PAYLOAD_GRACE_MS) {
                emitHudDebugAdb("trainer_payload_gap grace=1 keep_last=1");
                return;
            }
            renderTrainerHudOverlayPlaceholder();
            return;
        }
        if (isTrainingConnection && trainerHudSessionActive) {
            if (lastTrainerHudPayloadAtMs > 0L
                    && (nowMs - lastTrainerHudPayloadAtMs) <= TRAINER_HUD_PAYLOAD_GRACE_MS) {
                emitHudDebugAdb("trainer_payload_missing grace=1 keep_last=1");
                return;
            }
            showHudTextOnly("trainer", "TRAINING HUD\nwaiting for trainer metrics...", "#B3EAF4FF");
            lastHudCompactLine = "trainer hud waiting metrics";
            refreshDebugInfoOverlay();
            return;
        }
        hudOverlayMode = "runtime";

        JSONObject kpi = metrics.optJSONObject("kpi");
        JSONObject latest = metrics.optJSONObject("latest_client_metrics");
        JSONObject limits = metrics.optJSONObject("queue_limits");
        long frameInHost = metrics.optLong("frame_in", 0);
        long frameOutHost = metrics.optLong("frame_out", 0);
        long streamUptimeSec = metrics.optLong("stream_uptime_sec", 0);

        double targetFps = kpi != null ? kpi.optDouble("target_fps", getSelectedFps()) : getSelectedFps();
        if (!Double.isFinite(targetFps) || targetFps <= 0.0) {
            targetFps = getSelectedFps();
        }
        double presentFps = kpi != null ? kpi.optDouble("present_fps", 0.0) : 0.0;
        double recvFps = kpi != null ? kpi.optDouble("recv_fps", 0.0) : 0.0;
        double decodeFps = kpi != null ? kpi.optDouble("decode_fps", 0.0) : 0.0;
        if (!Double.isFinite(presentFps) || presentFps < 0.0) {
            presentFps = 0.0;
        }
        if (presentFps < 1.0) {
            if (Double.isFinite(decodeFps) && decodeFps >= 1.0) {
                presentFps = decodeFps;
            } else if (Double.isFinite(recvFps) && recvFps >= 1.0) {
                presentFps = recvFps;
            }
        }
        boolean hasFlowSignals = streamUptimeSec > 0 || frameOutHost > 0 || recvFps >= 1.0 || decodeFps >= 1.0;
        if (presentFps >= 1.0) {
            latestStablePresentFps = presentFps;
            latestStablePresentFpsAtMs = nowMs;
        } else if (hasFlowSignals
                && latestStablePresentFps >= 1.0
                && (nowMs - latestStablePresentFpsAtMs) <= PRESENT_FPS_STALE_GRACE_MS) {
            presentFps = latestStablePresentFps;
        }
        String daemonStateUi = effectiveDaemonState(daemonState, presentFps, streamUptimeSec, frameOutHost);
        latestTargetFps = targetFps;
        latestPresentFps = presentFps;
        latestStreamUptimeSec = streamUptimeSec;
        latestFrameOutHost = frameOutHost;
        double frametimeP95 = kpi != null ? kpi.optDouble("frametime_ms_p95", 0.0) : 0.0;
        double decodeP95 = kpi != null ? kpi.optDouble("decode_time_ms_p95", 0.0) : 0.0;
        double renderP95 = kpi != null ? kpi.optDouble("render_time_ms_p95", 0.0) : 0.0;
        double e2eP95 = kpi != null ? kpi.optDouble("e2e_latency_ms_p95", 0.0) : 0.0;

        int qT = latest != null ? latest.optInt("transport_queue_depth", 0) : 0;
        int qD = latest != null ? latest.optInt("decode_queue_depth", 0) : 0;
        int qR = latest != null ? latest.optInt("render_queue_depth", 0) : 0;

        int qTMax = limits != null ? limits.optInt("transport_queue_max", TRANSPORT_QUEUE_MAX_FRAMES) : TRANSPORT_QUEUE_MAX_FRAMES;
        int qDMax = limits != null ? limits.optInt("decode_queue_max", DECODE_QUEUE_MAX_FRAMES) : DECODE_QUEUE_MAX_FRAMES;
        int qRMax = limits != null ? limits.optInt("render_queue_max", RENDER_QUEUE_MAX_FRAMES) : RENDER_QUEUE_MAX_FRAMES;

        int adaptiveLevel = metrics.optInt("adaptive_level", 0);
        String adaptiveAction = metrics.optString("adaptive_action", "hold");
        long drops = metrics.optLong("drops", 0);
        long bpHigh = metrics.optLong("backpressure_high_events", 0);
        long bpRecover = metrics.optLong("backpressure_recover_events", 0);
        String reason = metrics.optString("adaptive_reason", "");
        if (reason.length() > 44) {
            reason = reason.substring(0, 44) + "...";
        }

        boolean warmingUp = presentFps < 1.0 && streamUptimeSec < 5;
        String hud = String.format(
                Locale.US,
                "HUD %s\nfps %.0f/%.1f frame %.2fms\ndec %.2fms ren %.2fms e2e %.2fms\nq %d/%d/%d max %d/%d/%d\nadapt L%d %s\ndrops %d bp %d/%d\n%s",
                daemonReachable ? (warmingUp ? "WARM" : "LIVE") : "DEGRADED",
                targetFps,
                presentFps,
                frametimeP95,
                decodeP95,
                renderP95,
                e2eP95,
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                reason.isEmpty() ? "-" : reason
        );
        lastHudCompactLine = String.format(
                Locale.US,
                "hud fps %.0f/%.1f | e2e %.1fms | dec %.1fms | ren %.1fms | q %d/%d/%d",
                targetFps,
                presentFps,
                e2eP95,
                decodeP95,
                renderP95,
                qT,
                qD,
                qR
        );
        refreshDebugInfoOverlay();

        // Build explicit pressure reason so logcat shows exactly which condition fired.
        StringBuilder hpSb = new StringBuilder();
        double fpsFloor = targetFps * 0.90;
        boolean fpsUnderPressure = presentFps > 0.0 && presentFps < fpsFloor;
        boolean timingPressure = decodeP95 > 12.0 || renderP95 > 7.0;
        boolean queuePressure = qT >= qTMax || qD >= qDMax || qR >= qRMax;
        if (!warmingUp) {
            if (fpsUnderPressure) { hpSb.append("fps<").append(String.format(Locale.US, "%.1f", fpsFloor)); }
            if (decodeP95 > 12.0) { if (hpSb.length()>0) hpSb.append(','); hpSb.append("dec>12(").append(String.format(Locale.US,"%.1f",decodeP95)).append(")"); }
            if (renderP95 > 7.0)  { if (hpSb.length()>0) hpSb.append(','); hpSb.append("ren>7(").append(String.format(Locale.US,"%.1f",renderP95)).append(")"); }
            if (qT >= qTMax)      { if (hpSb.length()>0) hpSb.append(','); hpSb.append("qT=").append(qT).append("/").append(qTMax); }
            if (qD >= qDMax)      { if (hpSb.length()>0) hpSb.append(','); hpSb.append("qD=").append(qD).append("/").append(qDMax); }
            if (qR >= qRMax)      { if (hpSb.length()>0) hpSb.append(','); hpSb.append("qR=").append(qR).append("/").append(qRMax); }
        }
        String hpReason = hpSb.length() > 0 ? hpSb.toString() : (warmingUp ? "warmup" : "ok");

        // Red only for sustained heavy pressure with real FPS degradation.
        boolean highPressure = !warmingUp && fpsUnderPressure && (timingPressure || queuePressure);
        boolean mediumPressure = !warmingUp && (
                adaptiveAction.startsWith("degrade")
                        || fpsUnderPressure
                        || timingPressure
                        || queuePressure
        );
        String runtimeStateTone = "ok";
        if (highPressure) {
            runtimeStateTone = "risk";
            Log.w(TAG, "HUD RED warmingUp=" + warmingUp + " hp=" + hpReason
                    + " dec_p95=" + String.format(Locale.US, "%.2f", decodeP95)
                    + " ren_p95=" + String.format(Locale.US, "%.2f", renderP95)
                    + " qT=" + qT + "/" + qTMax
                    + " qD=" + qD + "/" + qDMax
                    + " qR=" + qR + "/" + qRMax
                    + " fps_present=" + String.format(Locale.US, "%.1f", presentFps)
                    + " stream_up=" + streamUptimeSec + "s");
        } else if (warmingUp || mediumPressure) {
            runtimeStateTone = "warn";
        }
        long latestDroppedFrames = latest != null ? latest.optLong("dropped_frames", -1L) : -1L;
        long latestTooLateFrames = latest != null ? latest.optLong("too_late_frames", 0L) : 0L;
        double dropPerSec = 0.0;
        if (latestDroppedFrames >= 0L) {
            long combined = latestDroppedFrames + Math.max(0L, latestTooLateFrames);
            if (runtimeDropPrevCount >= 0L && runtimeDropPrevAtMs > 0L && nowMs > runtimeDropPrevAtMs) {
                long deltaFrames = Math.max(0L, combined - runtimeDropPrevCount);
                long deltaMs = Math.max(1L, nowMs - runtimeDropPrevAtMs);
                dropPerSec = (deltaFrames * 1000.0) / deltaMs;
            }
            runtimeDropPrevCount = combined;
            runtimeDropPrevAtMs = nowMs;
        }
        double bitrateMbps = 0.0;
        if (latest != null) {
            long recvBps = latest.optLong("recv_bps", 0L);
            if (recvBps > 0L) {
                bitrateMbps = recvBps / 1_000_000.0;
            }
        }
        if (bitrateMbps <= 0.0) {
            bitrateMbps = metrics.optLong("bitrate_actual_bps", 0L) / 1_000_000.0;
        }
        appendSeriesSample(runtimePresentSeries, Math.max(0.0, presentFps));
        appendSeriesSample(runtimeMbpsSeries, Math.max(0.0, bitrateMbps));
        appendSeriesSample(runtimeDropSeries, Math.max(0.0, dropPerSec));
        appendSeriesSample(runtimeLatencySeries, Math.max(0.0, e2eP95));
        appendSeriesSample(runtimeQueueSeries, Math.max(0.0, qT + qD + qR));
        String runtimeChartsHtml = buildMetricTrendRowsHtml(
                seriesToJson(runtimePresentSeries),
                seriesToJson(runtimeMbpsSeries),
                seriesToJson(runtimeDropSeries),
                seriesToJson(runtimeLatencySeries),
                seriesToJson(runtimeQueueSeries),
                runtimeStateTone,
                runtimeStateTone,
                runtimeStateTone,
                runtimeStateTone,
                runtimeStateTone
        );
        renderRuntimeHudOverlay(
                daemonStateUi,
                targetFps,
                presentFps,
                recvFps,
                decodeFps,
                e2eP95,
                decodeP95,
                renderP95,
                frametimeP95,
                bitrateMbps,
                dropPerSec,
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                reason,
                runtimeChartsHtml,
                runtimeStateTone
        );

        String compact = String.format(
                Locale.US,
                "state=%s run_id=%d up=%ds stream_up=%ds host_in_out=%d/%d fps_target=%.0f fps_present=%.1f frame_p95=%.2f dec_p95=%.2f ren_p95=%.2f e2e_p95=%.2f q=%d/%d/%d qmax=%d/%d/%d adapt=L%d:%s drops=%d bp=%d/%d warmup=%b hp=%s reason=%s host_err=%s",
                daemonStateUi,
                daemonRunId,
                daemonUptimeSec,
                streamUptimeSec,
                frameInHost,
                frameOutHost,
                targetFps,
                presentFps,
                frametimeP95,
                decodeP95,
                renderP95,
                e2eP95,
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                warmingUp,
                hpReason,
                reason.isEmpty() ? "-" : reason,
                daemonLastError.isEmpty()
                        ? "-"
                        : (daemonLastError.length() > 44
                        ? daemonLastError.substring(0, 44) + "..."
                        : daemonLastError)
        );
        emitHudDebugAdb(compact);
    }

    private void renderRuntimeHudOverlay(
            String daemonStateUi,
            double targetFps,
            double presentFps,
            double recvFps,
            double decodeFps,
            double e2eP95,
            double decodeP95,
            double renderP95,
            double frametimeP95,
            double liveMbps,
            double dropsPerSec,
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax,
            int adaptiveLevel,
            String adaptiveAction,
            long drops,
            long bpHigh,
            long bpRecover,
            String reason,
            String metricChartsHtml,
            String tone
    ) {
        sampleDeviceResourceUsage(targetFps, renderP95);
        String resourceRows = buildResourceRowsHtml();
        if (perfHudWebView != null) {
            int[] streamSize = computeScaledSize();
            String streamMode = String.format(
                    Locale.US,
                    "%s | %dx%d | %.0ffps",
                    getSelectedEncoder().toUpperCase(Locale.US),
                    streamSize[0],
                    streamSize[1],
                    targetFps
            );
            String profileRev = safeText(daemonBuildRevision).equals("-")
                    ? safeText(BuildConfig.WBEAM_BUILD_REV)
                    : safeText(daemonBuildRevision);
            StringBuilder chips = new StringBuilder();
            chips.append(hudChip("CURRENT PROFILE", getSelectedProfile(), ""));
            chips.append(hudChip("PROFILE REV", profileRev, ""));
            chips.append(hudChip("STREAM MODE", streamMode, ""));
            chips.append(hudChip("DEVICE", daemonHostName, ""));
            chips.append(hudChip("STATE", daemonStateUi, hudToneClass(tone)));

            StringBuilder cards = new StringBuilder();
            cards.append(hudCard("PRESENT FPS", String.format(Locale.US, "%.1f", presentFps), hudToneClass(tone)));
            cards.append(hudCard("RECV FPS", String.format(Locale.US, "%.1f", recvFps), ""));
            cards.append(hudCard("DECODE FPS", String.format(Locale.US, "%.1f", decodeFps), ""));
            cards.append(hudCard("LIVE MBPS", fmtDoubleOrPlaceholder(liveMbps, "%.2f", "PENDING"), hudToneClass(tone)));
            cards.append(hudCard("E2E p95", String.format(Locale.US, "%.1f ms", e2eP95), hudToneClass(tone)));
            cards.append(hudCard("Decode p95", String.format(Locale.US, "%.2f ms", decodeP95), ""));
            cards.append(hudCard("Render p95", String.format(Locale.US, "%.2f ms", renderP95), ""));
            cards.append(hudCard("Frame p95", String.format(Locale.US, "%.2f ms", frametimeP95), ""));
            cards.append(hudCard("Drops / s", fmtDoubleOrPlaceholder(dropsPerSec, "%.3f", "PENDING"), hudToneClass(tone)));
            cards.append(hudCard("Drops total", String.valueOf(drops), ""));

            StringBuilder details = new StringBuilder();
            details.append(hudDetailRow("Adaptive", "L" + adaptiveLevel + " " + safeText(adaptiveAction)));
            details.append(hudDetailRow("Transport queue", qT + "/" + qTMax));
            details.append(hudDetailRow("Decode queue", qD + "/" + qDMax));
            details.append(hudDetailRow("Render queue", qR + "/" + qRMax));
            details.append(hudDetailRow("Backpressure", bpHigh + "/" + bpRecover));
            details.append(hudDetailRow("Connection mode", "runtime"));
            details.append(hudDetailRow("Reason", safeText(reason)));
            details.append(hudDetailRow("Host error", safeText(daemonLastError)));
            details.append(hudDetailRow("App build", safeText(BuildConfig.WBEAM_BUILD_REV)));
            details.append(hudDetailRow("Daemon build", safeText(daemonBuildRevision)));

            String trend = "runtime health=" + tone.toUpperCase(Locale.US)
                    + " | recv=" + String.format(Locale.US, "%.1f", recvFps)
                    + " decode=" + String.format(Locale.US, "%.1f", decodeFps)
                    + " present=" + String.format(Locale.US, "%.1f", presentFps)
                    + " | drops=" + drops;

            String html = buildRuntimeHudHtml(
                    chips.toString(),
                    cards.toString(),
                    metricChartsHtml == null ? "" : metricChartsHtml,
                    trend,
                    details.toString(),
                    resourceRows,
                    "scale-1x"
            );
            if (!showHudWebHtml("runtime", html) && perfHudText != null) {
                String hud = String.format(
                        Locale.US,
                        "HUD %s\nfps %.0f/%.1f frame %.2fms\ndec %.2fms ren %.2fms e2e %.2fms\nq %d/%d/%d max %d/%d/%d\nadapt L%d %s\ndrops %d bp %d/%d\n%s",
                        daemonReachable ? "LIVE" : "DEGRADED",
                        targetFps,
                        presentFps,
                        frametimeP95,
                        decodeP95,
                        renderP95,
                        e2eP95,
                        qT,
                        qD,
                        qR,
                        qTMax,
                        qDMax,
                        qRMax,
                        adaptiveLevel,
                        adaptiveAction,
                        drops,
                        bpHigh,
                        bpRecover,
                        reason == null || reason.isEmpty() ? "-" : reason
                );
                showHudTextOnly("runtime", hud, "#B3EAF4FF");
            }
        } else if (perfHudText != null) {
            String hud = String.format(
                    Locale.US,
                    "HUD %s\nfps %.0f/%.1f frame %.2fms\ndec %.2fms ren %.2fms e2e %.2fms\nq %d/%d/%d max %d/%d/%d\nadapt L%d %s\ndrops %d bp %d/%d\n%s",
                    daemonReachable ? "LIVE" : "DEGRADED",
                    targetFps,
                    presentFps,
                    frametimeP95,
                    decodeP95,
                    renderP95,
                    e2eP95,
                    qT,
                    qD,
                    qR,
                    qTMax,
                    qDMax,
                    qRMax,
                    adaptiveLevel,
                    adaptiveAction,
                    drops,
                    bpHigh,
                    bpRecover,
                    reason == null || reason.isEmpty() ? "-" : reason
            );
            showHudTextOnly("runtime", hud, "#B3EAF4FF");
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
    }

    private void renderTrainerHudOverlay(String rawHudText) {
        if (perfHudText == null) {
            return;
        }
        String hudText = rawHudText == null ? "" : rawHudText.replace("\r", "");
        hudText = hudText.replace("[MAIN]\n", "").replace("[MAIN]", "").trim();
        if (hudText.isEmpty()) {
            return;
        }

        String progressLine = buildTrainerProgressLine(hudText);
        int progressPercent = parseTrainerProgressPercent(hudText);
        if (perfHudWebView != null) {
            String html = buildTrainerHudHtml(hudText, progressLine, progressPercent);
            if (!showHudWebHtml("trainer", html)) {
                String finalText = progressLine.isEmpty() ? hudText : progressLine + "\n" + hudText;
                showHudTextOnly("trainer", finalText, "#B3EAF4FF");
            }
        } else {
            String finalText = progressLine.isEmpty() ? hudText : progressLine + "\n" + hudText;
            showHudTextOnly("trainer", finalText, "#B3EAF4FF");
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
        lastHudCompactLine = progressLine.isEmpty() ? "hud: trainer overlay active" : progressLine;
        refreshDebugInfoOverlay();
    }

    private void renderTrainerHudOverlayJson(JSONObject hudJson) {
        if (perfHudText == null || hudJson == null) {
            return;
        }
        int progressPercent = hudJson.optInt("progress_percent", -1);
        String progressText = String.format(
                Locale.US,
                "TRAINING PROGRESS %d%%  (trial %d/%d)",
                Math.max(0, progressPercent),
                hudJson.optInt("trial_index", 0),
                hudJson.optInt("trial_total", 0)
        );
        String html = buildTrainerHudHtmlFromJson(hudJson, progressText, progressPercent);
        if (perfHudWebView != null) {
            if (!showHudWebHtml("trainer", html)) {
                showHudTextOnly("trainer", progressText, "#B3EAF4FF");
            }
        } else {
            showHudTextOnly("trainer", progressText, "#B3EAF4FF");
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
        lastHudCompactLine = progressText;
        refreshDebugInfoOverlay();
    }

    private void renderTrainerHudOverlayPlaceholder() {
        if (perfHudText == null) {
            return;
        }
        sampleDeviceResourceUsage(latestTargetFps > 1.0 ? latestTargetFps : 60.0, 0.0);
        String resourceRows = buildResourceRowsHtml();
        String html = buildTrainerHudSotHtml(
                "PENDING",
                "PENDING",
                "PENDING",
                "PENDING",
                0,
                0,
                0,
                0,
                0,
                "T0",
                0,
                "TRAINING PROGRESS ...",
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                0,
                "PENDING",
                Double.NaN,
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "trainer feed pending | placeholders visible",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resourceRows,
                "wide",
                "arcade"
        );
        if (perfHudWebView != null) {
            if (!showHudWebHtml("trainer", html)) {
                showHudTextOnly("trainer", "TRAINER HUD PENDING\nplaceholder layout active", "#B3EAF4FF");
            }
        } else {
            showHudTextOnly("trainer", "TRAINER HUD PENDING\nplaceholder layout active", "#B3EAF4FF");
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
        lastHudCompactLine = "trainer hud pending placeholders";
        refreshDebugInfoOverlay();
    }

    private String buildTrainerProgressLine(String hudText) {
        Matcher matcher = TRAINER_TRIAL_PROGRESS_RE.matcher(hudText);
        if (!matcher.find()) {
            return "";
        }
        int cur;
        int total;
        try {
            cur = Integer.parseInt(matcher.group(1));
            total = Integer.parseInt(matcher.group(2));
        } catch (Exception ignored) {
            return "";
        }
        if (total <= 0 || cur <= 0) {
            return "";
        }
        int pct = Math.max(0, Math.min(100, (int) Math.round((cur * 100.0) / total)));
        return String.format(Locale.US, "TRAINING PROGRESS %d%%  (trial %d/%d)", pct, cur, total);
    }

    private int parseTrainerProgressPercent(String hudText) {
        Matcher matcher = TRAINER_TRIAL_PROGRESS_RE.matcher(hudText == null ? "" : hudText);
        if (!matcher.find()) {
            return -1;
        }
        try {
            int cur = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            if (cur <= 0 || total <= 0) {
                return -1;
            }
            return Math.max(0, Math.min(100, (int) Math.round((cur * 100.0) / total)));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String buildTrainerHudHtml(String hudText, String progressLine, int progressPercent) {
        StringBuilder details = new StringBuilder();
        String[] lines = hudText.split("\n");
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("+") && line.endsWith("+")) {
                details.append(hudDetailRow(" ", " "));
                continue;
            }
            if (line.startsWith("|") && line.endsWith("|") && line.length() > 2) {
                String content = line.substring(1, line.length() - 1).trim();
                String[] parts = splitHudColumns(content);
                details.append(hudDetailRow(parts[0], parts[1]));
            } else {
                details.append(hudDetailRow(line, "-"));
            }
        }
        sampleDeviceResourceUsage(latestTargetFps > 1.0 ? latestTargetFps : 60.0, 0.0);
        String resourceRows = buildResourceRowsHtml();
        return buildTrainerHudSotHtml(
                "TEXT-SNAPSHOT",
                "PENDING",
                "PENDING",
                "PENDING",
                0,
                0,
                0,
                0,
                0,
                "T0",
                progressPercent,
                progressLine,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                0,
                "PENDING",
                Double.NaN,
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "text snapshot mode | " + details.toString().replaceAll("<[^>]+>", " ").trim(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resourceRows,
                "wide",
                "arcade"
        );
    }

    private String buildTrainerHudHtmlFromJson(JSONObject hud, String progressLine, int progressPercent) {
        JSONObject sections = hud.optJSONObject("sections");
        JSONObject header = sections != null ? sections.optJSONObject("header") : null;
        JSONObject config = sections != null ? sections.optJSONObject("config") : null;
        JSONObject kpi = sections != null ? sections.optJSONObject("kpi") : null;
        JSONObject states = sections != null ? sections.optJSONObject("states") : null;
        JSONObject trends = sections != null ? sections.optJSONObject("trends") : null;
        JSONObject status = sections != null ? sections.optJSONObject("status") : null;

        String runId = header != null ? header.optString("run_id", hud.optString("run_id", "-")) : hud.optString("run_id", "-");
        String profile = header != null ? header.optString("profile_name", hud.optString("profile_name", "-")) : hud.optString("profile_name", "-");
        String trialId = header != null ? header.optString("trial_id", hud.optString("trial_id", "-")) : hud.optString("trial_id", "-");
        int gIdx = header != null ? header.optInt("generation_index", 0) : hud.optInt("generation_index", 0);
        int gTotal = header != null ? header.optInt("generation_total", 0) : hud.optInt("generation_total", 0);
        int tIdx = header != null ? header.optInt("trial_index", 0) : hud.optInt("trial_index", 0);
        int tTotal = header != null ? header.optInt("trial_total", 0) : hud.optInt("trial_total", 0);

        String encoder = config != null ? config.optString("encoder", "-") : "-";
        String size = config != null ? config.optString("size", "-") : "-";
        String fontProfile = config != null ? config.optString("font_profile", "arcade") : "arcade";
        int fps = config != null ? config.optInt("fps", 0) : 0;
        double targetMbps = config != null ? config.optDouble("target_mbps", 0.0) : 0.0;
        String layoutMode = config != null ? config.optString("layout_mode", hud.optString("layout_mode", "wide")) : hud.optString("layout_mode", "wide");

        double score = kpi != null ? kpi.optDouble("score", Double.NaN) : Double.NaN;
        double present = kpi != null ? kpi.optDouble("present_fps", Double.NaN) : Double.NaN;
        double recv = kpi != null ? kpi.optDouble("recv_fps", Double.NaN) : Double.NaN;
        double decode = kpi != null ? kpi.optDouble("decode_fps", Double.NaN) : Double.NaN;
        double liveMbps = kpi != null ? kpi.optDouble("live_mbps", Double.NaN) : Double.NaN;
        JSONObject metricsObj = hud.optJSONObject("metrics");
        if (Double.isNaN(liveMbps) || liveMbps <= 0.0) {
            liveMbps = hud.optDouble("bitrate_mbps_mean", Double.NaN);
        }
        if ((Double.isNaN(liveMbps) || liveMbps <= 0.0) && metricsObj != null) {
            liveMbps = metricsObj.optDouble("bitrate_mbps_mean", Double.NaN);
        }
        double latency = kpi != null ? kpi.optDouble("latency_ms_p95", Double.NaN) : Double.NaN;
        double drops = kpi != null ? kpi.optDouble("drops_per_sec", Double.NaN) : Double.NaN;
        double queue = kpi != null ? kpi.optDouble("queue_depth", Double.NaN) : Double.NaN;
        double renderMs = kpi != null ? kpi.optDouble("render_time_ms_p95", Double.NaN) : Double.NaN;
        if (Double.isNaN(renderMs)) {
            renderMs = kpi != null ? kpi.optDouble("render_ms_p95", 0.0) : 0.0;
        }

        String fpsState = states != null ? states.optString("fps", "PENDING").toLowerCase(Locale.US) : "pending";
        String latState = states != null ? states.optString("latency", "PENDING").toLowerCase(Locale.US) : "pending";
        String mbpsState = states != null ? states.optString("mbps", "PENDING").toLowerCase(Locale.US) : "pending";
        String dropState = states != null ? states.optString("drop", "PENDING").toLowerCase(Locale.US) : "pending";
        String queueState = states != null ? states.optString("queue", "PENDING").toLowerCase(Locale.US) : "pending";
        String qualityState = states != null ? states.optString("quality", "PENDING").toLowerCase(Locale.US) : "pending";

        JSONArray trendScoreArr = trends != null ? trends.optJSONArray("score") : null;
        JSONArray trendFpsArr = trends != null ? trends.optJSONArray("present_fps") : null;
        JSONArray trendRecvArr = trends != null ? trends.optJSONArray("recv_fps") : null;
        JSONArray trendDecodeArr = trends != null ? trends.optJSONArray("decode_fps") : null;
        JSONArray trendMbpsArr = trends != null ? trends.optJSONArray("mbps") : null;
        JSONArray trendDropArr = trends != null ? trends.optJSONArray("drop_per_sec") : null;
        if ((trendDropArr == null || trendDropArr.length() == 0) && trends != null) {
            trendDropArr = trends.optJSONArray("drop_pct");
        }
        JSONArray trendLatencyArr = trends != null ? trends.optJSONArray("latency_ms_p95") : null;
        JSONArray trendQueueArr = trends != null ? trends.optJSONArray("queue_depth") : null;
        JSONArray trendLateArr = trends != null ? trends.optJSONArray("late_per_sec") : null;
        double late = kpi != null ? kpi.optDouble("late_per_sec", Double.NaN) : Double.NaN;
        if (Double.isNaN(liveMbps) || liveMbps <= 0.0) {
            liveMbps = latestFiniteFromSeries(trendMbpsArr);
        }
        if (Double.isNaN(present) || present <= 0.0) {
            present = latestFiniteFromSeries(trendFpsArr);
        }
        if (Double.isNaN(recv) || recv <= 0.0) {
            recv = latestFiniteFromSeries(trendRecvArr);
        }
        if (Double.isNaN(decode) || decode <= 0.0) {
            decode = latestFiniteFromSeries(trendDecodeArr);
        }
        if (Double.isNaN(drops) || drops < 0.0) {
            drops = latestFiniteFromSeries(trendDropArr);
        }
        if (Double.isNaN(latency) || latency < 0.0) {
            latency = latestFiniteFromSeries(trendLatencyArr);
        }
        if (Double.isNaN(queue) || queue < 0.0) {
            queue = latestFiniteFromSeries(trendQueueArr);
        }
        if (Double.isNaN(late) || late < 0.0) {
            late = latestFiniteFromSeries(trendLateArr);
        }
        String statusNote = status != null ? status.optString("note", "") : "";
        int sampleCount = status != null ? Math.max(0, status.optInt("sample_count", 0)) : 0;
        String bestTrial = config != null ? config.optString("best_trial", "-") : "-";
        double bestScore = config != null ? config.optDouble("best_score", Double.NaN) : Double.NaN;
        sampleDeviceResourceUsage(fps > 1 ? fps : (latestTargetFps > 1.0 ? latestTargetFps : 60.0), Double.isNaN(renderMs) ? 0.0 : renderMs);
        String resourceRows = buildResourceRowsHtml();
        return buildTrainerHudSotHtml(
                runId,
                profile,
                encoder,
                size,
                Math.max(0, fps),
                gIdx,
                gTotal,
                tIdx,
                tTotal,
                trialId,
                progressPercent,
                progressLine,
                score,
                present,
                recv,
                decode,
                liveMbps,
                latency,
                drops,
                queue,
                late,
                sampleCount,
                bestTrial,
                bestScore,
                fpsState,
                mbpsState,
                latState,
                dropState,
                queueState,
                qualityState,
                statusNote,
                trendScoreArr,
                trendFpsArr,
                trendMbpsArr,
                trendLatencyArr,
                trendDropArr,
                trendQueueArr,
                trendRecvArr,
                trendDecodeArr,
                resourceRows,
                layoutMode,
                fontProfile
        );
    }

    private String buildRuntimeHudHtml(
            String chipsHtml,
            String cardsHtml,
            String chartsHtml,
            String trendText,
            String detailsRowsHtml,
            String resourceRowsHtml,
            String scaleClass
    ) {
        String bodyClass = "hud-live " + safeText(scaleClass);
        return "<!doctype html><html><head><meta charset='utf-8'/>"
                + "<meta name='viewport' content='width=device-width,height=device-height,initial-scale=1,maximum-scale=1,viewport-fit=cover'/>"
                + "<style>"
                + "html,body{margin:0;padding:0;background:transparent;color:#ecfbff;font-family:'JetBrains Mono','IBM Plex Mono',monospace;font-size:14px;width:100%;height:100%;}"
                + ".root{position:fixed;inset:0;padding:6px;box-sizing:border-box;display:grid;grid-template-rows:auto minmax(0,1fr);gap:6px;}"
                + ".top{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:6px;}"
                + ".chip{border:1px solid rgba(126,245,255,.52);background:rgba(6,24,31,.82);padding:8px 10px;min-height:42px;}"
                + ".chip .k{font-size:11px;color:#9dddea;display:block;letter-spacing:.05em;}"
                + ".chip .v{font-size:14px;color:#ecfbff;word-break:break-word;font-weight:700;}"
                + ".main{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);gap:6px;min-height:0;}"
                + ".panel{border:1px solid rgba(126,245,255,.5);background:rgba(6,24,31,.88);padding:8px;min-height:0;overflow:auto;}"
                + ".panel-main{display:grid;grid-template-rows:auto minmax(0,1fr) auto auto;gap:8px;overflow:hidden;}"
                + ".panel-side{overflow:auto;}"
                + ".kpi{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;}"
                + ".kpi .item{border:1px solid rgba(126,245,255,.42);padding:8px;background:rgba(0,0,0,.4);}"
                + ".kpi .item .k{font-size:11px;color:#9dddea;display:block;}"
                + ".kpi .item .v{font-size:18px;color:#dcf9ff;font-weight:700;line-height:1.15;}"
                + ".trend{font-size:11px;line-height:1.35;margin-top:6px;color:#9dddea;word-break:break-word;}"
                + ".metric-trends{margin-top:0;border:1px solid rgba(126,245,255,.35);padding:8px;background:rgba(2,10,14,.45);display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;min-height:0;overflow:auto;}"
                + ".trend-row{display:grid;grid-template-columns:1fr;gap:6px;}"
                + ".trend-label{font-size:11px;color:#9dddea;letter-spacing:.04em;}"
                + ".trend-range{font-size:12px;color:#d6fbff;text-align:right;white-space:nowrap;font-weight:700;}"
                + ".trend-card{border:1px solid rgba(126,245,255,.28);padding:6px;background:rgba(0,0,0,.24);display:grid;grid-template-rows:auto minmax(0,1fr);gap:6px;min-width:0;min-height:124px;}"
                + ".trend-head{display:flex;align-items:center;justify-content:space-between;gap:6px;}"
                + ".trend-meta{display:grid;grid-template-rows:auto auto;gap:4px;}"
                + ".trend-meta-row{display:grid;grid-template-columns:1fr 1fr 1fr;align-items:center;gap:6px;}"
                + ".trend-meta-item{font-size:13px;font-weight:700;color:#d6fbff;}"
                + ".trend-meta-item.mid{text-align:center;}"
                + ".trend-meta-item.high{text-align:right;}"
                + ".trend-meta-cur{font-size:12px;color:#b9f8ff;font-weight:700;}"
                + ".resource{margin-top:7px;border:1px solid rgba(126,245,255,.35);padding:6px;background:rgba(2,10,14,.35);display:grid;gap:5px;}"
                + ".resource .title{font-size:10px;color:#9dddea;letter-spacing:.05em;}"
                + ".res-row{display:grid;grid-template-columns:42px 62px minmax(0,1fr);align-items:center;gap:6px;}"
                + ".res-row .rk{font-size:10px;color:#9dddea;}"
                + ".res-row .rv{font-size:11px;color:#dcf9ff;}"
                + ".spark{height:92px;display:flex;align-items:flex-end;gap:1px;border:1px solid rgba(126,245,255,.24);padding:2px;background:rgba(0,0,0,.26);overflow:hidden;min-width:0;}"
                + ".spark-svg{width:100%;height:100%;display:block;}"
                + ".spark-bar{flex:1 1 0;min-width:2px;border-radius:2px 2px 0 0;background:linear-gradient(180deg,rgba(110,231,183,.96),rgba(110,231,183,.2));}"
                + ".spark-bar.state-warn{background:linear-gradient(180deg,rgba(251,191,36,.96),rgba(251,191,36,.2));}"
                + ".spark-bar.state-risk{background:linear-gradient(180deg,rgba(248,113,113,.96),rgba(248,113,113,.2));}"
                + ".detail-table{width:100%;border-collapse:collapse;table-layout:fixed;}"
                + ".detail-table td{border:1px solid rgba(126,245,255,.36);padding:4px 6px;vertical-align:top;word-break:break-word;}"
                + ".detail-table td:first-child{width:52%;color:#dffcff;} .detail-table td:last-child{text-align:right;color:#b9f8ff;}"
                + ".state-ok{color:#6ee7b7;} .state-warn{color:#fbbf24;} .state-risk{color:#f87171;} .state-pending{color:#94a3b8;}"
                + "@media (max-width:980px){.main{grid-template-columns:1fr;}.metric-trends{grid-template-columns:1fr;}.spark{height:110px;}}"
                + "</style></head><body class='" + bodyClass + "'><div class='root'>"
                + "<div class='top'>"
                + hudChip("HUD MODE", "RUNTIME", "")
                + chipsHtml
                + "</div>"
                + "<div class='main'>"
                + "<div class='panel panel-main'><div class='kpi'>" + cardsHtml + "</div><div class='metric-trends'>" + chartsHtml + "</div><div class='trend'>" + escapeHtml(safeText(trendText)) + "</div><div class='resource'><div class='title'>DEVICE RESOURCES (* GPU proxy from render time)</div>" + resourceRowsHtml + "</div></div>"
                + "<div class='panel panel-side'><table class='detail-table'>" + detailsRowsHtml + "</table></div>"
                + "</div>"
                + "</div></body></html>";
    }

    private String buildTrainerHudSotHtml(
            String runId,
            String profile,
            String encoder,
            String size,
            int fps,
            int gIdx,
            int gTotal,
            int tIdx,
            int tTotal,
            String trialId,
            int progressPercent,
            String progressLine,
            double score,
            double present,
            double recv,
            double decode,
            double liveMbps,
            double latency,
            double drops,
            double queue,
            double late,
            int sampleCount,
            String bestTrial,
            double bestScore,
            String fpsState,
            String mbpsState,
            String latState,
            String dropState,
            String queueState,
            String qualityState,
            String statusNote,
            JSONArray trendScore,
            JSONArray trendPresent,
            JSONArray trendMbps,
            JSONArray trendLatency,
            JSONArray trendDrops,
            JSONArray trendQueue,
            JSONArray trendRecv,
            JSONArray trendDecode,
            String resourceRowsHtml,
            String layoutMode,
            String fontProfile
    ) {
        int pct = progressPercent;
        if (pct < 0 && tTotal > 0) {
            pct = (int) Math.round((Math.max(0, tIdx) * 100.0) / Math.max(1, tTotal));
        }
        pct = clampPercent(pct);
        String progressText = progressLine == null || progressLine.trim().isEmpty()
                ? String.format(Locale.US, "TRAINING PROGRESS %d%%", pct)
                : progressLine.trim();
        String modeLine = safeText(encoder).toUpperCase(Locale.US)
                + " | " + safeText(size)
                + " | " + Math.max(0, fps) + "fps";
        String liveTriplet = String.format(
                Locale.US,
                "P/R/D %s / %s / %s",
                fmtDoubleOrPlaceholder(present, "%.1f", "PENDING"),
                fmtDoubleOrPlaceholder(recv, "%.1f", "PENDING"),
                fmtDoubleOrPlaceholder(decode, "%.1f", "PENDING")
        );
        String statusLine1 = "best_trial: " + safeText(bestTrial) + "    best_score: " + fmtDoubleOrPlaceholder(bestScore, "%.2f", "PENDING");
        String statusLine2 = "sample_count: " + sampleCount + "    state.fps: " + safeText(fpsState);
        String statusLine3 = "state.mbps: " + safeText(mbpsState) + "    state.latency: " + safeText(latState);
        String statusLine4 = "state.drop: " + safeText(dropState) + "    state.queue: " + safeText(queueState);
        String statusLine5 = "quality: " + safeText(qualityState) + "    note: " + safeText(statusNote);

        String trendGrid = buildSotTrendCellHtml("SCORE TREND", trendScore, hudToneClass(qualityState), "")
                + buildSotTrendCellHtml("PRESENT FPS TREND", trendPresent, hudToneClass(fpsState), "")
                + buildSotTrendCellHtml("LIVE MBPS TREND", trendMbps, hudToneClass(mbpsState), "Mbps")
                + buildSotTrendCellHtml("LAT p95 TREND", trendLatency, hudToneClass(latState), "ms")
                + buildSotTrendCellHtml("DROPS/s TREND", trendDrops, hudToneClass(dropState), "")
                + buildSotTrendCellHtml("QUEUE DEPTH TREND", trendQueue, hudToneClass(queueState), "")
                + buildSotTrendCellHtml("RECV FPS TREND", trendRecv, hudToneClass(fpsState), "")
                + buildSotTrendCellHtml("DECODE FPS TREND", trendDecode, hudToneClass(fpsState), "");

        String hudShellClass = "sot shell " + trainerScaleClass(fontProfile);
        return "<!doctype html><html><head><meta charset='utf-8'/>"
                + "<meta name='viewport' content='width=device-width,height=device-height,initial-scale=1,maximum-scale=1,viewport-fit=cover'/>"
                + "<style>"
                + "html,body{margin:0;padding:0;background:transparent;color:#ecfbff;font-family:'JetBrains Mono','IBM Plex Mono',monospace;width:100%;height:100%;}"
                + ".sot.shell{position:fixed;inset:0;padding:6px;box-sizing:border-box;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:6px;}"
                + ".sot .panel{background:rgba(4,25,34,.78);border:1px solid rgba(110,242,255,.34);border-radius:8px;padding:8px;min-width:0;}"
                + ".sot .header{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:6px;}"
                + ".sot .chip{background:rgba(6,39,55,.85);border:1px solid rgba(124,236,255,.42);border-radius:6px;padding:6px 8px;min-width:0;}"
                + ".sot .chip .k{display:block;font-size:11px;color:#9fe8f2;letter-spacing:.04em;}"
                + ".sot .chip .v{display:block;font-size:16px;color:#f0fdff;font-weight:700;line-height:1.15;word-break:break-word;}"
                + ".sot .main{display:grid;grid-template-columns:42% 58%;gap:6px;min-height:0;}"
                + ".sot .left-col,.sot .right-col{display:grid;gap:6px;min-height:0;min-width:0;}"
                + ".sot .left-col{grid-template-rows:repeat(3,minmax(98px,auto)) minmax(0,1fr);}"
                + ".sot .kpi-block{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:8px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;}"
                + ".sot .kpi-item .k{display:block;font-size:11px;color:#9fe8f2;}"
                + ".sot .kpi-item .v{display:block;font-size:20px;color:#f0fdff;font-weight:700;line-height:1.15;word-break:break-word;}"
                + ".sot .status{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:8px;display:grid;gap:5px;align-content:start;}"
                + ".sot .status .row{font-size:12px;color:#c8f7ff;line-height:1.28;word-break:break-word;}"
                + ".sot .trend-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;min-height:0;align-content:stretch;grid-auto-rows:minmax(128px,1fr);}"
                + ".sot .trend-cell{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:7px;display:grid;grid-template-rows:auto minmax(0,1fr);gap:6px;min-width:0;min-height:128px;}"
                + ".sot .trend-head{display:flex;justify-content:space-between;align-items:center;gap:8px;min-width:0;}"
                + ".sot .trend-title{font-size:11px;color:#9fe8f2;letter-spacing:.03em;}"
                + ".sot .trend-stats{font-size:12px;color:#d8fbff;white-space:nowrap;font-weight:700;}"
                + ".sot .spark{height:94px;display:grid;grid-auto-flow:column;grid-auto-columns:minmax(0,1fr);align-items:end;gap:1px;border:1px solid rgba(114,231,255,.3);background:rgba(2,12,18,.95);padding:2px;overflow:hidden;min-width:0;}"
                + ".sot .spark-svg{width:100%;height:100%;display:block;}"
                + ".sot .trend-meta{display:grid;grid-template-rows:auto auto;gap:4px;}"
                + ".sot .trend-meta-row{display:grid;grid-template-columns:1fr 1fr 1fr;align-items:center;gap:6px;}"
                + ".sot .trend-meta-item{font-size:13px;font-weight:700;color:#d8fbff;}"
                + ".sot .trend-meta-item.mid{text-align:center;}"
                + ".sot .trend-meta-item.high{text-align:right;}"
                + ".sot .trend-meta-cur{font-size:12px;color:#c8f7ff;font-weight:700;}"
                + ".sot .spark .spark-bar{width:100%;min-width:0;border-radius:2px 2px 0 0;background:linear-gradient(180deg,rgba(141,217,255,.96),rgba(141,217,255,.24));}"
                + ".sot .spark .spark-bar.state-warn{background:linear-gradient(180deg,rgba(251,191,36,.96),rgba(251,191,36,.24));}"
                + ".sot .spark .spark-bar.state-risk{background:linear-gradient(180deg,rgba(248,113,113,.96),rgba(248,113,113,.24));}"
                + ".sot .ok{color:#6ee7b7;} .sot .warn{color:#fbbf24;} .sot .risk{color:#f87171;}"
                + ".sot .state-ok{color:#6ee7b7;} .sot .state-warn{color:#fbbf24;} .sot .state-risk{color:#f87171;} .sot .state-pending{color:#94a3b8;}"
                + ".sot .res-row{display:grid;grid-template-columns:46px 70px minmax(0,1fr);align-items:center;gap:6px;margin-top:4px;}"
                + ".sot .res-row .rk{font-size:11px;color:#9fe8f2;}"
                + ".sot .res-row .rv{font-size:12px;color:#f0fdff;}"
                + ".sot .footer{display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px;}"
                + ".sot .foot{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:6px 8px;font-size:12px;color:#c8f7ff;line-height:1.3;word-break:break-word;}"
                + ".sot.scale-2x .chip .k{font-size:12px;}.sot.scale-2x .chip .v{font-size:20px;}.sot.scale-2x .kpi-item .k{font-size:12px;}.sot.scale-2x .kpi-item .v{font-size:24px;}.sot.scale-2x .status .row{font-size:14px;}.sot.scale-2x .trend-title{font-size:13px;}.sot.scale-2x .trend-stats{font-size:12px;}.sot.scale-2x .spark{height:110px;}"
                + "@media (max-width:1200px){.sot .main{grid-template-columns:1fr;}.sot .trend-grid{grid-template-columns:1fr;}.sot .footer{grid-template-columns:1fr;}}"
                + "</style></head><body class='hud-trainer'><div class='" + hudShellClass + "'>"
                + "<div class='panel header'>"
                + "<div class='chip'><span class='k'>RUN / PROFILE</span><span class='v'>" + escapeHtml(safeText(runId) + " / " + safeText(profile)) + "</span></div>"
                + "<div class='chip'><span class='k'>GEN</span><span class='v'>" + escapeHtml(gIdx + "/" + gTotal) + "</span></div>"
                + "<div class='chip'><span class='k'>TRIAL</span><span class='v'>" + escapeHtml(tIdx + "/" + tTotal + " (" + safeText(trialId) + ")") + "</span></div>"
                + "<div class='chip'><span class='k'>CURRENT MODE</span><span class='v'>" + escapeHtml(modeLine) + "</span></div>"
                + "<div class='chip'><span class='k'>PROGRESS</span><span class='v'>" + escapeHtml(progressText + " | " + pct + "%") + "</span></div>"
                + "</div>"
                + "<div class='main'>"
                + "<div class='panel left-col'>"
                + "<div class='kpi-block'>"
                + "<div class='kpi-item'><span class='k'>SCORE</span><span class='v " + escapeHtml(hudToneClass(qualityState)) + "'>" + escapeHtml(fmtDoubleOrPlaceholder(score, "%.2f", "PENDING")) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>PRESENT FPS</span><span class='v " + escapeHtml(hudToneClass(fpsState)) + "'>" + escapeHtml(fmtDoubleOrPlaceholder(present, "%.1f", "PENDING")) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>LIVE MBPS</span><span class='v " + escapeHtml(hudToneClass(mbpsState)) + "'>" + escapeHtml(fmtLiveMbps(liveMbps, Double.NaN) + " Mbps") + "</span></div>"
                + "</div>"
                + "<div class='kpi-block'>"
                + "<div class='kpi-item'><span class='k'>RECV FPS</span><span class='v'>" + escapeHtml(fmtDoubleOrPlaceholder(recv, "%.1f", "PENDING")) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>DECODE FPS</span><span class='v'>" + escapeHtml(fmtDoubleOrPlaceholder(decode, "%.1f", "PENDING")) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>LAT p95</span><span class='v " + escapeHtml(hudToneClass(latState)) + "'>" + escapeHtml(fmtDoubleOrPlaceholder(latency, "%.1f ms", "PENDING")) + "</span></div>"
                + "</div>"
                + "<div class='kpi-block'>"
                + "<div class='kpi-item'><span class='k'>DROPS/s</span><span class='v " + escapeHtml(hudToneClass(dropState)) + "'>" + escapeHtml(fmtDoubleOrPlaceholder(drops, "%.3f", "PENDING")) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>LATE/s</span><span class='v " + escapeHtml(hudToneClass(dropState)) + "'>" + escapeHtml(fmtDoubleOrPlaceholder(late, "%.3f", "PENDING")) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>QUEUE</span><span class='v " + escapeHtml(hudToneClass(queueState)) + "'>" + escapeHtml(fmtDoubleOrPlaceholder(queue, "%.3f", "PENDING")) + "</span></div>"
                + "</div>"
                + "<div class='status'>"
                + "<div class='row'>" + escapeHtml(statusLine1) + "</div>"
                + "<div class='row'>" + escapeHtml(statusLine2) + "</div>"
                + "<div class='row'>" + escapeHtml(statusLine3) + "</div>"
                + "<div class='row'>" + escapeHtml(statusLine4) + "</div>"
                + "<div class='row'>" + escapeHtml(statusLine5) + "</div>"
                + "<div class='row'>" + escapeHtml(liveTriplet) + "</div>"
                + "</div>"
                + "</div>"
                + "<div class='panel right-col'><div class='trend-grid'>" + trendGrid + "</div></div>"
                + "</div>"
                + "<div class='footer'>"
                + "<div class='foot'><strong>LAYOUT/FONT</strong><br/>layout=" + escapeHtml(safeText(layoutMode)) + " | font=" + escapeHtml(safeText(fontProfile)) + "</div>"
                + "<div class='foot'><strong>DEVICE RESOURCES (* GPU proxy)</strong><br/>" + resourceRowsHtml + "</div>"
                + "<div class='foot'><strong>THRESHOLDS</strong><br/><span class='ok'>OK</span> stable | <span class='warn'>WARN</span> drift | <span class='risk'>RISK</span> critical</div>"
                + "</div>"
                + "</div></body></html>";
    }

    private String buildSotTrendCellHtml(String label, JSONArray series, String toneClass, String unitSuffix) {
        String stats = buildSeriesStats(series, unitSuffix);
        String meta = buildSeriesMetaHtml(label, series, unitSuffix);
        return "<div class='trend-cell'>"
                + "<div class='trend-head'><span class='trend-title'>" + escapeHtml(label) + "</span>"
                + "<span class='trend-stats " + escapeHtml(toneClass == null ? "" : toneClass) + "'>" + escapeHtml(stats) + "</span></div>"
                + "<div class='spark'>" + buildTrendSparkChartFromJson(series, toneClass) + "</div>"
                + meta
                + "</div>";
    }

    private String trainerScaleClass(String fontProfile) {
        String profile = fontProfile == null ? "" : fontProfile.trim().toLowerCase(Locale.US);
        if ("compact".equals(profile) || "dense".equals(profile) || "system".equals(profile)) {
            return "scale-2x";
        }
        if ("arcade".equals(profile)) {
            return "scale-2x";
        }
        return "scale-2x";
    }

    private int clampPercent(int progressPercent) {
        if (progressPercent < 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, progressPercent));
    }

    private double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void appendSeriesSample(ArrayDeque<Double> series, double value) {
        if (series == null) {
            return;
        }
        series.addLast(value);
        while (series.size() > HUD_RESOURCE_SERIES_MAX) {
            series.removeFirst();
        }
    }

    private void sampleDeviceResourceUsage(double targetFps, double renderP95Ms) {
        long nowMs = SystemClock.elapsedRealtime();
        long procCpuNow = android.os.Process.getElapsedCpuTime();
        if (usageSampleLastRealtimeMs > 0L && usageSampleLastCpuMs > 0L) {
            long dWall = Math.max(1L, nowMs - usageSampleLastRealtimeMs);
            long dCpu = Math.max(0L, procCpuNow - usageSampleLastCpuMs);
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            usageCpuPct = clampDouble((dCpu * 100.0) / (dWall * cores), 0.0, 100.0);
        }
        usageSampleLastRealtimeMs = nowMs;
        usageSampleLastCpuMs = procCpuNow;

        Runtime rt = Runtime.getRuntime();
        double usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        usageMemMb = Math.max(0.0, usedMb);
        double maxMb = Math.max(1.0, rt.maxMemory() / (1024.0 * 1024.0));
        double memPct = clampDouble((usageMemMb / maxMb) * 100.0, 0.0, 100.0);

        double frameBudgetMs = targetFps > 1.0 ? (1000.0 / targetFps) : 16.67;
        usageGpuPct = clampDouble((Math.max(0.0, renderP95Ms) / Math.max(1.0, frameBudgetMs)) * 100.0, 0.0, 100.0);

        appendSeriesSample(usageCpuSeries, usageCpuPct);
        appendSeriesSample(usageMemSeries, memPct);
        appendSeriesSample(usageGpuSeries, usageGpuPct);
    }

    private String buildSparkBarsHtml(ArrayDeque<Double> series, String toneClass) {
        if (series == null || series.isEmpty()) {
            return buildSparkPlaceholderBars(toneClass, 18);
        }
        StringBuilder bars = new StringBuilder();
        for (Double sample : series) {
            double v = sample == null ? 0.0 : sample;
            int height = (int) Math.round(clampDouble(v, 0.0, 100.0));
            if (height < 8) {
                height = 8;
            }
            bars.append("<span class='spark-bar ")
                    .append(escapeHtml(toneClass))
                    .append("' style='height:")
                    .append(height)
                    .append("%'></span>");
        }
        return bars.toString();
    }

    private String buildResourceRowsHtml() {
        String cpuTone = usageCpuPct > 85.0 ? "state-risk" : (usageCpuPct > 65.0 ? "state-warn" : "state-ok");
        double memPct = usageMemSeries.isEmpty() ? 0.0 : (usageMemSeries.peekLast() != null ? usageMemSeries.peekLast() : 0.0);
        String memTone = memPct > 88.0 ? "state-risk" : (memPct > 70.0 ? "state-warn" : "state-ok");
        String gpuTone = usageGpuPct > 90.0 ? "state-risk" : (usageGpuPct > 70.0 ? "state-warn" : "state-ok");

        StringBuilder html = new StringBuilder();
        html.append("<div class='res-row'><span class='rk'>CPU</span><span class='rv ")
                .append(cpuTone)
                .append("'>")
                .append(String.format(Locale.US, "%.0f%%", usageCpuPct))
                .append("</span><div class='spark'>")
                .append(buildSparkBarsHtml(usageCpuSeries, cpuTone))
                .append("</div></div>");
        html.append("<div class='res-row'><span class='rk'>MEM</span><span class='rv ")
                .append(memTone)
                .append("'>")
                .append(String.format(Locale.US, "%.0f MB", usageMemMb))
                .append("</span><div class='spark'>")
                .append(buildSparkBarsHtml(usageMemSeries, memTone))
                .append("</div></div>");
        html.append("<div class='res-row'><span class='rk'>GPU*</span><span class='rv ")
                .append(gpuTone)
                .append("'>")
                .append(String.format(Locale.US, "%.0f%%", usageGpuPct))
                .append("</span><div class='spark'>")
                .append(buildSparkBarsHtml(usageGpuSeries, gpuTone))
                .append("</div></div>");
        return html.toString();
    }

    private JSONArray seriesToJson(ArrayDeque<Double> series) {
        JSONArray arr = new JSONArray();
        if (series == null || series.isEmpty()) {
            return arr;
        }
        for (Double v : series) {
            if (v == null || !Double.isFinite(v)) {
                continue;
            }
            arr.put(v);
        }
        return arr;
    }

    private String buildPendingMetricTrendRowsHtml() {
        return buildTrainerTrendGridHtml(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "pending"
        );
    }

    private String buildTrainerTrendGridHtml(
            JSONArray score,
            JSONArray fps,
            JSONArray recv,
            JSONArray decode,
            JSONArray mbps,
            JSONArray drops,
            JSONArray latency,
            JSONArray queue,
            JSONArray late,
            String scoreTone,
            String fpsTone,
            String recvTone,
            String decodeTone,
            String mbpsTone,
            String dropTone,
            String latTone,
            String queueTone,
            String lateTone
    ) {
        return buildTrendCardHtml("SCORE", score, hudToneClass(scoreTone), "")
                + buildTrendCardHtml("PRESENT FPS", fps, hudToneClass(fpsTone), "")
                + buildTrendCardHtml("RECV FPS", recv, hudToneClass(recvTone), "")
                + buildTrendCardHtml("DECODE FPS", decode, hudToneClass(decodeTone), "")
                + buildTrendCardHtml("LIVE MBPS", mbps, hudToneClass(mbpsTone), "Mbps")
                + buildTrendCardHtml("DROP / SEC", drops, hudToneClass(dropTone), "")
                + buildTrendCardHtml("LAT p95", latency, hudToneClass(latTone), "ms")
                + buildTrendCardHtml("QUEUE DEPTH", queue, hudToneClass(queueTone), "")
                + buildTrendCardHtml("LATE / SEC", late, hudToneClass(lateTone), "");
    }

    private String buildMetricTrendRowsHtml(
            JSONArray fps,
            JSONArray mbps,
            JSONArray drops,
            JSONArray latency,
            JSONArray queue,
            String fpsTone,
            String mbpsTone,
            String dropTone,
            String latTone,
            String queueTone
    ) {
        return buildTrendCardHtml("FPS", fps, hudToneClass(fpsTone), "")
                + buildTrendCardHtml("MBPS", mbps, hudToneClass(mbpsTone), "Mbps")
                + buildTrendCardHtml("DROPS / SEC", drops, hudToneClass(dropTone), "")
                + buildTrendCardHtml("LAT p95", latency, hudToneClass(latTone), "ms")
                + buildTrendCardHtml("QUEUE DEPTH", queue, hudToneClass(queueTone), "");
    }

    private String buildTrendCardHtml(String label, JSONArray series, String toneClass, String unitSuffix) {
        String bars = buildTrendSparkChartFromJson(series, toneClass);
        String stats = buildSeriesStats(series, unitSuffix);
        String meta = buildSeriesMetaHtml(label, series, unitSuffix);
        return "<div class='trend-card'><div class='trend-head'><span class='trend-label'>"
                + escapeHtml(label)
                + "</span><span class='trend-range "
                + escapeHtml(toneClass == null ? "" : toneClass)
                + "'>"
                + escapeHtml(stats)
                + "</span></div><div class='spark'>"
                + bars
                + "</div>"
                + meta
                + "</div>";
    }

    private String buildTrendRowHtml(String label, JSONArray series, String toneClass, String unitSuffix) {
        String bars = buildTrendSparkChartFromJson(series, toneClass);
        String stats = buildSeriesStats(series, unitSuffix);
        return "<div class='trend-row'><span class='trend-label'>"
                + escapeHtml(label)
                + "</span><div class='spark'>"
                + bars
                + "</div><span class='trend-range "
                + escapeHtml(toneClass == null ? "" : toneClass)
                + "'>"
                + escapeHtml(stats)
                + "</span></div>";
    }

    private String buildTrendSparkChartFromJson(JSONArray series, String toneClass) {
        return HudRenderSupport.buildTrendSparkChartFromJson(series, toneClass);
    }

    private String buildSparkPlaceholderBars(String toneClass, int count) {
        return HudRenderSupport.buildSparkPlaceholderBars(toneClass, count);
    }

    private String buildSeriesStats(JSONArray series, String unitSuffix) {
        return HudRenderSupport.buildSeriesStats(series, unitSuffix);
    }

    private String buildSeriesMetaHtml(String metricLabel, JSONArray series, String unitSuffix) {
        return HudRenderSupport.buildSeriesMetaHtml(metricLabel, series, unitSuffix, FPS_LOW_ANCHOR);
    }

    private String fmt1(double value) {
        return HudRenderSupport.fmt1(value);
    }

    private double latestFiniteFromSeries(JSONArray series) {
        return HudRenderSupport.latestFiniteFromSeries(series);
    }

    private String fmtDoubleOrPlaceholder(double value, String pattern, String fallback) {
        return HudRenderSupport.fmtDoubleOrPlaceholder(value, pattern, fallback);
    }

    private String fmtLiveMbps(double liveMbps, double targetMbps) {
        return HudRenderSupport.fmtLiveMbps(liveMbps, targetMbps);
    }

    private String safeText(String value) {
        return HudRenderSupport.safeText(value);
    }

    private String hudToneClass(String tone) {
        return HudRenderSupport.hudToneClass(tone);
    }

    private String hudChip(String key, String value, String toneClass) {
        return HudRenderSupport.hudChip(key, value, toneClass);
    }

    private String hudCard(String key, String value, String toneClass) {
        return HudRenderSupport.hudCard(key, value, toneClass);
    }

    private String hudDetailRow(String left, String right) {
        return HudRenderSupport.hudDetailRow(left, right);
    }

    private String[] splitHudColumns(String content) {
        if (content == null) {
            return new String[]{"", ""};
        }
        int pivot = -1;
        for (int i = 2; i < content.length() - 2; i++) {
            if (content.charAt(i) == ' ' && content.charAt(i - 1) == ' ' && content.charAt(i + 1) == ' ') {
                pivot = i;
                break;
            }
        }
        if (pivot < 0) {
            return new String[]{content.trim(), ""};
        }
        String left = content.substring(0, pivot).trim();
        String right = content.substring(pivot).trim();
        return new String[]{left, right};
    }

    private String escapeHtml(String value) {
        return HudRenderSupport.escapeHtml(value);
    }

    private void refreshDebugInfoOverlay() {
        if (!BuildConfig.DEBUG || debugInfoText == null || debugInfoPanel == null) {
            return;
        }
        String state = lastUiState == null ? "IDLE" : lastUiState.toUpperCase(Locale.US);
        String daemonStateUi = effectiveDaemonState(
                daemonState, latestPresentFps, latestStreamUptimeSec, latestFrameOutHost);
        String host = daemonHostName == null || daemonHostName.trim().isEmpty() ? "-" : daemonHostName;
        double safeTarget = latestTargetFps > 0.0 ? latestTargetFps : (double) getSelectedFps();
        double lossPct = Math.max(0.0, ((safeTarget - latestPresentFps) / safeTarget) * 100.0);
        String text = String.format(
                Locale.US,
                "DBG %s | host:%s | daemon:%s\nFPS %.0f/%.1f (loss %.0f%%)  thresholds: green <=20%% orange >20%% red >55%%\n%s\n%s",
                state,
                host,
                daemonStateUi,
                safeTarget,
                latestPresentFps,
                lossPct,
                lastStatsLine,
                lastHudCompactLine
        );
        debugInfoText.setText(text);
    }

    private String effectiveDaemonState(String rawState, double presentFps, long streamUptimeSec, long frameOutHost) {
        String normalized = rawState == null ? "IDLE" : rawState.toUpperCase(Locale.US);
        if (!"STREAMING".equals(normalized)) {
            return normalized;
        }
        boolean flowing = presentFps >= 1.0 || streamUptimeSec > 0 || frameOutHost > 0;
        return flowing ? "STREAMING" : "RECONNECTING";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - preflight overlay
    // ══════════════════════════════════════════════════════════════════════════

    private void startPreflightPulse() {
        if (startupOverlayController != null) {
            startupOverlayController.startPulse();
        }
    }

    private void stopPreflightPulse() {
        if (startupOverlayController != null) {
            startupOverlayController.stopPulse();
        }
    }

    private void setPreflightVisible(boolean visible) {
        if (startupOverlayController != null) {
            startupOverlayController.setVisible(visible);
        }
    }

    private void updatePreflightOverlay() {
        if (preflightOverlay == null) {
            return;
        }

        // Video test controller overrides the startup overlay
        if (videoTestController != null && videoTestController.isOverlayActive()) {
            if (startupTitleText != null)    startupTitleText.setText(videoTestController.getOverlayTitle());
            if (startupSubtitleText != null) startupSubtitleText.setText(videoTestController.getOverlayBody());
            if (startupInfoText != null) {
                startupInfoText.setText(videoTestController.getOverlayHint());
                startupInfoText.setTextColor(Color.parseColor("#FDE68A"));
            }
            setPreflightVisible(true);
            return;
        }

        boolean shouldProbe = requiresTransportProbe();
        if (daemonReachable && handshakeResolved && !isBuildMismatch() && shouldProbe) {
            maybeStartTransportProbe();
        }

        StartupOverlayModelBuilder.Input input = new StartupOverlayModelBuilder.Input();
        input.daemonReachable = daemonReachable;
        input.daemonHostName = daemonHostName;
        input.daemonService = daemonService;
        input.daemonBuildRevision = daemonBuildRevision;
        input.daemonState = daemonState;
        input.daemonLastError = daemonLastError;
        input.handshakeResolved = handshakeResolved;
        input.buildMismatch = isBuildMismatch();
        input.requiresTransportProbe = shouldProbe;
        input.probeOk = transportProbe.isProbeOk();
        input.probeInFlight = transportProbe.isProbeInFlight();
        input.probeInfo = transportProbe.getProbeInfo();
        input.apiImpl = BuildConfig.WBEAM_API_IMPL;
        input.apiBase = HostApiClient.API_BASE;
        input.apiHost = BuildConfig.WBEAM_API_HOST;
        input.streamHost = BuildConfig.WBEAM_STREAM_HOST;
        input.streamPort = BuildConfig.WBEAM_STREAM_PORT;
        input.appBuildRevision = BuildConfig.WBEAM_BUILD_REV;
        input.lastUiInfo = lastUiInfo;
        input.effectiveDaemonState = effectiveDaemonState(
                daemonState, latestPresentFps, latestStreamUptimeSec, latestFrameOutHost);
        input.latestPresentFps = latestPresentFps;
        input.startupBeganAtMs = startupBeganAtMs;
        input.controlRetryCount = controlRetryCount;
        input.nowMs = SystemClock.elapsedRealtime();
        input.lastStatsLine = lastStatsLine;
        input.daemonErrCompact = ErrorTextUtil.compactDaemonErrorForUi(daemonLastError);

        StartupOverlayModelBuilder.Model model = StartupOverlayModelBuilder.build(input);
        startupBeganAtMs = model.updatedStartupBeganAtMs;
        controlRetryCount = model.updatedControlRetryCount;

        StartupStepStyler.applyStepState(model.step1State, preflightAnimTick, SS_OK, SS_ERROR, SS_ACTIVE,
                "1", startupStep1Card, startupStep1Badge, startupStep1Label,
                startupStep1Status, startupStep1Detail, model.step1Detail);
        StartupStepStyler.applyStepState(model.step2State, preflightAnimTick, SS_OK, SS_ERROR, SS_ACTIVE,
                "2", startupStep2Card, startupStep2Badge, startupStep2Label,
                startupStep2Status, startupStep2Detail, model.step2Detail);
        StartupStepStyler.applyStepState(model.step3State, preflightAnimTick, SS_OK, SS_ERROR, SS_ACTIVE,
                "3", startupStep3Card, startupStep3Badge, startupStep3Label,
                startupStep3Status, startupStep3Detail, model.step3Detail);

        if (startupSubtitleText != null) {
            startupSubtitleText.setText(model.subtitle);
            startupSubtitleText.setTextColor(model.step3State == SS_OK
                    ? Color.parseColor("#4ADE80")
                    : model.step3State == SS_ERROR ? Color.parseColor("#F87171") : Color.parseColor("#475569"));
        }

        if (startupInfoText != null) {
            startupInfoText.setText(model.infoLog);
            startupInfoText.setTextColor(Color.parseColor("#CBD5E1"));
        }

        if (!model.allOk) {
            startupDismissed = false;
            preflightComplete = false;
            setPreflightVisible(true);
        } else if (!startupDismissed) {
            startupDismissed = true;
            preflightComplete = true;
            uiHandler.postDelayed(() -> {
                if (startupDismissed) {
                    setPreflightVisible(false);
                }
            }, 800);
        }
    }

    private boolean requiresTransportProbe() {
        return transportProbe.requiresProbe(
                daemonReachable,
                handshakeResolved,
                BuildConfig.WBEAM_API_IMPL,
                BuildConfig.WBEAM_API_HOST
        );
    }

    private void maybeStartTransportProbe() {
        transportProbe.maybeStartProbe(
                requiresTransportProbe(),
                ioExecutor,
                uiHandler,
                new TransportProbeCoordinator.Callbacks() {
                    @Override
                    public String shortError(Exception e) {
                        return ErrorTextUtil.shortError(e);
                    }

                    @Override
                    public void onProbeLogInfo(String msg) {
                        appendLiveLogInfo(msg);
                    }

                    @Override
                    public void onProbeLogWarn(String msg) {
                        appendLiveLogWarn(msg);
                    }

                    @Override
                    public void onProbeStateChanged() {
                        updatePreflightOverlay();
                    }
                }
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════════

    private boolean hasHardwareAvcDecoder() {
        try {
            MediaCodecInfo[] infos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            } else {
                int count = MediaCodecList.getCodecCount();
                infos = new MediaCodecInfo[count];
                for (int i = 0; i < count; i++) {
                    infos[i] = MediaCodecList.getCodecInfoAt(i);
                }
            }

            for (MediaCodecInfo info : infos) {
                if (info == null || info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(type)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (info.isHardwareAccelerated()) {
                                return true;
                            }
                        } else {
                            String name = info.getName().toLowerCase(Locale.US);
                            if (!name.startsWith("omx.google.")
                                    && !name.startsWith("c2.android.")
                                    && !name.contains("sw")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to inspect codecs", e);
        }
        return false;
    }

    private void emitHudDebugAdb(String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastHudAdbLogAt < HUD_ADB_LOG_INTERVAL_MS) {
            return;
        }
        if (snapshot.equals(lastHudAdbSnapshot)) {
            return;
        }
        lastHudAdbLogAt = now;
        lastHudAdbSnapshot = snapshot;
        Log.i(TAG, "HUDDBG " + snapshot);
    }

    private int ledColorForState(String state) {
        if (STATE_STREAMING.equals(state)) {
            return Color.parseColor("#22C55E");
        }
        if (STATE_CONNECTING.equals(state)) {
            return Color.parseColor("#F59E0B");
        }
        return Color.parseColor("#EF4444");
    }

    private String formatBps(long bps) {
        return StatusTextFormatter.formatBps(bps);
    }

    private int[] computeScaledSize() {
        return StreamConfigResolver.computeScaledSize(
                getSelectedProfile(),
                getResolutionScalePercent()
        );
    }

    private boolean isLegacyAndroidDevice() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2;
    }

    private StreamConfigResolver.Resolved effectiveStreamConfig() {
        return StreamConfigResolver.resolve(
                getSelectedProfile(),
                getResolutionScalePercent(),
                getSelectedFps(),
                getSelectedBitrateMbps(),
                isLegacyAndroidDevice()
        );
    }

    private int getResolutionScalePercent() {
        return 50 + resolutionSeek.getProgress();
    }

    private int getSelectedFps() {
        return 24 + fpsSeek.getProgress();
    }

    private int getSelectedBitrateMbps() {
        return 5 + bitrateSeek.getProgress();
    }

    private String getSelectedProfile() {
        return String.valueOf(profileSpinner.getSelectedItem());
    }

    private String getSelectedEncoder() {
        return String.valueOf(encoderSpinner.getSelectedItem());
    }

    private String getSelectedCursorMode() {
        return String.valueOf(cursorSpinner.getSelectedItem());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void setSpinnerSelection(Spinner spinner, String[] options, String value) {
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(value)) {
                idx = i;
                break;
            }
        }
        spinner.setSelection(idx, false);
    }

}
