package net.vulkanmod.mixin.texture.update;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * skip redundant animated texture uploads during multi-tick catch-up frames.
 */

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TickableTexture;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(TextureManager.class)
public abstract class MTextureManager {

    @Shadow @Final private Set<TickableTexture> tickableTextures;

    /**
     * @author
     */
    @Overwrite
    public void tick() {
        if (!Initializer.CONFIG.textureAnimations || !SpriteUpdateUtil.doUploadFrame())
            return;

        for (TickableTexture tickable : this.tickableTextures) {
            tickable.tick();
        }

        SpriteUpdateUtil.transitionLayouts();
    }
}
