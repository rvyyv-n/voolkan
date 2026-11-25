package net.vulkanmod.vulkan.framebuffer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * configurable swapchain image count; BGRA swapchain format preferred over RGBA; log readable swapchain configuration via StartupLog;
 * macOS/MoltenVK present-mode and image-count clamps and MemoryStack fix in getExtent.
 */

import dev.voolkan.compat.MacPerfOpts;
import dev.voolkan.compat.StartupLog;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Platform;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.shader.DescriptorSets;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.device.DeviceManager.vkDevice;
import static net.vulkanmod.vulkan.util.VUtil.UINT32_MAX;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class SwapChain extends Framebuffer {
    // Necessary until tearing-control-unstable-v1 is fully implemented on all GPU Drivers for Wayland
    // (As Immediate Mode (and by extension Screen tearing) doesn't exist on some Wayland installations currently)
    private static final int defUncappedMode = isMacOrMoltenVK()
            ? checkPresentMode(VK_PRESENT_MODE_IMMEDIATE_KHR)
            : checkPresentMode(VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR);

    private static boolean isMacOrMoltenVK() {
        return Platform.isMacOS() || (DeviceManager.device != null && DeviceManager.device.isMoltenVK());
    }

    private final Long2ReferenceOpenHashMap<long[]> FBO_map = new Long2ReferenceOpenHashMap<>();

    private long swapChainId = VK_NULL_HANDLE;
    private List<VulkanImage> swapChainImages;
    private VulkanImage[] depthAttachments;
    private VkExtent2D extent2D;
    public boolean isBGRAformat;
    private boolean vsync = false;
    private boolean hasImages = false;
    private boolean acquired = false;

    public SwapChain() {
        this.attachmentCount = 2;
        this.depthFormat = Vulkan.getDefaultDepthFormat();

        this.hasColorAttachment = true;
        this.hasDepthAttachment = true;

        recreate();
    }

    public void recreate() {
        freeDepthResources();

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        createSwapChain();
    }

    private void freeDepthResources() {
        if (this.depthAttachments != null) {
            for (VulkanImage depth : this.depthAttachments) {
                if (depth != null) {
                    depth.free();
                }
            }
            this.depthAttachments = null;
            this.depthAttachment = null;
        } else if (this.depthAttachment != null) {
            this.depthAttachment.free();
            this.depthAttachment = null;
        }
    }

    /** Rebuilds per-frame depth attachments if the frame queue size changed during swapchain recreation. */
    public void syncDepthAttachments(int frameSlots) {
        if (!this.hasImages || !MacPerfOpts.perFrameDepth()) {
            return;
        }
        int targetSlots = Math.max(1, frameSlots);
        int currentSlots = this.depthAttachments != null ? this.depthAttachments.length : (this.depthAttachment != null ? 1 : 0);
        if (currentSlots == targetSlots) {
            return;
        }
        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }
        freeDepthResources();
        createDepthResources(targetSlots);
    }

    private void createSwapChain() {
        try (MemoryStack stack = stackPush()) {
            VkDevice device = Vulkan.getVkDevice();
            DeviceManager.SurfaceProperties surfaceProperties = DeviceManager.querySurfaceProperties(device.getPhysicalDevice(), stack);

            VkSurfaceFormatKHR surfaceFormat = getFormat(surfaceProperties.formats);
            int presentMode = getPresentMode(surfaceProperties.presentModes);
            VkExtent2D extent = getExtent(surfaceProperties.capabilities);

            if (extent.width() == 0 && extent.height() == 0) {
                if (this.swapChainId != VK_NULL_HANDLE) {
                    this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                    vkDestroySwapchainKHR(device, this.swapChainId, null);
                    this.swapChainId = VK_NULL_HANDLE;
                    DescriptorSets.bumpResourceEpoch();
                }

                this.width = 0;
                this.height = 0;
                this.hasImages = false;
                this.acquired = false;
                return;
            }

            int minImages = surfaceProperties.capabilities.minImageCount();
            int maxImages = surfaceProperties.capabilities.maxImageCount();
            int configuredImages = Initializer.CONFIG.swapChainImages;
            int requestedImages = configuredImages > 0
                    ? Math.max(configuredImages, minImages)
                    : minImages + 1;
            if (isMacOrMoltenVK()) {
                int lo = Math.max(2, minImages);
                int hi = maxImages > 0 ? Math.min(3, maxImages) : 3;
                if (hi < lo) {
                    if (maxImages > 0 && maxImages < lo) {
                        lo = minImages;
                        hi = maxImages;
                    } else {
                        hi = lo;
                    }
                }
                requestedImages = Math.max(lo, Math.min(requestedImages, hi));
            } else if (maxImages > 0 && requestedImages > maxImages) {
                requestedImages = maxImages;
            }

            IntBuffer imageCount = stack.ints(requestedImages);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(Vulkan.getSurface());

            // Image settings
            this.format = surfaceFormat.format();
            this.extent2D = VkExtent2D.create().set(extent);

            createInfo.minImageCount(requestedImages);
            createInfo.imageFormat(this.format);
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);

            Queue.QueueFamilyIndices indices = Queue.getQueueFamilies();

            if (indices.graphicsFamily != indices.presentFamily) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(surfaceProperties.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);

            if (DeviceManager.fullScreenExclusiveControl) {
                VkSurfaceFullScreenExclusiveInfoEXT fullScreenExclusiveInfo = VkSurfaceFullScreenExclusiveInfoEXT.calloc(stack);
                fullScreenExclusiveInfo.sType(EXTFullScreenExclusive.VK_STRUCTURE_TYPE_SURFACE_FULL_SCREEN_EXCLUSIVE_INFO_EXT);
                fullScreenExclusiveInfo.fullScreenExclusive(EXTFullScreenExclusive.VK_FULL_SCREEN_EXCLUSIVE_DISALLOWED_EXT);
                createInfo.pNext(fullScreenExclusiveInfo.address());
            }

            if (this.swapChainId != VK_NULL_HANDLE) {
                this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                vkDestroySwapchainKHR(device, this.swapChainId, null);
                DescriptorSets.bumpResourceEpoch();
            }

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            int result = vkCreateSwapchainKHR(device, createInfo, null, pSwapChain);
            Vulkan.checkResult(result, "Failed to create swap chain");

            this.swapChainId = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, pSwapchainImages);

            StartupLog.logSwapChain(
                    this.format,
                    surfaceFormat.colorSpace(),
                    extent.width(),
                    extent.height(),
                    imageCount.get(0),
                    requestedImages,
                    surfaceProperties.capabilities.minImageCount(),
                    surfaceProperties.capabilities.maxImageCount(),
                    presentMode,
                    surfaceProperties.presentModes);

            this.swapChainImages = new ArrayList<>(imageCount.get(0));
            this.hasImages = true;

            this.width = extent2D.width();
            this.height = extent2D.height();

            for (int i = 0; i < pSwapchainImages.capacity(); i++) {
                long imageId = pSwapchainImages.get(i);
                long imageView = VulkanImage.createImageView(imageId, this.format, VK_IMAGE_ASPECT_COLOR_BIT, 1, 1);

                VulkanImage image = new VulkanImage("Swapchain", imageId, this.format, 1, this.width, this.height, 4, 0, imageView);
                long samplerId = SamplerManager.getSampler(true, true, 0);
                image.setSampler(samplerId);
                this.swapChainImages.add(image);
            }
        }

        createDepthResources();
    }

    private long[] createFramebuffers(RenderPass renderPass) {

        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean hasDepth = renderPass.getDepthFormat() != 0;
            int depthCount = (hasDepth && this.depthAttachments != null && this.depthAttachments.length > 1)
                    ? this.depthAttachments.length
                    : 1;
            long[] framebuffers = new long[this.swapChainImages.size() * depthCount];

            for (int i = 0; i < this.swapChainImages.size(); ++i) {
                for (int d = 0; d < depthCount; ++d) {
                    LongBuffer attachments;
                    if (hasDepth) {
                        VulkanImage depthImg = (this.depthAttachments != null && d < this.depthAttachments.length)
                                ? this.depthAttachments[d]
                                : this.depthAttachment;
                        attachments = stack.longs(this.swapChainImages.get(i).getImageView(), depthImg.getImageView());
                    } else {
                        attachments = stack.longs(this.swapChainImages.get(i).getImageView());
                    }

                    LongBuffer pFramebuffer = stack.mallocLong(1);

                    VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
                    framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                    framebufferInfo.renderPass(renderPass.getId());
                    framebufferInfo.width(this.width);
                    framebufferInfo.height(this.height);
                    framebufferInfo.layers(1);
                    framebufferInfo.pAttachments(attachments);

                    if (vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                        throw new RuntimeException("Failed to create framebuffer");
                    }

                    framebuffers[i * depthCount + d] = pFramebuffer.get(0);
                }
            }

            return framebuffers;
        }
    }

    private void createDepthResources() {
        int frameSlots = Renderer.getInstance() != null ? Math.max(1, Renderer.getFramesNum()) : 1;
        createDepthResources(frameSlots);
    }

    private void createDepthResources(int frameSlots) {
        if (MacPerfOpts.perFrameDepth() && frameSlots > 1) {
            this.depthAttachments = new VulkanImage[frameSlots];
            for (int i = 0; i < frameSlots; i++) {
                this.depthAttachments[i] = VulkanImage.createDepthImage(depthFormat, this.width, this.height,
                                                                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                                                                        false, false);
            }
            this.depthAttachment = this.depthAttachments[0];
        } else {
            this.depthAttachments = null;
            this.depthAttachment = VulkanImage.createDepthImage(depthFormat, this.width, this.height,
                                                                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                                                                false, false);
        }
    }

    @Override
    protected long getFramebufferId(RenderPass renderPass) {
        long[] framebuffers = this.FBO_map.computeIfAbsent(renderPass.id, renderPass1 -> createFramebuffers(renderPass));
        int depthCount = (renderPass.getDepthFormat() != 0 && this.depthAttachments != null && this.depthAttachments.length > 1)
                ? this.depthAttachments.length
                : 1;
        int depthIdx = depthCount > 1 ? (Renderer.getCurrentFrame() % depthCount) : 0;
        return framebuffers[Renderer.getCurrentImage() * depthCount + depthIdx];
    }

    public void cleanUp() {
        VkDevice device = Vulkan.getVkDevice();

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        vkDestroySwapchainKHR(device, this.swapChainId, null);
        this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
        DescriptorSets.bumpResourceEpoch();

        freeDepthResources();
    }

    private VkSurfaceFormatKHR getFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().toList();

        // BGRA first: it is what desktop drivers present natively, and Mesa lavapipe on Windows shows an
        // R8G8B8A8 swapchain with red and blue swapped.
        VkSurfaceFormatKHR format = null;

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
                break;
            }
        }

        if (format == null) {
            for (VkSurfaceFormatKHR availableFormat : list) {
                if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    format = availableFormat;
                    break;
                }
            }
        }

        if (format == null)
            format = list.get(0);

        if (format.format() == VK_FORMAT_B8G8R8A8_UNORM)
            isBGRAformat = true;
        return format;
    }

    private int getPresentMode(IntBuffer availablePresentModes) {
        int requestedMode = vsync ? VK_PRESENT_MODE_FIFO_KHR : defUncappedMode;

        if (isMacOrMoltenVK() && requestedMode == VK_PRESENT_MODE_MAILBOX_KHR) {
            requestedMode = VK_PRESENT_MODE_FIFO_KHR;
        }

        // FIFO mode is the only mode that has to be supported
        if (requestedMode == VK_PRESENT_MODE_FIFO_KHR)
            return VK_PRESENT_MODE_FIFO_KHR;

        for (int i = 0; i < availablePresentModes.capacity(); i++) {
            if (availablePresentModes.get(i) == requestedMode) {
                return requestedMode;
            }
        }

        Initializer.LOGGER.warn("Requested mode not supported: " + getDisplayModeString(requestedMode) + ": using FIFO present mode");
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private String getDisplayModeString(int requestedMode) {
        return switch (requestedMode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "Immediate";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "Mailbox (FastSync)";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO Relaxed (Adaptive VSync)";
            default -> "FIFO (VSync)";
        };
    }

    private static VkExtent2D getExtent(VkSurfaceCapabilitiesKHR capabilities) {

        if (capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        // Fallback
        IntBuffer width = stackGet().ints(0);
        IntBuffer height = stackGet().ints(0);

        glfwGetFramebufferSize(window, width, height);

        VkExtent2D actualExtent = VkExtent2D.malloc(stackGet()).set(width.get(0), height.get(0));

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(MathUtil.clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(MathUtil.clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }

    private static int checkPresentMode(int... requestedModes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var a = DeviceManager.querySurfaceProperties(vkDevice.getPhysicalDevice(), stack).presentModes;
            for (int dMode : requestedModes) {
                for (int i = 0; i < a.capacity(); i++) {
                    if (a.get(i) == dMode) {
                        return dMode;
                    }
                }
            }
            return VK_PRESENT_MODE_FIFO_KHR; // If None of the request modes exist/are supported by Driver
        }
    }

    public long getId() {
        return this.swapChainId;
    }

    public List<VulkanImage> getImages() {
        return this.swapChainImages;
    }

    public long getImageId(int i) {
        return this.swapChainImages.get(i).getId();
    }

    public VkExtent2D getExtent() {
        return this.extent2D;
    }

    public VulkanImage getColorAttachment() {
        return this.swapChainImages.get(Renderer.getCurrentImage());
    }

    public long getColorAttachmentView() {
        return this.getColorAttachment().getImageView();
    }

    @Override
    public VulkanImage getDepthAttachment() {
        if (this.depthAttachments != null && this.depthAttachments.length > 0) {
            return this.depthAttachments[Renderer.getCurrentFrame() % this.depthAttachments.length];
        }
        return this.depthAttachment;
    }

    @Override
    public long getDepthImageView() {
        return this.getDepthAttachment().getImageView();
    }

    public VulkanImage[] getDepthAttachments() {
        return this.depthAttachments;
    }

    public boolean hasImages() {
        return this.hasImages;
    }

    public boolean isAcquired() {
        return this.acquired;
    }

    public void setAcquired(boolean acquired) {
        this.acquired = acquired;
    }

    public boolean isVsync() {
        return this.vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public int getImagesNum() {
        return this.swapChainImages.size();
    }
}
