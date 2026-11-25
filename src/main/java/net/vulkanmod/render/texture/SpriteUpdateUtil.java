package net.vulkanmod.render.texture;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * support flushing sprite layout transitions directly on a caller-provided command buffer for inline macOS image uploads.
 */

import dev.voolkan.compat.MacPerfOpts;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashSet;
import java.util.Set;

public abstract class SpriteUpdateUtil {

    private static boolean doUpload = true;
    private static final Set<VulkanImage> transitionedLayouts = new HashSet<>();

    public static void setDoUpload(boolean b) {
        doUpload = b;
    }

    public static boolean doUploadFrame() {
        return doUpload;
    }

    public static void addTransitionedLayout(VulkanImage image) {
        transitionedLayouts.add(image);
    }

    public static boolean containsTransitionedLayout(VulkanImage image) {
        return transitionedLayouts.contains(image);
    }

    public static void transitionLayouts() {
        if (transitionedLayouts.isEmpty()) {
            return;
        }

        if (MacPerfOpts.inlineImageUploads()
                && Renderer.isRecording()
                && Renderer.getInstance().getBoundRenderPass() == null
                && ImageUploadHelper.INSTANCE.getCommandBuffer() == null) {
            transitionLayoutsOn(Renderer.getCommandBuffer());
            return;
        }

        if (MacPerfOpts.inlineImageUploads() && Renderer.isRecording()) {
            for (VulkanImage image : transitionedLayouts) {
                if (ImageUploadHelper.INSTANCE.hasPendingInlineUpload(image.getId())) {
                    Renderer.getInstance().flushCmds();
                    break;
                }
            }
        }

        VkCommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer().handle;
        transitionLayoutsOn(commandBuffer);
    }

    public static void transitionLayoutsOn(VkCommandBuffer commandBuffer) {
        if (transitionedLayouts.isEmpty()) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (VulkanImage image : transitionedLayouts) {
                if (image.getId() != 0L) {
                    image.readOnlyLayout(stack, commandBuffer);
                }
            }
        }
        transitionedLayouts.clear();
    }

    /**
     * Transitions only images with an unsubmitted inline upload on {@code commandBuffer}, leaving
     * out-of-band {@link ImageUploadHelper} uploads in {@code transitionedLayouts} so they are
     * transitioned on {@code ImageUploadHelper}'s own command buffer before submit.
     */
    public static void transitionInlineLayoutsOn(VkCommandBuffer commandBuffer) {
        if (transitionedLayouts.isEmpty()) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var it = transitionedLayouts.iterator();
            while (it.hasNext()) {
                VulkanImage image = it.next();
                if (ImageUploadHelper.INSTANCE.hasPendingInlineUpload(image.getId())) {
                    if (image.getId() != 0L) {
                        image.readOnlyLayout(stack, commandBuffer);
                    }
                    it.remove();
                }
            }
        }
    }
}
