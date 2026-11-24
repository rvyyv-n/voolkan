package net.vulkanmod.vulkan.memory.buffer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * reset usedBytes when resizing a UniformBuffer so subsequent writes start at offset 0 of the new VkBuffer.
 */

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryType;

import static net.vulkanmod.vulkan.util.VUtil.align;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;

public class UniformBuffer extends Buffer {
    private final static int MIN_OFFSET_ALIGNMENT = (int) DeviceManager.deviceProperties.limits().minUniformBufferOffsetAlignment();

    public static int getAlignedSize(int uploadSize) {
        return align(uploadSize, MIN_OFFSET_ALIGNMENT);
    }

    public UniformBuffer(int size, MemoryType memoryType) {
        super("Uniform buffer", VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, memoryType);
        this.createBuffer(size);
    }

    public void checkCapacity(int size) {
        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + size) * 2);
            this.usedBytes = 0;
        }
    }

    public void updateOffset(int alignedSize) {
        usedBytes += alignedSize;
    }

    public long getPointer() {
        return this.dataPtr + usedBytes;
    }
}
