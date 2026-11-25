package net.vulkanmod.config;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * add the renderPath, lowLatency, swapChainImages, pipelineCache, backgroundFps, batteryFps, worldScale, blockEntityDistance, macNativeFullscreen, and macReduceResolution options, and apply macOS fresh-config defaults.
 */

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.vulkanmod.config.video.VideoModeSet;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class Config {
    public VideoModeSet.VideoMode videoMode = VideoModeSet.getDummy().getVideoMode();
    public int windowMode = 0;

    public int advCulling = 2;
    public boolean indirectDraw = true;

    /** "auto", "dynamic" or "legacy". See dev.voolkan.compat.RenderPath. */
    public String renderPath = "auto";

    /** Save the Vulkan pipeline cache to disk and reload it at startup. */
    public boolean pipelineCache = true;

    public boolean uniqueOpaqueLayer = true;
    public boolean entityCulling = true;
    public int device = -1;
    public boolean useWayland = false;

    public int ambientOcclusion = 1;
    public int frameQueueSize = 2;
    /** Wait for the previous frame's fence before input is polled. */
    public boolean lowLatency = false;
    /** Frame-rate cap when the window is unfocused; 0 = off. */
    public int backgroundFps = 30;
    /** Frame-rate cap while running on battery power; 0 = off. */
    public int batteryFps = 0;
    /** World render resolution as a percentage (50..100, step 5); 100 = native (off). */
    public int worldScale = 100;
    /** Maximum render distance for block entities (chests, signs, banners) in blocks (16..128); 64 = vanilla default. */
    public int blockEntityDistance = 64;
    /** Opt out of Windows fullscreen exclusive / independent flip via VK_EXT_full_screen_exclusive (for capture compatibility). */
    public boolean disallowFullscreenExclusive = false;
    /** Use native macOS fullscreen Spaces instead of borderless fullscreen (macOS only). */
    public boolean macNativeFullscreen = false;
    /** Render at 1x logical resolution instead of full Retina HiDPI on macOS (macOS only). */
    public boolean macReduceResolution = false;
    /** Requested swapchain image count; 0 = driver minimum + 1. Clamped to the surface limits. */
    public int swapChainImages = 0;
    public int builderThreads = 0;

    public boolean backFaceCulling = true;
    public boolean textureAnimations = true;

    public void write() {
        if (!Files.exists(CONFIG_PATH.getParent())) {
            try {
                Files.createDirectories(CONFIG_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            Files.write(CONFIG_PATH, Collections.singleton(GSON.toJson(this)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path CONFIG_PATH;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static Config load(Path path) {
        Config config;
        Config.CONFIG_PATH = path;

        if (Files.exists(path)) {
            try (FileReader fileReader = new FileReader(path.toFile())) {
                config = GSON.fromJson(fileReader, Config.class);
            } catch (IOException exception) {
                throw new RuntimeException(exception.getMessage());
            }
        }
        else {
            config = new Config();
            config.applyPlatformDefaults();
            config.write();
        }

        return config;
    }

    private void applyPlatformDefaults() {
        if (Platform.isMacOS()) {
            // Overlap CPU/GPU across frameQueueSize=2 (safe with per-frame depth attachments)
            // and split SOLID into the early-Z no-discard shader for Apple TBDR HSR.
            this.lowLatency = false;
            this.uniqueOpaqueLayer = false;
            this.frameQueueSize = 2;
            this.swapChainImages = 0;
            this.macNativeFullscreen = false;
            this.macReduceResolution = false;
            this.indirectDraw = false;
        }
    }
}
