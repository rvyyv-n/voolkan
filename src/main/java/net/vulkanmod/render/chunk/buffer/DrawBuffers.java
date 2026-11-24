package net.vulkanmod.render.chunk.buffer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * route terrain buffer binds through Renderer state caching and optimize terrain draw submission.
 */

import net.minecraft.util.Mth;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.task.CompiledSection;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import static org.lwjgl.vulkan.VK10.*;

public class DrawBuffers {
    public static final int INDEX_SIZE = Short.BYTES;
    public static final int UNDEFINED_FACING_IDX = QuadFacing.UNDEFINED.ordinal();
    public static final float POS_OFFSET = CustomVertexFormat.getPositionOffset();

    private static final int CMD_STRIDE = 20;

    private static final long cmdBufferPtr = MemoryUtil.nmemAlignedAlloc(64, (long) ChunkAreaManager.AREA_SIZE * QuadFacing.COUNT * CMD_STRIDE);
    private static final long originScratchPtr = MemoryUtil.nmemAlloc(12);

    private final int index;
    public final int vertexSize = PipelineManager.getTerrainVertexFormat().getVertexSize();
    private final Vector3i origin;
    private final int minHeight;

    private boolean allocated = false;
    AreaBuffer indexBuffer;
    private final EnumMap<TerrainRenderType, AreaBuffer> vertexBuffers = new EnumMap<>(TerrainRenderType.class);

    private final UniformBuffer sectionDataSlab;
    private final long sectionDataOffset;
    private final long sectionDataPtr;

    final long drawParamsPtr;
    final int[] sectionIndices = new int[512];
    final int[] masks = new int[512];

    final long[] buildTimes = new long[512];
    long latestBuildTime = 0;
    long lastFadeUpdate = -1;
    boolean fadeDirty = false;

    // Need ugly minHeight parameter to fix custom world heights (exceeding 384 Blocks in total)
    public DrawBuffers(int index, Vector3i origin, int minHeight, UniformBuffer sectionDataSlab, long sectionDataOffset) {
        this.index = index;
        this.origin = origin;
        this.minHeight = minHeight;
        this.sectionDataSlab = sectionDataSlab;
        this.sectionDataOffset = sectionDataOffset;
        this.sectionDataPtr = sectionDataSlab.getDataPtr() + sectionDataOffset;

        long fadePtr = this.sectionDataPtr + 512L * 4L;
        for (int i = 0; i < 512; i++) {
            MemoryUtil.memPutFloat(fadePtr + (i * 4L), 1.0f);
        }

        this.drawParamsPtr = DrawParametersBuffer.allocateBuffer();
    }

    public void upload(RenderSection section, UploadBuffer buffer, TerrainRenderType renderType) {
        if (buffer.indexOnly) {
            long paramsPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), QuadFacing.UNDEFINED.ordinal());

            int firstIndex = DrawParametersBuffer.getFirstIndex(paramsPtr);
            int indexCount = DrawParametersBuffer.getIndexCount(paramsPtr);

