package net.vulkanmod.mixin.render.particle;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * use Mth.floor for accurate negative-coordinate section lookup in particle culling.
 */

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = QuadParticleGroup.class, priority = 999)
public class QuadParticleGroupM {

    @WrapOperation(method = "extractRenderState",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean particleWithinSections(Frustum instance, double x, double y, double z, Operation<Boolean> original) {
        return !cull(WorldRenderer.getInstance(), x, y, z) && instance.pointInFrustum(x, y, z);
    }

    @Unique
    private static boolean cull(WorldRenderer worldRenderer, double x, double y, double z) {
        RenderSection section = worldRenderer.getSectionGrid().getSectionAtBlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        return section != null && section.getLastFrame() != worldRenderer.getLastFrame();
    }
}
