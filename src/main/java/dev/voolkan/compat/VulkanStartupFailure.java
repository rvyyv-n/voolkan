/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import net.minecraft.client.main.SilentInitException;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Platform;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates fatal Vulkan startup failures into plain-English guidance and displays a native
 * OS error dialog via LWJGL {@link TinyFileDialogs} without ever touching Java AWT/Swing
 * (critical on macOS under {@code -XstartOnFirstThread}).
 */
public final class VulkanStartupFailure {
    private static final int MAX_DIALOG_CHARS = 1800;

    public record Guidance(String title, String whatFailed, List<String> whatToTry, String technicalSummary) {}

    private VulkanStartupFailure() {
    }

    /**
     * Handles a fatal Throwable raised during Vulkan/renderer initialization, logs full details,
     * shows a safe native dialog when interactive, optionally copies sanitized failure info to
     * the clipboard, and throws {@link SilentInitException} so Minecraft exits cleanly without
     * stacking a raw crash dialog on top.
     */
    public static RuntimeException handleFatal(long windowHandle, Throwable failure) {
        Guidance guidance = classify(failure);
        String formattedLog = formatForLog(guidance);
        DiagnosticLogBuffer.record("ERROR", "startup", guidance.whatFailed() + " | " + guidance.technicalSummary());
        Initializer.LOGGER.error("Vulkan startup failed:\n{}", formattedLog, failure);

        if (windowHandle != 0L) {
            try {
                GLFW.glfwHideWindow(windowHandle);
            } catch (Throwable ignored) {
            }
        }

        if (isDialogAllowed()) {
            try {
                String dialogTitle = sanitizeForTinyFd(guidance.title());
                boolean offerClipboard = windowHandle != 0L && !Platform.isLinux();
                String dialogBody = sanitizeForTinyFd(formatForDialog(guidance, offerClipboard));
                if (offerClipboard) {
                    boolean copy = TinyFileDialogs.tinyfd_messageBox(dialogTitle, dialogBody, "yesno", "error", false);
                    if (copy) {
                        copyFailureDiagnostics(windowHandle, guidance);
                    }
                } else {
                    TinyFileDialogs.tinyfd_messageBox(dialogTitle, dialogBody, "ok", "error", false);
                }
            } catch (Throwable dialogErr) {
                Initializer.LOGGER.warn("Could not display native startup failure dialog: {}", dialogErr.getMessage());
            }
        }

        return new SilentInitException(guidance.title() + ": " + guidance.whatFailed(), failure);
    }

