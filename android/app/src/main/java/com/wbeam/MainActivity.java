package com.wbeam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.text.style.ForegroundColorSpan;
import android.util.Log;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.wbeam.api.HostApiClient;
import com.wbeam.api.StatusListener;
import com.wbeam.api.StatusPoller;
import com.wbeam.settings.SettingsRepository;
import com.wbeam.stream.H264TcpPlayer;
import com.wbeam.stream.VideoTestController;
import com.wbeam.stream.StreamSessionController;
import com.wbeam.telemetry.ClientMetricsReporter;
import com.wbeam.widget.FpsLossGraphView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String TEST_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4";

    private static final String[] PROFILE_OPTIONS = {"lowlatency", "balanced", "ultra"};
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

    private static final int LIVE_LOG_MAX_LINES = 80;
    private static final long SIMPLE_MENU_AUTO_HIDE_MS = 10_000L;
    private static final float DEBUG_INFO_ALPHA_IDLE = 0.55f;
    private static final float DEBUG_INFO_ALPHA_TOUCH = 0.92f;
    private static final long DEBUG_INFO_ALPHA_RESET_MS = 1300L;
    private static final long DEBUG_FPS_SAMPLE_MS = 1000L;
    private static final int DEBUG_FPS_GRAPH_POINTS = 180;

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
    private TextView debugInfoText;
    // ── Startup overlay ────────────────────────────────────────────────────────
    private TextView startupTitleText;
    private TextView startupSubtitleText;
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
    private boolean isFullscreen = false;
    private boolean cursorOverlayEnabled = true;
    private boolean debugControlsVisible = false;
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
    private boolean transportProbeOk = false;
    private boolean transportProbeInFlight = false;
    private long transportProbeLastAtMs = 0L;
    private long transportProbeRetryAfterMs = 0L;
    private String transportProbeInfo = "not started";
    private boolean hwAvcDecodeAvailable = false;
    private String lastUiState = STATE_IDLE;
    private String lastUiInfo = "tap Settings -> Start Live";
    private long lastUiBps = 0;
    private String lastCriticalErrorInfo = "";
    private long lastCriticalErrorLogAtMs = 0L;
    private String lastStatsLine = "fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -";
    private String lastHudCompactLine = "hud: waiting for metrics";
    private double latestTargetFps = 60.0;
    private double latestPresentFps = 0.0;
    private final SpannableStringBuilder liveLogBuffer = new SpannableStringBuilder();

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

    private SettingsRepository settingsRepository;
    private StatusPoller statusPoller;
    private StreamSessionController sessionController;
    private ClientMetricsReporter metricsReporter;
    private VideoTestController videoTestController;

    // ── Media ──────────────────────────────────────────────────────────────────
    private Surface surface;
    private H264TcpPlayer player;
    // ── Runnables ──────────────────────────────────────────────────────────────
    private final Runnable preflightPulseTask = new Runnable() {
        @Override
        public void run() {
            preflightAnimTick = (preflightAnimTick + 1) % 4;
            updatePreflightOverlay();
            uiHandler.postDelayed(this, 400);
        }
    };
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

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settingsRepository = new SettingsRepository(this);

        bindViews();
        setupSpinners();
        setupSeekbars();
        setupSurfaceCallbacks();
        setupButtons();
        loadSavedSettings();
        hwAvcDecodeAvailable = hasHardwareAvcDecoder();
        Log.i(TAG, "startup transport api_impl=" + BuildConfig.WBEAM_API_IMPL
            + " api=" + HostApiClient.API_BASE
            + " stream=tcp://" + BuildConfig.WBEAM_STREAM_HOST + ":5000");

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
                updateActionButtonsEnabled();
                updateHostHint();
                refreshStatusText();
                updatePreflightOverlay();
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
            }

            @Override
            public void onDaemonOffline(boolean wasReachable, Exception e) {
                daemonReachable = false;
                daemonState = "DISCONNECTED";
                handshakeResolved = false;
                transportProbeOk = false;
                transportProbeInFlight = false;
                transportProbeRetryAfterMs = 0L;
                transportProbeInfo = "waiting for control link";
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
                    updateStatus(STATE_ERROR, "Host API offline: " + shortError(e), 0);
                    appendLiveLogError("daemon poll failed: " + shortError(e)
                            + " | api=" + HostApiClient.API_BASE);
                    Toast.makeText(MainActivity.this,
                            "Host API unreachable (" + shortError(e) + "). Check USB tethering/LAN and host IP: " + HostApiClient.API_BASE,
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
        updateStatus(STATE_IDLE, "tap Settings -> Start Live", 0);
        statusPoller.start();
    }

    @Override
    protected void onDestroy() {
        statusPoller.stop();
        stopPreflightPulse();
        uiHandler.removeCallbacks(simpleMenuAutoHideTask);
        uiHandler.removeCallbacks(debugInfoFadeTask);
        uiHandler.removeCallbacks(debugGraphSampleTask);
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
        if (isFullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // View setup
    // ══════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        rootLayout = findViewById(R.id.rootLayout);
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
        debugInfoText = findViewById(R.id.debugInfoText);
        startupTitleText    = findViewById(R.id.startupTitle);
        startupSubtitleText = findViewById(R.id.startupSubtitle);
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
    }

    private void applyBuildVariantUi() {
        if (BuildConfig.DEBUG) {
            hideSimpleMenu();
            setFullscreen(false);
            if (quickActionRow != null) {
                quickActionRow.setVisibility(View.VISIBLE);
            }
            if (statusPanel != null) {
                statusPanel.setVisibility(View.GONE);
            }
            if (perfHudPanel != null) {
                perfHudPanel.setVisibility(View.GONE);
            }
            if (debugInfoPanel != null) {
                debugInfoPanel.setVisibility(View.VISIBLE);
                debugInfoPanel.setAlpha(DEBUG_INFO_ALPHA_IDLE);
            }
            if (debugFpsGraphView != null) {
                debugFpsGraphView.setCapacity(DEBUG_FPS_GRAPH_POINTS);
            }
            if (logButton != null) {
                logButton.setVisibility(View.GONE);
            }
            if (fullscreenButton != null) {
                fullscreenButton.setVisibility(View.VISIBLE);
            }
            startDebugGraphSampling();
            refreshDebugInfoOverlay();
            return;
        }

        hideSettingsPanel();
        setDebugControlsVisible(false);
        setFullscreen(true);
        showSimpleMenu();

        if (quickActionRow != null) {
            quickActionRow.setVisibility(View.GONE);
        }
        if (statusPanel != null) {
            statusPanel.setVisibility(View.GONE);
        }
        if (perfHudPanel != null) {
            perfHudPanel.setVisibility(View.GONE);
        }
        if (debugInfoPanel != null) {
            debugInfoPanel.setVisibility(View.GONE);
        }
        stopDebugGraphSampling();
        if (logButton != null) {
            logButton.setVisibility(View.GONE);
        }
        if (fullscreenButton != null) {
            fullscreenButton.setVisibility(View.GONE);
        }
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
            if (!cursorOverlayEnabled) {
                return false;
            }
            updateCursorOverlay(event.getX(), event.getY(), event.getActionMasked());
            return false;
        });

        preview.setOnGenericMotionListener((v, event) -> {
            if (!cursorOverlayEnabled) {
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
        settingsButton.setOnClickListener(v -> {
            if (BuildConfig.DEBUG) {
                toggleSettingsPanel();
            } else {
                toggleSimpleMenu();
            }
        });
        logButton.setOnClickListener(v -> toggleLiveLogPanel());
        settingsButton.setOnLongClickListener(v -> {
            if (!BuildConfig.DEBUG) {
                return false;
            }
            setDebugControlsVisible(!debugControlsVisible);
            Toast.makeText(
                    this,
                    debugControlsVisible ? "Debug controls ON" : "Debug controls OFF",
                    Toast.LENGTH_SHORT
            ).show();
            return true;
        });
        settingsCloseButton.setOnClickListener(v -> hideSettingsPanel());
        applySettingsButton.setOnClickListener(v -> applySettings(true));

        startButton.setOnClickListener(v -> requestStartGuarded(true, true));
        stopButton.setOnClickListener(v -> sessionController.requestStop(true));
        testButton.setOnClickListener(v -> videoTestController.startBandwidthTest());
        testButton.setOnLongClickListener(v -> {
            videoTestController.startPublicVideoTest();
            return true;
        });
        if (quickStartButton != null) {
            quickStartButton.setOnClickListener(v -> requestStartGuarded(true, true));
        }
        if (quickStopButton != null) {
            quickStopButton.setOnClickListener(v -> sessionController.requestStop(true));
        }
        if (quickTestButton != null) {
            quickTestButton.setOnClickListener(v -> videoTestController.startBandwidthTest());
            quickTestButton.setOnLongClickListener(v -> {
                videoTestController.startPublicVideoTest();
                return true;
            });
        }

        fullscreenButton.setOnClickListener(v -> toggleFullscreen());
        cursorOverlayButton.setOnClickListener(v -> toggleCursorOverlayMode());

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
                applySettings(true);
                requestStartGuarded(false, true);
                hideSimpleMenu();
            });
        }

        intraOnlyButton.setOnClickListener(v -> {
            intraOnlyEnabled = !intraOnlyEnabled;
            updateIntraOnlyButton();
            applySettings(true);
        });

        updateActionButtonsEnabled();
    }

    private void setActionButtonEnabled(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.45f);
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
        SettingsRepository.SettingsSnapshot s = settingsRepository.load();
        setSpinnerSelection(profileSpinner, PROFILE_OPTIONS, s.profile);
        setSpinnerSelection(encoderSpinner, ENCODER_OPTIONS, s.encoder);
        setSpinnerSelection(cursorSpinner, CURSOR_OPTIONS, s.cursor);
        resolutionSeek.setProgress(clamp(s.resScale, 50, 100) - 50);
        fpsSeek.setProgress(clamp(s.fps, 24, 144) - 24);
        bitrateSeek.setProgress(clamp(s.bitrateMbps, 5, 300) - 5);
        cursorOverlayEnabled = s.localCursor;
        enforceCursorOverlayPolicy(false);
        intraOnlyEnabled = s.intraOnly;
        updateIntraOnlyButton();
        updateSettingValueLabels();
        simpleMode = "raw-png".equals(getSelectedEncoder()) ? "raw-png" : PREFERRED_VIDEO;
        simpleFps = clamp(getSelectedFps(), 30, 144);
        refreshSimpleMenuButtons();
    }

    private void saveSettings() {
        settingsRepository.save(new SettingsRepository.SettingsSnapshot(
                getSelectedProfile(), getSelectedEncoder(), getSelectedCursorMode(),
                getResolutionScalePercent(), getSelectedFps(), getSelectedBitrateMbps(),
                cursorOverlayEnabled, intraOnlyEnabled
        ));
    }

    private void applySettings(boolean userAction) {
        updateSettingValueLabels();
        saveSettings();
        updateHostHint();
        if (userAction) {
            JSONObject payload = buildConfigPayload();
            sessionController.postApiCommand("POST", "/apply", payload, "applied", true, false);
        }
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
        int[] sz = computeScaledSize();
        String apiBase = HostApiClient.API_BASE;
        String daemonStateUi = effectiveDaemonState(daemonState, latestPresentFps, 0, 0);
        String line1 = "Control API " + (daemonReachable ? "connected" : "waiting")
            + ": " + apiBase;
        String line2 = "Host: " + daemonHostName + " | Daemon: " + daemonStateUi + " (" + daemonService + ")";
        String line3 = "Outgoing config: " + getSelectedProfile()
                + ", " + sz[0] + "x" + sz[1]
                + ", " + getSelectedFps() + "fps, "
                + getSelectedBitrateMbps() + "Mbps, "
                + getSelectedEncoder() + (intraOnlyEnabled ? "+intra" : "")
                + ", cursor " + getSelectedCursorMode();
        hostHintText.setText(line1 + "\n" + line2 + "\n" + line3);
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
        int[] sz = computeScaledSize();
        JSONObject payload = new JSONObject();
        try {
            String uiEncoder = getSelectedEncoder();
            // "raw-png" (UI label) → "rawpng" (API name); "h264"/"h265" pass through as-is.
            String encoder = "raw-png".equals(uiEncoder) ? "rawpng" : uiEncoder;
            boolean intraOnly = "h265".equals(encoder) && intraOnlyEnabled;
            payload.put("profile", getSelectedProfile());
            payload.put("encoder", encoder);
            payload.put("cursor_mode", getSelectedCursorMode());
            payload.put("size", sz[0] + "x" + sz[1]);
            payload.put("fps", getSelectedFps());
            payload.put("bitrate_kbps", getSelectedBitrateMbps() * 1000);
            payload.put("debug_fps", 0);
            payload.put("intra_only", intraOnly);
        } catch (JSONException ignored) {
        }
        return payload;
    }

    private void handleApiFailure(String prefix, boolean userAction, Exception e) {
        String reason = shortError(e);
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

    private String shortError(Exception e) {
        String msg = e.getMessage();
        if (msg != null) {
            msg = msg.trim();
            if (!msg.isEmpty()) {
                if (msg.length() > 120) {
                    return msg.substring(0, 120);
                }
                return msg;
            }
        }
        return e.getClass().getSimpleName();
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

        int[] decodeSize = computeScaledSize();
        int fps = getSelectedFps();
        long frameUs = Math.max(1L, 1_000_000L / Math.max(1, fps));
        SurfaceView preview = findViewById(R.id.previewSurface);
        preview.getHolder().setFixedSize(decodeSize[0], decodeSize[1]);

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
                decodeSize[0],
                decodeSize[1],
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
        setFullscreen(!isFullscreen);
    }

    private void setFullscreen(boolean enable) {
        isFullscreen = enable;

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (enable) {
            topBar.setVisibility(View.GONE);
            statusPanel.setVisibility(View.GONE);
            hideSettingsPanel();

            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
            return;
        }

        topBar.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.VISIBLE);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
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
        if (!"hidden".equals(getSelectedCursorMode())) {
            cursorOverlayEnabled = false;
            hideCursorOverlay();
            enforceCursorOverlayPolicy(true);
            return;
        }
        cursorOverlayEnabled = !cursorOverlayEnabled;
        enforceCursorOverlayPolicy(true);
        if (!cursorOverlayEnabled) {
            hideCursorOverlay();
        }
    }

    private void enforceCursorOverlayPolicy(boolean persist) {
        String cursorMode = getSelectedCursorMode();
        boolean allowLocalOverlay = "hidden".equals(cursorMode);
        if (!allowLocalOverlay && cursorOverlayEnabled) {
            cursorOverlayEnabled = false;
            hideCursorOverlay();
        }
        if (cursorOverlayButton != null) {
            cursorOverlayButton.setEnabled(allowLocalOverlay);
            cursorOverlayButton.setAlpha(allowLocalOverlay ? 1.0f : 0.45f);
            if (allowLocalOverlay) {
                cursorOverlayButton.setText(cursorOverlayEnabled ? "Local Cursor Overlay ON" : "Local Cursor Overlay OFF");
            } else {
                cursorOverlayButton.setText("Local Cursor Overlay N/A (cursor hidden required)");
            }
        }
        if (persist) {
            saveSettings();
        }
    }

    private void updateCursorOverlay(float x, float y, int action) {
        if (cursorOverlay == null || !cursorOverlayEnabled) {
            return;
        }
        cursorOverlay.setX(x - (cursorOverlay.getWidth() / 2f));
        cursorOverlay.setY(y - (cursorOverlay.getHeight() / 2f));
        cursorOverlay.setVisibility(View.VISIBLE);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cursorOverlay.removeCallbacks(this::hideCursorOverlay);
            cursorOverlay.postDelayed(this::hideCursorOverlay, 400);
        }
    }

    private void hideCursorOverlay() {
        if (cursorOverlay != null) {
            cursorOverlay.setVisibility(View.GONE);
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
        if (STATE_ERROR.equals(lastUiState) && isCriticalUiInfo(lastUiInfo)) {
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

    private boolean isCriticalUiInfo(String info) {
        if (info == null) {
            return false;
        }
        String text = info.toLowerCase(Locale.US);
        return text.contains("offline")
                || text.contains("ioexception")
                || text.contains("stream error")
                || text.contains("failed")
                || text.contains("disconnected");
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
        String host = daemonHostName == null || daemonHostName.trim().isEmpty() ? "-" : daemonHostName;
        String daemonStateUi = effectiveDaemonState(daemonState, latestPresentFps, 0, 0);
        String transport = "USB:" + (daemonReachable ? "Connected" : "Disconnected")
                + " | Host:" + host
            + " | Stream:" + daemonStateUi;

        statusText.setText(lastUiState.toUpperCase(Locale.US));
        if (lastUiInfo == null || lastUiInfo.trim().isEmpty()) {
            detailText.setText(transport);
        } else {
            detailText.setText(lastUiInfo + " | " + transport);
        }
        bpsText.setText("throughput: " + formatBps(lastUiBps));

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

    // ══════════════════════════════════════════════════════════════════════════
    // UI - perf HUD
    // ══════════════════════════════════════════════════════════════════════════

    private void updatePerfHudUnavailable() {
        if (perfHudText == null) {
            return;
        }
        latestTargetFps = getSelectedFps();
        latestPresentFps = 0.0;
        lastHudCompactLine = "hud: offline | waiting metrics";
        perfHudText.setText("HUD OFFLINE\nwaiting for host metrics...");
        perfHudText.setTextColor(Color.parseColor("#FCA5A5"));
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.92f);
        }
        refreshDebugInfoOverlay();
        emitHudDebugAdb("state=offline waiting_metrics=1");
    }

    private void updatePerfHud(JSONObject metrics) {
        if (perfHudText == null) {
            return;
        }
        if (metrics == null) {
            updatePerfHudUnavailable();
            return;
        }

        JSONObject kpi = metrics.optJSONObject("kpi");
        JSONObject latest = metrics.optJSONObject("latest_client_metrics");
        JSONObject limits = metrics.optJSONObject("queue_limits");
        long frameInHost = metrics.optLong("frame_in", 0);
        long frameOutHost = metrics.optLong("frame_out", 0);
        long streamUptimeSec = metrics.optLong("stream_uptime_sec", 0);

        double targetFps = kpi != null ? kpi.optDouble("target_fps", getSelectedFps()) : getSelectedFps();
        double presentFps = kpi != null ? kpi.optDouble("present_fps", 0.0) : 0.0;
        String daemonStateUi = effectiveDaemonState(daemonState, presentFps, streamUptimeSec, frameOutHost);
        latestTargetFps = targetFps;
        latestPresentFps = presentFps;
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
        perfHudText.setText(hud);
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

        // Build explicit high-pressure reason so logcat shows exactly which condition fired.
        StringBuilder hpSb = new StringBuilder();
        if (!warmingUp) {
            if (decodeP95 > 12.0) { hpSb.append("dec>12(").append(String.format(Locale.US,"%.1f",decodeP95)).append(")"); }
            if (renderP95 > 7.0)  { if (hpSb.length()>0) hpSb.append(','); hpSb.append("ren>7(").append(String.format(Locale.US,"%.1f",renderP95)).append(")"); }
            if (qT >= qTMax)      { if (hpSb.length()>0) hpSb.append(','); hpSb.append("qT=").append(qT).append("/").append(qTMax); }
            if (qD >= qDMax)      { if (hpSb.length()>0) hpSb.append(','); hpSb.append("qD=").append(qD).append("/").append(qDMax); }
            if (qR >= qRMax)      { if (hpSb.length()>0) hpSb.append(','); hpSb.append("qR=").append(qR).append("/").append(qRMax); }
        }
        String hpReason = hpSb.length() > 0 ? hpSb.toString() : (warmingUp ? "warmup" : "ok");

        // Red only when degraded with frames already flowing; warmup = yellow.
        boolean highPressure = !warmingUp && hpSb.length() > 0;
        if (highPressure) {
            perfHudText.setTextColor(Color.parseColor("#FCA5A5")); // red
            Log.w(TAG, "HUD RED warmingUp=" + warmingUp + " hp=" + hpReason
                    + " dec_p95=" + String.format(Locale.US, "%.2f", decodeP95)
                    + " ren_p95=" + String.format(Locale.US, "%.2f", renderP95)
                    + " qT=" + qT + "/" + qTMax
                    + " qD=" + qD + "/" + qDMax
                    + " qR=" + qR + "/" + qRMax
                    + " fps_present=" + String.format(Locale.US, "%.1f", presentFps)
                    + " stream_up=" + streamUptimeSec + "s");
        } else if (adaptiveAction.startsWith("degrade") || warmingUp) {
            perfHudText.setTextColor(Color.parseColor("#FDE68A")); // yellow
        } else {
            perfHudText.setTextColor(Color.parseColor("#BBF7D0")); // green
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.95f);
        }

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

    private void refreshDebugInfoOverlay() {
        if (!BuildConfig.DEBUG || debugInfoText == null || debugInfoPanel == null) {
            return;
        }
        String state = lastUiState == null ? "IDLE" : lastUiState.toUpperCase(Locale.US);
        String daemonStateUi = effectiveDaemonState(daemonState, latestPresentFps, 0, 0);
        String host = daemonHostName == null || daemonHostName.trim().isEmpty() ? "-" : daemonHostName;
        double safeTarget = latestTargetFps > 0.0 ? latestTargetFps : 60.0;
        double lossPct = Math.max(0.0, ((safeTarget - latestPresentFps) / safeTarget) * 100.0);
        String text = String.format(
                Locale.US,
                "DBG %s | host:%s | daemon:%s\nFPS %.0f/%.1f (loss %.0f%%)  thresholds: green <=10%% orange >10%% red >50%%\n%s\n%s",
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
        uiHandler.removeCallbacks(preflightPulseTask);
        uiHandler.post(preflightPulseTask);
    }

    private void stopPreflightPulse() {
        uiHandler.removeCallbacks(preflightPulseTask);
    }

    private void setPreflightVisible(boolean visible) {
        if (preflightOverlay == null) {
            return;
        }
        if (visible) {
            preflightOverlay.setVisibility(View.VISIBLE);
            preflightOverlay.setAlpha(1f);
            return;
        }
        preflightOverlay.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    if (preflightOverlay != null) {
                        preflightOverlay.setVisibility(View.GONE);
                        preflightOverlay.setAlpha(1f);
                    }
                })
                .start();
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

        long elapsedMs = startupBeganAtMs > 0L
                ? Math.max(0L, SystemClock.elapsedRealtime() - startupBeganAtMs)
                : 0L;

        // If we've been waiting > 20s with no response, auto-reset the window
        // so the overlay cycles back to ACTIVE (endless retry) rather than
        // permanently showing red ERROR.
        if (!daemonReachable && elapsedMs > 20_000L) {
            controlRetryCount++;
            startupBeganAtMs = SystemClock.elapsedRealtime();
            elapsedMs = 0L;
        }
        // Also reset counter when daemon connects successfully
        if (daemonReachable && controlRetryCount > 0) {
            controlRetryCount = 0;
        }

        // ── step 1: control link ──────────────────────────────────────────────
        int step1 = daemonReachable ? SS_OK : SS_ACTIVE;

        String step1Detail;
        if (step1 == SS_OK) {
            boolean isLocalImpl = "local".equalsIgnoreCase(BuildConfig.WBEAM_API_IMPL);
            if (isLocalImpl) {
                step1Detail = "on-device (local api) \u00b7 no host connection needed";
            } else {
                step1Detail = "reachable \u00b7 api_impl=" + BuildConfig.WBEAM_API_IMPL
                        + " \u00b7 " + daemonHostName;
            }
        } else if (controlRetryCount == 0) {
            step1Detail = "polling " + HostApiClient.API_BASE
                    + " \u2026 (" + (elapsedMs / 1000L) + "s)";
        } else {
            step1Detail = "no response \u00b7 retry #" + controlRetryCount
                    + " \u00b7 polling " + HostApiClient.API_BASE
                    + " (" + (elapsedMs / 1000L) + "s)";
        }

        // ── step 2: handshake ─────────────────────────────────────────────────
        int step2;
        String step2Detail;
        if (step1 != SS_OK) {
            step2 = SS_PENDING;
            step2Detail = "waiting for control link";
        } else if (!handshakeResolved) {
            step2 = SS_ACTIVE;
            step2Detail = "resolving service / api version\u2026";
        } else if (isBuildMismatch()) {
            step2 = SS_ERROR;
            step2Detail = "build mismatch · app=" + BuildConfig.WBEAM_BUILD_REV
                    + " · host=" + daemonBuildRevision;
        } else {
            step2 = SS_OK;
            if (requiresTransportProbe()) {
                maybeStartTransportProbe();
                if (transportProbeOk) {
                    step2Detail = "service=" + daemonService + " · transport test OK";
                } else if (transportProbeInFlight) {
                    step2Detail = "service=" + daemonService + " · transport test in progress…";
                } else {
                    step2Detail = "service=" + daemonService + " · transport test pending";
                }
            } else {
                step2Detail = "service=" + daemonService + " · " + BuildConfig.WBEAM_API_IMPL;
            }
        }

        // ── step 3: stream ────────────────────────────────────────────────────
        String effState = effectiveDaemonState(daemonState, latestPresentFps, 0, 0);
        boolean streamFlowing = latestPresentFps >= 1.0 || "STREAMING".equals(effState);

        // Parse stream reconnect count from lastStatsLine ("reconnects: N")
        int streamReconnects = 0;
        try {
            int rIdx = lastStatsLine.indexOf("reconnects: ");
            if (rIdx >= 0) {
                String rPart = lastStatsLine.substring(rIdx + "reconnects: ".length()).trim();
                int end = 0;
                while (end < rPart.length() && Character.isDigit(rPart.charAt(end))) end++;
                if (end > 0) streamReconnects = Integer.parseInt(rPart.substring(0, end));
            }
        } catch (Exception ignored) {}

        // Build the stream address hint once (depends on build config, not runtime)
        String streamHost = BuildConfig.WBEAM_STREAM_HOST;
        boolean streamIsLoopback = streamHost == null
                || streamHost.trim().isEmpty()
                || streamHost.trim().equals("127.0.0.1")
                || streamHost.trim().equals("localhost");
        String streamAddr = (streamHost != null && !streamHost.trim().isEmpty())
                ? streamHost.trim() : "127.0.0.1";
        String streamFixHint = streamIsLoopback
                ? "run: ./devtool ip up  (adb reverse)"
            : "check USB tethering / host IP / LAN";

        int step3;
        String step3Detail;
        if (step2 != SS_OK) {
            step3 = SS_PENDING;
            step3Detail = (step2 == SS_ERROR && isBuildMismatch())
                    ? "blocked by build mismatch"
                    : "waiting for handshake";
        } else if (requiresTransportProbe() && !transportProbeOk) {
            step3 = SS_ACTIVE;
            if (transportProbeInFlight) {
                step3Detail = "testing transport I/O… " + transportProbeInfo;
            } else {
                step3Detail = "transport test retrying… " + transportProbeInfo;
            }
        } else if (streamFlowing) {
            step3 = SS_OK;
            step3Detail = "live \u00b7 fps=" + String.format(Locale.US, "%.0f", latestPresentFps)
                    + " \u00b7 " + effState.toLowerCase(Locale.US);
        } else {
            // Never permanently error — always show ACTIVE so the user sees
            // endless retry rather than a frozen red badge.
            step3 = SS_ACTIVE;
            boolean hasWaited = elapsedMs > 5_000L;
            if (streamReconnects > 0 && hasWaited) {
                step3Detail = "retry #" + streamReconnects
                        + " \u00b7 " + streamAddr + ":5000 unreachable \u00b7 " + streamFixHint;
            } else if (streamReconnects > 0) {
                step3Detail = "reconnecting \u00b7 attempt #" + streamReconnects + " \u00b7 awaiting frames\u2026";
            } else if (hasWaited) {
                step3Detail = "connecting to " + streamAddr + ":5000 \u00b7 " + streamFixHint;
            } else {
                step3Detail = "decoder started \u00b7 awaiting frames\u2026";
            }
        }

        applyStepState(step1, "1", startupStep1Badge, startupStep1Label,
                startupStep1Status, startupStep1Detail, step1Detail);
        applyStepState(step2, "2", startupStep2Badge, startupStep2Label,
                startupStep2Status, startupStep2Detail, step2Detail);
        applyStepState(step3, "3", startupStep3Badge, startupStep3Label,
                startupStep3Status, startupStep3Detail, step3Detail);

        // subtitle
        if (startupSubtitleText != null) {
            String subtitle;
            if (step1 != SS_OK) {
                if (elapsedMs < 2000L && controlRetryCount == 0) {
                    subtitle = "starting up\u2026";
                } else if (controlRetryCount == 0) {
                    subtitle = "awaiting control link\u2026";
                } else {
                    subtitle = "retrying control link \u00b7 attempt #" + controlRetryCount + "\u2026";
                }
            } else if (step2 != SS_OK) {
                subtitle = (step2 == SS_ERROR && isBuildMismatch())
                        ? "build mismatch \u00b7 redeploy APK or rebuild host"
                        : "handshake in progress\u2026";
            } else if (step3 != SS_OK) {
                subtitle = streamReconnects > 0
                        ? "stream reconnecting \u00b7 attempt #" + streamReconnects + "\u2026"
                        : elapsedMs > 5_000L
                                ? "stream unreachable \u00b7 retrying\u2026"
                                : "waiting for video frames\u2026";
            } else {
                subtitle = "all systems ready";
            }
            startupSubtitleText.setText(subtitle);
            startupSubtitleText.setTextColor(step3 == SS_OK
                    ? Color.parseColor("#4ADE80")
                    : step3 == SS_ERROR ? Color.parseColor("#F87171") : Color.parseColor("#475569"));
        }

        // info line
        if (startupInfoText != null) {
            startupInfoText.setText(
                    "api=" + HostApiClient.API_BASE
                    + "  impl=" + BuildConfig.WBEAM_API_IMPL
                    + "  stream=" + BuildConfig.WBEAM_STREAM_HOST + ":5000"
                    + "  app=" + BuildConfig.WBEAM_BUILD_REV
                    + "  host=" + daemonBuildRevision);
            startupInfoText.setTextColor(Color.parseColor("#334155"));
        }

        boolean allOk = step1 == SS_OK && step2 == SS_OK && step3 == SS_OK;
        if (!allOk) {
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

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return true;
        }
        String trimmed = host.trim();
        return trimmed.isEmpty()
                || "127.0.0.1".equals(trimmed)
                || "localhost".equalsIgnoreCase(trimmed);
    }

    private boolean requiresTransportProbe() {
        if (!daemonReachable || !handshakeResolved) {
            return false;
        }
        if ("local".equalsIgnoreCase(BuildConfig.WBEAM_API_IMPL)) {
            return false;
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return false;
        }
        return !isLoopbackHost(BuildConfig.WBEAM_API_HOST);
    }

    private void maybeStartTransportProbe() {
        if (!requiresTransportProbe()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (transportProbeOk || transportProbeInFlight || now < transportProbeRetryAfterMs) {
            return;
        }

        transportProbeInFlight = true;
        transportProbeLastAtMs = now;
        transportProbeInfo = "starting";

        ioExecutor.execute(() -> {
            try {
                long elapsed = HostApiClient.runTransportProbeMs(1);
                uiHandler.post(() -> {
                    transportProbeOk = true;
                    transportProbeInFlight = false;
                    transportProbeRetryAfterMs = 0L;
                    transportProbeInfo = "1MB in " + elapsed + "ms";
                    appendLiveLogInfo("transport probe OK: " + transportProbeInfo);
                    updatePreflightOverlay();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    transportProbeOk = false;
                    transportProbeInFlight = false;
                    transportProbeRetryAfterMs = SystemClock.elapsedRealtime() + 4_000L;
                    transportProbeInfo = shortError(e);
                    appendLiveLogWarn("transport probe failed: " + transportProbeInfo);
                    updatePreflightOverlay();
                });
            }
        });
    }

    private void applyStepState(int state, String number,
            TextView badge, TextView label, TextView status, TextView detail,
            String detailText) {
        if (badge == null || label == null || status == null || detail == null) {
            return;
        }
        int badgeBg, badgeFg, labelColor, statusColor;
        String statusStr;
        switch (state) {
            case SS_OK:
                badgeBg    = Color.parseColor("#14532D");
                badgeFg    = Color.parseColor("#4ADE80");
                labelColor = Color.parseColor("#4ADE80");
                statusColor= Color.parseColor("#4ADE80");
                statusStr  = " OK";
                badge.setText("\u2713");
                detail.setTextColor(Color.parseColor("#166534"));
                break;
            case SS_ERROR:
                badgeBg    = Color.parseColor("#450A0A");
                badgeFg    = Color.parseColor("#F87171");
                labelColor = Color.parseColor("#F87171");
                statusColor= Color.parseColor("#F87171");
                statusStr  = "ERR";
                badge.setText("\u2717");
                detail.setTextColor(Color.parseColor("#7F1D1D"));
                break;
            case SS_ACTIVE:
                badgeBg    = Color.parseColor("#78350F");
                badgeFg    = Color.parseColor("#FDE68A");
                labelColor = Color.parseColor("#F8FAFC");
                statusColor= Color.parseColor("#FDE68A");
                final String[] spinChars = {"|", "/", "-", "\\"};
                statusStr  = " " + spinChars[preflightAnimTick % 4];
                badge.setText(number);
                detail.setTextColor(Color.parseColor("#92400E"));
                break;
            default: // SS_PENDING
                badgeBg    = Color.parseColor("#0F172A");
                badgeFg    = Color.parseColor("#334155");
                labelColor = Color.parseColor("#334155");
                statusColor= Color.parseColor("#1E293B");
                statusStr  = "---";
                badge.setText(number);
                detail.setTextColor(Color.parseColor("#1E293B"));
                break;
        }
        badge.setBackgroundColor(badgeBg);
        badge.setTextColor(badgeFg);
        label.setTextColor(labelColor);
        status.setText(statusStr);
        status.setTextColor(statusColor);
        detail.setText(detailText != null ? detailText : "");
    }

    private String spinnerGlyph() {
        switch (preflightAnimTick) {
            case 1:  return "/";
            case 2:  return "-";
            case 3:  return "\\";
            default: return "|";
        }
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
        if (bps <= 0) {
            return "-";
        }
        if (bps >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0));
        }
        if (bps >= 1024L) {
            return String.format(Locale.US, "%.1f KB/s", bps / 1024.0);
        }
        return bps + " B/s";
    }

    private int[] computeScaledSize() {
        String profile = getSelectedProfile();
        int baseW = "ultra".equals(profile) ? 2560 : 1920;
        int baseH = "ultra".equals(profile) ? 1440 : 1080;

        int scale = getResolutionScalePercent();
        int w = Math.max(640, (baseW * scale / 100) & ~1);
        int h = Math.max(360, (baseH * scale / 100) & ~1);
        return new int[]{w, h};
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
