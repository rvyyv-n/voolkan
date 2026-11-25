/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.cache;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.voolkan.compat.MacPerfOpts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.vulkanmod.Initializer;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.mixin.compatibility.PostChainAccessor;
import net.vulkanmod.mixin.compatibility.PostPassAccessor;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.shader.CustomRenderPipelines;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Pre-builds known Vulkan {@code VkPipeline} handles incrementally on the render thread during
 * world load using the driver pipeline cache, compiled SPIR-V shader cache, and persisted
 * {@link PipelineStateStore} ledger so draws do not stall mid-frame on first encounter while
 * keeping the loading screen responsive with live progress reporting.
 */
public final class PipelineWarmup {
    private static final long INITIAL_BURST_BUDGET_NS = 8_000_000L;
    private static final long LOADING_SCREEN_BUDGET_NS = 20_000_000L;
    private static final long IN_WORLD_BUDGET_NS = 3_000_000L;
    private static final long INDICATOR_DELAY_NS = 100_000_000L;

    public record Progress(boolean active, int completed, int total, long elapsedMs) {
        public float fraction() {
            if (total <= 0) {
                return 1.0f;
            }
            return Math.min(1.0f, Math.max(0.0f, (float) completed / (float) total));
        }
    }

    private sealed interface WarmupTask permits StaticPipelineTask, BuiltInTerrainTask, BuiltInCloudsTask, MenuBlurTask, SavedStateTask {}

    private record StaticPipelineTask(RenderPipeline pipeline) implements WarmupTask {}
    private record BuiltInTerrainTask() implements WarmupTask {}
    private record BuiltInCloudsTask() implements WarmupTask {}
    private record MenuBlurTask() implements WarmupTask {}
    private record SavedStateTask(PipelineStateStore.StateRecord record) implements WarmupTask {}

    private static final AtomicBoolean FIRST_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static final AtomicBoolean DEFERRED_WORLD_LOAD = new AtomicBoolean(false);
    private static final AtomicInteger COMPLETED_TASKS = new AtomicInteger(0);
    private static final AtomicInteger TOTAL_TASKS = new AtomicInteger(0);
    private static volatile boolean sessionStartSet = false;
    private static volatile long sessionStartNs = 0L;
    private static volatile boolean clearPending = false;

    // Render-thread-only incremental work state
    private static final Deque<WarmupTask> PENDING_TASKS = new ArrayDeque<>();
    private static Map<String, List<GraphicsPipeline>> livePipelinesByName;
    private static int sessionBuilt = 0;
    private static int sessionAlreadyReady = 0;
    private static int sessionBuiltFromSaved = 0;

    private PipelineWarmup() {
    }

    public static boolean isEnabled() {
        String property = System.getProperty("voolkan.pipelineWarmup");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return Initializer.CONFIG != null && Initializer.CONFIG.pipelineCache;
    }

    /** Thread-safe snapshot of current warm-up progress. */
    public static Progress getProgress() {
        boolean isActive = ACTIVE.get();
        int total = TOTAL_TASKS.get();
        int completed = Math.min(COMPLETED_TASKS.get(), total);
        long elapsedMs = (isActive && sessionStartSet)
                ? Math.max(0L, (System.nanoTime() - sessionStartNs) / 1_000_000L)
                : 0L;
        return new Progress(isActive, completed, total, elapsedMs);
    }

    /** Returns true when warm-up is actively running long enough to merit showing the progress bar. */
    public static boolean shouldShowIndicator() {
        if (!ACTIVE.get() || !sessionStartSet) {
            return false;
        }
        int total = TOTAL_TASKS.get();
        int completed = COMPLETED_TASKS.get();
        if (total <= 0 || completed >= total) {
            return false;
        }
        return (System.nanoTime() - sessionStartNs) >= INDICATOR_DELAY_NS;
    }

    /**
     * Begins incremental pipeline warm-up on the render thread when entering a world.
     * Warm cache hits finish immediately inside the initial burst without flashing a bar.
     */
    public static void onWorldLoad() {
        beginWorldLoad();
    }

