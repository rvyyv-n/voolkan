/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.gui;

import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.server.level.ParticleStatus;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.render.chunk.build.light.LightMode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-page default values for the built-in voolkan settings pages (Video, Graphics,
 * Optimizations, Other). Restoring defaults only stages values in the options; the user
 * still presses Apply to commit or Undo to revert.
 * <p>
 * Hardware-specific display options ({@code options.fullscreen.resolution},
 * {@code vulkanmod.options.refreshRate}) and read-only compatibility info rows are
 * intentionally omitted.
 */
public final class PageDefaults {

    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        // Video
        DEFAULTS.put("voolkan.options.preset", Preset.CUSTOM);
        DEFAULTS.put("vulkanmod.options.windowMode", WindowMode.WINDOWED);
        DEFAULTS.put("voolkan.options.macNativeFullscreen", false);
        DEFAULTS.put("voolkan.options.worldScale", 100);
        DEFAULTS.put("options.framerateLimit", 120);
        DEFAULTS.put("options.vsync", true);
        DEFAULTS.put("options.inactivityFpsLimit", InactivityFpsLimit.AFK);
        DEFAULTS.put("voolkan.options.backgroundFps", 30);
        DEFAULTS.put("voolkan.options.batteryFps", 0);
        DEFAULTS.put("options.guiScale", 0);
        DEFAULTS.put("options.gamma", 50);
        DEFAULTS.put("options.attackIndicator", AttackIndicatorStatus.CROSSHAIR);
        DEFAULTS.put("options.autosaveIndicator", true);

        // Graphics
        DEFAULTS.put("options.renderDistance", 12);
        DEFAULTS.put("options.simulationDistance", 12);
        DEFAULTS.put("options.prioritizeChunkUpdates", PrioritizeChunkUpdates.NONE);
        DEFAULTS.put("options.graphics.preset", GraphicsPreset.FANCY);
        DEFAULTS.put("options.textureFiltering", TextureFilteringMethod.NONE);
        DEFAULTS.put("options.maxAnisotropy", 2);
        DEFAULTS.put("options.particles", ParticleStatus.ALL);
        DEFAULTS.put("options.renderClouds", CloudStatus.FANCY);
        DEFAULTS.put("options.renderCloudsDistance", 128);
        DEFAULTS.put("options.cutoutLeaves", true);
        DEFAULTS.put("options.chunkFade", 15);
        DEFAULTS.put("options.ao", LightMode.SMOOTH);
        DEFAULTS.put("options.biomeBlendRadius", 2);
        DEFAULTS.put("options.entityShadows", true);
        DEFAULTS.put("options.entityDistanceScaling", 4);
        DEFAULTS.put("voolkan.options.blockEntityDistance", 64);
        DEFAULTS.put("options.mipmapLevels", 4);
        DEFAULTS.put("options.weatherRadius", 10);
        DEFAULTS.put("options.vignette", true);

        // Optimizations
        DEFAULTS.put("vulkanmod.options.advCulling", 2);
        DEFAULTS.put("vulkanmod.options.entityCulling", true);
        DEFAULTS.put("vulkanmod.options.uniqueOpaqueLayer", !Platform.isMacOS());
        DEFAULTS.put("vulkanmod.options.backfaceCulling", true);
        DEFAULTS.put("vulkanmod.options.indirectDraw", !Platform.isMacOS());

        // Other
        DEFAULTS.put("vulkanmod.options.builderThreads", 0);
        DEFAULTS.put("vulkanmod.options.frameQueue", 2);
        DEFAULTS.put("vulkanmod.options.lowLatency", false);
        DEFAULTS.put("vulkanmod.options.swapChainImages", 0);
        DEFAULTS.put("vulkanmod.options.textureAnimations", true);
        DEFAULTS.put("vulkanmod.options.wayland", false);
        DEFAULTS.put("vulkanmod.options.deviceSelector", -1);
    }

    private PageDefaults() {
    }

    /** Returns true if {@code page} contains at least one registered defaultable option. */
    public static boolean hasDefaults(OptionPage page) {
        if (page == null) {
            return false;
        }

        for (var block : page.optionBlocks) {
            for (Option<?> option : block.options()) {
                String key = option.getNameKey();
                if (key != null && DEFAULTS.containsKey(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns true when every active registered option on {@code page} already holds its default value. */
    public static boolean isAtDefaults(OptionPage page) {
        if (page == null) {
            return true;
        }

        for (var block : page.optionBlocks) {
            for (Option<?> option : block.options()) {
                if (!option.isActive()) {
                    continue;
                }

                String key = option.getNameKey();
                if (key == null) {
                    continue;
                }

                Object defaultVal = DEFAULTS.get(key);
                if (defaultVal == null) {
                    continue;
                }

                if (option instanceof CyclingOption<?> cycling && !offersValue(cycling, defaultVal)) {
                    continue;
                }

                if (!Objects.equals(option.getNewValue(), defaultVal)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Stages default values onto every active registered option on {@code page}.
     * Returns the number of options whose staged value changed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int restore(OptionPage page) {
        if (page == null) {
            return 0;
        }

        int count = 0;
        for (var block : page.optionBlocks) {
            for (Option option : block.options()) {
                if (!option.isActive()) {
                    continue;
                }

                String key = option.getNameKey();
                if (key == null) {
                    continue;
                }

                Object defaultVal = DEFAULTS.get(key);
                if (defaultVal == null) {
                    continue;
                }

                if (option instanceof CyclingOption cycling && !offersValue(cycling, defaultVal)) {
                    continue;
                }

                if (!Objects.equals(option.getNewValue(), defaultVal)) {
                    option.setNewValue(defaultVal);
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean offersValue(CyclingOption<?> option, Object value) {
        Object[] offered = option.getValues();
        return offered != null && Arrays.asList(offered).contains(value);
    }
}
