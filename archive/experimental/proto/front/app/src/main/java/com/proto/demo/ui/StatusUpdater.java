package com.proto.demo.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

/**
 * Single responsibility: post status text updates to the main (UI) thread.
 *
 * May be called from any background thread.
 */
public class StatusUpdater {

    private static final String TAG = "WBeam";

    private final TextView view;
    private final Handler  handler = new Handler(Looper.getMainLooper());

    public StatusUpdater(TextView view) {
        this.view = view;
    }

    public void set(final String msg) {
        Log.i(TAG, msg);
        handler.post(() -> {
            if (view != null) view.setText(msg);
        });
    }
}
