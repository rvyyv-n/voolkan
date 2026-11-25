/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.IntBuffer;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Structured startup and swapchain capability diagnostics logged once at boot (and whenever the
 * swapchain configuration changes) to aid cross-platform triage.
 */
public final class StartupLog {
    private static final long BYTES_PER_MIB = 1024L * 1024L;

    private static boolean swapChainLogged = false;
    private static int lastSwapFormat;
    private static int lastSwapColorSpace;
    private static int lastSwapPresentMode;
    private static int lastSwapImageCount;
    private static int lastSwapWidth;
    private static int lastSwapHeight;

    private StartupLog() {}

    public static void reset() {
        swapChainLogged = false;
    }

    public static void logStartupCapabilities() {
        Device device = DeviceManager.device;
        if (device == null) {
            return;
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("Vulkan startup capabilities:\n");

        // GPU, vendor, device type, API and driver versions
        sb.append("  GPU: ").append(device.deviceName)
          .append(" (vendor=").append(device.vendorIdString)
          .append(", type=").append(deviceTypeName(device.getDeviceType()))
          .append(", Vulkan ").append(device.vkVersion)
          .append(", driver ").append(device.driverVersion);
        if (!device.getDriverName().isEmpty() || !device.getDriverInfo().isEmpty()) {
            sb.append(", ");
            if (!device.getDriverName().isEmpty()) {
                sb.append(device.getDriverName());
                if (!device.getDriverInfo().isEmpty()) {
                    sb.append(" / ").append(device.getDriverInfo());
                }
            } else {
                sb.append(device.getDriverInfo());
            }
        }
        sb.append(")\n");

        // Portability extensions
        sb.append("  Portability: VK_KHR_portability_enumeration=")
          .append(Vulkan.portabilityEnumerationSupported ? "enabled" : "not advertised")
          .append(", VK_KHR_portability_subset=")
          .append(DeviceManager.portabilitySubsetEnabled
                  ? "enabled"
                  : (device.isPortabilitySubsetSupported() ? "advertised (not enabled)" : "not advertised"))
          .append('\n');

        PortabilityInfo portability = device.getPortabilityInfo();
        if (DeviceManager.portabilitySubsetEnabled && portability != null) {
            sb.append("  Portability subset: triangleFans=").append(portability.triangleFans())
              .append(", constantAlphaColorBlendFactors=").append(portability.constantAlphaColorBlendFactors())
              .append(", imageViewFormatReinterpretation=").append(portability.imageViewFormatReinterpretation())
              .append(", imageViewFormatSwizzle=").append(portability.imageViewFormatSwizzle())
              .append(", mutableComparisonSamplers=").append(portability.mutableComparisonSamplers())
              .append(", samplerMipLodBias=").append(portability.samplerMipLodBias())
              .append(", pointPolygons=").append(portability.pointPolygons())
              .append(", vertexAttributeAccessBeyondStride=").append(portability.vertexAttributeAccessBeyondStride())
              .append(", minVertexInputBindingStrideAlignment=").append(portability.minVertexInputBindingStrideAlignment())
              .append('\n');
        }

        // Key device features and extensions
        VkPhysicalDeviceFeatures features = device.availableFeatures.features();
        boolean logicOp = device.hasLogicOp();
        boolean multiDrawIndirect = features.multiDrawIndirect();
        String dynamicRenderingStatus;
        if (Vulkan.DYNAMIC_RENDERING) {
            dynamicRenderingStatus = "enabled";
        } else if (device.isDynamicRenderingSupported()) {
            dynamicRenderingStatus = "supported (legacy path selected)";
        } else {
            dynamicRenderingStatus = "not supported";
        }

        sb.append("  Features: logicOp=").append(logicOp ? "true" : "false (fallback active)")
          .append(", wideLines=").append(features.wideLines())
          .append(", samplerAnisotropy=").append(features.samplerAnisotropy())
          .append(", multiDrawIndirect=").append(multiDrawIndirect);
        if (multiDrawIndirect && !device.isDrawIndirectSupported()) {
            sb.append(" (no shaderDrawParameters)");
        }
        sb.append(", VK_KHR_dynamic_rendering=").append(dynamicRenderingStatus)
          .append(", VK_EXT_memory_budget=").append(DeviceManager.memoryBudgetSupported ? "enabled" : "not supported")
          .append('\n');

        // Memory heaps and selected memory types
        VkPhysicalDeviceMemoryProperties memProps = DeviceManager.memoryProperties;
        if (memProps != null) {
            StringJoiner heapJoiner = new StringJoiner(", ");
            int heapCount = Math.min(memProps.memoryHeapCount(), memProps.memoryHeaps().capacity());
            for (int i = 0; i < heapCount; i++) {
                VkMemoryHeap heap = memProps.memoryHeaps(i);
                long mib = heap.size() / BYTES_PER_MIB;
                boolean deviceLocal = (heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
                heapJoiner.add("heap[" + i + "]=" + mib + " MiB" + (deviceLocal ? " DEVICE_LOCAL" : ""));
            }
            sb.append("  Memory heaps: ").append(heapJoiner).append('\n');
        }

        sb.append("  Memory types: unified=").append(MemoryTypes.isUnifiedMemory())
          .append(", GPU_MEM=").append(describeMemoryType(MemoryTypes.GPU_MEM))
          .append(", HOST_MEM=").append(describeMemoryType(MemoryTypes.HOST_MEM))
          .append(", BAR_MEM=").append(describeMemoryType(MemoryTypes.BAR_MEM))
          .append(", UNIFIED_MEM=").append(describeMemoryType(MemoryTypes.UNIFIED_MEM))
          .append('\n');

        // Chosen default depth format
        sb.append("  Default depth format: ").append(formatName(Vulkan.getDefaultDepthFormat())).append('\n');

        // Tile-GPU, unified-memory, and MoltenVK diagnostics
        sb.append("  Tile GPU opts: ").append(TileGpuOpts.isEnabled() ? "enabled" : "disabled").append('\n');
        sb.append("  Unified direct-write: ");
        if (MemoryTypes.unifiedDirectWriteActive) {
            sb.append("enabled (coherent=").append(MemoryTypes.isUnifiedDirectWriteCoherent()).append(')');
        } else {
            sb.append("disabled");
        }
        sb.append('\n');
        sb.append("  VK_EXT_layer_settings: ")
          .append(Vulkan.layerSettingsSupported ? "advertised" : "not advertised")
          .append('\n');
        sb.append("  MVK_CONFIG env: ").append(formatMvkConfigEnv());
        if (MacPerfOpts.isPlatformEligible()) {
            sb.append('\n').append("  macOS perf opts: ").append(MacPerfOpts.describeActive());
        }

        Initializer.LOGGER.info(sb.toString());
    }

    public static void logSwapChain(int format, int colorSpace, int width, int height,
                                    int imageCount, int requestedImages,
                                    int minImageCount, int maxImageCount,
                                    int presentMode, IntBuffer supportedPresentModes) {
        if (swapChainLogged
                && format == lastSwapFormat
                && colorSpace == lastSwapColorSpace
                && presentMode == lastSwapPresentMode
                && imageCount == lastSwapImageCount
                && width == lastSwapWidth
                && height == lastSwapHeight) {
            return;
        }

        swapChainLogged = true;
        lastSwapFormat = format;
        lastSwapColorSpace = colorSpace;
        lastSwapPresentMode = presentMode;
        lastSwapImageCount = imageCount;
        lastSwapWidth = width;
        lastSwapHeight = height;

        String range = minImageCount + ".." + (maxImageCount > 0 ? Integer.toString(maxImageCount) : "unlimited");
        String supportedModes = formatPresentModes(supportedPresentModes);

        Initializer.LOGGER.info(
                "Swapchain: {} ({}), {}x{}, {} images (requested {}, surface {}), present mode {} (supported: [{}])",
                formatName(format),
                colorSpaceName(colorSpace),
                width,
                height,
                imageCount,
                requestedImages,
                range,
                presentModeName(presentMode),
                supportedModes);
    }

    private static String formatMvkConfigEnv() {
        TreeMap<String, String> mvkVars = new TreeMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith("MVK_CONFIG_")) {
                mvkVars.put(entry.getKey(), entry.getValue());
            }
        }
        if (mvkVars.isEmpty()) {
            return "none";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, String> entry : mvkVars.entrySet()) {
            joiner.add(entry.getKey() + "=" + entry.getValue());
        }
        return joiner.toString();
    }

