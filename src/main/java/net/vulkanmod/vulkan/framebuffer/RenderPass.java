package net.vulkanmod.vulkan.framebuffer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * legacy render pass: fix attachment indexing for colour-only/depth-only/both, clear value slots and mip-level render area; expose attachment formats; keep all mip levels in one layout for multi-level colour targets.
 */

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class RenderPass {
    Framebuffer framebuffer;
    long id;

    final int attachmentCount;

    /**
     * Legacy path, colour image with several mip levels: the render pass ends in COLOR_ATTACHMENT_OPTIMAL
     * (a render pass final layout only applies to the single level in the framebuffer view) and the whole
     * image is transitioned to the requested final layout after vkCmdEndRenderPass, like the dynamic path does.
     */
    final boolean explicitColorFinalLayout;
    AttachmentInfo colorAttachmentInfo;
    AttachmentInfo depthAttachmentInfo;

    public RenderPass(Framebuffer framebuffer, AttachmentInfo colorAttachmentInfo, AttachmentInfo depthAttachmentInfo) {
        this.framebuffer = framebuffer;
        this.colorAttachmentInfo = colorAttachmentInfo;
        this.depthAttachmentInfo = depthAttachmentInfo;

        int count = 0;
        if (colorAttachmentInfo != null)
            count++;
        if (depthAttachmentInfo != null)
            count++;

        this.attachmentCount = count;

        this.explicitColorFinalLayout = !Vulkan.DYNAMIC_RENDERING && colorAttachmentInfo != null
                                        && framebuffer.hasMultiLevelColorAttachment();

        if (!Vulkan.DYNAMIC_RENDERING) {
            createRenderPass();
        }

    }

    private void createRenderPass() {

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);
            VkAttachmentReference.Buffer attachmentRefs = VkAttachmentReference.calloc(attachmentCount, stack);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);

            int i = 0;

            final int colorFinalLayout = explicitColorFinalLayout
                    ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                    : (colorAttachmentInfo != null ? colorAttachmentInfo.finalLayout : VK_IMAGE_LAYOUT_UNDEFINED);

            // Color attachment
            if (colorAttachmentInfo != null) {
                VkAttachmentDescription colorAttachment = attachments.get(i);
                colorAttachment.format(colorAttachmentInfo.format)
                               .samples(VK_SAMPLE_COUNT_1_BIT)
                               .loadOp(colorAttachmentInfo.loadOp)
                               .storeOp(colorAttachmentInfo.storeOp)
                               .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                               .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                               .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                               .finalLayout(colorFinalLayout);

                VkAttachmentReference colorAttachmentRef = attachmentRefs.get(i)
                                                                         .attachment(i)
                                                                         .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

                subpass.colorAttachmentCount(1);
                subpass.pColorAttachments(VkAttachmentReference.calloc(1, stack).put(0, colorAttachmentRef));

                ++i;
            }

            // Depth-Stencil attachment
            if (depthAttachmentInfo != null) {
                VkAttachmentDescription depthAttachment = attachments.get(i);
                depthAttachment.format(depthAttachmentInfo.format)
                               .samples(VK_SAMPLE_COUNT_1_BIT)
                               .loadOp(depthAttachmentInfo.loadOp)
                               .storeOp(depthAttachmentInfo.storeOp)
                               .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                               .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                               .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                               .finalLayout(depthAttachmentInfo.finalLayout);

                VkAttachmentReference depthAttachmentRef = attachmentRefs.get(i)
                                                                         .attachment(i)
                                                                         .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

                subpass.pDepthStencilAttachment(depthAttachmentRef);
            }

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack);
            renderPassInfo.sType$Default()
                          .pAttachments(attachments)
                          .pSubpasses(subpass);

            // Layout transition subpass dependency (depth-only passes rely on the implicit external dependencies)
            int dependencyLayout = colorFinalLayout;
            switch (dependencyLayout) {
                case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> {
                    VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                    subpassDependencies.get(0)
                                       .srcSubpass(VK_SUBPASS_EXTERNAL)
                                       .dstSubpass(0)
                                       .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                                       .dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                                       .srcAccessMask(0)
                                       .dstAccessMask(0);

                    renderPassInfo.pDependencies(subpassDependencies);
                }
                case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                    VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                    subpassDependencies.get(0)
                                       .srcSubpass(0)
                                       .dstSubpass(VK_SUBPASS_EXTERNAL)
                                       .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                                       .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                                       .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                                       .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                    renderPassInfo.pDependencies(subpassDependencies);
                }
            }

            LongBuffer pRenderPass = stack.mallocLong(1);

            if (vkCreateRenderPass(Vulkan.getVkDevice(), renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create render pass");
            }

            id = pRenderPass.get(0);
        }
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, long framebufferId, MemoryStack stack) {

        if (colorAttachmentInfo != null
            && framebuffer.getColorAttachment().getCurrentLayout() != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        {
            framebuffer.getColorAttachment()
                       .transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        if (depthAttachmentInfo != null
            && framebuffer.getDepthAttachment().getCurrentLayout() != VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        {
            framebuffer.getDepthAttachment()
                       .transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }

        VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack);
        renderPassInfo.sType$Default();
        renderPassInfo.renderPass(this.id);
        renderPassInfo.framebuffer(framebufferId);

        // The framebuffer is built from the level image view, so the render area is the level extent
        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getLevelWidth(), framebuffer.getLevelHeight());
        renderPassInfo.renderArea(renderArea);

        // Clear values are indexed by attachment index: colour (if any) first, then depth
        VkClearValue.Buffer clearValues = VkClearValue.calloc(attachmentCount, stack);
        int clearIdx = 0;
        if (colorAttachmentInfo != null) {
            clearValues.get(clearIdx++).color().float32(VRenderSystem.clearColor);
        }
        if (depthAttachmentInfo != null) {
            clearValues.get(clearIdx).depthStencil().set(1.0f, 0);
        }

        renderPassInfo.pClearValues(clearValues);

        vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        Renderer.getInstance().setBoundRenderPass(this);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        if (Vulkan.DYNAMIC_RENDERING) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (colorAttachmentInfo != null
                    && framebuffer.getColorAttachment().getCurrentLayout() != this.colorAttachmentInfo.finalLayout)
                {
                    framebuffer.getColorAttachment()
                               .transitionImageLayout(stack, commandBuffer, this.colorAttachmentInfo.finalLayout);
                }
                if (depthAttachmentInfo != null
                    && framebuffer.getDepthAttachment().getCurrentLayout() != this.depthAttachmentInfo.finalLayout)
                {
                    framebuffer.getDepthAttachment()
                               .transitionImageLayout(stack, commandBuffer, this.depthAttachmentInfo.finalLayout);
                }
            }

        }
        else {
            vkCmdEndRenderPass(commandBuffer);

            if (colorAttachmentInfo != null) {
                VulkanImage colorImage = framebuffer.getColorAttachment();

                if (explicitColorFinalLayout) {
                    // Every level is in COLOR_ATTACHMENT_OPTIMAL here; move the whole image in one barrier
                    colorImage.setCurrentLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        colorImage.transitionImageLayout(stack, commandBuffer, colorAttachmentInfo.finalLayout);
                    }
                }
                else {
                    colorImage.setCurrentLayout(colorAttachmentInfo.finalLayout);
                }
            }

            if (depthAttachmentInfo != null)
                framebuffer.getDepthAttachment().setCurrentLayout(depthAttachmentInfo.finalLayout);
        }

        Renderer.getInstance().setBoundRenderPass(null);
    }

    public void beginDynamicRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (colorAttachmentInfo != null
            && framebuffer.getColorAttachment().getCurrentLayout() != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        {
            framebuffer.getColorAttachment()
                       .transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        if (depthAttachmentInfo != null
            && framebuffer.getDepthAttachment().getCurrentLayout() != VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        {
            framebuffer.getDepthAttachment()
                       .transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }

        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getWidth(), framebuffer.getHeight());

        VkClearValue.Buffer clearValues = VkClearValue.malloc(2, stack);
        clearValues.get(0).color().float32(VRenderSystem.clearColor);
        clearValues.get(1).depthStencil().set(1.0f, 0);

        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
        renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
        renderingInfo.renderArea(renderArea);
        renderingInfo.layerCount(1);

        // Color attachment
        if (colorAttachmentInfo != null) {
            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            colorAttachment.imageView(framebuffer.getColorAttachmentView());
            colorAttachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorAttachment.loadOp(colorAttachmentInfo.loadOp);
            colorAttachment.storeOp(colorAttachmentInfo.storeOp);
            colorAttachment.clearValue(clearValues.get(0));

            renderingInfo.pColorAttachments(colorAttachment);
        }

        // Depth attachment
        if (depthAttachmentInfo != null) {
            VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack);
            depthAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            depthAttachment.imageView(framebuffer.getDepthImageView());
            depthAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depthAttachment.loadOp(depthAttachmentInfo.loadOp);
            depthAttachment.storeOp(depthAttachmentInfo.storeOp);
            depthAttachment.clearValue(clearValues.get(1));

            renderingInfo.pDepthAttachment(depthAttachment);
        }

        KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public void setFramebuffer(Framebuffer framebuffer) {
        this.framebuffer = framebuffer;
    }

    public void cleanUp() {
        if (!Vulkan.DYNAMIC_RENDERING) {
            MemoryManager.getInstance()
                         .addFrameOp(
                                 () -> vkDestroyRenderPass(Vulkan.getVkDevice(), this.id, null));
        }
    }

    public long getId() {
        return id;
    }

    /**
     * Render pass compatibility also compares the subpass dependencies (the validation layers check
     * dependencyCount), which depend on the colour final layout. Returns 0 for none, 1 for the
     * present-source dependency, 2 for the shader-read dependency.
     */
    public int getDependencyKind() {
        if (colorAttachmentInfo == null) {
            return 0;
        }

        int layout = explicitColorFinalLayout ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : colorAttachmentInfo.finalLayout;
        return switch (layout) {
            case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> 1;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> 2;
            default -> 0;
        };
    }

    /** Colour attachment format, or 0 when the pass has no colour attachment. */
    public int getColorFormat() {
        return colorAttachmentInfo != null ? colorAttachmentInfo.format : 0;
    }

    /** Depth attachment format, or 0 when the pass has no depth attachment. */
    public int getDepthFormat() {
        return depthAttachmentInfo != null ? depthAttachmentInfo.format : 0;
    }

    public static class AttachmentInfo {
        final Type type;
        final int format;
        int finalLayout;
        int loadOp;
        int storeOp;

        public AttachmentInfo(Type type, int format) {
            this.type = type;
            this.format = format;
            this.finalLayout = type.defaultLayout;

            this.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            this.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        }

        public AttachmentInfo setOps(int loadOp, int storeOp) {
            this.loadOp = loadOp;
            this.storeOp = storeOp;

            return this;
        }

        public AttachmentInfo setLoadOp(int loadOp) {
            this.loadOp = loadOp;

            return this;
        }

        public AttachmentInfo setFinalLayout(int finalLayout) {
            this.finalLayout = finalLayout;

            return this;
        }

        public enum Type {
            COLOR(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL),
            DEPTH(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            final int defaultLayout;

            Type(int layout) {
                defaultLayout = layout;
            }
        }
    }

    public static Builder builder(Framebuffer framebuffer) {
        return new Builder(framebuffer);
    }

    public static class Builder {
        Framebuffer framebuffer;
        AttachmentInfo colorAttachmentInfo;
        AttachmentInfo depthAttachmentInfo;

        public Builder(Framebuffer framebuffer) {
            this.framebuffer = framebuffer;

            if (framebuffer.hasColorAttachment)
                colorAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.COLOR, framebuffer.format).setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            if (framebuffer.hasDepthAttachment)
                depthAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.DEPTH, framebuffer.depthFormat).setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        }

        public RenderPass build() {
            return new RenderPass(framebuffer, colorAttachmentInfo, depthAttachmentInfo);
        }

        public Builder setLoadOp(int loadOp) {
            if (colorAttachmentInfo != null) {
                colorAttachmentInfo.setLoadOp(loadOp);
            }
            if (depthAttachmentInfo != null) {
                depthAttachmentInfo.setLoadOp(loadOp);
            }


            return this;
        }

        public AttachmentInfo getColorAttachmentInfo() {
            return colorAttachmentInfo;
        }

        public AttachmentInfo getDepthAttachmentInfo() {
            return depthAttachmentInfo;
        }
    }
}
