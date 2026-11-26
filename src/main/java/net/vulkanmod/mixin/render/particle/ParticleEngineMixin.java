/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package net.vulkanmod.mixin.render.particle;

import dev.voolkan.render.ParticleCap;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {

    @Inject(method = "extract", at = @At("HEAD"))
    private void onExtractHead(ParticlesRenderState renderState, Frustum frustum, Camera camera, float partialTick, CallbackInfo ci) {
        ParticleCap.beginFrame();
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void onExtractReturn(ParticlesRenderState renderState, Frustum frustum, Camera camera, float partialTick, CallbackInfo ci) {
        ParticleCap.endFrame();
    }
}
