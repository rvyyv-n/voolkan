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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings presets for the Video page. A preset only stages new values in the options
 * (the user still presses Apply, or Undo to drop them).
 * <p>
 * Values: see docs/presets.md. Performance is the absolute minimum of each option, with the
 * highest refresh rate; a null entry leaves that option unchanged.
 */
public enum Preset {
    CUSTOM("voolkan.options.preset.custom"),
    PERFORMANCE("voolkan.options.preset.performance"),
    BALANCED("voolkan.options.preset.balanced"),
    QUALITY("voolkan.options.preset.quality");

    /** Marker value: the highest value the option offers. */
    private static final Object MAX = new Object();

    /** Option name key -> {performance, balanced, quality}. */
    private static final Map<String, Object[]> VALUES = new LinkedHashMap<>();

    static {
        // Video
        put("options.framerateLimit", 260, 120, 144);
        put("options.vsync", false, true, true);
        put("vulkanmod.options.refreshRate", MAX, null, null);
        // Graphics
        put("options.renderDistance", 2, 12, 16);
        put("options.simulationDistance", 5, 8, 10);
        put("options.textureFiltering", TextureFilteringMethod.NONE, TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC);
        put("options.maxAnisotropy", 1, 1, 2);
        put("options.particles", ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.ALL);
        put("options.renderClouds", CloudStatus.OFF, CloudStatus.FAST, CloudStatus.FANCY);
        put("options.renderCloudsDistance", 2, 32, 64);
        put("options.cutoutLeaves", false, true, true);
        put("options.chunkFade", 0, 10, 20);
        put("options.ao", LightMode.FLAT, LightMode.SMOOTH, LightMode.SUB_BLOCK);
        put("options.biomeBlendRadius", 0, 2, 4);
        put("options.entityShadows", false, true, true);
        put("options.entityDistanceScaling", 2, 4, 6);
        put("options.mipmapLevels", 0, 2, 4);
        put("options.weatherRadius", 3, 5, 8);
        put("options.vignette", false, true, true);
        // Optimizations
        put("vulkanmod.options.advCulling", 1, 2, 3);
        // Other
        put("vulkanmod.options.frameQueue", 1, 2, 2);
        put("vulkanmod.options.lowLatency", true, false, false);
        put("vulkanmod.options.textureAnimations", false, true, true);
    }

    private final String key;

    Preset(String key) {
        this.key = key;
    }

    public Component displayName() {
        return Component.translatable(this.key);
    }

    private static void put(String nameKey, Object performance, Object balanced, Object quality) {
        VALUES.put(nameKey, new Object[]{performance, balanced, quality});
    }

    /** Stage this preset's values in the matching options of {@code pages}. Returns the number of options set. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int stage(List<OptionPage> pages) {
        if (this == CUSTOM) {
            return 0;
        }

        int index = this.ordinal() - 1;
        int count = 0;
        for (OptionPage page : pages) {
            for (var block : page.optionBlocks) {
                for (Option option : block.options()) {
                    Object[] values = VALUES.get(option.getNameKey());
                    if (values == null) {
                        continue;
                    }

                    Object value = values[index];
                    if (value == null) {
                        continue;
                    }

                    if (option instanceof CyclingOption cycling) {
                        if (value == MAX) {
                            Object[] offered = cycling.getValues();
                            value = offered[offered.length - 1];
                        } else if (!Arrays.asList(cycling.getValues()).contains(value)) {
                            continue;
                        }
                    }

                    option.setNewValue(value);
                    count++;
                }
            }
        }

        return count;
    }
}