            int oldOffset = indexCount > 0 ? firstIndex : -1;
            AreaBuffer.Segment segment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramsPtr);
            firstIndex = segment.offset / INDEX_SIZE;

            DrawParametersBuffer.setFirstIndex(paramsPtr, firstIndex);

            buffer.release();
            return;
        }

        int oldOffset = -1;
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), i);
            int vertexOffset = DrawParametersBuffer.getVertexOffset(paramPtr);

            // Only need to get first used offset, as it identifies the whole segment that will be freed
            if (vertexOffset != -1) {
                oldOffset = vertexOffset;
                break;
            }
        }

        ByteBuffer packedVertices = buffer.getVertexBuffer();
        int size = packedVertices != null ? packedVertices.remaining() : 0;

        AreaBuffer areaBuffer = null;
        AreaBuffer.Segment segment = null;
        boolean doUpload = false;
        if (size > 0) {
            areaBuffer = this.getAreaBufferOrAlloc(renderType);
            areaBuffer.freeSegment(oldOffset);
            segment = areaBuffer.allocateSegment(size);
            areaBuffer.upload(segment, packedVertices, 0);
            doUpload = true;
            section.layerMask |= (byte) (1 << renderType.ordinal());
        } else {
            areaBuffer = this.getAreaBuffer(renderType);
            if (areaBuffer != null && oldOffset != -1) {
                areaBuffer.freeSegment(oldOffset);
            }
            section.layerMask &= (byte) ~(1 << renderType.ordinal());
        }

        int baseInstance = section.inAreaIndex;

        int offset = 0;
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), i);

            int vertexOffset = -1;
            int firstIndex = 0;
            int indexCount = 0;

            int facingSize = buffer.getFacingSize(i);
            int vertexCount = 0;

            if (facingSize > 0 && doUpload) {
                vertexOffset = (segment.offset + offset) / vertexSize;

                offset += facingSize;
                vertexCount = facingSize / vertexSize;
                indexCount = vertexCount * 6 / 4;
            }

            if (i == QuadFacing.UNDEFINED.ordinal() && !buffer.autoIndices) {
                if (this.indexBuffer == null) {
                    this.indexBuffer = new AreaBuffer(AreaBuffer.Usage.INDEX, 60000, INDEX_SIZE);
                }

                oldOffset = DrawParametersBuffer.getIndexCount(paramPtr) > 0 ? DrawParametersBuffer.getFirstIndex(paramPtr) : -1;
                AreaBuffer.Segment ibSegment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramPtr);
                firstIndex = ibSegment.offset / INDEX_SIZE;
            } else if (vertexCount > 0) {
                Renderer.getDrawer().getQuadsIndexBuffer().checkCapacity(vertexCount);
            }

            DrawParametersBuffer.setIndexCount(paramPtr, indexCount);
            DrawParametersBuffer.setFirstIndex(paramPtr, firstIndex);
            DrawParametersBuffer.setVertexOffset(paramPtr, vertexOffset);
            DrawParametersBuffer.setBaseInstance(paramPtr, baseInstance);
        }

        updateUniformData(section);

        buffer.release();
    }

    private void updateUniformData(RenderSection section) {
        int encodedOffset = encodeSectionOffset(section.xOffset(), section.yOffset(), section.zOffset());
        int ptrOffset = section.inAreaIndex * 4;
        MemoryUtil.memPutInt(this.sectionDataPtr + ptrOffset, encodedOffset);

        if (section.getCompiledSection() == CompiledSection.UNCOMPILED) {
            long buildTime = System.currentTimeMillis();
            this.buildTimes[section.inAreaIndex] = buildTime;

            if (buildTime > this.latestBuildTime) {
                this.latestBuildTime = buildTime;
            }
            this.fadeDirty = true;
        }
    }

    private void updateFadeUniform(long currentTime, int fadeTimeMs, float fadeTimeInv) {
        if (fadeTimeMs <= 0) {
            if (this.fadeDirty) {
                long fadePtr = this.sectionDataPtr + 512L * 4L;
                for (int i = 0; i < 512; i++) {
                    MemoryUtil.memPutFloat(fadePtr + (i * 4L), 1.0f);
                }
                this.fadeDirty = false;
                this.lastFadeUpdate = currentTime;
            }
            return;
        }

        if (this.lastFadeUpdate < this.latestBuildTime + fadeTimeMs) {
            long fadePtr = this.sectionDataPtr + 512L * 4L;
            for (int i = 0; i < 512; i++) {
                long delta = currentTime - this.buildTimes[i];
                float fade = Mth.clamp(delta * fadeTimeInv, 0.0f, 1.0f);

                MemoryUtil.memPutFloat(fadePtr + (i * 4L), fade);
            }

            this.lastFadeUpdate = currentTime;
            this.fadeDirty = currentTime < this.latestBuildTime + fadeTimeMs;
        }
    }

    private AreaBuffer getAreaBufferOrAlloc(TerrainRenderType renderType) {
        this.allocated = true;

        int initialSize = switch (renderType) {
            case SOLID -> 100000;
            case CUTOUT -> 250000;
            case TRANSLUCENT, TRIPWIRE -> 60000;
        };

        return this.vertexBuffers.computeIfAbsent(
                renderType, renderType1 -> new AreaBuffer(AreaBuffer.Usage.VERTEX, initialSize, vertexSize));
    }

    public AreaBuffer getAreaBuffer(TerrainRenderType r) {
        return this.vertexBuffers.get(r);
    }

    private boolean hasRenderType(TerrainRenderType r) {
        return this.vertexBuffers.containsKey(r);
    }

    private int encodeSectionOffset(int xOffset, int yOffset, int zOffset) {
        final int xOffset1 = (xOffset & 127);
        final int zOffset1 = (zOffset & 127);
        final int yOffset1 = (yOffset - this.minHeight & 127);
        return yOffset1 << 16 | zOffset1 << 8 | xOffset1;
    }

    private void updateChunkAreaOrigin(VkCommandBuffer commandBuffer, Pipeline pipeline, double camX, double camY, double camZ) {
        float xOffset = (float) ((this.origin.x) + POS_OFFSET - camX);
        float yOffset = (float) ((this.origin.y) + POS_OFFSET - camY);
        float zOffset = (float) ((this.origin.z) + POS_OFFSET - camZ);

        MemoryUtil.memPutFloat(originScratchPtr, xOffset);
        MemoryUtil.memPutFloat(originScratchPtr + 4L, yOffset);
        MemoryUtil.memPutFloat(originScratchPtr + 8L, zOffset);

        nvkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, 12, originScratchPtr);
    }

    public int buildIndirectBatch(Vector3d cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        final long bufferPtr = cmdBufferPtr;
        final boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        final boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;
        final int layerBit = 1 << terrainRenderType.ordinal();

        int drawCount = 0;

        long drawParamsBasePtr = this.drawParamsPtr + (terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        final long facingsStride = DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;

        final int qSize = queue.size();
        int count = 0;
        if (backFaceCulling) {
            for (int k = 0; k < qSize; ++k) {
                final RenderSection section = queue.get(k);
                if ((section.layerMask & layerBit) == 0) {
                    continue;
                }
                sectionIndices[count] = section.inAreaIndex;
                masks[count] = getMask(cameraPos, section);
                count++;
            }

            if (count == 0) {
                return 0;
            }

            long ptr = bufferPtr;

            for (int j = 0; j < count; ++j) {
                final int sectionIdx = sectionIndices[j];
                final int mask = masks[j];

                long drawParamsBasePtr2 = drawParamsBasePtr + (sectionIdx * facingsStride);

                int indexCount = 0;
                int firstIndex = 0;
                int vertexOffset = 0;
                int baseInstance = 0;

                for (int i = 0; i < QuadFacing.COUNT; i++) {
                    if ((mask & (1 << i)) == 0) {
                        drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;

                        if (indexCount > 0) {
                            MemoryUtil.memPutInt(ptr, indexCount);
                            MemoryUtil.memPutInt(ptr + 4, 1);
                            MemoryUtil.memPutInt(ptr + 8, firstIndex);
                            MemoryUtil.memPutInt(ptr + 12, vertexOffset);
                            MemoryUtil.memPutInt(ptr + 16, baseInstance);

                            ptr += CMD_STRIDE;
                            drawCount++;
                            indexCount = 0;
                        }
                        continue;
                    }

                    final int indexCount_i = DrawParametersBuffer.getIndexCount(drawParamsBasePtr2);
                    if (indexCount_i > 0) {
                        if (indexCount == 0) {
                            indexCount = indexCount_i;
                            firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsBasePtr2);
                            vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsBasePtr2);
                            baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsBasePtr2);
                        } else {
                            indexCount += indexCount_i;
                        }
                    }

                    drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;
                }

                if (indexCount > 0) {
                    MemoryUtil.memPutInt(ptr, indexCount);
                    MemoryUtil.memPutInt(ptr + 4, 1);
                    MemoryUtil.memPutInt(ptr + 8, firstIndex);
                    MemoryUtil.memPutInt(ptr + 12, vertexOffset);
                    MemoryUtil.memPutInt(ptr + 16, baseInstance);

                    ptr += CMD_STRIDE;
                    drawCount++;
                }
            }
        }
        else {
            if (isTranslucent) {
                for (int k = qSize - 1; k >= 0; --k) {
                    final RenderSection section = queue.get(k);
                    if ((section.layerMask & layerBit) == 0) {
                        continue;
                    }
                    sectionIndices[count++] = section.inAreaIndex;
                }
            } else {
                for (int k = 0; k < qSize; ++k) {
                    final RenderSection section = queue.get(k);
                    if ((section.layerMask & layerBit) == 0) {
                        continue;
                    }
                    sectionIndices[count++] = section.inAreaIndex;
                }
            }

            if (count == 0) {
                return 0;
            }

            final long facingOffset = UNDEFINED_FACING_IDX * DrawParametersBuffer.STRIDE;
            drawParamsBasePtr += facingOffset;

            long ptr = bufferPtr;
            for (int i = 0; i < count; ++i) {
                int sectionIdx = sectionIndices[i];
                long drawParamsPtr = drawParamsBasePtr + (sectionIdx * facingsStride);

                final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                if (indexCount <= 0) {
                    continue;
                }

                MemoryUtil.memPutInt(ptr, indexCount);
                MemoryUtil.memPutInt(ptr + 4, 1);
                MemoryUtil.memPutInt(ptr + 8, DrawParametersBuffer.getFirstIndex(drawParamsPtr));
                MemoryUtil.memPutInt(ptr + 12, DrawParametersBuffer.getVertexOffset(drawParamsPtr));
                MemoryUtil.memPutInt(ptr + 16, DrawParametersBuffer.getBaseInstance(drawParamsPtr));

                ptr += CMD_STRIDE;
                drawCount++;
            }
        }

        return drawCount;
    }

    public void submitIndirectBatch(VkCommandBuffer commandBuffer, IndirectBuffer indirectBuffer, int drawCount) {
        indirectBuffer.recordCopyCmd(cmdBufferPtr, drawCount * CMD_STRIDE);
        vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffer.getId(), indirectBuffer.getOffset(), drawCount, CMD_STRIDE);
    }

    public void buildDrawBatchesIndirect(Vector3d cameraPos, IndirectBuffer indirectBuffer, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        int drawCount = buildIndirectBatch(cameraPos, queue, terrainRenderType);
        if (drawCount > 0) {
            submitIndirectBatch(Renderer.getCommandBuffer(), indirectBuffer, drawCount);
        }
    }

    public int prepareDirectBatch(Vector3d cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        final boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        final boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;
        final int layerBit = 1 << terrainRenderType.ordinal();
        final int qSize = queue.size();
        int count = 0;

        if (backFaceCulling) {
            for (int k = 0; k < qSize; ++k) {
                final RenderSection section = queue.get(k);
                if ((section.layerMask & layerBit) == 0) {
                    continue;
                }
                sectionIndices[count] = section.inAreaIndex;
                masks[count] = getMask(cameraPos, section);
                count++;
            }
        } else if (isTranslucent) {
            for (int k = qSize - 1; k >= 0; --k) {
                final RenderSection section = queue.get(k);
                if ((section.layerMask & layerBit) == 0) {
                    continue;
                }
                sectionIndices[count++] = section.inAreaIndex;
            }
        } else {
            for (int k = 0; k < qSize; ++k) {
                final RenderSection section = queue.get(k);
                if ((section.layerMask & layerBit) == 0) {
                    continue;
                }
                sectionIndices[count++] = section.inAreaIndex;
            }
        }

        return count;
    }

    public void submitDirectBatch(VkCommandBuffer commandBuffer, TerrainRenderType terrainRenderType, int count) {
        final boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        final boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;

        long drawParamsBasePtr = this.drawParamsPtr + (terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        final long facingsStride = DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;

        if (backFaceCulling) {
            for (int j = 0; j < count; ++j) {
                final int sectionIdx = sectionIndices[j];
                final int mask = masks[j];

                long drawParamsBasePtr2 = drawParamsBasePtr + (sectionIdx * facingsStride);

                int indexCount   = 0;
                int firstIndex   = 0;
                int vertexOffset = 0;
                int baseInstance = 0;

                for (int i = 0; i < QuadFacing.COUNT; i++) {
                    if ((mask & (1 << i)) == 0) {
                        drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;

                        if (indexCount > 0) {
                            vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
                            indexCount = 0;
                        }
                        continue;
                    }

                    final int indexCount_i = DrawParametersBuffer.getIndexCount(drawParamsBasePtr2);
                    if (indexCount_i > 0) {
                        if (indexCount == 0) {
                            indexCount   = indexCount_i;
                            firstIndex   = DrawParametersBuffer.getFirstIndex(drawParamsBasePtr2);
                            vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsBasePtr2);
                            baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsBasePtr2);
                        } else {
                            indexCount += indexCount_i;
                        }
                    }

                    drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;
                }

                if (indexCount > 0) {
                    vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
                }
            }
        }
        else {
            final long facingOffset = UNDEFINED_FACING_IDX * DrawParametersBuffer.STRIDE;
            drawParamsBasePtr += facingOffset;

            for (int i = 0; i < count; ++i) {
                int sectionIdx = sectionIndices[i];
                long drawParamsPtr = drawParamsBasePtr + (sectionIdx * facingsStride);

                final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                if (indexCount <= 0) {
                    continue;
                }

                vkCmdDrawIndexed(commandBuffer, indexCount, 1,
                        DrawParametersBuffer.getFirstIndex(drawParamsPtr),
                        DrawParametersBuffer.getVertexOffset(drawParamsPtr),
                        DrawParametersBuffer.getBaseInstance(drawParamsPtr));
            }
        }
    }

    public void buildDrawBatchesDirect(Vector3d cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        int count = prepareDirectBatch(cameraPos, queue, terrainRenderType);
        if (count > 0) {
            submitDirectBatch(Renderer.getCommandBuffer(), terrainRenderType, count);
        }
    }

    private int getMask(Vector3d camera, RenderSection section) {
        final int secX = section.xOffset;
        final int secY = section.yOffset;
        final int secZ = section.zOffset;

        int mask = 1 << UNDEFINED_FACING_IDX;

        mask |= camera.x - secX >= 0 ? 1 << QuadFacing.X_POS.ordinal() : 0;
        mask |= camera.y - secY >= 0 ? 1 << QuadFacing.Y_POS.ordinal() : 0;
        mask |= camera.z - secZ >= 0 ? 1 << QuadFacing.Z_POS.ordinal() : 0;
        mask |= camera.x - (secX + 16) < 0 ? 1 << QuadFacing.X_NEG.ordinal() : 0;
        mask |= camera.y - (secY + 16) < 0 ? 1 << QuadFacing.Y_NEG.ordinal() : 0;
        mask |= camera.z - (secZ + 16) < 0 ? 1 << QuadFacing.Z_NEG.ordinal() : 0;

        return mask;
    }

    public void bindBuffers(VkCommandBuffer commandBuffer, Pipeline pipeline, TerrainRenderType terrainRenderType,
                            double camX, double camY, double camZ,
                            long currentTime, int fadeTimeMs, float fadeTimeInv) {
        Renderer renderer = Renderer.getInstance();
        var vertexBuffer = getAreaBuffer(terrainRenderType);
        renderer.bindVertexBuffer(commandBuffer, vertexBuffer.getId(), 0L);

        updateChunkAreaOrigin(commandBuffer, pipeline, camX, camY, camZ);
        this.updateFadeUniform(currentTime, fadeTimeMs, fadeTimeInv);

        UBO ubo = pipeline.getUBO(2); // SectionData
        ubo.setUseGlobalBuffer(false);
        ubo.getBufferSlice().set(this.sectionDataSlab, this.sectionDataOffset, ChunkAreaManager.SECTION_DATA_SIZE);

        if (terrainRenderType == TerrainRenderType.TRANSLUCENT && this.indexBuffer != null) {
            renderer.bindIndexBuffer(commandBuffer, this.indexBuffer.getId(), 0L, VK_INDEX_TYPE_UINT16);
        }
    }

    public void releaseBuffers() {
        if (!this.allocated)
            return;

        this.vertexBuffers.values().forEach(AreaBuffer::freeBuffer);
        this.vertexBuffers.clear();

        if (this.indexBuffer != null)
            this.indexBuffer.freeBuffer();
        this.indexBuffer = null;

        this.allocated = false;
    }

    public void free() {
        this.releaseBuffers();

        DrawParametersBuffer.freeBuffer(this.drawParamsPtr);
    }

    public boolean isAllocated() {
        return !this.vertexBuffers.isEmpty();
    }

    public EnumMap<TerrainRenderType, AreaBuffer> getVertexBuffers() {
        return vertexBuffers;
    }

    public AreaBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public long getDrawParamsPtr() {
        return drawParamsPtr;
    }

}
