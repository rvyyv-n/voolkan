package net.vulkanmod.mixin.compatibility;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * route opaque color-only post-processing passes to depth-less LOAD_OP_DONT_CARE passes on Apple TBDR GPUs.
 */

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.*;
import dev.voolkan.compat.MacPerfOpts;
import net.minecraft.client.renderer.PostPass;
import net.vulkanmod.render.engine.*;
import net.vulkanmod.vulkan.Renderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

@Mixin(PostPass.class)
public abstract class PostPassM {
    @Shadow @Final private List<PostPass.Input> inputs;
    @Shadow @Final private RenderPipeline pipeline;

    @Inject(method = "method_67884", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createCommandEncoder()Lcom/mojang/blaze3d/systems/CommandEncoder;"))
    private void transitionLayouts(ResourceHandle resourceHandle, GpuBufferSlice gpuBufferSlice, Map map,
                                   CallbackInfo ci) {
        Renderer.getInstance().endRenderPass();

        for (var input : this.inputs) {
            VkGpuTexture gpuTexture = (VkGpuTexture) input.texture(map).texture();

            if (gpuTexture.needsClear()) {
                gpuTexture.getFbo(null).bind();
                if (MacPerfOpts.menuBlurOpts()) {
                    Renderer.getInstance().endRenderPass();
                }
            }

            gpuTexture.getVulkanImage().readOnlyLayout();
        }
    }

    @Redirect(
            method = "method_67884",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass voolkan$createPostRenderPass(
            CommandEncoder encoder,
            Supplier<String> label,
            GpuTextureView colorView,
            OptionalInt clearColor,
            @Nullable GpuTextureView depthView,
            OptionalDouble clearDepth) {
        if (MacPerfOpts.menuBlurOpts()
                && encoder instanceof VkCommandEncoder vkEncoder
                && clearColor.isEmpty()
                && clearDepth.isEmpty()
                && voolkan$isOpaqueColorOnlyPass()) {
            return vkEncoder.createOpaqueColorOnlyPostPass(label, colorView);
        }
        return encoder.createRenderPass(label, colorView, clearColor, depthView, clearDepth);
    }

    @Unique
    private boolean voolkan$isOpaqueColorOnlyPass() {
        return this.pipeline != null
                && this.pipeline.getBlendFunction().isEmpty()
                && this.pipeline.getDepthTestFunction() == DepthTestFunction.NO_DEPTH_TEST
                && !this.pipeline.isWriteDepth()
                && this.pipeline.isWriteColor()
                && this.pipeline.isWriteAlpha();
    }

}
