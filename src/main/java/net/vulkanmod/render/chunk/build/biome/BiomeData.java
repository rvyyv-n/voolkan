package net.vulkanmod.render.chunk.build.biome;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * store cell offsets in a flat float array to avoid per-cell Vector3f allocations and accept a pre-resolved default biome.
 */

import net.minecraft.core.registries.Registries;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.vulkanmod.render.chunk.build.RenderRegion;

public class BiomeData {
    private static final int ZOOM_LENGTH = 4;
    private static final int BIOMES_PER_SECTION = 4 * 4 * 4;
    private static final int SIZE = RenderRegion.SIZE * BIOMES_PER_SECTION;

    Biome[] biomes = new Biome[SIZE];
    private final long biomeZoomSeed;

    int secX, secY, secZ;

    // Cached cell offsets (flat x, y, z triplets)
    private final float[] offsets = new float[SIZE * 3];
    private final boolean[] offsetComputed = new boolean[SIZE];

    public BiomeData(long biomeZoomSeed, int secX, int secY, int secZ) {
        this.biomeZoomSeed = biomeZoomSeed;
        this.secX = secX;
        this.secY = secY;
        this.secZ = secZ;
    }

    public void getBiomeData(Level level, LevelChunkSection chunkSection, int secX, int secY, int secZ) {
        Biome defaultValue = chunkSection == null
                ? level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value()
                : null;
        getBiomeData(chunkSection, defaultValue, secX, secY, secZ);
    }

    public void getBiomeData(LevelChunkSection chunkSection, Biome defaultValue, int secX, int secY, int secZ) {
        int baseIdx = getRelativeSectionIdx(secX, secY, secZ);

        if (chunkSection != null) {
            for (int x = 0; x < 4; x++) {
                int xBase = baseIdx + (x << 4);
                for (int y = 0; y < 4; y++) {
                    int xyBase = xBase + (y << 2);
                    for (int z = 0; z < 4; z++) {
                        biomes[xyBase + z] = chunkSection.getNoiseBiome(x, y, z).value();
                    }
                }
            }
        } else {
            java.util.Arrays.fill(biomes, baseIdx, baseIdx + BIOMES_PER_SECTION, defaultValue);
        }
    }

    public Biome getBiome(int blockX, int blockY, int blockZ) {
        int x = blockX - 2;
        int y = blockY - 2;
        int z = blockZ - 2;

        int zoomX = x >> 2;
        int zoomY = y >> 2;
        int zoomZ = z >> 2;

        float fracZoomX = (x & 3) * 0.25f;
        float fracZoomY = (y & 3) * 0.25f;
        float fracZoomZ = (z & 3) * 0.25f;

        int closestCellIdx = 0;
        float closestDistance = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 8; ++i) {
            boolean dirX = (i & 4) != 0;
            boolean dirY = (i & 2) != 0;
            boolean dirZ = (i & 1) != 0;

            int cellX = dirX ? zoomX + 1 : zoomX;
            int cellY = dirY ? zoomY + 1 : zoomY;
            int cellZ = dirZ ? zoomZ + 1 : zoomZ;

            float fCellX = dirX ? fracZoomX - 1.0f : fracZoomX;
            float fCellY = dirY ? fracZoomY - 1.0f : fracZoomY;
            float fCellZ = dirZ ? fracZoomZ - 1.0f : fracZoomZ;

            int baseSectionIdx = getSectionIdx(cellX >> 2, cellY >> 2, cellZ >> 2);
            int cellIdx = baseSectionIdx + getRelativeIdx(cellX & 3, cellY & 3, cellZ & 3);

            int offIdx = cellIdx * 3;
            if (!this.offsetComputed[cellIdx]) {
                computeCellOffset(this.biomeZoomSeed, cellX, cellY, cellZ, offIdx);
                this.offsetComputed[cellIdx] = true;
            }

            float distance = Mth.square(fCellX + this.offsets[offIdx])
                    + Mth.square(fCellY + this.offsets[offIdx + 1])
                    + Mth.square(fCellZ + this.offsets[offIdx + 2]);

            if (closestDistance > distance) {
                closestCellIdx = cellIdx;
                closestDistance = distance;
            }
        }

        return this.biomes[closestCellIdx];
    }

    private int getSectionIdx(int secX, int secY, int secZ) {
        return getRelativeSectionIdx(secX - this.secX, secY - this.secY, secZ - this.secZ);
    }

    private void computeCellOffset(long l, int cellX, int cellY, int cellZ, int offIdx) {
        long seed;
        seed = LinearCongruentialGenerator.next(l, cellX);
        seed = LinearCongruentialGenerator.next(seed, cellY);
        seed = LinearCongruentialGenerator.next(seed, cellZ);
        seed = LinearCongruentialGenerator.next(seed, cellX);
        seed = LinearCongruentialGenerator.next(seed, cellY);
        seed = LinearCongruentialGenerator.next(seed, cellZ);

        this.offsets[offIdx] = getFiddle(seed);
        seed = LinearCongruentialGenerator.next(seed, l);
        this.offsets[offIdx + 1] = getFiddle(seed);
        seed = LinearCongruentialGenerator.next(seed, l);
        this.offsets[offIdx + 2] = getFiddle(seed);
    }

    private static float getFiddle(long l) {
        float d = Math.floorMod(l >> 24, 1024) * (1.0f / 1024.0f);
        return (d - 0.5f) * 0.9f;
    }

    private static int getRelativeSectionIdx(int x, int y, int z) {
        return ((x * RenderRegion.WIDTH * RenderRegion.WIDTH) + (y * RenderRegion.WIDTH) + z) * BIOMES_PER_SECTION;
    }

    private static int getRelativeIdx(int x, int y, int z) {
        return (x * ZOOM_LENGTH * ZOOM_LENGTH) + (y * ZOOM_LENGTH) + z;
    }

}
