package net.vulkanmod.mixin.render;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * enter and exit world-resolution scaling around renderLevel and world post-processing.
 */

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow @Final private Camera mainCamera;

    @Inject(method = "getDepthFar", at = @At("HEAD"), cancellable = true)
    public void getInfiniteDepthFar(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(Float.POSITIVE_INFINITY);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"),
            require = 1
    )
    private void voolkan$beginWorldScale(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        Renderer.getInstance().getMainPass().beginWorld(deltaTracker, this.mainCamera);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"),
            require = 1
    )
    private void voolkan$endWorldScale(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        Renderer.getInstance().getMainPass().endWorld();
    }

}
