package net.vulkanmod.vulkan.pass;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * expose the main render pass for pipeline creation fallback; integrate scaled world rendering mode, depth attachment view, and tile-GPU lazy swapchain pass start with deferred initial clear and LOAD_OP_CLEAR variants.
 */

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.voolkan.compat.MacPerfOpts;
import dev.voolkan.compat.TileGpuOpts;
import dev.voolkan.render.WorldScale;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
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
    private RenderPass mainColorOnlyDiscardPass;

    private boolean mainTargetStartedThisFrame = false;
    private boolean pendingInitialClearColor = false;
    private boolean pendingInitialClearDepth = false;
    private boolean mainDepthCleanAtOne = false;
    private boolean mainColorNeedsReuseBarrier = false;

    private GpuTexture[] colorAttachmentTextures;
    private GpuTextureView[] colorAttachmentTextureViews;
    IntSupplier imageIdxSupplier;
    private GpuTexture depthAttachmentTexture;
    private GpuTexture[] depthAttachmentTextures;

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
        this.mainDepthCleanAtOne = false;
        this.mainColorNeedsReuseBarrier = false;

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

            if (MacPerfOpts.menuBlurOpts()) {
                builder = RenderPass.builder(this.mainFramebuffer);
                builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
                builder.dropDepthAttachment();
                this.mainColorOnlyDiscardPass = builder.build();
            }
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
        if (this.mainColorOnlyDiscardPass != null) {
            this.mainColorOnlyDiscardPass.cleanUp();
            this.mainColorOnlyDiscardPass = null;
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
            if (MacPerfOpts.tbdrPassOpts() && !this.worldScale.isPassStartedThisFrame()) {
                return;
            }
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
                ensureSwapChainAcquiredIfNeeded();
                Framebuffer framebuffer = this.mainFramebuffer;
                emitMainColorReuseBarrierIfNeeded(commandBuffer);
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
        if (MacPerfOpts.inlineImageUploads()) {
            net.vulkanmod.render.texture.ImageUploadHelper.INSTANCE.flushInlineTransitions(commandBuffer);
        }
        ensureSwapChainAcquiredIfNeeded();

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
        this.mainDepthCleanAtOne = false;
        this.mainColorNeedsReuseBarrier = false;

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
        this.mainDepthCleanAtOne = false;
        this.mainColorNeedsReuseBarrier = false;
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
            if (this.worldScale.isBoundPass(boundRenderPass))
                return;

            if (MacPerfOpts.tbdrPassOpts() && !this.worldScale.isPassStartedThisFrame()) {
                this.worldScale.beginPassOnDemand(false, false);
                return;
            }

            Renderer.getInstance().endRenderPass(commandBuffer);
            Renderer.getInstance().beginRenderPass(this.worldScale.getAuxPass(), this.worldScale.getFramebuffer());
            return;
        }

        // Do not rebind if the framebuffer is already bound
        if (isMainRenderPass(boundRenderPass))
            return;

        ensureSwapChainAcquiredIfNeeded();

        if (TileGpuOpts.isEnabled() && !this.mainTargetStartedThisFrame) {
            rebindMainTarget(false, false);
            return;
        }

        Renderer.getInstance().endRenderPass(commandBuffer);
        emitMainColorReuseBarrierIfNeeded(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);
    }

    @Override
    public boolean rebindMainTarget(boolean clearColor, boolean clearDepth) {
        if (this.worldScale.isActive()) {
            if (MacPerfOpts.tbdrPassOpts() && !this.worldScale.isPassStartedThisFrame()) {
                return this.worldScale.beginPassOnDemand(clearColor, clearDepth);
            }
            rebindMainTarget();
            return false;
        }

        ensureSwapChainAcquiredIfNeeded();

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

        ensureSwapChainAcquiredIfNeeded();

        this.pendingInitialClearColor = false;
        this.pendingInitialClearDepth = false;

        if (TileGpuOpts.isEnabled()) {
            // Keep depth STORE_OP_STORE because GuiRenderer.prepare() may open offscreen PiP/item-atlas
            // passes before GuiRenderer.draw() resumes swapchain rendering via auxRenderPass (LOAD_OP_LOAD).
            // On macOS TBDR, fold the upcoming pre-GUI clearDepth(1.0) into mainClearDepthPass (LOAD_OP_CLEAR)
            // so TRACY_BLIT (depthWrite=false) leaves depth pristine at 1.0 and skips vkCmdClearAttachments.
            RenderPass pass;
            if (MacPerfOpts.tbdrPassOpts() && this.mainClearDepthPass != null) {
                VRenderSystem.clearDepth(1.0);
                pass = this.mainClearDepthPass;
            } else {
                pass = this.mainRenderPass;
            }
            Renderer.getInstance().beginRenderPass(pass, this.mainFramebuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Renderer.setViewport(0, 0, this.mainFramebuffer.getWidth(), this.mainFramebuffer.getHeight(), stack);
            }
            Renderer.resetScissor();
            this.mainTargetStartedThisFrame = true;
            this.mainDepthCleanAtOne = (pass == this.mainClearDepthPass);
            return;
        }

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.mainRenderPass, this.mainFramebuffer);
    }

    @Override
    public boolean consumeCleanDepthAtOne() {
        boolean clean = this.mainDepthCleanAtOne
                && !this.worldScale.isActive()
                && isMainRenderPass(Renderer.getInstance().getBoundRenderPass());
        this.mainDepthCleanAtOne = false;
        return clean;
    }

    @Override
    public boolean rebindMainTargetColorOnlyDiscard() {
        if (!MacPerfOpts.menuBlurOpts()
                || this.mainColorOnlyDiscardPass == null
                || this.worldScale.isActive()
                || !this.mainTargetStartedThisFrame) {
            return false;
        }

        ensureSwapChainAcquiredIfNeeded();

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        Renderer.getInstance().endRenderPass(commandBuffer);
        emitMainColorReuseBarrierIfNeeded(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.mainColorOnlyDiscardPass, this.mainFramebuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Renderer.setViewport(0, 0, this.mainFramebuffer.getWidth(), this.mainFramebuffer.getHeight(), stack);
        }
        Renderer.resetScissor();
        this.mainColorNeedsReuseBarrier = true;
        return true;
    }

    /**
     * Emits a same-layout {@code COLOR_ATTACHMENT_OPTIMAL} execution and memory barrier when
     * resuming main color rendering under Dynamic Rendering after {@code mainColorOnlyDiscardPass}
     * ended without a layout transition (e.g. final menu blur pass into {@code AFTER_BLUR} GUI).
     */
    private void emitMainColorReuseBarrierIfNeeded(VkCommandBuffer commandBuffer) {
        if (!this.mainColorNeedsReuseBarrier) {
            return;
        }
        this.mainColorNeedsReuseBarrier = false;
        if (!Vulkan.DYNAMIC_RENDERING) {
            return;
        }
        VulkanImage color = this.mainFramebuffer.getColorAttachment();
        if (color == null || color.getCurrentLayout() != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(color.getId());
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);
            barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    0,
                    null,
                    null,
                    barrier
            );
        }
    }

    @Override
    public RenderPass getColorOnlyDiscardPass() {
        return this.mainColorOnlyDiscardPass;
    }

    @Override
    public void invalidateCleanDepth() {
        this.mainDepthCleanAtOne = false;
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Check if render pass is using the active main framebuffer
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        Framebuffer activeFramebuffer;
        if (this.worldScale.isActive()) {
            if (this.worldScale.isBoundPass(boundRenderPass))
                Renderer.getInstance().endRenderPass(commandBuffer);
            activeFramebuffer = this.worldScale.getFramebuffer();
        }
        else {
            ensureSwapChainAcquiredIfNeeded();
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

    private void ensureSwapChainAcquiredIfNeeded() {
        if (MacPerfOpts.lateAcquire() && this.mainFramebuffer == Renderer.getInstance().getSwapChain()) {
            Renderer.getInstance().ensureSwapChainImageAcquired();
        }
    }

    @Override
    public GpuTexture getColorAttachment() {
        if (this.worldScale.isActive()) {
            return this.worldScale.getColorAttachment();
        }
        ensureSwapChainAcquiredIfNeeded();
        return this.colorAttachmentTextures[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        if (this.worldScale.isActive()) {
            return this.worldScale.getColorAttachmentView();
        }
        ensureSwapChainAcquiredIfNeeded();
        return this.colorAttachmentTextureViews[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTexture getDepthAttachment() {
        if (this.worldScale.isActive()) {
            return this.worldScale.getDepthAttachment();
        }
        if (this.depthAttachmentTextures != null && this.depthAttachmentTextures.length > 0) {
            return this.depthAttachmentTextures[Renderer.getCurrentFrame() % this.depthAttachmentTextures.length];
        }
        return this.depthAttachmentTexture;
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

        if (this.mainFramebuffer == swapChain && swapChain.getDepthAttachments() != null) {
            VulkanImage[] depths = swapChain.getDepthAttachments();
            this.depthAttachmentTextures = new GpuTexture[depths.length];
            for (int i = 0; i < depths.length; i++) {
                this.depthAttachmentTextures[i] = device.gpuTextureFromVulkanImage(depths[i]);
            }
            this.depthAttachmentTexture = this.depthAttachmentTextures[0];
        } else {
            this.depthAttachmentTextures = null;
            this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getDepthAttachment());
        }
    }
}
