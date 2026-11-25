package net.vulkanmod.render.chunk.build.task;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * configurable per-frame chunk upload budget, high-priority upload queue, generation checks, thread pool scaling, and leak prevention.
 */

import com.google.common.collect.Queues;
import dev.voolkan.render.ChunkUploadBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;

public class TaskDispatcher {
    private final Queue<CompileResult> highPriorityResults = Queues.newLinkedBlockingDeque();
    private final Queue<CompileResult> compileResults = Queues.newLinkedBlockingDeque();

    private volatile boolean stopThreads;
    private Thread[] threads;
    private BuilderResources[] resources;
    private volatile int idleThreads;
    private final Queue<ChunkTask> highPriorityTasks = Queues.newConcurrentLinkedQueue();
    private final Queue<ChunkTask> lowPriorityTasks = Queues.newConcurrentLinkedQueue();

    public TaskDispatcher() {
        this.stopThreads = true;
    }

    public void createThreads() {
        createThreads(0);
    }

    public void createThreads(int n) {
        if (!this.stopThreads) {
            this.stopThreads();
        }

        this.stopThreads = false;

        if (this.resources != null) {
            for (BuilderResources resources : this.resources) {
                resources.free();
            }
        }

        // Auto select thread count
        if (n == 0) {
            int available = Runtime.getRuntime().availableProcessors();
            n = Math.clamp((int) Math.round((available - 2) * 0.6), 1, 8);
        }

        this.threads = new Thread[n];
        this.resources = new BuilderResources[n];

        for (int i = 0; i < n; i++) {
            BuilderResources builderResources = new BuilderResources();
            Thread thread = new Thread(() -> runTaskThread(builderResources),
                                       "Builder-" + i);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));

