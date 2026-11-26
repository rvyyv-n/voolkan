/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.gui;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.render.chunk.build.light.LightMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings presets for the Video page. A preset only stages new values in the options
 * (the user still presses Apply, or Undo to drop them).
 * <p>
 * Values: see docs/presets.md. Performance is the absolute minimum of each option, with the
 * highest refresh rate; Competitive tunes for minimum latency and PvP readability; Battery
 * caps framerate and power draw; a null entry leaves that option unchanged.
 */
public enum Preset {
    CUSTOM("voolkan.options.preset.custom"),
    PERFORMANCE("voolkan.options.preset.performance"),
    COMPETITIVE("voolkan.options.preset.competitive"),
    BALANCED("voolkan.options.preset.balanced"),
    QUALITY("voolkan.options.preset.quality"),
    BATTERY("voolkan.options.preset.battery");

    /** Marker value: the highest value the option offers. */
    private static final Object MAX = new Object();

    /** Option name key -> {performance, competitive, balanced, quality, battery}. */
    private static final Map<String, Object[]> VALUES = new LinkedHashMap<>();

    static {
        // Video — Display & Latency
        put("vulkanmod.options.refreshRate", MAX, MAX, null, null, null);
        put("options.vsync", false, false, true, true, true);
        put("voolkan.options.adaptiveVsync", false, false, null, null, false);
        put("options.framerateLimit", 260, 260, 120, 144, 60);
        put("voolkan.options.backgroundFps", null, null, null, null, 15);
        put("voolkan.options.batteryFps", null, null, null, null, 60);
        put("vulkanmod.options.lowLatency", true, true, false, false, false);
        put("vulkanmod.options.frameQueue", 1, 1, 2, 2, 2);
        put("vulkanmod.options.swapChainImages", null, 2, 0, 0, 0);

        // Graphics
        put("options.renderDistance", 2, 8, 12, 16, 8);
        put("options.simulationDistance", 5, 6, 8, 10, 6);
        put("options.textureFiltering", TextureFilteringMethod.NONE, TextureFilteringMethod.NONE, TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC, TextureFilteringMethod.NONE);
        put("options.maxAnisotropy", 1, 1, 1, 2, 1);
        put("options.particles", ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.DECREASED, ParticleStatus.ALL, ParticleStatus.DECREASED);
        put("voolkan.options.maxParticles", 500, 250, 0, 0, 500);
        put("options.renderClouds", CloudStatus.OFF, CloudStatus.OFF, CloudStatus.FAST, CloudStatus.FANCY, CloudStatus.OFF);
        put("options.renderCloudsDistance", 2, 2, 32, 64, null);
        put("options.cutoutLeaves", false, false, true, true, true);
        put("options.chunkFade", 0, 0, 10, 20, 0);
        put("options.ao", LightMode.FLAT, LightMode.FLAT, LightMode.SMOOTH, LightMode.SUB_BLOCK, LightMode.SMOOTH);
        put("options.biomeBlendRadius", 0, 0, 2, 4, 2);
        put("options.entityShadows", false, false, true, true, false);
        put("options.entityDistanceScaling", 2, 4, 4, 6, 4);
        put("voolkan.options.blockEntityDistance", 16, 48, 64, 64, 32);
        put("options.mipmapLevels", 0, 2, 2, 4, 2);
        put("options.weatherRadius", 3, 3, 5, 8, 3);
        put("options.vignette", false, false, true, true, true);

        // Optimizations
        put("vulkanmod.options.advCulling", 1, 1, 2, 3, 2);
        put("voolkan.options.chunkUploadBudget", 0, 0, 0, 0, 0);

        // Other
        put("vulkanmod.options.textureAnimations", false, true, true, true, true);
    }

    private final String key;

    Preset(String key) {
        this.key = key;
    }

    public Component displayName() {
        return Component.translatable(this.key);
    }

    private static void put(String nameKey, Object performance, Object competitive, Object balanced, Object quality, Object battery) {
        VALUES.put(nameKey, new Object[]{performance, competitive, balanced, quality, battery});
    }

    /**
     * Stage this preset's values in the matching active options of {@code pages}.
     * Snapshots initially active options per page before staging so dependent child options
     * deactivated mid-pass (e.g. Adaptive VSync when VSync turns off, or Max Anisotropy when
     * Texture Filtering changes to NONE) still receive the preset value cleanly.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int stage(List<OptionPage> pages) {
        if (this == CUSTOM || pages == null) {
            return 0;
        }

        int index = this.ordinal() - 1;
        int count = 0;
        for (OptionPage page : pages) {
            Set<Option<?>> initiallyActive = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var block : page.optionBlocks) {
                for (Option<?> option : block.options()) {
                    if (option.isActive()) {
                        initiallyActive.add(option);
                    }
                }
            }

            Set<Option<?>> staged = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int pass = 0; pass < 2; pass++) {
                int passChanges = 0;
                for (var block : page.optionBlocks) {
                    for (Option option : block.options()) {
                        if (staged.contains(option)) {
                            continue;
                        }
                        boolean eligible = option.isActive() || (pass == 0 && initiallyActive.contains(option));
                        if (!eligible) {
                            continue;
                        }
                        Object[] values = VALUES.get(option.getNameKey());
                        if (values == null) {
                            continue;
                        }

                        Object value = values[index];
                        if (value == null) {
                            continue;
                        }

                        if (option instanceof CyclingOption cycling) {
                            Object[] offered = cycling.getValues();
                            if (offered == null || offered.length == 0) {
                                continue;
                            }
                            if (value == MAX) {
                                value = offered[offered.length - 1];
                            } else if (!Arrays.asList(offered).contains(value)) {
                                continue;
                            }
                        }

                        option.setNewValue(value);
                        staged.add(option);
                        count++;
                        passChanges++;
                    }
                }
                page.updateOptionStates();
                if (passChanges == 0) {
                    break;
                }
            }
        }

        return count;
    }
}