    public static Guidance classify(Throwable failure) {
        String combined = collectCauseChain(failure);
        String lower = stripDeviceListing(combined).toLowerCase(Locale.ROOT);
        String technical = buildTechnicalSummary(failure);
        String rootLower = technical.toLowerCase(Locale.ROOT);

        boolean mac = Platform.isMacOS();
        boolean linux = Platform.isLinux();

        // 1. Missing Vulkan loader / MoltenVK / shaderc native library
        if (hasThrowableClass(failure, UnsatisfiedLinkError.class)
                || hasThrowableClass(failure, NoClassDefFoundError.class)
                || lower.contains("failed to locate library")
                || lower.contains("failed to load")
                || lower.contains("vulkan loader")
                || lower.contains("libmoltenvk")
                || lower.contains("vulkan-1.dll")) {
            List<String> steps = new ArrayList<>();
            if (lower.contains("shaderc")) {
                steps.add("Re-download the official voolkan mod jar so bundled shaderc natives are intact.");
                steps.add("Clear the voolkan/shader_cache folder in your Minecraft directory and relaunch.");
                return new Guidance(
                        "voolkan: Shader Compiler Failed to Load",
                        "The native shader compiler library (shaderc) could not be loaded.",
                        steps,
                        technical
                );
            }
            if (mac) {
                steps.add("Ensure your Mac is running macOS 11 or newer on a Metal-capable GPU.");
                steps.add("Re-download the official voolkan jar (which bundles MoltenVK for macOS).");
                steps.add("If macOS quarantined the library, remove quarantine or launch via a standard launcher.");
            } else if (linux) {
                steps.add("Install your distribution's Vulkan loader (vulkan-loader / libvulkan1).");
                steps.add("Install the Vulkan GPU driver for your card (mesa-vulkan-drivers, vulkan-radeon, vulkan-intel, or NVIDIA Vulkan).");
            } else {
                steps.add("Install or update your GPU driver (AMD Adrenalin, NVIDIA GeForce, or Intel Graphics).");
                steps.add("Verify your graphics card supports Vulkan 1.2 or newer.");
            }
            return new Guidance(
                    "voolkan: Vulkan Driver Not Found",
                    mac
                            ? "Could not load MoltenVK / the Vulkan library on macOS."
                            : "Could not load the Vulkan graphics driver on this system.",
                    steps,
                    technical
            );
        }

        // 2. Validation layer explicitly requested but not installed
        if (lower.contains("validation requested") || lower.contains("vk_error_layer_not_present")) {
            List<String> steps = List.of(
                    "Remove -Dvoolkan.validation=true from your JVM arguments.",
                    "Or install the LunarG Vulkan SDK if you intentionally enabled validation layers."
            );
            return new Guidance(
                    "voolkan: Vulkan Validation Layer Missing",
                    "Vulkan validation was requested via JVM flag, but VK_LAYER_KHRONOS_validation is not installed.",
                    steps,
                    technical
            );
        }

        // 3. Incompatible driver (Vulkan < 1.2 or broken ICD / MoltenVK)
        if (lower.contains("vk_error_incompatible_driver")) {
            List<String> steps = new ArrayList<>();
            if (mac) {
                steps.add("Update macOS and ensure the official voolkan jar with bundled MoltenVK is installed.");
                steps.add("Check that no external VK_ICD_FILENAMES or VK_DRIVER_FILES variable overrides MoltenVK.");
            } else {
                steps.add("Update your graphics driver to a version that supports Vulkan 1.2 or newer.");
                steps.add("If you have both integrated and dedicated GPUs, make sure the dedicated GPU driver is up to date.");
            }
            return new Guidance(
                    "voolkan: Incompatible Vulkan Driver",
                    "Your Vulkan driver reported VK_ERROR_INCOMPATIBLE_DRIVER (Vulkan 1.2 is required).",
                    steps,
                    technical
            );
        }

        // 4. Depth format, VMA allocator, or out-of-memory failure (check before generic device wrapper)
        if (lower.contains("supported format")
                || lower.contains("create allocator")
                || lower.contains("vk_error_out_of_device_memory")
                || lower.contains("vk_error_out_of_host_memory")
                || lower.contains("memorytypes")) {
            List<String> steps = List.of(
                    "Close other GPU-intensive applications to free graphics memory.",
                    "Update your graphics driver.",
                    "Reset config/vulkanmod_settings.json to default settings and relaunch."
            );
            return new Guidance(
                    "voolkan: GPU Memory or Format Init Failed",
                    "Vulkan could not initialize required GPU memory heaps or depth image formats.",
                    steps,
                    technical
            );
        }

        // 5. Missing required extension/feature or logical device creation failure (before generic device wrapper)
        if (lower.contains("vk_error_extension_not_present")
                || lower.contains("vk_error_feature_not_present")
                || lower.contains("failed to create logical device")
                || rootLower.contains("dynamic_rendering")
                || rootLower.contains("dynamic rendering")) {
            List<String> steps = new ArrayList<>();
            steps.add("Update your graphics driver to the latest release for your GPU.");
            steps.add("Try launching with -Dvoolkan.renderPath=legacy if dynamic rendering was forced.");
            steps.add("Check config/vulkanmod_settings.json and reset renderPath back to auto.");
            return new Guidance(
                    "voolkan: Unsupported Vulkan Feature",
                    "Your GPU or driver does not support a required Vulkan extension or device feature.",
                    steps,
                    technical
            );
        }

        // 6. No suitable Vulkan GPU found
        if (lower.contains("could not set up a vulkan device")
                || lower.contains("failed to find a suitable gpu")
                || lower.contains("no suitable")
                || lower.contains("failed to find gpus")
                || lower.contains("failed to pick")) {
            String detected = describeDetectedDevices();
            List<String> steps = new ArrayList<>();
            if (mac) {
                steps.add("Check that your Mac supports Metal and macOS 11 or newer.");
                steps.add("Reset device selection by removing vulkanmod_settings.json from your config folder.");
            } else if (linux) {
                steps.add("Install the Vulkan driver package for your GPU (Mesa RADV/ANV or NVIDIA Vulkan).");
                steps.add("Try switching between X11 and Wayland (useWayland in config/vulkanmod_settings.json).");
            } else {
                steps.add("Update your graphics driver (AMD, NVIDIA, or Intel) to one with Vulkan 1.2 support.");
                steps.add("Check that your monitor is plugged into your primary GPU and remove stale GPU selection in config/vulkanmod_settings.json.");
            }
            String what = detected.isEmpty()
                    ? "No Vulkan 1.2 graphics device capable of presenting to the window was found."
                    : "No suitable Vulkan 1.2 graphics device was found (detected: " + detected + ").";
            return new Guidance("voolkan: No Compatible Vulkan GPU", what, steps, technical);
        }

        // 7. Window surface or swapchain creation failure
        if (lower.contains("window surface")
                || lower.contains("swap chain")
                || lower.contains("swapchain")
                || lower.contains("vk_error_surface_lost")
                || lower.contains("vk_error_native_window_in_use")
                || lower.contains("required vulkan instance extensions")) {
            List<String> steps = new ArrayList<>();
            if (linux) {
                steps.add("Toggle useWayland in config/vulkanmod_settings.json (or launch under XWayland/X11).");
                steps.add("Verify your graphics driver and Vulkan WSI libraries are installed.");
            } else if (mac) {
                steps.add("Close third-party overlay utilities and verify macOS display scaling settings.");
                steps.add("Reset config/vulkanmod_settings.json if a saved window mode is invalid.");
            } else {
                steps.add("Temporarily disable screen-hook overlays (RivaTuner, OBS Vulkan hook, Discord overlay).");
                steps.add("Try launching with -Dvoolkan.disallowFullscreenExclusive=true or reset config/vulkanmod_settings.json.");
                steps.add("Update your GPU driver.");
            }
            return new Guidance(
                    "voolkan: Display Surface Failed",
                    "Vulkan could not attach a rendering surface or swapchain to the Minecraft window.",
                    steps,
                    technical
            );
        }

        // 8. Shader compiler or pipeline cache failure
        if (lower.contains("shaderc") || lower.contains("pipeline cache") || lower.contains("spirv")) {
            List<String> steps = List.of(
                    "Delete the voolkan/shader_cache folder and voolkan/pipeline_cache.bin in your Minecraft directory.",
                    "Try launching with -Dvoolkan.pipelineCache=false -Dvoolkan.shaderCache=false."
            );
            return new Guidance(
                    "voolkan: Shader Cache Init Failed",
                    "The Vulkan shader compiler or pipeline cache failed during startup.",
                    steps,
                    technical
            );
        }

        // 9. Generic fallback
        List<String> steps = new ArrayList<>();
        steps.add("Update your graphics driver to a recent Vulkan 1.2 compatible version.");
        steps.add("Try launching with -Dvoolkan.renderPath=legacy or delete config/vulkanmod_settings.json.");
        steps.add("Check logs/latest.log for full error details.");
        return new Guidance(
                "voolkan: Vulkan Failed to Start",
                "voolkan encountered a fatal error while initializing Vulkan.",
                steps,
                technical
        );
    }

