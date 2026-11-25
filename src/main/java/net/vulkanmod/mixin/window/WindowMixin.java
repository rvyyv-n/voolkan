package net.vulkanmod.mixin.window;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * restore macOS Retina/graphics-switching GLFW hints and add macOS borderless/native fullscreen handling.
 */

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.voolkan.macos.MacosWindowUtil;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.option.Options;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Final @Shadow private long handle;

    @Shadow private boolean vsync;
    @Shadow private boolean fullscreen;

    @Shadow @Final private static Logger LOGGER;

    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    @Shadow public abstract int getWidth();

    @Shadow public abstract int getHeight();

    @Shadow protected abstract void updateFullscreen(boolean bl, @Nullable TracyFrameCapture tracyFrameCapture);


    @Unique private static final long MACOS_EXIT_TIMEOUT_NS = 2_000_000_000L;

    @Unique private boolean wasOnFullscreen = false;
    @Unique private boolean macosModeInitialized = false;
    @Unique private boolean pendingMacosExit = false;
    @Unique private long pendingMacosExitDeadlineNs = 0L;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void redirect(int hint, int value) { }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"), index = 3)
    private long modifyCreateWindowMonitor(long monitor) {
        // On macOS, always create a windowed handle first so setModeMacOS enters borderless or native fullscreen cleanly.
        return Platform.isMacOS() ? 0L : monitor;
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void vulkanHint(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        //Fix Gnome Client-Side Decorators
        boolean b = (Platform.isGnome() | Platform.isWeston() | Platform.isGeneric()) && Platform.isWayLand();
        GLFW.glfwWindowHint(GLFW_DECORATED, (b ? GLFW_FALSE : GLFW_TRUE));

        if (Platform.isMacOS()) {
            GLFW.glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW_COCOA_GRAPHICS_SWITCHING, GLFW_FALSE);
        }
    }

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void getHandle(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        VRenderSystem.setWindow(this.handle);
    }

    /**
     * @author
     */
    @Overwrite
    public void updateVsync(boolean vsync) {
        this.vsync = vsync;
        Vulkan.setVsync(vsync);
    }

    /**
     * @author
     */
    @Overwrite
    public void toggleFullScreen() {
        if (Platform.isMacOS()) {
            toggleFullScreenMacOS();
            return;
        }

        this.fullscreen = !this.fullscreen;
        Options.fullscreenDirty = true;

        if (!this.fullscreen) {
            Config config = Initializer.CONFIG;
            config.windowMode = WindowMode.WINDOWED.mode;
        }
    }

    /**
     * @author
     */
    @Overwrite
    public void updateDisplay(@Nullable TracyFrameCapture tracyFrameCapture) {
        RenderSystem.flipFrame((Window) ((Object)this), tracyFrameCapture);

        if (Platform.isMacOS() && this.pendingMacosExit) {
            if (!MacosWindowUtil.isInNativeFullscreen(this.handle)
                    || System.nanoTime() >= this.pendingMacosExitDeadlineNs) {
                this.pendingMacosExit = false;
                Options.fullscreenDirty = true;
            }
        }

        if (Options.fullscreenDirty) {
            Options.fullscreenDirty = false;
            this.updateFullscreen(this.vsync, tracyFrameCapture);
        }
    }

    /**
     * @author
     */
    @Overwrite
    private void setMode() {
        if (Platform.isMacOS()) {
            setModeMacOS();
            return;
        }

        Config config = Initializer.CONFIG;
        VideoModeManager.checkConfigVideoMode(config);

        if (this.fullscreen) {
            config.windowMode = WindowMode.EXCLUSIVE_FULLSCREEN.mode;
        }

        if (this.fullscreen) {
            {
                VideoModeManager.selectBestMonitor((Window) (Object) this);
                long monitor = VideoModeManager.selectedMonitor;
                VideoModeSet.VideoMode videoMode = config.videoMode;

                boolean supported;
                VideoModeSet set = VideoModeManager.getVideoModeSet(videoMode);

                if (set != null) {
                    supported = set.hasRefreshRate(videoMode.refreshRate);
                }
                else {
                    supported = false;
                }

                if (!supported) {
                    LOGGER.error("Resolution not supported, using first available as fallback");
                    videoMode = VideoModeManager.getFirstAvailable().getVideoMode();
                }

                if (!this.wasOnFullscreen) {
                    this.windowedX = this.x;
                    this.windowedY = this.y;
                    this.windowedWidth = this.width;
                    this.windowedHeight = this.height;
                }

                this.x = 0;
                this.y = 0;
                this.width = videoMode.width;
                this.height = videoMode.height;
                GLFW.glfwSetWindowMonitor(this.handle, monitor, this.x, this.y, this.width, this.height, videoMode.refreshRate);

                this.wasOnFullscreen = true;
            }
        }
        else if (config.windowMode == WindowMode.WINDOWED_FULLSCREEN.mode) {
            VideoModeManager.selectBestMonitor((Window) (Object) this);
            VideoModeSet.VideoMode videoMode = VideoModeManager.getOsVideoMode();

            if (!this.wasOnFullscreen) {
                this.windowedX = this.x;
                this.windowedY = this.y;
                this.windowedWidth = this.width;
                this.windowedHeight = this.height;
            }

            int width = videoMode.width;
            int height = videoMode.height;

            GLFW.glfwSetWindowAttrib(this.handle, GLFW_DECORATED, GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(this.handle, 0L, 0, 0, width, height, -1);

            this.width = width;
            this.height = height;
            this.wasOnFullscreen = true;
        } else {
            this.x = this.windowedX;
            this.y = this.windowedY;
            this.width = this.windowedWidth;
            this.height = this.windowedHeight;

            GLFW.glfwSetWindowMonitor(this.handle, 0L, this.x, this.y, this.width, this.height, -1);
            GLFW.glfwSetWindowAttrib(this.handle, GLFW_DECORATED, GLFW_TRUE);

            this.wasOnFullscreen = false;
        }
    }

    @Unique
    private void toggleFullScreenMacOS() {
        Config config = Initializer.CONFIG;
        boolean currentlyFullscreen = this.fullscreen
                || config.windowMode != WindowMode.WINDOWED.mode
                || MacosWindowUtil.isInNativeFullscreen(this.handle);
        if (currentlyFullscreen) {
            this.fullscreen = false;
            config.windowMode = WindowMode.WINDOWED.mode;
        } else {
            this.fullscreen = true;
            config.windowMode = config.macNativeFullscreen
                    ? WindowMode.EXCLUSIVE_FULLSCREEN.mode
                    : WindowMode.WINDOWED_FULLSCREEN.mode;
        }
        Options.fullscreenDirty = true;
    }

    @Unique
    private void setModeMacOS() {
        Config config = Initializer.CONFIG;
        VideoModeManager.checkConfigVideoMode(config);

        boolean firstInit = !this.macosModeInitialized;
        this.macosModeInitialized = true;

        if (firstInit && config.windowMode == WindowMode.WINDOWED.mode && this.fullscreen) {
            config.windowMode = config.macNativeFullscreen
                    ? WindowMode.EXCLUSIVE_FULLSCREEN.mode
                    : WindowMode.WINDOWED_FULLSCREEN.mode;
        }

        boolean inNativeBefore = MacosWindowUtil.isInNativeFullscreen(this.handle);

        if (config.windowMode == WindowMode.EXCLUSIVE_FULLSCREEN.mode && config.macNativeFullscreen) {
            this.pendingMacosExit = false;

            if (!this.wasOnFullscreen && !inNativeBefore) {
                this.windowedX = this.x;
                this.windowedY = this.y;
                this.windowedWidth = this.width;
                this.windowedHeight = this.height;
            }

            if (GLFW.glfwGetWindowMonitor(this.handle) != 0L) {
                int w = this.windowedWidth > 0 ? this.windowedWidth : this.width;
                int h = this.windowedHeight > 0 ? this.windowedHeight : this.height;
                GLFW.glfwSetWindowMonitor(this.handle, 0L, this.windowedX, this.windowedY, w, h, -1);
            }

            boolean inNative = inNativeBefore || MacosWindowUtil.enterNativeFullscreen(this.handle);
            if (!inNative) {
                enterBorderlessMacOS(false);
                return;
            }

            this.fullscreen = true;
            this.wasOnFullscreen = true;
            syncVanillaFullscreenMacOS(true);
        } else if (config.windowMode == WindowMode.WINDOWED_FULLSCREEN.mode
                || config.windowMode == WindowMode.EXCLUSIVE_FULLSCREEN.mode) {
            enterBorderlessMacOS(inNativeBefore);
        } else {
            if (this.pendingMacosExit && inNativeBefore && System.nanoTime() < this.pendingMacosExitDeadlineNs) {
                return;
            }
            if (MacosWindowUtil.exitNativeFullscreen((Window) (Object) this, this.handle)) {
                this.pendingMacosExit = true;
                this.pendingMacosExitDeadlineNs = System.nanoTime() + MACOS_EXIT_TIMEOUT_NS;
                this.fullscreen = false;
                this.wasOnFullscreen = false;
                syncVanillaFullscreenMacOS(false);
                return;
            }

            this.pendingMacosExit = false;
            GLFW.glfwSetWindowAttrib(this.handle, GLFW_DECORATED, GLFW_TRUE);
            GLFW.glfwSetWindowAttrib(this.handle, GLFW_RESIZABLE, GLFW_TRUE);

            if (this.windowedWidth > 0 && this.windowedHeight > 0) {
                this.x = this.windowedX;
                this.y = this.windowedY;
                this.width = this.windowedWidth;
                this.height = this.windowedHeight;
                GLFW.glfwSetWindowMonitor(this.handle, 0L, this.x, this.y, this.width, this.height, -1);
            }

            this.fullscreen = false;
            this.wasOnFullscreen = false;
            syncVanillaFullscreenMacOS(false);
        }
    }

    @Unique
    private void enterBorderlessMacOS(boolean inNativeBefore) {
        if (this.pendingMacosExit && inNativeBefore && System.nanoTime() < this.pendingMacosExitDeadlineNs) {
            return;
        }
        if (MacosWindowUtil.exitNativeFullscreen((Window) (Object) this, this.handle)) {
            this.pendingMacosExit = true;
            this.pendingMacosExitDeadlineNs = System.nanoTime() + MACOS_EXIT_TIMEOUT_NS;
            return;
        }
        this.pendingMacosExit = false;

        Window self = (Window) (Object) this;
        VideoModeManager.selectBestMonitor(self);
        long monitorHandle = VideoModeManager.selectedMonitor;
        Monitor monitor = VideoModeManager.getMonitors() != null
                ? VideoModeManager.getMonitors().get(monitorHandle)
                : null;

        int targetX = 0;
        int targetY = 0;
        int targetWidth;
        int targetHeight;
        if (monitor != null) {
            targetX = monitor.getX();
            targetY = monitor.getY();
            VideoMode currentMode = monitor.getCurrentMode();
            targetWidth = currentMode.getWidth();
            targetHeight = currentMode.getHeight();
        } else {
            VideoModeSet.VideoMode videoMode = VideoModeManager.getOsVideoMode();
            targetWidth = videoMode.width;
            targetHeight = videoMode.height;
        }

        // Fit within the monitor workarea (NSScreen.visibleFrame on macOS) so borderless fullscreen
        // stays below the menu bar / camera notch and clear of any always-visible Dock.
        if (monitorHandle != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer wx = stack.mallocInt(1);
                IntBuffer wy = stack.mallocInt(1);
                IntBuffer ww = stack.mallocInt(1);
                IntBuffer wh = stack.mallocInt(1);
                GLFW.glfwGetMonitorWorkarea(monitorHandle, wx, wy, ww, wh);
                if (ww.get(0) > 0 && wh.get(0) > 0) {
                    targetX = wx.get(0);
                    targetY = wy.get(0);
                    targetWidth = ww.get(0);
                    targetHeight = wh.get(0);
                }
            }
        }

        if (!this.wasOnFullscreen && !inNativeBefore) {
            this.windowedX = this.x;
            this.windowedY = this.y;
            this.windowedWidth = this.width;
            this.windowedHeight = this.height;
        }

        GLFW.glfwSetWindowAttrib(this.handle, GLFW_DECORATED, GLFW_FALSE);
        GLFW.glfwSetWindowAttrib(this.handle, GLFW_RESIZABLE, GLFW_FALSE);
        GLFW.glfwSetWindowMonitor(this.handle, 0L, targetX, targetY, targetWidth, targetHeight, -1);

        this.x = targetX;
        this.y = targetY;
        this.width = targetWidth;
        this.height = targetHeight;
        this.fullscreen = true;
        this.wasOnFullscreen = true;
        syncVanillaFullscreenMacOS(true);
    }

    @Unique
    private void syncVanillaFullscreenMacOS(boolean targetFullscreen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null && mc.options.fullscreen().get() != targetFullscreen) {
            // this.fullscreen already matches targetFullscreen, so vanilla's listener will not call toggleFullScreen().
            mc.options.fullscreen().set(targetFullscreen);
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void onFramebufferResize(long window, int width, int height) {
        if (window == this.handle) {
            int prevWidth = this.getWidth();
            int prevHeight = this.getHeight();

            if(width > 0 && height > 0) {
                this.framebufferWidth = width;
                this.framebufferHeight = height;

                Renderer.scheduleSwapChainUpdate();
            }

        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void onResize(long window, int width, int height) {
        this.width = width;
        this.height = height;

        if (width > 0 && height > 0) {
            if (Platform.isMacOS()
                    && !this.fullscreen
                    && !this.wasOnFullscreen
                    && !this.pendingMacosExit
                    && !MacosWindowUtil.isInNativeFullscreen(this.handle)) {
                this.windowedX = this.x;
                this.windowedY = this.y;
                this.windowedWidth = width;
                this.windowedHeight = height;
            }
            Renderer.scheduleSwapChainUpdate();
        }
    }

}
