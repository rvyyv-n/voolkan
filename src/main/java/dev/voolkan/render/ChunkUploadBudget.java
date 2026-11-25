/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.render;

import net.vulkanmod.Initializer;

/**
 * Per-frame chunk section upload budgeting helpers.
 * <p>
 * Configured values in {@code Config.chunkUploadBudget}:
 * <ul>
 *     <li>{@code 0} ({@link #AUTO}): adaptive CPU time + VRAM staging byte budget.</li>
 *     <li>{@code 1..63}: explicit maximum background section uploads per frame (UI exposes 4..60 in steps of 4).</li>
 *     <li>{@code >= 64} or {@code < 0} ({@link #UNLIMITED}): unlimited background uploads per frame.</li>
 * </ul>
 * Immediate/high-priority updates (player block edits and rebuilds of already-visible sections)
 * are drained in a separate priority queue before background streaming and are only bounded by
 * a generous staging-overflow safety ceiling on discrete GPUs.
 */
public final class ChunkUploadBudget {
    public static final int AUTO = 0;
    public static final int MIN_EXPLICIT = 4;
    public static final int MAX_EXPLICIT = 60;
    public static final int STEP = 4;
    public static final int UNLIMITED = 64;

    private static final long AUTO_TIME_BUDGET_NS = 2_500_000L;
    private static final long AUTO_BACKLOG_TIME_BUDGET_NS = 6_000_000L;
    private static final int AUTO_BACKLOG_THRESHOLD = 256;
    private static final int AUTO_MIN_SECTIONS = 3;

    private static final long AUTO_BYTE_BUDGET = 8L * 1024L * 1024L;
    private static final long AUTO_BACKLOG_BYTE_BUDGET = 16L * 1024L * 1024L;
    private static final long STAGING_SAFETY_BYTES = 24L * 1024L * 1024L;
    private static final long HIGH_PRIORITY_SAFETY_NS = 12_000_000L;

    private ChunkUploadBudget() {
    }

    public static int getConfigured() {
        return Initializer.CONFIG != null ? Initializer.CONFIG.chunkUploadBudget : AUTO;
    }

    public static boolean isAuto(int configured) {
        return configured == AUTO;
    }

    public static boolean isUnlimited(int configured) {
        return configured < 0 || configured >= UNLIMITED;
    }

    /**
     * Normalizes a persisted config value onto the RangeOption grid ({@code 0..64}, step {@code 4}).
     */
    public static int normalizeOptionValue(int configured) {
        if (configured < 0 || configured >= UNLIMITED) {
            return UNLIMITED;
        }
        if (configured == AUTO) {
            return AUTO;
        }
        int snapped = ((configured + (STEP / 2)) / STEP) * STEP;
        if (snapped < MIN_EXPLICIT) {
            return MIN_EXPLICIT;
        }
        if (snapped > MAX_EXPLICIT) {
            return MAX_EXPLICIT;
        }
        return snapped;
    }

    /**
     * High-priority uploads always drain first so player block edits never stall behind background loads.
     * On discrete GPUs backed by a 64 MiB staging ring, yield only if an extraordinary burst
     * (e.g. massive world edit) approaches the staging safety ceiling AND exceeds the safety time cap.
     */
    public static boolean shouldStopHighPriority(
            boolean skipBudget,
            boolean usesStaging,
            int uploadedCount,
            long uploadedBytes,
            long elapsedNs
    ) {
        if (skipBudget || uploadedCount <= 0) {
            return false;
        }
        return usesStaging && uploadedBytes >= STAGING_SAFETY_BYTES && elapsedNs >= HIGH_PRIORITY_SAFETY_NS;
    }

    /**
     * Returns true when background chunk section uploads should pause until the next frame.
     */
    public static boolean shouldStopBackground(
            int configured,
            boolean skipBudget,
            boolean usesStaging,
            int uploadedCount,
            long uploadedBytes,
            long elapsedNs,
            int remainingBacklog
    ) {
        if (skipBudget || uploadedCount <= 0) {
            return false;
        }

        if (isUnlimited(configured)) {
            return usesStaging && uploadedBytes >= STAGING_SAFETY_BYTES;
        }

        if (isAuto(configured)) {
            boolean heavyBacklog = remainingBacklog > AUTO_BACKLOG_THRESHOLD;
            long byteBudget = heavyBacklog ? AUTO_BACKLOG_BYTE_BUDGET : AUTO_BYTE_BUDGET;
            if (usesStaging && uploadedBytes >= byteBudget) {
                return true;
            }
            long timeBudgetNs = heavyBacklog ? AUTO_BACKLOG_TIME_BUDGET_NS : AUTO_TIME_BUDGET_NS;
            return uploadedCount >= AUTO_MIN_SECTIONS && elapsedNs >= timeBudgetNs;
        }

        if (uploadedCount >= configured) {
            return true;
        }

        return usesStaging && uploadedBytes >= STAGING_SAFETY_BYTES;
    }
}
