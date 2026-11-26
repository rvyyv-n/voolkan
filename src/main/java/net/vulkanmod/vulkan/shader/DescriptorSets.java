package net.vulkanmod.vulkan.shader;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * cache descriptor sets within each frame and elide redundant descriptor binds and stack pushes;
 * persist per-frame-slot descriptor caches across frames on macOS/MoltenVK with global resource-destruction epoch invalidation.
 */

import dev.voolkan.compat.MacPerfOpts;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.BufferSlice;
import net.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;

public class DescriptorSets {
    private static final VkDevice DEVICE = Vulkan.getVkDevice();
    private static long resourceEpoch = 1L;

    /**
     * Bumps the global descriptor-cache invalidation epoch whenever a VkBuffer, VkImageView, or
     * swapchain image view is destroyed so persistent caches never alias recycled native handles.
     */
    public static void bumpResourceEpoch() {
        resourceEpoch++;
    }

    private final Pipeline pipeline;
    private final int uboCount;
    private final int texCount;
    private int poolSize = 32;
    private long descriptorPool = VK_NULL_HANDLE;
    private long[] sets;
    private long currentSet;
    private int currentIdx = -1;
    private int usedCount = 0;
    private long cachedEpoch = 0L;
    private long activeSubmitSerial = -1L;
    private int usedThisSerial = 0;

    private final long[] boundUBs;
    private final ImageDescriptor.State[] boundTextures;
    private final IntBuffer dynamicOffsets;

    private final long[] scratchUboIds;
    private final long[] scratchViews;
    private final long[] scratchSamplers;
    private long[] slotUboIds;
    private long[] slotTexIds;
    private final Long2IntOpenHashMap frameSetCache = new Long2IntOpenHashMap(32);

    DescriptorSets(Pipeline pipeline) {
        this.pipeline = pipeline;
        this.uboCount = pipeline.buffers.size();
        this.texCount = pipeline.imageDescriptors.size();
        this.boundTextures = new ImageDescriptor.State[this.texCount];
        this.dynamicOffsets = MemoryUtil.memAllocInt(this.uboCount);
        this.boundUBs = new long[this.uboCount];

        this.scratchUboIds = new long[this.uboCount];
        this.scratchViews = new long[this.texCount];
        this.scratchSamplers = new long[this.texCount];
        this.slotUboIds = new long[this.poolSize * this.uboCount];
        this.slotTexIds = new long[this.poolSize * this.texCount * 2];
        this.frameSetCache.defaultReturnValue(-1);

        Arrays.setAll(boundTextures, i -> new ImageDescriptor.State(0, 0));

        try (MemoryStack stack = stackPush()) {
            this.createDescriptorPool(stack);
            this.createDescriptorSets(stack);
        }
    }

    public void bindSets(VkCommandBuffer commandBuffer, UniformBuffer uniformBuffer, int bindPoint) {
        this.updateUniforms(uniformBuffer);
        this.resolveDescriptorSet(uniformBuffer);

        Renderer.getInstance().bindDescriptorSet(
                commandBuffer, bindPoint, pipeline.pipelineLayout,
                this.currentSet, this.dynamicOffsets, this.uboCount);
    }

    private void updateUniforms(UniformBuffer globalUB) {
        int totalGlobalBytes = 0;
        for (int i = 0; i < this.uboCount; ++i) {
            UBO ubo = pipeline.buffers.get(i);
            // Prevent NPE in case UBO has no bound buffer slice
            if (ubo.getBufferSlice().getBuffer() == null) {
                ubo.setUseGlobalBuffer(true);
                ubo.setUpdate(true);
            }

            if (ubo.useGlobalBuffer()) {
                totalGlobalBytes += UniformBuffer.getAlignedSize(ubo.getSize());
            }
        }

        if (totalGlobalBytes > 0) {
            globalUB.checkCapacity(totalGlobalBytes);
        }

        for (int i = 0; i < this.uboCount; ++i) {
            UBO ubo = pipeline.buffers.get(i);
            boolean useOwnUB = !ubo.useGlobalBuffer();

            int offset;
            if (useOwnUB) {
                BufferSlice bufferSlice = ubo.getBufferSlice();
                offset = (int) bufferSlice.getOffset();
            }
            else {
                offset = (int) globalUB.getUsedBytes();
                int alignedSize = UniformBuffer.getAlignedSize(ubo.getSize());

                if (ubo.shouldUpdate()) {
                    ubo.update(globalUB.getPointer());
                }
                globalUB.updateOffset(alignedSize);

                BufferSlice bufferSlice = ubo.getBufferSlice();
                bufferSlice.set(globalUB, offset, alignedSize);
            }

            this.dynamicOffsets.put(i, offset);
        }
    }

    private boolean matchesCurrent() {
        for (int j = 0; j < this.texCount; ++j) {
            if (!this.boundTextures[j].isCurrentState(this.scratchViews[j], this.scratchSamplers[j])) {
                return false;
            }
        }
        for (int j = 0; j < this.uboCount; ++j) {
            if (this.boundUBs[j] != this.scratchUboIds[j]) {
                return false;
            }
        }
        return true;
    }

