package net.vulkanmod.mixin.render.entity.model;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * precompute transformed axis normals once per ModelPart instead of normalizing every cube polygon.
 */

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.interfaces.ModelPartCubeMixed;
import net.vulkanmod.render.model.CubeModel;
import net.vulkanmod.render.vertex.format.I32_SNorm;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ModelPart.class)
public abstract class ModelPartM {
    @Shadow @Final private List<ModelPart.Cube> cubes;

    @Unique Vector3f normal = new Vector3f();

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void injCompile(PoseStack.Pose pose, VertexConsumer vertexConsumer, int light, int overlay, int color, CallbackInfo ci) {
        this.renderCubes(pose, vertexConsumer, light, overlay, color);
        ci.cancel();
    }

    @Unique
    public void renderCubes(PoseStack.Pose pose, VertexConsumer vertexConsumer, int light, int overlay, int color) {
        if (this.cubes.isEmpty()) {
            return;
        }

        Matrix4f matrix4f = pose.pose();
        Matrix3f matrix3f = pose.normal();

        ExtendedVertexBuilder vertexBuilder = ExtendedVertexBuilder.of(vertexConsumer);

        boolean useFastFormat = vertexBuilder != null && vertexBuilder.canUseFastVertex();

        if (useFastFormat) {
            color = ColorUtil.RGBA.fromArgb32(color);

            this.normal.set(matrix3f.m00, matrix3f.m01, matrix3f.m02).normalize();
            int pxPos = I32_SNorm.packNormal(normal.x(), normal.y(), normal.z());
            int pxNeg = I32_SNorm.packNormal(-normal.x(), -normal.y(), -normal.z());

            this.normal.set(matrix3f.m10, matrix3f.m11, matrix3f.m12).normalize();
            int pyPos = I32_SNorm.packNormal(normal.x(), normal.y(), normal.z());
            int pyNeg = I32_SNorm.packNormal(-normal.x(), -normal.y(), -normal.z());

            this.normal.set(matrix3f.m20, matrix3f.m21, matrix3f.m22).normalize();
            int pzPos = I32_SNorm.packNormal(normal.x(), normal.y(), normal.z());
            int pzNeg = I32_SNorm.packNormal(-normal.x(), -normal.y(), -normal.z());

            for (int c = 0, cn = this.cubes.size(); c < cn; c++) {
                ModelPartCubeMixed cubeMixed = (ModelPartCubeMixed) this.cubes.get(c);
                CubeModel cubeModel = cubeMixed.getCubeModel();

                CubeModel.Polygon[] polygons = cubeModel.getPolygons();

                cubeModel.transformVertices(matrix4f);

                for (CubeModel.Polygon polygon : polygons) {
                    Vector3f polyNormal = polygon.normal();
                    int packedNormal;
                    if (polyNormal.y == 0.0f && polyNormal.z == 0.0f) {
                        packedNormal = polyNormal.x > 0.0f ? pxPos : pxNeg;
                    } else if (polyNormal.x == 0.0f && polyNormal.z == 0.0f) {
                        packedNormal = polyNormal.y > 0.0f ? pyPos : pyNeg;
                    } else if (polyNormal.x == 0.0f && polyNormal.y == 0.0f) {
                        packedNormal = polyNormal.z > 0.0f ? pzPos : pzNeg;
                    } else {
                        matrix3f.transform(this.normal.set(polyNormal)).normalize();
                        packedNormal = I32_SNorm.packNormal(normal.x(), normal.y(), normal.z());
                    }

                    CubeModel.Vertex[] vertices = polygon.vertices();

                    for (CubeModel.Vertex vertex : vertices) {
                        Vector3f pos = vertex.pos();
                        vertexBuilder.vertex(pos.x(), pos.y(), pos.z(),
                                             color,
                                             vertex.u(), vertex.v(),
                                             overlay, light, packedNormal);
                    }
                }
            }
        }
        else {
            for (int c = 0, cn = this.cubes.size(); c < cn; c++) {
                ModelPartCubeMixed cubeMixed = (ModelPartCubeMixed) this.cubes.get(c);
                CubeModel cubeModel = cubeMixed.getCubeModel();

                CubeModel.Polygon[] polygons = cubeModel.getPolygons();

                cubeModel.transformVertices(matrix4f);

                for (CubeModel.Polygon polygon : polygons) {
                    matrix3f.transform(this.normal.set(polygon.normal()));
                    this.normal.normalize();

                    CubeModel.Vertex[] vertices = polygon.vertices();

                    for (CubeModel.Vertex vertex : vertices) {
                        Vector3f pos = vertex.pos();
                        vertexConsumer.addVertex(pos.x(), pos.y(), pos.z(),
                                                 color,
                                                 vertex.u(), vertex.v(),
                                                 overlay, light,
                                                 normal.x(), normal.y(), normal.z());
                    }
                }
            }
        }

    }
}
