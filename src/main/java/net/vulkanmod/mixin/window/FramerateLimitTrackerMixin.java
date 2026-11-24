/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package net.vulkanmod.mixin.window;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {

    @Unique
    private static final int HIDDEN_FLOOR_FPS = 10;
    @Unique
    private static final long WINDOW_STATE_CACHE_NS = 1_000_000L; // 1 ms: at most once per frame

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private long voolkan$lastQueryNs = 0L;
    @Unique
    private boolean voolkan$focused = true;
    @Unique
    private boolean voolkan$visible = true;

    @Inject(method = "getFramerateLimit()I", at = @At("RETURN"), cancellable = true, require = 1)
    private void voolkan$applyBackgroundCap(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValueI();
        int limit = original;

        int backgroundFps = Initializer.CONFIG.backgroundFps;
        if (backgroundFps > 0) {
            Window window = this.minecraft.getWindow();
            if (window != null) {
                voolkan$refreshWindowState(window);
                if (window.isIconified() || !this.voolkan$visible) {
                    limit = Math.min(limit, Math.min(backgroundFps, HIDDEN_FLOOR_FPS));
                } else if (!this.voolkan$focused) {
                    limit = Math.min(limit, backgroundFps);
                }
            }
        }

        if (limit != original) {
            cir.setReturnValue(limit);
        }
    }

    @Unique
    private void voolkan$refreshWindowState(Window window) {
        long now = System.nanoTime();
        if (now - this.voolkan$lastQueryNs < WINDOW_STATE_CACHE_NS && this.voolkan$lastQueryNs != 0L) {
            return;
        }
        this.voolkan$lastQueryNs = now;

        long handle = window.handle();
        if (handle == 0L) {
            this.voolkan$focused = true;
            this.voolkan$visible = true;
            return;
        }

        this.voolkan$focused = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) != GLFW.GLFW_FALSE;
        this.voolkan$visible = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) != GLFW.GLFW_FALSE;
    }
}
