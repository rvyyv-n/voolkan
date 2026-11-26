package net.vulkanmod.mixin.render.entity;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * check frustum first, avoid Vec3 allocation and negative-coordinate truncation, and exempt large AABBs from single-point culling.
 */

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public class EntityRendererM<T extends Entity> {

    @Redirect(method = "shouldRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean isVisible(Frustum frustum, AABB aABB) {
        if (!frustum.isVisible(aABB)) {
            return false;
        }

        if (Initializer.CONFIG.entityCulling
                && (aABB.maxX - aABB.minX) <= 3.0
                && (aABB.maxY - aABB.minY) <= 4.0
                && (aABB.maxZ - aABB.minZ) <= 3.0) {
            WorldRenderer worldRenderer = WorldRenderer.getInstance();

            int cx = Mth.floor((aABB.minX + aABB.maxX) * 0.5);
            int cy = Mth.floor((aABB.minY + aABB.maxY) * 0.5);
            int cz = Mth.floor((aABB.minZ + aABB.maxZ) * 0.5);

            RenderSection section = worldRenderer.getSectionGrid().getSectionAtBlockPos(cx, cy, cz);
            if (section == null) {
                return true;
            }

            return worldRenderer.getLastFrame() == section.getLastFrame();
        }

        return true;
    }
}
