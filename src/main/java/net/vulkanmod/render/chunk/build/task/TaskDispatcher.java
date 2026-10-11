package net.vulkanmod.render.chunk.build.task;

import com.google.common.collect.Queues;
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
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;

public class TaskDispatcher {
    private final Queue<CompileResult> compileResults = Queues.newLinkedBlockingDeque();
    public final ThreadBuilderPack fixedBuffers;

    private volatile boolean stopThreads;
    private Thread[] threads;
    private BuilderResources[] resources;
    private int idleThreads;
    private final Queue<ChunkTask> highPriorityTasks = Queues.newConcurrentLinkedQueue();
    private final Queue<ChunkTask> lowPriorityTasks = Queues.newConcurrentLinkedQueue();

    public TaskDispatcher() {
        this.fixedBuffers = new ThreadBuilderPack();

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

            if (task == null)
                synchronized (this) {
                    try {
                        this.idleThreads++;
                        this.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    this.idleThreads--;
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

        synchronized (this) {
            this.notify();
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
        boolean skipBudget = this.compileResults.size() > 256 || isWorldLoading();
        long startTime = System.nanoTime();
        int processed = 0;

        while ((result = this.compileResults.poll()) != null) {
            flag = true;
            doSectionUpdate(result);
            processed++;

            if (!skipBudget && processed >= 3 && (System.nanoTime() - startTime) >= 2_500_000L) {
                break;
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
        this.compileResults.add(compileResult);
    }

    private void doSectionUpdate(CompileResult compileResult) {
        RenderSection section = compileResult.renderSection;
        ChunkArea renderArea = section.getChunkArea();
        DrawBuffers drawBuffers = renderArea.getDrawBuffers();

        // Check if area has been dismissed before uploading
        ChunkAreaManager chunkAreaManager = WorldRenderer.getInstance().getChunkAreaManager();
        if (chunkAreaManager.getChunkArea(renderArea.index) != renderArea) {
            compileResult.renderedLayers.values()
                                        .forEach(UploadBuffer::release);
            return;
        }

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
            UploadBuffer uploadBuffer = compileResult.renderedLayers.get(TerrainRenderType.TRANSLUCENT);
            drawBuffers.upload(section, uploadBuffer, TerrainRenderType.TRANSLUCENT);
        }
    }

    public boolean isIdle() {
        return this.idleThreads == this.threads.length && this.compileResults.isEmpty();
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
