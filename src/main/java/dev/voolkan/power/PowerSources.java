/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.power;

/**
 * Registry and cached accessor for the active {@link PowerSource}.
 * <p>
 * Defaults to {@link #UNSUPPORTED} on every OS. A platform-specific probe (such as a macOS
 * battery probe in a later pass) registers itself during client initialisation by calling
 * {@link #set(PowerSource)}.
 */
public final class PowerSources {

    /** Fallback power source used when no platform probe is registered. */
    public static final PowerSource UNSUPPORTED = new PowerSource() {
        @Override
        public boolean isSupported() {
            return false;
        }

        @Override
        public boolean isOnBattery() {
            return false;
        }
    };

    private static final long POLL_INTERVAL_NS = 5_000_000_000L; // 5 s

    private static volatile PowerSource active = UNSUPPORTED;
    private static volatile long lastPollNs = 0L;
    private static volatile boolean cachedOnBattery = false;

    private PowerSources() {
    }

    public static PowerSource get() {
        return active;
    }

    /**
     * Register the platform {@link PowerSource} (or {@code null} to reset to {@link #UNSUPPORTED}).
     * Invalidates the cached battery state so the next query polls the new source.
     */
    public static void set(PowerSource source) {
        active = (source != null) ? source : UNSUPPORTED;
        lastPollNs = 0L;
        cachedOnBattery = false;
    }

    /**
     * Returns whether the system is currently on battery power, polling the active
     * {@link PowerSource} at most once every 5 seconds. Always returns {@code false}
     * when {@link PowerSource#isSupported()} is {@code false}.
     */
    public static boolean isOnBattery() {
        PowerSource source = active;
        if (!source.isSupported()) {
            return false;
        }

        long now = System.nanoTime();
        long prev = lastPollNs;
        if (prev == 0L || now - prev >= POLL_INTERVAL_NS) {
            cachedOnBattery = source.isOnBattery();
            lastPollNs = now;
        }
        return cachedOnBattery;
    }
}
