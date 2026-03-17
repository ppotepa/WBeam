package com.wbeam.ui;

import android.os.SystemClock;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.BuildConfig;
import com.wbeam.R;
import com.wbeam.api.HostApiClient;
import com.wbeam.api.StatusPoller;
import com.wbeam.startup.StartupBuildVersionPresenter;
import com.wbeam.startup.StartupOverlayController;
import com.wbeam.startup.StartupOverlayControllerGuard;
import com.wbeam.stream.DecoderCapabilityInspector;
import com.wbeam.ui.state.MainUiState;

@SuppressWarnings("java:S107")
public final class MainInitializationCoordinator {
    public interface UiTask {
        void run();
    }

    public interface UiStateRefreshTask {
        void refresh(boolean includeSimpleMenuButtons);
    }

    public interface CursorPolicyTask {
        void apply(boolean persist);
    }

    public static final class UiBindingsConfig {
        private String logTag;
        private AppCompatActivity activity;
        private String[] profileOptions;
        private String[] encoderOptions;
        private String[] cursorOptions;
        private UiTask bindViewsTask;
        private UiTask setScreenAlwaysOnTask;
        private UiTask setupSurfaceCallbacksTask;
        private UiTask setupButtonsTask;
        private UiTask loadSavedSettingsTask;
        private UiTask updateSettingValueLabelsTask;

        public UiBindingsConfig setLogTag(String logTag) {
            this.logTag = logTag;
            return this;
        }

        public UiBindingsConfig setActivity(AppCompatActivity activity) {
            this.activity = activity;
            return this;
        }

        public UiBindingsConfig setProfileOptions(String[] profileOptions) {
            this.profileOptions = profileOptions;
            return this;
        }

        public UiBindingsConfig setEncoderOptions(String[] encoderOptions) {
            this.encoderOptions = encoderOptions;
            return this;
        }

        public UiBindingsConfig setCursorOptions(String[] cursorOptions) {
            this.cursorOptions = cursorOptions;
            return this;
        }

        public UiBindingsConfig setBindViewsTask(UiTask bindViewsTask) {
            this.bindViewsTask = bindViewsTask;
            return this;
        }

        public UiBindingsConfig setSetScreenAlwaysOnTask(UiTask setScreenAlwaysOnTask) {
            this.setScreenAlwaysOnTask = setScreenAlwaysOnTask;
            return this;
        }

        public UiBindingsConfig setSetupSurfaceCallbacksTask(UiTask setupSurfaceCallbacksTask) {
            this.setupSurfaceCallbacksTask = setupSurfaceCallbacksTask;
            return this;
        }

        public UiBindingsConfig setSetupButtonsTask(UiTask setupButtonsTask) {
            this.setupButtonsTask = setupButtonsTask;
            return this;
        }

        public UiBindingsConfig setLoadSavedSettingsTask(UiTask loadSavedSettingsTask) {
            this.loadSavedSettingsTask = loadSavedSettingsTask;
            return this;
        }

        public UiBindingsConfig setUpdateSettingValueLabelsTask(UiTask updateSettingValueLabelsTask) {
            this.updateSettingValueLabelsTask = updateSettingValueLabelsTask;
            return this;
        }
    }

    private MainInitializationCoordinator() {
    }

