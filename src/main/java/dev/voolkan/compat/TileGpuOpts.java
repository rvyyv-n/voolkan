/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import net.vulkanmod.vulkan.device.DeviceManager;

/**
 * Runtime gate for tile-based GPU render-pass optimizations (lazy swapchain pass start,
 * deferred initial frame clear, and native {@code LOAD_OP_CLEAR} folding).
 * Always disabled on non-tile GPUs and can be opted out via {@code -Dvoolkan.disableTileOpts=true}.
 */
public final class TileGpuOpts {
    private static final boolean DISABLED = Boolean.getBoolean("voolkan.disableTileOpts");

    private TileGpuOpts() {}

    public static boolean isEnabled() {
        return !DISABLED && DeviceManager.device != null && DeviceManager.device.isTileGpu();
    }
}
