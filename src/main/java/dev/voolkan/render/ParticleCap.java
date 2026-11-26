/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.render;

import net.vulkanmod.Initializer;

/**
 * Per-frame quad particle rendering cap.
 * <p>
 * Default is {@link #UNLIMITED} ({@code 0}), which leaves vanilla particle rendering untouched.
 * When set to a positive limit, {@code QuadParticleGroupM} caps extracted particles per frame
 * while keeping the most recently spawned visible particles in draw order.
 */
public final class ParticleCap {
    public static final int UNLIMITED = 0;

    public static final Integer[] OPTION_VALUES = new Integer[]{
            100, 250, 500, 1000, 2000, 4000, 8000, UNLIMITED
    };

    private static boolean inExtract = false;
    private static int activeLimit = UNLIMITED;
    private static int remaining = Integer.MAX_VALUE;

    private ParticleCap() {
    }

    /** Called at the start of {@code ParticleEngine.extract} on the render thread. */
    public static void beginFrame() {
        int limit = getConfiguredLimit();
        activeLimit = limit;
        remaining = limit > 0 ? limit : Integer.MAX_VALUE;
        inExtract = true;
    }

    /** Called on exit from {@code ParticleEngine.extract} so out-of-band callers are unaffected. */
    public static void endFrame() {
        inExtract = false;
        remaining = activeLimit > 0 ? activeLimit : Integer.MAX_VALUE;
    }

    public static int getConfiguredLimit() {
        return Initializer.CONFIG != null ? Math.max(0, Initializer.CONFIG.maxParticles) : UNLIMITED;
    }

    public static boolean isActive() {
        return inExtract && activeLimit > 0;
    }

    public static int getActiveLimit() {
        return activeLimit;
    }

    public static int getRemaining() {
        return remaining;
    }

    public static void consume(int count) {
        if (activeLimit > 0 && count > 0) {
            remaining = Math.max(0, remaining - count);
        }
    }

    /**
     * Maps any persisted integer value to the closest offered option value for the UI widget.
     */
    public static int normalizeOptionValue(int configured) {
        if (configured <= 0) {
            return UNLIMITED;
        }
        int best = OPTION_VALUES[0];
        int bestDiff = Integer.MAX_VALUE;
        for (int v : OPTION_VALUES) {
            if (v <= 0) {
                continue;
            }
            int diff = Math.abs(v - configured);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = v;
            }
        }
        return best;
    }
}
