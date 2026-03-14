package com.wbeam.ui;

import android.os.Handler;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;
import com.wbeam.input.CursorOverlayController;
import com.wbeam.input.ImmersiveModeController;
import com.wbeam.startup.StartupOverlayController;
import com.wbeam.startup.StartupOverlayViewRenderer;
import com.wbeam.startup.StartupOverlayViewsBinder;

public final class MainViewBindingCoordinator {
    public interface TickListener {
        void onTick(int tick);
    }

    /**
     * Plain data carrier for bound views.
     */
    public static final class BoundViews {
        private final MainActivityPrimaryViewsBinder.Views primaryViews;
        private final MainActivityControlViewsBinder.Views controlViews;
        private final SurfaceView previewSurface;
        private final ImmersiveModeController immersiveModeController;
        private final SettingsPanelController settingsPanelController;
        private final TextView startupBuildVersionText;
        private final StartupOverlayController startupOverlayController;
        private final CursorOverlayController cursorOverlayController;

        private BoundViews(
                MainActivityPrimaryViewsBinder.Views primaryViews,
                MainActivityControlViewsBinder.Views controlViews,
                SurfaceView previewSurface,
                ImmersiveModeController immersiveModeController,
                SettingsPanelController settingsPanelController,
                TextView startupBuildVersionText,
                StartupOverlayController startupOverlayController,
                CursorOverlayController cursorOverlayController
        ) {
            this.primaryViews = primaryViews;
            this.controlViews = controlViews;
            this.previewSurface = previewSurface;
            this.immersiveModeController = immersiveModeController;
            this.settingsPanelController = settingsPanelController;
            this.startupBuildVersionText = startupBuildVersionText;
            this.startupOverlayController = startupOverlayController;
            this.cursorOverlayController = cursorOverlayController;
        }

        public MainActivityPrimaryViewsBinder.Views getPrimaryViews() { return primaryViews; }
        public MainActivityControlViewsBinder.Views getControlViews() { return controlViews; }
        public SurfaceView getPreviewSurface() { return previewSurface; }
        public ImmersiveModeController getImmersiveModeController() { return immersiveModeController; }
        public SettingsPanelController getSettingsPanelController() { return settingsPanelController; }
        public TextView getStartupBuildVersionText() { return startupBuildVersionText; }
        public StartupOverlayController getStartupOverlayController() { return startupOverlayController; }
        public CursorOverlayController getCursorOverlayController() { return cursorOverlayController; }
    }

    private MainViewBindingCoordinator() {
    }

    public static BoundViews bind(
            AppCompatActivity activity,
            Handler uiHandler,
            StartupOverlayViewRenderer.Views startupOverlayViews,
            TickListener tickListener
    ) {
        MainActivityPrimaryViewsBinder.Views primaryViews = MainActivityPrimaryViewsBinder.bind(activity);
        MainActivityControlViewsBinder.Views controlViews = MainActivityControlViewsBinder.bind(activity);
        SurfaceView previewSurface = activity.findViewById(R.id.previewSurface);
        ImmersiveModeController immersiveModeController =
                new ImmersiveModeController(activity, primaryViews.getRootLayout());
        SettingsPanelController settingsPanelController =
                new SettingsPanelController(primaryViews.getSettingsPanel());
        TextView startupBuildVersionText = StartupOverlayViewsBinder.bind(activity, startupOverlayViews);
        StartupOverlayController startupOverlayController =
                new StartupOverlayController(uiHandler, primaryViews.getPreflightOverlay());
        startupOverlayController.setTickListener(tickListener::onTick);
        CursorOverlayController cursorOverlayController = new CursorOverlayController(
                primaryViews.getCursorOverlay(),
                controlViews.getCursorOverlayButton()
        );
        MainActivityUiBinder.setupTrainerHudWebView(primaryViews.getPerfHudWebView());
        return new BoundViews(
                primaryViews,
                controlViews,
                previewSurface,
                immersiveModeController,
                settingsPanelController,
                startupBuildVersionText,
                startupOverlayController,
                cursorOverlayController
        );
    }
}
