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
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.Initializer;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.shader.CustomRenderPipelines;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Pre-builds known Vulkan {@code VkPipeline} handles during world load using the driver pipeline
 * cache, compiled SPIR-V shader cache, and persisted {@link PipelineStateStore} ledger so draws do
 * not stall mid-frame on first encounter.
 */
public final class PipelineWarmup {
    private static final AtomicBoolean FIRST_LOGGED = new AtomicBoolean(false);

    private PipelineWarmup() {
    }

    public static boolean isEnabled() {
        String property = System.getProperty("voolkan.pipelineWarmup");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return Initializer.CONFIG != null && Initializer.CONFIG.pipelineCache;
    }

    /**
     * Runs pipeline warm-up on the render thread when entering a world. Never throws into callers.
     */
    public static void onWorldLoad() {
        if (!isEnabled() || !RenderSystem.isOnRenderThread()) {
            return;
        }

        Renderer renderer = Renderer.getInstance();
        if (renderer == null) {
            return;
        }

        MainPass mainPass = renderer.getMainPass();
        SwapChain swapChain = renderer.getSwapChain();
        if (mainPass == null || swapChain == null || !swapChain.hasImages()) {
            return;
        }

        // Never warm up against the 10x10 minimised dummy framebuffer or an active scaled world pass
        if (mainPass.isWorldActive() || mainPass.getMainFramebuffer() != swapChain) {
            return;
        }

        RenderPass mainRenderPass = mainPass.getMainRenderPass();
        if (mainRenderPass == null) {
            return;
        }
        if (!Vulkan.DYNAMIC_RENDERING && mainRenderPass.getId() == VK_NULL_HANDLE) {
            return;
        }

        long startNs = System.nanoTime();
        int built = 0;
        int alreadyReady = 0;
        int builtFromSaved = 0;

        // Step A: ensure GraphicsPipeline / VkShaderModule objects exist for all static pipelines
        GpuDevice gpuDevice = RenderSystem.getDevice();
        if (gpuDevice instanceof VkGpuDevice vkDevice) {
            ensureCompiled(vkDevice, RenderPipelines.getStaticPipelines());
            ensureCompiled(vkDevice, CustomRenderPipelines.pipelines);
        }

        // Step B(i): pre-build VkPipeline handles for static vanilla & custom RenderPipelines
        int[] counts = new int[2]; // [0] = built, [1] = alreadyReady
        warmStaticPipelines(RenderPipelines.getStaticPipelines(), mainRenderPass, counts);
        warmStaticPipelines(CustomRenderPipelines.pipelines, mainRenderPass, counts);

        // Step B(ii): pre-build VulkanMod's built-in PipelineManager pipelines
        warmBuiltInPipelines(mainRenderPass, counts);

        built += counts[0];
        alreadyReady += counts[1];

        // Step B(iii): pre-build saved states from previous sessions compatible with mainRenderPass
        PipelineStateStore.loadOnce();
        List<PipelineStateStore.StateRecord> saved = PipelineStateStore.getObservedSnapshot();
        if (!saved.isEmpty()) {
            int mainColorFmt = mainRenderPass.getColorFormat();
            int mainDepthFmt = mainRenderPass.getDepthFormat();
            int mainDepKind = mainRenderPass.getDependencyKind();

            Map<String, List<GraphicsPipeline>> byName = indexLiveGraphicsPipelines();
            for (PipelineStateStore.StateRecord rec : saved) {
                if (rec.colorFormat() != mainColorFmt
                        || rec.depthFormat() != mainDepthFmt
                        || rec.dependencyKind() != mainDepKind) {
                    continue;
                }

                List<GraphicsPipeline> targets = byName.get(rec.pipelineName());
                if (targets == null) {
                    continue;
                }

                PipelineState state = new PipelineState(
                        rec.assemblyRasterState(),
                        rec.blendState(),
                        rec.depthState(),
                        rec.logicOp(),
                        rec.colorMask(),
                        mainRenderPass);

                if (!isStateSupportedByDevice(state)) {
                    continue;
                }

                for (GraphicsPipeline gp : targets) {
                    int res = gp.warmUpState(state);
                    if (res == GraphicsPipeline.WARMUP_BUILT) {
                        built++;
                        builtFromSaved++;
                    } else if (res == GraphicsPipeline.WARMUP_ALREADY_READY) {
                        alreadyReady++;
                    }
                }
            }
        }

        boolean firstRun = FIRST_LOGGED.compareAndSet(false, true);
        if (built > 0 || firstRun) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            Initializer.LOGGER.info(
                    "Pipeline warm-up: built {} pipelines ({} already ready, {} from saved states) in {} ms",
                    built, alreadyReady, builtFromSaved, elapsedMs);
        }
    }

    private static void ensureCompiled(VkGpuDevice vkDevice, Iterable<RenderPipeline> pipelines) {
        for (RenderPipeline rp : pipelines) {
            if (rp == null) {
                continue;
            }
            ExtendedRenderPipeline ext = ExtendedRenderPipeline.of(rp);
            if (ext.getPipeline() != null) {
                continue;
            }
            try {
                vkDevice.compilePipeline(rp);
            } catch (RuntimeException e) {
                Initializer.LOGGER.debug("Pipeline warm-up: skipped compiling {}: {}", rp.getLocation(), e.getMessage());
            }
        }
    }

    private static void warmStaticPipelines(Iterable<RenderPipeline> pipelines, RenderPass mainRenderPass, int[] counts) {
        for (RenderPipeline rp : pipelines) {
            if (rp == null) {
                continue;
            }
            Pipeline p = ExtendedRenderPipeline.of(rp).getPipeline();
            if (!(p instanceof GraphicsPipeline gp)) {
                continue;
            }

            PipelineState state = deriveState(rp, mainRenderPass);
            if (state == null || !isStateSupportedByDevice(state)) {
                continue;
            }

            recordResult(gp.warmUpState(state), counts);
        }
    }

    private static void warmBuiltInPipelines(RenderPass mainRenderPass, int[] counts) {
        int defaultLogicOp = PipelineState.encodeLogicOpState(false, 0);
        int depthLequalWrite = PipelineState.encodeDepthState(true, true, GL11.GL_LEQUAL);
        int colorMaskAll = PipelineState.ColorMask.getColorMask(true, true, true, true);
        int colorMaskNone = PipelineState.ColorMask.getColorMask(false, false, false, false);
        int triFillCull = PipelineState.AssemblyRasterState.encode(true, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
        int triFillNoCull = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);

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
        if (PipelineState.LogicOpState.enable(state.getLogicOpStateValue())) {
            if (DeviceManager.device == null || !DeviceManager.device.availableFeatures.features().logicOp()) {
                return false;
            }
        }

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
