package net.vulkanmod.vulkan.shader.layout;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * use primitive IntSupplier to avoid per-draw integer boxing.
 */

import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class Vec1i extends Uniform {
    private IntSupplier intSupplier;

    public Vec1i(Info info) {
        super(info);
    }

    protected void setupSupplier() {
        if (this.info.intSupplier != null) {
            this.intSupplier = this.info.intSupplier;
        }
        else if (this.info.bufferSupplier != null) {
            this.setSupplier(this.info.bufferSupplier);
        }
    }

    @Override
    public void setSupplier(Supplier<MappedBuffer> supplier) {
        this.intSupplier = () -> supplier.get().getInt(0);
    }

    void update(long ptr) {
        if (this.intSupplier == null) {
            return;
        }

        int i = this.intSupplier.getAsInt();
        MemoryUtil.memPutInt(ptr + this.offset, i);
    }
}
