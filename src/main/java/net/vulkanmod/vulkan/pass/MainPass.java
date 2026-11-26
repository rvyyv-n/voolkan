package net.vulkanmod.vulkan.pass;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * expose the main render pass for pipeline creation fallback; add world-resolution scaling hooks, depth attachment view, and clear-folding rebindMainTarget overload.
 */

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);

    void cleanUp();

    void onResize();

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    /**
     * Rebinds the main target and, when supported (tile GPUs before the swapchain pass has started
     * this frame), folds the requested attachment clears into {@code LOAD_OP_CLEAR}.
     *
     * @return {@code true} if the requested clear(s) were performed natively by the render pass
     */
    default boolean rebindMainTarget(boolean clearColor, boolean clearDepth) {
        rebindMainTarget();
        return false;
    }

    /** Marks a deferred initial colour+depth clear at frame start on tile GPUs without opening a pass. */
    default void deferInitialClear() {}

    /**
     * Returns {@code true} (and consumes the clean flag) when the active main pass already holds a
     * pristine {@code 1.0} depth buffer from {@code LOAD_OP_CLEAR}, avoiding a redundant fullscreen depth clear.
     */
    default boolean consumeCleanDepthAtOne() {
        return false;
    }

    /** Marks the main target's depth buffer as modified so subsequent depth clears cannot be elided. */
    default void invalidateCleanDepth() {}

    /**
     * Rebinds the main target using a color-only {@code LOAD_OP_DONT_CARE} render pass (no depth
     * attachment) for opaque fullscreen post-processing passes on Apple TBDR GPUs.
     *
     * @return {@code true} if the color-only pass was bound; {@code false} if the caller should fall back
     */
    default boolean rebindMainTargetColorOnlyDiscard() {
        return false;
    }

    /** Returns the color-only discard pass for pipeline warm-up, or {@code null} when unused. */
    default RenderPass getColorOnlyDiscardPass() {
        return null;
    }

    default void bindAsTexture() {}

    default void beginWorld(DeltaTracker deltaTracker, Camera camera) {}

    default void endWorld() {}

    default void resetWorld() {}

    default Framebuffer getMainFramebuffer() {
        return null;
    }

    default RenderPass getMainRenderPass() {
        return null;
    }

    default GpuTexture getColorAttachment() {
        return null;
    }

    default GpuTextureView getColorAttachmentView() {
        return null;
    }

    default GpuTexture getDepthAttachment() {
        return null;
    }

    default GpuTextureView getDepthAttachmentView() {
        return null;
    }

    /** True while the world is drawn into the scaled offscreen target (between beginWorld and endWorld). */
    default boolean isWorldActive() {
        return false;
    }

}
