package net.vulkanmod.vulkan.shader.layout;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * use primitive FloatSupplier to avoid per-draw float boxing.
 */

import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Supplier;

public class Vec1f extends Uniform {
    private Uniforms.FloatSupplier floatSupplier;

    public Vec1f(Info info) {
        super(info);
    }

    protected void setupSupplier() {
        if (this.info.floatSupplier != null) {
            this.floatSupplier = this.info.floatSupplier;
        }
        else if (this.info.bufferSupplier != null) {
            this.setSupplier(this.info.bufferSupplier);
        }
    }

    @Override
    public void setSupplier(Supplier<MappedBuffer> supplier) {
        this.floatSupplier = () -> supplier.get().getFloat(0);
    }

    void update(long ptr) {
        if (this.floatSupplier == null) {
            return;
        }

        float f = this.floatSupplier.getAsFloat();
        MemoryUtil.memPutFloat(ptr + this.offset, f);
    }
}
