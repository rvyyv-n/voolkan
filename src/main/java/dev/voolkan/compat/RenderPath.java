/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import java.util.Locale;

/**
 * Which Vulkan rendering API the renderer uses: dynamic rendering (VK_KHR_dynamic_rendering)
 * or the legacy VkRenderPass / VkFramebuffer path.
 */
public enum RenderPath {
    DYNAMIC,
    LEGACY;

    /** Requested mode from the config file or the {@code voolkan.renderPath} system property. */
    public enum Request {
        AUTO, DYNAMIC, LEGACY;

        public static Request parse(String s) {
            if (s == null) {
                return AUTO;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "dynamic" -> DYNAMIC;
                case "legacy" -> LEGACY;
                default -> AUTO;
            };
        }
    }

    public record Choice(RenderPath path, String reason) {}

    /**
     * Precedence: config value (if not auto), then -Dvoolkan.renderPath (if not auto), then device support.
     * Forcing dynamic on a device that lacks it falls back to legacy.
     */
    public static Choice resolve(String configValue, String propertyValue, boolean deviceSupportsDynamic) {
        Request request = Request.parse(configValue);
        String source = "config renderPath";

        if (request == Request.AUTO) {
            request = Request.parse(propertyValue);
            source = "-Dvoolkan.renderPath";
        }

        if (request == Request.LEGACY) {
            return new Choice(LEGACY, "forced by " + source);
        }

        if (request == Request.DYNAMIC) {
            if (deviceSupportsDynamic) {
                return new Choice(DYNAMIC, "forced by " + source);
            }
            return new Choice(LEGACY, source + " asks for dynamic rendering but the device does not support it");
        }

        return deviceSupportsDynamic
                ? new Choice(DYNAMIC, "auto: device supports VK_KHR_dynamic_rendering")
                : new Choice(LEGACY, "auto: device lacks VK_KHR_dynamic_rendering");
    }
}
