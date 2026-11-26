package net.vulkanmod.mixin.compatibility;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * omit unused depth attachments on PostChain internal targets (such as menu blur's swap target) on Apple TBDR GPUs.
 */

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.voolkan.compat.MacPerfOpts;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(PostChain.class)
public abstract class PostChainMixin {
    @Shadow @Final private List<PostPass> passes;

    @ModifyArg(
            method = "addToFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IIZI)V"
            ),
            index = 2
    )
    private boolean voolkan$omitUnusedInternalDepth(boolean useDepth) {
        if (!useDepth || !MacPerfOpts.menuBlurOpts()) {
            return useDepth;
        }
        return !voolkan$allPassesSkipDepth();
    }

    @Unique
    private boolean voolkan$allPassesSkipDepth() {
        if (this.passes == null || this.passes.isEmpty()) {
            return false;
        }
        for (PostPass pass : this.passes) {
            PostPassAccessor accessor = (PostPassAccessor) (Object) pass;
            RenderPipeline pipeline = accessor.voolkan$getPipeline();
            if (pipeline == null
                    || pipeline.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST
                    || pipeline.isWriteDepth()) {
                return false;
            }
            for (PostPass.Input input : accessor.voolkan$getInputs()) {
                if (input instanceof PostPass.TargetInput targetInput && targetInput.depthBuffer()) {
                    return false;
                }
            }
        }
        return true;
    }
}
