/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import net.vulkanmod.config.Platform;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.StringJoiner;

/**
 * Runtime feature gates and JVM opt-out flags for macOS / Apple Silicon / MoltenVK performance
 * optimizations. Every gate evaluates to {@code false} on non-Apple, non-MoltenVK platforms
 * (including Windows / AMD Radeon R7 350) so desktop Windows execution paths remain identical.
 */
public final class MacPerfOpts {
    private static final boolean DISABLE_UNIFIED_GPU_BUFFERS = Boolean.getBoolean("voolkan.disableUnifiedGpuBuffers");
    private static final boolean DISABLE_INLINE_IMAGE_UPLOADS = Boolean.getBoolean("voolkan.disableInlineImageUploads");
    private static final boolean DISABLE_PER_FRAME_DEPTH = Boolean.getBoolean("voolkan.disablePerFrameDepth");
    private static final boolean DISABLE_LATE_ACQUIRE = Boolean.getBoolean("voolkan.disableLateAcquire");
    private static final boolean DISABLE_DESCRIPTOR_CACHE_PERSIST = Boolean.getBoolean("voolkan.disableDescriptorCachePersist");
    private static final boolean DISABLE_DRAW_COALESCING = Boolean.getBoolean("voolkan.disableDrawCoalescing");
    private static final boolean DISABLE_TBDR_PASS_OPTS = Boolean.getBoolean("voolkan.disableTbdrPassOpts");
    private static final boolean DISABLE_MENU_BLUR_OPTS = Boolean.getBoolean("voolkan.disableMenuBlurOpts");
    private static final boolean DISABLE_SHADER_OPTS_MAC = Boolean.getBoolean("voolkan.disableShaderOptsMac");

    private MacPerfOpts() {}

    /**
     * Returns {@code true} only on macOS or when the selected Vulkan device is an Apple GPU or
     * MoltenVK driver (e.g. under the Apple Vulkan profile harness). Always {@code false} on
     * native Windows/Linux desktop GPUs.
     */
    public static boolean isPlatformEligible() {
        if (Platform.isMacOS()) {
            return true;
        }
        Device device = DeviceManager.device;
        return device != null && (device.isApple() || device.isMoltenVK());
    }

    /** Phase 1A: unified-memory direct writes and per-frame uniform rings for VkGpuBuffer + compact staging. */
    public static boolean unifiedGpuBuffers() {
        return !DISABLE_UNIFIED_GPU_BUFFERS && isPlatformEligible();
    }

    /** Phase 1B: record pre-pass texture uploads inline into the main frame command buffer. */
    public static boolean inlineImageUploads() {
        return !DISABLE_INLINE_IMAGE_UPLOADS && isPlatformEligible();
    }

    /** Phase 1C: per-frame-slot swapchain depth attachments for unserialized CPU/GPU frame overlap. */
    public static boolean perFrameDepth() {
        return !DISABLE_PER_FRAME_DEPTH && isPlatformEligible();
    }

    /** Phase 1D: defer vkAcquireNextImageKHR out of runTick HEAD until swapchain access. */
    public static boolean lateAcquire() {
        return !DISABLE_LATE_ACQUIRE && TileGpuOpts.isEnabled() && isPlatformEligible();
    }

    /** Phase 2A: persist per-frame-slot descriptor set caches across frames with epoch invalidation. */
    public static boolean persistentDescriptorCache() {
        return !DISABLE_DESCRIPTOR_CACHE_PERSIST && isPlatformEligible();
    }

    /** Phase 2B: coalesce empty culled terrain facings, trim redundant binds/allocs, and size DynamicUniforms. */
    public static boolean drawCoalescing() {
        return !DISABLE_DRAW_COALESCING && isPlatformEligible();
    }

    /** Phase 3: narrow TBDR barriers, mip-exact dynamic rendering, offscreen FBO batching, and WorldScale fixes. */
    public static boolean tbdrPassOpts() {
        return !DISABLE_TBDR_PASS_OPTS && TileGpuOpts.isEnabled() && isPlatformEligible();
    }

    /** Phase 4: depth-less / LOAD_OP_DONT_CARE menu blur passes, blur PSO warm-up, and stale ScreenM guard. */
    public static boolean menuBlurOpts() {
        return !DISABLE_MENU_BLUR_OPTS && TileGpuOpts.isEnabled() && isPlatformEligible();
    }

    /** Phase 5: macOS shaderc Vulkan 1.2 target-env fix (evaluated during early shader compiler init). */
    public static boolean shaderOptsMac() {
        return !DISABLE_SHADER_OPTS_MAC && Platform.isMacOS();
    }

    /** Compact diagnostic summary for {@link StartupLog}. */
    public static String describeActive() {
        if (!isPlatformEligible()) {
            return "disabled (non-macOS)";
        }
        StringJoiner joiner = new StringJoiner(", ");
        joiner.add("unifiedGpuBuf=" + unifiedGpuBuffers());
        joiner.add("inlineImgUp=" + inlineImageUploads());
        joiner.add("perFrameDepth=" + perFrameDepth());
        joiner.add("lateAcquire=" + lateAcquire());
        joiner.add("persistDesc=" + persistentDescriptorCache());
        joiner.add("drawCoalesce=" + drawCoalescing());
        joiner.add("tbdrPass=" + tbdrPassOpts());
        joiner.add("menuBlur=" + menuBlurOpts());
        joiner.add("shaderTarget=" + shaderOptsMac());
        return joiner.toString();
    }
}