    public static void beginWorldLoad() {
        if (!isEnabled() || !RenderSystem.isOnRenderThread()) {
            return;
        }

        cancel();

        RenderPass mainRenderPass = currentSafeMainRenderPass();
        if (mainRenderPass == null) {
            // Window may be minimized at world join; defer warm-up until the swapchain is ready again.
            DEFERRED_WORLD_LOAD.set(true);
            return;
        }

        for (RenderPipeline rp : RenderPipelines.getStaticPipelines()) {
            if (rp != null) {
                PENDING_TASKS.addLast(new StaticPipelineTask(rp));
            }
        }
        for (RenderPipeline rp : CustomRenderPipelines.pipelines) {
            if (rp != null) {
                PENDING_TASKS.addLast(new StaticPipelineTask(rp));
            }
        }
        PENDING_TASKS.addLast(new BuiltInTerrainTask());
        PENDING_TASKS.addLast(new BuiltInCloudsTask());
        if (MacPerfOpts.menuBlurOpts()) {
            PENDING_TASKS.addLast(new MenuBlurTask());
        }

        PipelineStateStore.loadOnce();
        List<PipelineStateStore.StateRecord> saved = PipelineStateStore.getObservedSnapshot();
        int mainColorFmt = mainRenderPass.getColorFormat();
        int mainDepthFmt = mainRenderPass.getDepthFormat();
        int mainDepKind = mainRenderPass.getDependencyKind();
        for (PipelineStateStore.StateRecord rec : saved) {
            if (rec.colorFormat() == mainColorFmt
                    && rec.depthFormat() == mainDepthFmt
                    && rec.dependencyKind() == mainDepKind) {
                PENDING_TASKS.addLast(new SavedStateTask(rec));
            }
        }

        int total = PENDING_TASKS.size();
        if (total == 0) {
            return;
        }

        sessionBuilt = 0;
        sessionAlreadyReady = 0;
        sessionBuiltFromSaved = 0;
        livePipelinesByName = null;
        clearPending = false;
        sessionStartNs = System.nanoTime();
        sessionStartSet = true;
        TOTAL_TASKS.set(total);
        COMPLETED_TASKS.set(0);
        ACTIVE.set(true);

        // Run a short initial burst so already-cached pipelines complete immediately with zero UI flash.
        stepWithBudget(INITIAL_BURST_BUDGET_NS);
    }

