package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class HudOverlayDisplay {
    public static final class State {
        String mode = "none";
        String lastWebHtml = "";
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
        if (!modeTag.equals(state.mode) || !html.equals(state.lastWebHtml)) {
            perfHudWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            state.lastWebHtml = html;
        }
        state.mode = modeTag;
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
        state.mode = modeTag;
        state.lastWebHtml = "";
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
