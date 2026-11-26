package net.vulkanmod.config.option;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * add stepBy keyboard stepping and clamp bounds safely when min equals max.
 */

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.gui.widget.RangeOptionWidget;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class RangeOption extends Option<Integer> {
    int min;
    int max;
    int step;
    float scaledNewValue;

    public RangeOption(Component name, int min, int max, int step, Function<Integer, Component> translator, Consumer<Integer> setter, Supplier<Integer> getter) {
        super(name, setter, getter, translator);
        this.min = min;
        this.max = max;
        this.step = step;
        this.scaledNewValue = computeScaledValue(this.newValue);
    }

    public RangeOption(Component name, int min, int max, int step, Consumer<Integer> setter, Supplier<Integer> getter) {
        this(name, min, max, step, (i) -> Component.literal(String.valueOf(i)), setter, getter);
    }

    protected OptionWidget<?> createWidget() {
        var widget = new RangeOptionWidget(this, this.name);
        this.widget = widget;
        return widget;
    }

    public Component getName() {
        return Component.nullToEmpty(this.name.getString() + ": " + this.getNewValue().toString());
    }

    public float getScaledValue() {
        return this.scaledNewValue;
    }

    public void setNewValueFromScaledFloat(float f) {
        double n = Mth.lerp(f, min, max);

        n = this.step * Math.round(n / this.step);

        this.setNewValue(Mth.clamp((int) n, this.min, this.max));
    }

    public void stepBy(int deltaSteps) {
        if (deltaSteps == 0 || this.step <= 0) {
            return;
        }
        int next = Mth.clamp(this.newValue + deltaSteps * this.step, this.min, this.max);
        if (next != this.newValue) {
            this.setNewValue(next);
        }
    }

    public void setNewValue(Integer newValue) {
        super.setNewValue(newValue);

        this.scaledNewValue = computeScaledValue(this.newValue);
    }

    public float getScaledNewValue() {
        return scaledNewValue;
    }

    private float computeScaledValue(float value) {
        if (this.max <= this.min) {
            return 0.0f;
        }
        return Mth.clamp((value - this.min) / (this.max - this.min), 0.0f, 1.0f);
    }
}
