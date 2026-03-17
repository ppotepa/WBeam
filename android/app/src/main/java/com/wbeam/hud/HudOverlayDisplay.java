package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class HudOverlayDisplay {
    @SuppressWarnings("java:S1104")
    public static final class State {
        private String mode = "none";
        private String lastWebHtml = "";
        private String lastWebUpdateScript = "";
        private long lastRenderAtMs = 0L;
        private long runtimeSemanticSignature = Long.MIN_VALUE;
        private String lastRuntimeTextFallback = "";

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

        public String getLastWebUpdateScript() {
            return lastWebUpdateScript;
        }

        public void setLastWebUpdateScript(String lastWebUpdateScript) {
            this.lastWebUpdateScript = lastWebUpdateScript;
        }

        public long getLastRenderAtMs() {
            return lastRenderAtMs;
        }

        public void setLastRenderAtMs(long lastRenderAtMs) {
            this.lastRenderAtMs = lastRenderAtMs;
        }

        public long getRuntimeSemanticSignature() {
            return runtimeSemanticSignature;
        }

        public void setRuntimeSemanticSignature(long runtimeSemanticSignature) {
            this.runtimeSemanticSignature = runtimeSemanticSignature;
        }

        public String getLastRuntimeTextFallback() {
            return lastRuntimeTextFallback;
        }

        public void setLastRuntimeTextFallback(String lastRuntimeTextFallback) {
            this.lastRuntimeTextFallback = lastRuntimeTextFallback;
        }
    }

    private HudOverlayDisplay() {
    }

    public static boolean showWebHtml(
            WebView perfHudWebView,
            TextView perfHudText,
            String modeTag,
            RuntimeHudWebPayloadBuilder.RenderPayload payload,
            State state
    ) {
        if (perfHudWebView == null || payload == null || payload.getHtmlContent() == null) {
            return false;
        }
        if (!modeTag.equals(state.getMode()) || state.getLastWebHtml().isEmpty()) {
            String initialHtml = RuntimeHudShellRenderer.buildHtml(payload.getHtmlContent());
            state.setLastWebUpdateScript("");
            perfHudWebView.loadDataWithBaseURL(null, initialHtml, "text/html", "utf-8", null);
            state.setLastWebHtml(initialHtml);
        } else {
            String updateScript = payload.getUpdateScript();
            if (updateScript != null
                    && !updateScript.isEmpty()
                    && !updateScript.equals(state.getLastWebUpdateScript())) {
                perfHudWebView.evaluateJavascript(updateScript, null);
                state.setLastWebUpdateScript(updateScript);
            }
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
        state.setLastWebUpdateScript("");
        if (!"runtime".equals(modeTag)) {
            state.setLastRuntimeTextFallback("");
            state.setRuntimeSemanticSignature(Long.MIN_VALUE);
        }
        if (perfHudWebView != null) {
            perfHudWebView.setVisibility(View.GONE);
        }
        if (perfHudText != null) {
            perfHudText.setText(text == null ? "" : text);
            perfHudText.setTextColor(color);
            perfHudText.setVisibility(View.VISIBLE);
        }
    }

    public static boolean showWebOnly(
            WebView perfHudWebView,
            TextView perfHudText,
            String modeTag,
            State state
    ) {
        if (perfHudWebView == null) {
            return false;
        }
        state.setMode(modeTag);
        perfHudWebView.setVisibility(View.VISIBLE);
        if (perfHudText != null) {
            perfHudText.setVisibility(View.GONE);
        }
        return true;
    }
}
