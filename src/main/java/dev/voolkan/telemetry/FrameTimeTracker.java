/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.telemetry;

import java.util.Arrays;
import java.util.Locale;

/**
 * Zero-allocation primitive ring buffer tracking recent per-frame durations to compute rolling
 * average FPS / ms and 1% low FPS / p99 ms for the F3 overlay.
 * <p>
 * All methods are invoked on the render thread.
 */
public final class FrameTimeTracker {
    private static final int CAPACITY = 512;
    private static final int MASK = CAPACITY - 1;
    private static final long MAX_VALID_SAMPLE_NS = 2_000_000_000L;
    private static final long REFRESH_INTERVAL_NS = 500_000_000L;
    private static final int MIN_SAMPLES_FOR_STATS = 4;

    private static final long[] SAMPLES_NS = new long[CAPACITY];
    private static final long[] SORT_SCRATCH = new long[CAPACITY];

    private static int writeIndex = 0;
    private static int sampleCount = 0;
    private static long lastRefreshNs = 0L;
    private static String cachedLine = "";

    private FrameTimeTracker() {
    }

    /** Records one completed frame duration in nanoseconds (zero allocations). */
    public static void recordFrame(long durationNs) {
        if (durationNs <= 0L || durationNs > MAX_VALID_SAMPLE_NS) {
            return;
        }
        SAMPLES_NS[writeIndex] = durationNs;
        writeIndex = (writeIndex + 1) & MASK;
        if (sampleCount < CAPACITY) {
            sampleCount++;
        }
    }

    /** Clears collected frame samples (e.g. on world load/disconnect). */
    public static void reset() {
        writeIndex = 0;
        sampleCount = 0;
        lastRefreshNs = 0L;
        cachedLine = "";
    }

    /**
     * Returns the cached F3 summary line, recomputing at most twice per second while F3 is open.
     * Returns an empty string until enough samples have been collected.
     */
    public static String getFormattedLine() {
        int count = sampleCount;
        if (count < MIN_SAMPLES_FOR_STATS) {
            return "";
        }

        long now = System.nanoTime();
        if (!cachedLine.isEmpty() && now - lastRefreshNs < REFRESH_INTERVAL_NS) {
            return cachedLine;
        }

        long sumNs = 0L;
        for (int i = 0; i < count; i++) {
            long s = SAMPLES_NS[i];
            SORT_SCRATCH[i] = s;
            sumNs += s;
        }
        Arrays.sort(SORT_SCRATCH, 0, count);

        double avgNs = (double) sumNs / (double) count;
        double avgMs = avgNs * 1.0e-6;
        double avgFps = avgNs > 0.0 ? 1.0e9 / avgNs : 0.0;

        int p99Idx = Math.min(count - 1, Math.max(0, (int) Math.ceil(count * 0.99) - 1));
        double p99Ms = SORT_SCRATCH[p99Idx] * 1.0e-6;

        // 1% low FPS = reciprocal of the mean duration of the slowest 1% of frames (matches PresentMon/RTSS).
        int worstCount = Math.max(1, (count + 99) / 100);
        long worstSumNs = 0L;
        for (int i = count - worstCount; i < count; i++) {
            worstSumNs += SORT_SCRATCH[i];
        }
        double worstAvgNs = (double) worstSumNs / (double) worstCount;
        double onePctLowFps = worstAvgNs > 0.0 ? 1.0e9 / worstAvgNs : 0.0;

        cachedLine = String.format(
                Locale.ROOT,
                "Frame: %.0f fps (%.1f ms) | 1%% low: %.0f fps (p99 %.1f ms)",
                avgFps, avgMs, onePctLowFps, p99Ms
        );
        lastRefreshNs = now;
        return cachedLine;
    }
}
