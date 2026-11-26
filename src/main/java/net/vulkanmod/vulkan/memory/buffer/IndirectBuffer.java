package net.vulkanmod.vulkan.memory.buffer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * add zero-allocation raw-pointer copy overload and guarantee resize capacity covers the incoming batch.
 */

import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.TransferQueue;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

public class IndirectBuffer extends Buffer {
    CommandPool.CommandBuffer commandBuffer;

    public IndirectBuffer(int size, MemoryType type) {
        super("Indirect buffer", VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, type);
        this.createBuffer(size);
    }

    public void recordCopyCmd(ByteBuffer byteBuffer) {
        int size = byteBuffer.remaining();

        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer(Math.max((long) (this.bufferSize * 1.5f), size));
            this.usedBytes = 0;
        }

        if (this.type.mappable()) {
            this.type.copyToBuffer(this, byteBuffer, size, 0, this.usedBytes);
        } else {
            if (commandBuffer == null)
                commandBuffer = DeviceManager.getTransferQueue().beginCommands();

            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
            stagingBuffer.copyBuffer(size, byteBuffer);

            TransferQueue.uploadBufferCmd(commandBuffer.getHandle(), stagingBuffer.id, stagingBuffer.offset, this.getId(), this.getUsedBytes(), size);
        }

        offset = usedBytes;
        usedBytes += size;
    }

    public void recordCopyCmd(long srcPtr, int size) {
        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer(Math.max((long) (this.bufferSize * 1.5f), size));
            this.usedBytes = 0;
        }

        if (this.type.mappable()) {
            MemoryUtil.memCopy(srcPtr, this.dataPtr + this.usedBytes, size);
        } else {
            if (commandBuffer == null)
                commandBuffer = DeviceManager.getTransferQueue().beginCommands();

            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
            stagingBuffer.copyBuffer(size, MemoryUtil.memByteBuffer(srcPtr, size));

            TransferQueue.uploadBufferCmd(commandBuffer.getHandle(), stagingBuffer.id, stagingBuffer.offset, this.getId(), this.getUsedBytes(), size);
        }

        offset = usedBytes;
        usedBytes += size;
    }

    public void submitUploads() {
        if (commandBuffer == null)
            return;

        DeviceManager.getTransferQueue().submitCommands(commandBuffer);
        Synchronization.INSTANCE.addCommandBuffer(commandBuffer);
        commandBuffer = null;
    }

}
