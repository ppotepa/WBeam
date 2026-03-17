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
        public final String logTag;
        public final AppCompatActivity activity;
        public final String[] profileOptions;
        public final String[] encoderOptions;
        public final String[] cursorOptions;
        public final UiTask bindViewsTask;
        public final UiTask setScreenAlwaysOnTask;
        public final UiTask setupSurfaceCallbacksTask;
        public final UiTask setupButtonsTask;
        public final UiTask loadSavedSettingsTask;
        public final UiTask updateIntraOnlyButtonTask;
        public final UiTask updateHostHintTask;
        public final CursorPolicyTask enforceCursorOverlayPolicyTask;
        public final UiTask updateSettingValueLabelsTask;

        public UiBindingsConfig(
                String logTag,
                AppCompatActivity activity,
                String[] profileOptions,
                String[] encoderOptions,
                String[] cursorOptions,
                UiTask bindViewsTask,
                UiTask setScreenAlwaysOnTask,
                UiTask setupSurfaceCallbacksTask,
                UiTask setupButtonsTask,
                UiTask loadSavedSettingsTask,
                UiTask updateIntraOnlyButtonTask,
                UiTask updateHostHintTask,
                CursorPolicyTask enforceCursorOverlayPolicyTask,
                UiTask updateSettingValueLabelsTask
        ) {
            this.logTag = logTag;
            this.activity = activity;
            this.profileOptions = profileOptions;
            this.encoderOptions = encoderOptions;
            this.cursorOptions = cursorOptions;
            this.bindViewsTask = bindViewsTask;
            this.setScreenAlwaysOnTask = setScreenAlwaysOnTask;
            this.setupSurfaceCallbacksTask = setupSurfaceCallbacksTask;
            this.setupButtonsTask = setupButtonsTask;
            this.loadSavedSettingsTask = loadSavedSettingsTask;
            this.updateIntraOnlyButtonTask = updateIntraOnlyButtonTask;
            this.updateHostHintTask = updateHostHintTask;
            this.enforceCursorOverlayPolicyTask = enforceCursorOverlayPolicyTask;
            this.updateSettingValueLabelsTask = updateSettingValueLabelsTask;
        }
    }

    private MainInitializationCoordinator() {
    }

    public static boolean initializeUiBindings(
            String logTag,
            AppCompatActivity activity,
            TextView ignoredStartupBuildVersionText,
            Spinner ignoredProfileSpinner,
            Spinner ignoredEncoderSpinner,
            Spinner ignoredCursorSpinner,
            String[] profileOptions,
            String[] encoderOptions,
            String[] cursorOptions,
            SeekBar ignoredResolutionSeek,
            SeekBar ignoredFpsSeek,
            SeekBar ignoredBitrateSeek,
            UiTask bindViewsTask,
            UiTask setScreenAlwaysOnTask,
            UiTask setupSurfaceCallbacksTask,
            UiTask setupButtonsTask,
            UiTask loadSavedSettingsTask,
            UiTask updateIntraOnlyButtonTask,
            UiTask updateHostHintTask,
            CursorPolicyTask enforceCursorOverlayPolicyTask,
            UiTask updateSettingValueLabelsTask
    ) {
        UiBindingsConfig config = new UiBindingsConfig(
                logTag,
                activity,
                profileOptions,
                encoderOptions,
                cursorOptions,
                bindViewsTask,
                setScreenAlwaysOnTask,
                setupSurfaceCallbacksTask,
                setupButtonsTask,
                loadSavedSettingsTask,
                updateIntraOnlyButtonTask,
                updateHostHintTask,
                enforceCursorOverlayPolicyTask,
                updateSettingValueLabelsTask
        );
        return initializeUiBindings(config);
    }

    public static boolean initializeUiBindings(UiBindingsConfig config) {
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
                config.activity,
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                config.profileOptions,
                config.encoderOptions,
                config.cursorOptions,
                () -> {
                    config.updateIntraOnlyButtonTask.run();
                    config.updateHostHintTask.run();
                },
                () -> {
                    config.enforceCursorOverlayPolicyTask.apply(false);
                    config.updateHostHintTask.run();
                }
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
        public final MainUiState uiState;
        public final StartupOverlayController startupOverlayController;
        public final UiStateRefreshTask refreshSettingsUiTask;
        public final UiTask setDebugControlsHiddenTask;
        public final UiTask applyBuildVariantUiTask;
        public final UiTask setDefaultStatsLineTask;
        public final UiTask updatePerfHudUnavailableTask;
        public final UiTask updatePreflightOverlayTask;
        public final UiTask setIdleWaitingStatusTask;
        public final StatusPoller statusPoller;

        public StartupStateConfig(
                MainUiState uiState,
                StartupOverlayController startupOverlayController,
                UiStateRefreshTask refreshSettingsUiTask,
                UiTask setDebugControlsHiddenTask,
                UiTask applyBuildVariantUiTask,
                UiTask setDefaultStatsLineTask,
                UiTask updatePerfHudUnavailableTask,
                UiTask updatePreflightOverlayTask,
                UiTask setIdleWaitingStatusTask,
                StatusPoller statusPoller
        ) {
            this.uiState = uiState;
            this.startupOverlayController = startupOverlayController;
            this.refreshSettingsUiTask = refreshSettingsUiTask;
            this.setDebugControlsHiddenTask = setDebugControlsHiddenTask;
            this.applyBuildVariantUiTask = applyBuildVariantUiTask;
            this.setDefaultStatsLineTask = setDefaultStatsLineTask;
            this.updatePerfHudUnavailableTask = updatePerfHudUnavailableTask;
            this.updatePreflightOverlayTask = updatePreflightOverlayTask;
            this.setIdleWaitingStatusTask = setIdleWaitingStatusTask;
            this.statusPoller = statusPoller;
        }
    }

    public static void initializeStartupState(
            MainUiState uiState,
            StartupOverlayController startupOverlayController,
            UiStateRefreshTask refreshSettingsUiTask,
            UiTask setDebugControlsHiddenTask,
            UiTask applyBuildVariantUiTask,
            UiTask setDefaultStatsLineTask,
            UiTask updatePerfHudUnavailableTask,
            UiTask updatePreflightOverlayTask,
            UiTask setIdleWaitingStatusTask,
            StatusPoller statusPoller
    ) {
        StartupStateConfig config = new StartupStateConfig(
                uiState,
                startupOverlayController,
                refreshSettingsUiTask,
                setDebugControlsHiddenTask,
                applyBuildVariantUiTask,
                setDefaultStatsLineTask,
                updatePerfHudUnavailableTask,
                updatePreflightOverlayTask,
                setIdleWaitingStatusTask,
                statusPoller
        );
        initializeStartupState(config);
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
