package net.vulkanmod.vulkan.framebuffer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * legacy framebuffers use the level image view and level extent, are cached per (pass, level, views) and support depth-only.
 */

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.commons.lang3.Validate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static net.vulkanmod.vulkan.Vulkan.DYNAMIC_RENDERING;
import static org.lwjgl.vulkan.VK10.*;

public class Framebuffer {
    public static final int DEFAULT_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    public final String name;
    protected int format;
    protected int depthFormat;
    protected int width, height;
    protected boolean linearFiltering;
    protected boolean depthLinearFiltering;
    protected int attachmentCount;

    boolean hasColorAttachment;
    boolean hasDepthAttachment;

    private VulkanImage colorAttachment;
    protected VulkanImage depthAttachment;

    private int level;

    /**
     * Cache key for VkFramebuffer handles. RenderPass has identity equality, and the views are part of
     * the key so that a different mip level (or a re-created view) never reuses a stale framebuffer.
     */
    private record FramebufferKey(RenderPass renderPass, int level, long colorView, long depthView) {}

    private final Map<FramebufferKey, Long> renderpassToFramebufferMap = new HashMap<>();

    // SwapChain
    protected Framebuffer() {
        this.name = null;
    }

    public Framebuffer(Builder builder) {
        this.name = builder.name;
        this.format = builder.format;
        this.depthFormat = builder.depthFormat;
        this.width = builder.width;
        this.height = builder.height;
        this.linearFiltering = builder.linearFiltering;
        this.depthLinearFiltering = builder.depthLinearFiltering;
        this.hasColorAttachment = builder.hasColorAttachment;
        this.hasDepthAttachment = builder.hasDepthAttachment;

        if (builder.createImages)
            this.createImages();
        else {
            this.colorAttachment = builder.colorAttachment;
            this.depthAttachment = builder.depthAttachment;
        }

        this.level = builder.level;
    }

    public void createImages() {
        if (this.hasColorAttachment) {
            this.colorAttachment = VulkanImage.builder(this.width, this.height)
                                              .setName(this.name != null ? String.format("%s Color", this.name) : null)
                                              .setFormat(format)
                                              .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                                              .setLinearFiltering(linearFiltering)
                                              .setClamp(true)
                                              .createVulkanImage();
        }

        if (this.hasDepthAttachment) {
            this.depthAttachment = VulkanImage.builder(width, height)
                                              .setName(this.name != null ? String.format("%s Depth", this.name) : null)
                                              .setFormat(depthFormat)
                                              .setUsage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                                              .setLinearFiltering(depthLinearFiltering)
                                              .setClamp(true)
                                              .createVulkanImage();

            this.attachmentCount++;
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;

        this.cleanUp();

        this.createImages();
    }

    private long createFramebuffer(RenderPass renderPass, long colorView, long depthView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // Attachment order must match the render pass: colour first (if any), then depth
            int count = (renderPass.colorAttachmentInfo != null ? 1 : 0) + (renderPass.depthAttachmentInfo != null ? 1 : 0);
            if (count == 0) {
                throw new IllegalStateException("Render pass without attachments");
            }

            LongBuffer attachments = stack.mallocLong(count);
            if (renderPass.colorAttachmentInfo != null) {
                attachments.put(colorView);
            }
            if (renderPass.depthAttachmentInfo != null) {
                attachments.put(depthView);
            }
            attachments.flip();

            LongBuffer pFramebuffer = stack.mallocLong(1);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
            framebufferInfo.sType$Default();
            framebufferInfo.renderPass(renderPass.getId());
            framebufferInfo.width(this.getLevelWidth());
            framebufferInfo.height(this.getLevelHeight());
            framebufferInfo.layers(1);
            framebufferInfo.pAttachments(attachments);

            if (VK10.vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create framebuffer");
            }

            return pFramebuffer.get(0);
        }
    }

    /** View used as colour attachment: the single-level view of the targeted mip level. */
    private long legacyColorView() {
        return this.colorAttachment != null ? this.colorAttachment.getLevelImageView(this.level) : VK_NULL_HANDLE;
    }

    /** View used as depth attachment: it must cover exactly one mip level. */
    private long legacyDepthView() {
        if (this.depthAttachment == null) {
            return VK_NULL_HANDLE;
        }

        return this.depthAttachment.mipLevels > 1
                ? this.depthAttachment.getLevelImageView(Math.min(this.level, this.depthAttachment.mipLevels - 1))
                : this.depthAttachment.getImageView();
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, RenderPass renderPass, MemoryStack stack) {
        renderPass.setFramebuffer(this);

        if (!DYNAMIC_RENDERING) {
            long framebufferId = this.getFramebufferId(renderPass);
            renderPass.beginRenderPass(commandBuffer, framebufferId, stack);
        }
        else {
            renderPass.beginDynamicRendering(commandBuffer, stack);
        }
    }

