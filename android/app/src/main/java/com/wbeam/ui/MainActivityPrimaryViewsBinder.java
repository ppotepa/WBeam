package com.wbeam.ui;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;
import com.wbeam.widget.FpsLossGraphView;

public final class MainActivityPrimaryViewsBinder {
    public static final class Views {
        public View rootLayout;
        public View topBar;
        public View quickActionRow;
        public View settingsPanel;
        public View simpleMenuPanel;
        public View statusPanel;
        public View perfHudPanel;
        public View debugInfoPanel;
        public FpsLossGraphView debugFpsGraphView;
        public View preflightOverlay;
        public View debugControlsRow;
        public View statusLed;
        public View cursorOverlay;

        public TextView statusText;
        public TextView detailText;
        public TextView bpsText;
        public TextView statsText;
        public TextView perfHudText;
        public WebView perfHudWebView;
        public TextView debugInfoText;
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
