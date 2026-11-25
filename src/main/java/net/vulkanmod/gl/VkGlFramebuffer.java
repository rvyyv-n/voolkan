package net.vulkanmod.gl;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * repair legacy VkRenderPass framebuffer attachments, mips, and bound state; implement glBlitFramebuffer from read to draw target;
 * add tile-GPU LOAD_OP_CLEAR render-pass variants for offscreen framebuffers.
 */

import dev.voolkan.compat.MacPerfOpts;
import dev.voolkan.compat.TileGpuOpts;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK11.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK11.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK11.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

public class VkGlFramebuffer {
    private static int idCounter = 1;

    private static final Int2ReferenceOpenHashMap<VkGlFramebuffer> map = new Int2ReferenceOpenHashMap<>();
    private static VkGlFramebuffer boundFramebuffer;
    private static VkGlFramebuffer readFramebuffer;

    public static void resetBoundFramebuffer() {
        boundFramebuffer = null;
    }

    public static void beginRendering(VkGlFramebuffer glFramebuffer) {
        boolean begunRendering = glFramebuffer.beginRendering();

        if (begunRendering) {
            Framebuffer framebuffer = glFramebuffer.framebuffer;
            int viewWidth = framebuffer.getLevelWidth();
            int viewHeight = framebuffer.getLevelHeight();

            Renderer.setInvertedViewport(0, 0, viewWidth, viewHeight);
            Renderer.setScissor(0, 0, viewWidth, viewHeight);

            // TODO: invert cull instead of disabling
            VRenderSystem.disableCull();
        }

        boundFramebuffer = glFramebuffer;
    }

    /**
     * Begins rendering into {@code glFramebuffer}, using a {@code LOAD_OP_CLEAR} render-pass variant
     * on tile GPUs when the framebuffer is not already bound.
     *
     * @return {@code true} if the requested clear(s) were performed natively via {@code LOAD_OP_CLEAR}
     */
    public static boolean beginRendering(VkGlFramebuffer glFramebuffer, boolean clearColor, boolean clearDepth) {
        if (!TileGpuOpts.isEnabled() || (!clearColor && !clearDepth)) {
            beginRendering(glFramebuffer);
            return false;
        }

        Framebuffer alreadyBound = Renderer.getInstance().getBoundFramebuffer();
        boolean newlyBound = alreadyBound != glFramebuffer.framebuffer;
        boolean doClearDepth = clearDepth && glFramebuffer.depthAttachment != null;
        RenderPass pass = (newlyBound && (clearColor || doClearDepth))
                ? glFramebuffer.getRenderPass(clearColor, doClearDepth)
                : glFramebuffer.renderPass;

        boolean begunRendering = Renderer.getInstance().beginRenderPass(pass, glFramebuffer.framebuffer);

        if (begunRendering) {
            Framebuffer framebuffer = glFramebuffer.framebuffer;
            int viewWidth = framebuffer.getLevelWidth();
            int viewHeight = framebuffer.getLevelHeight();

            Renderer.setInvertedViewport(0, 0, viewWidth, viewHeight);
            Renderer.setScissor(0, 0, viewWidth, viewHeight);

            // TODO: invert cull instead of disabling
            VRenderSystem.disableCull();
        }

        boundFramebuffer = glFramebuffer;
        return newlyBound && (clearColor || doClearDepth);
    }

    public static int genFramebufferId() {
        int id = idCounter;
        map.put(id, new VkGlFramebuffer(id));
        idCounter++;
        return id;
    }

    public static void bindFramebuffer(int target, int id) {
        if (id == 0) {
            if (target == GL30.GL_READ_FRAMEBUFFER) {
                readFramebuffer = null;
                return;
            }

            Renderer.getInstance().endRenderPass();

            if (Renderer.isRecording()) {
                Renderer.getInstance().getMainPass().rebindMainTarget();
            }

            boundFramebuffer = null;
            if (target == GL30.GL_FRAMEBUFFER) {
                readFramebuffer = null;
            }
            return;
        }

        VkGlFramebuffer glFramebuffer = map.get(id);

        if (glFramebuffer == null)
            throw new NullPointerException("No Framebuffer with ID: %d ".formatted(id));

        if (glFramebuffer.needsUpdate) {
            glFramebuffer.create();
        }

        switch (target) {
            case GL30.GL_DRAW_FRAMEBUFFER -> {
                if (glFramebuffer.framebuffer != null) {
                    beginRendering(glFramebuffer);
                }

                boundFramebuffer = glFramebuffer;
            }
            case GL30.GL_FRAMEBUFFER -> {
                if (glFramebuffer.framebuffer != null) {
                    beginRendering(glFramebuffer);
                }

                boundFramebuffer = glFramebuffer;
                readFramebuffer = glFramebuffer;
            }
            case GL30.GL_READ_FRAMEBUFFER -> {
                readFramebuffer = glFramebuffer;
            }
        }

    }

