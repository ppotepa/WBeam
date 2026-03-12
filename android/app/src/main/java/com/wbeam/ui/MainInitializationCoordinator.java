package com.wbeam.ui;

import android.os.SystemClock;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.BuildConfig;
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

    private MainInitializationCoordinator() {
    }

    public static boolean initializeUiBindings(
            String logTag,
            AppCompatActivity activity,
            TextView startupBuildVersionText,
            Spinner profileSpinner,
            Spinner encoderSpinner,
            Spinner cursorSpinner,
            String[] profileOptions,
            String[] encoderOptions,
            String[] cursorOptions,
            SeekBar resolutionSeek,
            SeekBar fpsSeek,
            SeekBar bitrateSeek,
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
        bindViewsTask.run();
        setScreenAlwaysOnTask.run();
        StartupBuildVersionPresenter.apply(startupBuildVersionText, BuildConfig.WBEAM_BUILD_REV);
        MainActivitySpinnersSetup.setup(
                activity,
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                profileOptions,
                encoderOptions,
                cursorOptions,
                () -> {
                    updateIntraOnlyButtonTask.run();
                    updateHostHintTask.run();
                },
                () -> {
                    enforceCursorOverlayPolicyTask.apply(false);
                    updateHostHintTask.run();
                }
        );
        MainActivityUiBinder.setupSeekbars(
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                updateSettingValueLabelsTask::run
        );
        setupSurfaceCallbacksTask.run();
        setupButtonsTask.run();
        loadSavedSettingsTask.run();
        boolean hwAvcDecodeAvailable = DecoderCapabilityInspector.hasHardwareAvcDecoder(logTag);
        Log.i(
                logTag,
                "startup transport api_impl=" + BuildConfig.WBEAM_API_IMPL
                        + " api=" + HostApiClient.API_BASE
                        + " stream=tcp://" + BuildConfig.WBEAM_STREAM_HOST + ":" + BuildConfig.WBEAM_STREAM_PORT
        );
        return hwAvcDecodeAvailable;
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
        refreshSettingsUiTask.refresh(false);
        setDebugControlsHiddenTask.run();
        applyBuildVariantUiTask.run();
        setDefaultStatsLineTask.run();
        updatePerfHudUnavailableTask.run();
        uiState.startupBeganAtMs = SystemClock.elapsedRealtime();
        uiState.handshakeResolved = false;
        uiState.startupDismissed = false;
        StartupOverlayControllerGuard.startPulse(startupOverlayController);
        updatePreflightOverlayTask.run();
        setIdleWaitingStatusTask.run();
        statusPoller.start();
    }
}
