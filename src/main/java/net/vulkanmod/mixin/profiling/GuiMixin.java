package net.vulkanmod.mixin.profiling;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * render unobtrusive pipeline warm-up progress overlay on HUD when warm-up completes after world join.
 */

import dev.voolkan.gui.WarmupProgressOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.vulkanmod.render.profiling.ProfilerOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {

    @Shadow @Final private DebugScreenOverlay debugOverlay;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void createProfilerOverlay(Minecraft minecraft, CallbackInfo ci) {
        ProfilerOverlay.createInstance(minecraft);
    }

    @Inject(method = "render", at = @At(value = "RETURN"))
    private void renderProfilerOverlay(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        boolean debugVisible = this.debugOverlay.showDebugScreen();
        if (ProfilerOverlay.shouldRender && !debugVisible)
            ProfilerOverlay.INSTANCE.render(guiGraphics);
        WarmupProgressOverlay.renderInGameHud(guiGraphics, debugVisible);
    }
}
