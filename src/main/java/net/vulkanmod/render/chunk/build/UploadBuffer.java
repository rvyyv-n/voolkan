package net.vulkanmod.render.chunk.build;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * pack all non-empty QuadFacing vertex buffers into one contiguous upload buffer per section layer.
 */

import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.util.BufferUtil;
import net.vulkanmod.render.vertex.TerrainBuilder;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class UploadBuffer {
    public final int indexCount;
    public final boolean autoIndices;
    public final boolean indexOnly;
    private final ByteBuffer vertexBuffer;
    private final int[] facingSizes;
    private final ByteBuffer indexBuffer;

    public UploadBuffer(TerrainBuilder terrainBuilder, TerrainBuilder.DrawState drawState) {
        this.indexCount = drawState.indexCount();
        this.autoIndices = drawState.sequentialIndex();
        this.indexOnly = drawState.indexOnly();

        if (!this.indexOnly) {
            int[] sizes = new int[QuadFacing.COUNT];
            int totalBytes = 0;

            for (int i = 0; i < QuadFacing.COUNT; i++) {
                var bufferBuilder = terrainBuilder.getBufferBuilder(i);
                if (bufferBuilder.getVertices() > 0) {
                    int remaining = bufferBuilder.getBuffer().remaining();
                    sizes[i] = remaining;
                    totalBytes += remaining;
                }
            }

            this.facingSizes = sizes;
            if (totalBytes > 0) {
                ByteBuffer packed = MemoryUtil.memAlloc(totalBytes);
                long dstPtr = MemoryUtil.memAddress(packed);
                int offset = 0;
                for (int i = 0; i < QuadFacing.COUNT; i++) {
                    int size = sizes[i];
                    if (size > 0) {
                        ByteBuffer src = terrainBuilder.getBufferBuilder(i).getBuffer();
                        MemoryUtil.memCopy(MemoryUtil.memAddress(src), dstPtr + offset, size);
                        offset += size;
                    }
                }
                packed.position(0);
                packed.limit(totalBytes);
                this.vertexBuffer = packed;
            } else {
                this.vertexBuffer = null;
            }
        }
        else {
            this.vertexBuffer = null;
            this.facingSizes = null;
        }

        if (!drawState.sequentialIndex()) {
            this.indexBuffer = BufferUtil.clone(terrainBuilder.getIndexBuffer());
        }
        else {
            this.indexBuffer = null;
        }
    }

    public int indexCount() {
        return indexCount;
    }

    public ByteBuffer getVertexBuffer() {
        return vertexBuffer;
    }

    public int getFacingSize(int facingIdx) {
        return facingSizes != null ? facingSizes[facingIdx] : 0;
    }

    public ByteBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public void release() {
        if (vertexBuffer != null)
            MemoryUtil.memFree(vertexBuffer);

        if (indexBuffer != null)
            MemoryUtil.memFree(indexBuffer);
    }
}
