/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.util.OptionalInt;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Renders the 3D world (level, hand, entity outlines, and vanilla post-effect chains) into a
 * scaled offscreen target and bilinearly upscales it into the swapchain before GUI rendering so
 * the HUD and screens remain at native resolution.
 *
 * <h3>Hooks and invariants</h3>
 * <ul>
 *   <li><b>Hooks:</b> {@code GameRendererMixin} calls {@link DefaultMainPass#beginWorld} right
 *       before {@code GameRenderer.renderLevel} and {@link DefaultMainPass#endWorld} right before
 *       {@code FogRenderer.endFrame} (after {@code renderLevel}, {@code tryTakeScreenshotIfNeeded},
 *       {@code doEntityOutline}, and {@code PostChain.process}, and before vanilla's GUI depth
 *       clear). World mode is only active between these two hooks and is unconditionally reset in
 *       {@code Renderer.beginFrame}, {@code DefaultMainPass.end}, {@code onResize}, and
 *       {@code cleanUp} so exceptions cannot leak world mode into a later frame.</li>
 *   <li><b>Zero-cost default:</b> at scale 100%, no offscreen resources are allocated and both
 *       hooks return immediately without altering the render pass or command stream.</li>
 *   <li><b>Pipeline compatibility (legacy and dynamic):</b> the world framebuffer uses the exact
 *       swapchain colour format and {@link Vulkan#getDefaultDepthFormat()}, and both passes use
 *       {@code COLOR_ATTACHMENT_OPTIMAL} as final layout (dependency kind 0) and non-UNDEFINED
 *       initial layouts (matching {@link DefaultMainPass}). Legacy {@code VkRenderPass} pipelines
 *       are render-pass compatible with zero recompilation, and dynamic-rendering pipelines see
 *       identical attachment formats.</li>
 *   <li><b>Deferred destruction:</b> resizing, changing scale, returning to 100%, or shutting down
 *       releases {@code VkFramebuffer}, {@code VkRenderPass}, {@code VulkanImage}, and the world
 *       {@code GlobalSettingsUniform} buffer through {@code MemoryManager} (after the in-flight
 *       frame slot finishes) or after {@code Vulkan.waitIdle()} on swapchain recreation/cleanup.</li>
 *   <li><b>Cross-frame synchronisation:</b> colour transitions {@code COLOR_ATTACHMENT_OPTIMAL ->
 *       SHADER_READ_ONLY_OPTIMAL} (RAW) before upscaling and {@code SHADER_READ_ONLY_OPTIMAL ->
 *       COLOR_ATTACHMENT_OPTIMAL} (WAR) at next frame's {@link #begin}. Depth remains in
 *       {@code DEPTH_STENCIL_ATTACHMENT_OPTIMAL} across frames, so {@link #begin} emits an
 *       explicit {@code EARLY|LATE_FRAGMENT_TESTS} barrier before clearing to prevent WAW hazards
 *       across frames in flight.</li>
 *   <li><b>Upscale orientation:</b> the world target is bound as a main-like framebuffer with
 *       VulkanMod's flipped viewport ({@code y = sh, height = -sh}), storing row 0 ({@code v = 0})
 *       at the top of the screen just like the swapchain. Drawing {@link RenderPipelines#TRACY_BLIT}
 *       (VulkanMod's remapped {@code core/screenquad.vsh} + vanilla {@code core/blit_screen.fsh})
 *       onto the swapchain's flipped viewport maps screen top-left ({@code NDC (-1,+1)}) to
 *       {@code texCoord (0,0)} and bottom-right ({@code NDC (+1,-1)}) to {@code (1,1)} with CCW
 *       front-facing winding, producing an upright, unculled, opaque bilinear upscale.</li>
 * </ul>
 */
public final class WorldScale {
    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 100;
    public static final int STEP = 5;

    private Framebuffer framebuffer;
    private RenderPass clearPass;
    private RenderPass auxPass;

    private VkGpuTexture colorTexture;
    private GpuTextureView colorTextureView;
    private VkGpuTexture depthTexture;
    private GpuTextureView depthTextureView;

    private GlobalSettingsUniform worldGlobals;

    private int width;
    private int height;
    private int colorFormat;
    private int depthFormat;

    private boolean active;
    private int savedTargetWidth;
    private int savedTargetHeight;
    private GpuBuffer savedGlobalsUniform;

    public static int clampScale(int percent) {
        if (percent <= 0 || percent >= MAX_SCALE) {
            return MAX_SCALE;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, percent));
    }

    public static int configuredScale() {
        return Initializer.CONFIG != null ? clampScale(Initializer.CONFIG.worldScale) : MAX_SCALE;
    }

    public static boolean isEnabled() {
        return configuredScale() < MAX_SCALE;
    }

    public boolean isActive() {
        return this.active;
    }

    public Framebuffer getFramebuffer() {
        return this.framebuffer;
    }

    public RenderPass getClearPass() {
        return this.clearPass;
    }

    public RenderPass getAuxPass() {
        return this.auxPass;
    }

    public GpuTexture getColorAttachment() {
        return this.colorTexture;
    }

    public GpuTextureView getColorAttachmentView() {
        return this.colorTextureView;
    }

    public GpuTexture getDepthAttachment() {
        return this.depthTexture;
    }

    public GpuTextureView getDepthAttachmentView() {
        return this.depthTextureView;
    }

    /**
     * Releases offscreen resources once the option returns to 100%. Must be called after
     * {@code MemoryManager.initFrame(currentFrame)} so deferred destruction waits for in-flight frames.
     */
    public void syncDisabled() {
        if (!isEnabled() && this.framebuffer != null) {
            release();
        }
    }

    public boolean begin(SwapChain swapChain, DeltaTracker deltaTracker, Camera camera) {
        int scale = configuredScale();
        if (scale >= MAX_SCALE || this.active || !Renderer.isRecording()) {
            return false;
        }

        if (!ensureResources(swapChain, scale)) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        this.savedTargetWidth = mainTarget.width;
        this.savedTargetHeight = mainTarget.height;
        this.savedGlobalsUniform = RenderSystem.getGlobalSettingsUniform();

        try {
            mainTarget.width = this.width;
            mainTarget.height = this.height;

            // Update a dedicated Globals UBO with the scaled ScreenSize so world shaders such as
            // rendertype_lines.vsh size lines to the scaled framebuffer while vanilla's Globals
            // buffer keeps the full window size for GUI draws later in the same command buffer.
            long gameTime = mc.level == null ? 0L : mc.level.getGameTime();
            boolean useRgss = mc.options.textureFiltering().get() == TextureFilteringMethod.RGSS;
            this.worldGlobals.update(
                    this.width,
                    this.height,
                    mc.options.glintStrength().get(),
                    gameTime,
                    deltaTracker,
                    mc.options.getMenuBackgroundBlurriness(),
                    camera,
                    useRgss);

            Renderer renderer = Renderer.getInstance();
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            renderer.endRenderPass(commandBuffer);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanImage depth = this.framebuffer.getDepthAttachment();
                if (depth.getCurrentLayout() == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                    emitDepthReuseBarrier(stack, commandBuffer, depth);
                }

                VulkanImage color = this.framebuffer.getColorAttachment();
                if (color.getCurrentLayout() == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                    emitColorReuseBarrier(stack, commandBuffer, color);
                }
            }

            VRenderSystem.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            VRenderSystem.clearDepth(1.0);
            this.active = true;
            renderer.beginRenderPass(this.clearPass, this.framebuffer);
            return true;
        } catch (Throwable t) {
            restoreState();
            throw t;
        }
    }

    public void end(DefaultMainPass mainPass) {
        if (!this.active) {
            return;
        }

        if (!Renderer.isRecording()) {
            restoreState();
            return;
        }

        Renderer renderer = Renderer.getInstance();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // End the world pass BEFORE transitioning to SHADER_READ_ONLY_OPTIMAL so the barrier is
        // recorded directly into the frame command buffer (never into ImageUploadHelper).
        renderer.endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.framebuffer.getColorAttachment().readOnlyLayout(stack, commandBuffer);
        }

        restoreState();

        // Rebind the real swapchain with LOAD_OP_DONT_CARE (non-UNDEFINED initial layout) because
        // TRACY_BLIT immediately overwrites 100% of swapchain color and vanilla clears depth before GUI.
        mainPass.rebindMainTargetDiscard();

        // Bilinear full-screen upscale into the swapchain; autoManaged=true leaves the swapchain bound
        // for vanilla's subsequent depth clear and GUI rendering.
        // Orientation: worldColor was rendered with VulkanMod's flipped viewport (row 0 = top), and
        // TRACY_BLIT uses VulkanMod's remapped core/screenquad.vsh (v=0 at NDC y=+1, v=1 at y=-1)
        // on the swapchain's flipped viewport with CCW winding -> upright, unculled opaque blit.
        GpuTextureView swapchainView = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
        try (com.mojang.blaze3d.systems.RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "voolkan world upscale", swapchainView, OptionalInt.empty())) {
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", this.colorTextureView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
    }

    public void reset() {
        if (this.active) {
            restoreState();
        }
    }

    public void release() {
        reset();

        if (this.colorTextureView != null) {
            this.colorTextureView.close();
            this.colorTextureView = null;
        }
        if (this.depthTextureView != null) {
            this.depthTextureView.close();
            this.depthTextureView = null;
        }
        if (this.framebuffer != null) {
            // Destroy cached VkFramebuffer handles deferred; images are freed via the VkGpuTexture
            // wrappers below so their VkGlTexture entries are cleaned up without double-queueing.
            this.framebuffer.cleanUp(false);
            this.framebuffer = null;
        }
        if (this.colorTexture != null) {
            this.colorTexture.close();
            this.colorTexture = null;
        }
        if (this.depthTexture != null) {
            this.depthTexture.close();
            this.depthTexture = null;
        }
        if (this.clearPass != null) {
            this.clearPass.cleanUp();
            this.clearPass = null;
        }
        if (this.auxPass != null) {
            this.auxPass.cleanUp();
            this.auxPass = null;
        }
        if (this.worldGlobals != null) {
            this.worldGlobals.close();
            this.worldGlobals = null;
        }

        this.width = 0;
        this.height = 0;
        this.colorFormat = 0;
        this.depthFormat = 0;
    }

    private boolean ensureResources(SwapChain swapChain, int scalePercent) {
        if (swapChain == null || !swapChain.hasImages()) {
            return false;
        }

        int baseW = swapChain.getWidth();
        int baseH = swapChain.getHeight();
        if (baseW <= 0 || baseH <= 0) {
            return false;
        }

        int sw = Math.max(1, Math.round(baseW * (scalePercent / 100.0f)));
        int sh = Math.max(1, Math.round(baseH * (scalePercent / 100.0f)));
        if (sw >= baseW && sh >= baseH) {
            if (this.framebuffer != null) {
                release();
            }
            return false;
        }

        int cFmt = swapChain.getFormat();
        int dFmt = Vulkan.getDefaultDepthFormat();

        if (this.framebuffer != null
                && this.width == sw
                && this.height == sh
                && this.colorFormat == cFmt
                && this.depthFormat == dFmt) {
            return true;
        }

        release();

        this.framebuffer = new Framebuffer.Builder("World", sw, sh, 1, true)
                .setFormat(cFmt)
                .setLinearFiltering(true)
                .build();

        RenderPass.Builder builder = RenderPass.builder(this.framebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        this.clearPass = builder.build();

        builder = RenderPass.builder(this.framebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        this.auxPass = builder.build();

        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();
        this.colorTexture = device.gpuTextureFromVulkanImage(this.framebuffer.getColorAttachment());
        this.colorTextureView = device.createTextureView(this.colorTexture);
        this.depthTexture = device.gpuTextureFromVulkanImage(this.framebuffer.getDepthAttachment());
        this.depthTextureView = device.createTextureView(this.depthTexture);

        this.worldGlobals = new GlobalSettingsUniform();

        this.width = sw;
        this.height = sh;
        this.colorFormat = cFmt;
        this.depthFormat = dFmt;
        return true;
    }

    private void restoreState() {
        this.active = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            RenderTarget mainTarget = mc.getMainRenderTarget();
            if (mainTarget != null && this.savedTargetWidth > 0 && this.savedTargetHeight > 0) {
                mainTarget.width = this.savedTargetWidth;
                mainTarget.height = this.savedTargetHeight;
            }
        }

        if (this.savedGlobalsUniform != null) {
            RenderSystem.setGlobalSettingsUniform(this.savedGlobalsUniform);
            this.savedGlobalsUniform = null;
        }

        this.savedTargetWidth = 0;
        this.savedTargetHeight = 0;
    }

    private static void emitDepthReuseBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage depth) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        barrier.oldLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        barrier.newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.image(depth.getId());
        barrier.subresourceRange()
               .aspectMask(depth.aspect)
               .baseMipLevel(0)
               .levelCount(1)
               .baseArrayLayer(0)
               .layerCount(1);
        barrier.srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        barrier.dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                0,
                null,
                null,
                barrier);
    }

    private static void emitColorReuseBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage color) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        barrier.oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        barrier.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.image(color.getId());
        barrier.subresourceRange()
               .aspectMask(color.aspect)
               .baseMipLevel(0)
               .levelCount(1)
               .baseArrayLayer(0)
               .layerCount(1);
        barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        barrier.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                0,
                null,
                null,
                barrier);
    }
}
