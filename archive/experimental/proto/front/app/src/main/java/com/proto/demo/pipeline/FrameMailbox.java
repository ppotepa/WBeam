package com.proto.demo.pipeline;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free single-slot frame mailbox with a recycling buffer pool.
 *
 * Single responsibility: decouple the IO (producer) thread from the render
 * (consumer) thread so that neither ever blocks the other.
 *
 * Contract:
 *  - IO thread:     acquire() → fill buffer → publish()
 *  - Render thread: poll() → render → recycle()
 *
 * If the render thread is busy when a new frame arrives, the previous pending
 * frame is evicted (dropped) and the drop counter is incremented.  This
 * guarantees IO never stalls on render latency.
 */
public final class FrameMailbox {

    // ── Public frame handle ───────────────────────────────────────────────────
    public static final class Frame {
        public byte[] data;
        public int    len;

        Frame(int capacity) { data = new byte[capacity]; }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final AtomicReference<Frame>       pending      = new AtomicReference<>(null);
    private final ConcurrentLinkedQueue<Frame> pool         = new ConcurrentLinkedQueue<>();
    private final AtomicLong                   droppedTotal = new AtomicLong(0);

    public FrameMailbox(int initialCapacity) {
        // Pre-warm the pool with 3 buffers (IO + pending + render in-flight)
        for (int i = 0; i < 3; i++) {
            pool.offer(new Frame(initialCapacity));
        }
    }

    // ── IO thread API ─────────────────────────────────────────────────────────

    /**
     * Get a recyclable frame buffer large enough for {@code minLen} bytes.
     * Allocates a new buffer only when the pool is empty or too small.
     */
    public Frame acquire(int minLen) {
        Frame f = pool.poll();
        if (f == null) f = new Frame(minLen);
        if (f.data.length < minLen) f.data = new byte[minLen];
        return f;
    }

    /**
     * Publish {@code frame} as the latest available frame.
     * Any frame that was already pending but not yet consumed is evicted and
     * returned to the pool (drop).
     */
    public void publish(Frame frame) {
        Frame evicted = pending.getAndSet(frame);
        if (evicted != null) {
            droppedTotal.incrementAndGet();
            pool.offer(evicted);
        }
    }

    // ── Render thread API ─────────────────────────────────────────────────────

    /**
     * Atomically take the latest pending frame.
     * Returns {@code null} if no frame has been published since the last poll.
     */
    public Frame poll() {
        return pending.getAndSet(null);
    }

    /** Return a rendered frame to the pool for reuse by the IO thread. */
    public void recycle(Frame f) {
        pool.offer(f);
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /** Returns the cumulative drop count since the last call (resets on read). */
    public long getAndResetDropped() {
        return droppedTotal.getAndSet(0);
    }
}
