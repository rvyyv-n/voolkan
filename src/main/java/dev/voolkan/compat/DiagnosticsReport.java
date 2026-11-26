/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import dev.voolkan.cache.PipelineWarmup;
import dev.voolkan.cache.ShaderBinaryCache;
import dev.voolkan.latency.FrameLimiter;
import dev.voolkan.power.PowerSources;
import dev.voolkan.render.WorldScale;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.SystemInfo;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.StringJoiner;

/**
 * Builds a strictly sanitized, plain-text diagnostics bundle (GPU/driver/Vulkan/MoltenVK
 * capabilities, swapchain state, active optimizations, settings, and recent sanitized
 * warnings/errors) for one-click clipboard copying.
 */
public final class DiagnosticsReport {
    private DiagnosticsReport() {
    }

    /** Builds the sanitized diagnostics report (contains zero usernames or local paths). */
    public static String buildReport() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("=== voolkan Diagnostics ===\n");
        sb.append("voolkan: ").append(Initializer.getVersion() != null ? Initializer.getVersion() : "unknown").append('\n');

        String mcVer = "unknown";
        try {
            mcVer = SharedConstants.getCurrentVersion().name();
        } catch (Throwable ignored) {
        }
        sb.append("Minecraft: ").append(mcVer).append('\n');
        sb.append("OS: ").append(System.getProperty("os.name", "unknown"))
          .append(' ').append(System.getProperty("os.version", ""))
          .append(" (").append(System.getProperty("os.arch", "unknown")).append(")")
          .append(", platform=").append(Platform.getActivePlat()).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version", "unknown"))
          .append(" (").append(System.getProperty("java.vm.name", "unknown")).append(")\n");

        try {
            sb.append("CPU: ").append(SystemInfo.cpuInfo).append('\n');
        } catch (Throwable ignored) {
        }
        try {
            sb.append("GLFW: ").append(GLFW.glfwGetVersionString()).append('\n');
        } catch (Throwable ignored) {
        }

        // Startup capability summary
        sb.append('\n');
        String caps = StartupLog.getCapabilitiesSummary();
        if (caps == null || caps.isEmpty()) {
            caps = StartupLog.buildStartupCapabilitiesSummary();
        }
        if (caps != null && !caps.isEmpty()) {
            sb.append(caps).append('\n');
        } else {
            sb.append("Vulkan startup capabilities: unavailable\n");
        }

        // Swapchain & present mode summary
        String swap = StartupLog.getSwapChainSummary();
        if (swap != null && !swap.isEmpty()) {
            sb.append(swap).append('\n');
        }
        SwapChain swapChain = Renderer.getInstance() != null ? Renderer.getInstance().getSwapChain() : null;
        if (swapChain != null) {
            sb.append("Active present mode: ").append(StartupLog.presentModeName(swapChain.getActivePresentMode()))
              .append(", vsync=").append(swapChain.isVsync())
              .append(", adaptiveVsyncSupported=").append(SwapChain.isAdaptiveVsyncSupported())
              .append('\n');
        }

        // Active runtime features & caches
        sb.append("\nRuntime optimizations:\n");
        sb.append("  RenderPath: ").append(Vulkan.DYNAMIC_RENDERING ? "DYNAMIC" : "LEGACY")
          .append(", validation=").append(Vulkan.ENABLE_VALIDATION_LAYERS).append('\n');
        sb.append("  TileGpuOpts: ").append(TileGpuOpts.isEnabled()).append('\n');
        sb.append("  MacPerfOpts: ").append(MacPerfOpts.describeActive()).append('\n');
        sb.append("  WorldScale: ").append(WorldScale.configuredScale()).append("%\n");
        sb.append("  FrameLimiter: ").append(FrameLimiter.isEnabled() ? "precise" : "vanilla")
          .append(", PowerSource: supported=").append(PowerSources.get().isSupported())
          .append(", onBattery=").append(PowerSources.get().isOnBattery()).append('\n');
        sb.append("  Caches: pipelineWarmup=").append(PipelineWarmup.isEnabled())
          .append(", shaderBinaryCache=").append(ShaderBinaryCache.isEnabled())
          .append(" (hits=").append(ShaderBinaryCache.getHits())
          .append(", misses=").append(ShaderBinaryCache.getMisses()).append(")\n");

        // Key voolkan settings (strictly non-PII allowlist)
        Config cfg = Initializer.CONFIG;
        if (cfg != null) {
            sb.append("\nConfig:\n");
            sb.append("  windowMode=").append(cfg.windowMode)
              .append(", videoMode=").append(cfg.videoMode != null ? cfg.videoMode.toString() : "none")
              .append(", worldScale=").append(cfg.worldScale)
              .append(", renderPath=").append(cfg.renderPath)
              .append(", pipelineCache=").append(cfg.pipelineCache).append('\n');
            sb.append("  frameQueueSize=").append(cfg.frameQueueSize)
              .append(", lowLatency=").append(cfg.lowLatency)
              .append(", swapChainImages=").append(cfg.swapChainImages)
              .append(", adaptiveVsync=").append(cfg.adaptiveVsync)
              .append(", backgroundFps=").append(cfg.backgroundFps)
              .append(", batteryFps=").append(cfg.batteryFps).append('\n');
            sb.append("  advCulling=").append(cfg.advCulling)
              .append(", entityCulling=").append(cfg.entityCulling)
              .append(", uniqueOpaqueLayer=").append(cfg.uniqueOpaqueLayer)
              .append(", backFaceCulling=").append(cfg.backFaceCulling)
              .append(", indirectDraw=").append(cfg.indirectDraw)
              .append(", chunkUploadBudget=").append(cfg.chunkUploadBudget).append('\n');
            sb.append("  ambientOcclusion=").append(cfg.ambientOcclusion)
              .append(", blockEntityDistance=").append(cfg.blockEntityDistance)
              .append(", maxParticles=").append(cfg.maxParticles)
              .append(", showFrameTimeF3=").append(cfg.showFrameTimeF3)
              .append(", builderThreads=").append(cfg.builderThreads)
              .append(", textureAnimations=").append(cfg.textureAnimations).append('\n');
            sb.append("  disallowFSE=").append(cfg.disallowFullscreenExclusive)
              .append(", macNativeFullscreen=").append(cfg.macNativeFullscreen)
              .append(", macReduceResolution=").append(cfg.macReduceResolution)
              .append(", useWayland=").append(cfg.useWayland)
              .append(", device=").append(cfg.device).append('\n');
        }