    private boolean slotMatches(int slot) {
        int uBase = slot * this.uboCount;
        for (int j = 0; j < this.uboCount; ++j) {
            if (this.slotUboIds[uBase + j] != this.scratchUboIds[j]) {
                return false;
            }
        }
        int tBase = slot * this.texCount * 2;
        for (int j = 0; j < this.texCount; ++j) {
            if (this.slotTexIds[tBase + j * 2] != this.scratchViews[j]
                    || this.slotTexIds[tBase + j * 2 + 1] != this.scratchSamplers[j]) {
                return false;
            }
        }
        return true;
    }

    private void syncBoundState(int slot) {
        this.currentIdx = slot;
        this.currentSet = this.sets[slot];
        System.arraycopy(this.scratchUboIds, 0, this.boundUBs, 0, this.uboCount);
        for (int j = 0; j < this.texCount; ++j) {
            this.boundTextures[j].set(this.scratchViews[j], this.scratchSamplers[j]);
        }
    }

    private long computeKeyHash() {
        long h = 0x9E3779B97F4A7C15L;
        for (int j = 0; j < this.uboCount; ++j) {
            h = mix64(h ^ this.scratchUboIds[j]);
        }
        for (int j = 0; j < this.texCount; ++j) {
            h = mix64(h ^ this.scratchViews[j]);
            h = mix64(h ^ this.scratchSamplers[j]);
        }
        return h;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private void resolveDescriptorSet(UniformBuffer uniformBuffer) {
        final boolean persist = MacPerfOpts.persistentDescriptorCache();
        if (persist) {
            long currentSerial = Renderer.getInstance().nextSubmitSerial();
            if (currentSerial != this.activeSubmitSerial) {
                this.activeSubmitSerial = currentSerial;
                this.usedThisSerial = 0;
                if (this.cachedEpoch != resourceEpoch) {
                    this.currentIdx = -1;
                    this.usedCount = 0;
                    this.frameSetCache.clear();
                    this.cachedEpoch = resourceEpoch;
                }
            } else if (this.cachedEpoch != resourceEpoch) {
                // A resource was destroyed mid-frame: drop cache lookups immediately without
                // overwriting any descriptor set already bound earlier in this command buffer.
                this.currentIdx = -1;
                this.frameSetCache.clear();
                if (this.usedThisSerial == 0) {
                    this.usedCount = 0;
                }
                this.cachedEpoch = resourceEpoch;
            }
        }

        for (int j = 0; j < this.texCount; ++j) {
            ImageDescriptor imageDescriptor = pipeline.imageDescriptors.get(j);
            VulkanImage image = imageDescriptor.getImage();

            if (image == null) {
                throw new NullPointerException(
                        "Pipeline %s: image descriptor %s (binding %d) has no image bound"
                                .formatted(pipeline.name, imageDescriptor.name, imageDescriptor.getBinding()));
            }

            if (imageDescriptor.isReadOnlyLayout) {
                image.readOnlyLayout();
            }

            this.scratchViews[j] = imageDescriptor.getImageView(image);
            this.scratchSamplers[j] = image.getSampler();
        }

        for (int j = 0; j < this.uboCount; ++j) {
            UBO ubo = pipeline.buffers.get(j);
            Buffer uniformBufferI = ubo.getBufferSlice().getBuffer();
            if (uniformBufferI == null) {
                uniformBufferI = uniformBuffer;
            }
            this.scratchUboIds[j] = uniformBufferI.getId();
        }

        if (this.currentIdx != -1 && matchesCurrent()) {
            if (persist) {
                this.usedThisSerial++;
            }
            return;
        }

        long hash = computeKeyHash();
        int cachedSlot = this.frameSetCache.get(hash);
        if (cachedSlot >= 0 && slotMatches(cachedSlot)) {
            syncBoundState(cachedSlot);
            if (persist) {
                this.usedThisSerial++;
            }
            return;
        }

        if (persist && this.usedCount >= this.poolSize && this.usedThisSerial == 0) {
            // Reclaim idle cross-frame entries in place once this frame slot's fence has completed.
            this.frameSetCache.clear();
            this.usedCount = 0;
            this.currentIdx = -1;
        }

        int slot = this.usedCount++;
        try (MemoryStack stack = stackPush()) {
            if (slot >= this.poolSize) {
                this.poolSize *= 2;
                this.createDescriptorPool(stack);
                this.createDescriptorSets(stack);
                this.frameSetCache.clear();
                this.slotUboIds = new long[this.poolSize * this.uboCount];
                this.slotTexIds = new long[this.poolSize * this.texCount * 2];
                slot = 0;
                this.usedCount = 1;
            }

            long targetSet = this.sets[slot];
            int totalWrites = this.uboCount + this.texCount;
            if (totalWrites > 0) {
                VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(totalWrites, stack);

                int i = 0;
                for (int j = 0; j < this.uboCount; ++j) {
                    UBO ubo = pipeline.buffers.get(j);
                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfo.buffer(this.scratchUboIds[j]);
                    bufferInfo.range(ubo.getSize());

                    VkWriteDescriptorSet descriptorWrite = descriptorWrites.get(i);
                    descriptorWrite.sType$Default();
                    descriptorWrite.dstBinding(ubo.getBinding());
                    descriptorWrite.dstArrayElement(0);
                    descriptorWrite.descriptorType(ubo.getType());
                    descriptorWrite.descriptorCount(1);
                    descriptorWrite.pBufferInfo(bufferInfo);
                    descriptorWrite.dstSet(targetSet);
                    ++i;
                }

                for (int j = 0; j < this.texCount; ++j) {
                    ImageDescriptor imageDescriptor = pipeline.imageDescriptors.get(j);
                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                    imageInfo.imageLayout(imageDescriptor.getLayout());
                    imageInfo.imageView(this.scratchViews[j]);
                    if (imageDescriptor.useSampler) {
                        imageInfo.sampler(this.scratchSamplers[j]);
                    }

                    VkWriteDescriptorSet descriptorWrite = descriptorWrites.get(i);
                    descriptorWrite.sType$Default();
                    descriptorWrite.dstBinding(imageDescriptor.getBinding());
                    descriptorWrite.dstArrayElement(0);
                    descriptorWrite.descriptorType(imageDescriptor.getType());
                    descriptorWrite.descriptorCount(1);
                    descriptorWrite.pImageInfo(imageInfo);
                    descriptorWrite.dstSet(targetSet);
                    ++i;
                }

                vkUpdateDescriptorSets(DEVICE, descriptorWrites, null);
            }
        }

        int uBase = slot * this.uboCount;
        System.arraycopy(this.scratchUboIds, 0, this.slotUboIds, uBase, this.uboCount);
        int tBase = slot * this.texCount * 2;
        for (int j = 0; j < this.texCount; ++j) {
            this.slotTexIds[tBase + j * 2] = this.scratchViews[j];
            this.slotTexIds[tBase + j * 2 + 1] = this.scratchSamplers[j];
        }
        this.frameSetCache.put(hash, slot);
        syncBoundState(slot);
        if (persist) {
            this.usedThisSerial++;
        }
    }

    private void createDescriptorSets(MemoryStack stack) {
        LongBuffer layouts = MemoryUtil.memAllocLong(this.poolSize);

        for (int i = 0; i < this.poolSize; ++i) {
            layouts.put(i, pipeline.descriptorSetLayout);
        }

        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
        allocInfo.sType$Default();
        allocInfo.descriptorPool(descriptorPool);
        allocInfo.pSetLayouts(layouts);

        // Not hotspot code, use heap array
        this.sets = new long[this.poolSize];

        int result = vkAllocateDescriptorSets(DEVICE, allocInfo, this.sets);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to allocate descriptor sets. Result:" + result);
        }

        MemoryUtil.memFree(layouts);
    }

