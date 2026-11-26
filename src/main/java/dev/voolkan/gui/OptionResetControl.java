/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.gui;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.util.VGuiConstants;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.vulkan.util.ColorUtil;

/**
 * Compact inline per-option "Reset to Default" button and right-click helper rendered inside
 * {@link OptionWidget} rows whenever an active option differs from its platform-aware default.
 */
public final class OptionResetControl {
    public static final int BUTTON_SIZE = 14;
    public static final int BUTTON_GAP = 4;

    private OptionResetControl() {
    }

    /** Returns true when this widget's option is eligible for Reset to Default (right-click or button). */
    public static boolean isVisible(OptionWidget<?> widget) {
        if (widget == null || !widget.active || !widget.visible) {
            return false;
        }
        Option<?> option = widget.getOption();
        return option != null && PageDefaults.canReset(option);
    }

    /** Returns true when the inline reset icon has enough horizontal space to render and accept clicks. */
    public static boolean isButtonVisible(OptionWidget<?> widget) {
        return isVisible(widget) && getX(widget) > widget.getX() + 28;
    }

    public static int getX(OptionWidget<?> widget) {
        return widget.controlX - BUTTON_SIZE - BUTTON_GAP;
    }

    public static int getY(OptionWidget<?> widget) {
        return widget.getY() + (widget.getHeight() - BUTTON_SIZE) / 2;
    }

    /** Returns the right-most X coordinate available for the option label text. */
    public static int getLabelRightEdge(OptionWidget<?> widget) {
        if (isButtonVisible(widget)) {
            return getX(widget) - 4;
        }
        return widget.controlX - 6;
    }

    public static boolean isHovered(OptionWidget<?> widget, double mouseX, double mouseY) {
        if (!isButtonVisible(widget)) {
            return false;
        }
        int bx = getX(widget);
        int by = getY(widget);
        return mouseX >= bx && mouseX < bx + BUTTON_SIZE && mouseY >= by && mouseY < by + BUTTON_SIZE;
    }

    /** Attempts to reset {@code option} to its platform-aware default. Returns true if changed. */
    public static boolean tryReset(Option<?> option) {
        return option != null && PageDefaults.resetOption(option);
    }

    /** Tooltip shown when hovering the inline reset button directly. */
    public static Component getButtonTooltip(Option<?> option) {
        Component defaultLabel = PageDefaults.formatDefaultValue(option);
        if (defaultLabel != null) {
            return Component.translatable("voolkan.options.reset.tooltipValue", defaultLabel);
        }
        return Component.translatable("voolkan.options.reset.tooltip");
    }

    /** Short hint appended to an option's tooltip when it differs from its default. */
    public static Component getRowHint() {
        return Component.translatable("voolkan.options.reset.hint");
    }

    /** Draws the compact inline reset button with a font-independent pixel reset arrow. */
    public static void render(OptionWidget<?> widget, double mouseX, double mouseY) {
        if (!isButtonVisible(widget)) {
            return;
        }

        int bx = getX(widget);
        int by = getY(widget);

        boolean hovered = isHovered(widget, mouseX, mouseY);
        int bgColor = hovered
                ? ColorUtil.ARGB.multiplyAlpha(VGuiConstants.COLOR_RED, 0.35f)
                : ColorUtil.ARGB.pack(0.0f, 0.0f, 0.0f, 0.35f);
        int borderColor = hovered
                ? VGuiConstants.COLOR_RED
                : ColorUtil.ARGB.pack(0.65f, 0.65f, 0.65f, 0.55f);
        int iconColor = hovered ? 0xFFFFFFFF : 0xFFD0D0D0;

        GuiRenderer.fill(bx, by, bx + BUTTON_SIZE, by + BUTTON_SIZE, bgColor);
        GuiRenderer.renderBorder(bx, by, bx + BUTTON_SIZE, by + BUTTON_SIZE, 1, borderColor);

        // Draw a crisp 7x7 counter-clockwise reset arrow centered in the 14x14 box
        int ox = bx + 3;
        int oy = by + 3;
        // Top arc (x=2..5, y=1)
        GuiRenderer.fill(ox + 2, oy + 1, ox + 6, oy + 2, iconColor);
        // Right side (x=6, y=2..5)
        GuiRenderer.fill(ox + 6, oy + 2, ox + 7, oy + 6, iconColor);
        // Bottom arc (x=2..5, y=6)
        GuiRenderer.fill(ox + 2, oy + 6, ox + 6, oy + 7, iconColor);
        // Left-bottom tail (x=1, y=5)
        GuiRenderer.fill(ox + 1, oy + 5, ox + 2, oy + 6, iconColor);
        // Arrowhead pointing down-left at top-left corner (x=1..3, y=0..3)
        GuiRenderer.fill(ox + 1, oy + 2, ox + 2, oy + 3, iconColor);
        GuiRenderer.fill(ox + 1, oy + 0, ox + 2, oy + 2, iconColor);
        GuiRenderer.fill(ox + 1, oy + 2, ox + 4, oy + 3, iconColor);
    }
}
