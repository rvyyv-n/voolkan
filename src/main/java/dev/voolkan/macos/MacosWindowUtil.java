/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.macos;

import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Window;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Platform;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.ObjCRuntime;

/**
 * macOS AppKit / Cocoa helpers for querying and toggling native macOS fullscreen Spaces.
 * <p>
 * All methods are strictly gated on {@link Platform#isMacOS()} and guarded against runtime errors
 * so this class compiles and stays completely inert on Windows and Linux.
 */
public final class MacosWindowUtil {

    /** NSWindowStyleMaskFullScreen (1 << 14). */
    private static final long NS_WINDOW_STYLE_MASK_FULL_SCREEN = 1L << 14;
    private static final long ENTER_TRANSITION_GUARD_NS = 2_000_000_000L;

    private static boolean objcInitialized = false;
    private static long objcMsgSend = 0L;
    private static long selStyleMask = 0L;
    private static long selToggleFullScreen = 0L;
    private static long enterPendingUntilNs = 0L;

    private MacosWindowUtil() {
    }

    /**
     * Returns whether the given GLFW window is currently in native macOS fullscreen mode.
     * Always returns {@code false} off macOS or if the Cocoa query fails.
     */
    public static boolean isInNativeFullscreen(long handle) {
        if (!Platform.isMacOS() || handle == 0L) {
            return false;
        }
        try {
            if (!ensureObjC()) {
                return false;
            }
            long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(handle);
            if (nsWindow == 0L) {
                return false;
            }
            long styleMask = JNI.invokePPJ(nsWindow, selStyleMask, objcMsgSend);
            return (styleMask & NS_WINDOW_STYLE_MASK_FULL_SCREEN) != 0L;
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Failed to query macOS native fullscreen state", t);
            return false;
        }
    }

    /**
     * Requests native macOS fullscreen via {@code -[NSWindow toggleFullScreen:]}.
     * Restores {@code GLFW_DECORATED} and {@code GLFW_RESIZABLE} first because Cocoa requires both
     * before entering a fullscreen Space.
     *
     * @return {@code true} if the window is already in native fullscreen or the request succeeded
     */
    public static boolean enterNativeFullscreen(long handle) {
        if (!Platform.isMacOS() || handle == 0L) {
            return false;
        }
        try {
            if (isInNativeFullscreen(handle)) {
                enterPendingUntilNs = 0L;
                return true;
            }
            if (enterPendingUntilNs != 0L && System.nanoTime() < enterPendingUntilNs) {
                return true;
            }

            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);

            if (toggleNativeFullscreen(handle)) {
                enterPendingUntilNs = System.nanoTime() + ENTER_TRANSITION_GUARD_NS;
                return true;
            }
            return false;
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Failed to enter macOS native fullscreen", t);
            return false;
        }
    }

    /**
     * Exits native macOS fullscreen if the window is currently in a native fullscreen Space.
     * Because Cocoa's {@code toggleFullScreen:} animation is asynchronous, callers should wait for
     * {@link #isInNativeFullscreen(long)} to return {@code false} before mutating window decorations
     * or frame bounds when this method returns {@code true}.
     *
     * @return {@code true} if an asynchronous native fullscreen exit was triggered, {@code false} if
     *         not on macOS or the window was not in native fullscreen
     */
    public static boolean exitNativeFullscreen(Window window, long handle) {
        if (!Platform.isMacOS() || handle == 0L) {
            return false;
        }
        enterPendingUntilNs = 0L;
        if (!isInNativeFullscreen(handle)) {
            return false;
        }
        if (window != null) {
            try {
                MacosUtil.exitNativeFullscreen(window);
                return true;
            } catch (Throwable t) {
                Initializer.LOGGER.warn("Vanilla MacosUtil.exitNativeFullscreen failed, falling back to Objective-C runtime", t);
            }
        }
        try {
            return toggleNativeFullscreen(handle);
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Failed to exit macOS native fullscreen", t);
            return false;
        }
    }

    private static boolean toggleNativeFullscreen(long handle) {
        if (!ensureObjC()) {
            return false;
        }
        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(handle);
        if (nsWindow == 0L) {
            return false;
        }
        JNI.invokePPPV(nsWindow, selToggleFullScreen, 0L, objcMsgSend);
        return true;
    }

    private static boolean ensureObjC() {
        if (!objcInitialized) {
            objcInitialized = true;
            objcMsgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
            selStyleMask = ObjCRuntime.sel_registerName("styleMask");
            selToggleFullScreen = ObjCRuntime.sel_registerName("toggleFullScreen:");
        }
        return objcMsgSend != 0L && selStyleMask != 0L && selToggleFullScreen != 0L;
    }
}