    public static boolean bindFramebuffer(int target, int id, boolean clearColor, boolean clearDepth) {
        if (id == 0 || !TileGpuOpts.isEnabled() || (!clearColor && !clearDepth)) {
            bindFramebuffer(target, id);
            return false;
        }

        VkGlFramebuffer glFramebuffer = map.get(id);

        if (glFramebuffer == null)
            throw new NullPointerException("No Framebuffer with ID: %d ".formatted(id));

        if (glFramebuffer.needsUpdate) {
            glFramebuffer.create();
        }

        boolean clearedNatively = false;
        switch (target) {
            case GL30.GL_DRAW_FRAMEBUFFER -> {
                if (glFramebuffer.framebuffer != null) {
                    clearedNatively = beginRendering(glFramebuffer, clearColor, clearDepth);
                }

                boundFramebuffer = glFramebuffer;
            }
            case GL30.GL_FRAMEBUFFER -> {
                if (glFramebuffer.framebuffer != null) {
                    clearedNatively = beginRendering(glFramebuffer, clearColor, clearDepth);
                }

                boundFramebuffer = glFramebuffer;
                readFramebuffer = glFramebuffer;
            }
            case GL30.GL_READ_FRAMEBUFFER -> {
                readFramebuffer = glFramebuffer;
            }
        }
        return clearedNatively;
    }

    public static void deleteFramebuffer(int id) {
        if (id == 0) {
            return;
        }

        VkGlFramebuffer glFramebuffer = map.remove(id);

        if (glFramebuffer == null)
            throw new NullPointerException("bound framebuffer is null");

        if (readFramebuffer == glFramebuffer) {
            readFramebuffer = null;
        }

        if (MacPerfOpts.tbdrPassOpts()
                && glFramebuffer.framebuffer != null
                && Renderer.getInstance().getBoundFramebuffer() == glFramebuffer.framebuffer) {
            Renderer.getInstance().endRenderPass();
        }

        glFramebuffer.cleanUp(true);
        if (boundFramebuffer == glFramebuffer) {
            boundFramebuffer = null;
        }
    }

    public static void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        if (attachment != GL30.GL_COLOR_ATTACHMENT0 && attachment != GL30.GL_DEPTH_ATTACHMENT) {
            throw new UnsupportedOperationException();
        }
        if (texTarget != GL11.GL_TEXTURE_2D) {
            throw new UnsupportedOperationException();
        }
        if (level != 0) {
            throw new UnsupportedOperationException();
        }

