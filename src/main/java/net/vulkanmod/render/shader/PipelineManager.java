package net.vulkanmod.render.shader;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * early-Z shader wiring and pipeline compatibility management.
 */

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

import java.util.function.Function;

public abstract class PipelineManager {
    public static VertexFormat terrainVertexFormat;

    static GraphicsPipeline
            terrainShader, terrainShaderEarlyZ,
            terrainShaderRgss, terrainShaderEarlyZRgss,
            fastBlitPipeline, cloudsPipeline;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;

    public static void init() {
        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultTerrainShaderGetter();
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultTerrainShaderGetter() {
        setShaderGetter(renderType -> {
            boolean useRgss = Minecraft.getInstance().options.textureFiltering().get() == TextureFilteringMethod.RGSS;
            boolean earlyZ = !Initializer.CONFIG.uniqueOpaqueLayer && renderType == TerrainRenderType.SOLID;
            if (useRgss) {
                return earlyZ ? terrainShaderEarlyZRgss : terrainShaderRgss;
            }
            return earlyZ ? terrainShaderEarlyZ : terrainShader;
        });
    }

    private static void createBasicPipelines() {
        terrainShader = createPipeline("terrain", "basic", PipelineConfigs.TERRAIN, CustomVertexFormat.COMPRESSED_TERRAIN, null);
        terrainShaderEarlyZ = createPipeline("terrain_earlyZ", "basic", PipelineConfigs.TERRAIN_EARLY_Z_CONFIG, CustomVertexFormat.COMPRESSED_TERRAIN, null);
        terrainShaderRgss = createPipeline("terrain_rgss", "basic", PipelineConfigs.TERRAIN, CustomVertexFormat.COMPRESSED_TERRAIN, "#define VOOLKAN_RGSS");
        terrainShaderEarlyZRgss = createPipeline("terrain_earlyZ_rgss", "basic", PipelineConfigs.TERRAIN_EARLY_Z_CONFIG, CustomVertexFormat.COMPRESSED_TERRAIN, "#define VOOLKAN_RGSS");
        fastBlitPipeline = createPipeline("blit", "basic/blit", CustomVertexFormat.NONE);
        cloudsPipeline = createPipeline("clouds", "basic/clouds", DefaultVertexFormat.POSITION_COLOR);
    }

    private static GraphicsPipeline createPipeline(String configName, String shaderPath, PipelineConfig config, VertexFormat vertexFormat, String fragmentDefine) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        final String path = ShaderLoadUtil.resolveShaderPath(shaderPath);

        pipelineBuilder.applyConfig(config);

        pipelineBuilder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, ShaderLoadUtil.loadShader(path, "%s.vsh".formatted(config.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER))));
        String fragSrc = ShaderLoadUtil.loadShader(path, "%s.fsh".formatted(config.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER)));
        if (fragmentDefine != null) {
            fragSrc = injectShaderDefine(fragSrc, fragmentDefine);
        }
        pipelineBuilder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragSrc);

        var pipeline = pipelineBuilder.createGraphicsPipeline();

        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }

    private static String injectShaderDefine(String src, String defineLine) {
        int nl = src.indexOf('\n');
        if (nl >= 0) {
            return src.substring(0, nl + 1) + defineLine + "\n" + src.substring(nl + 1);
        }
        return defineLine + "\n" + src;
    }

    private static GraphicsPipeline createPipeline(String configName, String shaderPath, VertexFormat vertexFormat) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        final String path = ShaderLoadUtil.resolveShaderPath(shaderPath);
        JsonObject config = ShaderLoadUtil.getJsonConfig(path, configName);
        var pipelineConfig = PipelineConfig.fromJson(configName, config);
        pipelineBuilder.applyConfig(pipelineConfig);

        pipelineBuilder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, ShaderLoadUtil.loadShader(path, "%s.vsh".formatted(pipelineConfig.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER))));
        pipelineBuilder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, ShaderLoadUtil.loadShader(path, "%s.fsh".formatted(pipelineConfig.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER))));

        var pipeline = pipelineBuilder.createGraphicsPipeline();

        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

    public static void setTerrainVertexFormat(VertexFormat format) {
        terrainVertexFormat = format;
    }

    public static VertexFormat getTerrainVertexFormat() {
        return terrainVertexFormat;
    }

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getTerrainRgssShader() {
        return terrainShaderRgss;
    }

    public static GraphicsPipeline getTerrainEarlyZRgssShader() {
        return terrainShaderEarlyZRgss;
    }

    public static GraphicsPipeline getFastBlitPipeline() {
        return fastBlitPipeline;
    }

    public static GraphicsPipeline getCloudsPipeline() {
        return cloudsPipeline;
    }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        terrainShaderEarlyZRgss.cleanUp();
        terrainShaderRgss.cleanUp();
        fastBlitPipeline.cleanUp();
        cloudsPipeline.cleanUp();
    }
}