    /**
     * Advances incremental warm-up on the render thread once per frame.
     * Safe no-op when inactive or when the swapchain is minimized/unavailable.
     */
    public static void stepFrame() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        if (clearPending && !ACTIVE.get()) {
            clearRenderThreadState();
            clearPending = false;
        }
        if (!ACTIVE.get()) {
            if (DEFERRED_WORLD_LOAD.get() && currentSafeMainRenderPass() != null
                    && DEFERRED_WORLD_LOAD.compareAndSet(true, false)) {
                beginWorldLoad();
            }
            return;
        }
        long budgetNs = isOnLoadingScreen() ? LOADING_SCREEN_BUDGET_NS : IN_WORLD_BUDGET_NS;
        stepWithBudget(budgetNs);
    }

    /** Cancels any pending warm-up work cleanly (e.g. on disconnect or Vulkan shutdown). */
    public static void cancel() {
        DEFERRED_WORLD_LOAD.set(false);
        ACTIVE.set(false);
        COMPLETED_TASKS.set(0);
        TOTAL_TASKS.set(0);
        sessionStartSet = false;
        sessionStartNs = 0L;
        if (RenderSystem.isOnRenderThread()) {
            clearRenderThreadState();
            clearPending = false;
        } else {
            clearPending = true;
        }
    }

    private static void clearRenderThreadState() {
        PENDING_TASKS.clear();
        livePipelinesByName = null;
        sessionBuilt = 0;
        sessionAlreadyReady = 0;
        sessionBuiltFromSaved = 0;
    }

    private static void stepWithBudget(long budgetNs) {
        if (!ACTIVE.get()) {
            return;
        }
        if (PENDING_TASKS.isEmpty()) {
            finishSession();
            return;
        }

        // Re-fetch live MainPass / RenderPass each frame so window resize or swapchain recreation
        // never passes a destroyed legacy VkRenderPass handle.
        Renderer renderer = Renderer.getInstance();
        MainPass mainPass = renderer != null ? renderer.getMainPass() : null;
        RenderPass mainRenderPass = currentSafeMainRenderPass();
        if (mainPass == null || mainRenderPass == null) {
            return;
        }

        GpuDevice gpuDevice = RenderSystem.getDevice();
        VkGpuDevice vkDevice = gpuDevice instanceof VkGpuDevice vk ? vk : null;
        int[] counts = new int[2]; // [0] = built, [1] = alreadyReady
        long stepStart = System.nanoTime();

        while (!PENDING_TASKS.isEmpty()) {
            if (!ACTIVE.get()) {
                clearRenderThreadState();
                clearPending = false;
                return;
            }
            WarmupTask task = PENDING_TASKS.pollFirst();
            counts[0] = 0;
            counts[1] = 0;

            try {
                if (task instanceof StaticPipelineTask st) {
                    runStaticPipelineTask(vkDevice, st.pipeline(), mainRenderPass, counts);
                    sessionBuilt += counts[0];
                    sessionAlreadyReady += counts[1];
                } else if (task instanceof BuiltInTerrainTask) {
                    warmBuiltInTerrain(mainRenderPass, counts);
                    sessionBuilt += counts[0];
                    sessionAlreadyReady += counts[1];
                } else if (task instanceof BuiltInCloudsTask) {
                    warmBuiltInClouds(mainRenderPass, counts);
                    sessionBuilt += counts[0];
                    sessionAlreadyReady += counts[1];
                } else if (task instanceof MenuBlurTask) {
                    if (vkDevice != null && MacPerfOpts.menuBlurOpts()) {
                        warmMenuBlurPipelines(vkDevice, mainPass, counts);
                        sessionBuilt += counts[0];
                        sessionAlreadyReady += counts[1];
                    }
                } else if (task instanceof SavedStateTask saved) {
                    if (livePipelinesByName == null) {
                        livePipelinesByName = indexLiveGraphicsPipelines();
                    }
                    runSavedStateTask(saved.record(), mainRenderPass, counts);
                    sessionBuilt += counts[0];
                    sessionBuiltFromSaved += counts[0];
                    sessionAlreadyReady += counts[1];
                }
            } catch (RuntimeException e) {
                Initializer.LOGGER.debug("Pipeline warm-up: skipped task {}: {}", task, e.getMessage());
            }

            if (!ACTIVE.get()) {
                clearRenderThreadState();
                clearPending = false;
                return;
            }
            COMPLETED_TASKS.incrementAndGet();

            if (System.nanoTime() - stepStart >= budgetNs) {
                break;
            }
        }

        if (ACTIVE.get() && PENDING_TASKS.isEmpty()) {
            finishSession();
        }
    }

    private static void finishSession() {
        if (!ACTIVE.compareAndSet(true, false)) {
            return;
        }
        long elapsedMs = sessionStartSet ? Math.max(0L, (System.nanoTime() - sessionStartNs) / 1_000_000L) : 0L;
        int built = sessionBuilt;
        int alreadyReady = sessionAlreadyReady;
        int builtFromSaved = sessionBuiltFromSaved;

        clearRenderThreadState();
        sessionStartSet = false;
        clearPending = false;

        boolean firstRun = FIRST_LOGGED.compareAndSet(false, true);
        if (built > 0 || firstRun) {
            Initializer.LOGGER.info(
                    "Pipeline warm-up: built {} pipelines ({} already ready, {} from saved states) in {} ms",
                    built, alreadyReady, builtFromSaved, elapsedMs);
        }
    }

    private static RenderPass currentSafeMainRenderPass() {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null) {
            return null;
        }
        MainPass mainPass = renderer.getMainPass();
        SwapChain swapChain = renderer.getSwapChain();
        if (mainPass == null || swapChain == null || !swapChain.hasImages()) {
            return null;
        }
        // Never warm up against the 10x10 minimised dummy framebuffer or an active scaled world pass
        if (mainPass.isWorldActive() || mainPass.getMainFramebuffer() != swapChain) {
            return null;
        }
        RenderPass mainRenderPass = mainPass.getMainRenderPass();
        if (mainRenderPass == null) {
            return null;
        }
        if (!Vulkan.DYNAMIC_RENDERING && mainRenderPass.getId() == VK_NULL_HANDLE) {
            return null;
        }
        return mainRenderPass;
    }

    private static boolean isOnLoadingScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        if (mc.getOverlay() != null) {
            return true;
        }
        Screen screen = mc.screen;
        return screen instanceof LevelLoadingScreen || screen instanceof ProgressScreen;
    }

    private static void runStaticPipelineTask(VkGpuDevice vkDevice, RenderPipeline rp, RenderPass mainRenderPass, int[] counts) {
        if (vkDevice != null) {
            ensureCompiledSingle(vkDevice, rp);
        }
        Pipeline p = ExtendedRenderPipeline.of(rp).getPipeline();
        if (!(p instanceof GraphicsPipeline gp)) {
            return;
        }
        PipelineState state = deriveState(rp, mainRenderPass);
        if (state == null || !isStateSupportedByDevice(state)) {
            return;
        }
        recordResult(gp.warmUpState(state), counts);
    }

    private static void runSavedStateTask(PipelineStateStore.StateRecord rec, RenderPass mainRenderPass, int[] counts) {
        if (rec.colorFormat() != mainRenderPass.getColorFormat()
                || rec.depthFormat() != mainRenderPass.getDepthFormat()
                || rec.dependencyKind() != mainRenderPass.getDependencyKind()) {
            return;
        }
        List<GraphicsPipeline> targets = livePipelinesByName != null ? livePipelinesByName.get(rec.pipelineName()) : null;
        if (targets == null) {
            return;
        }
        PipelineState state = new PipelineState(
                rec.assemblyRasterState(),
                rec.blendState(),
                rec.depthState(),
                rec.logicOp(),
                rec.colorMask(),
                mainRenderPass);
        if (!isStateSupportedByDevice(state)) {
            return;
        }
        for (GraphicsPipeline gp : targets) {
            recordResult(gp.warmUpState(state), counts);
        }
    }

    private static void ensureCompiledSingle(VkGpuDevice vkDevice, RenderPipeline rp) {
        if (rp == null) {
            return;
        }
        ExtendedRenderPipeline ext = ExtendedRenderPipeline.of(rp);
        if (ext.getPipeline() != null) {
            return;
        }
        try {
            vkDevice.compilePipeline(rp);
        } catch (RuntimeException e) {
            Initializer.LOGGER.debug("Pipeline warm-up: skipped compiling {}: {}", rp.getLocation(), e.getMessage());
        }
    }

    private static void warmBuiltInTerrain(RenderPass mainRenderPass, int[] counts) {
        int defaultLogicOp = PipelineState.encodeLogicOpState(false, 0);
        int depthLequalWrite = PipelineState.encodeDepthState(true, true, GL11.GL_LEQUAL);
        int colorMaskAll = PipelineState.ColorMask.getColorMask(true, true, true, true);
        int triFillCull = PipelineState.AssemblyRasterState.encode(true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);

        // Opaque terrain: blend disabled (canonical default factors/op)
        PipelineState.BlendInfo opaqueBlendInfo = PipelineState.defaultBlendInfo();
        opaqueBlendInfo.enabled = false;
        int opaqueBlend = PipelineState.BlendState.getState(opaqueBlendInfo);

        PipelineState terrainOpaque = new PipelineState(
                triFillCull, opaqueBlend, depthLequalWrite, defaultLogicOp, colorMaskAll, mainRenderPass);

        // Translucent terrain: WorldRenderer.renderSectionLayer sets SRC_ALPHA/ONE_MINUS_SRC_ALPHA, ONE/ONE_MINUS_SRC_ALPHA, depthMask=true
        PipelineState.BlendInfo translucentBlendInfo = PipelineState.defaultBlendInfo();
        translucentBlendInfo.enabled = true;
        translucentBlendInfo.setBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        int translucentBlend = PipelineState.BlendState.getState(translucentBlendInfo);

        PipelineState terrainTranslucent = new PipelineState(
                triFillCull, translucentBlend, depthLequalWrite, defaultLogicOp, colorMaskAll, mainRenderPass);

        GraphicsPipeline terrain = PipelineManager.getTerrainDirectShader(null);
        if (terrain != null) {
            recordResult(terrain.warmUpState(terrainOpaque), counts);
            recordResult(terrain.warmUpState(terrainTranslucent), counts);
        }

        if (Initializer.CONFIG != null && !Initializer.CONFIG.uniqueOpaqueLayer) {
            GraphicsPipeline terrainEarlyZ = PipelineManager.getTerrainIndirectShader(null);
            if (terrainEarlyZ != null) {
                recordResult(terrainEarlyZ.warmUpState(terrainOpaque), counts);
            }
        }

        Minecraft mc = Minecraft.getInstance();
        if (MacPerfOpts.isPlatformEligible() && mc != null && mc.options != null
                && mc.options.textureFiltering().get() == TextureFilteringMethod.RGSS) {
            GraphicsPipeline terrainRgss = PipelineManager.getTerrainRgssShader();
            if (terrainRgss != null) {
                recordResult(terrainRgss.warmUpState(terrainOpaque), counts);
                recordResult(terrainRgss.warmUpState(terrainTranslucent), counts);
            }
            if (Initializer.CONFIG != null && !Initializer.CONFIG.uniqueOpaqueLayer) {
                GraphicsPipeline terrainEarlyZRgss = PipelineManager.getTerrainEarlyZRgssShader();
                if (terrainEarlyZRgss != null) {
                    recordResult(terrainEarlyZRgss.warmUpState(terrainOpaque), counts);
                }
            }
        }
    }

    private static void warmBuiltInClouds(RenderPass mainRenderPass, int[] counts) {
        int defaultLogicOp = PipelineState.encodeLogicOpState(false, 0);
        int depthLequalWrite = PipelineState.encodeDepthState(true, true, GL11.GL_LEQUAL);
        int colorMaskAll = PipelineState.ColorMask.getColorMask(true, true, true, true);
        int colorMaskNone = PipelineState.ColorMask.getColorMask(false, false, false, false);
        int triFillCull = PipelineState.AssemblyRasterState.encode(true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
        int triFillNoCull = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);

        // Clouds: CloudRenderer uses SRC_ALPHA/ONE_MINUS_SRC_ALPHA, ONE/ZERO; cull on/off; colour mask all/none (fancy depth pre-pass)
        GraphicsPipeline clouds = PipelineManager.getCloudsPipeline();
        if (clouds != null) {
            PipelineState.BlendInfo cloudsBlendInfo = PipelineState.defaultBlendInfo();
            cloudsBlendInfo.enabled = true;
            cloudsBlendInfo.setBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            int cloudsBlend = PipelineState.BlendState.getState(cloudsBlendInfo);

            recordResult(clouds.warmUpState(new PipelineState(
                    triFillCull, cloudsBlend, depthLequalWrite, defaultLogicOp, colorMaskAll, mainRenderPass)), counts);
            recordResult(clouds.warmUpState(new PipelineState(
                    triFillCull, cloudsBlend, depthLequalWrite, defaultLogicOp, colorMaskNone, mainRenderPass)), counts);
            recordResult(clouds.warmUpState(new PipelineState(
                    triFillNoCull, cloudsBlend, depthLequalWrite, defaultLogicOp, colorMaskAll, mainRenderPass)), counts);
        }
    }

    private static void warmMenuBlurPipelines(VkGpuDevice vkDevice, MainPass mainPass, int[] counts) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getShaderManager() == null) {
            return;
        }

        PostChain blurChain;
        try {
            blurChain = mc.getShaderManager().getPostChain(
                    Identifier.withDefaultNamespace("blur"), LevelTargetBundle.MAIN_TARGETS);
        } catch (RuntimeException e) {
            Initializer.LOGGER.debug("Pipeline warm-up: skipped menu blur PostChain: {}", e.getMessage());
            return;
        }
        if (blurChain == null) {
            return;
        }

        RenderPass mainColorOnlyPass = mainPass.getColorOnlyDiscardPass();
        RenderPass mainFallbackPass = mainPass.getMainRenderPass();
        RenderPass targetMainPass = mainColorOnlyPass != null ? mainColorOnlyPass : mainFallbackPass;

        List<PostPass> passes = ((PostChainAccessor) (Object) blurChain).voolkan$getPasses();
        if (passes == null) {
            return;
        }

        for (PostPass pass : passes) {
            PostPassAccessor accessor = (PostPassAccessor) (Object) pass;
            RenderPipeline rp = accessor.voolkan$getPipeline();
            if (rp == null) {
                continue;
            }
            ensureCompiledSingle(vkDevice, rp);

            Pipeline p = ExtendedRenderPipeline.of(rp).getPipeline();
            if (!(p instanceof GraphicsPipeline gp)) {
                continue;
            }

            Identifier outId = accessor.voolkan$getOutputTargetId();
            if (PostChain.MAIN_TARGET_ID.equals(outId)) {
                if (targetMainPass != null) {
                    PipelineState state = deriveState(rp, targetMainPass);
                    if (state != null && isStateSupportedByDevice(state)) {
                        recordResult(gp.warmUpState(state), counts);
                    }
                }
            } else if (Vulkan.DYNAMIC_RENDERING && targetMainPass != null) {
                // Warm the offscreen depth-less RGBA8 swap target state directly under dynamic rendering.
                PipelineState base = deriveState(rp, targetMainPass);
                if (base != null && isStateSupportedByDevice(base)) {
                    PipelineState swapState = PipelineState.forFormats(
                            base.getAssemblyRasterStateValue(),
                            base.getBlendStateValue(),
                            base.getDepthStateValue(),
                            base.getLogicOpStateValue(),
                            base.getColorMaskValue(),
                            VK_FORMAT_R8G8B8A8_UNORM,
                            0,
                            2);
                    recordResult(gp.warmUpState(swapState), counts);
                }
            }
        }
    }

    /**
     * Derives the {@link PipelineState} for a vanilla/custom {@link RenderPipeline} matching
     * {@code VkCommandEncoder.applyPipelineState} without mutating global {@code VRenderSystem} state.
     */
    private static PipelineState deriveState(RenderPipeline rp, RenderPass mainRenderPass) {
        int topology = glToVulkanTopology(GlConst.toGl(rp.getVertexFormatMode()));
        int polygonMode = glToVulkanPolygonMode(GlConst.toGl(rp.getPolygonMode()));
        if (topology < 0 || polygonMode < 0) {
            return null;
        }

        int assemblyRasterState = PipelineState.AssemblyRasterState.encode(rp.isCull(), topology, polygonMode);

        PipelineState.BlendInfo blendInfo = PipelineState.defaultBlendInfo();
        if (rp.getBlendFunction().isPresent()) {
            BlendFunction bf = rp.getBlendFunction().get();
            blendInfo.enabled = true;
            blendInfo.setBlendFuncSeparate(
                    GlConst.toGl(bf.sourceColor()),
                    GlConst.toGl(bf.destColor()),
                    GlConst.toGl(bf.sourceAlpha()),
                    GlConst.toGl(bf.destAlpha()));
        } else {
            blendInfo.enabled = false;
        }
        int blendState = PipelineState.BlendState.getState(blendInfo);

        boolean depthTest = rp.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST;
        int glDepthFun = depthTest ? GlConst.toGl(rp.getDepthTestFunction()) : GL11.GL_LEQUAL;
        int depthState = PipelineState.encodeDepthState(depthTest, rp.isWriteDepth(), glDepthFun);

        int logicOp = switch (rp.getColorLogic()) {
            case NONE -> PipelineState.encodeLogicOpState(false, 0);
            case OR_REVERSE -> PipelineState.encodeLogicOpState(true, GL11.GL_OR_REVERSE);
        };

        boolean writeColor = rp.isWriteColor();
        int colorMask = PipelineState.ColorMask.getColorMask(writeColor, writeColor, writeColor, rp.isWriteAlpha());

        return new PipelineState(assemblyRasterState, blendState, depthState, logicOp, colorMask, mainRenderPass);
    }

    private static boolean isStateSupportedByDevice(PipelineState state) {
        // Logic-op states are allowed on all devices: GraphicsPipeline falls back to alpha blending when logicOp is unsupported.
        int polygonMode = PipelineState.AssemblyRasterState.decodePolygonMode(state.getAssemblyRasterStateValue());
        if (polygonMode != VK_POLYGON_MODE_FILL) {
            // Logical device creation does not enable fillModeNonSolid; skip non-fill modes
            return false;
        }

        return true;
    }

    private static Map<String, List<GraphicsPipeline>> indexLiveGraphicsPipelines() {
        Map<String, List<GraphicsPipeline>> map = new HashMap<>();
        for (Pipeline p : Pipeline.getRegisteredPipelines()) {
            if (p instanceof GraphicsPipeline gp && gp.name != null && !gp.name.isEmpty()) {
                map.computeIfAbsent(gp.name, k -> new ArrayList<>(1)).add(gp);
            }
        }
        return map;
    }

    private static void recordResult(int res, int[] counts) {
        if (res == GraphicsPipeline.WARMUP_BUILT) {
            counts[0]++;
        } else if (res == GraphicsPipeline.WARMUP_ALREADY_READY) {
            counts[1]++;
        }
    }

    private static int glToVulkanTopology(int glMode) {
        return switch (glMode) {
            case GL11.GL_LINES, GL11.GL_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case GL11.GL_TRIANGLE_FAN, GL11.GL_TRIANGLES, GL11.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case GL11.GL_POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            default -> -1;
        };
    }

    private static int glToVulkanPolygonMode(int glMode) {
        return switch (glMode) {
            case GL11.GL_POINT -> VK_POLYGON_MODE_POINT;
            case GL11.GL_LINE -> VK_POLYGON_MODE_LINE;
            case GL11.GL_FILL -> VK_POLYGON_MODE_FILL;
            default -> -1;
        };
    }
}
