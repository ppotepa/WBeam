package com.wbeam.ui.state;

/**
 * Plain data carrier for main UI state.
 */
public final class MainUiState {
    private boolean debugControlsVisible = false;
    private boolean debugOverlayVisible = false;
    private boolean liveLogVisible = false;
    private boolean surfaceReady = false;
    private boolean preflightComplete = false;
    private int preflightAnimTick = 0;
    private long startupBeganAtMs = 0L;
    private boolean startupDismissed = false;
    private boolean handshakeResolved = false;
    private int controlRetryCount = 0;

    public boolean isDebugControlsVisible() { return debugControlsVisible; }
    public void setDebugControlsVisible(boolean debugControlsVisible) { this.debugControlsVisible = debugControlsVisible; }
    public boolean isDebugOverlayVisible() { return debugOverlayVisible; }
    public void setDebugOverlayVisible(boolean debugOverlayVisible) { this.debugOverlayVisible = debugOverlayVisible; }
    public boolean isLiveLogVisible() { return liveLogVisible; }
    public void setLiveLogVisible(boolean liveLogVisible) { this.liveLogVisible = liveLogVisible; }
    public boolean isSurfaceReady() { return surfaceReady; }
    public void setSurfaceReady(boolean surfaceReady) { this.surfaceReady = surfaceReady; }
    public boolean isPreflightComplete() { return preflightComplete; }
    public void setPreflightComplete(boolean preflightComplete) { this.preflightComplete = preflightComplete; }
    public int getPreflightAnimTick() { return preflightAnimTick; }
    public void setPreflightAnimTick(int preflightAnimTick) { this.preflightAnimTick = preflightAnimTick; }
    public long getStartupBeganAtMs() { return startupBeganAtMs; }
    public void setStartupBeganAtMs(long startupBeganAtMs) { this.startupBeganAtMs = startupBeganAtMs; }
    public boolean isStartupDismissed() { return startupDismissed; }
    public void setStartupDismissed(boolean startupDismissed) { this.startupDismissed = startupDismissed; }
    public boolean isHandshakeResolved() { return handshakeResolved; }
    public void setHandshakeResolved(boolean handshakeResolved) { this.handshakeResolved = handshakeResolved; }
    public int getControlRetryCount() { return controlRetryCount; }
    public void setControlRetryCount(int controlRetryCount) { this.controlRetryCount = controlRetryCount; }
}
