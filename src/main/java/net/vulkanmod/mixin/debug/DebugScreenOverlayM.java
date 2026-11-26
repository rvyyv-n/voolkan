/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package net.vulkanmod.mixin.debug;

import dev.voolkan.telemetry.FrameTimeTracker;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayM {

    @Inject(method = "logFrameDuration(J)V", at = @At("HEAD"))
    private void onLogFrameDuration(long durationNs, CallbackInfo ci) {
        FrameTimeTracker.recordFrame(durationNs);
    }
}
