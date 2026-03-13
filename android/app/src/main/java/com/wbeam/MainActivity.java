package com.wbeam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
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
import com.wbeam.hud.MainHudState;
import com.wbeam.api.StatusPoller;
import com.wbeam.api.StatusPollerCallbacksFactory;
import com.wbeam.hud.HudDebugLogLimiter;
import com.wbeam.hud.MainHudCoordinator;
import com.wbeam.hud.MainHudInputFactory;
import com.wbeam.hud.MetricSeriesBuffer;
import com.wbeam.hud.ResourceUsageTracker;
import com.wbeam.input.CursorOverlayController;
import com.wbeam.input.ImmersiveModeController;
import com.wbeam.startup.MainStartupCoordinator;
import com.wbeam.startup.MainStartupInputFactory;
import com.wbeam.startup.StartupOverlayControllerGuard;
import com.wbeam.startup.StartupOverlayController;
import com.wbeam.startup.StartupOverlayViewRenderer;
import com.wbeam.startup.StartupOverlayViewsBinder;
import com.wbeam.startup.TransportProbeCoordinator;
import com.wbeam.stream.MainStreamCoordinator;
import com.wbeam.stream.SessionUiBridge;
import com.wbeam.stream.SessionUiBridgeFactory;
import com.wbeam.stream.VideoTestController;
import com.wbeam.stream.StreamSessionController;
import com.wbeam.stream.DecoderCapabilityInspector;
import com.wbeam.stream.VideoTestCallbacksFactory;
import com.wbeam.ui.state.MainDaemonState;
import com.wbeam.ui.state.MainUiState;
import com.wbeam.telemetry.ClientMetricsReporter;
import com.wbeam.ui.ErrorTextUtil;
import com.wbeam.ui.LiveLogBuffer;
import com.wbeam.ui.LiveLogUiAppender;
import com.wbeam.ui.MainDaemonRuntimeCoordinator;
import com.wbeam.ui.MainDaemonRuntimeInputFactory;
import com.wbeam.ui.MainActivityRuntimeStateView;
import com.wbeam.ui.MainActivityInteractionPolicy;
import com.wbeam.ui.MainActivityControlViewsBinder;
import com.wbeam.ui.MainActivityLifecycleCleaner;
import com.wbeam.ui.MainActivityPrimaryViewsBinder;
import com.wbeam.ui.MainActivitySimpleMenuCoordinator;
import com.wbeam.ui.MainActivityUiBinder;
import com.wbeam.ui.MainInitializationCoordinator;
import com.wbeam.ui.MainSessionControlCoordinator;
import com.wbeam.ui.MainActivityStatusPresenter;
import com.wbeam.ui.MainStatusCoordinator;
import com.wbeam.ui.MainUiControlsCoordinator;
import com.wbeam.ui.MainViewBehaviorCoordinator;
import com.wbeam.ui.MainViewBindingCoordinator;
import com.wbeam.ui.SettingsSelectionReader;
import com.wbeam.ui.SettingsPayloadBuilder;
import com.wbeam.ui.SettingsPanelController;
import com.wbeam.ui.StreamConfigResolver;
import com.wbeam.ui.state.MainStatusState;
import com.wbeam.widget.FpsLossGraphView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String DEFAULT_PROFILE = "baseline";

    private static final String[] PROFILE_OPTIONS = {
            DEFAULT_PROFILE
    };
    /**
     * Preferred video encoder for this device.
     */
    static final String PREFERRED_VIDEO = DecoderCapabilityInspector.preferredVideoEncoder();
    private static final String[] ENCODER_OPTIONS = {PREFERRED_VIDEO, "raw-png"};
    private static final String CURSOR_EMBEDDED = "embedded";
    private static final String METRIC_KEY_TRAINER_HUD_ACTIVE = "trainer_hud_active";
    private static final String METRIC_KEY_TRAINER_HUD_TEXT = "trainer_hud_text";
    private static final String[] CURSOR_OPTIONS = {CURSOR_EMBEDDED, "hidden", "metadata"};
    private static final String DEFAULT_CURSOR_MODE = CURSOR_EMBEDDED;
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
    private SurfaceView previewSurface;
    private TextView statusText;
    private TextView detailText;
    private TextView bpsText;
    private TextView statsText;
    private TextView perfHudText;
    private WebView perfHudWebView;
    private TextView debugInfoText;
    // ── Startup overlay ────────────────────────────────────────────────────────
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
    private final MainActivitySimpleMenuCoordinator.State simpleMenuState =
            new MainActivitySimpleMenuCoordinator.State();
    private final MainUiState uiState = new MainUiState();
    private final MainDaemonState daemon = new MainDaemonState();
    private final MainHudState hudState = new MainHudState();
    private final MainActivityInteractionPolicy.ToggleState debugToggleState =
            new MainActivityInteractionPolicy.ToggleState();
    private final HudDebugLogLimiter hudDebugLogLimiter = new HudDebugLogLimiter(HUD_ADB_LOG_INTERVAL_MS);
    private boolean hwAvcDecodeAvailable = false;
    private final MainStatusState statusState =
            new MainStatusState(STATE_IDLE, "tap Settings -> Start Live");
    private final HudOverlayDisplay.State hudOverlayState = new HudOverlayDisplay.State();
    private final LiveLogBuffer liveLogBuffer = new LiveLogBuffer(LIVE_LOG_MAX_LINES);
    private final ResourceUsageTracker resourceUsageTracker = new ResourceUsageTracker(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimePresentSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeMbpsSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeDropSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeLatencySeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
    private final MetricSeriesBuffer runtimeQueueSeries = new MetricSeriesBuffer(HUD_RESOURCE_SERIES_MAX);
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
    private MainStreamCoordinator streamCoordinator;
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
                debugFpsGraphView.addSample(hudState.latestTargetFps, hudState.latestPresentFps);
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
            boolean nextVisible = !uiState.debugOverlayVisible;
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
        hwAvcDecodeAvailable = MainInitializationCoordinator.initializeUiBindings(
                TAG,
                this,
                startupBuildVersionText,
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                PROFILE_OPTIONS,
                ENCODER_OPTIONS,
                CURSOR_OPTIONS,
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                this::bindViews,
                () -> setScreenAlwaysOn(true),
                this::setupSurfaceCallbacks,
                this::setupButtons,
                this::loadSavedSettings,
                this::updateIntraOnlyButton,
                this::updateHostHint,
                this::enforceCursorOverlayPolicy,
                this::updateSettingValueLabels
        );
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
        metricsReporter = new ClientMetricsReporter(ioExecutor, msg -> appendLiveLog("W", msg));
        videoTestController = createVideoTestController();
        statusPoller = createStatusPoller();
        sessionController = createSessionController();
        streamCoordinator = createStreamCoordinator();
    }

    private MainStreamCoordinator createStreamCoordinator() {
        return new MainStreamCoordinator(
                TAG,
                previewSurface,
                videoTestController,
                this::effectiveStreamConfig,
                this::updateStatus,
                this::updateStatsLine,
                metricsReporter::push,
                this::runOnUiThread,
                this::hideSettingsPanel,
                this::hideCursorOverlay,
                STATE_ERROR,
                STATE_STREAMING,
                STATE_IDLE
        );
    }

    private void initializeStartupState() {
        MainInitializationCoordinator.initializeStartupState(
                uiState,
                startupOverlayController,
                this::refreshSettingsUi,
                () -> setDebugControlsVisible(false),
                this::applyBuildVariantUi,
                () -> updateStatsLine(MainActivityStatusPresenter.DEFAULT_STATS_LINE),
                this::updatePerfHudUnavailable,
                this::updatePreflightOverlay,
                () -> updateStatus(STATE_IDLE, "waiting for desktop connect", 0),
                statusPoller
        );
    }

    private VideoTestController createVideoTestController() {
        return new VideoTestController(
                uiHandler,
                ioExecutor,
                VideoTestCallbacksFactory.create(
                        () -> surface,
                        () -> {
                            if (streamCoordinator != null) {
                                streamCoordinator.stopPlaybackOnly();
                            }
                        },
                        () -> daemon.state,
                        () -> daemon.reachable,
                        this::updateStatus,
                        this::updateStatsLine,
                        msg -> appendLiveLog("I", msg),
                        msg -> appendLiveLog("W", msg),
                        msg -> appendLiveLog("E", msg),
                        this::updatePreflightOverlay,
                        visible -> {
                            uiState.liveLogVisible = visible;
                            if (liveLogText != null) {
                                liveLogText.setVisibility(visible ? View.VISIBLE : View.GONE);
                            }
                        },
                        (msg, longToast) -> Toast.makeText(
                                MainActivity.this,
                                msg,
                                longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT
                        ).show(),
                        this::buildVideoTestConfig
                )
        );
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
                        () -> appendLiveLog(
                                "W",
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
        MainDaemonRuntimeCoordinator.StatusInput input =
                MainDaemonRuntimeInputFactory.createStatusInput(
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

        MainDaemonRuntimeCoordinator.StatusContext context =
                MainDaemonRuntimeInputFactory.createStatusContext(
                        daemon,
                        uiState,
                        this::requiresTransportProbeNow,
                        this::maybeStartTransportProbeNow,
                        this::notifyConnectedHost,
                        this::appendLiveLog,
                        this::stopLiveView,
                        this::refreshUiAfterDaemonStateChange,
                        this::updateStatsLine,
                        this::updatePerfHud
                );

        MainDaemonRuntimeCoordinator.onStatusUpdate(input, context);
    }

    private void handleDaemonOffline(boolean wasReachable, Exception e) {
        MainDaemonRuntimeCoordinator.OfflineContext context =
                MainDaemonRuntimeInputFactory.createOfflineContext(
                        daemon,
                        uiState,
                        transportProbe,
                        STATE_ERROR,
                        this::stopLiveView,
                        this::updateActionButtonsEnabled,
                        this::updateHostHint,
                        this::updatePerfHudUnavailable,
                        this::refreshStatusText,
                        this::updatePreflightOverlay,
                        this::updateStatus,
                        this::appendLiveLog,
                        message -> Toast.makeText(
                                MainActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show()
                );

        MainDaemonRuntimeCoordinator.onOffline(wasReachable, e, context);
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
        MainActivityLifecycleCleaner.cleanup(
                statusPoller,
                () -> StartupOverlayControllerGuard.stopPulse(startupOverlayController),
                uiHandler,
                simpleMenuAutoHideTask,
                debugInfoFadeTask,
                debugGraphSampleTask,
                debugOverlayToggleTask,
                videoTestController::release,
                this::stopLiveView,
                ioExecutor
        );
    }

    private void refreshUiAfterDaemonStateChange() {
        updateActionButtonsEnabled();
        updateHostHint();
        refreshStatusText();
        updatePreflightOverlay();
    }

    private void notifyConnectedHost(String hostName) {
        Toast.makeText(MainActivity.this, "Connected to " + hostName, Toast.LENGTH_SHORT).show();
        appendLiveLog("I", "connected to host " + hostName);
    }

    @Override
    public void onBackPressed() {
        if (MainActivityInteractionPolicy.handleBackNavigation(
                simpleMenuState.visible,
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
        MainViewBindingCoordinator.BoundViews bound = MainViewBindingCoordinator.bind(
                this,
                uiHandler,
                startupOverlayViews,
                animTick -> {
                    uiState.preflightAnimTick = animTick;
                    updatePreflightOverlay();
                }
        );
        MainActivityPrimaryViewsBinder.Views primaryViews = bound.primaryViews;
        rootLayout = primaryViews.rootLayout;
        topBar = primaryViews.topBar;
        quickActionRow = primaryViews.quickActionRow;
        settingsPanel = primaryViews.settingsPanel;
        simpleMenuPanel = primaryViews.simpleMenuPanel;
        statusPanel = primaryViews.statusPanel;
        perfHudPanel = primaryViews.perfHudPanel;
        debugInfoPanel = primaryViews.debugInfoPanel;
        debugFpsGraphView = primaryViews.debugFpsGraphView;
        preflightOverlay = primaryViews.preflightOverlay;
        debugControlsRow = primaryViews.debugControlsRow;
        statusLed = primaryViews.statusLed;
        cursorOverlay = primaryViews.cursorOverlay;
        statusText = primaryViews.statusText;
        detailText = primaryViews.detailText;
        bpsText = primaryViews.bpsText;
        statsText = primaryViews.statsText;
        perfHudText = primaryViews.perfHudText;
        perfHudWebView = primaryViews.perfHudWebView;
        debugInfoText = primaryViews.debugInfoText;
        previewSurface = bound.previewSurface;

        immersiveModeController = bound.immersiveModeController;
        settingsPanelController = bound.settingsPanelController;
        startupBuildVersionText = bound.startupBuildVersionText;
        startupOverlayController = bound.startupOverlayController;
        MainActivityControlViewsBinder.Views controlViews = bound.controlViews;
        liveLogText = controlViews.liveLogText;
        resValueText = controlViews.resValueText;
        fpsValueText = controlViews.fpsValueText;
        bitrateValueText = controlViews.bitrateValueText;
        hostHintText = controlViews.hostHintText;

        profileSpinner = controlViews.profileSpinner;
        encoderSpinner = controlViews.encoderSpinner;
        cursorSpinner = controlViews.cursorSpinner;

        resolutionSeek = controlViews.resolutionSeek;
        fpsSeek = controlViews.fpsSeek;
        bitrateSeek = controlViews.bitrateSeek;

        settingsButton = controlViews.settingsButton;
        logButton = controlViews.logButton;
        settingsCloseButton = controlViews.settingsCloseButton;
        applySettingsButton = controlViews.applySettingsButton;
        quickStartButton = controlViews.quickStartButton;
        quickStopButton = controlViews.quickStopButton;
        quickTestButton = controlViews.quickTestButton;
        startButton = controlViews.startButton;
        stopButton = controlViews.stopButton;
        testButton = controlViews.testButton;
        fullscreenButton = controlViews.fullscreenButton;
        cursorOverlayButton = controlViews.cursorOverlayButton;
        intraOnlyButton = controlViews.intraOnlyButton;
        simpleModeH265Button = controlViews.simpleModeH265Button;
        simpleModeRawButton = controlViews.simpleModeRawButton;
        simpleFps30Button = controlViews.simpleFps30Button;
        simpleFps45Button = controlViews.simpleFps45Button;
        simpleFps60Button = controlViews.simpleFps60Button;
        simpleFps90Button = controlViews.simpleFps90Button;
        simpleFps120Button = controlViews.simpleFps120Button;
        simpleFps144Button = controlViews.simpleFps144Button;
        simpleApplyButton = controlViews.simpleApplyButton;
        cursorOverlayController = bound.cursorOverlayController;
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
        MainViewBehaviorCoordinator.applyBuildVariantUi(
                BuildConfig.DEBUG,
                uiState,
                debugFpsGraphView,
                DEBUG_FPS_GRAPH_POINTS,
                debugInfoPanel,
                () -> setFullscreen(true),
                this::setDebugOverlayVisible,
                this::startDebugGraphSampling,
                this::refreshDebugInfoOverlay,
                this::stopDebugGraphSampling
        );
    }

    private void setupSurfaceCallbacks() {
        MainViewBehaviorCoordinator.setupSurfaceCallbacks(
                previewSurface,
                cursorOverlayController,
                (nextSurface, ready) -> {
                    surface = nextSurface;
                    uiState.surfaceReady = ready;
                    updatePreflightOverlay();
                    updateStatus(STATE_IDLE, "surface ready", 0);
                },
                (nextSurface, ready) -> {
                    surface = nextSurface;
                    uiState.surfaceReady = ready;
                    updatePreflightOverlay();
                },
                () -> {
                    stopLiveView();
                    surface = null;
                    uiState.surfaceReady = false;
                    uiState.preflightComplete = false;
                    updatePreflightOverlay();
                    hideCursorOverlay();
                    updateStatus(STATE_IDLE, "surface destroyed", 0);
                },
                this::updateCursorOverlay
        );
    }

    private void setupButtons() {
        MainViewBehaviorCoordinator.setupButtons(
                settingsCloseButton,
                simpleMenuPanel,
                debugInfoPanel,
                uiHandler,
                debugInfoFadeTask,
                DEBUG_INFO_ALPHA_TOUCH,
                DEBUG_INFO_ALPHA_RESET_MS,
                simpleModeH265Button,
                simpleModeRawButton,
                PREFERRED_VIDEO,
                mode -> {
                    simpleMenuState.mode = mode;
                    refreshSimpleMenuButtons();
                    scheduleSimpleMenuAutoHide();
                },
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                this::selectSimpleFps,
                simpleApplyButton,
                this::hideSettingsPanel,
                this::scheduleSimpleMenuAutoHide,
                () -> {
                    applySimpleMenuToSettings();
                    requestStartGuarded(false, true);
                    hideSimpleMenu();
                }
        );
        updateActionButtonsEnabled();
    }

    private void setDebugOverlayVisible(boolean visible) {
        MainViewBehaviorCoordinator.setDebugOverlayVisible(
                uiState,
                BuildConfig.DEBUG,
                visible,
                debugInfoPanel,
                perfHudPanel
        );
    }

    private void updateActionButtonsEnabled() {
        MainViewBehaviorCoordinator.updateActionButtonsEnabled(
                daemon.reachable,
                quickStartButton,
                quickStopButton,
                quickTestButton,
                startButton,
                stopButton,
                testButton
        );
    }

    private void setDebugControlsVisible(boolean visible) {
        MainViewBehaviorCoordinator.setDebugControlsVisible(
                uiState,
                visible,
                debugControlsRow,
                testButton
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Settings
    // ══════════════════════════════════════════════════════════════════════════

    private void loadSavedSettings() {
        MainUiControlsCoordinator.loadSavedSettings(
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
                DEFAULT_BITRATE_MBPS,
                cursorOverlayController,
                simpleMenuState,
                () -> enforceCursorOverlayPolicy(false),
                this::refreshSettingsUi
        );
        intraOnlyEnabled = false;
    }

    private void updateSettingValueLabels() {
        int scale = getResolutionScalePercent();
        int fps = getSelectedFps();
        int bitrate = getSelectedBitrateMbps();
        int[] sz = computeScaledSize();
        MainUiControlsCoordinator.updateSettingValueLabels(
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
        intraOnlyEnabled = MainUiControlsCoordinator.updateIntraOnlyButton(
                intraOnlyButton,
                getSelectedEncoder(),
                intraOnlyEnabled
        );
    }

    private void updateHostHint() {
        StreamConfigResolver.Resolved cfg = effectiveStreamConfig();
        MainUiControlsCoordinator.updateHostHint(
                hostHintText,
                daemon.reachable,
                HostApiClient.API_BASE,
                daemon.hostName,
                effectiveDaemonStateUi(),
                daemon.service,
                getSelectedProfile(),
                cfg,
                getSelectedEncoder(),
                intraOnlyEnabled,
                getSelectedCursorMode()
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Core helpers (called by callbacks and internally)
    // ══════════════════════════════════════════════════════════════════════════

    private void ensureDecoderRunning() {
        if (streamCoordinator != null) {
            streamCoordinator.ensureDecoderRunning(surface);
        }
    }

    private SessionUiBridge buildSessionUiBridge() {
        return SessionUiBridgeFactory.create(
                this,
                statusPoller,
                this::updateStatus,
                status -> {
                    if (status == null) {
                        return;
                    }
                    daemon.applySnapshot(
                            true,
                            status.optString("host_name", daemon.hostName),
                            status.optString("state", "IDLE").toUpperCase(Locale.US),
                            status.optString("last_error", daemon.lastError),
                            status.optLong("run_id", daemon.runId),
                            status.optLong("uptime", daemon.uptimeSec),
                            daemon.service,
                            daemon.buildRevision
                    );
                    refreshUiAfterDaemonStateChange();
                },
                this::ensureDecoderRunning,
                this::stopLiveView,
                line -> appendLiveLog("W", line),
                this::handleApiFailure,
                () -> SettingsPayloadBuilder.buildPayload(
                        getSelectedProfile(),
                        getSelectedEncoder(),
                        getSelectedCursorMode(),
                        effectiveStreamConfig(),
                        intraOnlyEnabled,
                        isLegacyAndroidDevice()
                )
        );
    }

    private void handleApiFailure(String prefix, boolean userAction, Exception e) {
        MainSessionControlCoordinator.handleApiFailure(
                this,
                TAG,
                STATE_ERROR,
                prefix,
                userAction,
                e,
                this::updateStatus,
                this::appendLiveLog
        );
    }

    private boolean isBuildMismatch() {
        return MainSessionControlCoordinator.isBuildMismatch(
                daemon.reachable,
                uiState.handshakeResolved,
                daemon.buildRevision
        );
    }

    private void requestStartGuarded(boolean userAction, boolean ensureViewer) {
        MainSessionControlCoordinator.requestStartGuarded(
                this,
                userAction,
                ensureViewer,
                daemon.reachable,
                uiState.handshakeResolved,
                daemon.buildRevision,
                this::updateStatus,
                this::appendLiveLog,
                sessionController::requestStart
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Stream / media
    // ══════════════════════════════════════════════════════════════════════════

    private void startLiveView() {
        if (streamCoordinator != null) {
            streamCoordinator.startLiveView(surface);
        }
    }

    private void stopLiveView() {
        if (streamCoordinator != null) {
            streamCoordinator.stopLiveView();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - settings panel
    // ══════════════════════════════════════════════════════════════════════════

    private void hideSettingsPanel() {
        MainUiControlsCoordinator.hideSettingsPanel(settingsPanelController);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - fullscreen / cursor
    // ══════════════════════════════════════════════════════════════════════════

    private void setFullscreen(boolean enable) {
        MainUiControlsCoordinator.setFullscreen(
                immersiveModeController,
                enable,
                BuildConfig.DEBUG,
                topBar,
                statusPanel,
                this::hideSettingsPanel
        );
    }

    private void enforceImmersiveModeIfNeeded() {
        MainUiControlsCoordinator.enforceImmersiveModeIfNeeded(immersiveModeController);
    }

    private void setScreenAlwaysOn(boolean enable) {
        MainUiControlsCoordinator.setScreenAlwaysOn(immersiveModeController, enable);
    }

    private void selectSimpleFps(int fps) {
        MainUiControlsCoordinator.selectSimpleFps(
                fps,
                simpleMenuState,
                this::refreshSimpleMenuButtons,
                this::scheduleSimpleMenuAutoHide
        );
    }

    private void hideSimpleMenu() {
        MainUiControlsCoordinator.hideSimpleMenu(
                simpleMenuPanel,
                uiHandler,
                simpleMenuAutoHideTask,
                simpleMenuState
        );
    }

    private void scheduleSimpleMenuAutoHide() {
        MainUiControlsCoordinator.scheduleSimpleMenuAutoHide(
                simpleMenuPanel,
                uiHandler,
                simpleMenuAutoHideTask,
                simpleMenuState.visible,
                SIMPLE_MENU_AUTO_HIDE_MS
        );
    }

    private void applySimpleMenuToSettings() {
        MainUiControlsCoordinator.applySimpleMenuToSettings(
                encoderSpinner,
                fpsSeek,
                ENCODER_OPTIONS,
                PREFERRED_VIDEO,
                simpleMenuState.mode,
                simpleMenuState.fps
        );
        refreshSettingsUi(false);
    }

    private void refreshSimpleMenuButtons() {
        MainUiControlsCoordinator.refreshSimpleMenuButtons(
                simpleMenuPanel,
                simpleModeH265Button,
                simpleModeRawButton,
                PREFERRED_VIDEO,
                simpleMenuState.mode,
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                simpleMenuState.fps
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
        MainUiControlsCoordinator.toggleCursorOverlayMode(
                cursorOverlayController,
                getSelectedCursorMode(),
                () -> enforceCursorOverlayPolicy(true)
        );
    }

    private void enforceCursorOverlayPolicy(boolean persist) {
        MainUiControlsCoordinator.enforceCursorOverlayPolicy(
                cursorOverlayController,
                getSelectedCursorMode(),
                persist,
                this::updateHostHint
        );
    }

    private void updateCursorOverlay(float x, float y, int action) {
        MainUiControlsCoordinator.updateCursorOverlay(cursorOverlayController, x, y, action);
    }

    private void hideCursorOverlay() {
        MainUiControlsCoordinator.hideCursorOverlay(cursorOverlayController);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - status text
    // ══════════════════════════════════════════════════════════════════════════

    private void updateStatus(String state, String info, long bps) {
        MainStatusCoordinator.updateStatus(
                state,
                info,
                bps,
                STATE_IDLE,
                STATE_ERROR,
                statusState,
                line -> appendLiveLog("E", line),
                TAG
        );
        refreshStatusText();
        refreshDebugInfoOverlay();
    }

    private void appendLiveLog(String level, String line) {
        LiveLogUiAppender.append(
                liveLogText,
                liveLogBuffer,
                uiState.liveLogVisible,
                level,
                line,
                this::runOnUiThread
        );
    }

    private void refreshStatusText() {
        MainStatusCoordinator.renderStatus(
                statusText,
                detailText,
                bpsText,
                statusLed,
                statusState,
                daemon.reachable,
                daemon.hostName,
                effectiveDaemonStateUi(),
                STATE_STREAMING,
                STATE_CONNECTING
        );
    }

    private void updateStatsLine(String line) {
        MainStatusCoordinator.updateStatsLine(statusState, statsText, line);
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
        MainHudCoordinator.updateUnavailable(buildHudInput());
    }

    private void updatePerfHud(JSONObject metrics) {
        MainHudCoordinator.update(buildHudInput(), metrics);
    }

    private MainHudCoordinator.Input buildHudInput() {
        return MainHudInputFactory.create(
                TAG,
                hudState,
                hudOverlayState,
                runtimePresentSeries,
                runtimeMbpsSeries,
                runtimeDropSeries,
                runtimeLatencySeries,
                runtimeQueueSeries,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                TRANSPORT_QUEUE_MAX_FRAMES,
                DECODE_QUEUE_MAX_FRAMES,
                RENDER_QUEUE_MAX_FRAMES,
                PRESENT_FPS_STALE_GRACE_MS,
                METRICS_STALE_GRACE_MS,
                TRAINER_HUD_PAYLOAD_GRACE_MS,
                FPS_LOW_ANCHOR,
                HUD_TEXT_COLOR_OFFLINE,
                HUD_TEXT_COLOR_LIVE,
                BuildConfig.WBEAM_BUILD_REV,
                this::getSelectedFps,
                this::getSelectedProfile,
                this::getSelectedEncoder,
                this::computeScaledSize,
                () -> daemon.reachable,
                () -> daemon.state,
                () -> daemon.hostName,
                () -> daemon.buildRevision,
                () -> daemon.lastError,
                () -> daemon.runId,
                () -> daemon.uptimeSec,
                this::effectiveDaemonStateUi,
                () -> uiState.debugOverlayVisible,
                BuildConfig.DEBUG,
                this::setDebugOverlayVisible,
                this::showHudTextOnly,
                this::refreshDebugInfoOverlay,
                this::emitHudDebugAdb
        );
    }

    private void refreshDebugInfoOverlay() {
        MainActivityRuntimeStateView.refreshDebugOverlayText(
                BuildConfig.DEBUG,
                debugInfoText,
                debugInfoPanel,
                statusState.uiState,
                daemon.hostName,
                effectiveDaemonStateUi(),
                hudState.latestTargetFps,
                hudState.latestPresentFps,
                getSelectedFps(),
                statusState.statsLine,
                hudState.compactLine
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI - preflight overlay
    // ══════════════════════════════════════════════════════════════════════════

    private void updatePreflightOverlay() {
        MainStartupCoordinator.Input input = MainStartupInputFactory.create(
                preflightOverlay,
                startupOverlayController,
                startupOverlayViews,
                videoTestController,
                STARTUP_VIDEO_TEST_HINT_COLOR,
                transportProbe,
                ioExecutor,
                uiHandler,
                daemon.reachable,
                daemon.hostName,
                daemon.service,
                daemon.buildRevision,
                daemon.state,
                daemon.lastError,
                uiState.handshakeResolved,
                BuildConfig.WBEAM_API_IMPL,
                HostApiClient.API_BASE,
                BuildConfig.WBEAM_API_HOST,
                BuildConfig.WBEAM_STREAM_HOST,
                BuildConfig.WBEAM_STREAM_PORT,
                BuildConfig.WBEAM_BUILD_REV,
                statusState.uiInfo,
                hudState.latestPresentFps,
                statusState.statsLine,
                ErrorTextUtil.compactDaemonErrorForUi(daemon.lastError),
                uiState.preflightAnimTick,
                uiState.startupBeganAtMs,
                uiState.controlRetryCount,
                uiState.startupDismissed,
                uiState.preflightComplete,
                this::isBuildMismatch,
                this::effectiveDaemonStateUi,
                this::updatePreflightOverlay,
                line -> appendLiveLog("I", line),
                line -> appendLiveLog("W", line)
        );

        com.wbeam.startup.StartupOverlayStateSync.StateValues synced =
                MainStartupCoordinator.updatePreflightOverlay(input);
        uiState.startupBeganAtMs = synced.startupBeganAtMs;
        uiState.controlRetryCount = synced.controlRetryCount;
        uiState.startupDismissed = synced.startupDismissed;
        uiState.preflightComplete = synced.preflightComplete;
    }

    private String effectiveDaemonStateUi() {
        return MainActivityRuntimeStateView.effectiveDaemonState(
                daemon.state,
                hudState.latestPresentFps,
                hudState.latestStreamUptimeSec,
                hudState.latestFrameOutHost
        );
    }

    private boolean requiresTransportProbeNow() {
        return MainStartupCoordinator.requiresTransportProbeNow(
                transportProbe,
                daemon.reachable,
                uiState.handshakeResolved,
                BuildConfig.WBEAM_API_IMPL,
                BuildConfig.WBEAM_API_HOST
        );
    }

    private void maybeStartTransportProbeNow(boolean requiresProbe) {
        MainStartupCoordinator.maybeStartTransportProbeNow(
                transportProbe,
                requiresProbe,
                ioExecutor,
                uiHandler,
                line -> appendLiveLog("I", line),
                line -> appendLiveLog("W", line),
                this::updatePreflightOverlay
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
