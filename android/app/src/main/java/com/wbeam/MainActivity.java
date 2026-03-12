package com.wbeam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import com.wbeam.api.StatusPoller;
import com.wbeam.api.StatusPollerCallbacksFactory;
import com.wbeam.hud.HudDebugLogLimiter;
import com.wbeam.hud.MetricSeriesBuffer;
import com.wbeam.hud.ResourceUsageTracker;
import com.wbeam.hud.RuntimeHudComputation;
import com.wbeam.hud.RuntimeHudOverlayPipeline;
import com.wbeam.hud.RuntimeHudTrendComposer;
import com.wbeam.hud.RuntimeHudUpdateState;
import com.wbeam.hud.TrainerHudOverlayPipeline;
import com.wbeam.hud.TrainerHudRouting;
import com.wbeam.input.CursorOverlayController;
import com.wbeam.input.ImmersiveModeController;
import com.wbeam.startup.StartupOverlayCoordinator;
import com.wbeam.startup.StartupOverlayHooksFactory;
import com.wbeam.startup.StartupOverlayInputFactory;
import com.wbeam.startup.StartupOverlayModelBuilder;
import com.wbeam.startup.StartupOverlayController;
import com.wbeam.startup.StartupOverlayViewRenderer;
import com.wbeam.startup.TransportProbeCallbacksFactory;
import com.wbeam.startup.TransportProbeCoordinator;
import com.wbeam.stream.H264TcpPlayer;
import com.wbeam.stream.LiveViewPlaybackCoordinator;
import com.wbeam.stream.SessionUiBridge;
import com.wbeam.stream.VideoTestController;
import com.wbeam.stream.StreamSessionController;
import com.wbeam.stream.DecoderCapabilityInspector;
import com.wbeam.telemetry.ClientMetricsReporter;
import com.wbeam.telemetry.RuntimeTelemetryMapper;
import com.wbeam.ui.ErrorTextUtil;
import com.wbeam.ui.IntraOnlyButtonController;
import com.wbeam.ui.LiveLogBuffer;
import com.wbeam.ui.LiveLogUiAppender;
import com.wbeam.ui.BuildRevisionGuard;
import com.wbeam.ui.HostApiFailureNotifier;
import com.wbeam.ui.HostOfflineFlowCoordinator;
import com.wbeam.ui.HostOfflineHooksFactory;
import com.wbeam.ui.MainActivityRuntimeStateView;
import com.wbeam.ui.MainActivityInteractionPolicy;
import com.wbeam.ui.MainActivityUiBinder;
import com.wbeam.ui.MainActivitySettingsPresenter;
import com.wbeam.ui.MainActivityStatusPresenter;
import com.wbeam.ui.MainActivityStatusTracker;
import com.wbeam.ui.StatusPollerUiUpdateCoordinator;
import com.wbeam.ui.StatusTransitionHooksFactory;
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
    private final MainActivityInteractionPolicy.ToggleState debugToggleState =
            new MainActivityInteractionPolicy.ToggleState();
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
        if (debugToggleState.volumeUpHeld
                && debugToggleState.volumeDownHeld
                && !debugToggleState.debugOverlayToggleArmed) {
            debugToggleState.debugOverlayToggleArmed = true;
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
        MainActivityUiBinder.setupSeekbars(
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                this::updateSettingValueLabels
        );
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
        refreshSettingsUi(false);
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
            @Override
            public void showToast(String msg, boolean longT) {
                Toast.makeText(
                        MainActivity.this,
                        msg,
                        longT ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT
                ).show();
            }
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
        return new StatusPoller(
                uiHandler,
                ioExecutor,
                StatusPollerCallbacksFactory.create(
                        this::handleDaemonStatusUpdate,
                        this::handleDaemonOffline,
                        () -> requestStartGuarded(false, true),
                        () -> appendLiveLogWarn(
                                "auto-start paused after failed capture; tap Start Live to retry"
                        ),
                        this::ensureDecoderRunning
                )
        );
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
        handshakeResolved = StatusPollerUiUpdateCoordinator.resolveHandshake(
                handshakeResolved,
                service
        );
        StatusPollerUiUpdateCoordinator.maybeStartTransportProbe(
                requiresTransportProbe(),
                this::maybeStartTransportProbe
        );
        StatusPollerUiUpdateCoordinator.handleStatusTransition(
                wasReachable,
                hostName,
                errorChanged,
                lastError,
                shouldStopLiveViewForDaemonState(state),
                StatusTransitionHooksFactory.create(
                        this::notifyConnectedHost,
                        changedLastError -> appendLiveLogError("host last_error: " + changedLastError),
                        this::stopLiveView
                )
        );
        refreshUiAfterDaemonStateChange();
        String hostStatsLine = StatusPollerUiUpdateCoordinator.buildStatsLine(metrics, lastError);
        if (hostStatsLine != null) {
            updateStatsLine(hostStatsLine);
        }
        updatePerfHud(metrics);
    }

    private void handleDaemonOffline(boolean wasReachable, Exception e) {
        HostOfflineFlowCoordinator.handle(
                wasReachable,
                e,
                STATE_ERROR,
                HostApiClient.API_BASE,
                HostOfflineHooksFactory.create(
                        this::applyDisconnectedDaemonState,
                        this::updateOfflineUiState,
                        this::refreshStatusText,
                        this::updateStatus,
                        this::appendLiveLogError,
                        message -> Toast.makeText(
                                MainActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show()
                )
        );
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
        if (MainActivityInteractionPolicy.handleBackNavigation(
                simpleMenuVisible,
                settingsPanelController != null && settingsPanelController.isVisible(),
                this::hideSimpleMenu,
                this::hideSettingsPanel
        )) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        MainActivityInteractionPolicy.ToggleAction action =
                MainActivityInteractionPolicy.handleDebugToggleKeyDown(
                        debugToggleState,
                        BuildConfig.DEBUG,
                        keyCode,
                        event.getRepeatCount()
                );
        if (action.handled) {
            if (action.cancelScheduledToggle) {
                uiHandler.removeCallbacks(debugOverlayToggleTask);
            }
            if (action.scheduleToggle) {
                uiHandler.postDelayed(debugOverlayToggleTask, DEBUG_OVERLAY_TOGGLE_HOLD_MS);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        MainActivityInteractionPolicy.ToggleAction action =
                MainActivityInteractionPolicy.handleDebugToggleKeyUp(
                        debugToggleState,
                        BuildConfig.DEBUG,
                        keyCode
                );
        if (action.handled) {
            if (action.cancelScheduledToggle) {
                uiHandler.removeCallbacks(debugOverlayToggleTask);
            }
            if (action.resetArmed) {
                debugToggleState.debugOverlayToggleArmed = false;
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
        simpleMode = PREFERRED_VIDEO;
        simpleFps = DEFAULT_FPS;
        refreshSettingsUi(true);
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
                this::updateDaemonStateFromJson,
                this::ensureDecoderRunning,
                this::stopLiveView,
                this::appendLiveLogWarn,
                this::handleApiFailure,
                this::buildConfigPayload
        );
    }

    private void handleApiFailure(String prefix, boolean userAction, Exception e) {
        HostApiFailureNotifier.handle(
                this,
                TAG,
                STATE_ERROR,
                prefix,
                userAction,
                e,
                HostApiClient.API_BASE,
                this::updateStatus,
                this::appendLiveLogError
        );
    }

    private boolean isBuildMismatch() {
        return BuildRevisionGuard.isMismatch(
                daemonReachable,
                handshakeResolved,
                BuildConfig.WBEAM_API_IMPL,
                daemonBuildRevision,
                BuildConfig.WBEAM_BUILD_REV
        );
    }

    private void requestStartGuarded(boolean userAction, boolean ensureViewer) {
        if (isBuildMismatch()) {
            handleBuildMismatchStartBlocked();
            return;
        }
        sessionController.requestStart(userAction, ensureViewer);
    }

    private void handleBuildMismatchStartBlocked() {
        String msg = BuildRevisionGuard.buildMismatchMessage(
                BuildConfig.WBEAM_BUILD_REV,
                daemonBuildRevision
        );
        updateStatus(STATE_ERROR, msg, 0);
        appendLiveLogError(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stream / media
    // ══════════════════════════════════════════════════════════════════════════

    private void startLiveView() {
        videoTestController.release();
        player = LiveViewPlaybackCoordinator.start(
                TAG,
                surface,
                player,
                findViewById(R.id.previewSurface),
                effectiveStreamConfig(),
                this::updateStatus,
                this::updateStatsLine,
                metricsReporter::push,
                this::runOnUiThread,
                this::hideSettingsPanel,
                STATE_ERROR,
                STATE_STREAMING
        );
    }

    private void stopLiveView() {
        player = LiveViewPlaybackCoordinator.stop(
                player,
                () -> {
                    videoTestController.release();
                    hideCursorOverlay();
                },
                this::updateStatsLine,
                this::updateStatus,
                STATE_IDLE
        );
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
        refreshSettingsUi(false);
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

    private void refreshSettingsUi(boolean includeSimpleMenuButtons) {
        updateIntraOnlyButton();
        updateSettingValueLabels();
        updateHostHint();
        if (includeSimpleMenuButtons) {
            refreshSimpleMenuButtons();
        }
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
        MainActivityStatusTracker.UpdateResult next = MainActivityStatusTracker.update(
                state,
                info,
                bps,
                STATE_IDLE,
                STATE_ERROR,
                SystemClock.elapsedRealtime(),
                30_000L,
                lastCriticalErrorInfo,
                lastCriticalErrorLogAtMs
        );
        lastUiState = next.state;
        lastUiInfo = next.info;
        lastUiBps = next.bps;
        lastCriticalErrorInfo = next.criticalErrorInfo;
        lastCriticalErrorLogAtMs = next.criticalErrorLogAtMs;
        refreshStatusText();
        refreshDebugInfoOverlay();
        if (next.shouldLogCritical) {
            appendLiveLogError(next.criticalLogLine);
            Log.e(TAG, next.criticalLogLine);
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
        LiveLogUiAppender.append(
                liveLogText,
                liveLogBuffer,
                liveLogVisible,
                level,
                line,
                this::runOnUiThread
        );
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
        JSONObject trainerHudJson = metrics.optJSONObject("trainer_hud_json");
        boolean trainerHudFromJson = trainerHudJson != null && trainerHudJson.length() > 0;
        String trainerHudText = metrics.optString("trainer_hud_text", "");
        boolean trainerHudFromText = trainerHudText != null && !trainerHudText.trim().isEmpty();
        boolean trainerHudFlag = metrics.optBoolean("trainer_hud_active", false);

        TrainerHudRouting.Decision decision = TrainerHudRouting.decide(
                connectionMode,
                trainerHudFlag,
                trainerHudFromJson,
                trainerHudFromText,
                nowMs,
                lastTrainerHudPayloadAtMs,
                trainerHudSessionActive,
                TRAINER_HUD_PAYLOAD_GRACE_MS,
                BuildConfig.DEBUG,
                debugOverlayVisible
        );
        lastTrainerHudPayloadAtMs = decision.updatedLastPayloadAtMs;
        trainerHudSessionActive = decision.updatedSessionActive;
        if (decision.enableDebugOverlay) {
            setDebugOverlayVisible(true);
        }
        if (decision.logMessage != null && !decision.logMessage.isEmpty()) {
            emitHudDebugAdb(decision.logMessage);
        }

        switch (decision.action) {
            case RENDER_JSON:
                renderTrainerHudOverlayJson(trainerHudJson);
                return true;
            case RENDER_TEXT:
                renderTrainerHudOverlay(trainerHudText);
                return true;
            case KEEP_LAST:
                return true;
            case RENDER_PLACEHOLDER:
                renderTrainerHudOverlayPlaceholder();
                return true;
            case SHOW_WAITING:
                showHudTextOnly("trainer", "TRAINING HUD\nwaiting for trainer metrics...", HUD_TEXT_COLOR_LIVE);
                lastHudCompactLine = "trainer hud waiting metrics";
                refreshDebugInfoOverlay();
                return true;
            case NONE:
            default:
                return decision.handled;
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
        RuntimeHudUpdateState state = RuntimeHudUpdateState.fromSnapshot(
                runtime,
                nowMs,
                latestStablePresentFps,
                latestStablePresentFpsAtMs,
                PRESENT_FPS_STALE_GRACE_MS,
                runtimeDropPrevCount,
                runtimeDropPrevAtMs
        );
        latestStablePresentFps = state.updatedStablePresentFps;
        latestStablePresentFpsAtMs = state.updatedStablePresentFpsAtMs;
        runtimeDropPrevCount = state.updatedDropPrevCount;
        runtimeDropPrevAtMs = state.updatedDropPrevAtMs;

        String daemonStateUi = MainActivityRuntimeStateView.effectiveDaemonState(
                daemonState,
                state.presentFps,
                state.streamUptimeSec,
                state.frameOutHost
        );
        latestTargetFps = state.targetFps;
        latestPresentFps = state.presentFps;
        latestStreamUptimeSec = state.streamUptimeSec;
        latestFrameOutHost = state.frameOutHost;

        lastHudCompactLine = RuntimeHudComputation.formatCompactHudLine(
                state.targetFps,
                state.presentFps,
                state.e2eP95,
                state.decodeP95,
                state.renderP95,
                state.qT,
                state.qD,
                state.qR
        );
        refreshDebugInfoOverlay();

        if (state.pressureState.highPressure) {
            Log.w(TAG, RuntimeHudComputation.formatHighPressureLog(
                    state.pressureState,
                    state.decodeP95,
                    state.renderP95,
                    state.qT,
                    state.qD,
                    state.qR,
                    state.qTMax,
                    state.qDMax,
                    state.qRMax,
                    state.presentFps,
                    state.streamUptimeSec
            ));
        }
        String runtimeChartsHtml = RuntimeHudTrendComposer.appendSamplesAndBuildHtml(
                runtimePresentSeries,
                runtimeMbpsSeries,
                runtimeDropSeries,
                runtimeLatencySeries,
                runtimeQueueSeries,
                state.presentFps,
                state.bitrateMbps,
                state.dropPerSec,
                state.e2eP95,
                state.qT,
                state.qD,
                state.qR,
                state.pressureState.tone,
                FPS_LOW_ANCHOR
        );
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
                state.targetFps,
                state.presentFps,
                state.recvFps,
                state.decodeFps,
                state.e2eP95,
                state.decodeP95,
                state.renderP95,
                state.frametimeP95,
                state.bitrateMbps,
                state.dropPerSec,
                state.qT,
                state.qD,
                state.qR,
                state.qTMax,
                state.qDMax,
                state.qRMax,
                state.adaptiveLevel,
                state.adaptiveAction,
                state.drops,
                state.bpHigh,
                state.bpRecover,
                state.reason,
                runtimeChartsHtml,
                state.pressureState.tone,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                HUD_TEXT_COLOR_LIVE
        );

        emitHudDebugAdb(RuntimeHudComputation.buildRuntimeDebugSnapshot(
                daemonStateUi,
                daemonRunId,
                daemonUptimeSec,
                latestStreamUptimeSec,
                state.frameInHost,
                state.frameOutHost,
                state.targetFps,
                state.presentFps,
                state.frametimeP95,
                state.decodeP95,
                state.renderP95,
                state.e2eP95,
                state.qT,
                state.qD,
                state.qR,
                state.qTMax,
                state.qDMax,
                state.qRMax,
                state.adaptiveLevel,
                state.adaptiveAction,
                state.drops,
                state.bpHigh,
                state.bpRecover,
                state.pressureState,
                state.reason,
                daemonLastError
        ));
    }

    private void renderTrainerHudOverlay(String rawHudText) {
        TrainerHudOverlayPipeline.renderFromText(
                rawHudText,
                latestTargetFps,
                FPS_LOW_ANCHOR,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                HUD_TEXT_COLOR_LIVE,
                compactLine -> lastHudCompactLine = compactLine,
                this::refreshDebugInfoOverlay
        );
    }

    private void renderTrainerHudOverlayJson(JSONObject hudJson) {
        TrainerHudOverlayPipeline.renderFromJson(
                hudJson,
                latestTargetFps,
                FPS_LOW_ANCHOR,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                HUD_TEXT_COLOR_LIVE,
                compactLine -> lastHudCompactLine = compactLine,
                this::refreshDebugInfoOverlay
        );
    }

    private void renderTrainerHudOverlayPlaceholder() {
        TrainerHudOverlayPipeline.renderPlaceholder(
                latestTargetFps,
                FPS_LOW_ANCHOR,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                HUD_TEXT_COLOR_LIVE,
                compactLine -> lastHudCompactLine = compactLine,
                this::refreshDebugInfoOverlay
        );
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
        StartupOverlayCoordinator.State state = new StartupOverlayCoordinator.State();
        state.startupBeganAtMs = startupBeganAtMs;
        state.controlRetryCount = controlRetryCount;
        state.startupDismissed = startupDismissed;
        state.preflightComplete = preflightComplete;

        StartupOverlayCoordinator.State next = StartupOverlayCoordinator.update(
                createStartupOverlayHooks(),
                state
        );
        startupBeganAtMs = next.startupBeganAtMs;
        controlRetryCount = next.controlRetryCount;
        startupDismissed = next.startupDismissed;
        preflightComplete = next.preflightComplete;
    }

    private StartupOverlayCoordinator.Hooks createStartupOverlayHooks() {
        return StartupOverlayHooksFactory.create(
                preflightOverlay,
                startupOverlayViews,
                videoTestController,
                STARTUP_VIDEO_TEST_HINT_COLOR,
                new StartupOverlayHooksFactory.ProbeHooks() {
                    @Override
                    public boolean requiresTransportProbe() {
                        return MainActivity.this.requiresTransportProbe();
                    }

                    @Override
                    public void maybeStartTransportProbe() {
                        if (daemonReachable && handshakeResolved && !isBuildMismatch()) {
                            MainActivity.this.maybeStartTransportProbe();
                        }
                    }
                },
                this::buildStartupOverlayInput,
                model -> StartupOverlayViewRenderer.applyModel(
                        model,
                        preflightAnimTick,
                        startupOverlayViews
                ),
                this::setPreflightVisible,
                (delayMs, action) -> uiHandler.postDelayed(action, delayMs)
        );
    }

    private StartupOverlayModelBuilder.Input buildStartupOverlayInput(boolean shouldProbe) {
        return StartupOverlayInputFactory.fromRuntimeState(
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
                TransportProbeCallbacksFactory.create(
                        this::appendLiveLogInfo,
                        this::appendLiveLogWarn,
                        this::updatePreflightOverlay
                )
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
