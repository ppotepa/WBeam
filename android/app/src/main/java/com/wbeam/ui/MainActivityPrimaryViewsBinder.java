package com.wbeam.ui;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;
import com.wbeam.widget.FpsLossGraphView;

public final class MainActivityPrimaryViewsBinder {
    @SuppressWarnings({"java:S1104", "java:S107"})
    public static final class Views {
        private final View rootLayout;
        private final View topBar;
        private final View quickActionRow;
        private final View settingsPanel;
        private final View simpleMenuPanel;
        private final View statusPanel;
        private final View perfHudPanel;
        private final View debugInfoPanel;
        private final FpsLossGraphView debugFpsGraphView;
        private final View preflightOverlay;
        private final View debugControlsRow;
        private final View statusLed;
        private final View cursorOverlay;
        private final TextView statusText;
        private final TextView detailText;
        private final TextView bpsText;
        private final TextView statsText;
        private final TextView perfHudText;
        private final WebView perfHudWebView;
        private final TextView debugInfoText;

        private Views(
                View rootLayout,
                View topBar,
                View quickActionRow,
                View settingsPanel,
                View simpleMenuPanel,
                View statusPanel,
                View perfHudPanel,
                View debugInfoPanel,
                FpsLossGraphView debugFpsGraphView,
                View preflightOverlay,
                View debugControlsRow,
                View statusLed,
                View cursorOverlay,
                TextView statusText,
                TextView detailText,
                TextView bpsText,
                TextView statsText,
                TextView perfHudText,
                WebView perfHudWebView,
                TextView debugInfoText
        ) {
            this.rootLayout = rootLayout;
            this.topBar = topBar;
            this.quickActionRow = quickActionRow;
            this.settingsPanel = settingsPanel;
            this.simpleMenuPanel = simpleMenuPanel;
            this.statusPanel = statusPanel;
            this.perfHudPanel = perfHudPanel;
            this.debugInfoPanel = debugInfoPanel;
            this.debugFpsGraphView = debugFpsGraphView;
            this.preflightOverlay = preflightOverlay;
            this.debugControlsRow = debugControlsRow;
            this.statusLed = statusLed;
            this.cursorOverlay = cursorOverlay;
            this.statusText = statusText;
            this.detailText = detailText;
            this.bpsText = bpsText;
            this.statsText = statsText;
            this.perfHudText = perfHudText;
            this.perfHudWebView = perfHudWebView;
            this.debugInfoText = debugInfoText;
        }

        public View getRootLayout() {
            return rootLayout;
        }

        public View getTopBar() {
            return topBar;
        }

        public View getQuickActionRow() {
            return quickActionRow;
        }

        public View getSettingsPanel() {
            return settingsPanel;
        }

        public View getSimpleMenuPanel() {
            return simpleMenuPanel;
        }

        public View getStatusPanel() {
            return statusPanel;
        }

        public View getPerfHudPanel() {
            return perfHudPanel;
        }

        public View getDebugInfoPanel() {
            return debugInfoPanel;
        }

        public FpsLossGraphView getDebugFpsGraphView() {
            return debugFpsGraphView;
        }

        public View getPreflightOverlay() {
            return preflightOverlay;
        }

        public View getDebugControlsRow() {
            return debugControlsRow;
        }

        public View getStatusLed() {
            return statusLed;
        }

        public View getCursorOverlay() {
            return cursorOverlay;
        }

        public TextView getStatusText() {
            return statusText;
        }

        public TextView getDetailText() {
            return detailText;
        }

        public TextView getBpsText() {
            return bpsText;
        }

        public TextView getStatsText() {
            return statsText;
        }

        public TextView getPerfHudText() {
            return perfHudText;
        }

        public WebView getPerfHudWebView() {
            return perfHudWebView;
        }

        public TextView getDebugInfoText() {
            return debugInfoText;
        }
    }

    private MainActivityPrimaryViewsBinder() {
    }

    public static Views bind(AppCompatActivity activity) {
        return new Views(
                activity.findViewById(R.id.rootLayout),
                activity.findViewById(R.id.topBar),
                activity.findViewById(R.id.quickActionRow),
                activity.findViewById(R.id.settingsPanel),
                activity.findViewById(R.id.simpleMenuPanel),
                activity.findViewById(R.id.statusPanel),
                activity.findViewById(R.id.perfHudPanel),
                activity.findViewById(R.id.debugInfoPanel),
                activity.findViewById(R.id.debugFpsGraph),
                activity.findViewById(R.id.preflightOverlay),
                activity.findViewById(R.id.debugControlsRow),
                activity.findViewById(R.id.statusLed),
                activity.findViewById(R.id.cursorOverlay),
                activity.findViewById(R.id.statusText),
                activity.findViewById(R.id.detailText),
                activity.findViewById(R.id.bpsText),
                activity.findViewById(R.id.statsText),
                activity.findViewById(R.id.perfHudText),
                activity.findViewById(R.id.perfHudWebView),
                activity.findViewById(R.id.debugInfoText)
        );
    }
}
