package net.vulkanmod.mixin.render.frame;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * low-latency wait on the previous frame's fence before input is polled;
 * precise frame limiter replacing vanilla limitDisplayFPS pacing.
 */

import com.mojang.blaze3d.systems.RenderSystem;
import dev.voolkan.latency.FrameLimiter;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {

    @Shadow
    private static void pollEvents() {
        throw new AssertionError();
    }

    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), remap = false)
    private static void endFrame(long window) {
        Renderer.getInstance().endFrame();

        // flipFrame polls input right after this call
        Renderer.getInstance().waitForLastFrame();
    }

    @Inject(method = "limitDisplayFPS(I)V", at = @At("HEAD"), cancellable = true, require = 1)
    private static void voolkan$limitDisplayFPS(int fps, CallbackInfo ci) {
        if (!FrameLimiter.isEnabled()) {
            return;
        }

        FrameLimiter.limit(fps, RenderSystemMixin::pollEvents);
        ci.cancel();
    }
}