    private void createDescriptorPool(MemoryStack stack) {
        int size = pipeline.buffers.size() + pipeline.imageDescriptors.size();

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(size, stack);

        int i = 0;
        for (var buffer : pipeline.getBuffers()) {
            VkDescriptorPoolSize uniformBufferPoolSize = poolSizes.get(i);
            uniformBufferPoolSize.type(buffer.getType());
            uniformBufferPoolSize.descriptorCount(this.poolSize);

            ++i;
        }

        for (var imageDescriptor : pipeline.getImageDescriptors()) {
            VkDescriptorPoolSize textureSamplerPoolSize = poolSizes.get(i);
            textureSamplerPoolSize.type(imageDescriptor.getType());
            textureSamplerPoolSize.descriptorCount(this.poolSize);

            ++i;
        }

        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
        poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
        poolInfo.pPoolSizes(poolSizes);
        poolInfo.maxSets(this.poolSize);

        LongBuffer pDescriptorPool = stack.mallocLong(1);

        if (vkCreateDescriptorPool(DEVICE, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create descriptor pool");
        }

        if (this.descriptorPool != VK_NULL_HANDLE) {
            final long oldDescriptorPool = this.descriptorPool;
            MemoryManager.getInstance().addFrameOp(() -> {
                vkDestroyDescriptorPool(DEVICE, oldDescriptorPool, null);
            });
        }

        this.descriptorPool = pDescriptorPool.get(0);
    }

    public void resetIdx() {
        this.currentIdx = -1;
        this.usedCount = 0;
        this.frameSetCache.clear();
    }

    public void cleanUp() {
        vkResetDescriptorPool(DEVICE, descriptorPool, 0);
        vkDestroyDescriptorPool(DEVICE, descriptorPool, null);

        MemoryUtil.memFree(this.dynamicOffsets);
    }

}