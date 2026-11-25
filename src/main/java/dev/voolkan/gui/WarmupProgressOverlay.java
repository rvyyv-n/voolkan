/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.gui;

import dev.voolkan.cache.PipelineWarmup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Unobtrusive shader/pipeline warm-up progress line and thin bar rendered on
 * {@code LevelLoadingScreen} (and as a subtle corner pill in-game if warm-up finishes
 * shortly after joining a server).
 */
public final class WarmupProgressOverlay {
    private static final int BAR_WIDTH = 160;
    private static final int BAR_HEIGHT = 3;
    private static final int TEXT_COLOR = 0xFFE6E6E6;
    private static final int TRACK_COLOR = 0x90181818;
    private static final int FILL_COLOR = 0xFFCC3333;
    private static final int PILL_BG_COLOR = 0x96101010;
    private static final int PILL_BORDER_COLOR = 0x99661A1A;

    private WarmupProgressOverlay() {
    }

    /** Renders a centered progress line and bar near the bottom of {@code LevelLoadingScreen}. */
    public static void renderOnLoadingScreen(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (!PipelineWarmup.shouldShowIndicator()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            return;
        }

        PipelineWarmup.Progress progress = PipelineWarmup.getProgress();
        Font font = mc.font;
        Component label = Component.translatable("voolkan.warmup.progress", progress.completed(), progress.total());

        int textWidth = font.width(label);
        int textX = (screenWidth - textWidth) / 2;
        int textY = Math.max(12, screenHeight - 36);

        graphics.drawString(font, label, textX, textY, TEXT_COLOR, true);

        int barW = Math.min(BAR_WIDTH, Math.max(80, screenWidth - 40));
        int barX = (screenWidth - barW) / 2;
        int barY = textY + font.lineHeight + 3;
        int fillW = Math.round(barW * progress.fraction());

        graphics.fill(barX, barY, barX + barW, barY + BAR_HEIGHT, TRACK_COLOR);
        if (fillW > 0) {
            graphics.fill(barX, barY, barX + fillW, barY + BAR_HEIGHT, FILL_COLOR);
        }
    }

    /**
     * Renders a compact bottom-left progress pill on the in-game HUD if warm-up is still draining
     * after the loading screen closes (hidden when another screen or the F3 overlay is open).
     */
    public static void renderInGameHud(GuiGraphics graphics, boolean debugScreenVisible) {
        if (debugScreenVisible || !PipelineWarmup.shouldShowIndicator()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null || mc.screen != null) {
            return;
        }

        PipelineWarmup.Progress progress = PipelineWarmup.getProgress();
        Font font = mc.font;
        Component label = Component.translatable("voolkan.warmup.progress", progress.completed(), progress.total());

        int screenHeight = graphics.guiHeight();
        int textWidth = font.width(label);
        int padX = 6;
        int padY = 4;
        int pillW = Math.max(120, textWidth + padX * 2);
        int pillH = font.lineHeight + padY * 2 + 3;
        int x = 8;
        int y = Math.max(8, screenHeight - pillH - 8);

        graphics.fill(x, y, x + pillW, y + pillH, PILL_BG_COLOR);
        graphics.fill(x, y, x + pillW, y + 1, PILL_BORDER_COLOR);
        graphics.fill(x, y + pillH - 1, x + pillW, y + pillH, PILL_BORDER_COLOR);

        graphics.drawString(font, label, x + padX, y + padY, TEXT_COLOR, false);

        int barX = x + padX;
        int barW = pillW - padX * 2;
        int barY = y + pillH - 4;
        int fillW = Math.round(barW * progress.fraction());
        graphics.fill(barX, barY, barX + barW, barY + 2, TRACK_COLOR);
        if (fillW > 0) {
            graphics.fill(barX, barY, barX + fillW, barY + 2, FILL_COLOR);
        }
    }
}
