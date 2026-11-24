package net.vulkanmod.vulkan.memory;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * detect unified memory heaps and log chosen memory heap sizes at startup.
 */

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkMemoryType;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class MemoryTypes {
    public static MemoryType GPU_MEM;
    public static MemoryType HOST_MEM;
    /** Host-visible coherent memory that prefers device-local BAR (256 MB on GCN1) with automatic host fallback. */
    public static MemoryType BAR_MEM;
    /** Host-visible coherent memory that prefers CPU-cached pages for fast GPU-to-CPU readbacks. */
    public static MemoryType READBACK_MEM;

    public static void createMemoryTypes() {

        for (int i = 0; i < DeviceManager.memoryProperties.memoryTypeCount(); ++i) {
            VkMemoryType memoryType = DeviceManager.memoryProperties.memoryTypes(i);
            VkMemoryHeap heap = DeviceManager.memoryProperties.memoryHeaps(memoryType.heapIndex());
            int propertyFlags = memoryType.propertyFlags();

            if (GPU_MEM == null && propertyFlags == VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                GPU_MEM = new DeviceLocalMemory(memoryType, heap);
            }

            if (HOST_MEM == null && propertyFlags == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                HOST_MEM = new HostCoherentMemory(memoryType, heap);
            }
        }

        if (GPU_MEM == null || HOST_MEM == null) {
            // Could not find 1 or more MemoryTypes, need to use fallback
            for (int i = 0; i < DeviceManager.memoryProperties.memoryTypeCount(); ++i) {
                VkMemoryType memoryType = DeviceManager.memoryProperties.memoryTypes(i);
                VkMemoryHeap heap = DeviceManager.memoryProperties.memoryHeaps(memoryType.heapIndex());

                // GPU mappable memory
                if (GPU_MEM == null && (memoryType.propertyFlags() & (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) == (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
                    GPU_MEM = new DeviceMappableMemory(memoryType, heap);
                }

                if (HOST_MEM == null && (memoryType.propertyFlags() & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                    HOST_MEM = new HostLocalFallbackMemory(memoryType, heap);
                }

                if (GPU_MEM != null && HOST_MEM != null) {
                    break;
                }
            }
        }

        if (GPU_MEM == null) {
            // Could not find device memory, fallback to host memory
            GPU_MEM = HOST_MEM;
        }

        BAR_MEM = new HostPreferredMemory(
                HOST_MEM.vkMemoryType, HOST_MEM.vkMemoryHeap,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        READBACK_MEM = new HostPreferredMemory(
                HOST_MEM.vkMemoryType, HOST_MEM.vkMemoryHeap,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                VK_MEMORY_PROPERTY_HOST_CACHED_BIT);

        logChosenHeaps();
    }

    public static boolean isUnifiedMemory() {
        if (GPU_MEM == null || HOST_MEM == null || DeviceManager.memoryProperties == null) {
            return false;
        }
        int gpuHeapIdx = GPU_MEM.vkMemoryType.heapIndex();
        if (gpuHeapIdx != HOST_MEM.vkMemoryType.heapIndex()) {
            return false;
        }
        return heapHasProperty(gpuHeapIdx, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                && heapHasProperty(gpuHeapIdx, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    }

    private static boolean heapHasProperty(int heapIndex, int flag) {
        int count = DeviceManager.memoryProperties.memoryTypeCount();
        for (int i = 0; i < count; i++) {
            VkMemoryType type = DeviceManager.memoryProperties.memoryTypes(i);
            if (type.heapIndex() == heapIndex && (type.propertyFlags() & flag) != 0) {
                return true;
            }
        }
        return false;
    }

    private static void logChosenHeaps() {
        if (GPU_MEM == null) {
            return;
        }
        int gpuHeapIdx = GPU_MEM.vkMemoryType.heapIndex();
        long gpuHeapMB = GPU_MEM.vkMemoryHeap.size() / MemoryManager.BYTES_IN_MB;
        String budgetStatus = DeviceManager.memoryBudgetSupported ? "enabled" : "not supported";

        if (isUnifiedMemory()) {
            Initializer.LOGGER.info("Memory heap: unified {} MB (heap {}, VK_EXT_memory_budget: {})",
                    gpuHeapMB, gpuHeapIdx, budgetStatus);
        } else if (HOST_MEM != null && HOST_MEM.vkMemoryType.heapIndex() != gpuHeapIdx) {
            int hostHeapIdx = HOST_MEM.vkMemoryType.heapIndex();
            long hostHeapMB = HOST_MEM.vkMemoryHeap.size() / MemoryManager.BYTES_IN_MB;
            Initializer.LOGGER.info("Memory heaps: VRAM {} MB (heap {}), shared {} MB (heap {}), VK_EXT_memory_budget: {}",
                    gpuHeapMB, gpuHeapIdx, hostHeapMB, hostHeapIdx, budgetStatus);
        } else {
            Initializer.LOGGER.info("Memory heap: {} MB (heap {}, VK_EXT_memory_budget: {})",
                    gpuHeapMB, gpuHeapIdx, budgetStatus);
        }
    }

    public static class DeviceLocalMemory extends MemoryType {

        DeviceLocalMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.DEVICE_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false);
        }

        @Override
        public void copyToBuffer(Buffer buffer, ByteBuffer src, long size, long srcOffset, long dstOffset) {
            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
            stagingBuffer.copyBuffer((int) size, src);

            DeviceManager.getTransferQueue().copyBufferCmd(stagingBuffer.getId(), stagingBuffer.getOffset(), buffer.getId(), dstOffset, size);
        }

        @Override
        public void copyFromBuffer(Buffer buffer, long bufferSize, ByteBuffer byteBuffer) {
            // TODO
        }

        public long copyBuffer(Buffer src, Buffer dst) {
            if (dst.getBufferSize() < src.getBufferSize()) {
                throw new IllegalArgumentException("dst size is less than src size.");
            }

            return DeviceManager.getTransferQueue().copyBufferCmd(src.getId(), 0, dst.getId(), 0, src.getBufferSize());
        }

        @Override
        public boolean mappable() {
            return false;
        }
    }

    static abstract class MappableMemory extends MemoryType {

        MappableMemory(Type type, VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(type, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void copyToBuffer(Buffer buffer, ByteBuffer src, long size, long srcOffset, long dstOffset) {
            VUtil.memcpy(src, buffer, size, srcOffset, dstOffset);
        }

        @Override
        public void copyFromBuffer(Buffer buffer, long size, ByteBuffer byteBuffer) {
            MemoryUtil.memCopy(buffer.getDataPtr(), MemoryUtil.memAddress(byteBuffer), size);
        }

        @Override
        public boolean mappable() {
            return true;
        }
    }

    static class HostCoherentMemory extends MappableMemory {

        HostCoherentMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }

    }

    static class HostLocalFallbackMemory extends MappableMemory {

        HostLocalFallbackMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
    }

    static class HostPreferredMemory extends MappableMemory {
        private final int requiredFlags;
        private final int preferredFlags;

        HostPreferredMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap, int requiredFlags, int preferredFlags) {
            super(Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
            this.requiredFlags = requiredFlags;
            this.preferredFlags = preferredFlags;
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    this.requiredFlags, this.preferredFlags, true);
        }
    }

    static class DeviceMappableMemory extends MappableMemory {

        DeviceMappableMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.DEVICE_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true);
        }
    }
}
