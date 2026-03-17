package com.wbeam.ui.state;

@SuppressWarnings("java:S1104")
public final class MainUiState {
    public boolean debugControlsVisible = false;
    public boolean debugOverlayVisible = false;
    public boolean liveLogVisible = false;
    public boolean surfaceReady = false;
    public boolean preflightComplete = false;
    public int preflightAnimTick = 0;
    public long startupBeganAtMs = 0L;
    public boolean startupDismissed = false;
    public boolean handshakeResolved = false;
    public int controlRetryCount = 0;
}
