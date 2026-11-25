package net.vulkanmod.render.texture;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * track inline-uploaded texture mip levels on macOS/Apple Silicon and flush their read-only layout transitions on the main command buffer.
 */

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

    final Queue queue;
    private CommandPool.CommandBuffer currentCmdBuffer;
    private final ObjectArrayList<VulkanImage> pendingInlineImages = new ObjectArrayList<>();
    private final IntArrayList pendingInlineMips = new IntArrayList();
    private final LongOpenHashSet inlineUploadedImages = new LongOpenHashSet();

    public ImageUploadHelper() {
        queue = DeviceManager.getGraphicsQueue();
    }

    public void addPendingInlineTransition(VulkanImage image, int mipLevel) {
        this.inlineUploadedImages.add(image.getId());
        int size = this.pendingInlineImages.size();
        if (size > 0 && this.pendingInlineImages.get(size - 1) == image && this.pendingInlineMips.getInt(size - 1) == mipLevel) {
            return;
        }
        this.pendingInlineImages.add(image);
        this.pendingInlineMips.add(mipLevel);
    }

    /** Returns true if {@code imageId} received an unsubmitted inline upload on the current main command buffer. */
    public boolean hasPendingInlineUpload(long imageId) {
        return imageId != 0L && !this.inlineUploadedImages.isEmpty() && this.inlineUploadedImages.contains(imageId);
    }

    /** Clears the unsubmitted inline-upload tracking set after the main command buffer is submitted or reset. */
    public void onMainCmdBufferSubmitted() {
        this.inlineUploadedImages.clear();
    }

    public void flushInlineTransitions(VkCommandBuffer commandBuffer) {
        SpriteUpdateUtil.transitionLayoutsOn(commandBuffer);
        int size = this.pendingInlineImages.size();
        if (size == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < size; i++) {
                VulkanImage image = this.pendingInlineImages.get(i);
                int mip = this.pendingInlineMips.getInt(i);
                if (image.getId() != 0L && mip >= 0 && mip < image.mipLevels
                        && image.getLevelLayout(mip) == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                    image.transitionLevelLayout(stack, commandBuffer, mip, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }
        }
        this.pendingInlineImages.clear();
        this.pendingInlineMips.clear();
    }

    public void clearInlineTransitions() {
        this.pendingInlineImages.clear();
        this.pendingInlineMips.clear();
        this.inlineUploadedImages.clear();
    }

    public void submitCommands() {
        this.submitCommands(true);
    }

    public void submitCommands(boolean useSemaphore) {
        if (this.currentCmdBuffer == null) {
            return;
        }

        SpriteUpdateUtil.transitionLayouts();

        queue.submitCommands(this.currentCmdBuffer, useSemaphore);
        Synchronization.INSTANCE.addCommandBuffer(this.currentCmdBuffer, useSemaphore);

        this.currentCmdBuffer = null;
    }

    public CommandPool.CommandBuffer getOrStartCommandBuffer() {
        if (this.currentCmdBuffer == null) {
            this.currentCmdBuffer = this.queue.beginCommands();
        }

        return this.currentCmdBuffer;
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        return this.currentCmdBuffer;
    }
}
