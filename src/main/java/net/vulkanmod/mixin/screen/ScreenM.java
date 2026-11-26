package net.vulkanmod.mixin.screen;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * skip redundant depth clear during GUI extraction on Apple TBDR GPUs.
 */

import dev.voolkan.compat.MacPerfOpts;
import net.minecraft.client.gui.screens.Screen;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenM {

    @Inject(method = "renderBlurredBackground", at = @At("RETURN"))
    private void clearDepth(CallbackInfo ci) {
        if (MacPerfOpts.menuBlurOpts()) {
            return;
        }
        // Workaround to fix hardcoded z value on PostPass blit shader,
        // that conflicts with Vulkan depth range [0.0, 1.0]
        Renderer.clearAttachments(256);
    }
}
