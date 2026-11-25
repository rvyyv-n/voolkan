package net.vulkanmod.mixin.render;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * pre-size Dynamic Transforms UBO capacity on macOS/Apple Silicon to prevent world-join ring-buffer resize cascades.
 */

import dev.voolkan.compat.MacPerfOpts;
import net.minecraft.client.renderer.DynamicUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(DynamicUniforms.class)
public abstract class DynamicUniformsMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/DynamicUniformStorage;<init>(Ljava/lang/String;II)V",
                    ordinal = 0
            ),
            index = 2
    )
    private int voolkan$macInitialTransformsCapacity(int initialCapacity) {
        if (MacPerfOpts.drawCoalescing() && initialCapacity < 64) {
            return 64;
        }
        return initialCapacity;
    }
}
