package net.vulkanmod.render.chunk.build.task;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * carry upload priority, section generation, and upload byte estimation for budgeted chunk uploads.
 */

import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.interfaces.VisibilitySetExtended;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.vertex.QuadSorter;
import net.vulkanmod.render.vertex.TerrainRenderType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public class CompileResult {
    public final RenderSection renderSection;
    public final boolean fullUpdate;
    public final boolean highPriority;
    public final int generation;

    final List<BlockEntity> globalBlockEntities = new ArrayList<>();
    final List<BlockEntity> blockEntities = new ArrayList<>();
    public final EnumMap<TerrainRenderType, UploadBuffer> renderedLayers = new EnumMap<>(TerrainRenderType.class);

    VisibilitySet visibilitySet;
    QuadSorter.SortState transparencyState;
    CompiledSection compiledSection;

    CompileResult(RenderSection renderSection, boolean fullUpdate, boolean highPriority, int generation) {
        this.renderSection = renderSection;
        this.fullUpdate = fullUpdate;
        this.highPriority = highPriority;
        this.generation = generation;
    }

    public long estimateUploadBytes() {
        long bytes = 0L;
        for (UploadBuffer buf : renderedLayers.values()) {
            if (buf == null) {
                continue;
            }
            ByteBuffer vb = buf.getVertexBuffer();
            if (vb != null) {
                bytes += vb.remaining();
            }
            ByteBuffer ib = buf.getIndexBuffer();
            if (ib != null) {
                bytes += ib.remaining();
            }
        }
        return bytes;
    }

    public void updateSection() {
        this.renderSection.updateGlobalBlockEntities(globalBlockEntities);
        this.renderSection.setCompiledSection(compiledSection);
        this.renderSection.setCompiledGeneration(this.generation);
        this.renderSection.setVisibility(((VisibilitySetExtended)visibilitySet).getVisibility());
        this.renderSection.setCompletelyEmpty(compiledSection.isCompletelyEmpty);
        this.renderSection.setContainsBlockEntities(!blockEntities.isEmpty());
    }
}