        boundFramebuffer.setAttachmentTexture(attachment, texture);
        boundFramebuffer.create();
        VkGlFramebuffer.beginRendering(boundFramebuffer);
    }

    public static void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (boundFramebuffer == null)
            return;

        boundFramebuffer.setAttachmentRenderbuffer(attachment, renderbuffer);
        boundFramebuffer.create();
        VkGlFramebuffer.beginRendering(boundFramebuffer);
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1,
                                         int dstY1, int mask, int filter) {
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) == 0) {
            return;
        }

        // The default framebuffer is the swapchain: its images have no TRANSFER usage, so they can't be blitted
        if (readFramebuffer == null || boundFramebuffer == null) {
            return;
        }

        VulkanImage srcImage = readFramebuffer.colorAttachment;
        VulkanImage dstImage = boundFramebuffer.colorAttachment;

        if (srcImage == null || dstImage == null || srcImage == dstImage) {
            return;
        }

        int vkFilter = (filter == GL11.GL_LINEAR) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        ImageUtil.blitFramebuffer(srcImage, dstImage, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, vkFilter);
    }

    public static int glCheckFramebufferStatus(int target) {
        //TODO
        return GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    public static VkGlFramebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    public static VkGlFramebuffer getFramebuffer(int id) {
        return map.get(id);
    }

    public final int id;
    Framebuffer framebuffer;
    RenderPass renderPass;
    RenderPass clearColorRenderPass;
    RenderPass clearDepthRenderPass;
    RenderPass clearBothRenderPass;

    VulkanImage colorAttachment;
    VulkanImage depthAttachment;
    int level = 0;

    boolean needsUpdate;

    VkGlFramebuffer(int i) {
        this.id = i;
    }

    boolean beginRendering() {
        return Renderer.getInstance().beginRenderPass(this.renderPass, this.framebuffer);
    }

    public void setAttachmentTexture(int attachment, int id) {
        VkGlTexture vkGlTexture = VkGlTexture.getTexture(id);

        if (vkGlTexture == null)
            throw new NullPointerException(String.format("Texture %d is null", id));

        setAttachmentImage(attachment, vkGlTexture.getVulkanImage());
    }

    public void setAttachmentRenderbuffer(int attachment, int id) {
        VkGlRenderbuffer renderbuffer = VkGlRenderbuffer.getRenderbuffer(id);

        if (renderbuffer == null)
            throw new NullPointerException(String.format("Texture %d is null", id));

        setAttachmentImage(attachment, renderbuffer.getVulkanImage());
    }

    public void setAttachmentImage(int attachment, VulkanImage image) {
        if (image == null)
            throw new NullPointerException("Image is null");

        switch (attachment) {
            case (GL30.GL_COLOR_ATTACHMENT0) -> this.setColorAttachment(image);
            case (GL30.GL_DEPTH_ATTACHMENT) -> this.setDepthAttachment(image);

            default -> throw new IllegalStateException("Unexpected value: " + attachment);
        }

        this.needsUpdate = true;
    }

    public void setLevel(int level) {
        this.level = level;

        if (this.framebuffer != null) {
            this.framebuffer.setLevel(level);
        }
    }

    void setColorAttachment(VulkanImage image) {
        this.colorAttachment = image;
    }

    void setDepthAttachment(VulkanImage image) {
        //TODO check if texture is in depth format
        this.depthAttachment = image;
    }

    public void create() {
        // Cannot create without color attachment
        if (this.colorAttachment == null)
            return;

        if (this.framebuffer != null) {
            this.cleanUp(false);
        }

        boolean hasDepthImage = this.depthAttachment != null;
        VulkanImage depthImage = this.depthAttachment;

        this.framebuffer = Framebuffer.builder(this.colorAttachment, depthImage)
                                      .build();

        this.framebuffer.setLevel(this.level);

        this.renderPass = buildRenderPass(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_LOAD);
        this.needsUpdate = false;
    }

    private RenderPass buildRenderPass(int colorLoadOp, int depthLoadOp) {
        RenderPass.Builder builder = RenderPass.builder(this.framebuffer);

        builder.getColorAttachmentInfo()
               .setLoadOp(colorLoadOp)
               .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if (this.depthAttachment != null) {
            builder.getDepthAttachmentInfo()
                   .setOps(depthLoadOp, VK_ATTACHMENT_STORE_OP_STORE);
        }

        return builder.build();
    }

    public RenderPass getRenderPass(boolean clearColor, boolean clearDepth) {
        if (!TileGpuOpts.isEnabled()) {
            return this.renderPass;
        }

        boolean doClearDepth = clearDepth && this.depthAttachment != null;
        if (!clearColor && !doClearDepth) {
            return this.renderPass;
        }

        if (clearColor && doClearDepth) {
            if (this.clearBothRenderPass == null) {
                this.clearBothRenderPass = buildRenderPass(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_CLEAR);
            }
            return this.clearBothRenderPass;
        } else if (clearColor) {
            if (this.clearColorRenderPass == null) {
                this.clearColorRenderPass = buildRenderPass(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_LOAD_OP_LOAD);
            }
            return this.clearColorRenderPass;
        } else {
            if (this.clearDepthRenderPass == null) {
                this.clearDepthRenderPass = buildRenderPass(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_CLEAR);
            }
            return this.clearDepthRenderPass;
        }
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public RenderPass getRenderPass() {
        return renderPass;
    }

    void cleanUp(boolean freeAttachments) {
        if (framebuffer != null) {
            this.framebuffer.cleanUp(freeAttachments);
            this.renderPass.cleanUp();
            if (this.clearColorRenderPass != null) {
                this.clearColorRenderPass.cleanUp();
            }
            if (this.clearDepthRenderPass != null) {
                this.clearDepthRenderPass.cleanUp();
            }
            if (this.clearBothRenderPass != null) {
                this.clearBothRenderPass.cleanUp();
            }
        }

        this.framebuffer = null;
        this.renderPass = null;
        this.clearColorRenderPass = null;
        this.clearDepthRenderPass = null;
        this.clearBothRenderPass = null;
    }
}
