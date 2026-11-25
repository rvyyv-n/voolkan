package net.vulkanmod.vulkan.device;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * detect dynamic rendering support and keep the extension list; query Vulkan 1.2 driver properties, VK_KHR_portability_subset capabilities, and Apple/MoltenVK/tile-GPU predicates.
 */

import dev.voolkan.compat.PortabilityInfo;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

public class Device {
    final VkPhysicalDevice physicalDevice;
    final VkPhysicalDeviceProperties properties;

    private final int vendorId;
    public final String vendorIdString;
    public final String deviceName;
    public final String driverVersion;
    public final String vkVersion;

    private final int driverId;
    private final String driverName;
    private final String driverInfo;

    public final VkPhysicalDeviceFeatures2 availableFeatures;
    public final VkPhysicalDeviceVulkan11Features availableFeatures11;

//    public final VkPhysicalDeviceVulkan13Features availableFeatures13;
//    public final boolean vulkan13Support;

    private boolean drawIndirectSupported;
    private boolean dynamicRenderingSupported;
    private final boolean portabilitySubsetSupported;
    private final PortabilityInfo portabilityInfo;
    private Set<String> extensionNames = Set.of();

    public Device(VkPhysicalDevice device) {
        this.physicalDevice = device;

        properties = VkPhysicalDeviceProperties.malloc();
        vkGetPhysicalDeviceProperties(physicalDevice, properties);

        this.vendorId = properties.vendorID();
        this.vendorIdString = decodeVendor(properties.vendorID());
        this.deviceName = properties.deviceNameString();
        this.driverVersion = decodeDvrVersion(properties.driverVersion(), properties.vendorID());
        this.vkVersion = decDefVersion(properties.apiVersion());

        this.extensionNames = queryExtensionNames();

        int queriedDriverId = 0;
        String queriedDriverName = "";
        String queriedDriverInfo = "";
        int apiVersion = properties.apiVersion();
        boolean driverPropertiesSupported = VK_VERSION_MAJOR(apiVersion) > 1
                || (VK_VERSION_MAJOR(apiVersion) == 1 && VK_VERSION_MINOR(apiVersion) >= 2)
                || this.extensionNames.contains(KHRDriverProperties.VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME);
        if (driverPropertiesSupported) {
            try (MemoryStack stack = stackPush()) {
                VkPhysicalDeviceDriverProperties driverProps = VkPhysicalDeviceDriverProperties.calloc(stack);
                driverProps.sType$Default();

                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack);
                props2.sType$Default();
                props2.pNext(driverProps);

                vkGetPhysicalDeviceProperties2(this.physicalDevice, props2);
                queriedDriverId = driverProps.driverID();
                String name = driverProps.driverNameString();
                String info = driverProps.driverInfoString();
                queriedDriverName = name != null ? name : "";
                queriedDriverInfo = info != null ? info : "";
            }
        }
        this.driverId = queriedDriverId;
        this.driverName = queriedDriverName;
        this.driverInfo = queriedDriverInfo;

        this.availableFeatures = VkPhysicalDeviceFeatures2.calloc();
        this.availableFeatures.sType$Default();

        this.availableFeatures11 = VkPhysicalDeviceVulkan11Features.malloc();
        this.availableFeatures11.sType$Default();
        this.availableFeatures.pNext(this.availableFeatures11);

        //Vulkan 1.3
//        this.availableFeatures13 = VkPhysicalDeviceVulkan13Features.malloc();
//        this.availableFeatures13.sType$Default();
//        this.availableFeatures11.pNext(this.availableFeatures13.address());
//
//        this.vulkan13Support = this.device.getCapabilities().apiVersion == VK_API_VERSION_1_3;

        vkGetPhysicalDeviceFeatures2(this.physicalDevice, this.availableFeatures);

        if (this.availableFeatures.features().multiDrawIndirect() && this.availableFeatures11.shaderDrawParameters())
            this.drawIndirectSupported = true;

        // The feature struct may only be chained when the extension is exposed.
        if (this.extensionNames.contains(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)) {
            try (MemoryStack stack = stackPush()) {
                VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicRendering = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack);
                dynamicRendering.sType$Default();

                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack);
                features2.sType$Default();
                features2.pNext(dynamicRendering);

