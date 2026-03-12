package com.wbeam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.wbeam.api.HostApiClient;
import com.wbeam.api.StatusListener;
import com.wbeam.api.StatusPoller;
import com.wbeam.hud.HudDebugLogLimiter;
import com.wbeam.hud.MetricSeriesBuffer;
import com.wbeam.hud.ResourceUsageTracker;
import com.wbeam.hud.RuntimeHudOverlayRenderer;
import com.wbeam.hud.RuntimeTrendGridRenderer;
import com.wbeam.hud.TrainerHudOverlayRenderer;
import com.wbeam.input.CursorOverlayController;
import com.wbeam.input.ImmersiveModeController;
import com.wbeam.startup.PreflightStateMachine;
import com.wbeam.startup.StartupOverlayModelBuilder;
import com.wbeam.startup.StartupOverlayController;
import com.wbeam.startup.StartupStepStyler;
import com.wbeam.startup.TransportProbeCoordinator;
import com.wbeam.stream.H264TcpPlayer;
import com.wbeam.stream.SessionUiBridge;
import com.wbeam.stream.VideoTestController;
import com.wbeam.stream.StreamSessionController;
import com.wbeam.stream.DecoderCapabilityInspector;
import com.wbeam.telemetry.ClientMetricsReporter;
import com.wbeam.telemetry.RuntimeTelemetryMapper;
import com.wbeam.ui.ErrorTextUtil;
import com.wbeam.ui.IntraOnlyButtonController;
import com.wbeam.ui.LiveLogBuffer;
import com.wbeam.ui.MainActivityUiBinder;
import com.wbeam.ui.SettingsSelectionReader;
import com.wbeam.ui.SettingsPayloadBuilder;
import com.wbeam.ui.SettingsPanelController;
import com.wbeam.ui.SettingsUiSupport;
import com.wbeam.ui.SimpleMenuUi;
import com.wbeam.ui.StatusColorResolver;
import com.wbeam.ui.StatusTextFormatter;
import com.wbeam.ui.StreamConfigResolver;
import com.wbeam.widget.FpsLossGraphView;

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

    private static final String[] PROFILE_OPTIONS = {
            "baseline"
    };
    /**
     * Preferred video encoder for this device.
     */
    static final String PREFERRED_VIDEO = DecoderCapabilityInspector.preferredVideoEncoder();
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
    private final HudDebugLogLimiter hudDebugLogLimiter = new HudDebugLogLimiter(HUD_ADB_LOG_INTERVAL_MS);
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
    private final LiveLogBuffer liveLogBuffer = new LiveLogBuffer(LIVE_LOG_MAX_LINES);
    private final ResourceUsageTracker resourceUsageTracker = new ResourceUsageTracker(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimePresentSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeMbpsSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeDropSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeLatencySeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeQueueSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
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
    private SettingsPanelController settingsPanelController;

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
        hwAvcDecodeAvailable = DecoderCapabilityInspector.hasHardwareAvcDecoder(TAG);
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
        videoTestController = createVideoTestController();
        statusPoller = createStatusPoller();
        sessionController = createSessionController();

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

    private VideoTestController createVideoTestController() {
        return new VideoTestController(uiHandler, ioExecutor, new VideoTestController.Callbacks() {
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
                if (liveLogText != null) {
                    liveLogText.setVisibility(v ? View.VISIBLE : View.GONE);
                }
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
    }

    private StatusPoller createStatusPoller() {
        return new StatusPoller(uiHandler, ioExecutor, new StatusPoller.Callbacks() {
            @Override
            public void onDaemonStatusUpdate(boolean reachable, boolean wasReachable,
                    String hostName, String state, long runId, String lastError,
                    boolean errorChanged, long uptimeSec, String service, String buildRevision, JSONObject metrics) {
                handleDaemonStatusUpdate(
                        reachable,
                        wasReachable,
                        hostName,
                        state,
                        runId,
                        lastError,
                        errorChanged,
                        uptimeSec,
                        service,
                        buildRevision,
                        metrics
                );
            }

            @Override
            public void onDaemonOffline(boolean wasReachable, Exception e) {
                handleDaemonOffline(wasReachable, e);
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
    }

    private void handleDaemonStatusUpdate(
            boolean reachable,
            boolean wasReachable,
            String hostName,
            String state,
            long runId,
            String lastError,
            boolean errorChanged,
            long uptimeSec,
            String service,
            String buildRevision,
            JSONObject metrics
    ) {
        daemonReachable = reachable;
        daemonHostName = hostName;
        daemonState = state;
        daemonLastError = lastError;
        daemonRunId = runId;
        daemonUptimeSec = uptimeSec;
        daemonService = service;
        daemonBuildRevision =
                (buildRevision == null || buildRevision.trim().isEmpty()) ? "-" : buildRevision.trim();
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
        updateStatsLineFromMetrics(metrics, lastError);
        updatePerfHud(metrics);
        updatePreflightOverlay();
    }

    private void updateStatsLineFromMetrics(JSONObject metrics, String lastError) {
        if (metrics == null) {
            return;
        }
        long frameIn = metrics.optLong("frame_in", 0);
        long frameOut = metrics.optLong("frame_out", 0);
        long drops = metrics.optLong("drops", 0);
        long reconnects = metrics.optLong("reconnects", 0);
        long bps = metrics.optLong("bitrate_actual_bps", 0);
        String errCompact = lastError.length() > 80 ? lastError.substring(0, 80) + "..." : lastError;
        updateStatsLine("host in/out: " + frameIn + "/" + frameOut
                + " | drops: " + drops + " | reconnects: " + reconnects
                + " | bitrate: " + StatusTextFormatter.formatBps(bps)
                + (errCompact.isEmpty() ? "" : " | last_error: " + errCompact));
    }

    private void handleDaemonOffline(boolean wasReachable, Exception e) {
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
            // Host was reachable and just dropped — restart retry cycle clean.
            startupBeganAtMs = SystemClock.elapsedRealtime();
            controlRetryCount = 0;
        }
        updatePreflightOverlay();
        if (wasReachable) {
            updateStatus(STATE_ERROR, "Host API offline: " + ErrorTextUtil.shortError(e), 0);
            appendLiveLogError("daemon poll failed: " + ErrorTextUtil.shortError(e)
                    + " | api=" + HostApiClient.API_BASE);
            Toast.makeText(MainActivity.this,
                    "Host API unreachable (" + ErrorTextUtil.shortError(e)
                            + "). Check USB tethering/LAN and host IP: " + HostApiClient.API_BASE,
                    Toast.LENGTH_LONG).show();
        } else {
            refreshStatusText();
        }
    }

    private StreamSessionController createSessionController() {
        return new StreamSessionController(
                uiHandler,
                ioExecutor,
                buildSessionUiBridge()
        );
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
        if (settingsPanelController != null && settingsPanelController.isVisible()) {
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
        settingsPanelController = new SettingsPanelController(settingsPanel);
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
        MainActivityUiBinder.setupTrainerHudWebView(perfHudWebView);
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
        MainActivityUiBinder.setupSpinners(
                this,
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                PROFILE_OPTIONS,
                ENCODER_OPTIONS,
                CURSOR_OPTIONS,
                () -> {
                    updateIntraOnlyButton();
                    updateHostHint();
                },
                () -> {
                    enforceCursorOverlayPolicy(false);
                    updateHostHint();
                }
        );
    }

    private void setupSeekbars() {
        MainActivityUiBinder.setupSeekbars(
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                this::updateSettingValueLabels
        );
    }

    private void setupSurfaceCallbacks() {
        SurfaceView preview = findViewById(R.id.previewSurface);
        MainActivityUiBinder.setupSurfaceCallbacks(preview, new MainActivityUiBinder.SurfaceCallbacks() {
            @Override
            public void onSurfaceCreated(Surface nextSurface, boolean ready) {
                surface = nextSurface;
                surfaceReady = ready;
                updatePreflightOverlay();
                updateStatus(STATE_IDLE, "surface ready", 0);
            }

            @Override
            public void onSurfaceChanged(Surface nextSurface, boolean ready) {
                surface = nextSurface;
                surfaceReady = ready;
                updatePreflightOverlay();
            }

            @Override
            public void onSurfaceDestroyed() {
                stopLiveView();
                surface = null;
                surfaceReady = false;
                preflightComplete = false;
                updatePreflightOverlay();
                hideCursorOverlay();
                updateStatus(STATE_IDLE, "surface destroyed", 0);
            }

            @Override
            public boolean isCursorOverlayEnabled() {
                return cursorOverlayController != null && cursorOverlayController.isOverlayEnabled();
            }

            @Override
            public void onCursorOverlayMotion(float x, float y, int actionMasked) {
                updateCursorOverlay(x, y, actionMasked);
            }
        });
    }

    private void setupButtons() {
        bindSettingsCloseButton();
        bindSimpleMenuTouchRefresh();
        bindDebugInfoTouchFade();
        bindSimpleModeButtons();
        bindSimpleFpsButtons();
        bindSimpleApplyButton();
        updateActionButtonsEnabled();
    }

    private void bindSettingsCloseButton() {
        if (settingsCloseButton != null) {
            settingsCloseButton.setOnClickListener(v -> hideSettingsPanel());
        }
    }

    private void bindSimpleMenuTouchRefresh() {
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
    }

    private void bindDebugInfoTouchFade() {
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
    }

    private void bindSimpleModeButtons() {
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
    }

    private void bindSimpleFpsButtons() {
        if (simpleFps30Button != null) simpleFps30Button.setOnClickListener(v -> selectSimpleFps(30));
        if (simpleFps45Button != null) simpleFps45Button.setOnClickListener(v -> selectSimpleFps(45));
        if (simpleFps60Button != null) simpleFps60Button.setOnClickListener(v -> selectSimpleFps(60));
        if (simpleFps90Button != null) simpleFps90Button.setOnClickListener(v -> selectSimpleFps(90));
        if (simpleFps120Button != null) simpleFps120Button.setOnClickListener(v -> selectSimpleFps(120));
        if (simpleFps144Button != null) simpleFps144Button.setOnClickListener(v -> selectSimpleFps(144));
    }

    private void bindSimpleApplyButton() {
        if (simpleApplyButton != null) {
            simpleApplyButton.setOnClickListener(v -> {
                applySimpleMenuToSettings();
                requestStartGuarded(false, true);
                hideSimpleMenu();
            });
        }
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
        SettingsUiSupport.setSpinnerSelection(profileSpinner, PROFILE_OPTIONS, DEFAULT_PROFILE);
        SettingsUiSupport.setSpinnerSelection(encoderSpinner, ENCODER_OPTIONS, PREFERRED_VIDEO);
        SettingsUiSupport.setSpinnerSelection(cursorSpinner, CURSOR_OPTIONS, DEFAULT_CURSOR_MODE);
        resolutionSeek.setProgress(SettingsSelectionReader.clamp(DEFAULT_RES_SCALE, 50, 100) - 50);
        fpsSeek.setProgress(SettingsSelectionReader.clamp(DEFAULT_FPS, 24, 144) - 24);
        bitrateSeek.setProgress(SettingsSelectionReader.clamp(DEFAULT_BITRATE_MBPS, 5, 300) - 5);
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

        resValueText.setText(SettingsUiSupport.resolutionValueLabel(scale, sz[0], sz[1]));
        fpsValueText.setText(SettingsUiSupport.fpsValueLabel(fps));
        bitrateValueText.setText(SettingsUiSupport.bitrateValueLabel(bitrate));
    }

    private void updateIntraOnlyButton() {
        intraOnlyEnabled = IntraOnlyButtonController.apply(
                intraOnlyButton,
                getSelectedEncoder(),
                intraOnlyEnabled
        );
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

    private SessionUiBridge buildSessionUiBridge() {
        return new SessionUiBridge(
                this,
                statusPoller,
                this::updateStatus,
                this::updateDaemonStateFromJson,
                this::ensureDecoderRunning,
                this::stopLiveView,
                this::appendLiveLogWarn,
                this::handleApiFailure,
                this::buildConfigPayload
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
        if (!isSurfaceReadyForLiveView()) {
            return;
        }
        if (isLivePlayerAlreadyRunning()) {
            return;
        }

        StreamConfigResolver.Resolved cfg = effectiveStreamConfig();
        long frameUs = Math.max(1L, 1_000_000L / Math.max(1, cfg.fps));
        SurfaceView preview = findViewById(R.id.previewSurface);
        logStartLiveViewConfig(cfg, preview);
        // Keep the Surface buffer sized from layout so video can scale to fill the screen.
        // setFixedSize(stream_w, stream_h) causes a "small centered video" effect whenever the
        // stream resolution differs from the view size (default config scales stream size).
        preview.getHolder().setSizeFromLayout();
        logSurfaceFrame(preview);
        createAndStartPlayer(cfg, frameUs);
        hideSettingsPanel();
    }

    private boolean isSurfaceReadyForLiveView() {
        if (surface != null && surface.isValid()) {
            return true;
        }
        updateStatus(STATE_ERROR, "surface not ready yet", 0);
        return false;
    }

    private boolean isLivePlayerAlreadyRunning() {
        if (player == null || !player.isRunning()) {
            return false;
        }
        updateStatus(STATE_STREAMING, "already running", 0);
        return true;
    }

    private void logStartLiveViewConfig(StreamConfigResolver.Resolved cfg, SurfaceView preview) {
        Log.i(TAG, String.format(Locale.US,
                "startLiveView: cfg=%dx%d@%dfps view=%dx%d surfaceValid=%s",
                cfg.width, cfg.height, cfg.fps,
                preview.getWidth(), preview.getHeight(),
                surface != null && surface.isValid()));
    }

    private void logSurfaceFrame(SurfaceView preview) {
        try {
            Rect frame = preview.getHolder().getSurfaceFrame();
            if (frame != null) {
                Log.i(TAG, String.format(Locale.US,
                        "startLiveView: surfaceFrame=%dx%d",
                        frame.width(), frame.height()));
            }
        } catch (Exception ignored) {
        }
    }

    private void createAndStartPlayer(StreamConfigResolver.Resolved cfg, long frameUs) {
        player = new H264TcpPlayer(
                surface,
                buildPlayerStatusListener(),
                cfg.width,
                cfg.height,
                frameUs
        );
        player.start();
    }

    private StatusListener buildPlayerStatusListener() {
        return new StatusListener() {
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
        };
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
        if (settingsPanelController != null) {
            settingsPanelController.toggle();
        }
    }

    private void showSettingsPanel() {
        if (settingsPanelController != null) {
            settingsPanelController.show();
        }
    }

    private void hideSettingsPanel() {
        if (settingsPanelController != null) {
            settingsPanelController.hide();
        }
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
        simpleFps = SimpleMenuUi.clampSimpleFps(fps);
        refreshSimpleMenuButtons();
        scheduleSimpleMenuAutoHide();
    }

    private void showSimpleMenu() {
        if (simpleMenuPanel == null) {
            return;
        }
        simpleMode = SimpleMenuUi.modeFromEncoder(getSelectedEncoder(), PREFERRED_VIDEO);
        simpleFps = SimpleMenuUi.clampSimpleFps(getSelectedFps());
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
        String selectedEncoder = SimpleMenuUi.encoderFromMode(simpleMode, PREFERRED_VIDEO);
        int selectedFps = SimpleMenuUi.clampSimpleFps(simpleFps);

        SettingsUiSupport.setSpinnerSelection(encoderSpinner, ENCODER_OPTIONS, selectedEncoder);
        fpsSeek.setProgress(SettingsSelectionReader.clamp(selectedFps, 24, 144) - 24);

        updateIntraOnlyButton();
        updateSettingValueLabels();
        updateHostHint();
    }

    private void refreshSimpleMenuButtons() {
        if (simpleMenuPanel == null) {
            return;
        }
        SimpleMenuUi.applyModeButtons(
                simpleModeH265Button,
                simpleModeRawButton,
                PREFERRED_VIDEO,
                simpleMode
        );
        SimpleMenuUi.applyFpsButtons(
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                simpleFps
        );
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
            liveLogText.setText(liveLogBuffer.append(level, line));
            liveLogText.setVisibility(liveLogVisible ? View.VISIBLE : View.GONE);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            runOnUiThread(task);
        }
    }

    private void refreshStatusText() {
        int color = StatusColorResolver.ledColorForState(lastUiState, STATE_STREAMING, STATE_CONNECTING);
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
        if (handleMissingPerfMetrics(metrics, nowMs)) {
            return;
        }
        lastPerfMetricsAtMs = nowMs;
        if (handleTrainerHudPath(metrics, nowMs)) {
            return;
        }
        hudOverlayMode = "runtime";
        updateRuntimePerfHud(metrics, nowMs);
    }

    private boolean handleMissingPerfMetrics(JSONObject metrics, long nowMs) {
        if (metrics != null) {
            return false;
        }
        if (daemonReachable
                && lastPerfMetricsAtMs > 0L
                && (nowMs - lastPerfMetricsAtMs) <= METRICS_STALE_GRACE_MS) {
            emitHudDebugAdb("state=metrics_stale grace=1");
            return true;
        }
        updatePerfHudUnavailable();
        return true;
    }

    private boolean handleTrainerHudPath(JSONObject metrics, long nowMs) {
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
            return true;
        }
        if (isTrainingConnection && trainerHudFromText) {
            renderTrainerHudOverlay(trainerHudText);
            return true;
        }
        if (isTrainingConnection && trainerHudActive) {
            if (lastTrainerHudPayloadAtMs > 0L
                    && (nowMs - lastTrainerHudPayloadAtMs) <= TRAINER_HUD_PAYLOAD_GRACE_MS) {
                emitHudDebugAdb("trainer_payload_gap grace=1 keep_last=1");
                return true;
            }
            renderTrainerHudOverlayPlaceholder();
            return true;
        }
        if (isTrainingConnection && trainerHudSessionActive) {
            if (lastTrainerHudPayloadAtMs > 0L
                    && (nowMs - lastTrainerHudPayloadAtMs) <= TRAINER_HUD_PAYLOAD_GRACE_MS) {
                emitHudDebugAdb("trainer_payload_missing grace=1 keep_last=1");
                return true;
            }
            showHudTextOnly("trainer", "TRAINING HUD\nwaiting for trainer metrics...", "#B3EAF4FF");
            lastHudCompactLine = "trainer hud waiting metrics";
            refreshDebugInfoOverlay();
            return true;
        }
        return isTrainingConnection;
    }

    private static final class RuntimePressureState {
        final boolean warmingUp;
        final String reason;
        final String tone;

        RuntimePressureState(boolean warmingUp, String reason, String tone) {
            this.warmingUp = warmingUp;
            this.reason = reason;
            this.tone = tone;
        }
    }

    private void updateRuntimePerfHud(JSONObject metrics, long nowMs) {
        RuntimeTelemetryMapper.Snapshot runtime = RuntimeTelemetryMapper.map(
                metrics,
                getSelectedFps(),
                TRANSPORT_QUEUE_MAX_FRAMES,
                DECODE_QUEUE_MAX_FRAMES,
                RENDER_QUEUE_MAX_FRAMES
        );
        long frameInHost = runtime.frameInHost;
        long frameOutHost = runtime.frameOutHost;
        long streamUptimeSec = runtime.streamUptimeSec;

        double targetFps = runtime.targetFps;
        double presentFps = stabilizePresentFps(runtime, nowMs);
        double recvFps = runtime.recvFps;
        double decodeFps = runtime.decodeFps;
        String daemonStateUi = effectiveDaemonState(daemonState, presentFps, streamUptimeSec, frameOutHost);
        latestTargetFps = targetFps;
        latestPresentFps = presentFps;
        latestStreamUptimeSec = streamUptimeSec;
        latestFrameOutHost = frameOutHost;
        double frametimeP95 = runtime.frametimeP95;
        double decodeP95 = runtime.decodeP95;
        double renderP95 = runtime.renderP95;
        double e2eP95 = runtime.e2eP95;

        int qT = runtime.qT;
        int qD = runtime.qD;
        int qR = runtime.qR;

        int qTMax = runtime.qTMax;
        int qDMax = runtime.qDMax;
        int qRMax = runtime.qRMax;

        int adaptiveLevel = runtime.adaptiveLevel;
        String adaptiveAction = runtime.adaptiveAction;
        long drops = runtime.drops;
        long bpHigh = runtime.bpHigh;
        long bpRecover = runtime.bpRecover;
        String reason = runtime.reason;

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

        RuntimePressureState pressureState = evaluateRuntimePressure(
                targetFps,
                presentFps,
                decodeP95,
                renderP95,
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveAction,
                streamUptimeSec
        );
        long latestDroppedFrames = runtime.latestDroppedFrames;
        long latestTooLateFrames = runtime.latestTooLateFrames;
        double dropPerSec = computeDropRatePerSec(latestDroppedFrames, latestTooLateFrames, nowMs);
        double bitrateMbps = runtime.bitrateMbps;
        runtimePresentSeries.addSample(Math.max(0.0, presentFps));
        runtimeMbpsSeries.addSample(Math.max(0.0, bitrateMbps));
        runtimeDropSeries.addSample(Math.max(0.0, dropPerSec));
        runtimeLatencySeries.addSample(Math.max(0.0, e2eP95));
        runtimeQueueSeries.addSample(Math.max(0.0, qT + qD + qR));
        String runtimeChartsHtml = RuntimeTrendGridRenderer.buildMetricTrendRowsHtml(
                runtimePresentSeries.toJsonFinite(),
                runtimeMbpsSeries.toJsonFinite(),
                runtimeDropSeries.toJsonFinite(),
                runtimeLatencySeries.toJsonFinite(),
                runtimeQueueSeries.toJsonFinite(),
                pressureState.tone,
                pressureState.tone,
                pressureState.tone,
                pressureState.tone,
                pressureState.tone,
                FPS_LOW_ANCHOR
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
                pressureState.tone
        );

        emitHudDebugAdb(buildRuntimeHudDebugSnapshot(
                daemonStateUi,
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
                pressureState,
                reason
        ));
    }

    private double stabilizePresentFps(RuntimeTelemetryMapper.Snapshot runtime, long nowMs) {
        double presentFps = runtime.presentFps;
        boolean hasFlowSignals =
                runtime.streamUptimeSec > 0
                        || runtime.frameOutHost > 0
                        || runtime.recvFps >= 1.0
                        || runtime.decodeFps >= 1.0;
        if (presentFps >= 1.0) {
            latestStablePresentFps = presentFps;
            latestStablePresentFpsAtMs = nowMs;
            return presentFps;
        }
        if (hasFlowSignals
                && latestStablePresentFps >= 1.0
                && (nowMs - latestStablePresentFpsAtMs) <= PRESENT_FPS_STALE_GRACE_MS) {
            return latestStablePresentFps;
        }
        return presentFps;
    }

    private RuntimePressureState evaluateRuntimePressure(
            double targetFps,
            double presentFps,
            double decodeP95,
            double renderP95,
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax,
            String adaptiveAction,
            long streamUptimeSec
    ) {
        boolean warmingUp = presentFps < 1.0 && streamUptimeSec < 5;
        StringBuilder pressureReasonBuilder = new StringBuilder();
        double fpsFloor = targetFps * 0.90;
        boolean fpsUnderPressure = presentFps > 0.0 && presentFps < fpsFloor;
        boolean timingPressure = decodeP95 > 12.0 || renderP95 > 7.0;
        boolean queuePressure = qT >= qTMax || qD >= qDMax || qR >= qRMax;
        if (!warmingUp) {
            if (fpsUnderPressure) {
                pressureReasonBuilder.append("fps<").append(String.format(Locale.US, "%.1f", fpsFloor));
            }
            if (decodeP95 > 12.0) {
                appendPressureSegment(pressureReasonBuilder, "dec>12(" + String.format(Locale.US, "%.1f", decodeP95) + ")");
            }
            if (renderP95 > 7.0) {
                appendPressureSegment(pressureReasonBuilder, "ren>7(" + String.format(Locale.US, "%.1f", renderP95) + ")");
            }
            if (qT >= qTMax) {
                appendPressureSegment(pressureReasonBuilder, "qT=" + qT + "/" + qTMax);
            }
            if (qD >= qDMax) {
                appendPressureSegment(pressureReasonBuilder, "qD=" + qD + "/" + qDMax);
            }
            if (qR >= qRMax) {
                appendPressureSegment(pressureReasonBuilder, "qR=" + qR + "/" + qRMax);
            }
        }
        String pressureReason = pressureReasonBuilder.length() > 0
                ? pressureReasonBuilder.toString()
                : (warmingUp ? "warmup" : "ok");

        boolean highPressure = !warmingUp && fpsUnderPressure && (timingPressure || queuePressure);
        boolean mediumPressure = !warmingUp && (
                adaptiveAction.startsWith("degrade")
                        || fpsUnderPressure
                        || timingPressure
                        || queuePressure
        );
        String runtimeTone = "ok";
        if (highPressure) {
            runtimeTone = "risk";
            Log.w(TAG, "HUD RED warmingUp=" + warmingUp + " hp=" + pressureReason
                    + " dec_p95=" + String.format(Locale.US, "%.2f", decodeP95)
                    + " ren_p95=" + String.format(Locale.US, "%.2f", renderP95)
                    + " qT=" + qT + "/" + qTMax
                    + " qD=" + qD + "/" + qDMax
                    + " qR=" + qR + "/" + qRMax
                    + " fps_present=" + String.format(Locale.US, "%.1f", presentFps)
                    + " stream_up=" + streamUptimeSec + "s");
        } else if (warmingUp || mediumPressure) {
            runtimeTone = "warn";
        }
        return new RuntimePressureState(warmingUp, pressureReason, runtimeTone);
    }

    private void appendPressureSegment(StringBuilder sb, String segment) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(segment);
    }

    private double computeDropRatePerSec(long latestDroppedFrames, long latestTooLateFrames, long nowMs) {
        if (latestDroppedFrames < 0L) {
            return 0.0;
        }
        long combined = latestDroppedFrames + Math.max(0L, latestTooLateFrames);
        double dropPerSec = 0.0;
        if (runtimeDropPrevCount >= 0L && runtimeDropPrevAtMs > 0L && nowMs > runtimeDropPrevAtMs) {
            long deltaFrames = Math.max(0L, combined - runtimeDropPrevCount);
            long deltaMs = Math.max(1L, nowMs - runtimeDropPrevAtMs);
            dropPerSec = (deltaFrames * 1000.0) / deltaMs;
        }
        runtimeDropPrevCount = combined;
        runtimeDropPrevAtMs = nowMs;
        return dropPerSec;
    }

    private String buildRuntimeHudDebugSnapshot(
            String daemonStateUi,
            long frameInHost,
            long frameOutHost,
            double targetFps,
            double presentFps,
            double frametimeP95,
            double decodeP95,
            double renderP95,
            double e2eP95,
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
            RuntimePressureState pressureState,
            String reason
    ) {
        return String.format(
                Locale.US,
                "state=%s run_id=%d up=%ds stream_up=%ds host_in_out=%d/%d fps_target=%.0f fps_present=%.1f frame_p95=%.2f dec_p95=%.2f ren_p95=%.2f e2e_p95=%.2f q=%d/%d/%d qmax=%d/%d/%d adapt=L%d:%s drops=%d bp=%d/%d warmup=%b hp=%s reason=%s host_err=%s",
                daemonStateUi,
                daemonRunId,
                daemonUptimeSec,
                latestStreamUptimeSec,
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
                pressureState.warmingUp,
                pressureState.reason,
                reason.isEmpty() ? "-" : reason,
                daemonLastError.isEmpty()
                        ? "-"
                        : (daemonLastError.length() > 44
                        ? daemonLastError.substring(0, 44) + "..."
                        : daemonLastError)
        );
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
        RuntimeHudOverlayRenderer.Input input = new RuntimeHudOverlayRenderer.Input();
        int[] streamSize = computeScaledSize();
        input.daemonReachable = daemonReachable;
        input.selectedProfile = getSelectedProfile();
        input.selectedEncoder = getSelectedEncoder();
        input.streamWidth = streamSize[0];
        input.streamHeight = streamSize[1];
        input.daemonHostName = daemonHostName;
        input.daemonStateUi = daemonStateUi;
        input.daemonBuildRevision = daemonBuildRevision;
        input.appBuildRevision = BuildConfig.WBEAM_BUILD_REV;
        input.daemonLastError = daemonLastError;
        input.tone = tone;
        input.targetFps = targetFps;
        input.presentFps = presentFps;
        input.recvFps = recvFps;
        input.decodeFps = decodeFps;
        input.liveMbps = liveMbps;
        input.e2eP95 = e2eP95;
        input.decodeP95 = decodeP95;
        input.renderP95 = renderP95;
        input.frametimeP95 = frametimeP95;
        input.dropsPerSec = dropsPerSec;
        input.qT = qT;
        input.qD = qD;
        input.qR = qR;
        input.qTMax = qTMax;
        input.qDMax = qDMax;
        input.qRMax = qRMax;
        input.adaptiveLevel = adaptiveLevel;
        input.adaptiveAction = adaptiveAction;
        input.drops = drops;
        input.bpHigh = bpHigh;
        input.bpRecover = bpRecover;
        input.reason = reason;
        input.metricChartsHtml = metricChartsHtml;
        RuntimeHudOverlayRenderer.Rendered rendered = RuntimeHudOverlayRenderer.render(
                input,
                (target, render) -> {
                    resourceUsageTracker.sample(target, render);
                    return resourceUsageTracker.buildRowsHtml();
                }
        );

        if (perfHudWebView != null) {
            if (!showHudWebHtml("runtime", rendered.html) && perfHudText != null) {
                showHudTextOnly("runtime", rendered.textFallback, "#B3EAF4FF");
            }
        } else if (perfHudText != null) {
            showHudTextOnly("runtime", rendered.textFallback, "#B3EAF4FF");
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
    }

    private void renderTrainerHudOverlay(String rawHudText) {
        if (perfHudText == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.fromText(
                rawHudText,
                latestTargetFps,
                FPS_LOW_ANCHOR,
                (targetFps, renderP95Ms) -> {
                    resourceUsageTracker.sample(targetFps, renderP95Ms);
                    return resourceUsageTracker.buildRowsHtml();
                }
        );
        if (rendered.html.isEmpty() && rendered.textFallback.isEmpty()) {
            return;
        }
        applyTrainerHudRendered(rendered);
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
    }

    private void renderTrainerHudOverlayJson(JSONObject hudJson) {
        if (perfHudText == null || hudJson == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.fromJson(
                hudJson,
                latestTargetFps,
                FPS_LOW_ANCHOR,
                (targetFps, renderP95Ms) -> {
                    resourceUsageTracker.sample(targetFps, renderP95Ms);
                    return resourceUsageTracker.buildRowsHtml();
                }
        );
        applyTrainerHudRendered(rendered);
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
    }

    private void renderTrainerHudOverlayPlaceholder() {
        if (perfHudText == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.placeholder(
                latestTargetFps,
                FPS_LOW_ANCHOR,
                (targetFps, renderP95Ms) -> {
                    resourceUsageTracker.sample(targetFps, renderP95Ms);
                    return resourceUsageTracker.buildRowsHtml();
                }
        );
        applyTrainerHudRendered(rendered);
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
    }

    private void applyTrainerHudRendered(TrainerHudOverlayRenderer.Rendered rendered) {
        if (perfHudWebView != null) {
            if (!showHudWebHtml("trainer", rendered.html)) {
                showHudTextOnly("trainer", rendered.textFallback, "#B3EAF4FF");
            }
        } else {
            showHudTextOnly("trainer", rendered.textFallback, "#B3EAF4FF");
        }
        lastHudCompactLine = rendered.compactLine;
        refreshDebugInfoOverlay();
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

        if (applyVideoTestOverlayIfActive()) {
            return;
        }

        boolean shouldProbe = requiresTransportProbe();
        maybeStartTransportProbeIfReady(shouldProbe);

        StartupOverlayModelBuilder.Input input = buildStartupOverlayInput(shouldProbe);
        StartupOverlayModelBuilder.Model model = StartupOverlayModelBuilder.build(input);
        startupBeganAtMs = model.updatedStartupBeganAtMs;
        controlRetryCount = model.updatedControlRetryCount;

        applyStartupOverlayModel(model);
        applyPreflightTransition(model.allOk);
    }

    private boolean applyVideoTestOverlayIfActive() {
        if (videoTestController == null || !videoTestController.isOverlayActive()) {
            return false;
        }
        if (startupTitleText != null) {
            startupTitleText.setText(videoTestController.getOverlayTitle());
        }
        if (startupSubtitleText != null) {
            startupSubtitleText.setText(videoTestController.getOverlayBody());
        }
        if (startupInfoText != null) {
            startupInfoText.setText(videoTestController.getOverlayHint());
            startupInfoText.setTextColor(Color.parseColor("#FDE68A"));
        }
        setPreflightVisible(true);
        return true;
    }

    private void maybeStartTransportProbeIfReady(boolean shouldProbe) {
        if (daemonReachable && handshakeResolved && !isBuildMismatch() && shouldProbe) {
            maybeStartTransportProbe();
        }
    }

    private StartupOverlayModelBuilder.Input buildStartupOverlayInput(boolean shouldProbe) {
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
        return input;
    }

    private void applyStartupOverlayModel(StartupOverlayModelBuilder.Model model) {
        StartupStepStyler.applyStepState(
                model.step1State,
                preflightAnimTick,
                StartupOverlayModelBuilder.Model.SS_OK,
                StartupOverlayModelBuilder.Model.SS_ERROR,
                StartupOverlayModelBuilder.Model.SS_ACTIVE,
                "1", startupStep1Card, startupStep1Badge, startupStep1Label,
                startupStep1Status, startupStep1Detail, model.step1Detail);
        StartupStepStyler.applyStepState(
                model.step2State,
                preflightAnimTick,
                StartupOverlayModelBuilder.Model.SS_OK,
                StartupOverlayModelBuilder.Model.SS_ERROR,
                StartupOverlayModelBuilder.Model.SS_ACTIVE,
                "2", startupStep2Card, startupStep2Badge, startupStep2Label,
                startupStep2Status, startupStep2Detail, model.step2Detail);
        StartupStepStyler.applyStepState(
                model.step3State,
                preflightAnimTick,
                StartupOverlayModelBuilder.Model.SS_OK,
                StartupOverlayModelBuilder.Model.SS_ERROR,
                StartupOverlayModelBuilder.Model.SS_ACTIVE,
                "3", startupStep3Card, startupStep3Badge, startupStep3Label,
                startupStep3Status, startupStep3Detail, model.step3Detail);

        if (startupSubtitleText != null) {
            startupSubtitleText.setText(model.subtitle);
            startupSubtitleText.setTextColor(model.step3State == StartupOverlayModelBuilder.Model.SS_OK
                    ? Color.parseColor("#4ADE80")
                    : model.step3State == StartupOverlayModelBuilder.Model.SS_ERROR
                    ? Color.parseColor("#F87171")
                    : Color.parseColor("#475569"));
        }

        if (startupInfoText != null) {
            startupInfoText.setText(model.infoLog);
            startupInfoText.setTextColor(Color.parseColor("#CBD5E1"));
        }
    }

    private void applyPreflightTransition(boolean allOk) {
        PreflightStateMachine.Transition transition =
                PreflightStateMachine.next(allOk, startupDismissed, 800L);
        startupDismissed = transition.startupDismissed;
        preflightComplete = transition.preflightComplete;
        if (transition.showOverlayNow) {
            setPreflightVisible(true);
        }
        if (transition.scheduleHide) {
            uiHandler.postDelayed(() -> {
                if (startupDismissed) {
                    setPreflightVisible(false);
                }
            }, transition.hideDelayMs);
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

    private void emitHudDebugAdb(String snapshot) {
        if (hudDebugLogLimiter.shouldLog(snapshot)) {
            Log.i(TAG, "HUDDBG " + snapshot);
        }
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
        return SettingsSelectionReader.resolutionScalePercent(resolutionSeek);
    }

    private int getSelectedFps() {
        return SettingsSelectionReader.selectedFps(fpsSeek);
    }

    private int getSelectedBitrateMbps() {
        return SettingsSelectionReader.selectedBitrateMbps(bitrateSeek);
    }

    private String getSelectedProfile() {
        return SettingsSelectionReader.selectedItem(profileSpinner, DEFAULT_PROFILE);
    }

    private String getSelectedEncoder() {
        return SettingsSelectionReader.selectedItem(encoderSpinner, PREFERRED_VIDEO);
    }

    private String getSelectedCursorMode() {
        return SettingsSelectionReader.selectedItem(cursorSpinner, DEFAULT_CURSOR_MODE);
    }

}
