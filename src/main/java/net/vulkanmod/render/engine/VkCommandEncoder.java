package net.vulkanmod.render.engine;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * oversized buffer writes fall back to a one-shot staging buffer; copyTextureToTexture (with cross-format blit fallback) and createFence are implemented; fold main-target and offscreen clears into LOAD_OP_CLEAR on tile GPUs.
 */

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dev.voolkan.compat.MacPerfOpts;
import dev.voolkan.compat.TileGpuOpts;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.queue.GraphicsQueue;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VkCommandEncoder implements CommandEncoder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final VkGpuDevice device;

    @Nullable
    private RenderPipeline lastPipeline;
    private boolean inRenderPass;

    @Nullable
    private EGlProgram lastProgram;

    private int framebufferId = VkGlFramebuffer.genFramebufferId();
    private final LongOpenHashSet writtenTransferBuffers = new LongOpenHashSet();

    protected VkCommandEncoder(VkGpuDevice glDevice) {
        this.device = glDevice;
    }

    /**
     * Opens a color-only {@code LOAD_OP_DONT_CARE} render pass for opaque fullscreen post-processing
     * passes (such as menu blur) on Apple TBDR GPUs, skipping depth attachment load/store and
     * unnecessary color tile loads while leaving main depth untouched.
     */
    public RenderPass createOpaqueColorOnlyPostPass(Supplier<String> supplier, GpuTextureView colorAttachmentView) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        if (!MacPerfOpts.menuBlurOpts()) {
            return this.createRenderPass(supplier, colorAttachmentView, OptionalInt.empty(), null, OptionalDouble.empty());
        }

        if (Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachmentView.texture()) {
            if (Renderer.getInstance().getMainPass().rebindMainTargetColorOnlyDiscard()) {
                this.inRenderPass = true;
                this.lastPipeline = null;
                // forceEnd=true on close so subsequent GUI draws cleanly rebind auxRenderPass with depth.
                return new VkRenderPass(this, false, false);
            }
            return this.createRenderPass(supplier, colorAttachmentView, OptionalInt.empty(), null, OptionalDouble.empty());
        }

        if (colorAttachmentView.isClosed()) {
            throw new IllegalStateException("Color texture is closed");
        }

        VkFbo fbo = ((VkTextureView) colorAttachmentView).getFbo(null);
        if (fbo.bindDiscardColor()) {
            this.inRenderPass = true;
            GlStateManager._viewport(0, 0, colorAttachmentView.getWidth(0), colorAttachmentView.getHeight(0));
            this.lastPipeline = null;
            return new VkRenderPass(this, false, false);
        }

        return this.createRenderPass(supplier, colorAttachmentView, OptionalInt.empty(), null, OptionalDouble.empty());
    }

    @Override
    public RenderPass createRenderPass(Supplier<String> supplier, GpuTextureView colorAttachmentView, OptionalInt optionalInt) {
        return this.createRenderPass(supplier, colorAttachmentView, optionalInt, null, OptionalDouble.empty());
    }

    @Override
    public RenderPass createRenderPass(Supplier<String> supplier, GpuTextureView colorAttachmentView, OptionalInt optionalInt, @Nullable GpuTextureView depthTexture, OptionalDouble optionalDouble) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        } else {
            if (optionalDouble.isPresent() && depthTexture == null) {
                LOGGER.warn("Depth clear value was provided but no depth texture is being used");
            }

            if (Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachmentView.texture()) {
                if (TileGpuOpts.isEnabled()) {
                    boolean clearColor = optionalInt.isPresent();
                    boolean clearDepth = depthTexture != null && optionalDouble.isPresent();

                    int j = 0;
                    if (clearColor) {
                        int k = optionalInt.getAsInt();
                        VRenderSystem.setClearColor(ARGB.redFloat(k), ARGB.greenFloat(k), ARGB.blueFloat(k), ARGB.alphaFloat(k));
                        j |= 16384;
                    }

                    if (clearDepth) {
                        VRenderSystem.clearDepth(optionalDouble.getAsDouble());
                        j |= 256;
                    }

                    boolean clearedNatively = Renderer.getInstance().getMainPass().rebindMainTarget(clearColor, clearDepth);
                    if (j != 0 && !clearedNatively) {
                        GlStateManager._disableScissorTest();
                        GlStateManager._depthMask(true);
                        GlStateManager._colorMask(true, true, true, true);
                        GlStateManager._clear(j);
                    }

                    return new VkRenderPass(this, depthTexture != null, true);
                }

                Renderer.getInstance().getMainPass().rebindMainTarget();

                int j = 0;
                if (optionalInt.isPresent()) {
                    int k = optionalInt.getAsInt();
                    GL11.glClearColor(ARGB.redFloat(k), ARGB.greenFloat(k), ARGB.blueFloat(k), ARGB.alphaFloat(k));
                    j |= 16384;
                }

                if (depthTexture != null && optionalDouble.isPresent()) {
                    GL11.glClearDepth(optionalDouble.getAsDouble());
                    j |= 256;
                }

                if (j != 0) {
                    GlStateManager._disableScissorTest();
                    GlStateManager._depthMask(true);
                    GlStateManager._colorMask(true, true, true, true);
                    GlStateManager._clear(j);
                }

                return new VkRenderPass(this, depthTexture != null, true);
            }

            if (colorAttachmentView.isClosed()) {
                throw new IllegalStateException("Color texture is closed");
            } else if (depthTexture != null && depthTexture.isClosed()) {
                throw new IllegalStateException("Depth texture is closed");
            } else {
                this.inRenderPass = true;
                GpuTexture depthTexture1 = depthTexture != null ? depthTexture.texture() : null;
                VkFbo fbo = ((VkTextureView)colorAttachmentView).getFbo(depthTexture1);

                if (TileGpuOpts.isEnabled()) {
                    boolean clearColor = optionalInt.isPresent();
                    boolean clearDepth = depthTexture != null && optionalDouble.isPresent();

                    if (clearColor) {
                        int k = optionalInt.getAsInt();
                        VRenderSystem.setClearColor(ARGB.redFloat(k), ARGB.greenFloat(k), ARGB.blueFloat(k), ARGB.alphaFloat(k));
                    }

                    if (clearDepth) {
                        VRenderSystem.clearDepth(optionalDouble.getAsDouble());
                    }

                    fbo.bind(clearColor, clearDepth);

                    GlStateManager._viewport(0, 0, colorAttachmentView.getWidth(0), colorAttachmentView.getHeight(0));
                    this.lastPipeline = null;
                    // On Apple TBDR, keep GuiRenderer.prepare() offscreen FBOs (PiP, itemsAtlas) open across
                    // consecutive RenderType draws so multi-batch UI preparation stays in one render pass
                    // (closed deterministically at GuiRenderer.prepare() RETURN or on FBO switch/delete).
                    boolean keepOffscreenOpen = MacPerfOpts.tbdrPassOpts()
                            && depthTexture != null
                            && RenderSystem.outputColorTextureOverride != null;
                    return new VkRenderPass(this, depthTexture != null, keepOffscreenOpen);
                }

                fbo.bind();

                int j = 0;
                if (optionalInt.isPresent()) {
                    int k = optionalInt.getAsInt();
                    GL11.glClearColor(ARGB.redFloat(k), ARGB.greenFloat(k), ARGB.blueFloat(k), ARGB.alphaFloat(k));
                    j |= 16384;
                }

                if (depthTexture != null && optionalDouble.isPresent()) {
                    GL11.glClearDepth(optionalDouble.getAsDouble());
                    j |= 256;
                }

                if (j != 0) {
                    GlStateManager._disableScissorTest();
                    GlStateManager._depthMask(true);
                    GlStateManager._colorMask(true, true, true, true);
                    GlStateManager._clear(j);
                }

                GlStateManager._viewport(0, 0, colorAttachmentView.getWidth(0), colorAttachmentView.getHeight(0));
                this.lastPipeline = null;
                return new VkRenderPass(this, depthTexture != null, false);
            }
        }

    }

    @Override
    public void clearColorTexture(GpuTexture colorAttachment, int clearColor) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        else if (Renderer.isRecording()) {
            if (Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachment) {
                if (TileGpuOpts.isEnabled()) {
                    VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                    if (!Renderer.getInstance().getMainPass().rebindMainTarget(true, false)) {
                        Renderer.clearAttachments(0x4000);
                    }
                } else {
                    Renderer.getInstance().getMainPass().rebindMainTarget();

                    VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                    Renderer.clearAttachments(0x4000);
                }
            }
            else if (TileGpuOpts.isEnabled()) {
                VkGpuTexture vkGpuTexture = (VkGpuTexture) colorAttachment;
                if (MacPerfOpts.menuBlurOpts()) {
                    // Defer offscreen color-only clears (matching clearColorAndDepthTextures) so
                    // depth-less PostChain targets (e.g. blur swap) skip an eager full-res clear pass
                    // right before an opaque LOAD_OP_DONT_CARE draw and avoid thrashing framebufferId.
                    VkFbo fbo = vkGpuTexture.getFbo(null);
                    vkGpuTexture.setClearColor(clearColor);

                    Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
                    if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == vkGpuTexture.getVulkanImage()) {
                        fbo.clearAttachments();
                    }
                    return;
                }

                VkGlFramebuffer glFramebuffer = VkGlFramebuffer.getFramebuffer(this.framebufferId);
                Renderer.getInstance().endRenderPass();

                if (glFramebuffer.getFramebuffer() == null
                        || glFramebuffer.getFramebuffer().getColorAttachment() != vkGpuTexture.getVulkanImage()) {
                    glFramebuffer.setAttachmentTexture(GL30.GL_COLOR_ATTACHMENT0, vkGpuTexture.glId());
                    glFramebuffer.create();
                }

                VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                if (!VkGlFramebuffer.beginRendering(glFramebuffer, true, false)) {
                    Renderer.clearAttachments(0x4000);
                }
                Renderer.getInstance().endRenderPass();

                vkGpuTexture.needsClear = false;
            }
            else {
                VkGpuTexture vkGpuTexture = (VkGpuTexture) colorAttachment;
                VkGlFramebuffer.bindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
                VkGlFramebuffer.framebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, vkGpuTexture.glId(), 0);

                VkGlFramebuffer.beginRendering(VkGlFramebuffer.getFramebuffer(framebufferId));
                VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                Renderer.clearAttachments(0x4000);
                Renderer.getInstance().endRenderPass();

                VkFbo fbo = ((VkGpuTexture)colorAttachment).getFbo(null);

                ((VkGpuTexture) colorAttachment).setClearColor(clearColor);

                Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
                if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == ((VkGpuTexture) colorAttachment).getVulkanImage()) {
                    fbo.clearAttachments();
                }
            }
        }
        else {
            GraphicsQueue graphicsQueue = DeviceManager.getGraphicsQueue();
            var commandBuffer = graphicsQueue.getCommandBuffer();
            VkGpuTexture vkGpuTexture = (VkGpuTexture) colorAttachment;

            VkGlFramebuffer glFramebuffer = VkGlFramebuffer.getFramebuffer(this.framebufferId);
            glFramebuffer.setAttachmentTexture(GL30.GL_COLOR_ATTACHMENT0, vkGpuTexture.glId());
            glFramebuffer.create();

            Framebuffer framebuffer = glFramebuffer.getFramebuffer();
            var renderPass = glFramebuffer.getRenderPass();
            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(commandBuffer.handle, renderPass, stack);
            }

            VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
            Renderer.clearAttachments(commandBuffer.handle, 0x4000, 0, 0, framebuffer.getWidth(), framebuffer.getHeight());
            renderPass.endRenderPass(commandBuffer.handle);

            long fence = graphicsQueue.submitCommands(commandBuffer);
            Synchronization.waitFence(fence);
            commandBuffer.reset();
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorAttachment, int clearColor, GpuTexture depthAttachment, double clearDepth) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        else {
            if (Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachment) {
                if (TileGpuOpts.isEnabled()) {
                    VRenderSystem.clearDepth(clearDepth);
                    VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                    if (!Renderer.getInstance().getMainPass().rebindMainTarget(true, true)) {
                        Renderer.clearAttachments(0x4100);
                    }
                } else {
                    Renderer.getInstance().getMainPass().rebindMainTarget();

                    VRenderSystem.clearDepth(clearDepth);
                    VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
                    Renderer.clearAttachments(0x4100);
                }
            }
            else {
                VkFbo fbo = ((VkGpuTexture)colorAttachment).getFbo(depthAttachment);

                ((VkGpuTexture) colorAttachment).setClearColor(clearColor);
                ((VkGpuTexture) depthAttachment).setDepthClearValue((float) clearDepth);

                Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
                if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == ((VkGpuTexture) colorAttachment).getVulkanImage()
                    && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture) depthAttachment).getVulkanImage())
                {
                    fbo.clearAttachments();
                }
            }
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorAttachment, int clearColor, GpuTexture depthAttachment, double clearDepth, int x0, int y0, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        } else {
            VRenderSystem.clearDepth(clearDepth);
            VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));

            int framebufferHeight = colorAttachment.getHeight(0);
            y0 = framebufferHeight - height - y0;

            Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
            if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == ((VkGpuTexture) colorAttachment).getVulkanImage()
                && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture) depthAttachment).getVulkanImage())
            {
                Renderer.clearAttachments(0x4100, x0, y0, width, height);
            }
            else if (TileGpuOpts.isEnabled() && Minecraft.getInstance().getMainRenderTarget().getColorTexture() == colorAttachment) {
                Renderer.getInstance().getMainPass().rebindMainTarget();
                Renderer.clearAttachments(0x4100, x0, y0, width, height);
            }
            else {
                VkGpuTexture gpuTexture = (VkGpuTexture) colorAttachment;
                gpuTexture.getFbo(depthAttachment).bind();

                Renderer.clearAttachments(0x4100, x0, y0, width, height);
            }
        }
    }

    @Override
    public void clearDepthTexture(GpuTexture depthAttachment, double clearDepth) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        else {
            if (TileGpuOpts.isEnabled() && Minecraft.getInstance().getMainRenderTarget().getDepthTexture() == depthAttachment) {
                if (MacPerfOpts.tbdrPassOpts() && clearDepth == 1.0 && Renderer.getInstance().getMainPass().consumeCleanDepthAtOne()) {
                    return;
                }
                VRenderSystem.clearDepth(clearDepth);
                if (!Renderer.getInstance().getMainPass().rebindMainTarget(false, true)) {
                    Renderer.clearAttachments(0x100);
                }
                return;
            }

            Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
            if (boundFramebuffer != null && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture) depthAttachment).getVulkanImage()) {
                VRenderSystem.clearDepth(clearDepth);
                Renderer.clearAttachments(0x100);
            }
            else {
                ((VkGpuTexture) depthAttachment).setDepthClearValue((float) clearDepth);
            }
        }
    }

    @Override
    public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer byteBuffer) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();
            if (vkGpuBuffer.closed) {
                throw new IllegalStateException("Buffer already closed");
            }
            else {
                int size = byteBuffer.remaining();
                if (size + gpuBufferSlice.offset() > vkGpuBuffer.size()) {
                    throw new IllegalArgumentException(
                            "Cannot write more data than this buffer can hold (attempting to write " + size + " bytes at offset " + gpuBufferSlice.offset() + " to " + gpuBufferSlice.length() + " slice size)"
                    );
                } else {
                    long dstOffset = gpuBufferSlice.offset();

                    if (vkGpuBuffer.tryUnifiedUniformWrite(dstOffset, byteBuffer)) {
                        return;
                    }

                    StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();

                    // Large immediate batches (e.g. many entities sharing one RenderType) can
                    // exceed the default staging buffer; fall back to a one-shot oversized buffer.
                    if (size > stagingBuffer.getBufferSize()) {
                        stagingBuffer = new StagingBuffer(size);
                        stagingBuffer.scheduleFree();
                    }

                    stagingBuffer.copyBuffer(size, byteBuffer);

                    long srcOffset = stagingBuffer.getOffset();

                    // Fetch transferCb after staging copy in case overflow flushed and rotated it.
                    var commandBuffer = Renderer.getInstance().getTransferCb();

                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        if (!commandBuffer.isRecording()) {
                            commandBuffer.begin(stack);
                            this.writtenTransferBuffers.clear();
                        }

                        long dstBufferId = vkGpuBuffer.buffer.getId();
                        if (!this.writtenTransferBuffers.add(dstBufferId)) {
                            VkBufferMemoryBarrier.Buffer bufferMemoryBarriers = VkBufferMemoryBarrier.calloc(1, stack);
                            VkBufferMemoryBarrier bufferMemoryBarrier = bufferMemoryBarriers.get(0);
                            bufferMemoryBarrier.sType$Default();
                            bufferMemoryBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                            bufferMemoryBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                            bufferMemoryBarrier.buffer(dstBufferId);
                            bufferMemoryBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                            bufferMemoryBarrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                            bufferMemoryBarrier.size(VK_WHOLE_SIZE);

                            vkCmdPipelineBarrier(commandBuffer.handle,
                                                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                 0,
                                                 null,
                                                 bufferMemoryBarriers,
                                                 null);
                        }

                        VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
                        copyRegion.size(size);
                        copyRegion.srcOffset(srcOffset);
                        copyRegion.dstOffset(dstOffset);

                        vkCmdCopyBuffer(commandBuffer.handle, stagingBuffer.getId(), dstBufferId, copyRegion);
                    }
                }
            }
        }
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBuffer gpuBuffer, boolean readable, boolean writable) {
        return this.mapBuffer(gpuBuffer.slice(), readable, writable);
    }

    @Override
    public GpuBuffer.MappedView mapBuffer(GpuBufferSlice gpuBufferSlice, boolean readable, boolean writable) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            VkGpuBuffer gpuBuffer = (VkGpuBuffer)(gpuBufferSlice.buffer());
            if (gpuBuffer.closed) {
                throw new IllegalStateException("Buffer already closed");
            } else if (!readable && !writable) {
                throw new IllegalArgumentException("At least read or write must be true");
            } else if (readable && (gpuBuffer.usage() & 1) == 0) {
                throw new IllegalStateException("Buffer is not readable");
            } else if (writable && (gpuBuffer.usage() & 2) == 0) {
                throw new IllegalStateException("Buffer is not writable");
            } else if (gpuBufferSlice.offset() + gpuBufferSlice.length() > gpuBuffer.size()) {
                throw new IllegalArgumentException(
                        "Cannot map more data than this buffer can hold (attempting to map "
                        + gpuBufferSlice.length()
                        + " bytes at offset "
                        + gpuBufferSlice.offset()
                        + " from "
                        + gpuBuffer.size()
                        + " size buffer)"
                );
            } else {
                int i = 0;
                if (readable) {
                    i |= 1;
                }

                if (writable) {
                    i |= 34;
                }

                ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(gpuBuffer.getBuffer().getDataPtr() + gpuBufferSlice.offset(), (int) gpuBufferSlice.length());
                return new VkGpuBuffer.MappedView(0, byteBuffer);
            }
        }
    }

    public void copyToBuffer(GpuBufferSlice gpuBufferSlice, GpuBufferSlice gpuBufferSlice2) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();
            if (vkGpuBuffer.closed) {
                throw new IllegalStateException("Source buffer already closed");
            } else if ((vkGpuBuffer.usage() & 8) == 0) {
                throw new IllegalStateException("Source buffer needs USAGE_COPY_DST to be a destination for a copy");
            } else {
                VkGpuBuffer vkGpuBuffer2 = (VkGpuBuffer) gpuBufferSlice2.buffer();
                if (vkGpuBuffer2.closed) {
                    throw new IllegalStateException("Target buffer already closed");
                } else if ((vkGpuBuffer2.usage() & 8) == 0) {
                    throw new IllegalStateException("Target buffer needs USAGE_COPY_DST to be a destination for a copy");
                } else if (gpuBufferSlice.length() != gpuBufferSlice2.length()) {
                    long var6 = gpuBufferSlice.length();
                    throw new IllegalArgumentException("Cannot copy from slice of size " + var6 + " to slice of size " + gpuBufferSlice2.length() + ", they must be equal");
                } else if (gpuBufferSlice.offset() + gpuBufferSlice.length() > vkGpuBuffer.size()) {
                    long var5 = gpuBufferSlice.length();
                    throw new IllegalArgumentException("Cannot copy more data than the source buffer holds (attempting to copy " + var5 + " bytes at offset " + gpuBufferSlice.offset() + " from " + vkGpuBuffer.size() + " size buffer)");
                } else if (gpuBufferSlice2.offset() + gpuBufferSlice2.length() > vkGpuBuffer2.size()) {
                    long var10002 = gpuBufferSlice2.length();
                    throw new IllegalArgumentException("Cannot copy more data than the target buffer can hold (attempting to copy " + var10002 + " bytes at offset " + gpuBufferSlice2.offset() + " to " + vkGpuBuffer2.size() + " size buffer)");
                } else {
//                    this.device.directStateAccess().copyBufferSubData(vkGpuBuffer.handle, vkGpuBuffer2.handle, gpuBufferSlice.offset(), gpuBufferSlice2.offset(), gpuBufferSlice.length());
//                    vkGpuBuffer.buffer.copyBuffer(byteBuffer, byteBuffer.remaining(), gpuBufferSlice.offset());

                    // TODO
                    throw new UnsupportedOperationException();
                }
            }
        }
    }

    @Override
    public void writeToTexture(GpuTexture gpuTexture, NativeImage nativeImage) {
        int i = gpuTexture.getWidth(0);
        int j = gpuTexture.getHeight(0);
        if (nativeImage.getWidth() != i || nativeImage.getHeight() != j) {
            throw new IllegalArgumentException(
                    "Cannot replace texture of size " + i + "x" + j + " with image of size " + nativeImage.getWidth() + "x" + nativeImage.getHeight()
            );
        } else if (gpuTexture.isClosed()) {
            throw new IllegalStateException("Destination texture is closed");
        } else {
            this.writeToTexture(gpuTexture, nativeImage, 0, 0, 0, 0, i, j, 0, 0);
        }
    }

    @Override
    public void writeToTexture(GpuTexture gpuTexture, NativeImage nativeImage, int level, int arrayLayer, int xOffset, int yOffset, int width, int height, int unpackSkipPixels, int unpackSkipRows) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (level >= 0 && level < gpuTexture.getMipLevels()) {
            if (unpackSkipPixels + width > nativeImage.getWidth() || unpackSkipRows + height > nativeImage.getHeight()) {
                throw new IllegalArgumentException(
                        "Copy source ("
                        + nativeImage.getWidth()
                        + "x"
                        + nativeImage.getHeight()
                        + ") is not large enough to read a rectangle of "
                        + width
                        + "x"
                        + height
                        + " from "
                        + unpackSkipPixels
                        + "x"
                        + unpackSkipRows
                );
            } else if (xOffset + width > gpuTexture.getWidth(level) || yOffset + height > gpuTexture.getHeight(level)) {
                throw new IllegalArgumentException(
                        "Dest texture (" + width + "x" + height + ") is not large enough to write a rectangle of " + width + "x" + height + " at " + xOffset + "x" + yOffset + " (at mip level " + level + ")"
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Destination texture is closed");
            } else {
                endOffscreenPassIfOpen();
                VTextureSelector.setActiveTexture(0);
                var glTexture = VkGlTexture.getTexture(((GlTexture) gpuTexture).glId());
//                VTextureSelector.bindTexture(((VkGpuTexture) gpuTexture).getVulkanImage());
                VTextureSelector.bindTexture(glTexture.getVulkanImage());
                VTextureSelector.uploadSubTexture(level, arrayLayer, width, height, xOffset, yOffset, unpackSkipRows, unpackSkipPixels, nativeImage.getWidth(), nativeImage.getPointer());
            }
        } else {
            throw new IllegalArgumentException("Invalid mipLevel " + level + ", must be >= 0 and < " + gpuTexture.getMipLevels());
        }
    }

    @Override
    public void writeToTexture(GpuTexture gpuTexture, ByteBuffer byteBuffer, NativeImage.Format format, int level, int j, int xOffset, int yOffset, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (level >= 0 && level < gpuTexture.getMipLevels()) {
            if (width * height * format.components() > byteBuffer.remaining()) {
                throw new IllegalArgumentException(
                        "Copy would overrun the source buffer (remaining length of " + byteBuffer.remaining() + ", but copy is " + width + "x" + height + " of format " + format + ")"
                );
            } else if (xOffset + width > gpuTexture.getWidth(level) || yOffset + height > gpuTexture.getHeight(level)) {
                throw new IllegalArgumentException(
                        "Dest texture ("
                        + gpuTexture.getWidth(level)
                        + "x"
                        + gpuTexture.getHeight(level)
                        + ") is not large enough to write a rectangle of "
                        + width
                        + "x"
                        + height
                        + " at "
                        + xOffset
                        + "x"
                        + yOffset
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Destination texture is closed");
            } else if ((gpuTexture.usage() & 1) == 0) {
                throw new IllegalStateException("Color texture must have USAGE_COPY_DST to be a destination for a write");
            } else if (j >= gpuTexture.getDepthOrLayers()) {
                throw new UnsupportedOperationException("Depth or layer is out of range, must be >= 0 and < " + gpuTexture.getDepthOrLayers());
            }
            else {
                endOffscreenPassIfOpen();
                GlStateManager._bindTexture(((VkGpuTexture)gpuTexture).id);

                GlStateManager._pixelStore(3314, width);
                GlStateManager._pixelStore(3316, 0);
                GlStateManager._pixelStore(3315, 0);
                GlStateManager._pixelStore(3317, format.components());
                GlStateManager._texSubImage2D(3553, level, xOffset, yOffset, width, height, GlConst.toGl(format), 5121, byteBuffer);
            }
        } else {
            throw new IllegalArgumentException("Invalid mipLevel, must be >= 0 and < " + gpuTexture.getMipLevels());
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long i, Runnable runnable, int j) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            this.copyTextureToBuffer(gpuTexture, gpuBuffer, i, runnable, j, 0, 0, gpuTexture.getWidth(j), gpuTexture.getHeight(j));
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long dstOffset, Runnable runnable, int mipLevel, int xOffset, int yOffset, int width, int height) {
        VkGpuBuffer vkGpuBuffer = (VkGpuBuffer) gpuBuffer;
        VkGpuTexture vkGpuTexture = (VkGpuTexture) gpuTexture;

        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (mipLevel >= 0 && mipLevel < gpuTexture.getMipLevels()) {
            if (gpuTexture.getWidth(mipLevel) * gpuTexture.getHeight(mipLevel) * vkGpuTexture.getVulkanImage().formatSize + dstOffset > gpuBuffer.size()) {
                throw new IllegalArgumentException(
                        "Buffer of size "
                        + gpuBuffer.size()
                        + " is not large enough to hold "
                        + width
                        + "x"
                        + height
                        + " pixels ("
                        + vkGpuTexture.getVulkanImage().formatSize
                        + " bytes each) starting from offset "
                        + dstOffset
                );
            }
            else if (xOffset + width > gpuTexture.getWidth(mipLevel) || yOffset + height > gpuTexture.getHeight(mipLevel)) {
                throw new IllegalArgumentException(
                        "Copy source texture ("
                        + gpuTexture.getWidth(mipLevel)
                        + "x"
                        + gpuTexture.getHeight(mipLevel)
                        + ") is not large enough to read a rectangle of "
                        + width
                        + "x"
                        + height
                        + " from "
                        + xOffset
                        + ","
                        + yOffset
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Source texture is closed");
            } else if (gpuBuffer.isClosed()) {
                throw new IllegalStateException("Destination buffer is closed");
            } else {
                endOffscreenPassIfOpen();
                // The copy must be submitted before the buffer is read: record it on a one-off command
                // buffer, submit and wait, then run the callback (the cherry-picked version recorded the
                // copy on a command buffer that was never submitted, so the read saw stale data)
                ImageUtil.copyImageToBuffer(vkGpuBuffer.getBuffer(), vkGpuTexture.getVulkanImage(), mipLevel,
                                            width, height, xOffset, yOffset, dstOffset, width, height);

                runnable.run();
            }
        } else {
            throw new IllegalArgumentException("Invalid mipLevel " + mipLevel + ", must be >= 0 and < " + gpuTexture.getMipLevels());
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture gpuTexture, GpuTexture gpuTexture2, int mipLevel, int j, int k, int l, int m, int n, int o) {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else if (mipLevel >= 0 && mipLevel < gpuTexture.getMipLevels() && mipLevel < gpuTexture2.getMipLevels()) {
            if (j + n > gpuTexture2.getWidth(mipLevel) || k + o > gpuTexture2.getHeight(mipLevel)) {
                throw new IllegalArgumentException(
                        "Dest texture ("
                        + gpuTexture2.getWidth(mipLevel)
                        + "x"
                        + gpuTexture2.getHeight(mipLevel)
                        + ") is not large enough to write a rectangle of "
                        + n
                        + "x"
                        + o
                        + " at "
                        + j
                        + "x"
                        + k
                );
            } else if (l + n > gpuTexture.getWidth(mipLevel) || m + o > gpuTexture.getHeight(mipLevel)) {
                throw new IllegalArgumentException(
                        "Source texture ("
                        + gpuTexture.getWidth(mipLevel)
                        + "x"
                        + gpuTexture.getHeight(mipLevel)
                        + ") is not large enough to read a rectangle of "
                        + n
                        + "x"
                        + o
                        + " at "
                        + l
                        + "x"
                        + m
                );
            } else if (gpuTexture.isClosed()) {
                throw new IllegalStateException("Source texture is closed");
            } else if (gpuTexture2.isClosed()) {
                throw new IllegalStateException("Destination texture is closed");
            } else {
                var srcImage = ((VkGpuTexture) gpuTexture).getVulkanImage();
                var dstImage = ((VkGpuTexture) gpuTexture2).getVulkanImage();

                VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

                // Transfers cannot run inside a render pass; the next draw rebinds its target
                Renderer.getInstance().endRenderPass(commandBuffer);

                try (MemoryStack stack = stackPush()) {
                    if (srcImage.format == dstImage.format) {
                        ImageUtil.copyImageToImageCmd(stack, commandBuffer, srcImage, dstImage, mipLevel, l, m, j, k, n, o);
                    } else if (dev.voolkan.compat.FormatCaps.canBlit(srcImage.format, dstImage.format)) {
                        ImageUtil.blitImageToImageCmd(stack, commandBuffer, srcImage, dstImage, mipLevel, l, m, j, k, n, o, VK_FILTER_NEAREST);
                    } else {
                        ImageUtil.warnUnsupportedBlitOnce(srcImage.format, dstImage.format);
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("Invalid mipLevel " + mipLevel + ", must be >= 0 and < " + gpuTexture.getMipLevels() + " and < " + gpuTexture2.getMipLevels());
        }
    }

    @Override
    public GpuFence createFence() {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        } else {
            // Completes when the frame submission holding the commands recorded so far has finished on the GPU
            final long serial = Renderer.getInstance().nextSubmitSerial();

            return new GpuFence() {
                @Override
                public void close() {

                }

                @Override
                public boolean awaitCompletion(long timeoutNanos) {
                    return Renderer.getInstance().awaitSubmission(serial, timeoutNanos);
                }
            };
        }
    }

    @Override
    public GpuQuery timerQueryBegin() {
        return null;
    }

    @Override
    public void timerQueryEnd(GpuQuery gpuQuery) {

    }

    @Override
    public void presentTexture(GpuTextureView gpuTexture) {
        throw new UnsupportedOperationException();
    }

    protected <T> void executeDrawMultiple(
            VkRenderPass renderPass,
            Collection<RenderPass.Draw<T>> collection,
            @Nullable GpuBuffer indexBuffer,
            @Nullable VertexFormat.IndexType indexType,
            Collection<String> collection2,
            T object
    ) {
        boolean skipInitialDescriptorBind = false;
        if (MacPerfOpts.drawCoalescing() && !collection.isEmpty()) {
            RenderPass.Draw<T> first = collection.iterator().next();
            skipInitialDescriptorBind = first.uniformUploaderConsumer() != null;
        }
        if (this.trySetup(renderPass, !skipInitialDescriptorBind)) {
            if (indexType == null) {
                indexType = VertexFormat.IndexType.SHORT;
            }

            ExtendedRenderPipeline extPipeline = ExtendedRenderPipeline.of(renderPass.getPipeline());
            Pipeline pipeline = extPipeline.getPipeline();
            EGlProgram glProgram = extPipeline.getProgram();
            RenderPass.UniformUploader uniformUploader = (string, gpuBufferSlice) -> {
                if (glProgram.getUniform(string) instanceof Uniform.Ubo ubo1) {
                    int blockBinding;
                    try {
                        blockBinding = ubo1.blockBinding();
                    } catch (Throwable var7) {
                        throw new MatchException(var7.toString(), var7);
                    }

                    VkGpuBuffer gpuBuffer1 = (VkGpuBuffer) gpuBufferSlice.buffer();

                    UBO ubo = pipeline.getUBO(blockBinding);
                    ubo.setUseGlobalBuffer(false);
                    ubo.getBufferSlice().set(gpuBuffer1.buffer, gpuBuffer1.getDynamicBaseOffset() + gpuBufferSlice.offset(), (int) gpuBufferSlice.length());
                }
            };

            for (RenderPass.Draw draw : collection) {
                VertexFormat.IndexType indexType2 = draw.indexType() == null ? indexType : draw.indexType();
                renderPass.setIndexBuffer(draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer(), indexType2);
                renderPass.setVertexBuffer(draw.slot(), draw.vertexBuffer());

                if (GlRenderPass.VALIDATION) {
                    if (renderPass.indexBuffer == null) {
                        throw new IllegalStateException("Missing index buffer");
                    }

                    if (renderPass.indexBuffer.isClosed()) {
                        throw new IllegalStateException("Index buffer has been closed!");
                    }

                    if (renderPass.vertexBuffers[0] == null) {
                        throw new IllegalStateException("Missing vertex buffer at slot 0");
                    }

                    if (renderPass.vertexBuffers[0].isClosed()) {
                        throw new IllegalStateException("Vertex buffer at slot 0 has been closed!");
                    }
                }

                BiConsumer<T, RenderPass.UniformUploader> biConsumer = draw.uniformUploaderConsumer();
                if (biConsumer != null) {
                    biConsumer.accept(object, uniformUploader);

                    Renderer.getInstance().uploadAndBindUBOs(pipeline);
                }

                this.drawFromBuffers(renderPass, 0, draw.firstIndex(), draw.indexCount(), indexType2, renderPass.pipeline, 1);
            }
        }
    }

    protected void executeDraw(VkRenderPass renderPass, int vertexOffset, int firstIndex, int vertexCount, @Nullable VertexFormat.IndexType indexType, int instanceCount) {
        if (this.trySetup(renderPass)) {
            if (GlRenderPass.VALIDATION) {
                if (indexType != null) {
                    if (renderPass.indexBuffer == null) {
                        throw new IllegalStateException("Missing index buffer");
                    }

                    if (renderPass.indexBuffer.isClosed()) {
                        throw new IllegalStateException("Index buffer has been closed!");
                    }
                }

                if (renderPass.pipeline.getVertexFormat() != DefaultVertexFormat.EMPTY) {
                    if (renderPass.vertexBuffers[0] == null) {
                        throw new IllegalStateException("Missing vertex buffer at slot 0");
                    }

                    if (renderPass.vertexBuffers[0].isClosed()) {
                        throw new IllegalStateException("Vertex buffer at slot 0 has been closed!");
                    }
                }
            }

            this.drawFromBuffers(renderPass, vertexOffset, firstIndex, vertexCount, indexType, renderPass.pipeline, instanceCount);
        }
    }

    public void drawFromBuffers(VkRenderPass renderPass, int vertexOffset, int firstIndex, int vertexCount,
                                @Nullable VertexFormat.IndexType indexType, RenderPipeline renderPipeline, int instanceCount)
    {
        if (instanceCount < 1) {
            instanceCount = 1;
        }
        if (vertexOffset < 0) {
            vertexOffset = 0;
        }

        Renderer renderer = Renderer.getInstance();
        VkCommandBuffer vkCommandBuffer = Renderer.getCommandBuffer();
        VkGpuBuffer vertexBuffer = (VkGpuBuffer) renderPass.vertexBuffers[0];
        if (vertexBuffer != null) {
            renderer.bindVertexBuffer(vkCommandBuffer, vertexBuffer.buffer.getId(), vertexBuffer.offset);
        }

        if (renderPass.indexBuffer != null) {
            VkGpuBuffer indexBuffer = (VkGpuBuffer) renderPass.indexBuffer;

            int vkIndexType = switch (indexType) {
                case SHORT -> VK_INDEX_TYPE_UINT16;
                case INT -> VK_INDEX_TYPE_UINT32;
            };

            renderer.bindIndexBuffer(vkCommandBuffer, indexBuffer.buffer.getId(), indexBuffer.offset, vkIndexType);
            VK11.vkCmdDrawIndexed(vkCommandBuffer, vertexCount, instanceCount, firstIndex, vertexOffset, 0);
        }
        else {
            var autoIndexBuffer = Renderer.getDrawer().getAutoIndexBuffer(renderPipeline.getVertexFormatMode(), vertexCount);
            if (autoIndexBuffer != null) {
                int indexCount = autoIndexBuffer.getIndexCount(vertexCount);
                renderer.bindIndexBuffer(vkCommandBuffer, autoIndexBuffer.getIndexBuffer().getId(), 0, autoIndexBuffer.getIndexBuffer().indexType.value);
                VK11.vkCmdDrawIndexed(vkCommandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, 0);
            }
            else {
                VK11.vkCmdDraw(vkCommandBuffer, vertexCount, instanceCount, vertexOffset, 0);
            }
        }
    }

    public boolean trySetup(VkRenderPass renderPass) {
        return trySetup(renderPass, true);
    }

    public boolean trySetup(VkRenderPass renderPass, boolean bindDescriptors) {
        if (VkRenderPass.VALIDATION) {
            if (renderPass.pipeline == null) {
                throw new IllegalStateException("Can't draw without a render pipeline");
            }

            for (RenderPipeline.UniformDescription uniformDescription : renderPass.pipeline.getUniforms()) {
                Object object = renderPass.uniforms.get(uniformDescription.name());
                if (object == null && !GlProgram.BUILT_IN_UNIFORMS.contains(uniformDescription.name())) {
                    throw new IllegalStateException("Missing uniform " + uniformDescription.name() + " (should be " + uniformDescription.type() + ")");
                }
            }

        }

        applyPipelineState(renderPass.pipeline);
        setupUniforms(renderPass);

        if (renderPass.isScissorEnabled()) {
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(
                    renderPass.getScissorX(), renderPass.getScissorY(), renderPass.getScissorWidth(), renderPass.getScissorHeight()
            );
        } else {
            GlStateManager._disableScissorTest();
        }

        return bindPipeline(renderPass.pipeline, bindDescriptors);
    }

    public void setupUniforms(VkRenderPass renderPass) {
        Pipeline pipeline = ExtendedRenderPipeline.of(renderPass.pipeline).getPipeline();

        List<UBO> uboList = pipeline.getBuffers();
        for (int i = 0, n = uboList.size(); i < n; i++) {
            UBO ubo = uboList.get(i);
            GpuBufferSlice gpuBufferSlice = renderPass.uniforms.get(ubo.name);

            // In case uniform buffer is not set, fallback to global buffer
            if (gpuBufferSlice == null) {
                ubo.setUseGlobalBuffer(true);
                ubo.setUpdate(true);
                continue;
            }

            VkGpuBuffer gpuBuffer = (VkGpuBuffer) gpuBufferSlice.buffer();

            ubo.setUseGlobalBuffer(false);
            ubo.getBufferSlice().set(gpuBuffer.buffer, gpuBuffer.getDynamicBaseOffset() + gpuBufferSlice.offset(), (int) gpuBufferSlice.length());
        }

        List<ImageDescriptor> imageDescriptors = pipeline.getImageDescriptors();
        for (int i = 0, n = imageDescriptors.size(); i < n; i++) {
            ImageDescriptor imageDescriptor = imageDescriptors.get(i);
            var textureSampler = renderPass.samplers.get(imageDescriptor.name);
            if (textureSampler == null) {
                continue;
            }

            VkTextureView textureView = textureSampler.view();
            VkGpuTexture gpuTexture = textureView.texture();
            if (gpuTexture.isClosed()) {
                continue;
            }

            var vulkanImage = gpuTexture.getVulkanImage();
            vulkanImage.setSampler(textureSampler.sampler().getId());
            VTextureSelector.bindTexture(imageDescriptor.imageIdx, vulkanImage);
        }

    }

    public boolean bindPipeline(RenderPipeline renderPipeline) {
        return bindPipeline(renderPipeline, true);
    }

    public boolean bindPipeline(RenderPipeline renderPipeline, boolean bindDescriptors) {
        Pipeline pipeline = ExtendedRenderPipeline.of(renderPipeline).getPipeline();

        if (pipeline == null) {
            return false;
        }

        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline((GraphicsPipeline) pipeline);
//        VTextureSelector.bindShaderTextures(pipeline);

        if (bindDescriptors) {
            renderer.uploadAndBindUBOs(pipeline);
        }

        return true;
    }

    public void applyPipelineState(RenderPipeline renderPipeline) {
        if (this.lastPipeline != renderPipeline) {
            this.lastPipeline = renderPipeline;
            if (renderPipeline.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST) {
                GlStateManager._enableDepthTest();
                GlStateManager._depthFunc(GlConst.toGl(renderPipeline.getDepthTestFunction()));
            } else {
                GlStateManager._disableDepthTest();
            }

            if (renderPipeline.isCull()) {
                GlStateManager._enableCull();
            } else {
                GlStateManager._disableCull();
            }

            if (renderPipeline.getBlendFunction().isPresent()) {
                GlStateManager._enableBlend();
                BlendFunction blendFunction = renderPipeline.getBlendFunction().get();
                GlStateManager._blendFuncSeparate(
                        GlConst.toGl(blendFunction.sourceColor()),
                        GlConst.toGl(blendFunction.destColor()),
                        GlConst.toGl(blendFunction.sourceAlpha()),
                        GlConst.toGl(blendFunction.destAlpha())
                );
            } else {
                GlStateManager._disableBlend();
            }

            GlStateManager._polygonMode(1032, GlConst.toGl(renderPipeline.getPolygonMode()));
            GlStateManager._depthMask(renderPipeline.isWriteDepth());
            GlStateManager._colorMask(renderPipeline.isWriteColor(), renderPipeline.isWriteColor(), renderPipeline.isWriteColor(), renderPipeline.isWriteAlpha());
            if (renderPipeline.getDepthBiasConstant() == 0.0F && renderPipeline.getDepthBiasScaleFactor() == 0.0F) {
                GlStateManager._disablePolygonOffset();
            } else {
                GlStateManager._polygonOffset(renderPipeline.getDepthBiasScaleFactor(), renderPipeline.getDepthBiasConstant());
                GlStateManager._enablePolygonOffset();
            }

            switch (renderPipeline.getColorLogic()) {
                case NONE:
                    GlStateManager._disableColorLogicOp();
                    break;
                case OR_REVERSE:
                    GlStateManager._enableColorLogicOp();
                    GlStateManager._logicOp(5387);
            }

            VRenderSystem.setPrimitiveTopologyGL(GlConst.toGl(renderPipeline.getVertexFormatMode()));
        }
    }

    public void finishRenderPass(boolean forceEnd) {
        if (forceEnd) {
            Renderer.getInstance().endRenderPass();
        }

        this.lastPipeline = null;
        this.lastProgram = null;
        this.inRenderPass = false;
    }

    private static void endOffscreenPassIfOpen() {
        if (!MacPerfOpts.tbdrPassOpts() || !Renderer.isRecording()) {
            return;
        }
        Renderer renderer = Renderer.getInstance();
        Framebuffer bound = renderer.getBoundFramebuffer();
        if (bound != null && bound != renderer.getMainPass().getMainFramebuffer()) {
            renderer.endRenderPass();
        }
    }

    protected VkGpuDevice getDevice() {
        return this.device;
    }
}
