package net.vulkanmod.vulkan.texture;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * per-mip image layouts: transfers touch only their mip level, mipmap generation tracks each level, copyImageToImage added; check format features before blitting and implement glBlitFramebuffer fallback.
 */

import dev.voolkan.compat.FormatCaps;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public abstract class ImageUtil {
    private static final Set<Long> WARNED_UNSUPPORTED_BLITS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> WARNED_UNSUPPORTED_MIPS = ConcurrentHashMap.newKeySet();

    public static void warnUnsupportedBlitOnce(int srcFormat, int dstFormat) {
        long key = ((long) srcFormat << 32) | (dstFormat & 0xFFFFFFFFL);
        if (WARNED_UNSUPPORTED_BLITS.add(key)) {
            Initializer.LOGGER.warn("Skipping image blit: format {} -> {} does not support required blit features", srcFormat, dstFormat);
        }
    }

    public static void copyBufferToImageCmd(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer,
                                            long image, int arrayLayer,
                                            int mipLevel, int width, int height, int xOffset, int yOffset,
                                            int bufferOffset, int bufferRowLenght, int bufferImageHeight) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLenght);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(arrayLayer);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent(VkExtent3D.calloc(stack).set(width, height, 1));

        vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    public static void downloadTexture(VulkanImage image, long ptr) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getLevelLayout(0);
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionLevelLayout(stack, commandBuffer.getHandle(), 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            long imageSize = (long) image.width * image.height * image.formatSize;

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            PointerBuffer pStagingAllocation = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(imageSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                                     pStagingBuffer, pStagingAllocation);

            copyImageToBuffer(stack, commandBuffer.getHandle(), pStagingBuffer.get(0), image.getId(), 0, image.width,
                              image.height, 0, 0, 0, 0, 0);
            restoreLevelLayout(stack, commandBuffer.getHandle(), image, 0, prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);

            MemoryManager.MapAndCopy(pStagingAllocation.get(0),
                                     (data) -> VUtil.memcpy(data.getByteBuffer(0, (int) imageSize), ptr));

            MemoryManager.freeBuffer(pStagingBuffer.get(0), pStagingAllocation.get(0));
        }
    }

    /** Puts a level back in the layout it had before a transfer; an UNDEFINED level stays in its transfer layout. */
    private static void restoreLevelLayout(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int level, int prevLayout) {
        if (prevLayout != VK_IMAGE_LAYOUT_UNDEFINED) {
            image.transitionLevelLayout(stack, commandBuffer, level, prevLayout);
        }
    }

    /**
     * Copies a region of one mip level between two images (same format). The destination level ends in
     * SHADER_READ_ONLY_OPTIMAL and the source level returns to its previous layout. Source and destination
     * must not be the same level of the same image.
     */
    public static void copyImageToImageCmd(MemoryStack stack, VkCommandBuffer commandBuffer,
                                           VulkanImage srcImage, VulkanImage dstImage, int mipLevel,
                                           int srcX, int srcY, int dstX, int dstY, int width, int height) {
        if (srcImage == dstImage) {
            throw new IllegalArgumentException("Cannot copy a texture level onto itself");
        }

        final int prevSrcLayout = srcImage.getLevelLayout(mipLevel);

        srcImage.transitionLevelLayout(stack, commandBuffer, mipLevel, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        dstImage.transitionLevelLayout(stack, commandBuffer, mipLevel, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().aspectMask(srcImage.aspect).mipLevel(mipLevel).baseArrayLayer(0).layerCount(1);
        region.srcOffset().set(srcX, srcY, 0);
        region.dstSubresource().aspectMask(dstImage.aspect).mipLevel(mipLevel).baseArrayLayer(0).layerCount(1);
        region.dstOffset().set(dstX, dstY, 0);
        region.extent().set(width, height, 1);

        vkCmdCopyImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        restoreLevelLayout(stack, commandBuffer, srcImage, mipLevel, prevSrcLayout);
        dstImage.transitionLevelLayout(stack, commandBuffer, mipLevel, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public static void copyImageToBuffer(Buffer buffer, VulkanImage image, int mipLevel,
                                         int width, int height, int xOffset, int yOffset,
                                         long bufferOffset, int bufferRowLength, int bufferImageHeight
    ) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getLevelLayout(mipLevel);
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionLevelLayout(stack, commandBuffer.getHandle(), mipLevel, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            copyImageToBuffer(stack, commandBuffer.getHandle(), buffer.getId(), image.getId(), mipLevel, width,
                              height, xOffset, yOffset, bufferOffset, bufferRowLength, bufferImageHeight);
            restoreLevelLayout(stack, commandBuffer.getHandle(), image, mipLevel, prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }

    public static void copyImageToBuffer(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long image,
                                         int mipLevel, int width, int height, int xOffset, int yOffset,
                                         long bufferOffset, int bufferRowLength, int bufferImageHeight
    ) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLength);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(0);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent().set(width, height, 1);

        vkCmdCopyImageToBuffer(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
    }

    /**
     * Blits a region of one mip level between two images after verifying format blit features.
     * Caller must have ended any active render pass on {@code commandBuffer}.
     */
    public static void blitImageToImageCmd(MemoryStack stack, VkCommandBuffer commandBuffer,
                                           VulkanImage srcImage, VulkanImage dstImage, int mipLevel,
                                           int srcX, int srcY, int dstX, int dstY, int width, int height,
                                           int filtering) {
        if (!FormatCaps.canBlit(srcImage.format, dstImage.format)) {
            warnUnsupportedBlitOnce(srcImage.format, dstImage.format);
            return;
        }

        final int effectiveFilter = FormatCaps.chooseBlitFilter(srcImage.format, filtering);
        final int prevSrcLayout = srcImage.getLevelLayout(mipLevel);

        srcImage.transitionLevelLayout(stack, commandBuffer, mipLevel, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        dstImage.transitionLevelLayout(stack, commandBuffer, mipLevel, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
        blit.srcOffsets(0, VkOffset3D.calloc(stack).set(srcX, srcY, 0));
        blit.srcOffsets(1, VkOffset3D.calloc(stack).set(srcX + width, srcY + height, 1));
        blit.srcSubresource()
            .aspectMask(srcImage.aspect)
            .mipLevel(mipLevel)
            .baseArrayLayer(0)
            .layerCount(1);

        blit.dstOffsets(0, VkOffset3D.calloc(stack).set(dstX, dstY, 0));
        blit.dstOffsets(1, VkOffset3D.calloc(stack).set(dstX + width, dstY + height, 1));
        blit.dstSubresource()
            .aspectMask(dstImage.aspect)
            .mipLevel(mipLevel)
            .baseArrayLayer(0)
            .layerCount(1);

        vkCmdBlitImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, effectiveFilter);

        restoreLevelLayout(stack, commandBuffer, srcImage, mipLevel, prevSrcLayout);
        dstImage.transitionLevelLayout(stack, commandBuffer, mipLevel, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage) {
        blitFramebuffer(srcImage, dstImage, VK_FILTER_NEAREST);
    }

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage, int filtering) {
        if (srcImage == null || dstImage == null) {
            return;
        }
        blitFramebuffer(srcImage, dstImage, 0, 0, srcImage.width, srcImage.height, 0, 0, dstImage.width, dstImage.height, filtering);
    }

    public static void blitFramebuffer(VulkanImage dstImage, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        VulkanImage srcImage = Renderer.getInstance().getSwapChain().getColorAttachment();
        blitFramebuffer(srcImage, dstImage, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, VK_FILTER_NEAREST);
    }

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage,
                                       int srcX0, int srcY0, int srcX1, int srcY1,
                                       int dstX0, int dstY0, int dstX1, int dstY1,
                                       int filtering
    ) {
        if (srcImage == null || dstImage == null) {
            return;
        }

        if (!FormatCaps.canBlit(srcImage.format, dstImage.format)) {
            int srcW = srcX1 - srcX0;
            int srcH = srcY1 - srcY0;
            int dstW = dstX1 - dstX0;
            int dstH = dstY1 - dstY0;
            if (srcImage != dstImage && srcImage.format == dstImage.format
                    && srcW > 0 && srcH > 0 && srcW == dstW && srcH == dstH) {
                try (MemoryStack stack = stackPush()) {
                    VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
                    Renderer.getInstance().endRenderPass(commandBuffer);
                    copyImageToImageCmd(stack, commandBuffer, srcImage, dstImage, 0, srcX0, srcY0, dstX0, dstY0, srcW, srcH);
                }
            } else {
                warnUnsupportedBlitOnce(srcImage.format, dstImage.format);
            }
            return;
        }

        final int effectiveFilter = FormatCaps.chooseBlitFilter(srcImage.format, filtering);

        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

            Renderer.getInstance().endRenderPass(commandBuffer);

            final int prevSrcLayout = srcImage.getLevelLayout(0);
            dstImage.transitionLevelLayout(stack, commandBuffer, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            srcImage.transitionLevelLayout(stack, commandBuffer, 0, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(srcX0, srcY0, 0));
            blit.srcOffsets(1, VkOffset3D.calloc(stack).set(srcX1, srcY1, 1));
            blit.srcSubresource()
                .aspectMask(srcImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(dstX0, dstY0, 0));
            blit.dstOffsets(1, VkOffset3D.calloc(stack).set(dstX1, dstY1, 1));
            blit.dstSubresource()
                .aspectMask(dstImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            vkCmdBlitImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, effectiveFilter);

            restoreLevelLayout(stack, commandBuffer, srcImage, 0, prevSrcLayout);
            dstImage.transitionLevelLayout(stack, commandBuffer, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    public static void generateMipmaps(VulkanImage image) {
        final boolean canBlit = FormatCaps.canBlit(image.format, image.format);
        if (!canBlit && WARNED_UNSUPPORTED_MIPS.add(image.format)) {
            Initializer.LOGGER.warn("Format {} does not support BLIT_SRC/BLIT_DST; skipping GPU mipmap generation", image.format);
        }

        try (MemoryStack stack = stackPush()) {
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            VkCommandBuffer cb = commandBuffer.getHandle();

            if (canBlit && image.mipLevels > 1) {
                final int filter = FormatCaps.chooseBlitFilter(image.format, VK_FILTER_LINEAR);

                // Level 0 holds the source data; every level is a transfer destination first
                image.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                for (int level = 1; level < image.mipLevels; level++) {
                    final int prevLevel = level - 1;

                    image.transitionLevelLayout(stack, cb, prevLevel, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

                    VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                    blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                    blit.srcOffsets(1, VkOffset3D.calloc(stack).set(Math.max(image.width >> prevLevel, 1), Math.max(image.height >> prevLevel, 1), 1));
                    blit.srcSubresource()
                        .aspectMask(image.aspect)
                        .mipLevel(prevLevel)
                        .baseArrayLayer(0)
                        .layerCount(1);

                    blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                    blit.dstOffsets(1, VkOffset3D.calloc(stack).set(Math.max(image.width >> level, 1), Math.max(image.height >> level, 1), 1));
                    blit.dstSubresource().aspectMask(image.aspect).mipLevel(level).baseArrayLayer(0)
                        .layerCount(1);

                    vkCmdBlitImage(cb, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                   image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, filter);
                }
            }

            // Leave every level in SHADER_READ_ONLY_OPTIMAL even when mip generation was skipped
            image.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);

            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }

    public static void imageTransferMemoryBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int baseLevel) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        barrier.oldLayout(image.getLevelLayout(baseLevel));
        barrier.newLayout(image.getLevelLayout(baseLevel));
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.image(image.getId());

        barrier.subresourceRange().baseMipLevel(baseLevel);
        barrier.subresourceRange().levelCount(1);
        barrier.subresourceRange().baseArrayLayer(0);
        barrier.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);

        barrier.subresourceRange().aspectMask(image.aspect);

        barrier.srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);
        barrier.dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);

        vkCmdPipelineBarrier(commandBuffer,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0,
                             null,
                             null,
                             barrier);
    }
}
