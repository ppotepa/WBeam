package com.wbeam.ui;

import android.graphics.Color;

public final class StatusColorResolver {
    private StatusColorResolver() {}

    public static int ledColorForState(String state, String streamingState, String connectingState) {
        if (streamingState.equals(state)) {
            return Color.parseColor("#22C55E");
        }
        if (connectingState.equals(state)) {
            return Color.parseColor("#F59E0B");
        }
        return Color.parseColor("#EF4444");
    }
}
