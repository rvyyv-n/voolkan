package net.vulkanmod.vulkan.memory;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * detect unified memory heaps, log chosen memory heap sizes at startup, and add unified-memory direct-write fast path on Apple/MoltenVK/macOS.
 */

import net.vulkanmod.Initializer;
import net.vulkanmod.config.Platform;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
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
    /** Device-local, host-visible unified memory for zero-staging direct writes (Apple Silicon / MoltenVK / macOS). */
    public static MemoryType UNIFIED_MEM;
    public static boolean unifiedDirectWriteActive = false;
    private static boolean unifiedDirectWriteCoherent = false;

    public static void createMemoryTypes() {
        UNIFIED_MEM = null;
        unifiedDirectWriteActive = false;
        unifiedDirectWriteCoherent = false;

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

        initUnifiedDirectWrite();

        logChosenHeaps();
    }

    private static void initUnifiedDirectWrite() {
        boolean disabled = Boolean.getBoolean("voolkan.disableUnifiedDirectWrite");
        if (disabled || !isUnifiedMemory()) {
            return;
        }
        boolean macOrApple = Platform.isMacOS()
                || (DeviceManager.device != null && (DeviceManager.device.isApple() || DeviceManager.device.isMoltenVK()));
        if (!macOrApple) {
            return;
        }

        int unifiedHeapIdx = GPU_MEM.vkMemoryType.heapIndex();
        VkMemoryType coherentType = null;
        VkMemoryType nonCoherentType = null;
        int reqFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
        int count = DeviceManager.memoryProperties.memoryTypeCount();
        for (int i = 0; i < count; i++) {
            VkMemoryType candidate = DeviceManager.memoryProperties.memoryTypes(i);
            if (candidate.heapIndex() != unifiedHeapIdx) {
                continue;
            }
            int flags = candidate.propertyFlags();
            if ((flags & reqFlags) == reqFlags) {
                if ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) {
                    if (coherentType == null) {
                        coherentType = candidate;
                    }
                } else if (nonCoherentType == null) {
                    nonCoherentType = candidate;
                }
            }
        }

        if (coherentType != null) {
            UNIFIED_MEM = new UnifiedDirectMemory(coherentType, GPU_MEM.vkMemoryHeap, true);
            unifiedDirectWriteActive = true;
            unifiedDirectWriteCoherent = true;
        } else if (nonCoherentType != null) {
            UNIFIED_MEM = new UnifiedDirectMemory(nonCoherentType, GPU_MEM.vkMemoryHeap, false);
            unifiedDirectWriteActive = true;
            unifiedDirectWriteCoherent = false;
        }
    }

    public static MemoryType getUnifiedOrGpuMem() {
        return unifiedDirectWriteActive && UNIFIED_MEM != null ? UNIFIED_MEM : GPU_MEM;
    }

    public static boolean isUnifiedDirectWriteCoherent() {
        return unifiedDirectWriteCoherent;
    }

    /**
     * Direct CPU-to-CPU copy between two persistently mapped buffers, flushing/invalidating VMA
     * allocations when either side uses non-coherent unified memory.
     */
    public static void copyMappableBuffer(Buffer src, long srcOffset, Buffer dst, long dstOffset, long size) {
        if (src.type instanceof UnifiedDirectMemory uSrc && !uSrc.isCoherent()) {
            Vma.vmaInvalidateAllocation(Vulkan.getAllocator(), src.getAllocation(), srcOffset, size);
        }
        MemoryUtil.memCopy(src.getDataPtr() + srcOffset, dst.getDataPtr() + dstOffset, size);
        if (dst.type instanceof UnifiedDirectMemory uDst && !uDst.isCoherent()) {
            Vma.vmaFlushAllocation(Vulkan.getAllocator(), dst.getAllocation(), dstOffset, size);
        }
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

    static class UnifiedDirectMemory extends MappableMemory {
        private final boolean coherent;

        UnifiedDirectMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap, boolean coherent) {
            super(Type.DEVICE_LOCAL, vkMemoryType, vkMemoryHeap);
            this.coherent = coherent;
        }

        boolean isCoherent() {
            return this.coherent;
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            int requiredFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                    | (this.coherent ? VK_MEMORY_PROPERTY_HOST_COHERENT_BIT : 0);
            int preferredFlags = this.coherent
                    ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT
                    : VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    requiredFlags, preferredFlags, true);
        }

        @Override
        public void copyToBuffer(Buffer buffer, ByteBuffer src, long size, long srcOffset, long dstOffset) {
            super.copyToBuffer(buffer, src, size, srcOffset, dstOffset);
            if (!this.coherent) {
                Vma.vmaFlushAllocation(Vulkan.getAllocator(), buffer.getAllocation(), dstOffset, size);
            }
        }

        @Override
        public void copyFromBuffer(Buffer buffer, long size, ByteBuffer byteBuffer) {
            if (!this.coherent) {
                Vma.vmaInvalidateAllocation(Vulkan.getAllocator(), buffer.getAllocation(), 0, size);
            }
            super.copyFromBuffer(buffer, size, byteBuffer);
        }
    }
}
