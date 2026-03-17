package com.wbeam.ui;

import android.os.Handler;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
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

    @SuppressWarnings("java:S1104")
    public static final class BoundViews {
        private MainActivityPrimaryViewsBinder.Views primaryViews;
        private MainActivityControlViewsBinder.Views controlViews;
        private SurfaceView previewSurface;
        private ImmersiveModeController immersiveModeController;
        private SettingsPanelController settingsPanelController;
        private TextView startupBuildVersionText;
        private StartupOverlayController startupOverlayController;
        private CursorOverlayController cursorOverlayController;

        public MainActivityPrimaryViewsBinder.Views getPrimaryViews() {
            return primaryViews;
        }

        public void setPrimaryViews(MainActivityPrimaryViewsBinder.Views primaryViews) {
            this.primaryViews = primaryViews;
        }

        public MainActivityControlViewsBinder.Views getControlViews() {
            return controlViews;
        }

        public void setControlViews(MainActivityControlViewsBinder.Views controlViews) {
            this.controlViews = controlViews;
        }

        public SurfaceView getPreviewSurface() {
            return previewSurface;
        }

        public void setPreviewSurface(SurfaceView previewSurface) {
            this.previewSurface = previewSurface;
        }

        public ImmersiveModeController getImmersiveModeController() {
            return immersiveModeController;
        }

        public void setImmersiveModeController(ImmersiveModeController immersiveModeController) {
            this.immersiveModeController = immersiveModeController;
        }

        public SettingsPanelController getSettingsPanelController() {
            return settingsPanelController;
        }

        public void setSettingsPanelController(SettingsPanelController settingsPanelController) {
            this.settingsPanelController = settingsPanelController;
        }

        public TextView getStartupBuildVersionText() {
            return startupBuildVersionText;
        }

        public void setStartupBuildVersionText(TextView startupBuildVersionText) {
            this.startupBuildVersionText = startupBuildVersionText;
        }

        public StartupOverlayController getStartupOverlayController() {
            return startupOverlayController;
        }

        public void setStartupOverlayController(StartupOverlayController startupOverlayController) {
            this.startupOverlayController = startupOverlayController;
        }

        public CursorOverlayController getCursorOverlayController() {
            return cursorOverlayController;
        }

        public void setCursorOverlayController(CursorOverlayController cursorOverlayController) {
            this.cursorOverlayController = cursorOverlayController;
        }
    }

    private MainViewBindingCoordinator() {
    }

    public static BoundViews bind(
            AppCompatActivity activity,
            Handler uiHandler,
            StartupOverlayViewRenderer.Views startupOverlayViews,
            TickListener tickListener
        ) {
        BoundViews out = new BoundViews();
        out.setPrimaryViews(MainActivityPrimaryViewsBinder.bind(activity));
        out.setControlViews(MainActivityControlViewsBinder.bind(activity));
        out.setPreviewSurface(activity.findViewById(R.id.previewSurface));
        out.setImmersiveModeController(
                new ImmersiveModeController(activity, out.getPrimaryViews().getRootLayout())
        );
        out.setSettingsPanelController(new SettingsPanelController(out.getPrimaryViews().getSettingsPanel()));
        out.setStartupBuildVersionText(StartupOverlayViewsBinder.bind(activity, startupOverlayViews));
        out.setStartupOverlayController(
                new StartupOverlayController(uiHandler, out.getPrimaryViews().getPreflightOverlay())
        );
        out.getStartupOverlayController().setTickListener(tickListener::onTick);
        out.setCursorOverlayController(new CursorOverlayController(
                out.getPrimaryViews().getCursorOverlay(),
                out.getControlViews().getCursorOverlayButton()
        ));
        MainActivityUiBinder.setupHudWebView(out.getPrimaryViews().getPerfHudWebView());
        return out;
    }
}
