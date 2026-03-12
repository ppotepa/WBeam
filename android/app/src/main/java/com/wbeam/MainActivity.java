package com.wbeam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
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
import com.wbeam.hud.HudOverlayDisplay;
import com.wbeam.api.StatusListener;
import com.wbeam.api.StatusPoller;
import com.wbeam.hud.HudDebugLogLimiter;
import com.wbeam.hud.MetricSeriesBuffer;
import com.wbeam.hud.ResourceUsageTracker;
import com.wbeam.hud.RuntimeHudComputation;
import com.wbeam.hud.RuntimeHudOverlayPipeline;
import com.wbeam.hud.RuntimeHudTrendComposer;
import com.wbeam.hud.TrainerHudOverlayRenderer;
import com.wbeam.input.CursorOverlayController;
import com.wbeam.input.ImmersiveModeController;
import com.wbeam.startup.PreflightStateMachine;
import com.wbeam.startup.StartupOverlayInputFactory;
import com.wbeam.startup.StartupOverlayModelBuilder;
import com.wbeam.startup.StartupOverlayController;
import com.wbeam.startup.StartupOverlayViewRenderer;
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
import com.wbeam.ui.MainActivityRuntimeStateView;
import com.wbeam.ui.MainActivityUiBinder;
import com.wbeam.ui.MainActivitySettingsPresenter;
import com.wbeam.ui.MainActivityStatusPresenter;
import com.wbeam.ui.SettingsSelectionReader;
import com.wbeam.ui.SettingsPayloadBuilder;
import com.wbeam.ui.SettingsPanelController;
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
    private static final int HUD_TEXT_COLOR_OFFLINE = 0xFFFCA5A5;
    private static final int HUD_TEXT_COLOR_LIVE = 0xB3EAF4FF;
    private static final int STARTUP_VIDEO_TEST_HINT_COLOR = 0xFFFDE68A;
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
    private final StartupOverlayViewRenderer.Views startupOverlayViews = new StartupOverlayViewRenderer.Views();
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
    private String lastStatsLine = MainActivityStatusPresenter.DEFAULT_STATS_LINE;
    private String lastHudCompactLine = "hud: waiting for metrics";
    private final HudOverlayDisplay.State hudOverlayState = new HudOverlayDisplay.State();
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

        initializeUiBindings();
        ensureNotificationPermissionIfNeeded();
        initializeControllers();
        initializeStartupState();
    }

    private void initializeUiBindings() {
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
    }

    private void ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                1001
        );
    }

    private void initializeControllers() {
        metricsReporter = new ClientMetricsReporter(ioExecutor, msg -> appendLiveLogWarn(msg));
        videoTestController = createVideoTestController();
        statusPoller = createStatusPoller();
        sessionController = createSessionController();
    }

    private void initializeStartupState() {
        applySettings(false);
        setDebugControlsVisible(false);
        applyBuildVariantUi();
        updateStatsLine(MainActivityStatusPresenter.DEFAULT_STATS_LINE);
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
            @Override public void stopVideoPlayer() { stopVideoPlayerForTestMode(); }
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
            @Override public void setLiveLogVisible(boolean v) { setLiveLogVisibleForTestMode(v); }
            @Override public void showToast(String msg, boolean longT) { showVideoTestToast(msg, longT); }
            @Override public VideoTestController.TestConfig getTestConfig() { return buildVideoTestConfig(); }
        });
    }

    private void stopVideoPlayerForTestMode() {
        if (player != null) {
            player.stop();
            player = null;
        }
    }

    private void setLiveLogVisibleForTestMode(boolean visible) {
        liveLogVisible = visible;
        if (liveLogText != null) {
            liveLogText.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void showVideoTestToast(String message, boolean longToast) {
        Toast.makeText(
                MainActivity.this,
                message,
                longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT
        ).show();
    }

    private VideoTestController.TestConfig buildVideoTestConfig() {
        int[] sz = computeScaledSize();
        return new VideoTestController.TestConfig(
                getSelectedProfile(),
                getSelectedEncoder(),
                getSelectedCursorMode(),
                sz[0],
                sz[1],
                getSelectedFps(),
                getSelectedBitrateMbps()
        );
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
                handleAutoStartRequired();
            }

            @Override
            public void onAutoStartFailed() {
                handleAutoStartFailed();
            }

            @Override
            public void ensureDecoderRunning() {
                handleEnsureDecoderRunning();
            }
        });
    }

    private void handleAutoStartRequired() {
        requestStartGuarded(false, true);
    }

    private void handleAutoStartFailed() {
        appendLiveLogWarn("auto-start paused after failed capture; tap Start Live to retry");
    }

    private void handleEnsureDecoderRunning() {
        ensureDecoderRunning();
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
        applyDaemonStatusSnapshot(
                reachable,
                hostName,
                state,
                lastError,
                runId,
                uptimeSec,
                service,
                buildRevision
        );
        updateHandshakeResolution(service);
        startTransportProbeIfNeeded();
        handleStatusTransitionNotifications(wasReachable, hostName, errorChanged, lastError, state);
        refreshUiAfterDaemonStateChange();
        updateStatsLineFromMetrics(metrics, lastError);
        updatePerfHud(metrics);
    }

    private void updateHandshakeResolution(String service) {
        if (!handshakeResolved && !"-".equals(service)) {
            handshakeResolved = true;
        }
    }

    private void startTransportProbeIfNeeded() {
        if (requiresTransportProbe()) {
            maybeStartTransportProbe();
        }
    }

    private void handleStatusTransitionNotifications(
            boolean wasReachable,
            String hostName,
            boolean errorChanged,
            String lastError,
            String state
    ) {
        if (!wasReachable) {
            notifyConnectedHost(hostName);
        }
        if (errorChanged && !lastError.isEmpty()) {
            appendLiveLogError("host last_error: " + lastError);
        }
        if (shouldStopLiveViewForDaemonState(state)) {
            stopLiveView();
        }
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
        updateStatsLine(MainActivityStatusPresenter.buildHostStatsLine(
                frameIn,
                frameOut,
                drops,
                reconnects,
                bps,
                lastError
        ));
    }

    private void handleDaemonOffline(boolean wasReachable, Exception e) {
        applyDisconnectedDaemonState();
        updateOfflineUiState(wasReachable);
        handleOfflineErrorNotification(wasReachable, e);
    }

    private void applyDisconnectedDaemonState() {
        daemonReachable = false;
        daemonState = "DISCONNECTED";
        stopLiveView();
        handshakeResolved = false;
        transportProbe.markWaitingForControlLink();
    }

    private void updateOfflineUiState(boolean wasReachable) {
        updateActionButtonsEnabled();
        updateHostHint();
        updatePerfHudUnavailable();
        resetStartupAfterDisconnect(wasReachable);
        updatePreflightOverlay();
    }

    private void handleOfflineErrorNotification(boolean wasReachable, Exception e) {
        if (!wasReachable) {
            refreshStatusText();
            return;
        }
        String shortError = ErrorTextUtil.shortError(e);
        updateStatus(STATE_ERROR, "Host API offline: " + shortError, 0);
        appendLiveLogError("daemon poll failed: " + shortError + " | api=" + HostApiClient.API_BASE);
        Toast.makeText(MainActivity.this,
                "Host API unreachable (" + shortError
                        + "). Check USB tethering/LAN and host IP: " + HostApiClient.API_BASE,
                Toast.LENGTH_LONG).show();
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
        performLifecycleCleanup();
        super.onDestroy();
    }

    private void performLifecycleCleanup() {
        statusPoller.stop();
        stopPreflightPulse();
        uiHandler.removeCallbacks(simpleMenuAutoHideTask);
        uiHandler.removeCallbacks(debugInfoFadeTask);
        uiHandler.removeCallbacks(debugGraphSampleTask);
        uiHandler.removeCallbacks(debugOverlayToggleTask);
        videoTestController.release();
        stopLiveView();
        ioExecutor.shutdownNow();
    }

    private void applyDaemonStatusSnapshot(
            boolean reachable,
            String hostName,
            String state,
            String lastError,
            long runId,
            long uptimeSec,
            String service,
            String buildRevision
    ) {
        daemonReachable = reachable;
        daemonHostName = hostName;
        daemonState = state;
        daemonLastError = lastError;
        daemonRunId = runId;
        daemonUptimeSec = uptimeSec;
        daemonService = service;
        daemonBuildRevision = (buildRevision == null || buildRevision.trim().isEmpty())
                ? "-"
                : buildRevision.trim();
    }

    private void refreshUiAfterDaemonStateChange() {
        updateActionButtonsEnabled();
        updateHostHint();
        refreshStatusText();
        updatePreflightOverlay();
    }

    private void notifyConnectedHost(String hostName) {
        Toast.makeText(MainActivity.this, "Connected to " + hostName, Toast.LENGTH_SHORT).show();
        appendLiveLogInfo("connected to host " + hostName);
    }

    private boolean shouldStopLiveViewForDaemonState(String state) {
        return !"STREAMING".equals(state)
                && !"STARTING".equals(state)
                && !"RECONNECTING".equals(state);
    }

    private void resetStartupAfterDisconnect(boolean wasReachable) {
        preflightComplete = false;
        startupDismissed = false;
        if (wasReachable) {
            // Host was reachable and just dropped — restart retry cycle clean.
            startupBeganAtMs = SystemClock.elapsedRealtime();
            controlRetryCount = 0;
        }
    }

    @Override
    public void onBackPressed() {
        if (handleBackNavigation()) {
            return;
        }
        super.onBackPressed();
    }

    private boolean handleBackNavigation() {
        if (simpleMenuVisible) {
            hideSimpleMenu();
            return true;
        }
        if (settingsPanelController != null && settingsPanelController.isVisible()) {
            hideSettingsPanel();
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleDebugOverlayToggleKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (handleDebugOverlayToggleKeyUp(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean handleDebugOverlayToggleKeyDown(int keyCode, KeyEvent event) {
        if (!BuildConfig.DEBUG
                || (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return false;
        }
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

    private boolean handleDebugOverlayToggleKeyUp(int keyCode) {
        if (!BuildConfig.DEBUG
                || (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return false;
        }
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
        startupOverlayViews.titleText = startupTitleText;
        startupOverlayViews.step1Card = startupStep1Card;
        startupOverlayViews.step2Card = startupStep2Card;
        startupOverlayViews.step3Card = startupStep3Card;
        startupOverlayViews.step1Badge = startupStep1Badge;
        startupOverlayViews.step1Label = startupStep1Label;
        startupOverlayViews.step1Detail = startupStep1Detail;
        startupOverlayViews.step1Status = startupStep1Status;
        startupOverlayViews.step2Badge = startupStep2Badge;
        startupOverlayViews.step2Label = startupStep2Label;
        startupOverlayViews.step2Detail = startupStep2Detail;
        startupOverlayViews.step2Status = startupStep2Status;
        startupOverlayViews.step3Badge = startupStep3Badge;
        startupOverlayViews.step3Label = startupStep3Label;
        startupOverlayViews.step3Detail = startupStep3Detail;
        startupOverlayViews.step3Status = startupStep3Status;
        startupOverlayViews.subtitleText = startupSubtitleText;
        startupOverlayViews.infoText = startupInfoText;
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
        MainActivityUiBinder.applyVisibility(
                View.GONE,
                topBar,
                quickActionRow,
                statusPanel,
                perfHudPanel,
                settingsButton,
                logButton,
                fullscreenButton
        );
        if (BuildConfig.DEBUG) {
            applyDebugVariantUi();
            return;
        }
        applyReleaseVariantUi();
    }

    private void applyDebugVariantUi() {
        setFullscreen(true);
        if (debugFpsGraphView != null) {
            debugFpsGraphView.setCapacity(DEBUG_FPS_GRAPH_POINTS);
        }
        setDebugOverlayVisible(debugOverlayVisible);
        startDebugGraphSampling();
        refreshDebugInfoOverlay();
    }

    private void applyReleaseVariantUi() {
        setFullscreen(true);
        MainActivityUiBinder.applyVisibility(View.GONE, debugInfoPanel);
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
        MainActivityUiBinder.bindSettingsCloseButton(settingsCloseButton, this::hideSettingsPanel);
        MainActivityUiBinder.bindSimpleMenuTouchRefresh(simpleMenuPanel, this::scheduleSimpleMenuAutoHide);
        MainActivityUiBinder.bindDebugInfoTouchFade(
                debugInfoPanel,
                uiHandler,
                debugInfoFadeTask,
                DEBUG_INFO_ALPHA_TOUCH,
                DEBUG_INFO_ALPHA_RESET_MS
        );
        MainActivityUiBinder.bindSimpleModeButtons(
                simpleModeH265Button,
                simpleModeRawButton,
                PREFERRED_VIDEO,
                mode -> {
                    simpleMode = mode;
                    refreshSimpleMenuButtons();
                    scheduleSimpleMenuAutoHide();
                }
        );
        MainActivityUiBinder.bindSimpleFpsButtons(
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                this::selectSimpleFps
        );
        MainActivityUiBinder.bindSimpleApplyButton(simpleApplyButton, () -> {
            applySimpleMenuToSettings();
            requestStartGuarded(false, true);
            hideSimpleMenu();
        });
        updateActionButtonsEnabled();
    }

    private void setDebugOverlayVisible(boolean visible) {
        debugOverlayVisible = visible;
        MainActivityRuntimeStateView.applyDebugOverlayVisibility(
                BuildConfig.DEBUG,
                visible,
                debugInfoPanel,
                perfHudPanel
        );
    }

    private void updateActionButtonsEnabled() {
        boolean enabled = daemonReachable;
        MainActivityUiBinder.applyActionButtonsEnabled(
                enabled,
                quickStartButton,
                quickStopButton,
                quickTestButton,
                startButton,
                stopButton,
                testButton
        );
    }

    private void setDebugControlsVisible(boolean visible) {
        debugControlsVisible = visible;
        MainActivityUiBinder.applyDebugControlsVisible(visible, debugControlsRow, testButton);
    }

    private void toggleLiveLogPanel() {
        liveLogVisible = MainActivityUiBinder.applyLiveLogPanelState(
                liveLogVisible,
                liveLogText,
                logButton
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Settings
    // ══════════════════════════════════════════════════════════════════════════

    private void loadSavedSettings() {
        MainActivitySettingsPresenter.applyDefaultSettings(
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                PROFILE_OPTIONS,
                ENCODER_OPTIONS,
                CURSOR_OPTIONS,
                DEFAULT_PROFILE,
                PREFERRED_VIDEO,
                DEFAULT_CURSOR_MODE,
                DEFAULT_RES_SCALE,
                DEFAULT_FPS,
                DEFAULT_BITRATE_MBPS
        );
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
        MainActivitySettingsPresenter.applySettingValueLabels(
                resValueText,
                fpsValueText,
                bitrateValueText,
                scale,
                fps,
                bitrate,
                sz[0],
                sz[1]
        );
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
        String daemonStateUi = MainActivityRuntimeStateView.effectiveDaemonState(
                daemonState, latestPresentFps, latestStreamUptimeSec, latestFrameOutHost);
        hostHintText.setText(MainActivitySettingsPresenter.buildHostHint(
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
        if (status == null) {
            return;
        }
        applyDaemonStatusSnapshot(
                true,
                status.optString("host_name", daemonHostName),
                status.optString("state", "IDLE").toUpperCase(Locale.US),
                status.optString("last_error", daemonLastError),
                status.optLong("run_id", daemonRunId),
                status.optLong("uptime", daemonUptimeSec),
                daemonService,
                daemonBuildRevision
        );
        refreshUiAfterDaemonStateChange();
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
                this::onSessionBridgeDaemonState,
                this::onSessionBridgeEnsureDecoder,
                this::stopLiveView,
                this::appendLiveLogWarn,
                this::onSessionBridgeApiFailure,
                this::buildSessionConfigPayload
        );
    }

    private void onSessionBridgeDaemonState(JSONObject status) {
        updateDaemonStateFromJson(status);
    }

    private void onSessionBridgeEnsureDecoder() {
        ensureDecoderRunning();
    }

    private void onSessionBridgeApiFailure(String prefix, boolean userAction, Exception e) {
        handleApiFailure(prefix, userAction, e);
    }

    private JSONObject buildSessionConfigPayload() {
        return buildConfigPayload();
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
            handleBuildMismatchStartBlocked();
            return;
        }
        sessionController.requestStart(userAction, ensureViewer);
    }

    private void handleBuildMismatchStartBlocked() {
        String msg = "Build mismatch: app=" + BuildConfig.WBEAM_BUILD_REV
                + " host=" + daemonBuildRevision
                + " (redeploy APK or rebuild host)";
        updateStatus(STATE_ERROR, msg, 0);
        appendLiveLogError(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
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
        simpleFps = MainActivitySettingsPresenter.simpleMenuFpsFromSelection(fps);
        refreshSimpleMenuButtons();
        scheduleSimpleMenuAutoHide();
    }

    private void showSimpleMenu() {
        if (simpleMenuPanel == null) {
            return;
        }
        simpleMode = MainActivitySettingsPresenter.simpleMenuModeFromSelection(
                getSelectedEncoder(),
                PREFERRED_VIDEO
        );
        simpleFps = MainActivitySettingsPresenter.simpleMenuFpsFromSelection(getSelectedFps());
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
        MainActivitySettingsPresenter.applySimpleMenuSettings(
                encoderSpinner,
                fpsSeek,
                ENCODER_OPTIONS,
                PREFERRED_VIDEO,
                simpleMode,
                simpleFps
        );
        updateIntraOnlyButton();
        updateSettingValueLabels();
        updateHostHint();
    }

    private void refreshSimpleMenuButtons() {
        if (simpleMenuPanel == null) {
            return;
        }
        MainActivitySettingsPresenter.applySimpleMenuButtons(
                simpleModeH265Button,
                simpleModeRawButton,
                PREFERRED_VIDEO,
                simpleMode,
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
        lastUiState = MainActivityStatusPresenter.normalizeState(state, STATE_IDLE);
        lastUiInfo = MainActivityStatusPresenter.normalizeInfo(info);
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
        String daemonStateUi = MainActivityRuntimeStateView.effectiveDaemonState(
                daemonState, latestPresentFps, latestStreamUptimeSec, latestFrameOutHost);
        MainActivityStatusPresenter.renderStatus(
                statusText,
                detailText,
                bpsText,
                statusLed,
                lastUiState,
                lastUiInfo,
                lastUiBps,
                daemonReachable,
                daemonHostName,
                daemonStateUi,
                STATE_STREAMING,
                STATE_CONNECTING
        );
    }

    private void updateStatsLine(String line) {
        lastStatsLine = MainActivityStatusPresenter.normalizeStatsLine(line);
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

    private void showHudTextOnly(String modeTag, String text, int color) {
        HudOverlayDisplay.showTextOnly(
                perfHudWebView,
                perfHudText,
                modeTag,
                text,
                color,
                hudOverlayState
        );
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
        showHudTextOnly("offline", "HUD OFFLINE\nwaiting for host metrics...", HUD_TEXT_COLOR_OFFLINE);
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
        hudOverlayState.mode = "runtime";
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
            showHudTextOnly("trainer", "TRAINING HUD\nwaiting for trainer metrics...", HUD_TEXT_COLOR_LIVE);
            lastHudCompactLine = "trainer hud waiting metrics";
            refreshDebugInfoOverlay();
            return true;
        }
        return isTrainingConnection;
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
        RuntimeHudComputation.FpsStabilizationResult fpsStabilization =
                RuntimeHudComputation.stabilizePresentFps(
                        runtime,
                        nowMs,
                        latestStablePresentFps,
                        latestStablePresentFpsAtMs,
                        PRESENT_FPS_STALE_GRACE_MS
                );
        double presentFps = fpsStabilization.presentFps;
        latestStablePresentFps = fpsStabilization.updatedStablePresentFps;
        latestStablePresentFpsAtMs = fpsStabilization.updatedStablePresentFpsAtMs;
        double recvFps = runtime.recvFps;
        double decodeFps = runtime.decodeFps;
        String daemonStateUi = MainActivityRuntimeStateView.effectiveDaemonState(
                daemonState,
                presentFps,
                streamUptimeSec,
                frameOutHost
        );
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

        lastHudCompactLine = RuntimeHudComputation.formatCompactHudLine(
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

        RuntimeHudComputation.PressureState pressureState = RuntimeHudComputation.evaluatePressure(
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
        if (pressureState.highPressure) {
            Log.w(TAG, RuntimeHudComputation.formatHighPressureLog(
                    pressureState,
                    decodeP95,
                    renderP95,
                    qT,
                    qD,
                    qR,
                    qTMax,
                    qDMax,
                    qRMax,
                    presentFps,
                    streamUptimeSec
            ));
        }
        long latestDroppedFrames = runtime.latestDroppedFrames;
        long latestTooLateFrames = runtime.latestTooLateFrames;
        RuntimeHudComputation.DropRateResult dropRate =
                RuntimeHudComputation.computeDropRatePerSec(
                        latestDroppedFrames,
                        latestTooLateFrames,
                        nowMs,
                        runtimeDropPrevCount,
                        runtimeDropPrevAtMs
                );
        double dropPerSec = dropRate.dropPerSec;
        runtimeDropPrevCount = dropRate.updatedPrevCount;
        runtimeDropPrevAtMs = dropRate.updatedPrevAtMs;
        double bitrateMbps = runtime.bitrateMbps;
        String runtimeChartsHtml = RuntimeHudTrendComposer.appendSamplesAndBuildHtml(
                runtimePresentSeries,
                runtimeMbpsSeries,
                runtimeDropSeries,
                runtimeLatencySeries,
                runtimeQueueSeries,
                presentFps,
                bitrateMbps,
                dropPerSec,
                e2eP95,
                qT,
                qD,
                qR,
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

        emitHudDebugAdb(RuntimeHudComputation.buildRuntimeDebugSnapshot(
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
                pressureState,
                reason,
                daemonLastError
        ));
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
        int[] streamSize = computeScaledSize();
        RuntimeHudOverlayPipeline.render(
                daemonReachable,
                getSelectedProfile(),
                getSelectedEncoder(),
                streamSize[0],
                streamSize[1],
                daemonHostName,
                daemonStateUi,
                daemonBuildRevision,
                BuildConfig.WBEAM_BUILD_REV,
                daemonLastError,
                targetFps,
                presentFps,
                recvFps,
                decodeFps,
                e2eP95,
                decodeP95,
                renderP95,
                frametimeP95,
                liveMbps,
                dropsPerSec,
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
                metricChartsHtml,
                tone,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                HUD_TEXT_COLOR_LIVE
        );
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
            if (!HudOverlayDisplay.showWebHtml(
                    perfHudWebView,
                    perfHudText,
                    "trainer",
                    rendered.html,
                    hudOverlayState
            )) {
                showHudTextOnly("trainer", rendered.textFallback, HUD_TEXT_COLOR_LIVE);
            }
        } else {
            showHudTextOnly("trainer", rendered.textFallback, HUD_TEXT_COLOR_LIVE);
        }
        lastHudCompactLine = rendered.compactLine;
        refreshDebugInfoOverlay();
    }

    private void refreshDebugInfoOverlay() {
        String daemonStateUi = MainActivityRuntimeStateView.effectiveDaemonState(
                daemonState, latestPresentFps, latestStreamUptimeSec, latestFrameOutHost);
        MainActivityRuntimeStateView.refreshDebugOverlayText(
                BuildConfig.DEBUG,
                debugInfoText,
                debugInfoPanel,
                lastUiState,
                daemonHostName,
                daemonStateUi,
                latestTargetFps,
                latestPresentFps,
                getSelectedFps(),
                lastStatsLine,
                lastHudCompactLine
        );
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

        StartupOverlayModelBuilder.Input input = StartupOverlayInputFactory.fromRuntimeState(
                daemonReachable,
                daemonHostName,
                daemonService,
                daemonBuildRevision,
                daemonState,
                daemonLastError,
                handshakeResolved,
                isBuildMismatch(),
                shouldProbe,
                transportProbe.isProbeOk(),
                transportProbe.isProbeInFlight(),
                transportProbe.getProbeInfo(),
                BuildConfig.WBEAM_API_IMPL,
                HostApiClient.API_BASE,
                BuildConfig.WBEAM_API_HOST,
                BuildConfig.WBEAM_STREAM_HOST,
                BuildConfig.WBEAM_STREAM_PORT,
                BuildConfig.WBEAM_BUILD_REV,
                lastUiInfo,
                MainActivityRuntimeStateView.effectiveDaemonState(
                        daemonState,
                        latestPresentFps,
                        latestStreamUptimeSec,
                        latestFrameOutHost
                ),
                latestPresentFps,
                startupBeganAtMs,
                controlRetryCount,
                SystemClock.elapsedRealtime(),
                lastStatsLine,
                ErrorTextUtil.compactDaemonErrorForUi(daemonLastError)
        );
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
        StartupOverlayViewRenderer.applyVideoTestOverride(
                startupOverlayViews,
                videoTestController.getOverlayTitle(),
                videoTestController.getOverlayBody(),
                videoTestController.getOverlayHint(),
                STARTUP_VIDEO_TEST_HINT_COLOR
        );
        setPreflightVisible(true);
        return true;
    }

    private void maybeStartTransportProbeIfReady(boolean shouldProbe) {
        if (daemonReachable && handshakeResolved && !isBuildMismatch() && shouldProbe) {
            maybeStartTransportProbe();
        }
    }

    private void applyStartupOverlayModel(StartupOverlayModelBuilder.Model model) {
        StartupOverlayViewRenderer.applyModel(model, preflightAnimTick, startupOverlayViews);
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
                createTransportProbeCallbacks()
        );
    }

    private TransportProbeCoordinator.Callbacks createTransportProbeCallbacks() {
        return new TransportProbeCoordinator.Callbacks() {
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
        };
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
