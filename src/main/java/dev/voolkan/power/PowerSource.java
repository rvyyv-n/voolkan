/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.power;

/**
 * Platform probe for AC vs. battery power state.
 * <p>
 * Implementations are registered at startup via {@link PowerSources#set(PowerSource)}.
 * Callers should query {@link PowerSources#isOnBattery()} rather than calling
 * {@link #isOnBattery()} every frame so platform probes are rate-limited.
 */
public interface PowerSource {

    /** Whether this platform can report battery state. */
    boolean isSupported();

    /** Whether the machine is currently running on battery power. */
    boolean isOnBattery();

    /**
     * Whether this source already manages its own non-blocking asynchronous polling so
     * {@link PowerSources#isOnBattery()} should read it directly without adding an extra cache layer.
     */
    default boolean isAsync() {
        return false;
    }

    /** Releases any background resources held by this probe on shutdown. */
    default void close() {
    }
}