    protected long getFramebufferId(RenderPass renderPass) {
        long colorView = legacyColorView();
        long depthView = legacyDepthView();

        FramebufferKey key = new FramebufferKey(renderPass, this.level, colorView, depthView);

        Long id = this.renderpassToFramebufferMap.get(key);
        if (id == null) {
            id = createFramebuffer(renderPass, colorView, depthView);
            this.renderpassToFramebufferMap.put(key, id);
        }

        return id;
    }

    public VkViewport.Buffer viewport(MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(0.0f);
        viewport.y(this.height);
        viewport.width(this.width);
        viewport.height(-this.height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        return viewport;
    }

    public VkRect2D.Buffer scissor(MemoryStack stack) {
        VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
        scissor.offset().set(0, 0);
        scissor.extent().set(this.width, this.height);

        return scissor;
    }

    public void cleanUp() {
        cleanUp(true);
    }

    public void cleanUp(boolean cleanImages) {
        if (cleanImages) {
            if (this.colorAttachment != null)
                this.colorAttachment.free();

            if (this.depthAttachment != null)
                this.depthAttachment.free();
        }

        final VkDevice device = Vulkan.getVkDevice();
        final long[] ids = renderpassToFramebufferMap.values().stream().mapToLong(Long::longValue).toArray();

        MemoryManager.getInstance().addFrameOp(
                () -> Arrays.stream(ids).forEach(id ->
                        vkDestroyFramebuffer(device, id, null))
        );


        renderpassToFramebufferMap.clear();
    }

    public void setLevel(int level) {
        VulkanImage levelImage = this.colorAttachment != null ? this.colorAttachment : this.depthAttachment;
        int maxLevel = levelImage.mipLevels - 1;
        if (level > maxLevel) {
            throw new IllegalStateException(
                    "Requested mip level (%d) greater than color attachments max mip level (%d)"
                            .formatted(level, maxLevel));
        }

        this.level = level;
    }

    public long getDepthImageView() {
        return depthAttachment.getImageView();
    }

    public VulkanImage getDepthAttachment() {
        return depthAttachment;
    }

    public VulkanImage getColorAttachment() {
        return colorAttachment;
    }

    public long getColorAttachmentView() {
        return colorAttachment.getLevelImageView(level);
    }

    public int getLevel() {
        return this.level;
    }

    /** Width of the mip level currently targeted by this framebuffer. */
    public int getLevelWidth() {
        return Math.max(1, this.width >> this.level);
    }

    /** Height of the mip level currently targeted by this framebuffer. */
    public int getLevelHeight() {
        return Math.max(1, this.height >> this.level);
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getFormat() {
        return this.format;
    }

    public int getDepthFormat() {
        return this.depthFormat;
    }

    public static Builder builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
        return new Builder(width, height, colorAttachments, hasDepthAttachment);
    }

    public static Builder builder(VulkanImage colorAttachment, VulkanImage depthAttachment) {
        return new Builder(colorAttachment, depthAttachment);
    }

    public static class Builder {
        final String name;
        final boolean createImages;
        final int width, height;
        int format, depthFormat;

        VulkanImage colorAttachment;
        VulkanImage depthAttachment;

        boolean hasColorAttachment;
        boolean hasDepthAttachment;

        boolean linearFiltering;
        boolean depthLinearFiltering;

        int level = 0;

        public Builder(int width, int height, int colorAttachments, boolean hasDepthAttachment) {
            this(null, width, height, colorAttachments, hasDepthAttachment);
        }

        public Builder(String name, int width, int height, int colorAttachments, boolean hasDepthAttachment) {
            Validate.isTrue(colorAttachments > 0 || hasDepthAttachment, "At least 1 attachment needed");

            //TODO multi color attachments
            Validate.isTrue(colorAttachments <= 1, "Not supported");

            this.name = name;
            this.createImages = true;
            this.format = DEFAULT_FORMAT;
            this.depthFormat = Vulkan.getDefaultDepthFormat();
            this.linearFiltering = true;
            this.depthLinearFiltering = false;

            this.width = width;
            this.height = height;
            this.hasColorAttachment = colorAttachments == 1;
            this.hasDepthAttachment = hasDepthAttachment;
        }

        public Builder(VulkanImage colorAttachment, VulkanImage depthAttachment) {
            this.name = null;
            this.createImages = false;
            this.colorAttachment = colorAttachment;
            this.depthAttachment = depthAttachment;

            this.format = colorAttachment.format;

            this.width = colorAttachment.width;
            this.height = colorAttachment.height;
            this.hasColorAttachment = true;
            this.hasDepthAttachment = depthAttachment != null;

            this.depthFormat = this.hasDepthAttachment ? depthAttachment.format : 0;
            this.linearFiltering = true;
            this.depthLinearFiltering = false;
        }

        public Framebuffer build() {
            return new Framebuffer(this);
        }

        public Builder setLevel(int level) {
            this.level = level;

            return this;
        }

        public Builder setFormat(int format) {
            this.format = format;

            return this;
        }

        public Builder setLinearFiltering(boolean b) {
            this.linearFiltering = b;

            return this;
        }

        public Builder setDepthLinearFiltering(boolean b) {
            this.depthLinearFiltering = b;

            return this;
        }

    }
}
