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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.SwitchOption;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.render.chunk.build.light.LightMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Platform-aware default values for voolkan settings pages (Video, Graphics,
 * Optimizations, Other) and per-option Reset to Default support. Restoring defaults
 * only stages values in the options; the user still presses Apply to commit or Undo to revert.
 * <p>
 * Hardware-specific display options ({@code options.fullscreen.resolution},
 * {@code vulkanmod.options.refreshRate}) and read-only compatibility info rows are
 * intentionally omitted.
 */
public final class PageDefaults {
    private static final String PRESET_KEY = "voolkan.options.preset";

    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        // Video — Display (Preset row is handled separately as a staging trigger)
        DEFAULTS.put("vulkanmod.options.windowMode", WindowMode.WINDOWED);
        DEFAULTS.put("voolkan.options.macNativeFullscreen", false);
        DEFAULTS.put("voolkan.options.macReduceResolution", false);
        DEFAULTS.put("voolkan.options.worldScale", 100);

        // Video — Latency
        DEFAULTS.put("options.vsync", true);
        DEFAULTS.put("voolkan.options.adaptiveVsync", false);
        DEFAULTS.put("options.framerateLimit", 120);
        DEFAULTS.put("options.inactivityFpsLimit", InactivityFpsLimit.AFK);
        DEFAULTS.put("voolkan.options.backgroundFps", 30);
        DEFAULTS.put("voolkan.options.batteryFps", 0);
        DEFAULTS.put("vulkanmod.options.lowLatency", false);
        DEFAULTS.put("vulkanmod.options.frameQueue", 2);
        DEFAULTS.put("vulkanmod.options.swapChainImages", 0);

        // Video — Interface
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
        DEFAULTS.put("voolkan.options.maxParticles", 0);
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
        DEFAULTS.put("voolkan.options.chunkUploadBudget", 0);

        // Other
        DEFAULTS.put("vulkanmod.options.builderThreads", 0);
        DEFAULTS.put("vulkanmod.options.textureAnimations", true);
        DEFAULTS.put("voolkan.options.showFrameTimeF3", true);
        DEFAULTS.put("vulkanmod.options.wayland", false);
        DEFAULTS.put("vulkanmod.options.deviceSelector", -1);
    }

    private PageDefaults() {
    }

    /** Returns the platform-aware default value for {@code option}, or {@code null} if unregistered/unoffered. */
    public static Object getDefault(Option<?> option) {
        if (option == null) {
            return null;
        }
        String key = option.getNameKey();
        if (key == null) {
            return null;
        }
        Object defaultVal = DEFAULTS.get(key);
        if (defaultVal == null) {
            return null;
        }
        if (option instanceof CyclingOption<?> cycling && !offersValue(cycling, defaultVal)) {
            return null;
        }
        return defaultVal;
    }

    /** Returns true if this single option supports per-option Reset to Default. */
    public static boolean isResettable(Option<?> option) {
        if (option == null) {
            return false;
        }
        String key = option.getNameKey();
        if (key == null || PRESET_KEY.equals(key)) {
            return false;
        }
        return getDefault(option) != null;
    }

    /** Returns true when {@code option} is active, resettable, and currently differs from its default. */
    public static boolean canReset(Option<?> option) {
        if (!isResettable(option) || !option.isActive()) {
            return false;
        }
        Object defaultVal = getDefault(option);
        return defaultVal != null && !Objects.equals(option.getNewValue(), defaultVal);
    }

    /** Stages the platform-aware default onto a single active option. Returns true if its staged value changed. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean resetOption(Option option) {
        if (!canReset(option)) {
            return false;
        }
        Object defaultVal = getDefault(option);
        if (defaultVal == null) {
            return false;
        }
        option.setNewValue(defaultVal);
        return true;
    }

    /** Formats the option's default value using its own display translator when available. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Component formatDefaultValue(Option option) {
        Object defaultVal = getDefault(option);
        if (defaultVal == null) {
            return null;
        }
        try {
            if (option instanceof SwitchOption && defaultVal instanceof Boolean b) {
                return Component.translatable(b ? "options.on" : "options.off");
            }
            Function translator = option.getTranslator();
            if (translator != null) {
                Object formatted = translator.apply(defaultVal);
                if (formatted instanceof Component comp) {
                    return comp;
                }
            }
            if (defaultVal instanceof Boolean b) {
                return Component.translatable(b ? "options.on" : "options.off");
            }
            return Component.literal(String.valueOf(defaultVal));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Returns true if {@code page} contains at least one registered defaultable option. */
    public static boolean hasDefaults(OptionPage page) {
        if (page == null) {
            return false;
        }

        for (var block : page.optionBlocks) {
            for (Option<?> option : block.options()) {
                if (isResettable(option)) {
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
                if (!isResettable(option) || !option.isActive()) {
                    continue;
                }

                Object defaultVal = getDefault(option);
                if (defaultVal == null) {
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
     * Snapshots initially active options before Pass 0 so dependent options deactivated mid-pass
     * (e.g. Max Anisotropy when Texture Filtering resets to NONE) still restore their defaults,
     * and runs a second pass for newly activated dependent options (e.g. Adaptive VSync when VSync
     * resets to ON).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int restore(OptionPage page) {
        if (page == null) {
            return 0;
        }

        Set<Option<?>> initiallyActive = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var block : page.optionBlocks) {
            for (Option<?> option : block.options()) {
                if (option.isActive()) {
                    initiallyActive.add(option);
                }
            }
        }

        int count = 0;
        for (int pass = 0; pass < 2; pass++) {
            int passChanges = 0;
            for (var block : page.optionBlocks) {
                for (Option option : block.options()) {
                    if (PRESET_KEY.equals(option.getNameKey())) {
                        option.syncFromSupplier();
                        continue;
                    }
                    boolean eligible = option.isActive() || (pass == 0 && initiallyActive.contains(option));
                    if (!eligible || !isResettable(option)) {
                        continue;
                    }

                    Object defaultVal = getDefault(option);
                    if (defaultVal == null) {
                        continue;
                    }

                    if (!Objects.equals(option.getNewValue(), defaultVal)) {
                        option.setNewValue(defaultVal);
                        passChanges++;
                    }
                }
            }
            count += passChanges;
            page.updateOptionStates();
            if (passChanges == 0) {
                break;
            }
        }
        return count;
    }

    private static boolean offersValue(CyclingOption<?> option, Object value) {
        Object[] offered = option.getValues();
        return offered != null && Arrays.asList(offered).contains(value);
    }
}
