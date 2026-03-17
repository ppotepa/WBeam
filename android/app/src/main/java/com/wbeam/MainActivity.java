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

@SuppressWarnings({"java:S107", "java:S1450", "java:S1068", "java:S1192"})
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WBeamMain";
    private static final String STATE_IDLE = "idle";
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_STREAMING = "streaming";
    private static final String STATE_ERROR = "error";

    private static final long HUD_ADB_LOG_INTERVAL_MS = 1000;
    private static final int TRANSPORT_QUEUE_MAX_FRAMES = 3;
    private static final int DECODE_QUEUE_MAX_FRAMES = 2;
    private static final int RENDER_QUEUE_MAX_FRAMES = 1;

    private static final String DEFAULT_PROFILE = "default";
    private static final String DEFAULT_CURSOR_MODE = "embedded";
    private static final String[] PROFILE_OPTIONS = {DEFAULT_PROFILE};
    /**
     * Preferred video encoder for this device.
     */
    static final String PREFERRED_VIDEO = DecoderCapabilityInspector.preferredVideoEncoder();
    private static final String[] ENCODER_OPTIONS = {PREFERRED_VIDEO, "raw-png"};
    private static final String[] CURSOR_OPTIONS = {DEFAULT_CURSOR_MODE, "hidden", "metadata"};
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

    // ── Views ──────────────────────────────────────────────────────────────────
    private View topBar;
    private View quickActionRow;
    private View simpleMenuPanel;
    private View statusPanel;
    private View perfHudPanel;
    private View debugInfoPanel;
    private FpsLossGraphView debugFpsGraphView;
    private View preflightOverlay;
    private View debugControlsRow;
    private View statusLed;
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
    private Button quickStartButton;
    private Button quickStopButton;
    private Button quickTestButton;
    private Button startButton;
    private Button stopButton;
    private Button testButton;
    private Button fullscreenButton;
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
    private final ExecutorService controlExecutor  = Executors.newSingleThreadExecutor();
    private final ExecutorService telemetryExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService probeExecutor    = Executors.newFixedThreadPool(2);

    private StatusPoller statusPoller;
    private StreamSessionController sessionController;
    private ClientMetricsReporter metricsReporter;
    private VideoTestController videoTestController;
    private final TransportProbeCoordinator transportProbe = new TransportProbeCoordinator();
    private MainDaemonRuntimeCoordinator.StatusContext daemonStatusContext;
    private StartupOverlayController startupOverlayController;
    private CursorOverlayController cursorOverlayController;
    private ImmersiveModeController immersiveModeController;
    private SettingsPanelController settingsPanelController;
    private MainHudCoordinator.Input hudInput;

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
            if (!BuildConfig.DEBUG || !uiState.debugOverlayVisible || debugFpsGraphView == null) {
                return;
            }
            debugFpsGraphView.addSample(hudState.latestTargetFps, hudState.latestPresentFps);
            uiHandler.postDelayed(this, DEBUG_FPS_SAMPLE_MS);
        }
    };
    private final Runnable debugOverlayToggleTask = () -> {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (debugToggleState.isVolumeUpHeld()
                && debugToggleState.isVolumeDownHeld()
                && !debugToggleState.isDebugOverlayToggleArmed()) {
            debugToggleState.setDebugOverlayToggleArmed(true);
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
        MainInitializationCoordinator.initializeUiBindings(
                new MainInitializationCoordinator.UiBindingsConfig()
                        .setLogTag(TAG)
                        .setActivity(this)
                        .setProfileOptions(PROFILE_OPTIONS)
                        .setEncoderOptions(ENCODER_OPTIONS)
                        .setCursorOptions(CURSOR_OPTIONS)
                        .setBindViewsTask(this::bindViews)
                        .setSetScreenAlwaysOnTask(() -> setScreenAlwaysOn(true))
                        .setSetupSurfaceCallbacksTask(this::setupSurfaceCallbacks)
                        .setSetupButtonsTask(this::setupButtons)
                        .setLoadSavedSettingsTask(this::loadSavedSettings)
                        .setUpdateSettingValueLabelsTask(this::updateSettingValueLabels),
                this::updateIntraOnlyButton,
                this::updateHostHint,
                this::enforceCursorOverlayPolicy
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
        metricsReporter = new ClientMetricsReporter(telemetryExecutor, msg -> appendLiveLog("W", msg));
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
                new MainInitializationCoordinator.StartupStateConfig()
                        .setUiState(uiState)
                        .setStartupOverlayController(startupOverlayController)
                        .setRefreshSettingsUiTask(this::refreshSettingsUi)
                        .setSetDebugControlsHiddenTask(() -> setDebugControlsVisible(false))
                        .setApplyBuildVariantUiTask(this::applyBuildVariantUi)
                        .setSetDefaultStatsLineTask(
                                () -> updateStatsLine(MainActivityStatusPresenter.DEFAULT_STATS_LINE)
                        )
                        .setUpdatePerfHudUnavailableTask(this::updatePerfHudUnavailable)
                        .setUpdatePreflightOverlayTask(this::updatePreflightOverlay)
                        .setSetIdleWaitingStatusTask(
                                () -> updateStatus(STATE_IDLE, "waiting for desktop connect", 0)
                        )
                        .setStatusPoller(statusPoller)
        );
    }

    private VideoTestController createVideoTestController() {
        return new VideoTestController(
                uiHandler,
                probeExecutor,
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
                probeExecutor,
                () -> uiState.debugOverlayVisible,
                StatusPollerCallbacksFactory.create(
                        (
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
                        ) -> handleDaemonStatusUpdate(
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
                                )
                        ),
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

    private void handleDaemonStatusUpdate(MainDaemonRuntimeCoordinator.StatusInput input) {
        if (daemonStatusContext == null) {
            daemonStatusContext = MainDaemonRuntimeInputFactory.createStatusContext(
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
        }
        MainDaemonRuntimeCoordinator.onStatusUpdate(input, daemonStatusContext);
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
                controlExecutor,
                buildSessionUiBridge()
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusPoller != null) {
            statusPoller.start();
        }
        setScreenAlwaysOn(true);
        enforceImmersiveModeIfNeeded();
    }

    @Override
    protected void onPause() {
        if (statusPoller != null) {
            statusPoller.stop();
        }
        super.onPause();
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
                controlExecutor,
                telemetryExecutor,
                probeExecutor
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
                simpleMenuState.isVisible(),
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
        if (action.isHandled()) {
            if (action.isCancelScheduledToggle()) {
                uiHandler.removeCallbacks(debugOverlayToggleTask);
            }
            if (action.isScheduleToggle()) {
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
        if (action.isHandled()) {
            if (action.isCancelScheduledToggle()) {
                uiHandler.removeCallbacks(debugOverlayToggleTask);
            }
            if (action.isResetArmed()) {
                debugToggleState.setDebugOverlayToggleArmed(false);
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
        MainActivityPrimaryViewsBinder.Views primaryViews = bound.getPrimaryViews();
        topBar = primaryViews.getTopBar();
        quickActionRow = primaryViews.getQuickActionRow();
        simpleMenuPanel = primaryViews.getSimpleMenuPanel();
        statusPanel = primaryViews.getStatusPanel();
        perfHudPanel = primaryViews.getPerfHudPanel();
        debugInfoPanel = primaryViews.getDebugInfoPanel();
        debugFpsGraphView = primaryViews.getDebugFpsGraphView();
        preflightOverlay = primaryViews.getPreflightOverlay();
        debugControlsRow = primaryViews.getDebugControlsRow();
        statusLed = primaryViews.getStatusLed();
        statusText = primaryViews.getStatusText();
        detailText = primaryViews.getDetailText();
        bpsText = primaryViews.getBpsText();
        statsText = primaryViews.getStatsText();
        perfHudText = primaryViews.getPerfHudText();
        perfHudWebView = primaryViews.getPerfHudWebView();
        debugInfoText = primaryViews.getDebugInfoText();
        previewSurface = bound.getPreviewSurface();

        immersiveModeController = bound.getImmersiveModeController();
        settingsPanelController = bound.getSettingsPanelController();
        startupBuildVersionText = bound.getStartupBuildVersionText();
        startupOverlayController = bound.getStartupOverlayController();
        MainActivityControlViewsBinder.Views controlViews = bound.getControlViews();
        liveLogText = controlViews.getLiveLogText();
        resValueText = controlViews.getResValueText();
        fpsValueText = controlViews.getFpsValueText();
        bitrateValueText = controlViews.getBitrateValueText();
        hostHintText = controlViews.getHostHintText();

        profileSpinner = controlViews.getProfileSpinner();
        encoderSpinner = controlViews.getEncoderSpinner();
        cursorSpinner = controlViews.getCursorSpinner();

        resolutionSeek = controlViews.getResolutionSeek();
        fpsSeek = controlViews.getFpsSeek();
        bitrateSeek = controlViews.getBitrateSeek();

        settingsButton = controlViews.getSettingsButton();
        logButton = controlViews.getLogButton();
        settingsCloseButton = controlViews.getSettingsCloseButton();
        quickStartButton = controlViews.getQuickStartButton();
        quickStopButton = controlViews.getQuickStopButton();
        quickTestButton = controlViews.getQuickTestButton();
        startButton = controlViews.getStartButton();
        stopButton = controlViews.getStopButton();
        testButton = controlViews.getTestButton();
        fullscreenButton = controlViews.getFullscreenButton();
        intraOnlyButton = controlViews.getIntraOnlyButton();
        simpleModeH265Button = controlViews.getSimpleModeH265Button();
        simpleModeRawButton = controlViews.getSimpleModeRawButton();
        simpleFps30Button = controlViews.getSimpleFps30Button();
        simpleFps45Button = controlViews.getSimpleFps45Button();
        simpleFps60Button = controlViews.getSimpleFps60Button();
        simpleFps90Button = controlViews.getSimpleFps90Button();
        simpleFps120Button = controlViews.getSimpleFps120Button();
        simpleFps144Button = controlViews.getSimpleFps144Button();
        simpleApplyButton = controlViews.getSimpleApplyButton();
        cursorOverlayController = bound.getCursorOverlayController();
        hudInput = null;
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
                new MainViewBehaviorCoordinator.BuildVariantUiInput()
                        .setBuildDebug(BuildConfig.DEBUG)
                        .setUiState(uiState)
                        .setDebugFpsGraphView(debugFpsGraphView)
                        .setDebugFpsGraphPoints(DEBUG_FPS_GRAPH_POINTS)
                        .setDebugInfoPanel(debugInfoPanel)
                        .setForceFullscreenTask(() -> setFullscreen(true))
                        .setOverlayVisibilityApplier(this::setDebugOverlayVisible)
                        .setStartDebugGraphSamplingTask(this::startDebugGraphSampling)
                        .setRefreshDebugInfoOverlayTask(this::refreshDebugInfoOverlay)
                        .setStopDebugGraphSamplingTask(this::stopDebugGraphSampling)
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
                new MainViewBehaviorCoordinator.SetupButtonsInput()
                        .setSettingsCloseButton(settingsCloseButton)
                        .setSimpleMenuPanel(simpleMenuPanel)
                        .setDebugInfoPanel(debugInfoPanel)
                        .setUiHandler(uiHandler)
                        .setDebugInfoFadeTask(debugInfoFadeTask)
                        .setDebugInfoAlphaTouch(DEBUG_INFO_ALPHA_TOUCH)
                        .setDebugInfoAlphaResetMs(DEBUG_INFO_ALPHA_RESET_MS)
                        .setSimpleModeH265Button(simpleModeH265Button)
                        .setSimpleModeRawButton(simpleModeRawButton)
                        .setPreferredVideo(PREFERRED_VIDEO)
                        .setOnModeSelected(mode -> {
                            simpleMenuState.setMode(mode);
                            refreshSimpleMenuButtons();
                            scheduleSimpleMenuAutoHide();
                        })
                        .setSimpleFps30Button(simpleFps30Button)
                        .setSimpleFps45Button(simpleFps45Button)
                        .setSimpleFps60Button(simpleFps60Button)
                        .setSimpleFps90Button(simpleFps90Button)
                        .setSimpleFps120Button(simpleFps120Button)
                        .setSimpleFps144Button(simpleFps144Button)
                        .setOnFpsSelected(this::selectSimpleFps)
                        .setSimpleApplyButton(simpleApplyButton)
                        .setHideSettingsPanelTask(this::hideSettingsPanel)
                        .setScheduleSimpleMenuAutoHideTask(this::scheduleSimpleMenuAutoHide)
                        .setOnApplyAndStartTask(() -> {
                            applySimpleMenuToSettings();
                            requestStartGuarded(false, true);
                            hideSimpleMenu();
                        })
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
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (visible) {
            refreshDebugInfoOverlay();
            startDebugGraphSampling();
            return;
        }
        stopDebugGraphSampling();
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
                new MainSessionControlCoordinator.ApiFailureInput()
                        .setContext(this)
                        .setTag(TAG)
                        .setStateError(STATE_ERROR)
                        .setPrefix(prefix)
                        .setUserAction(userAction)
                        .setError(e)
                        .setStatusSink(this::updateStatus)
                        .setLineLogger(this::appendLiveLog)
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
                new MainSessionControlCoordinator.RequestStartInput()
                        .setContext(this)
                        .setUserAction(userAction)
                        .setEnsureViewer(ensureViewer)
                        .setDaemonReachable(daemon.reachable)
                        .setHandshakeResolved(uiState.handshakeResolved)
                        .setDaemonBuildRevision(daemon.buildRevision)
                        .setStatusSink(this::updateStatus)
                        .setLineLogger(this::appendLiveLog)
                        .setStartRequester(sessionController::requestStart)
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
                simpleMenuState.isVisible(),
                SIMPLE_MENU_AUTO_HIDE_MS
        );
    }

    private void applySimpleMenuToSettings() {
        MainUiControlsCoordinator.applySimpleMenuToSettings(
                encoderSpinner,
                fpsSeek,
                ENCODER_OPTIONS,
                PREFERRED_VIDEO,
                simpleMenuState.getMode(),
                simpleMenuState.getFps()
        );
        refreshSettingsUi(false);
    }

    private void refreshSimpleMenuButtons() {
        MainUiControlsCoordinator.refreshSimpleMenuButtons(
                simpleMenuPanel,
                simpleModeH265Button,
                simpleModeRawButton,
                PREFERRED_VIDEO,
                simpleMenuState.getMode(),
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                simpleMenuState.getFps()
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
        if (BuildConfig.DEBUG && uiState.debugOverlayVisible && debugFpsGraphView != null) {
            uiHandler.post(debugGraphSampleTask);
        }
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
        if (hudInput == null
                || hudInput.getPerfHudWebView() != perfHudWebView
                || hudInput.getPerfHudText() != perfHudText
                || hudInput.getPerfHudPanel() != perfHudPanel) {
            hudInput = MainHudInputFactory.create(
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
        return hudInput;
    }

    private void refreshDebugInfoOverlay() {
        if (!BuildConfig.DEBUG || !uiState.debugOverlayVisible) {
            return;
        }
        MainActivityRuntimeStateView.refreshDebugOverlayText(
                BuildConfig.DEBUG,
                debugInfoText,
                debugInfoPanel,
                statusState.getUiState(),
                daemon.hostName,
                effectiveDaemonStateUi(),
                hudState.latestTargetFps,
                hudState.latestPresentFps,
                getSelectedFps(),
                statusState.getStatsLine(),
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
                probeExecutor,
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
                statusState.getUiInfo(),
                hudState.latestPresentFps,
                statusState.getStatsLine(),
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
        uiState.startupBeganAtMs = synced.getStartupBeganAtMs();
        uiState.controlRetryCount = synced.getControlRetryCount();
        uiState.startupDismissed = synced.isStartupDismissed();
        uiState.preflightComplete = synced.isPreflightComplete();
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
                probeExecutor,
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
