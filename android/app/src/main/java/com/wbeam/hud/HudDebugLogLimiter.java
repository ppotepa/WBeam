package com.wbeam.hud;

import android.os.SystemClock;

public final class HudDebugLogLimiter {
    private final long minIntervalMs;
    private long lastLoggedAtMs = 0L;
    private String lastSnapshot = "";

    public HudDebugLogLimiter(long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    public boolean shouldLog(String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastLoggedAtMs < minIntervalMs) {
            return false;
        }
        if (snapshot.equals(lastSnapshot)) {
            return false;
        }
        lastLoggedAtMs = now;
        lastSnapshot = snapshot;
        return true;
    }
}