            this.threads[i] = thread;
            this.resources[i] = builderResources;
            thread.start();
        }
    }

    private void runTaskThread(BuilderResources builderResources) {
        while (!this.stopThreads) {
            ChunkTask task = this.pollTask();

            if (task == null) {
                synchronized (this) {
                    this.idleThreads++;
                    try {
                        task = this.pollTask();
                        if (task == null && !this.stopThreads) {
                            this.wait();
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } finally {
                        this.idleThreads--;
                    }
                }
            }

            if (task == null)
                continue;

            task.runTask(builderResources);
        }
    }

    public void schedule(ChunkTask chunkTask) {
        if (chunkTask == null)
            return;

        if (chunkTask.highPriority) {
            this.highPriorityTasks.offer(chunkTask);
        }
        else {
            this.lowPriorityTasks.offer(chunkTask);
        }

        if (this.idleThreads > 0) {
            synchronized (this) {
                this.notify();
            }
        }
    }

    @Nullable
    private ChunkTask pollTask() {
        ChunkTask task = this.highPriorityTasks.poll();

        if (task == null)
            task = this.lowPriorityTasks.poll();

        return task;
    }

    public void stopThreads() {
        if (this.stopThreads)
            return;

        this.stopThreads = true;

        synchronized (this) {
            this.notifyAll();
        }

        for (Thread thread : this.threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    public boolean updateSections() {
        CompileResult result;
        boolean flag = false;
        boolean skipBudget = isWorldLoading();
        MemoryType terrainMemory = MemoryTypes.getUnifiedOrGpuMem();
        boolean usesStaging = terrainMemory != null && !terrainMemory.mappable();
        int configuredBudget = ChunkUploadBudget.getConfigured();
        long startTime = System.nanoTime();

        int highUploaded = 0;
        long totalBytes = 0L;

        // Drain high-priority updates (player block edits & rebuilds of visible sections) before
        // background streaming so interactive edits never stall behind flying/teleporting loads.
        while ((result = this.highPriorityResults.poll()) != null) {
            long resultBytes = result.estimateUploadBytes();
            if (doSectionUpdate(result)) {
                flag = true;
                highUploaded++;
                totalBytes += resultBytes;

                if (ChunkUploadBudget.shouldStopHighPriority(
                        skipBudget, usesStaging, highUploaded, totalBytes, System.nanoTime() - startTime)) {
                    return flag;
                }
            }
        }

        int bgUploaded = 0;
        int bgEmptyApplied = 0;
        while ((result = this.compileResults.poll()) != null) {
            long resultBytes = result.estimateUploadBytes();
            if (doSectionUpdate(result)) {
                flag = true;
                if (resultBytes > 0L) {
                    bgUploaded++;
                    totalBytes += resultBytes;
                } else {
                    bgEmptyApplied++;
                }

                if (bgUploaded > 0 && ChunkUploadBudget.shouldStopBackground(
                        configuredBudget,
                        skipBudget,
                        usesStaging,
                        bgUploaded,
                        totalBytes,
                        System.nanoTime() - startTime,
                        this.compileResults.size())) {
                    break;
                }

                // Cap empty air section applications only if an extreme backlog eats frame time,
                // without burning explicit GPU upload slots needed for real terrain geometry.
                if (!skipBudget && bgEmptyApplied >= 128 && (System.nanoTime() - startTime) >= 2_500_000L) {
                    break;
                }
            }
        }

        return flag;
    }

    private boolean isWorldLoading() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return false;
        if (minecraft.getOverlay() != null) return true;
        Screen screen = minecraft.screen;
        return screen instanceof LevelLoadingScreen || screen instanceof ProgressScreen;
    }

    public void scheduleSectionUpdate(CompileResult compileResult) {
        if (compileResult.highPriority) {
            this.highPriorityResults.add(compileResult);
        } else {
            this.compileResults.add(compileResult);
        }
    }

    private boolean doSectionUpdate(CompileResult compileResult) {
        RenderSection section = compileResult.renderSection;

        // Drop stale results from superseded builds or recycled sections so low-priority results
        // can never overwrite newer high-priority player edits or upload to a moved section.
        if (compileResult.generation != section.getCompileGeneration()) {
            compileResult.renderedLayers.values()
                                        .forEach(UploadBuffer::release);
            return false;
        }

        ChunkArea renderArea = section.getChunkArea();
        ChunkAreaManager chunkAreaManager = WorldRenderer.getInstance().getChunkAreaManager();
        if (renderArea == null || chunkAreaManager == null || chunkAreaManager.getChunkArea(renderArea.index) != renderArea) {
            compileResult.renderedLayers.values()
                                        .forEach(UploadBuffer::release);
            return false;
        }

        DrawBuffers drawBuffers = renderArea.getDrawBuffers();

        if (compileResult.fullUpdate) {
            var renderLayers = compileResult.renderedLayers;
            for (TerrainRenderType renderType : TerrainRenderType.VALUES) {
                UploadBuffer uploadBuffer = renderLayers.get(renderType);

                if (uploadBuffer != null) {
                    drawBuffers.upload(section, uploadBuffer, renderType);
                }
                else {
                    section.resetDrawParameters(renderType);
                }
            }

            compileResult.updateSection();
        }
        else {
            if (compileResult.generation != section.getCompiledGeneration()
                    || !section.getCompiledSection().hasTransparencyState()
                    || drawBuffers.getIndexBuffer() == null) {
                compileResult.renderedLayers.values()
                                            .forEach(UploadBuffer::release);
                return false;
            }
            UploadBuffer uploadBuffer = compileResult.renderedLayers.get(TerrainRenderType.TRANSLUCENT);
            if (uploadBuffer != null) {
                drawBuffers.upload(section, uploadBuffer, TerrainRenderType.TRANSLUCENT);
            }
        }
        return true;
    }

    public boolean isIdle() {
        return this.idleThreads == this.threads.length
                && this.highPriorityResults.isEmpty()
                && this.compileResults.isEmpty();
    }

    public void clearBatchQueue() {
        while (!this.highPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.highPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.cancel();
            }
        }

        while (!this.lowPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.lowPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.cancel();
            }
        }

        CompileResult result;
        while ((result = this.highPriorityResults.poll()) != null) {
            result.renderedLayers.values().forEach(UploadBuffer::release);
        }
        while ((result = this.compileResults.poll()) != null) {
            result.renderedLayers.values().forEach(UploadBuffer::release);
        }
    }

    public String getStats() {
        int taskCount = highPriorityTasks.size() + lowPriorityTasks.size();
        return String.format("iT: %d Ts: %d", this.idleThreads, taskCount);
    }

    public BuilderResources[] getResourcesArray() {
        return resources;
    }
}
