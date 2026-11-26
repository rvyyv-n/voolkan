package net.vulkanmod.render.chunk.build.task;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * attach section compile generation to transparency sort results.
 */

import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.QuadSorter;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Vector3d;

public class SortTransparencyTask extends ChunkTask {
    private final QuadSorter.SortState transparencyState;

    public SortTransparencyTask(RenderSection renderSection, Vector3d cameraPos, int generation, QuadSorter.SortState transparencyState) {
        super(renderSection, cameraPos, generation);
        this.transparencyState = transparencyState;
    }

    public String name() {
        return "rend_chk_sort";
    }

    public Result runTask(BuilderResources context) {
        ThreadBuilderPack builderPack = context.builderPack;

        if (this.cancelled.get()) {
            return Result.CANCELLED;
        }

        Vector3d cameraPos = WorldRenderer.getCameraPos();
        float x = (float) cameraPos.x;
        float y = (float) cameraPos.y;
        float z = (float) cameraPos.z;

        QuadSorter.SortState transparencyState = this.transparencyState;

        if (transparencyState == null) {
            return Result.CANCELLED;
        }

        TerrainBuilder bufferBuilder = builderPack.builder(TerrainRenderType.TRANSLUCENT);
        bufferBuilder.begin();
        bufferBuilder.restoreSortState(transparencyState);

        bufferBuilder.setupQuadSorting(x - (float) this.section.xOffset(), y - (float) this.section.yOffset(), z - (float) this.section.zOffset());
        TerrainBuilder.DrawState drawState = bufferBuilder.endDrawing();

        CompileResult compileResult = new CompileResult(this.section, false, false, this.generation);
        UploadBuffer uploadBuffer = new UploadBuffer(bufferBuilder, drawState);
        compileResult.renderedLayers.put(TerrainRenderType.TRANSLUCENT, uploadBuffer);

        bufferBuilder.reset();

        if (this.cancelled.get()) {
            compileResult.renderedLayers.values().forEach(UploadBuffer::release);
            return Result.CANCELLED;
        }

        taskDispatcher.scheduleSectionUpdate(compileResult);
        return Result.SUCCESSFUL;
    }
}
