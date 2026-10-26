package com.slingring.transfer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks how far a transfer has got: bytes done, current speed, time remaining,
 * and whether someone asked to cancel.
 *
 * One worker thread calls add() while the UI thread and other threads read, so the
 * counters are atomic and the cancel flag is volatile.
 *
 */
public class ProgressTracker {

    private static final long SAMPLE_INTERVAL_NANOS = 250_000_000L;   // 0.25 s
    private static final long STALL_NANOS = 2_000_000_000L;           // no data for 2 s => speed reads 0
    private static final double SMOOTHING = 0.3;                      // weight of the newest sample

    private final long total;
    private final AtomicLong bytes = new AtomicLong();
    private volatile boolean cancelled;

    // Speed sampling state, guarded by "this".
    private long lastSampleNanos = System.nanoTime();
    private long lastSampleBytes;
    private double speed;

    public ProgressTracker(long total) {
        this.total = Math.max(0, total);
    }

    /** Record that {@code count} more bytes were processed. */
    public void add(long count) {
        bytes.addAndGet(count);
        sample();
    }

    private synchronized void sample() {
        long now = System.nanoTime();
        long elapsed = now - lastSampleNanos;
        if (elapsed < SAMPLE_INTERVAL_NANOS) {
            return;
        }
        long current = bytes.get();
        double instant = (current - lastSampleBytes) / (elapsed / 1_000_000_000.0);
        speed = (speed == 0) ? instant : SMOOTHING * instant + (1 - SMOOTHING) * speed;
        lastSampleNanos = now;
        lastSampleBytes = current;
    }

    /** Start counting from zero again (used when moving from hashing to sending, and so on). */
    public synchronized void reset() {
        bytes.set(0);
        lastSampleBytes = 0;
        lastSampleNanos = System.nanoTime();
        speed = 0;
    }

    public long bytesTransferred() {
        return bytes.get();
    }

    public long total() {
        return total;
    }

    /** Bytes per second, smoothed. Reads 0 when nothing has moved recently. */
    public synchronized double speed() {
        if (System.nanoTime() - lastSampleNanos > STALL_NANOS) {
            return 0;
        }
        return speed;
    }

    /** Estimated seconds remaining, or -1 when it cannot be known yet. */
    public long eta() {
        double current = speed();
        if (current <= 0 || total <= 0) {
            return -1;
        }
        long remaining = total - bytes.get();
        return remaining <= 0 ? 0 : (long) Math.ceil(remaining / current);
    }

    /** Ask the worker to stop. The copy and hash loops poll {@link #isCancelled()}. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
