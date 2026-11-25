/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import org.lwjgl.vulkan.VkPhysicalDevicePortabilitySubsetFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDevicePortabilitySubsetPropertiesKHR;

/**
 * Immutable snapshot of {@code VK_KHR_portability_subset} features and properties queried from a
 * physical device (such as MoltenVK on macOS).
 */
public record PortabilityInfo(
        boolean constantAlphaColorBlendFactors,
        boolean events,
        boolean imageViewFormatReinterpretation,
        boolean imageViewFormatSwizzle,
        boolean imageView2DOn3DImage,
        boolean multisampleArrayImage,
        boolean mutableComparisonSamplers,
        boolean pointPolygons,
        boolean samplerMipLodBias,
        boolean separateStencilMaskRef,
        boolean shaderSampleRateInterpolationFunctions,
        boolean tessellationIsolines,
        boolean tessellationPointMode,
        boolean triangleFans,
        boolean vertexAttributeAccessBeyondStride,
        int minVertexInputBindingStrideAlignment
) {
    public static PortabilityInfo from(VkPhysicalDevicePortabilitySubsetFeaturesKHR features,
                                       VkPhysicalDevicePortabilitySubsetPropertiesKHR properties) {
        return new PortabilityInfo(
                features.constantAlphaColorBlendFactors(),
                features.events(),
                features.imageViewFormatReinterpretation(),
                features.imageViewFormatSwizzle(),
                features.imageView2DOn3DImage(),
                features.multisampleArrayImage(),
                features.mutableComparisonSamplers(),
                features.pointPolygons(),
                features.samplerMipLodBias(),
                features.separateStencilMaskRef(),
                features.shaderSampleRateInterpolationFunctions(),
                features.tessellationIsolines(),
                features.tessellationPointMode(),
                features.triangleFans(),
                features.vertexAttributeAccessBeyondStride(),
                properties.minVertexInputBindingStrideAlignment()
        );
    }
}
