package net.vulkanmod.vulkan.pass;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * expose the main render pass for pipeline creation fallback; integrate scaled world rendering mode, depth attachment view, and tile-GPU lazy swapchain pass start with deferred initial clear and LOAD_OP_CLEAR variants.
 */

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.voolkan.compat.TileGpuOpts;
import dev.voolkan.render.WorldScale;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;

import java.util.function.IntSupplier;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class DefaultMainPass implements MainPass {

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private Framebuffer mainFramebuffer;

    private RenderPass mainRenderPass;
    private RenderPass auxRenderPass;
    private RenderPass mainClearBothPass;
    private RenderPass mainClearColorPass;
    private RenderPass mainClearDepthPass;

    private boolean mainTargetStartedThisFrame = false;
    private boolean pendingInitialClearColor = false;
    private boolean pendingInitialClearDepth = false;

    private GpuTexture[] colorAttachmentTextures;
    private GpuTextureView[] colorAttachmentTextureViews;
    IntSupplier imageIdxSupplier;
    private GpuTexture depthAttachmentTexture;

    private final WorldScale worldScale = new WorldScale();

    DefaultMainPass() {
        createResources();
    }

    private void createResources() {
        if (this.mainFramebuffer != null) {
            if (this.mainFramebuffer != Renderer.getInstance()
                                                .getSwapChain()) {
                this.mainFramebuffer.cleanUp(true);
            }

            this.mainRenderPass.cleanUp();
            this.auxRenderPass.cleanUp();
            cleanUpTilePasses();
        }
        this.mainTargetStartedThisFrame = false;
        this.pendingInitialClearColor = false;
        this.pendingInitialClearDepth = false;

        Framebuffer framebuffer;
        if (Renderer.getInstance().getSwapChain().hasImages()) {
            framebuffer = Renderer.getInstance().getSwapChain();
        }
        else {
            framebuffer = Framebuffer.builder(10, 10, 1, true)
                                     .build();
        }

        this.mainFramebuffer = framebuffer;

        createRenderPasses();
        createAttachmentTextures();
    }

    private void createRenderPasses() {
        RenderPass.Builder builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);

        this.mainRenderPass = builder.build();

        // Create an auxiliary RenderPass needed in case of main target rebinding
        builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        this.auxRenderPass = builder.build();

        if (TileGpuOpts.isEnabled()) {
            builder = RenderPass.builder(this.mainFramebuffer);
            builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            this.mainClearBothPass = builder.build();

            builder = RenderPass.builder(this.mainFramebuffer);
            builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
            this.mainClearColorPass = builder.build();

            builder = RenderPass.builder(this.mainFramebuffer);
            builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
            builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            this.mainClearDepthPass = builder.build();
        }
    }

    private void cleanUpTilePasses() {
        if (this.mainClearBothPass != null) {
            this.mainClearBothPass.cleanUp();
            this.mainClearBothPass = null;
        }
        if (this.mainClearColorPass != null) {
            this.mainClearColorPass.cleanUp();
            this.mainClearColorPass = null;
        }
        if (this.mainClearDepthPass != null) {
            this.mainClearDepthPass.cleanUp();
            this.mainClearDepthPass = null;
        }
    }

    private boolean isMainRenderPass(RenderPass pass) {
        return pass != null && (pass == this.mainRenderPass
                || pass == this.auxRenderPass
                || pass == this.mainClearBothPass
                || pass == this.mainClearColorPass
                || pass == this.mainClearDepthPass);
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (this.worldScale.isActive()) {
            // Re-entering mid-world (e.g. Renderer.flushCmds): resume the scaled world pass preserving its contents
            Framebuffer worldFramebuffer = this.worldScale.getFramebuffer();
            Renderer.getInstance().beginRenderPass(this.worldScale.getAuxPass(), worldFramebuffer);
            Renderer.setViewport(0, 0, worldFramebuffer.getWidth(), worldFramebuffer.getHeight(), stack);
            Renderer.resetScissor();
            return;
        }

        this.worldScale.syncDisabled();

        if (TileGpuOpts.isEnabled()) {
            if (this.mainTargetStartedThisFrame) {
                // Mid-frame resume (e.g. Renderer.flushCmds): preserve swapchain contents drawn earlier this frame
                Framebuffer framebuffer = this.mainFramebuffer;
                framebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                Renderer.getInstance().beginRenderPass(this.auxRenderPass, framebuffer);
                Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight(), stack);
                Renderer.resetScissor();
            }
            return;
        }

        Framebuffer framebuffer = this.mainFramebuffer;

        VulkanImage colorAttachment = framebuffer.getColorAttachment();
        colorAttachment.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        Renderer.getInstance().beginRenderPass(this.mainRenderPass, framebuffer);

        Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight(), stack);

        Renderer.resetScissor();
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);
        this.worldScale.reset();

        if (TileGpuOpts.isEnabled()
                && !this.mainTargetStartedThisFrame
                && this.mainFramebuffer == Renderer.getInstance().getSwapChain()
                && this.mainFramebuffer.getColorAttachment().getCurrentLayout() == VK_IMAGE_LAYOUT_UNDEFINED) {
            // Ensure a freshly acquired UNDEFINED swapchain image is cleared before presenting on empty frames
            VRenderSystem.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            VRenderSystem.clearDepth(1.0);
            Renderer.getInstance().beginRenderPass(this.mainClearBothPass, this.mainFramebuffer);
            Renderer.getInstance().endRenderPass(commandBuffer);
        }

        this.mainTargetStartedThisFrame = false;
        this.pendingInitialClearColor = false;
        this.pendingInitialClearDepth = false;

        if (this.mainFramebuffer == Renderer.getInstance().getSwapChain()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    @Override
    public void cleanUp() {
        this.worldScale.release();
        this.mainRenderPass.cleanUp();
        this.auxRenderPass.cleanUp();
        cleanUpTilePasses();
    }

    @Override
    public void onResize() {
        this.worldScale.release();
        createResources();
    }

    @Override
    public void beginWorld(DeltaTracker deltaTracker, Camera camera) {
        if (this.mainFramebuffer != Renderer.getInstance().getSwapChain()) {
            return;
        }

        this.worldScale.begin(Renderer.getInstance().getSwapChain(), deltaTracker, camera);
    }

    @Override
    public void endWorld() {
        this.worldScale.end(this);
    }

    @Override
    public void resetWorld() {
        this.worldScale.reset();
        this.mainTargetStartedThisFrame = false;
        this.pendingInitialClearColor = false;
        this.pendingInitialClearDepth = false;
    }

    @Override
    public void deferInitialClear() {
        if (TileGpuOpts.isEnabled() && !this.mainTargetStartedThisFrame) {
            this.pendingInitialClearColor = true;
            this.pendingInitialClearDepth = true;
        }
    }

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();

        if (this.worldScale.isActive()) {
            if (boundRenderPass == this.worldScale.getClearPass() || boundRenderPass == this.worldScale.getAuxPass())
                return;

            Renderer.getInstance().endRenderPass(commandBuffer);
            Renderer.getInstance().beginRenderPass(this.worldScale.getAuxPass(), this.worldScale.getFramebuffer());
            return;
        }

        // Do not rebind if the framebuffer is already bound
        if (isMainRenderPass(boundRenderPass))
            return;

        if (TileGpuOpts.isEnabled() && !this.mainTargetStartedThisFrame) {
            rebindMainTarget(false, false);
            return;
        }

        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);
    }

    @Override
    public boolean rebindMainTarget(boolean clearColor, boolean clearDepth) {
        if (this.worldScale.isActive()) {
            rebindMainTarget();
            return false;
        }

        if (TileGpuOpts.isEnabled() && !this.mainTargetStartedThisFrame) {
            boolean effectiveClearColor = clearColor || this.pendingInitialClearColor;
            boolean effectiveClearDepth = clearDepth || this.pendingInitialClearDepth;

            if (!clearColor && this.pendingInitialClearColor) {
                VRenderSystem.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            }
            if (!clearDepth && this.pendingInitialClearDepth) {
                VRenderSystem.clearDepth(1.0);
            }

            this.pendingInitialClearColor = false;
            this.pendingInitialClearDepth = false;

            RenderPass pass;
            if (effectiveClearColor && effectiveClearDepth) {
                pass = this.mainClearBothPass;
            } else if (effectiveClearColor) {
                pass = this.mainClearColorPass;
            } else if (effectiveClearDepth) {
                pass = this.mainClearDepthPass;
            } else {
                pass = this.mainRenderPass;
            }

            // Renderer.beginRenderPass ensures recording, ends any prior pass, and transitions layouts on the live CB.
            Renderer.getInstance().beginRenderPass(pass, this.mainFramebuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Renderer.setViewport(0, 0, this.mainFramebuffer.getWidth(), this.mainFramebuffer.getHeight(), stack);
            }
            Renderer.resetScissor();
            this.mainTargetStartedThisFrame = true;
            return clearColor || clearDepth;
        }

        rebindMainTarget();
        return false;
    }

    /**
     * Rebinds the swapchain using {@code mainRenderPass} (LOAD_OP_DONT_CARE) when the caller is
     * about to overwrite 100% of swapchain color (e.g. fullscreen upscale blit) and vanilla will
     * clear depth before GUI draws, avoiding a wasted full-resolution LOAD of color and depth.
     */
    public void rebindMainTargetDiscard() {
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();

        if (isMainRenderPass(boundRenderPass))
            return;

        this.pendingInitialClearColor = false;
        this.pendingInitialClearDepth = false;

        if (TileGpuOpts.isEnabled()) {
            // Keep depth STORE_OP_STORE (mainRenderPass) because GuiRenderer.prepare() opens offscreen
            // PiP/item-atlas FBO passes after GameRenderer's pre-GUI clearDepthTexture, then resumes
            // swapchain drawing via auxRenderPass (LOAD_OP_LOAD).
            Renderer.getInstance().beginRenderPass(this.mainRenderPass, this.mainFramebuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Renderer.setViewport(0, 0, this.mainFramebuffer.getWidth(), this.mainFramebuffer.getHeight(), stack);
            }
            Renderer.resetScissor();
            this.mainTargetStartedThisFrame = true;
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.mainRenderPass, this.mainFramebuffer);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Check if render pass is using the active main framebuffer
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        Framebuffer activeFramebuffer;
        if (this.worldScale.isActive()) {
            if (boundRenderPass == this.worldScale.getClearPass() || boundRenderPass == this.worldScale.getAuxPass())
                Renderer.getInstance().endRenderPass(commandBuffer);
            activeFramebuffer = this.worldScale.getFramebuffer();
        }
        else {
            if (isMainRenderPass(boundRenderPass))
                Renderer.getInstance().endRenderPass(commandBuffer);
            activeFramebuffer = this.mainFramebuffer;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            activeFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(activeFramebuffer.getColorAttachment());
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return this.worldScale.isActive() ? this.worldScale.getFramebuffer() : this.mainFramebuffer;
    }

    @Override
    public RenderPass getMainRenderPass() {
        return this.worldScale.isActive() ? this.worldScale.getClearPass() : this.mainRenderPass;
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.worldScale.isActive()
                ? this.worldScale.getColorAttachment()
                : this.colorAttachmentTextures[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.worldScale.isActive()
                ? this.worldScale.getColorAttachmentView()
                : this.colorAttachmentTextureViews[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.worldScale.isActive() ? this.worldScale.getDepthAttachment() : this.depthAttachmentTexture;
    }

    @Override
    public GpuTextureView getDepthAttachmentView() {
        return this.worldScale.isActive() ? this.worldScale.getDepthAttachmentView() : null;
    }

    @Override
    public boolean isWorldActive() {
        return this.worldScale.isActive();
    }

    private void createAttachmentTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        if (this.mainFramebuffer == swapChain) {
            var swapChainImages = swapChain.getImages();

            int imageCount = swapChainImages.size();
            this.colorAttachmentTextures = new GpuTexture[imageCount];
            this.colorAttachmentTextureViews = new GpuTextureView[imageCount];

            for (int i = 0; i < imageCount; ++i) {
                VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(swapChainImages.get(i));
                GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
                this.colorAttachmentTextures[i] = attachmentTexture;
                this.colorAttachmentTextureViews[i] = attachmentTextureView;
            }

            this.imageIdxSupplier = Renderer::getCurrentImage;
        }
        else {
            this.colorAttachmentTextures = new GpuTexture[1];
            this.colorAttachmentTextureViews = new GpuTextureView[1];

            VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getColorAttachment());
            GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
            this.colorAttachmentTextures[0] = attachmentTexture;
            this.colorAttachmentTextureViews[0] = attachmentTextureView;

            // Always return idx 0 as there's only 1 image
            this.imageIdxSupplier = () -> 0;
        }

        this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getDepthAttachment());
    }
}
