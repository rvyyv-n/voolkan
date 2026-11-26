package net.vulkanmod.mixin.compatibility;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * expose PostPass pipeline, output target, and inputs for Apple TBDR post-chain optimization and warm-up.
 */

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(PostPass.class)
public interface PostPassAccessor {
    @Accessor("pipeline")
    RenderPipeline voolkan$getPipeline();

    @Accessor("outputTargetId")
    Identifier voolkan$getOutputTargetId();

    @Accessor("inputs")
    List<PostPass.Input> voolkan$getInputs();
}
