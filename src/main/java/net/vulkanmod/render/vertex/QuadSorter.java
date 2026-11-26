package net.vulkanmod.render.vertex;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * store quad centroids in a flat float array, reuse sort buffers, and fix 2-byte OOB index writes for SHORT indices.
 */

import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.util.SortUtil;
import org.lwjgl.system.MemoryUtil;

public class QuadSorter {

    private float[] sortingPoints;
    private int pointCount;
    private float sortX = Float.NaN;
    private float sortY = Float.NaN;
    private float sortZ = Float.NaN;
    private boolean indexOnly;

    private VertexFormat format;
    private int vertexCount;
    private int indexCount;

    private float[] distances;
    private int[] sortingPointsIndices;
    private int[] sortScratch;

    public void setQuadSortOrigin(float x, float y, float z) {
        this.sortX = x;
        this.sortY = y;
        this.sortZ = z;
    }

    public SortState getSortState() {
        return new SortState(this.vertexCount, this.pointCount, this.sortingPoints);
    }

    public void restoreSortState(QuadSorter.SortState sortState) {
        this.vertexCount = sortState.vertexCount;
        this.pointCount = sortState.pointCount;
        this.sortingPoints = sortState.sortingPoints;

        this.indexOnly = true;
    }

    public void setupQuadSortingPoints(long bufferPtr, int vertexCount, VertexFormat format) {
        this.vertexCount = vertexCount;
        int count = vertexCount / 4;
        this.pointCount = count;
        float[] points = new float[count * 3];

        int vertexSize = format.getVertexSize();
        int quadStride = vertexSize * 4;
        int offset = vertexSize * 2;

        if (format == CustomVertexFormat.COMPRESSED_TERRAIN) {
            final float invConv = 0.5f / VertexBuilder.CompressedVertexBuilder.POS_CONV_MUL;
            final float convOffset = -VertexBuilder.CompressedVertexBuilder.POS_OFFSET;

            for (int m = 0, p = 0; m < count; ++m, p += 3) {
                long ptr = bufferPtr + (long) m * quadStride;

                short x0 = MemoryUtil.memGetShort(ptr + 0);
                short y0 = MemoryUtil.memGetShort(ptr + 2);
                short z0 = MemoryUtil.memGetShort(ptr + 4);
                short x2 = MemoryUtil.memGetShort(ptr + offset + 0);
                short y2 = MemoryUtil.memGetShort(ptr + offset + 2);
                short z2 = MemoryUtil.memGetShort(ptr + offset + 4);

                points[p]     = (x0 + x2) * invConv + convOffset;
                points[p + 1] = (y0 + y2) * invConv + convOffset;
                points[p + 2] = (z0 + z2) * invConv + convOffset;
            }
        } else {
            for (int m = 0, p = 0; m < count; ++m, p += 3) {
                long ptr = bufferPtr + (long) m * quadStride;

                float x0 = MemoryUtil.memGetFloat(ptr + 0);
                float y0 = MemoryUtil.memGetFloat(ptr + 4);
                float z0 = MemoryUtil.memGetFloat(ptr + 8);
                float x2 = MemoryUtil.memGetFloat(ptr + offset + 0);
                float y2 = MemoryUtil.memGetFloat(ptr + offset + 4);
                float z2 = MemoryUtil.memGetFloat(ptr + offset + 8);

                points[p]     = (x0 + x2) * 0.5f;
                points[p + 1] = (y0 + y2) * 0.5f;
                points[p + 2] = (z0 + z2) * 0.5f;
            }
        }

        this.sortingPoints = points;
    }

    public void putSortedQuadIndices(TerrainBufferBuilder bufferBuilder, VertexFormat.IndexType indexType) {
        writeSortedQuadIndices(bufferBuilder.getPtr(), indexType);
    }

    public void putSortedQuadIndices(TerrainBuilder bufferBuilder, VertexFormat.IndexType indexType) {
        writeSortedQuadIndices(bufferBuilder.indexBufferPtr, indexType);
    }

    private void writeSortedQuadIndices(long ptr, VertexFormat.IndexType indexType) {
        final int count = this.pointCount;
        final float[] points = this.sortingPoints;
        float[] distances = this.distances;
        int[] indices = this.sortingPointsIndices;
        int[] scratch = this.sortScratch;
        if (distances == null || distances.length < count) {
            distances = new float[count];
            this.distances = distances;
        }
        if (indices == null || indices.length < count) {
            indices = new int[count];
            this.sortingPointsIndices = indices;
        }
        if (count >= 16 && (scratch == null || scratch.length < count)) {
            scratch = new int[count];
            this.sortScratch = scratch;
        }

        final float sx = this.sortX;
        final float sy = this.sortY;
        final float sz = this.sortZ;
        if (count >= 16) {
            for (int i = 0, p = 0; i < count; ++i, p += 3) {
                float dx = points[p]     - sx;
                float dy = points[p + 1] - sy;
                float dz = points[p + 2] - sz;
                distances[i] = dx * dx + dy * dy + dz * dz;
                indices[i] = i;
                scratch[i] = i;
            }
            SortUtil.mergeSort(indices, distances, 0, count, scratch);
        } else {
            for (int i = 0, p = 0; i < count; ++i, p += 3) {
                float dx = points[p]     - sx;
                float dy = points[p + 1] - sy;
                float dz = points[p + 2] - sz;
                distances[i] = dx * dx + dy * dy + dz * dz;
                indices[i] = i;
            }
            SortUtil.mergeSort(indices, distances, 0, count, null);
        }

        final int size = indexType.bytes;
        if (size == Short.BYTES) {
            for (int i = 0; i < count; ++i) {
                final int baseVertex = indices[i] * 4;
                MemoryUtil.memPutShort(ptr + 0L, (short) (baseVertex));
                MemoryUtil.memPutShort(ptr + 2L, (short) (baseVertex + 1));
                MemoryUtil.memPutShort(ptr + 4L, (short) (baseVertex + 2));
                MemoryUtil.memPutShort(ptr + 6L, (short) (baseVertex + 2));
                MemoryUtil.memPutShort(ptr + 8L, (short) (baseVertex + 3));
                MemoryUtil.memPutShort(ptr + 10L, (short) (baseVertex));
                ptr += 12L;
            }
        } else {
            for (int i = 0; i < count; ++i) {
                final int baseVertex = indices[i] * 4;
                MemoryUtil.memPutInt(ptr + 0L, baseVertex);
                MemoryUtil.memPutInt(ptr + 4L, baseVertex + 1);
                MemoryUtil.memPutInt(ptr + 8L, baseVertex + 2);
                MemoryUtil.memPutInt(ptr + 12L, baseVertex + 2);
                MemoryUtil.memPutInt(ptr + 16L, baseVertex + 3);
                MemoryUtil.memPutInt(ptr + 20L, baseVertex);
                ptr += 24L;
            }
        }
    }

    public void reset() {
        this.vertexCount = 0;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public int getIndexCount() {
        return indexCount;
    }

    public static class SortState {
        final int vertexCount;
        final int pointCount;
        final float[] sortingPoints;

        SortState(int vertexCount, int pointCount, float[] sortingPoints) {
            this.vertexCount = vertexCount;
            this.pointCount = pointCount;
            this.sortingPoints = sortingPoints;
        }
    }
}
