package com.wbeam.ui;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;
import com.wbeam.widget.FpsLossGraphView;

public final class MainActivityPrimaryViewsBinder {
    /**
     * Plain data carrier for primary views binding.
     */
    public static final class Views {
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

        public View getRootLayout() { return rootLayout; }
        public View getTopBar() { return topBar; }
        public View getQuickActionRow() { return quickActionRow; }
        public View getSettingsPanel() { return settingsPanel; }
        public View getSimpleMenuPanel() { return simpleMenuPanel; }
        public View getStatusPanel() { return statusPanel; }
        public View getPerfHudPanel() { return perfHudPanel; }
        public View getDebugInfoPanel() { return debugInfoPanel; }
        public FpsLossGraphView getDebugFpsGraphView() { return debugFpsGraphView; }
        public View getPreflightOverlay() { return preflightOverlay; }
        public View getDebugControlsRow() { return debugControlsRow; }
        public View getStatusLed() { return statusLed; }
        public View getCursorOverlay() { return cursorOverlay; }
        public TextView getStatusText() { return statusText; }
        public TextView getDetailText() { return detailText; }
        public TextView getBpsText() { return bpsText; }
        public TextView getStatsText() { return statsText; }
        public TextView getPerfHudText() { return perfHudText; }
        public WebView getPerfHudWebView() { return perfHudWebView; }
        public TextView getDebugInfoText() { return debugInfoText; }
    }

    private MainActivityPrimaryViewsBinder() {
    }

    public static Views bind(AppCompatActivity activity) {
        Views views = new Views();
        views.rootLayout = activity.findViewById(R.id.rootLayout);
        views.topBar = activity.findViewById(R.id.topBar);
        views.quickActionRow = activity.findViewById(R.id.quickActionRow);
        views.settingsPanel = activity.findViewById(R.id.settingsPanel);
        views.simpleMenuPanel = activity.findViewById(R.id.simpleMenuPanel);
        views.statusPanel = activity.findViewById(R.id.statusPanel);
        views.perfHudPanel = activity.findViewById(R.id.perfHudPanel);
        views.debugInfoPanel = activity.findViewById(R.id.debugInfoPanel);
        views.debugFpsGraphView = activity.findViewById(R.id.debugFpsGraph);
        views.preflightOverlay = activity.findViewById(R.id.preflightOverlay);
        views.debugControlsRow = activity.findViewById(R.id.debugControlsRow);
        views.statusLed = activity.findViewById(R.id.statusLed);
        views.cursorOverlay = activity.findViewById(R.id.cursorOverlay);
        views.statusText = activity.findViewById(R.id.statusText);
        views.detailText = activity.findViewById(R.id.detailText);
        views.bpsText = activity.findViewById(R.id.bpsText);
        views.statsText = activity.findViewById(R.id.statsText);
        views.perfHudText = activity.findViewById(R.id.perfHudText);
        views.perfHudWebView = activity.findViewById(R.id.perfHudWebView);
        views.debugInfoText = activity.findViewById(R.id.debugInfoText);
        return views;
    }
}