    private static String describeMemoryType(MemoryType type) {
        if (type == null) {
            return "none";
        }
        return type.getType()
                + "(heap=" + type.vkMemoryType.heapIndex()
                + ", mappable=" + type.mappable() + ")";
    }

    private static String formatPresentModes(IntBuffer modes) {
        if (modes == null || !modes.hasRemaining()) {
            return "none";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = modes.position(); i < modes.limit(); i++) {
            joiner.add(presentModeName(modes.get(i)));
        }
        return joiner.toString();
    }

    public static String deviceTypeName(int deviceType) {
        return switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "DISCRETE_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "VIRTUAL_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU";
            default -> "OTHER";
        };
    }

    public static String presentModeName(int mode) {
        return switch (mode) {
            case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "PRESENT_MODE_" + mode;
        };
    }

    public static String colorSpaceName(int colorSpace) {
        return switch (colorSpace) {
            case KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR -> "VK_COLOR_SPACE_SRGB_NONLINEAR_KHR";
            case EXTSwapchainColorspace.VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT -> "VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT";
            case EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT -> "VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT";
            case EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT -> "VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT";
            case EXTSwapchainColorspace.VK_COLOR_SPACE_BT2020_LINEAR_EXT -> "VK_COLOR_SPACE_BT2020_LINEAR_EXT";
            case EXTSwapchainColorspace.VK_COLOR_SPACE_HDR10_ST2084_EXT -> "VK_COLOR_SPACE_HDR10_ST2084_EXT";
            default -> "VK_COLOR_SPACE_" + colorSpace;
        };
    }

    public static String formatName(int format) {
        return switch (format) {
            case VK_FORMAT_B8G8R8A8_UNORM -> "VK_FORMAT_B8G8R8A8_UNORM";
            case VK_FORMAT_B8G8R8A8_SRGB -> "VK_FORMAT_B8G8R8A8_SRGB";
            case VK_FORMAT_R8G8B8A8_UNORM -> "VK_FORMAT_R8G8B8A8_UNORM";
            case VK_FORMAT_R8G8B8A8_SRGB -> "VK_FORMAT_R8G8B8A8_SRGB";
            case VK_FORMAT_A2B10G10R10_UNORM_PACK32 -> "VK_FORMAT_A2B10G10R10_UNORM_PACK32";
            case VK_FORMAT_A2R10G10B10_UNORM_PACK32 -> "VK_FORMAT_A2R10G10B10_UNORM_PACK32";
            case VK_FORMAT_R16G16B16A16_SFLOAT -> "VK_FORMAT_R16G16B16A16_SFLOAT";
            case VK_FORMAT_D32_SFLOAT -> "VK_FORMAT_D32_SFLOAT";
            case VK_FORMAT_D32_SFLOAT_S8_UINT -> "VK_FORMAT_D32_SFLOAT_S8_UINT";
            case VK_FORMAT_D24_UNORM_S8_UINT -> "VK_FORMAT_D24_UNORM_S8_UINT";
            case VK_FORMAT_X8_D24_UNORM_PACK32 -> "VK_FORMAT_X8_D24_UNORM_PACK32";
            case VK_FORMAT_D16_UNORM -> "VK_FORMAT_D16_UNORM";
            case VK_FORMAT_UNDEFINED -> "VK_FORMAT_UNDEFINED";
            default -> "VK_FORMAT_" + format;
        };
    }
}
