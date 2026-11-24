package net.vulkanmod.vulkan.pass;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * expose the main render pass for pipeline creation fallback; integrate scaled world rendering mode and depth attachment view.
 */

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.voolkan.render.WorldScale;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
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
        }

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
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (this.worldScale.isActive()) {
            // Re-entering mid-world (e.g. Renderer.flushCmds): resume the scaled world pass preserving its contents
            Framebuffer worldFramebuffer = this.worldScale.getFramebuffer();
            Renderer.getInstance().beginRenderPass(this.worldScale.getAuxPass(), worldFramebuffer);
            Renderer.setViewport(0, 0, worldFramebuffer.getWidth(), worldFramebuffer.getHeight(), stack);
            vkCmdSetScissor(commandBuffer, 0, worldFramebuffer.scissor(stack));
            return;
        }

        this.worldScale.syncDisabled();

        Framebuffer framebuffer = this.mainFramebuffer;

        VulkanImage colorAttachment = framebuffer.getColorAttachment();
        colorAttachment.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        Renderer.getInstance().beginRenderPass(this.mainRenderPass, framebuffer);

        Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight(), stack);

        VkRect2D.Buffer pScissor = framebuffer.scissor(stack);
        vkCmdSetScissor(commandBuffer, 0, pScissor);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);
        this.worldScale.reset();

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
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);
    }

    /**
     * Rebinds the swapchain using {@code mainRenderPass} (LOAD_OP_DONT_CARE) when the caller is
     * about to overwrite 100% of swapchain color (e.g. fullscreen upscale blit) and vanilla will
     * clear depth before GUI draws, avoiding a wasted full-resolution LOAD of color and depth.
     */
    public void rebindMainTargetDiscard() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();

        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

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
            if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
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
