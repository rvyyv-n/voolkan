package net.vulkanmod.render.chunk.build.task;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * skip air blocks early and omit empty TerrainRenderTypes so empty sections are detected and freed properly.
 */

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.renderer.BlockRenderer;
import net.vulkanmod.render.chunk.build.renderer.FluidRenderer;
import net.vulkanmod.render.chunk.build.RenderRegion;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class BuildTask extends ChunkTask {
    @Nullable
    protected RenderRegion region;

    public BuildTask(RenderSection renderSection, RenderRegion renderRegion, Vector3d cameraPos, boolean highPriority) {
        super(renderSection, cameraPos);
        this.region = renderRegion;
        this.highPriority = highPriority;
    }

    public String name() {
        return "rend_chk_rebuild";
    }

    public Result runTask(BuilderResources builderResources) {
        long startTime = System.nanoTime();

        if (this.cancelled.get()) {
            return Result.CANCELLED;
        }

        float x = (float) this.cameraPos.x;
        float y = (float) this.cameraPos.y;
        float z = (float) this.cameraPos.z;
        CompileResult compileResult = this.compile(x, y, z, builderResources);

        CompiledSection compiledSection = new CompiledSection();
        compiledSection.blockEntities.addAll(compileResult.blockEntities);
        compiledSection.transparencyState = compileResult.transparencyState;
        compiledSection.isCompletelyEmpty = compileResult.renderedLayers.isEmpty();
        compileResult.compiledSection = compiledSection;

        if (this.cancelled.get()) {
            compileResult.renderedLayers.values().forEach(UploadBuffer::release);
            return Result.CANCELLED;
        }

        taskDispatcher.scheduleSectionUpdate(compileResult);

        float buildTime = (System.nanoTime() - startTime) * 0.000001f;
        if (BENCH) {
            builderResources.updateBuildStats((int) buildTime);
        }

        return Result.SUCCESSFUL;
    }

    private CompileResult compile(float camX, float camY, float camZ, BuilderResources builderResources) {
        CompileResult compileResult = new CompileResult(this.section, true);

        final int secOriginX = section.xOffset();
        final int secOriginY = section.yOffset();
        final int secOriginZ = section.zOffset();
        VisGraph visGraph = new VisGraph();

        if (this.region == null) {
            compileResult.visibilitySet = visGraph.resolve();
            return compileResult;
        }

        Vector3f pos = new Vector3f();
        ThreadBuilderPack bufferBuilders = builderResources.builderPack;
        setupBufferBuilders(bufferBuilders);

        this.region.loadBlockStates(builderResources.blockDataScratch);
        this.region.initTintCache(builderResources.tintCache);

        builderResources.update(this.region, this.section);

        BlockRenderer blockRenderer = builderResources.blockRenderer;

        FluidRenderer fluidRenderer = builderResources.fluidRenderer;

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        for (int y = 0; y < 16; ++y) {
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    blockPos.set(secOriginX + x, secOriginY + y, secOriginZ + z);

                    BlockState blockState = this.region.getBlockState(blockPos);
                    if (blockState.isAir()) {
                        continue;
                    }
                    if (blockState.isSolidRender()) {
                        visGraph.setOpaque(blockPos);
                    }

                    if (blockState.hasBlockEntity()) {
                        BlockEntity blockEntity = this.region.getBlockEntity(blockPos);
                        if (blockEntity != null) {
                            this.handleBlockEntity(compileResult, blockEntity);
                        }
                    }

                    FluidState fluidState = blockState.getFluidState();
                    if (!fluidState.isEmpty()) {
                        fluidRenderer.renderLiquid(blockState, fluidState, blockPos);
                    }

                    if (blockState.getRenderShape() == RenderShape.MODEL) {
                        pos.set(x, y, z);
                        blockRenderer.renderBlock(blockState, blockPos, pos);
                    }
                }
            }
        }

        TerrainBuilder trasnlucentTerrainBuilder = bufferBuilders.builder(TerrainRenderType.TRANSLUCENT);
        if (trasnlucentTerrainBuilder.getBufferBuilder(QuadFacing.UNDEFINED.ordinal()).getVertices() > 0) {
            trasnlucentTerrainBuilder.setupQuadSortingPoints();
            trasnlucentTerrainBuilder.setupQuadSorting(camX - (float) secOriginX, camY - (float) secOriginY, camZ - (float) secOriginZ);
            compileResult.transparencyState = trasnlucentTerrainBuilder.getSortState();
        }

        for (TerrainRenderType renderType : TerrainRenderType.VALUES) {
            TerrainBuilder builder = bufferBuilders.builder(renderType);

            boolean hasGeometry = false;
            for (int i = 0; i < QuadFacing.COUNT; i++) {
                if (builder.getBufferBuilder(i).getVertices() > 0) {
                    hasGeometry = true;
                    break;
                }
            }

            TerrainBuilder.DrawState drawState = builder.endDrawing();
            if (hasGeometry && drawState != null) {
                UploadBuffer uploadBuffer = new UploadBuffer(builder, drawState);
                compileResult.renderedLayers.put(renderType, uploadBuffer);
            }

            builder.clear();
        }

        compileResult.visibilitySet = visGraph.resolve();
        this.region = null;
        return compileResult;
    }

    private void setupBufferBuilders(ThreadBuilderPack builderPack) {
        for (TerrainRenderType renderType : TerrainRenderType.VALUES) {
            TerrainBuilder bufferBuilder = builderPack.builder(renderType);
            bufferBuilder.begin();
        }
    }

    private TerrainBuilder getTerrainBuilder(ThreadBuilderPack bufferBuilders, TerrainRenderType renderType) {
        renderType = compactRenderTypes(renderType);
        return bufferBuilders.builder(renderType);
    }

    private TerrainRenderType compactRenderTypes(TerrainRenderType renderType) {
        if (Initializer.CONFIG.uniqueOpaqueLayer) {
            renderType = switch (renderType) {
                case SOLID, CUTOUT -> TerrainRenderType.CUTOUT;
                case TRANSLUCENT, TRIPWIRE -> TerrainRenderType.TRANSLUCENT;
            };
        } else {
            renderType = switch (renderType) {
                case SOLID -> TerrainRenderType.SOLID;
                case CUTOUT -> TerrainRenderType.CUTOUT;
                case TRANSLUCENT, TRIPWIRE -> TerrainRenderType.TRANSLUCENT;
            };
        }

        return renderType;
    }

    private <E extends BlockEntity> void handleBlockEntity(CompileResult compileResult, E blockEntity) {
        BlockEntityRenderer<E, BlockEntityRenderState> blockEntityRenderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (blockEntityRenderer != null) {
            compileResult.blockEntities.add(blockEntity);
            if (blockEntityRenderer.shouldRenderOffScreen()) {
                compileResult.globalBlockEntities.add(blockEntity);
            }
        }

    }
}