                vkGetPhysicalDeviceFeatures2(this.physicalDevice, features2);
                this.dynamicRenderingSupported = dynamicRendering.dynamicRendering();
            }
        }

        this.portabilitySubsetSupported = this.extensionNames.contains(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
        if (this.portabilitySubsetSupported) {
            try (MemoryStack stack = stackPush()) {
                VkPhysicalDevicePortabilitySubsetFeaturesKHR portabilityFeatures =
                        VkPhysicalDevicePortabilitySubsetFeaturesKHR.calloc(stack);
                portabilityFeatures.sType$Default();

                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack);
                features2.sType$Default();
                features2.pNext(portabilityFeatures);
                vkGetPhysicalDeviceFeatures2(this.physicalDevice, features2);

                VkPhysicalDevicePortabilitySubsetPropertiesKHR portabilityProps =
                        VkPhysicalDevicePortabilitySubsetPropertiesKHR.calloc(stack);
                portabilityProps.sType$Default();

                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack);
                props2.sType$Default();
                props2.pNext(portabilityProps);
                vkGetPhysicalDeviceProperties2(this.physicalDevice, props2);

                this.portabilityInfo = PortabilityInfo.from(portabilityFeatures, portabilityProps);
            }
        } else {
            this.portabilityInfo = null;
        }
    }

    private Set<String> queryExtensionNames() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer extensionCount = stack.ints(0);
            int res = vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCount, null);
            if (res < VK_SUCCESS) {
                return Set.of();
            }

            int count = extensionCount.get(0);
            if (count <= 0) {
                return Set.of();
            }

            try (VkExtensionProperties.Buffer available = VkExtensionProperties.malloc(count)) {
                res = vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCount, available);
                if (res < VK_SUCCESS) {
                    return Set.of();
                }
                int written = Math.min(Math.max(extensionCount.get(0), 0), count);
                available.limit(written);
                return available.stream().map(VkExtensionProperties::extensionNameString).collect(toSet());
            }
        }
    }

    public Set<String> getExtensionNames() {
        return extensionNames;
    }

    public boolean isDynamicRenderingSupported() {
        return dynamicRenderingSupported;
    }

    private static String decodeVendor(int i) {
        return switch (i) {
            case (0x10DE) -> "Nvidia";
            case (0x1022), (0x1002) -> "AMD"; // AMD has two deviceIds, apparently
            case (0x8086) -> "Intel";
            case (0x1010) -> "Imagination Technologies";
            case (0x13B5) -> "ARM";
            case (0x5143) -> "Qualcomm";
            case (0x106B) -> "Apple";
            case (0x14E4) -> "Broadcom";
            case (0x1AE0) -> "Google"; // Not sure about this, SwiftShader devices have this id
            case (0x10005) -> "Mesa"; // Honeykrisp on Apple devices has this vendorId for some reason
            default -> "undef"; //Either AMD or Unknown Driver version/vendor and.or Encoding Scheme
        };
    }

    // Should Work with AMD: https://gpuopen.com/learn/decoding-radeon-vulkan-versions/

    static String decDefVersion(int v) {
        return VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
    }

    // 0x10DE = Nvidia: https://pcisig.com/membership/member-companies?combine=Nvidia
    // https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VkPhysicalDeviceProperties.html
    // this should work with Nvidia + AMD but is not guaranteed to work with intel drivers in Windows and more obscure/Exotic Drivers/vendors
    private static String decodeDvrVersion(int v, int i) {
        return switch (i) {
            case (0x10DE) -> decodeNvidia(v); //Nvidia
            case (0x1022), (0x1002) -> decDefVersion(v); //AMD
            case (0x8086) -> decIntelVersion(v); //Intel
            default -> decDefVersion(v); //Either AMD or Unknown Driver Encoding Scheme
        };
    }

    // Source: https://www.intel.com/content/www/us/en/support/articles/000005654/graphics.html
    // Won't Work with older Drivers (15.45 And.or older)
    // May not work as this uses Guess work+Assumptions
    private static String decIntelVersion(int v) {
        return (glfwGetPlatform() == GLFW_PLATFORM_WIN32) ? (v >>> 14) + "." + (v & 0x3fff) : decDefVersion(v);
    }


    private static String decodeNvidia(int v) {
        return (v >>> 22 & 0x3FF) + "." + (v >>> 14 & 0xff) + "." + (v >>> 6 & 0xff) + "." + (v & 0xff);
    }

    static int getVkVer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var a = stack.mallocInt(1);
            vkEnumerateInstanceVersion(a);
            int vkVer1 = a.get(0);
            if (VK_VERSION_MINOR(vkVer1) < 2) {
                throw new RuntimeException("Vulkan 1.2 not supported: Only Has: %s".formatted(decDefVersion(vkVer1)));
            }
            return vkVer1;
        }
    }

    public Set<String> getUnsupportedExtensions(Set<String> requiredExtensions) {
        Set<String> unsupportedExtensions = new HashSet<>(requiredExtensions);
        unsupportedExtensions.removeAll(this.extensionNames);
        return unsupportedExtensions;
    }

    public boolean isDrawIndirectSupported() {
        return drawIndirectSupported;
    }

    // Added these to allow detecting GPU vendor, to allow handling vendor specific circumstances:
    // (e.g. such as in case we encounter a vendor specific driver bug)
    public boolean isAMD() {
        return vendorId == 0x1022 || vendorId == 0x1002;
    }

    public boolean isNvidia() {
        return vendorId == 0x10DE;
    }

    public boolean isIntel() {
        return vendorId == 0x8086;
    }

    public boolean isApple() {
        return vendorId == 0x106B;
    }

    public boolean isMoltenVK() {
        return driverId == VK12.VK_DRIVER_ID_MOLTENVK || driverName.contains("MoltenVK");
    }

    public boolean isTileGpu() {
        return isApple() || vendorId == 0x13B5 || vendorId == 0x5143 || vendorId == 0x1010
                || driverName.contains("Honeykrisp");
    }

    public boolean isPortabilitySubsetSupported() {
        return portabilitySubsetSupported;
    }

    public PortabilityInfo getPortabilityInfo() {
        return portabilityInfo;
    }

    public boolean hasLogicOp() {
        return availableFeatures.features().logicOp();
    }

    public int getDriverId() {
        return driverId;
    }

    public String getDriverName() {
        return driverName;
    }

    public String getDriverInfo() {
        return driverInfo;
    }

    public int getDeviceType() {
        return properties.deviceType();
    }
}
