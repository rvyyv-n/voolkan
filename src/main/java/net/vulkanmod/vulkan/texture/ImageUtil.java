package net.vulkanmod.vulkan.texture;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * per-mip image layouts: transfers touch only their mip level, mipmap generation tracks each level, copyImageToImage added.
 */

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

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public abstract class ImageUtil {

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

            copyImageToBufferCmd(stack, commandBuffer.getHandle(), pStagingBuffer.get(0), image.getId(), 0, image.width,
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

    public static void copyImageToBuffer(VulkanImage image, Buffer buffer, int mipLevel,
                                         int width, int height, int xOffset, int yOffset,
                                         long bufferOffset, int bufferRowLength, int bufferImageHeight) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getLevelLayout(mipLevel);
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionLevelLayout(stack, commandBuffer.getHandle(), mipLevel, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            copyImageToBufferCmd(stack, commandBuffer.getHandle(), buffer.getId(), image.getId(), mipLevel, width,
                                 height, xOffset, yOffset, bufferOffset, bufferRowLength, bufferImageHeight);
            restoreLevelLayout(stack, commandBuffer.getHandle(), image, mipLevel, prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }

    public static void copyImageToBufferCmd(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long image,
                                            int mipLevel, int width, int height, int xOffset, int yOffset, long bufferOffset,
                                            int bufferRowLength, int bufferImageHeight) {
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

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage) {
        blitFramebuffer(srcImage, dstImage, VK_FILTER_NEAREST);
    }

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage, int filtering) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

            Renderer.getInstance().endRenderPass(commandBuffer);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            srcImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.srcOffsets(1, VkOffset3D.calloc(stack).set(srcImage.width, srcImage.height, 1));
            blit.srcSubresource()
                .aspectMask(srcImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.dstOffsets(1, VkOffset3D.calloc(stack).set(dstImage.width, dstImage.height, 1));
            blit.dstSubresource()
                .aspectMask(dstImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            vkCmdBlitImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, filtering);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    public static void blitFramebuffer(VulkanImage dstImage, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        // TODO: implement blit
//        blitFramebuffer(Renderer.getInstance().getSwapChain().getColorAttachment(), dstImage);
    }

    public static void generateMipmaps(VulkanImage image) {
        try (MemoryStack stack = stackPush()) {

            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            VkCommandBuffer cb = commandBuffer.getHandle();

            // Level 0 holds the source data; every level is a transfer destination first
            image.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            for (int level = 1; level < image.mipLevels; level++) {
                final int prevLevel = level - 1;

                image.transitionLevelLayout(stack, cb, prevLevel, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.srcOffsets(1, VkOffset3D.calloc(stack).set(Math.max(image.width >> prevLevel, 1), Math.max(image.height >> prevLevel, 1), 1));
                blit.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(prevLevel)
                    .baseArrayLayer(0)
                    .layerCount(1);

                blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.dstOffsets(1, VkOffset3D.calloc(stack).set(Math.max(image.width >> level, 1), Math.max(image.height >> level, 1), 1));
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0)
                    .layerCount(1);

                vkCmdBlitImage(cb, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
            }

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