        // Allowlisted voolkan.* JVM overrides (each literal kept on same line as getProperty for check-lang.ps1)
        String overrides = collectJvmOverrides();
        sb.append("JVM overrides: ").append(overrides.isEmpty() ? "none" : overrides).append('\n');

        // Memory summary
        Runtime rt = Runtime.getRuntime();
        long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long heapMaxMb = rt.maxMemory() / (1024L * 1024L);
        sb.append("\nMemory:\n");
        sb.append("  JVM Heap: ").append(heapUsedMb).append(" / ").append(heapMaxMb).append(" MiB\n");
        try {
            MemoryManager mm = MemoryManager.getInstance();
            if (mm != null) {
                sb.append("  Native: ").append(mm.getNativeMemoryMB())
                  .append(" MiB, Device: ").append(mm.getAllocatedDeviceMemoryMB()).append(" MiB\n");
                for (String line : mm.getMemoryBudgetLines()) {
                    sb.append("  ").append(line).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }

        // Recent sanitized warnings & errors
        List<String> recent = DiagnosticLogBuffer.snapshot();
        sb.append("\nRecent Vulkan/voolkan warnings & errors (").append(recent.size()).append("):\n");
        if (recent.isEmpty()) {
            sb.append("  none\n");
        } else {
            for (String entry : recent) {
                sb.append("  ").append(entry).append('\n');
            }
        }

        // Final full-report path sanitization pass (freeform log/env fields are already username-scrubbed)
        return DiagnosticSanitizer.sanitizePathsOnly(sb.toString());
    }

    /** Copies the sanitized diagnostics report to the system clipboard. Returns true on success. */
    public static boolean copyToClipboard() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.keyboardHandler == null) {
                return false;
            }
            String report = buildReport();
            mc.keyboardHandler.setClipboard(report);
            return true;
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Failed to copy diagnostics to clipboard: {}", t.getMessage());
            return false;
        }
    }

    private static String collectJvmOverrides() {
        StringJoiner joiner = new StringJoiner(", ");
        addOverride(joiner, "renderPath", System.getProperty("voolkan.renderPath"));
        addOverride(joiner, "validation", System.getProperty("voolkan.validation"));
        addOverride(joiner, "pipelineCache", System.getProperty("voolkan.pipelineCache"));
        addOverride(joiner, "shaderCache", System.getProperty("voolkan.shaderCache"));
        addOverride(joiner, "pipelineWarmup", System.getProperty("voolkan.pipelineWarmup"));
        addOverride(joiner, "preciseLimiter", System.getProperty("voolkan.preciseLimiter"));
        addOverride(joiner, "disableTileOpts", System.getProperty("voolkan.disableTileOpts"));
        addOverride(joiner, "disableUnifiedDirectWrite", System.getProperty("voolkan.disableUnifiedDirectWrite"));
        addOverride(joiner, "disallowFullscreenExclusive", System.getProperty("voolkan.disallowFullscreenExclusive"));
        addOverride(joiner, "shaderDebug", System.getProperty("voolkan.shaderDebug"));
        addOverride(joiner, "disableShaderOptimizations", System.getProperty("voolkan.disableShaderOptimizations"));
        addOverride(joiner, "disableUnifiedGpuBuffers", System.getProperty("voolkan.disableUnifiedGpuBuffers"));
        addOverride(joiner, "disableInlineImageUploads", System.getProperty("voolkan.disableInlineImageUploads"));
        addOverride(joiner, "disablePerFrameDepth", System.getProperty("voolkan.disablePerFrameDepth"));
        addOverride(joiner, "disableLateAcquire", System.getProperty("voolkan.disableLateAcquire"));
        addOverride(joiner, "disableDescriptorCachePersist", System.getProperty("voolkan.disableDescriptorCachePersist"));
        addOverride(joiner, "disableDrawCoalescing", System.getProperty("voolkan.disableDrawCoalescing"));
        addOverride(joiner, "disableTbdrPassOpts", System.getProperty("voolkan.disableTbdrPassOpts"));
        addOverride(joiner, "disableMenuBlurOpts", System.getProperty("voolkan.disableMenuBlurOpts"));
        addOverride(joiner, "disableShaderOptsMac", System.getProperty("voolkan.disableShaderOptsMac"));
        return joiner.toString();
    }

    private static void addOverride(StringJoiner joiner, String shortName, String value) {
        if (value != null && !value.isEmpty()) {
            joiner.add(shortName + "=" + DiagnosticSanitizer.sanitizeEnvValue(value));
        }
    }
}
