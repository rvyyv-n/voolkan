package net.vulkanmod.vulkan.shader;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * pipeline cache is loaded from and saved to disk.
 */

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.voolkan.cache.PipelineCacheStore;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public abstract class Pipeline {
    private static final VkDevice DEVICE = Vulkan.getVkDevice();
    protected static final long PIPELINE_CACHE = createPipelineCache();
    protected static final List<Pipeline> PIPELINES = new ArrayList<>();

    // Size of the blob loaded from disk (0 = none; no initializer: it runs after createPipelineCache); if the blob is still this size at exit, nothing new was compiled
    private static int loadedCacheSize;
    private static final int VK_HEADER_ONLY_SIZE = 16 + VK_UUID_SIZE;

    private static boolean isDiskCacheEnabled() {
        String property = System.getProperty("voolkan.pipelineCache");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }

        return Initializer.CONFIG.pipelineCache;
    }

    private static Path getDiskCachePath() {
        return FabricLoader.getInstance().getGameDir().resolve("voolkan").resolve("pipeline_cache.bin");
    }

    private static PipelineCacheStore.DeviceKey getDeviceKey() {
        VkPhysicalDeviceProperties properties = DeviceManager.deviceProperties;
        byte[] uuid = new byte[VK_UUID_SIZE];
        properties.pipelineCacheUUID().get(0, uuid);

        return new PipelineCacheStore.DeviceKey(properties.vendorID(), properties.deviceID(), uuid);
    }

    private static long createPipelineCache() {
        byte[] initialData = null;

        if (isDiskCacheEnabled()) {
            Path path = getDiskCachePath();
            initialData = PipelineCacheStore.load(path, getDeviceKey(),
                    reason -> Initializer.LOGGER.warn("Ignoring pipeline cache {}: {}", path, reason));

            if (initialData != null) {
                Initializer.LOGGER.info("Loaded pipeline cache: {} bytes", initialData.length);
            }
        }

        long cache = createPipelineCache(initialData);

        // The driver can still reject the data (VK_SUCCESS with an empty cache, or an error): fall back to empty
        if (cache == VK_NULL_HANDLE) {
            Initializer.LOGGER.warn("Driver rejected the saved pipeline cache, starting with an empty one");
            initialData = null;
            cache = createPipelineCache(null);
        }

        loadedCacheSize = initialData != null ? initialData.length : 0;

        return cache;
    }

    /** @return the cache handle, or VK_NULL_HANDLE if creation with initialData failed (never for null data). */
    private static long createPipelineCache(byte[] initialData) {
        ByteBuffer data = null;

        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.calloc(stack);
            cacheCreateInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            if (initialData != null) {
                data = MemoryUtil.memAlloc(initialData.length);
                data.put(initialData).flip();
                cacheCreateInfo.pInitialData(data);
            }

            LongBuffer pPipelineCache = stack.mallocLong(1);

            int result = vkCreatePipelineCache(DEVICE, cacheCreateInfo, null, pPipelineCache);
            if (result != VK_SUCCESS) {
                if (initialData != null) {
                    return VK_NULL_HANDLE;
                }

                throw new RuntimeException("Failed to create graphics pipeline");
            }

            return pPipelineCache.get(0);
        } finally {
            if (data != null) {
                MemoryUtil.memFree(data);
            }
        }
    }

    /** Writes the pipeline cache to disk. Must be called before the cache is destroyed. Never throws. */
    public static void savePipelineCache() {
        if (!isDiskCacheEnabled()) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            org.lwjgl.PointerBuffer pSize = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(DEVICE, PIPELINE_CACHE, pSize, null) != VK_SUCCESS) {
                return;
            }

            int size = (int) pSize.get(0);
            if (size <= VK_HEADER_ONLY_SIZE || size == loadedCacheSize) {
                // Header only (nothing compiled, or a driver without cache data), or the size we loaded: keep the file as is
                return;
            }

            ByteBuffer data = MemoryUtil.memAlloc(size);
            try {
                pSize.put(0, size);
                int result = vkGetPipelineCacheData(DEVICE, PIPELINE_CACHE, pSize, data);
                if (result != VK_SUCCESS) {
                    Initializer.LOGGER.warn("Could not read the pipeline cache from the driver (result {})", result);
                    return;
                }

                byte[] bytes = new byte[(int) pSize.get(0)];
                data.get(0, bytes);

                Path path = getDiskCachePath();
                if (PipelineCacheStore.save(path, bytes, error -> Initializer.LOGGER.warn("Pipeline cache {}: {}", path, error))) {
                    Initializer.LOGGER.info("Saved pipeline cache: {} bytes", bytes.length);
                }
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (RuntimeException e) {
            Initializer.LOGGER.warn("Could not save the pipeline cache", e);
        }
    }

    public static void destroyPipelineCache() {
        savePipelineCache();
        vkDestroyPipelineCache(DEVICE, PIPELINE_CACHE, null);
    }

    public static void recreateDescriptorSets(int frames) {
        PIPELINES.forEach(pipeline -> {
            pipeline.destroyDescriptorSets();
            pipeline.createDescriptorSets(frames);
        });
    }

    public final String name;

    protected long descriptorSetLayout;
    protected long pipelineLayout;

    protected DescriptorSets[] descriptorSets;
    protected List<UBO> buffers;
    protected List<ImageDescriptor> imageDescriptors;
    protected PushConstants pushConstants;

    public Pipeline(String name) {
        this.name = name;
    }

    protected void createDescriptorSetLayout() {
        try (MemoryStack stack = stackPush()) {
            int bindingsSize = this.buffers.size() + this.imageDescriptors.size();

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingsSize, stack);

            for (UBO ubo : this.buffers) {
                VkDescriptorSetLayoutBinding uboLayoutBinding = bindings.get(ubo.getBinding());
                uboLayoutBinding.binding(ubo.getBinding());
                uboLayoutBinding.descriptorCount(1);
                uboLayoutBinding.descriptorType(ubo.getType());
                uboLayoutBinding.pImmutableSamplers(null);
                uboLayoutBinding.stageFlags(ubo.getStages());
            }

            for (ImageDescriptor imageDescriptor : this.imageDescriptors) {
                VkDescriptorSetLayoutBinding samplerLayoutBinding = bindings.get(imageDescriptor.getBinding());
                samplerLayoutBinding.binding(imageDescriptor.getBinding());
                samplerLayoutBinding.descriptorCount(1);
                samplerLayoutBinding.descriptorType(imageDescriptor.getType());
                samplerLayoutBinding.pImmutableSamplers(null);
                samplerLayoutBinding.stageFlags(imageDescriptor.getStages());
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);

            if (vkCreateDescriptorSetLayout(DeviceManager.vkDevice, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor set layout");
            }

            this.descriptorSetLayout = pDescriptorSetLayout.get(0);
        }
    }

    protected void createPipelineLayout() {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            pipelineLayoutInfo.pSetLayouts(stack.longs(this.descriptorSetLayout));

            if (this.pushConstants != null) {
                VkPushConstantRange.Buffer pushConstantRange = VkPushConstantRange.calloc(1, stack);
                pushConstantRange.size(this.pushConstants.getSize());
                pushConstantRange.offset(0);
                pushConstantRange.stageFlags(this.pushConstants.stages);

                pipelineLayoutInfo.pPushConstantRanges(pushConstantRange);
            }

            LongBuffer pPipelineLayout = stack.longs(VK_NULL_HANDLE);

            if (vkCreatePipelineLayout(DEVICE, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline layout");
            }

            pipelineLayout = pPipelineLayout.get(0);
        }
    }

    protected void createDescriptorSets(int frames) {
        descriptorSets = new DescriptorSets[frames];
        for (int i = 0; i < frames; ++i) {
            descriptorSets[i] = new DescriptorSets(this);
        }
    }

    public void scheduleCleanUp() {
        MemoryManager.getInstance().addFrameOp(this::cleanUp);
    }

    public abstract void cleanUp();

    protected void destroyDescriptorSets() {
        for (DescriptorSets descriptorSets : this.descriptorSets) {
            descriptorSets.cleanUp();
        }

        this.descriptorSets = null;
    }

    public void resetDescriptorPool(int i) {
        if (this.descriptorSets != null)
            this.descriptorSets[i].resetIdx();

    }

    public PushConstants getPushConstants() {
        return this.pushConstants;
    }

    public long getLayout() {
        return pipelineLayout;
    }

    public List<UBO> getBuffers() {
        return buffers;
    }

    public UBO getUBO(int binding) {
        return getUBO(ubo -> ubo.binding == binding);
    }

    public UBO getUBO(String name) {
        return getUBO(ubo -> ubo.name.equals(name));
    }

    public UBO getUBO(Predicate<UBO> fn) {
        UBO ubo = null;
        for (UBO ubo1 : this.buffers) {
            if (fn.test(ubo1)) {
                ubo = ubo1;
            }
        }

        return ubo;
    }

    public ImageDescriptor getImageDescriptor(String name) {
        return getImageDescriptor(imageDescriptor -> imageDescriptor.name.equals(name));
    }

    public ImageDescriptor getImageDescriptor(Predicate<ImageDescriptor> fn) {
        ImageDescriptor descriptor = null;
        for (ImageDescriptor descriptor1 : this.imageDescriptors) {
            if (fn.test(descriptor1)) {
                descriptor = descriptor1;
            }
        }

        return descriptor;
    }

    public List<ImageDescriptor> getImageDescriptors() {
        return imageDescriptors;
    }

    public void bindDescriptorSets(VkCommandBuffer commandBuffer, int frame) {
        UniformBuffer uniformBuffer = Renderer.getDrawer().getUniformBuffer();
        this.descriptorSets[frame].bindSets(commandBuffer, uniformBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS);
    }

    public void bindDescriptorSets(VkCommandBuffer commandBuffer, UniformBuffer uniformBuffer, int frame) {
        this.descriptorSets[frame].bindSets(commandBuffer, uniformBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS);
    }

    protected static long createShaderModule(ByteBuffer spirvCode) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(spirvCode);

            LongBuffer pShaderModule = stack.mallocLong(1);

            if (vkCreateShaderModule(DEVICE, createInfo, null, pShaderModule) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create shader module");
            }

            return pShaderModule.get(0);
        }
    }

    public static Builder builder(VertexFormat vertexFormat, String path) {
        return new Builder(vertexFormat, path);
    }

    public static class Builder {
        public final VertexFormat vertexFormat;
        public final String name;
        List<UBO> UBOs = new ArrayList<>();
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        PushConstants pushConstants;
        int nextBinding;

        Map<ShaderKind, String> shadersSrc = new EnumMap<>(ShaderKind.class);

        RenderPass renderPass;

        Function<Uniform.Info, Supplier<MappedBuffer>> uniformSupplierGetter;

        public Builder(VertexFormat vertexFormat, String name) {
            this.vertexFormat = vertexFormat;
            this.name = name;
        }

        public Builder(VertexFormat vertexFormat) {
            this(vertexFormat, null);
        }

        public Builder(String name) {
            this(null, name);
        }

        public Builder() {
            this(null, null);
        }

        public void setUniforms(List<UBO> UBOs, List<ImageDescriptor> imageDescriptors) {
            this.UBOs = UBOs;
            this.imageDescriptors = imageDescriptors;
        }

        public void setShaderSrc(ShaderKind kind, String src) {
            this.shadersSrc.put(kind, src);
        }

        public void addUBO(UBO ubo) {
            this.UBOs.add(ubo);
        }

        public void addImageDescriptor(ImageDescriptor imageDescriptor) {
            this.imageDescriptors.add(imageDescriptor);
        }

        public Builder applyConfig(PipelineConfig config) {
            for (var ub : config.ubs) {
                parseUboNode(ub);
            }

            for (var imageDescriptor : config.imageDescriptors) {
                this.parseImageDescriptor(imageDescriptor);
            }

            if (config.pushConstantsInfo != null) {
                this.parsePushConstantNode(config.pushConstantsInfo);
            }

            return this;
        }

        public void setUniformSupplierGetter(Function<Uniform.Info, Supplier<MappedBuffer>> uniformSupplierGetter) {
            this.uniformSupplierGetter = uniformSupplierGetter;
        }

        private void parseUboNode(PipelineConfig.UB ub) {
            int binding = ub.binding;
            int stages = ub.stage;
            AlignedStruct.Builder builder = new AlignedStruct.Builder();

            UBO ubo;
            if (!ub.uniforms.isEmpty()) {
                for (var field : ub.uniforms) {
                    String name = field.name();
                    String type = field.type();

                    Uniform.Info uniformInfo = Uniform.createUniformInfo(type, name);
                    uniformInfo.setupSupplier();

                    if (!uniformInfo.hasSupplier()) {
                        if (this.uniformSupplierGetter != null) {
                            var uniformSupplier = this.uniformSupplierGetter.apply(uniformInfo);

                            if (uniformSupplier == null) {
                                throw new IllegalStateException("No uniform supplier found for uniform: (%s:%s)".formatted(type, name));
                            }

                            uniformInfo.setBufferSupplier(uniformSupplier);
                        }
                        else {
                            throw new IllegalStateException("No uniform supplier found for uniform: (%s:%s)".formatted(type, name));
                        }
                    }

                    builder.addUniform(uniformInfo);
                }

                ubo = builder.buildUBO(binding, stages);
            }
            else {
                int size = ub.size;

                if (size <= 0) {
                    throw new IllegalStateException("Manual UBO has size <= 0");
                }

                ubo = new UBO("UBO %d".formatted(binding), binding, stages, size, null);
                ubo.setUseGlobalBuffer(false);
            }

            this.UBOs.add(ubo);
        }

        private void parseImageDescriptor(PipelineConfig.ImageDescriptorInfo info) {
            int descriptorType = switch (info.type()) {
                case "sampler2D" -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                case "image2D" -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                default -> throw new IllegalStateException("Unexpected value: " + info.type());
            };

            this.imageDescriptors.add(new ImageDescriptor(info.binding(), info.type(), info.name(), info.imageIdx(), descriptorType));
        }

        private void parsePushConstantNode(PipelineConfig.UB ub) {
            AlignedStruct.Builder builder = new AlignedStruct.Builder();
            int stages = ub.stage;

            for (var field : ub.uniforms) {
                String name = field.name();
                String type = field.type();

                Uniform.Info uniformInfo = Uniform.createUniformInfo(type, name);
                builder.addUniform(uniformInfo);
            }

            this.pushConstants = builder.buildPushConstant(stages);
        }

        public List<UBO> getUBOs() {
            return UBOs;
        }

        public List<ImageDescriptor> getImageDescriptors() {
            return imageDescriptors;
        }

        public PushConstants getPushConstants() {
            return pushConstants;
        }

        public Map<ShaderKind, String> getShadersSrc() {
            return shadersSrc;
        }

        public GraphicsPipeline createGraphicsPipeline() {
            return new GraphicsPipeline(this);
        }
    }
}
