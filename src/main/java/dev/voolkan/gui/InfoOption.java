/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.gui;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.option.Option;

import java.util.function.Supplier;

/** A read-only row in the options list: a name and a text value that cannot be changed. */
public class InfoOption extends Option<String> {

    public InfoOption(Component name, Supplier<String> value) {
        super(name, v -> {}, value, Component::literal);
        this.setActivationFn(() -> false);
    }

    @Override
    protected OptionWidget<?> createWidget() {
        return new InfoOptionWidget(this, this.name);
    }
}
