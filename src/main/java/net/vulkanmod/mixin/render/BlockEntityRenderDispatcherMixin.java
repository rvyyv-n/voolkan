/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin {

    @Unique
    private static final int MIN_DISTANCE = 16;
    @Unique
    private static final int MAX_DISTANCE = 128;
    @Unique
    private static final int VANILLA_DEFAULT_DISTANCE = 64;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
            method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;shouldRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/phys/Vec3;)Z"
            ),
            require = 1
    )
    private boolean voolkan$shouldRenderBlockEntity(BlockEntityRenderer renderer, BlockEntity blockEntity, Vec3 cameraPos) {
        if (renderer.shouldRenderOffScreen()) {
            return renderer.shouldRender(blockEntity, cameraPos);
        }

        int dist = Mth.clamp(Initializer.CONFIG.blockEntityDistance, MIN_DISTANCE, MAX_DISTANCE);
        if (dist == VANILLA_DEFAULT_DISTANCE) {
            return renderer.shouldRender(blockEntity, cameraPos);
        }

        if (dist < VANILLA_DEFAULT_DISTANCE) {
            return renderer.shouldRender(blockEntity, cameraPos)
                    && blockEntity.getBlockPos().distToCenterSqr(cameraPos.x, cameraPos.y, cameraPos.z) < (double) dist * dist;
        }

        if (renderer.getViewDistance() < dist) {
            return blockEntity.getBlockPos().distToCenterSqr(cameraPos.x, cameraPos.y, cameraPos.z) < (double) dist * dist;
        }

        return renderer.shouldRender(blockEntity, cameraPos);
    }
}
