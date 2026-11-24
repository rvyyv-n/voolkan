/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Cached per-format optimal-tiling feature queries for the active physical device.
 */
public final class FormatCaps {
    private static volatile VkPhysicalDevice cachedDevice;
    private static final ConcurrentHashMap<Integer, Integer> OPTIMAL_FEATURES = new ConcurrentHashMap<>();

    private FormatCaps() {
    }

    public static int optimalFeatures(int format) {
        VkPhysicalDevice physicalDevice = DeviceManager.physicalDevice;
        if (physicalDevice == null) {
            return 0;
        }
        if (cachedDevice != physicalDevice) {
            OPTIMAL_FEATURES.clear();
            cachedDevice = physicalDevice;
        }
        return OPTIMAL_FEATURES.computeIfAbsent(format, fmt -> queryOptimalFeatures(physicalDevice, fmt));
    }

    private static int queryOptimalFeatures(VkPhysicalDevice physicalDevice, int format) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);
            return props.optimalTilingFeatures();
        }
    }

    public static boolean supportsBlitSrc(int format) {
        return (optimalFeatures(format) & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0;
    }

    public static boolean supportsBlitDst(int format) {
        return (optimalFeatures(format) & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;
    }

    public static boolean supportsLinearFilter(int format) {
        return (optimalFeatures(format) & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
    }

    public static boolean canBlit(int srcFormat, int dstFormat) {
        return supportsBlitSrc(srcFormat) && supportsBlitDst(dstFormat);
    }

    /**
     * Picks a spec-valid VkFilter for a blit from {@code srcFormat}.
     * Depth/stencil formats always require {@code VK_FILTER_NEAREST}, and {@code VK_FILTER_LINEAR}
     * is only allowed when {@code VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT} is supported.
     */
    public static int chooseBlitFilter(int srcFormat, int requestedFilter) {
        if (VulkanImage.isDepthFormat(srcFormat)) {
            return VK_FILTER_NEAREST;
        }
        if (requestedFilter == VK_FILTER_LINEAR && supportsLinearFilter(srcFormat)) {
            return VK_FILTER_LINEAR;
        }
        return VK_FILTER_NEAREST;
    }

    public static void clear() {
        OPTIMAL_FEATURES.clear();
        cachedDevice = null;
    }
}