    @SuppressWarnings("java:S1172")
    public static boolean initializeUiBindings(
            UiBindingsConfig config,
            UiTask updateIntraOnlyButtonTask,
            UiTask updateHostHintTask,
            CursorPolicyTask enforceCursorOverlayPolicyTask
    ) {
        config.bindViewsTask.run();
        config.setScreenAlwaysOnTask.run();

        TextView startupBuildVersionText = config.activity.findViewById(R.id.startupBuildVersion);
        Spinner profileSpinner = config.activity.findViewById(R.id.profileSpinner);
        Spinner encoderSpinner = config.activity.findViewById(R.id.encoderSpinner);
        Spinner cursorSpinner = config.activity.findViewById(R.id.cursorSpinner);
        SeekBar resolutionSeek = config.activity.findViewById(R.id.resolutionSeek);
        SeekBar fpsSeek = config.activity.findViewById(R.id.fpsSeek);
        SeekBar bitrateSeek = config.activity.findViewById(R.id.bitrateSeek);

        StartupBuildVersionPresenter.apply(startupBuildVersionText, BuildConfig.WBEAM_BUILD_REV);
        MainActivitySpinnersSetup.setup(
                new MainActivitySpinnersSetup.SetupConfig()
                        .setActivity(config.activity)
                        .setProfileSpinner(profileSpinner)
                        .setEncoderSpinner(encoderSpinner)
                        .setCursorSpinner(cursorSpinner)
                        .setProfileOptions(config.profileOptions)
                        .setEncoderOptions(config.encoderOptions)
                        .setCursorOptions(config.cursorOptions)
                        .setOnProfileOrEncoderChange(() -> {
                            updateIntraOnlyButtonTask.run();
                            updateHostHintTask.run();
                        })
                        .setOnCursorChange(() -> {
                            enforceCursorOverlayPolicyTask.apply(false);
                            updateHostHintTask.run();
                        })
        );
        MainActivityUiBinder.setupSeekbars(
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                config.updateSettingValueLabelsTask::run
        );
        config.setupSurfaceCallbacksTask.run();
        config.setupButtonsTask.run();
        config.loadSavedSettingsTask.run();
        boolean hwAvcDecodeAvailable = DecoderCapabilityInspector.hasHardwareAvcDecoder(config.logTag);
        Log.i(
                config.logTag,
                "startup transport api_impl=" + BuildConfig.WBEAM_API_IMPL
                        + " api=" + HostApiClient.API_BASE
                        + " stream=tcp://" + BuildConfig.WBEAM_STREAM_HOST + ":" + BuildConfig.WBEAM_STREAM_PORT
        );
        return hwAvcDecodeAvailable;
    }

    public static final class StartupStateConfig {
        private MainUiState uiState;
        private StartupOverlayController startupOverlayController;
        private UiStateRefreshTask refreshSettingsUiTask;
        private UiTask setDebugControlsHiddenTask;
        private UiTask applyBuildVariantUiTask;
        private UiTask setDefaultStatsLineTask;
        private UiTask updatePerfHudUnavailableTask;
        private UiTask updatePreflightOverlayTask;
        private UiTask setIdleWaitingStatusTask;
        private StatusPoller statusPoller;

        public StartupStateConfig setUiState(MainUiState uiState) {
            this.uiState = uiState;
            return this;
        }

        public StartupStateConfig setStartupOverlayController(StartupOverlayController startupOverlayController) {
            this.startupOverlayController = startupOverlayController;
            return this;
        }

        public StartupStateConfig setRefreshSettingsUiTask(UiStateRefreshTask refreshSettingsUiTask) {
            this.refreshSettingsUiTask = refreshSettingsUiTask;
            return this;
        }

        public StartupStateConfig setSetDebugControlsHiddenTask(UiTask setDebugControlsHiddenTask) {
            this.setDebugControlsHiddenTask = setDebugControlsHiddenTask;
            return this;
        }

        public StartupStateConfig setApplyBuildVariantUiTask(UiTask applyBuildVariantUiTask) {
            this.applyBuildVariantUiTask = applyBuildVariantUiTask;
            return this;
        }

        public StartupStateConfig setSetDefaultStatsLineTask(UiTask setDefaultStatsLineTask) {
            this.setDefaultStatsLineTask = setDefaultStatsLineTask;
            return this;
        }

        public StartupStateConfig setUpdatePerfHudUnavailableTask(UiTask updatePerfHudUnavailableTask) {
            this.updatePerfHudUnavailableTask = updatePerfHudUnavailableTask;
            return this;
        }

        public StartupStateConfig setUpdatePreflightOverlayTask(UiTask updatePreflightOverlayTask) {
            this.updatePreflightOverlayTask = updatePreflightOverlayTask;
            return this;
        }

        public StartupStateConfig setSetIdleWaitingStatusTask(UiTask setIdleWaitingStatusTask) {
            this.setIdleWaitingStatusTask = setIdleWaitingStatusTask;
            return this;
        }

        public StartupStateConfig setStatusPoller(StatusPoller statusPoller) {
            this.statusPoller = statusPoller;
            return this;
        }
    }

    public static void initializeStartupState(StartupStateConfig config) {
        config.refreshSettingsUiTask.refresh(false);
        config.setDebugControlsHiddenTask.run();
        config.applyBuildVariantUiTask.run();
        config.setDefaultStatsLineTask.run();
        config.updatePerfHudUnavailableTask.run();
        config.uiState.startupBeganAtMs = SystemClock.elapsedRealtime();
        config.uiState.handshakeResolved = false;
        config.uiState.startupDismissed = false;
        StartupOverlayControllerGuard.startPulse(config.startupOverlayController);
        config.updatePreflightOverlayTask.run();
        config.setIdleWaitingStatusTask.run();
        config.statusPoller.start();
    }
}
