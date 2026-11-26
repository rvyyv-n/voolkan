/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.macos;

import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Window;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Platform;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

/**
 * macOS AppKit / Cocoa helpers for querying/toggling native macOS fullscreen Spaces and syncing
 * the {@code CAMetalLayer} backing scale for live non-Retina (1x) resolution switching.
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
    private static long selContentView = 0L;
    private static long selLayer = 0L;
    private static long selSetContentsScale = 0L;
    private static long selBackingScaleFactor = 0L;
    private static long enterPendingUntilNs = 0L;
    private static Boolean lastAppliedReducedScale = null;

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

    /**
     * Synchronizes the content view's {@code CAMetalLayer.contentsScale} for macOS Reduce Resolution
     * (1.0x logical points vs native Retina backing scale).
     * <p>
     * Called right after Vulkan surface creation and before {@code vkGetPhysicalDeviceSurfaceCapabilitiesKHR}
     * during swapchain (re)creation so MoltenVK reports the exact target extent even if AppKit
     * inherits {@code backingScaleFactor} on {@code setWantsLayer:YES} or fullscreen/display transitions,
     * and so users can toggle Reduce Resolution live without restarting.
     *
     * @return {@code true} if the layer scale was updated
     */
    public static boolean syncMetalLayerScale(long handle, boolean reduceResolution) {
        if (!Platform.isMacOS() || handle == 0L) {
            return false;
        }
        // Leave the default Retina layer untouched unless reduced resolution is active or being reverted live.
        if (!reduceResolution && !Boolean.TRUE.equals(lastAppliedReducedScale)) {
            return false;
        }
        try {
            if (!ensureObjC() || selContentView == 0L || selLayer == 0L || selSetContentsScale == 0L) {
                return false;
            }
            long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(handle);
            if (nsWindow == 0L) {
                return false;
            }
            long contentView = JNI.invokePPP(nsWindow, selContentView, objcMsgSend);
            if (contentView == 0L) {
                return false;
            }
            long layer = JNI.invokePPP(contentView, selLayer, objcMsgSend);
            if (layer == 0L) {
                return false;
            }

            double targetScale = 1.0;
            if (!reduceResolution) {
                double resolved = 0.0;
                if (selBackingScaleFactor != 0L) {
                    double backing = JNI.invokePPD(nsWindow, selBackingScaleFactor, objcMsgSend);
                    if (Double.isFinite(backing) && backing >= 1.0) {
                        resolved = backing;
                    }
                }
                if (resolved < 1.0) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        FloatBuffer xScale = stack.mallocFloat(1);
                        FloatBuffer yScale = stack.mallocFloat(1);
                        GLFW.glfwGetWindowContentScale(handle, xScale, yScale);
                        float sx = xScale.get(0);
                        if (Float.isFinite(sx) && sx >= 1.0f) {
                            resolved = sx;
                        }
                    }
                }
                if (resolved < 1.0) {
                    // Window may be minimized / 0x0; keep lastAppliedReducedScale so the next recreate retries.
                    return false;
                }
                targetScale = resolved;
            }

            // Invoke void (*)(id, SEL, CGFloat) via non-variadic libffi so Apple arm64 passes double in d0.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer argTypes = stack.pointers(
                        LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_double
                );
                FFICIF cif = FFICIF.calloc(stack);
                if (LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_void, argTypes) != LibFFI.FFI_OK) {
                    return false;
                }
                PointerBuffer pSelf = stack.pointers(layer);
                PointerBuffer pSel = stack.pointers(selSetContentsScale);
                DoubleBuffer pScale = stack.doubles(targetScale);
                PointerBuffer argValues = stack.pointers(
                        pSelf.address(),
                        pSel.address(),
                        MemoryUtil.memAddress(pScale)
                );
                LibFFI.ffi_call(cif, objcMsgSend, null, argValues);
            }
            lastAppliedReducedScale = reduceResolution;
            return true;
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Failed to sync macOS CAMetalLayer contentsScale", t);
            return false;
        }
    }

    /** Clears transient window state on renderer shutdown. */
    public static void reset() {
        enterPendingUntilNs = 0L;
        lastAppliedReducedScale = null;
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
            selContentView = ObjCRuntime.sel_registerName("contentView");
            selLayer = ObjCRuntime.sel_registerName("layer");
            selSetContentsScale = ObjCRuntime.sel_registerName("setContentsScale:");
            selBackingScaleFactor = ObjCRuntime.sel_registerName("backingScaleFactor");
        }
        return objcMsgSend != 0L && selStyleMask != 0L && selToggleFullScreen != 0L;
    }
}
