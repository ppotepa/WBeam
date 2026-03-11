package com.proto.demo.rendering;

import android.os.Process;
import android.util.Log;

import com.proto.demo.pipeline.FrameMailbox;
import com.proto.demo.ui.StatusUpdater;

import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated render loop that consumes frames from a {@link FrameMailbox}.
 *
 * Single responsibility: drain the mailbox as fast as possible on its own
 * thread, report fps and drop statistics, and never block the IO thread.
 *
 * Threading: must be run on a dedicated {@code Thread("wbeam-render")}.
 */
public final class RenderLoop implements Runnable {

    private static final String TAG              = "WBeam";
    private static final long   STAT_INTERVAL_MS = 2_000L;
    private static final long   IDLE_PARK_NS     = 500_000L; // 0.5 ms

    private final FrameMailbox  mailbox;
    private final FrameRenderer renderer;
    private final StatusUpdater status;
    private final String        label;   // "ADB" or "HTTP"

    private volatile boolean running = true;

    public RenderLoop(FrameMailbox mailbox,
                      FrameRenderer renderer,
                      StatusUpdater status,
                      String label) {
        this.mailbox  = mailbox;
        this.renderer = renderer;
        this.status   = status;
        this.label    = label;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        long frameCount  = 0;
        long lastStatMs  = System.currentTimeMillis();

        while (running && !Thread.currentThread().isInterrupted()) {
            FrameMailbox.Frame f = mailbox.poll();
            if (f == null) {
                // Nothing pending — park briefly rather than spin-burning a core
                LockSupport.parkNanos(IDLE_PARK_NS);
                continue;
            }

            try {
                renderer.render(f.data, f.len);
                frameCount++;
            } catch (Exception e) {
                Log.w(TAG, "render exception: " + e.getMessage());
            } finally {
                mailbox.recycle(f);
            }

            // ── Periodic stats ────────────────────────────────────────────────
            long now = System.currentTimeMillis();
            if (now - lastStatMs >= STAT_INTERVAL_MS) {
                double fps     = frameCount * 1000.0 / (now - lastStatMs);
                long   dropped = mailbox.getAndResetDropped();
                status.set(String.format("%s  %.1f fps  drop=%d", label, fps, dropped));
                frameCount  = 0;
                lastStatMs  = now;
            }
        }

        Log.i(TAG, "RenderLoop exited");
    }
}
