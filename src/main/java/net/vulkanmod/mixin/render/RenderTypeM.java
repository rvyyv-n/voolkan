package net.vulkanmod.mixin.render;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * remove dead duplicate immediate vertex/index uploads and per-draw temporary allocations.
 */

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.interfaces.ExtendedRenderType;
import net.vulkanmod.render.engine.VkCommandEncoder;
import net.vulkanmod.render.engine.VkRenderPass;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mixin(RenderType.class)
public class RenderTypeM implements ExtendedRenderType {
    @Unique
    private static final Vector4f VOOLKAN_IDENTITY_COLOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    @Unique
    private static final Vector3f VOOLKAN_ZERO_OFFSET = new Vector3f();
    @Unique
    private static final Supplier<String> VOOLKAN_IMMEDIATE_LABEL = () -> "Immediate draw";

    @Unique
    TerrainRenderType terrainRenderType;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void inj(String string, RenderSetup renderSetup, CallbackInfo ci) {
        terrainRenderType = switch (string) {
            case "solid" -> TerrainRenderType.SOLID;
            case "cutout" -> TerrainRenderType.CUTOUT;
            case "translucent" -> TerrainRenderType.TRANSLUCENT;
            case "tripwire" -> TerrainRenderType.TRIPWIRE;
            default -> null;
        };
    }

    @Override
    public TerrainRenderType getTerrainRenderType() {
        return terrainRenderType;
    }

    @Shadow @Final private RenderSetup state;
    @Shadow @Final protected String name;

    @Overwrite
    public void draw(MeshData meshData) {
        Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
        final var renderSetupAccessor = (RenderSetupAccessor) (Object) this.state;
        Consumer<Matrix4fStack> consumer = renderSetupAccessor.layeringTransform().getModifier();
        if (consumer != null) {
            matrix4fStack.pushMatrix();
            consumer.accept(matrix4fStack);
        }

        GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
                                                    .writeTransform(RenderSystem.getModelViewMatrix(),
                                                                    VOOLKAN_IDENTITY_COLOR,
                                                                    VOOLKAN_ZERO_OFFSET,
                                                                    renderSetupAccessor.textureTransform().getMatrix());

        Map<String, RenderSetup.TextureAndSampler> map = this.state.getTextures();

        RenderTarget renderTarget = renderSetupAccessor.outputTarget().getRenderTarget();
        GpuTextureView gpuTextureView = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : renderTarget.getColorTextureView();
        GpuTextureView gpuTextureView2 = renderTarget.useDepth ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView()) : null;

        VkCommandEncoder commandEncoder = (VkCommandEncoder) RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass renderPass = commandEncoder.createRenderPass(VOOLKAN_IMMEDIATE_LABEL, gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty())) {
            renderPass.setPipeline(renderSetupAccessor.pipeline());
            ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
            if (scissorState.enabled()) {
                renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
            }

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", gpuBufferSlice);

            for (Map.Entry<String, RenderSetup.TextureAndSampler> entry : map.entrySet()) {
                renderPass.bindTexture(entry.getKey(), entry.getValue().textureView(), entry.getValue().sampler());
            }

            VRenderSystem.applyModelViewMatrix(RenderSystem.getModelViewMatrix());
            VRenderSystem.calculateMVP();

            commandEncoder.trySetup((VkRenderPass) renderPass);

            Renderer.getDrawer().draw(meshData.vertexBuffer(), meshData.indexBuffer(), meshData.drawState().mode(), meshData.drawState().format(), meshData.drawState().vertexCount());
        }

        if (meshData != null) {
            meshData.close();
        }

        if (consumer != null) {
            matrix4fStack.popMatrix();
        }

    }
}
