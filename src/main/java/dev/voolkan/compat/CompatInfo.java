/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;

import static org.lwjgl.vulkan.VK10.*;

/** Text for the read-only Compatibility block on the Video page. */
public final class CompatInfo {
    private CompatInfo() {}

    public static String renderPath() {
        return Vulkan.DYNAMIC_RENDERING ? "Dynamic" : "Legacy";
    }

    public static String gpu() {
        return DeviceManager.device.deviceName;
    }

    public static String apiVersion() {
        return DeviceManager.device.vkVersion;
    }

    public static String dynamicRendering() {
        return DeviceManager.device.isDynamicRenderingSupported() ? "Yes" : "No";
    }

    public static String swapchainFormat() {
        var swapChain = Renderer.getInstance() != null ? Renderer.getInstance().getSwapChain() : null;
        return swapChain != null ? formatName(swapChain.getFormat()) : "N/A";
    }

    static String formatName(int format) {
        return switch (format) {
            case VK_FORMAT_B8G8R8A8_UNORM -> "B8G8R8A8_UNORM";
            case VK_FORMAT_R8G8B8A8_UNORM -> "R8G8B8A8_UNORM";
            case VK_FORMAT_B8G8R8A8_SRGB -> "B8G8R8A8_SRGB";
            case VK_FORMAT_R8G8B8A8_SRGB -> "R8G8B8A8_SRGB";
            default -> "format " + format;
        };
    }
}
