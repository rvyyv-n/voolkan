package net.vulkanmod.render.chunk.build;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * hoist light listeners and lazy default biome lookup out of the 27-section loop and remove duplicate DataLayer[] allocations.
 */

import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.vulkanmod.interfaces.biome.BiomeManagerExtended;
import net.vulkanmod.render.chunk.build.biome.BiomeData;

public class RenderRegionBuilder {
    private static final DataLayer DEFAULT_SKY_LIGHT_DATA_LAYER = new DataLayer(15);
    private static final DataLayer DEFAULT_BLOCK_LIGHT_DATA_LAYER = new DataLayer(0);

    private static final int MAX_CACHE_ENTRIES = 256;
    private final Long2ReferenceLinkedOpenHashMap<LevelChunk> levelChunkCache = new Long2ReferenceLinkedOpenHashMap<>(MAX_CACHE_ENTRIES);

    public RenderRegion createRegion(Level level, int secX, int secY, int secZ) {
        LevelChunk levelChunk = getLevelChunk(level, secX, secZ);
        var sections = levelChunk.getSections();
        LevelChunkSection section = sections[level.getSectionIndexFromSectionY(secY)];

        if (section == null || section.hasOnlyAir())
            return null;

        var blockEntityMap = levelChunk.getBlockEntities();

        int minSecX = secX - 1;
        int minSecZ = secZ - 1;
        int minSecY = secY - 1;
        int maxSecX = secX + 1;
        int maxSecZ = secZ + 1;
        int maxSecY = secY + 1;

        PalettedContainer<BlockState>[] blockData = new PalettedContainer[RenderRegion.SIZE];

        DataLayer[][] lightData = new DataLayer[RenderRegion.SIZE][2 /* Light types */];

        long biomeZoomSeed = BiomeManagerExtended.of(level.getBiomeManager()).getBiomeZoomSeed();
        BiomeData biomeData = new BiomeData(biomeZoomSeed, minSecX, minSecY, minSecZ);

        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        boolean hasSkyLight = level.dimensionType().hasSkyLight();
        LayerLightEventListener skyLightListener = hasSkyLight ? lightEngine.getLayerListener(LightLayer.SKY) : null;
        final int blockLightOrd = LightLayer.BLOCK.ordinal();
        final int skyLightOrd = LightLayer.SKY.ordinal();
        Biome defaultBiome = null;

        final int minHeightSec = level.getMinY() >> 4;
        for (int x = minSecX; x <= maxSecX; ++x) {
            for (int z = minSecZ; z <= maxSecZ; ++z) {
                LevelChunk levelChunk1 = getLevelChunk(level, x, z);
                sections = levelChunk1.getSections();

                for (int y = minSecY; y <= maxSecY; ++y) {
                    int sectionIdx = y - minHeightSec;
                    section = sectionIdx >= 0 && sectionIdx < sections.length ? sections[sectionIdx] : null;

                    final int relX = (x - minSecX), relY = (y - minSecY), relZ = (z - minSecZ);
                    final int idx = (relY * RenderRegion.WIDTH + relZ) * RenderRegion.WIDTH + relX;

                    PalettedContainer<BlockState> values = section == null || section.hasOnlyAir() ? null : section.getStates().copy();

                    blockData[idx] = values;

                    SectionPos pos = SectionPos.of(x, y, z);
                    DataLayer[] dataLayers = lightData[idx];

                    DataLayer blockDataLayer = blockLightListener.getDataLayerData(pos);
                    dataLayers[blockLightOrd] = blockDataLayer != null ? blockDataLayer : DEFAULT_BLOCK_LIGHT_DATA_LAYER;

                    if (skyLightListener != null) {
                        DataLayer skyDataLayer = skyLightListener.getDataLayerData(pos);
                        dataLayers[skyLightOrd] = skyDataLayer != null ? skyDataLayer : DEFAULT_SKY_LIGHT_DATA_LAYER;
                    } else {
                        dataLayers[skyLightOrd] = null;
                    }

                    if (section == null && defaultBiome == null) {
                        defaultBiome = level.registryAccess()
                                .lookupOrThrow(Registries.BIOME)
                                .getOrThrow(Biomes.PLAINS)
                                .value();
                    }
                    biomeData.getBiomeData(section, defaultBiome, relX, relY, relZ);
                }
            }
        }

        return new RenderRegion(level, secX, secY, secZ, blockData, lightData, biomeData, blockEntityMap);
    }

    private LevelChunk getLevelChunk(Level level, int x, int z) {
        long l = ChunkPos.asLong(x, z);
        LevelChunk chunk = this.levelChunkCache.getAndMoveToFirst(l);

        if (chunk == null) {
            chunk = level.getChunk(x, z);

            while (levelChunkCache.size() >= MAX_CACHE_ENTRIES) {
                levelChunkCache.removeLast();
            }

            this.levelChunkCache.putAndMoveToFirst(l, chunk);
        }

        return chunk;
    }

    public void remove(int x, int z) {
        levelChunkCache.remove(ChunkPos.asLong(x, z));
    }

    public void clear() {
        this.levelChunkCache.clear();
    }
}
