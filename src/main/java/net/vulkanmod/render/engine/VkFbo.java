package net.vulkanmod.render.engine;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * add clear-folding bind overload using tile-GPU LOAD_OP_CLEAR render-pass variants.
 */

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.voolkan.compat.TileGpuOpts;
import net.minecraft.util.ARGB;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL33;

public class VkFbo {
    final int glId;
    final VkTextureView colorAttachmentView;
    final VkGpuTexture depthAttachment;

    protected VkFbo(VkTextureView colorAttachmentView, VkGpuTexture depthAttachment) {
        this.glId = GlStateManager.glGenFramebuffers();
        this.colorAttachmentView = colorAttachmentView;
        this.depthAttachment = depthAttachment;

        // Direct access
        VkGlFramebuffer fbo = VkGlFramebuffer.getFramebuffer(this.glId);

        VkGpuTexture colorAttachmentTexture = this.colorAttachmentView.texture();
        fbo.setAttachmentTexture(GL33.GL_COLOR_ATTACHMENT0, colorAttachmentTexture.id);
        if (depthAttachment != null) {
            fbo.setAttachmentTexture(GL33.GL_DEPTH_ATTACHMENT, depthAttachment.id);
        }

        fbo.setLevel(this.colorAttachmentView.baseMipLevel());
    }

    public void bind() {
        if (TileGpuOpts.isEnabled()) {
            bind(false, false);
            return;
        }
        VkGlFramebuffer.bindFramebuffer(GL33.GL_FRAMEBUFFER, this.glId);
        clearAttachments();
    }

    /**
     * Binds this FBO, folding explicit clears and any pending deferred texture clears into
     * {@code LOAD_OP_CLEAR} on tile GPUs when the FBO is not already bound. Falls back to
     * {@link Renderer#clearAttachments(int)} if the FBO was already bound so clears are never dropped.
     */
    public boolean bind(boolean clearColor, boolean clearDepth) {
        if (!TileGpuOpts.isEnabled()) {
            bind();
            return false;
        }

        VkGpuTexture colorAttachmentTexture = colorAttachmentView.texture();
        boolean doClearColor = clearColor;
        if (!doClearColor && colorAttachmentTexture.needsClear()) {
            doClearColor = true;
            int c = colorAttachmentTexture.clearColor;
            VRenderSystem.setClearColor(ARGB.redFloat(c), ARGB.greenFloat(c), ARGB.blueFloat(c), ARGB.alphaFloat(c));
        }
        if (doClearColor) {
            colorAttachmentTexture.needsClear = false;
        }

        boolean doClearDepth = clearDepth && depthAttachment != null;
        if (!doClearDepth && depthAttachment != null && depthAttachment.needsClear()) {
            doClearDepth = true;
            VRenderSystem.clearDepth(depthAttachment.depthClearValue);
        }
        if (doClearDepth) {
            depthAttachment.needsClear = false;
        }

        boolean clearedNatively = VkGlFramebuffer.bindFramebuffer(GL33.GL_FRAMEBUFFER, this.glId, doClearColor, doClearDepth);
        if (!clearedNatively && (doClearColor || doClearDepth)) {
            int clearMask = (doClearColor ? 0x4000 : 0) | (doClearDepth ? 0x100 : 0);
            Renderer.clearAttachments(clearMask);
            return true;
        }
        return clearedNatively;
    }

    protected void clearAttachments() {
        int clear = 0;
        float clearDepth;
        int clearColor;

        VkGpuTexture colorAttachmentTexture = colorAttachmentView.texture();
        if (colorAttachmentTexture.needsClear()) {
            clear |= 0x4000;
            clearColor = colorAttachmentTexture.clearColor;

            VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));

            colorAttachmentTexture.needsClear = false;
        }

        if (depthAttachment != null && depthAttachment.needsClear()) {
            clear |= 0x100;
            clearDepth = depthAttachment.depthClearValue;

            VRenderSystem.clearDepth(clearDepth);

            depthAttachment.needsClear = false;
        }

        if (clear != 0) {
            Renderer.clearAttachments(clear);
        }
    }

    protected void close() {
        VkGlFramebuffer.deleteFramebuffer(this.glId);
    }

    public boolean needsClear() {
        return this.colorAttachmentView.texture().needsClear() || (this.depthAttachment != null && this.depthAttachment.needsClear());
    }
}
