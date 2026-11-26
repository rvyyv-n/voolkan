package net.vulkanmod.mixin.render.particle;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * compute particle quad corners from two rotated basis vectors instead of four quaternion rotations.
 */

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.state.QuadParticleRenderState;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(QuadParticleRenderState.class)
public class QuadParticleRenderStateM {

    @Overwrite
    public void renderRotatedQuad(
            VertexConsumer vertexConsumer, float x, float y, float z, float xr, float yr, float zr, float wr, float m, float u0, float u1, float v0, float v1, int color, int light
    ) {
        ExtendedVertexBuilder vertexBuilder = (ExtendedVertexBuilder) vertexConsumer;
        color = ColorUtil.BGRAtoRGBA(color);

        float xx = xr * xr;
        float yy = yr * yr;
        float zz = zr * zr;
        float ww = wr * wr;
        float xy2 = 2.0F * xr * yr;
        float xz2 = 2.0F * xr * zr;
        float yz2 = 2.0F * yr * zr;
        float wx2 = 2.0F * wr * xr;
        float wy2 = 2.0F * wr * yr;
        float wz2 = 2.0F * wr * zr;

        float rx = (ww + xx - yy - zz) * m;
        float ry = (xy2 + wz2) * m;
        float rz = (xz2 - wy2) * m;

        float ux = (xy2 - wz2) * m;
        float uy = (ww - xx + yy - zz) * m;
        float uz = (yz2 + wx2) * m;

        vertexBuilder.vertex(x + rx - ux, y + ry - uy, z + rz - uz, u1, v1, color, light);
        vertexBuilder.vertex(x + rx + ux, y + ry + uy, z + rz + uz, u1, v0, color, light);
        vertexBuilder.vertex(x - rx + ux, y - ry + uy, z - rz + uz, u0, v0, color, light);
        vertexBuilder.vertex(x - rx - ux, y - ry - uy, z - rz - uz, u0, v1, color, light);
    }
}
