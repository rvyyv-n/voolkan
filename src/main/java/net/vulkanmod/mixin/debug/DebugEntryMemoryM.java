package net.vulkanmod.mixin.debug;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * display cached VMA VRAM/shared/unified memory budget lines and rolling frame-time / 1% low stats on the F3 screen.
 */

import dev.voolkan.telemetry.FrameTimeTracker;
import net.minecraft.client.gui.components.debug.DebugEntryMemory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Mixin(DebugEntryMemory.class)
public abstract class DebugEntryMemoryM {

    @Unique
    private static final long VOOLKAN_BUDGET_REFRESH_NS = 500_000_000L;

    @Unique
    private long voolkanLastBudgetRefreshNs;

    @Unique
    private List<String> voolkanCachedBudgetLines = List.of();

    @Shadow @Final private static Identifier GROUP;

    @Shadow
    protected static long bytesToMegabytes(long l) {
        return 0;
    }

    // TODO
//    @Shadow @Final private DebugEntryMemory.AllocationRateCalculator allocationRateCalculator;

    @Overwrite
    public void display(DebugScreenDisplayer debugScreenDisplayer, @Nullable Level level, @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2) {
        long l = Runtime.getRuntime().maxMemory();
        long m = Runtime.getRuntime().totalMemory();
        long n = Runtime.getRuntime().freeMemory();
        long o = m - n;

        MemoryManager memoryManager = MemoryManager.getInstance();
        long now = System.nanoTime();
        if (this.voolkanCachedBudgetLines.isEmpty() || now - this.voolkanLastBudgetRefreshNs >= VOOLKAN_BUDGET_REFRESH_NS) {
            this.voolkanCachedBudgetLines = memoryManager.getMemoryBudgetLines();
            this.voolkanLastBudgetRefreshNs = now;
        }

        List<String> lines = new ArrayList<>(6 + this.voolkanCachedBudgetLines.size());
        lines.add(String.format(Locale.ROOT, "Mem: %2d%% %03d/%03dMB", o * 100L / l, bytesToMegabytes(o), bytesToMegabytes(l)));
//        lines.add(String.format(Locale.ROOT, "Allocation rate: %03dMB/s", bytesToMegabytes(this.allocationRateCalculator.bytesAllocatedPerSecond(o))));
        lines.add(String.format(Locale.ROOT, "Allocated: %2d%% %03dMB", m * 100L / l, bytesToMegabytes(m)));
        lines.add(String.format("Off-heap: " + getOffHeapMemory() + "MB"));
        lines.add("NativeMemory: %dMB".formatted(memoryManager.getNativeMemoryMB()));
        lines.add("DeviceMemory: %dMB".formatted(memoryManager.getAllocatedDeviceMemoryMB()));
        lines.addAll(this.voolkanCachedBudgetLines);
        if (Initializer.CONFIG == null || Initializer.CONFIG.showFrameTimeF3) {
            String frameTimeLine = FrameTimeTracker.getFormattedLine();
            if (!frameTimeLine.isEmpty()) {
                lines.add(frameTimeLine);
            }
        }

        debugScreenDisplayer.addToGroup(GROUP, lines);

    }

    private long getOffHeapMemory() {
        return bytesToMegabytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
    }
}
