package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class HudOverlayDisplay {
    public static final class State {
        private String mode = "none";
        private String lastWebHtml = "";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getLastWebHtml() {
            return lastWebHtml;
        }

        public void setLastWebHtml(String lastWebHtml) {
            this.lastWebHtml = lastWebHtml;
        }
    }

    private HudOverlayDisplay() {
    }

    public static boolean showWebHtml(
            WebView perfHudWebView,
            TextView perfHudText,
            String modeTag,
            String html,
            State state
    ) {
        if (perfHudWebView == null || html == null) {
            return false;
        }
        if (!modeTag.equals(state.getMode()) || !html.equals(state.getLastWebHtml())) {
            perfHudWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            state.setLastWebHtml(html);
        }
        state.setMode(modeTag);
        perfHudWebView.setVisibility(View.VISIBLE);
        if (perfHudText != null) {
            perfHudText.setVisibility(View.GONE);
        }
        return true;
    }

    public static void showTextOnly(
            WebView perfHudWebView,
            TextView perfHudText,
            String modeTag,
            String text,
            int color,
            State state
    ) {
        state.setMode(modeTag);
        state.setLastWebHtml("");
        if (perfHudWebView != null) {
            perfHudWebView.setVisibility(View.GONE);
        }
        if (perfHudText != null) {
            perfHudText.setText(text == null ? "" : text);
            perfHudText.setTextColor(color);
            perfHudText.setVisibility(View.VISIBLE);
        }
    }
}
