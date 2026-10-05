/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.OptionWidget;

public class InfoOptionWidget extends OptionWidget<InfoOption> {

    public InfoOptionWidget(InfoOption option, Component name) {
        super(option, name);
        this.updateDisplayedValue();
    }

    @Override
    protected void renderControls(double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        int x = this.controlX + this.controlWidth / 2;
        int y = this.y + (this.height - 9) / 2;
        GuiRenderer.drawScrollingString(font, this.getDisplayedValue(), x, y, this.controlWidth - 8, 0xFFFFFFFF);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
    }
}
