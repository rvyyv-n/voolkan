/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.latency;

import java.util.concurrent.locks.LockSupport;

/**
 * Precise frame-rate limiter replacing vanilla's coarse {@code glfwWaitEventsTimeout} loop.
 * <p>
 * Tracks a fixed deadline cadence ({@code next = previous + period}) and resyncs when a frame
 * overruns by more than one period so stalls never cause catch-up bursts. Sleeps via
 * {@link LockSupport#parkNanos} until an adaptive spin margin before the deadline, then
 * busy-waits with {@link Thread#onSpinWait()}.
 * <p>
 * Disable with {@code -Dvoolkan.preciseLimiter=false} to fall back to vanilla pacing.
 */
public final class FrameLimiter {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("voolkan.preciseLimiter", "true"));

    private static final int VANILLA_UNLIMITED_FPS = 260;
    private static final long MIN_SPIN_MARGIN_NS = 250_000L;      // 0.25 ms
    private static final long MAX_SPIN_MARGIN_NS = 2_500_000L;    // 2.5 ms
    private static final long SAFETY_BUFFER_NS = 250_000L;        // 0.25 ms
    private static final double EWMA_ALPHA = 0.15;

    private static int lastFps = -1;
    private static long nextDeadline = 0L;
    private static long ewmaOvershootNs = 500_000L;               // 0.5 ms initial estimate
    private static long spinMarginNs = ewmaOvershootNs + SAFETY_BUFFER_NS;

    private FrameLimiter() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void reset() {
        lastFps = -1;
        nextDeadline = 0L;
    }

    /**
     * Wait until the next frame deadline for {@code fps}, then run {@code pollEvents} once
     * if any wait occurred so input arriving during the sleep is not a full frame stale.
     */
    public static void limit(int fps, Runnable pollEvents) {
        if (fps <= 0 || fps >= VANILLA_UNLIMITED_FPS) {
            reset();
            return;
        }

        long periodNs = Math.round(1_000_000_000.0 / fps);
        long now = System.nanoTime();

        if (fps != lastFps || nextDeadline == 0L) {
            lastFps = fps;
            nextDeadline = now + periodNs;
            return;
        }

        long deadline = nextDeadline;
        if (now >= deadline) {
            // Resync on overrun > 1 period to avoid catch-up bursts.
            nextDeadline = (now - deadline > periodNs) ? (now + periodNs) : (deadline + periodNs);
            return;
        }

        long effectiveSpinMargin = Math.min(spinMarginNs, periodNs / 2L);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= effectiveSpinMargin) {
                break;
            }

            long sleepNs = remaining - effectiveSpinMargin;
            long before = System.nanoTime();
            LockSupport.parkNanos(sleepNs);
            long after = System.nanoTime();

            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            long overshoot = (after - before) - sleepNs;
            if (overshoot > 0L) {
                long clampedOvershoot = Math.min(overshoot, MAX_SPIN_MARGIN_NS * 2L);
                ewmaOvershootNs = (long) (ewmaOvershootNs * (1.0 - EWMA_ALPHA) + clampedOvershoot * EWMA_ALPHA);
            } else {
                ewmaOvershootNs = (long) (ewmaOvershootNs * (1.0 - EWMA_ALPHA));
            }
            spinMarginNs = Math.clamp(ewmaOvershootNs + SAFETY_BUFFER_NS, MIN_SPIN_MARGIN_NS, MAX_SPIN_MARGIN_NS);
            effectiveSpinMargin = Math.min(spinMarginNs, periodNs / 2L);
        }

        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        long finished = System.nanoTime();
        nextDeadline = (finished - deadline > periodNs) ? (finished + periodNs) : (deadline + periodNs);

        if (pollEvents != null) {
            pollEvents.run();
        }
    }
}
