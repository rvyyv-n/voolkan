package net.vulkanmod.render.chunk.build.task;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * propagate section compile generation through chunk build and sort tasks.
 */

import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.RenderRegion;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import org.joml.Vector3d;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ChunkTask {
    public static final boolean BENCH = true;

    protected static TaskDispatcher taskDispatcher;

    public static BuildTask createBuildTask(RenderSection renderSection, RenderRegion renderRegion, Vector3d cameraPos, boolean highPriority, int generation) {
        return new BuildTask(renderSection, renderRegion, cameraPos, highPriority, generation);
    }

    public static SortTransparencyTask createSortTask(RenderSection renderSection, Vector3d cameraPos, int generation, CompiledSection compiledSection) {
        return new SortTransparencyTask(renderSection, cameraPos, generation, compiledSection.transparencyState);
    }

    protected final RenderSection section;
    protected final Vector3d cameraPos;
    protected final int generation;
    protected AtomicBoolean cancelled = new AtomicBoolean(false);
    public boolean highPriority = false;

    ChunkTask(RenderSection renderSection, Vector3d cameraPos, int generation) {
        this.section = renderSection;
        this.cameraPos = cameraPos;
        this.generation = generation;
    }

    public abstract String name();

    public abstract Result runTask(BuilderResources builderResources);

    public void cancel() {
        this.cancelled.set(true);
    }

    public static void setTaskDispatcher(TaskDispatcher dispatcher) {
        taskDispatcher = dispatcher;
    }
    
    public enum Result {
        CANCELLED,
        SUCCESSFUL
    }
}
