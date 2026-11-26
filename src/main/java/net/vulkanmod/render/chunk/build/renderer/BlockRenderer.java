package net.vulkanmod.render.chunk.build.renderer;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * cache cullTest predicate and BlockRenderDispatcher, and avoid duplicate getChunkRenderType lookups.
 */

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import net.vulkanmod.render.chunk.build.frapi.render.AbstractBlockRenderContext;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.light.data.QuadLightData;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.model.quad.QuadUtils;
import net.vulkanmod.render.model.quad.ModelQuadView;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Vector3f;

import java.util.function.Predicate;

public class BlockRenderer extends AbstractBlockRenderContext {
    private Vector3f pos;

    private BuilderResources resources;
    private TerrainBuilder terrainBuilder;
    private BlockRenderDispatcher blockRenderDispatcher;
    private final Predicate<Direction> cullTest = this::isFaceCulled;

    final boolean backFaceCulling = Initializer.CONFIG.backFaceCulling;

    private TerrainRenderType renderType;

    public void setResources(BuilderResources resources) {
        this.resources = resources;
    }

    public BlockRenderer(LightPipeline flatLightPipeline, LightPipeline smoothLightPipeline) {
        super();
        this.setupLightPipelines(flatLightPipeline, smoothLightPipeline);

        this.random = new SingleThreadedRandomSource(42L);
    }

    @Override
    public void prepareForWorld(BlockAndTintGetter blockView, boolean enableCulling) {
        super.prepareForWorld(blockView, enableCulling);
        if (this.blockRenderDispatcher == null) {
            this.blockRenderDispatcher = Minecraft.getInstance().getBlockRenderer();
        }
    }

    public void renderBlock(BlockState blockState, BlockPos blockPos, Vector3f pos) {
        this.pos = pos;
        this.blockPos = blockPos;
        this.blockState = blockState;
        this.random.setSeed(blockState.getSeed(blockPos));

        ChunkSectionLayer chunkLayer = ItemBlockRenderTypes.getChunkRenderType(blockState);
        TerrainRenderType renderType = TerrainRenderType.get(chunkLayer);
        renderType = TerrainRenderType.getRemapped(renderType);
        this.renderType = renderType;
        this.terrainBuilder = this.resources.builderPack.builder(renderType);
        this.terrainBuilder.setBlockAttributes(blockState);

        BlockStateModel model = this.blockRenderDispatcher.getBlockModel(blockState);

        BlockAndTintGetter renderRegion = this.renderRegion;
        Vec3 offset = blockState.getOffset(blockPos);
        pos.add((float) offset.x, (float) offset.y, (float) offset.z);

        this.prepareForBlock(blockState, blockPos, chunkLayer, blockState.getLightEmission() == 0);

        model.emitQuads(this.getEmitter(), renderRegion, blockPos, blockState, this.random, this.cullTest);
    }

    @Override
    protected VertexConsumer getVertexConsumer(ChunkSectionLayer layer) {
        return null;
    }

    protected void endRenderQuad(MutableQuadViewImpl quad) {
        final TriState aoMode = quad.ambientOcclusion();
        final boolean ao = this.useAO && (aoMode == TriState.TRUE || (aoMode == TriState.DEFAULT && this.defaultAO));
        final boolean emissive = quad.emissive();
        final boolean vanillaShade = quad.shadeMode() == ShadeMode.VANILLA;

        TerrainBuilder terrainBuilder = getBufferBuilder(quad.renderLayer());

        LightPipeline lightPipeline = ao ? this.smoothLightPipeline : this.flatLightPipeline;

        tintQuad(quad);
        shadeQuad(quad, lightPipeline, emissive, vanillaShade);
        bufferQuad(terrainBuilder, this.pos, quad, this.quadLightData);
    }

    private TerrainBuilder getBufferBuilder(ChunkSectionLayer layer) {
        if (layer == null) {
            return this.terrainBuilder;
        } else {
            TerrainRenderType renderType = TerrainRenderType.get(layer);
            renderType = TerrainRenderType.getRemapped(renderType);
            TerrainBuilder bufferBuilder = this.resources.builderPack.builder(renderType);
            bufferBuilder.setBlockAttributes(this.blockState);

            return bufferBuilder;
        }
    }

    public void bufferQuad(TerrainBuilder terrainBuilder, Vector3f pos, ModelQuadView quad, QuadLightData quadLightData) {
        QuadFacing quadFacing = quad.getQuadFacing();

        if (renderType == TerrainRenderType.TRANSLUCENT || !this.backFaceCulling) {
            quadFacing = QuadFacing.UNDEFINED;
        }

        TerrainBufferBuilder bufferBuilder = terrainBuilder.getBufferBuilder(quadFacing.ordinal());

        int packedNormal = quad.getNormal();

        float[] brightnessArr = quadLightData.br;
        int[] lights = quadLightData.lm;

        // Rotate triangles if needed to fix AO anisotropy
        int idx = QuadUtils.getIterationStartIdx(brightnessArr, lights);

        bufferBuilder.ensureCapacity();

        for (byte i = 0; i < 4; ++i) {
            final float x = pos.x() + quad.getX(idx);
            final float y = pos.y() + quad.getY(idx);
            final float z = pos.z() + quad.getZ(idx);

            final int quadColor = quad.getColor(idx);

            int color = ColorUtil.ARGB.toRGBA(quadColor);

            final int light = lights[idx];
            final float u = quad.getU(idx);
            final float v = quad.getV(idx);

            bufferBuilder.vertex(x, y, z, color, u, v, light, packedNormal);

            idx = (idx + 1) & 0b11;
        }

    }

}

