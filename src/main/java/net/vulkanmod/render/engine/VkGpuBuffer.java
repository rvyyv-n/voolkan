package net.vulkanmod.render.engine;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * prefer CPU-cached host memory for USAGE_MAP_READ buffers; allocate non-mappable GPU buffers in unified memory on macOS/Apple Silicon with per-frame uniform ringing to eliminate per-frame transfer submissions.
 */

import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.voolkan.compat.MacPerfOpts;

import java.nio.ByteBuffer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.vulkan.VK10.*;

@Environment(EnvType.CLIENT)
public class VkGpuBuffer extends GpuBuffer {
    private static final int INITIAL_RING_SLOTS_PER_FRAME = 8;
    private static final long MAX_RINGED_UNIFORM_SIZE = 65536L;

    protected boolean closed;
    @Nullable protected final Supplier<String> label;

    Buffer buffer;

    int offset;

    private final int vkUsageFlags;
    private boolean unifiedUniformRing;
    private int ringAlignedStride;
    private int ringSlotsPerFrame;
    private int ringFrames;
    private int ringFrameCursor;
    private long activeSlotOffset;
    private long lastWriteSerial = -1L;
    private int writesThisSerial;

    protected VkGpuBuffer(VkDebugLabel debugLabel, @Nullable Supplier<String> supplier, int usage, long size) {
        super(usage, size);
        this.label = supplier;

        int vkUsage = 0;
        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) {
            vkUsage |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        }
        if ((usage & GpuBuffer.USAGE_COPY_DST) != 0) {
            vkUsage |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        }
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        }
        this.vkUsageFlags = vkUsage;

        boolean mapRead = (usage & GpuBuffer.USAGE_MAP_READ) != 0;
        boolean mappable = mapRead |
                           (usage & GpuBuffer.USAGE_MAP_WRITE) != 0 |
                           (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;

        String labelStr = supplier != null ? supplier.get() : null;
        boolean useUnified = !mappable && MacPerfOpts.unifiedGpuBuffers()
                && MemoryTypes.unifiedDirectWriteActive && MemoryTypes.UNIFIED_MEM != null;

        boolean ringUniform = useUnified
                && (usage & GpuBuffer.USAGE_UNIFORM) != 0
                && (usage & (GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER)) == 0
                && size <= MAX_RINGED_UNIFORM_SIZE
                && Renderer.getInstance() != null;

        if (ringUniform) {
            // Allocate (framesNum + 2) per-frame regions, advanced monotonically per submission serial,
            // so even sparsely updated UBOs and inter-frame writes never overwrite a slot still read
            // by any in-flight GPU frame and never require a separate transferCb vkQueueSubmit + semaphore.
            this.ringFrames = Renderer.getFramesNum() + 2;
            this.ringSlotsPerFrame = INITIAL_RING_SLOTS_PER_FRAME;
            this.ringAlignedStride = UniformBuffer.getAlignedSize((int) size);
            long totalSize = (long) this.ringFrames * this.ringSlotsPerFrame * this.ringAlignedStride;
            this.buffer = new Buffer(labelStr, vkUsage, MemoryTypes.UNIFIED_MEM);
            this.buffer.createBuffer(totalSize);
            this.unifiedUniformRing = true;
            this.ringFrameCursor = 0;
            this.activeSlotOffset = 0L;
        } else {
            MemoryType memoryType = mapRead
                    ? MemoryTypes.READBACK_MEM
                    : (mappable ? MemoryTypes.HOST_MEM : (useUnified ? MemoryTypes.UNIFIED_MEM : MemoryTypes.GPU_MEM));

            this.buffer = new Buffer(labelStr, vkUsage, memoryType);
            this.buffer.createBuffer(this.size());
        }
    }

    protected VkGpuBuffer(Buffer buffer) {
        super(buffer.usage, buffer.getBufferSize());

        this.label = null;
        this.buffer = buffer;
        this.vkUsageFlags = buffer.usage;
    }

    /** Base byte offset of the currently active slot for ringed unified uniform buffers (0 otherwise). */
    public long getDynamicBaseOffset() {
        return this.activeSlotOffset;
    }

    public boolean isUnifiedUniformRing() {
        return this.unifiedUniformRing;
    }

    /**
     * Writes directly into the next idle slot of this ringed unified uniform buffer without staging
     * or transfer command buffers. Preserves unmodified bytes on partial writes.
     */
    boolean tryUnifiedUniformWrite(long dstOffset, ByteBuffer src) {
        if (!this.unifiedUniformRing) {
            return false;
        }

        int size = src.remaining();
        long serial = Renderer.getInstance().nextSubmitSerial();
        int neededRingFrames = Renderer.getFramesNum() + 2;
        long prevOffset = this.activeSlotOffset;
        boolean partial = dstOffset > 0L || size < this.size();

        if (neededRingFrames > this.ringFrames) {
            reallocateRing(neededRingFrames, this.ringSlotsPerFrame, prevOffset, partial);
            prevOffset = 0L;
            this.ringFrameCursor = 0;
            this.lastWriteSerial = serial;
            this.writesThisSerial = 0;
        }

        int writeIdx;
        if (this.lastWriteSerial < 0L) {
            this.lastWriteSerial = serial;
            this.ringFrameCursor = 0;
            this.writesThisSerial = 0;
            writeIdx = 0;
        } else if (serial != this.lastWriteSerial) {
            this.lastWriteSerial = serial;
            this.ringFrameCursor = (this.ringFrameCursor + 1) % this.ringFrames;
            this.writesThisSerial = 0;
            writeIdx = 0;
        } else {
            writeIdx = this.writesThisSerial;
        }

        if (writeIdx >= this.ringSlotsPerFrame) {
            reallocateRing(this.ringFrames, this.ringSlotsPerFrame * 2, prevOffset, partial);
            prevOffset = 0L;
            this.ringFrameCursor = 0;
            writeIdx = 0;
            this.writesThisSerial = 0;
        }

        long newSlotOffset = ((long) this.ringFrameCursor * this.ringSlotsPerFrame + writeIdx) * this.ringAlignedStride;

        if (partial && newSlotOffset != prevOffset) {
            MemoryTypes.copyMappableBuffer(this.buffer, prevOffset, this.buffer, newSlotOffset, this.size());
        }

        this.buffer.type.copyToBuffer(this.buffer, src, size, 0L, newSlotOffset + dstOffset);
        this.activeSlotOffset = newSlotOffset;
        this.writesThisSerial = writeIdx + 1;
        return true;
    }

    private void reallocateRing(int newRingFrames, int newSlotsPerFrame, long prevOffset, boolean copyPrev) {
        Buffer oldBuffer = this.buffer;
        String labelStr = this.label != null ? this.label.get() : oldBuffer.name;
        long totalSize = (long) newRingFrames * newSlotsPerFrame * this.ringAlignedStride;
        Buffer newBuffer = new Buffer(labelStr, this.vkUsageFlags, MemoryTypes.UNIFIED_MEM);
        newBuffer.createBuffer(totalSize);
        if (copyPrev) {
            MemoryTypes.copyMappableBuffer(oldBuffer, prevOffset, newBuffer, 0L, this.size());
        }
        MemoryManager.getInstance().addToFreeable(oldBuffer);
        this.buffer = newBuffer;
        this.ringFrames = newRingFrames;
        this.ringSlotsPerFrame = newSlotsPerFrame;
        this.activeSlotOffset = 0L;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;

            MemoryManager.getInstance().addToFreeable(this.buffer);
        }
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public static int bufferUsageToGlEnum(int i) {
        boolean stream = (i & 4) != 0;
        // Draw
        if ((i & 2) != 0) {
            return stream ? 35040 : 35044;
        }
        // Read
        else if ((i & 1) != 0) {
            return stream ? 35041 : 35045;
        } else {
            return 35044;
        }
    }

    public static VkGpuBuffer from(Buffer buffer) {
        return new VkGpuBuffer(buffer);
    }

    @Environment(EnvType.CLIENT)
    public static class MappedView implements GpuBuffer.MappedView {
        private final int target;
        private final ByteBuffer data;

        protected MappedView(int i, ByteBuffer byteBuffer) {
            this.target = i;
            this.data = byteBuffer;
        }

        @Override
        public ByteBuffer data() {
            return this.data;
        }

        @Override
        public void close() {
//            GlStateManager._glUnmapBuffer(this.target);
        }
    }
}

