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

    /**
     * Plain data carrier for bound views.
     */
    @SuppressWarnings("java:S1104")
    public static final class BoundViews {
        public MainActivityPrimaryViewsBinder.Views primaryViews;
        public MainActivityControlViewsBinder.Views controlViews;
        public SurfaceView previewSurface;
        public ImmersiveModeController immersiveModeController;
        public SettingsPanelController settingsPanelController;
        public TextView startupBuildVersionText;
        public StartupOverlayController startupOverlayController;
        public CursorOverlayController cursorOverlayController;
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
        out.primaryViews = MainActivityPrimaryViewsBinder.bind(activity);
        out.controlViews = MainActivityControlViewsBinder.bind(activity);
        out.previewSurface = activity.findViewById(R.id.previewSurface);
        out.immersiveModeController =
                new ImmersiveModeController(activity, out.primaryViews.rootLayout);
        out.settingsPanelController = new SettingsPanelController(out.primaryViews.settingsPanel);
        out.startupBuildVersionText = StartupOverlayViewsBinder.bind(activity, startupOverlayViews);
        out.startupOverlayController =
                new StartupOverlayController(uiHandler, out.primaryViews.preflightOverlay);
        out.startupOverlayController.setTickListener(tickListener::onTick);
        out.cursorOverlayController = new CursorOverlayController(
                out.primaryViews.cursorOverlay,
                out.controlViews.cursorOverlayButton
        );
        MainActivityUiBinder.setupTrainerHudWebView(out.primaryViews.perfHudWebView);
        return out;
    }
}
