package com.wbeam.ui;

import android.os.Looper;
import android.view.View;
import android.widget.TextView;

public final class LiveLogUiAppender {
    @FunctionalInterface
    public interface UiThreadRunner {
        void run(Runnable task);
    }

    private LiveLogUiAppender() {
    }

    public static void append(
            TextView liveLogText,
            LiveLogBuffer liveLogBuffer,
            boolean liveLogVisible,
            String level,
            String line,
            UiThreadRunner uiThreadRunner
    ) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        Runnable task = () -> {
            if (liveLogText == null) {
                return;
            }
            liveLogText.setText(liveLogBuffer.append(level, line));
            liveLogText.setVisibility(liveLogVisible ? View.VISIBLE : View.GONE);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            uiThreadRunner.run(task);
        }
    }
}
