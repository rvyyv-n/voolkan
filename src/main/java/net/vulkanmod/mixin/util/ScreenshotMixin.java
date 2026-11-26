package net.vulkanmod.mixin.util;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * carries upstream fixes cherry-picked for v1.5 (screenshot sync).
 */

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.vulkanmod.vulkan.util.ScreenshotUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(Screenshot.class)
public class ScreenshotMixin {

    @Inject(method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V", at = @At("HEAD"), cancellable = true)
    private static void takeScreenshot(RenderTarget renderTarget, int mipLevel, Consumer<NativeImage> consumer,
                                       CallbackInfo ci) {
        ScreenshotUtil.takeScreenshot(renderTarget, mipLevel, consumer);
        ci.cancel();
    }

}
