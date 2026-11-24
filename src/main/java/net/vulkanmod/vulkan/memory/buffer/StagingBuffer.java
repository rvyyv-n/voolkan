package net.vulkanmod.vulkan.memory.buffer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * fence-sync chunk and inter-frame transfer uploads on staging overflow before resetting the buffer.
 */

import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.chunk.util.Util;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.system.libc.LibCString.nmemcpy;
import static org.lwjgl.vulkan.VK10.*;

public class StagingBuffer extends Buffer {
    private static final long DEFAULT_SIZE = 64 * 1024 * 1024;

    public StagingBuffer() {
        this(DEFAULT_SIZE);
    }

    public StagingBuffer(long size) {
        super("Staging buffer", VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        this.createBuffer(size);
    }

    public void copyBuffer(int size, ByteBuffer byteBuffer) {
        this.copyBuffer(size, MemoryUtil.memAddress(byteBuffer));
    }

    public void copyBuffer(int size, long scrPtr) {
        if (size > this.bufferSize) {
            throw new IllegalArgumentException("Upload size is greater than staging buffer size.");
        }

        if (size > this.bufferSize - this.usedBytes) {
            submitUploads();
        }

        nmemcpy(this.dataPtr + this.usedBytes, scrPtr, size);

        this.offset = this.usedBytes;
        this.usedBytes += size;
    }

    public void align(int alignment) {
        long alignedOffset = Util.align(usedBytes, alignment);

        if (alignedOffset > this.bufferSize) {
            submitUploads();
            alignedOffset = 0;
        }

        this.usedBytes = alignedOffset;
    }

    private void submitUploads() {
        // Submit and wait all recorded uploads before resetting the buffer
        UploadManager.INSTANCE.syncUploads();
        ImageUploadHelper.INSTANCE.submitCommands(false);
        Renderer renderer = Renderer.getInstance();
        if (Renderer.isRecording()) {
            renderer.flushCmds();
        } else {
            renderer.submitTransferCbSync();
        }
        Synchronization.INSTANCE.waitFences();

        this.reset();
    }
}
