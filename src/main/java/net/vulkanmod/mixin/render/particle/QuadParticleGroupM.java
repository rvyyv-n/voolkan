package net.vulkanmod.mixin.render.particle;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * use Mth.floor for accurate negative-coordinate section lookup in particle culling,
 * and apply configurable per-frame particle limit keeping the most recent visible particles.
 */

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.voolkan.render.ParticleCap;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.QuadParticleRenderState;
import net.minecraft.util.Mth;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.Queue;

@Mixin(value = QuadParticleGroup.class, priority = 999)
public class QuadParticleGroupM {

    @Shadow @Final private ParticleRenderType particleType;
    @Shadow @Final QuadParticleRenderState particleTypeRenderState;

    @Unique private SingleQuadParticle[] voolkan$recentBuffer;

    @SuppressWarnings("unchecked")
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void applyParticleLimit(
            Frustum frustum,
            Camera camera,
            float partialTick,
            CallbackInfoReturnable<ParticleGroupRenderState> cir
    ) {
        if (ParticleCap.getConfiguredLimit() <= 0) {
            this.voolkan$recentBuffer = null;
            return;
        }
        if (!ParticleCap.isActive()) {
            return;
        }

        int remaining = ParticleCap.getRemaining();
        if (remaining <= 0) {
            this.particleTypeRenderState.clear();
            cir.setReturnValue(this.particleTypeRenderState);
            return;
        }

        Queue<SingleQuadParticle> allParticles = ((ParticleGroup<SingleQuadParticle>) (Object) this).getAll();
        if (allParticles.isEmpty()) {
            cir.setReturnValue(this.particleTypeRenderState);
            return;
        }

        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (allParticles.size() <= remaining) {
            int extracted = 0;
            for (SingleQuadParticle particle : allParticles) {
                ParticleAccessor acc = (ParticleAccessor) particle;
                double px = acc.voolkan$getX();
                double py = acc.voolkan$getY();
                double pz = acc.voolkan$getZ();
                if (!cull(worldRenderer, px, py, pz) && frustum.pointInFrustum(px, py, pz)) {
                    voolkan$extractParticle(particle, camera, partialTick);
                    extracted++;
                }
            }
            ParticleCap.consume(extracted);
            cir.setReturnValue(this.particleTypeRenderState);
            return;
        }

        int bufferCapacity = Math.max(remaining, ParticleCap.getActiveLimit());
        SingleQuadParticle[] ring = this.voolkan$recentBuffer;
        if (ring == null || ring.length != bufferCapacity) {
            ring = new SingleQuadParticle[bufferCapacity];
            this.voolkan$recentBuffer = ring;
        }

        int written = 0;
        for (SingleQuadParticle particle : allParticles) {
            ParticleAccessor acc = (ParticleAccessor) particle;
            double px = acc.voolkan$getX();
            double py = acc.voolkan$getY();
            double pz = acc.voolkan$getZ();
            if (!cull(worldRenderer, px, py, pz) && frustum.pointInFrustum(px, py, pz)) {
                ring[written % remaining] = particle;
                written++;
            }
        }

        int count = Math.min(written, remaining);
        int start = written > remaining ? (written % remaining) : 0;
        try {
            for (int i = 0; i < count; i++) {
                int idx = (start + i) % remaining;
                SingleQuadParticle particle = ring[idx];
                ring[idx] = null;
                if (particle != null) {
                    voolkan$extractParticle(particle, camera, partialTick);
                }
            }
        } finally {
            Arrays.fill(ring, 0, count, null);
        }

        ParticleCap.consume(count);
        cir.setReturnValue(this.particleTypeRenderState);
    }

    @Unique
    private void voolkan$extractParticle(SingleQuadParticle particle, Camera camera, float partialTick) {
        try {
            particle.extract(this.particleTypeRenderState, camera, partialTick);
        } catch (Throwable throwable) {
            CrashReport crashReport = CrashReport.forThrowable(throwable, "Rendering Particle");
            CrashReportCategory category = crashReport.addCategory("Particle being rendered");
            category.setDetail("Particle", particle::toString);
            category.setDetail("Particle Type", this.particleType::toString);
            throw new ReportedException(crashReport);
        }
    }

    @WrapOperation(method = "extractRenderState",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean particleWithinSections(Frustum instance, double x, double y, double z, Operation<Boolean> original) {
        return !cull(WorldRenderer.getInstance(), x, y, z) && instance.pointInFrustum(x, y, z);
    }

    @Unique
    private static boolean cull(WorldRenderer worldRenderer, double x, double y, double z) {
        if (worldRenderer == null || worldRenderer.getSectionGrid() == null) {
            return false;
        }
        RenderSection section = worldRenderer.getSectionGrid().getSectionAtBlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        return section != null && section.getLastFrame() != worldRenderer.getLastFrame();
    }
}
