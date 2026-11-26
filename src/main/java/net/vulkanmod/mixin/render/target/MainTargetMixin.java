package net.vulkanmod.mixin.render.target;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * forward getDepthTextureView to the active main pass so scaled world rendering stays consistent.
 */

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {

    public MainTargetMixin(boolean useDepth) {
        super("Main", useDepth);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void createFrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void createBuffers(int i, int j) {
        RenderSystem.assertOnRenderThread();
        GpuDevice gpuDevice = RenderSystem.getDevice();
        int k = gpuDevice.getMaxTextureSize();
        if (i > 0 && i <= k && j > 0 && j <= k) {
            this.width = i;
            this.height = j;
            if (this.useDepth) {
                this.depthTexture = gpuDevice.createTexture(() -> this.label + " / Depth", 15, TextureFormat.DEPTH32, i, j, 1, 1);
                this.depthTextureView = gpuDevice.createTextureView(this.depthTexture);
            }

            this.colorTexture = gpuDevice.createTexture(() -> this.label + " / Color", 15, TextureFormat.RGBA8, i, j, 1, 1);
            this.colorTextureView = gpuDevice.createTextureView(this.colorTexture);
        } else {
            throw new IllegalArgumentException("Window " + i + "x" + j + " size out of bounds (max. size: " + k + ")");
        }
    }

    @Override
    public GpuTexture getColorTexture() {
        return Renderer.getInstance().getMainPass().getColorAttachment();
    }

    @Override
    public GpuTextureView getColorTextureView() {
        return Renderer.getInstance().getMainPass().getColorAttachmentView();
    }

    @Override
    public GpuTexture getDepthTexture() {
        return Renderer.getInstance().getMainPass().getDepthAttachment();
    }

    @Override
    public GpuTextureView getDepthTextureView() {
        // Only the scaled world target overrides the depth view; otherwise keep the vanilla behaviour
        GpuTextureView worldDepthView = Renderer.getInstance().getMainPass().getDepthAttachmentView();
        return worldDepthView != null ? worldDepthView : super.getDepthTextureView();
    }
}