    private static boolean isDialogAllowed() {
        if ("true".equalsIgnoreCase(System.getProperty("voolkan.noStartupDialog"))) {
            return false;
        }
        // Read system property directly without loading java.awt.GraphicsEnvironment (unsafe on macOS)
        return !"true".equalsIgnoreCase(System.getProperty("java.awt.headless"));
    }

    private static void copyFailureDiagnostics(long windowHandle, Guidance guidance) {
        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("voolkan startup failure diagnostics\n");
            sb.append("voolkan: ").append(Initializer.getVersion() != null ? Initializer.getVersion() : "unknown").append('\n');
            sb.append("OS: ").append(System.getProperty("os.name", "unknown"))
              .append(' ').append(System.getProperty("os.version", ""))
              .append(" (").append(System.getProperty("os.arch", "unknown")).append(")\n");
            sb.append("Java: ").append(System.getProperty("java.version", "unknown")).append('\n');
            sb.append("Issue: ").append(guidance.title()).append('\n');
            sb.append("Summary: ").append(guidance.whatFailed()).append('\n');
            sb.append("Details: ").append(guidance.technicalSummary()).append('\n');
            String detected = describeDetectedDevices();
            if (!detected.isEmpty()) {
                sb.append("Detected GPUs: ").append(detected).append('\n');
            }
            String sanitized = DiagnosticSanitizer.sanitize(sb.toString());
            GLFW.glfwSetClipboardString(windowHandle, sanitized);
        } catch (Throwable ignored) {
        }
    }

    private static String formatForLog(Guidance guidance) {
        StringBuilder sb = new StringBuilder(384);
        sb.append(guidance.title()).append('\n');
        sb.append("What failed: ").append(guidance.whatFailed()).append('\n');
        sb.append("What to try:\n");
        for (int i = 0; i < guidance.whatToTry().size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(guidance.whatToTry().get(i)).append('\n');
        }
        sb.append("Technical details: ").append(guidance.technicalSummary());
        return sb.toString();
    }

    private static String formatForDialog(Guidance guidance, boolean offerClipboard) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("What failed:\n").append(guidance.whatFailed()).append("\n\n");
        sb.append("What to try:\n");
        for (int i = 0; i < guidance.whatToTry().size(); i++) {
            sb.append(i + 1).append(". ").append(guidance.whatToTry().get(i)).append('\n');
        }
        sb.append("\nDetails: ").append(guidance.technicalSummary());
        if (offerClipboard) {
            sb.append("\n\nSelect Yes to copy sanitized diagnostics to clipboard, or No to close.");
        }
        return sb.toString();
    }

    /**
     * Strips characters that tinyfiledialogs shell helpers (osascript on macOS, zenity/kdialog on Linux)
     * reject, misinterpret as Pango markup, or truncate (NUL/control chars).
     */
    static String sanitizeForTinyFd(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String sanitized = DiagnosticSanitizer.sanitize(text);
        StringBuilder out = new StringBuilder(Math.min(sanitized.length(), MAX_DIALOG_CHARS));
        for (int i = 0; i < sanitized.length(); i++) {
            char c = sanitized.charAt(i);
            if (c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c == '\n') {
                out.append('\n');
            } else if (c < 0x20 || c == 0x7F) {
                continue;
            } else if (c == '"' || c == '\'' || c == '`' || c == '$') {
                out.append(' ');
            } else if (c == '\\') {
                out.append('/');
            } else if (c == '<') {
                out.append('[');
            } else if (c == '>') {
                out.append(']');
            } else {
                out.append(c);
            }
            if (out.length() >= MAX_DIALOG_CHARS) {
                out.setLength(MAX_DIALOG_CHARS - 3);
                out.append("...");
                break;
            }
        }
        return out.toString();
    }

    private static String stripDeviceListing(String text) {
        int idx = text.indexOf("Devices found:");
        return idx >= 0 ? text.substring(0, idx) : text;
    }

    private static String describeDetectedDevices() {
        List<Device> devices = DeviceManager.availableDevices;
        if (devices == null || devices.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            if (d == null || d.deviceName == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(d.deviceName);
            if (d.vkVersion != null && !d.vkVersion.isEmpty()) {
                sb.append(" (Vulkan ").append(d.vkVersion).append(')');
            }
        }
        return DiagnosticSanitizer.sanitize(sb.toString());
    }

    private static boolean hasThrowableClass(Throwable t, Class<? extends Throwable> target) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 16) {
            if (target.isInstance(cur)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String collectCauseChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 16) {
            sb.append(cur.getClass().getName()).append(": ");
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage());
            }
            sb.append('\n');
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private static String buildTechnicalSummary(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable root = t;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 16) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.getMessage();
        }
        String summary = root.getClass().getSimpleName() + (msg != null && !msg.isBlank() ? ": " + msg : "");
        summary = DiagnosticSanitizer.sanitize(summary).replace('\r', ' ').replace('\n', ' ').trim();
        if (summary.length() > 280) {
            summary = summary.substring(0, 277) + "...";
        }
        return summary;
    }
}
