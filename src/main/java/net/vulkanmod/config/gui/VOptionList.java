package net.vulkanmod.config.gui;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * fix live-offset hit-testing and hover bounds, widen scrollbar drag area, guard compact-height scrollbar clamping,
 * support right-click option reset forwarding, forward keyboard events to focused widgets, and pad bottom scroll reach.
 */

import com.mojang.blaze3d.opengl.GlStateManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.gui.widget.RangeOptionWidget;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class VOptionList extends GuiElement {
    private static final int SCROLLBAR_HIT_WIDTH = 6;
    private static final int BOTTOM_SCROLL_PADDING = 8;

    private final List<Entry> children = new ObjectArrayList<>();
    boolean scrolling = false;
    float scrollAmount = 0.0f;
    int itemWidth;
    int totalItemHeight;
    int itemHeight;
    int itemMargin;
    int listLength = 0;
    Entry focused;
    Entry draggingEntry;

    public VOptionList(int x, int y, int width, int height, int itemHeight) {
        this.setPosition(x, y, width, height);

        this.width = width;
        this.height = height;

        this.itemWidth = Math.max(40, this.width - 7);
        this.itemHeight = itemHeight;
        this.itemMargin = 3;
        this.totalItemHeight = this.itemHeight + this.itemMargin;
    }

    @SuppressWarnings("unused")
    public void addButton(OptionWidget<?> widget) {
        this.addEntry(new Entry(widget, this.itemMargin, null));
    }

    public void addAll(OptionBlock[] blocks) {
        for (int b = 0; b < blocks.length; b++) {
            OptionBlock block = blocks[b];
            int x0 = this.x;
            int width = this.itemWidth;
            int height = this.itemHeight;

            String title = block.title();
            if (title != null && !title.isEmpty()) {
                this.addEntry(new Entry(null, 8, title));
            }

            var options = block.options();
            for (Option<?> option : options) {
                int margin = this.itemMargin;
                OptionWidget<?> widget = option.getWidget();
                widget.setDimensions(x0, 0, width, height);
                this.addEntry(new Entry(widget, margin, null));
            }

            int spacer = (b == blocks.length - 1) ? BOTTOM_SCROLL_PADDING : 10;
            this.addEntry(new Entry(null, spacer, null));
        }
    }

    private void addEntry(Entry entry) {
        this.children.add(entry);
        this.listLength += entry.getTotalHeight();
    }

    @SuppressWarnings("unused")
    public void clearEntries() {
        this.listLength = 0;
        this.children.clear();
    }

    protected void updateScrollingState(double mouseX, double mouseY, int button) {
        int sbX = this.getScrollbarPosition();
        this.scrolling = button == 0
                && this.getMaxScroll() > 0
                && mouseX >= (double) (sbX - 1)
                && mouseX < (double) (sbX + SCROLLBAR_HIT_WIDTH)
                && mouseY >= (double) this.y
                && mouseY < (double) (this.y + this.height);
    }

    protected float getScrollAmount() {
        return scrollAmount;
    }

    public void setScrollAmount(double d) {
        this.scrollAmount = (float) Mth.clamp(d, 0.0, this.getMaxScroll());
    }

    private int getItemCount() {
        return this.children.size();
    }

    GuiEventListener getFocused() {
        return focused;
    }

    void setFocused(Entry focussed) {
        if (this.focused != null && this.focused != focussed) {
            this.focused.setFocused(false);
        }
        this.focused = focussed;
    }

    @Override
    public void setFocused(boolean bl) {
        if (!bl) {
            this.draggingEntry = null;
            this.scrolling = false;
            this.setFocused((Entry) null);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        this.updateScrollingState(event.x(), event.y(), event.button());
        if (this.scrolling) {
            this.draggingEntry = null;
            setFocused(null);
            return true;
        }

        if (this.isMouseOver(event.x(), event.y())) {
            Entry entry = this.getEntryAtPos(event.x(), event.y());
            if (entry != null && entry.mouseClicked(event, bl)) {
                if (event.button() == 0) {
                    this.draggingEntry = entry;
                    if (entry.widget instanceof RangeOptionWidget) {
                        setFocused(entry);
                        entry.setFocused(true);
                    } else {
                        setFocused(null);
                    }
                } else {
                    this.draggingEntry = null;
                    setFocused(null);
                }
                return true;
            }

            if (event.button() == 0) {
                this.draggingEntry = null;
                setFocused(null);
                return true;
            }
        }

        this.draggingEntry = null;
        setFocused(null);
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = false;
        if (event.button() == 0 && this.scrolling) {
            this.scrolling = false;
            handled = true;
        }
        if (this.isValidClickButton(event.button())) {
            if (this.draggingEntry != null) {
                handled |= this.draggingEntry.mouseReleased(event);
                this.draggingEntry = null;
                return true;
            }
            Entry entry = this.getEntryAtPos(event.x(), event.y());
            if (entry != null && entry.mouseReleased(event)) {
                return true;
            }
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (event.button() != 0) {
            return false;
        }

        if (this.draggingEntry != null) {
            return this.draggingEntry.mouseDragged(event, deltaX, deltaY);
        }

        if (!this.scrolling) {
            return false;
        }

        double maxScroll = this.getMaxScroll();
        if (event.y() < this.y) {
            this.setScrollAmount(0.0);
        } else if (event.y() > this.getBottom()) {
            this.setScrollAmount(maxScroll);
        } else if (maxScroll > 0.0) {
            double barHeight = this.computeScrollbarHeight();
            double trackHeight = Math.max(1.0, this.height - barHeight);
            double scrollFactor = Math.max(1.0, maxScroll / trackHeight);
            this.setScrollAmount(this.getScrollAmount() + deltaY * scrollFactor);
        }

        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.focused != null) {
            return this.focused.keyPressed(event);
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double xScroll, double yScroll) {
        if (this.getMaxScroll() <= 0) {
            return false;
        }
        this.setScrollAmount(this.getScrollAmount() - yScroll * (double) this.totalItemHeight / 2.0);
        return true;
    }

    public int getMaxScroll() {
        return Math.max(0, this.getTotalLength() - this.height);
    }

    protected int getTotalLength() {
        return this.listLength;
    }

    public int getBottom() {
        return this.y + this.height;
    }

    @Nullable
    protected VOptionList.Entry getEntryAtPos(double x, double y) {
        if (x < (double) this.x || x >= (double) this.getScrollbarPosition()
                || y < (double) this.y || y >= (double) (this.y + this.height)) {
            return null;
        }

        int rowTop = this.y - (int) this.getScrollAmount();
        for (var entry : this.children) {
            int totalH = entry.getTotalHeight();
            VAbstractWidget widget = entry.widget;
            if (widget != null) {
                int visTop = Math.max(this.y, rowTop);
                int visBottom = Math.min(this.y + this.height, rowTop + widget.height);
                if (visBottom > visTop && y >= visTop && y < visBottom) {
                    widget.y = rowTop;
                    return entry;
                }
            }
            rowTop += totalH;
        }
        return null;
    }

    @Override
    public void updateState(double mX, double mY) {
        if (this.draggingEntry != null)
            return;

        super.updateState(mX, mY);
    }

    private int computeScrollbarHeight() {
        int h = Math.max(1, this.getHeight());
        int totalLength = Math.max(1, this.getTotalLength());
        int raw = (int) ((float) (h * h) / totalLength);
        int maxBar = Math.max(4, h - 4);
        int minBar = Math.min(24, maxBar);
        return Mth.clamp(raw, minBar, maxBar);
    }

    public void renderWidget(int mouseX, int mouseY) {
        if (this.height <= 0 || this.width <= 0) {
            return;
        }

        this.scrollAmount = (float) Mth.clamp(this.scrollAmount, 0.0, this.getMaxScroll());

        GuiRenderer.enableScissor(x, y, x + width, y + height);
        this.renderList(mouseX, mouseY);
        GuiRenderer.disableScissor();

        // Scroll bar
        int maxScroll = this.getMaxScroll();
        if (maxScroll > 0) {
            GlStateManager._enableBlend();

            int height = this.getHeight();
            int barHeight = this.computeScrollbarHeight();

            int scrollAmount = (int) this.getScrollAmount();
            int trackSpan = Math.max(0, height - barHeight);
            int barY = (maxScroll > 0 ? scrollAmount * trackSpan / maxScroll : 0) + this.getY();
            barY = Mth.clamp(barY, this.getY(), this.getY() + trackSpan);

            int scrollbarPosition = this.getScrollbarPosition();
            int thickness = 3;

            int backgroundColor = ColorUtil.ARGB.pack(0.8f, 0.8f, 0.8f, 0.2f);
            GuiRenderer.fill(scrollbarPosition, this.getY(), scrollbarPosition + thickness, this.getY() + height, backgroundColor);

            int barColor = this.scrolling
                    ? ColorUtil.ARGB.pack(0.6f, 0.08f, 0.08f, 0.85f)
                    : ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.6f);
            GuiRenderer.fill(scrollbarPosition, barY, scrollbarPosition + thickness, barY + barHeight, barColor);
        }
    }

    protected int getScrollbarPosition() {
        return this.x + this.width - 3;
    }

    public VAbstractWidget getHoveredWidget(double mouseX, double mouseY) {
        if (this.draggingEntry != null)
            return draggingEntry.widget;

        Entry entry = this.getEntryAtPos(mouseX, mouseY);
        if (entry == null || entry.widget == null) {
            return null;
        }
        return entry.widget.isMouseOver(mouseX, mouseY) ? entry.widget : null;
    }

    protected void renderList(int mouseX, int mouseY) {
        int itemCount = this.getItemCount();

        int rowTop = this.y - (int) this.getScrollAmount();
        boolean mouseInList = mouseX >= this.x && mouseX < this.getScrollbarPosition()
                && mouseY >= this.y && mouseY < this.y + this.height;
        int effectiveMouseX = mouseInList ? mouseX : -1;
        int effectiveMouseY = mouseInList ? mouseY : -1;

        for (int j = 0; j < itemCount; ++j) {
            VOptionList.Entry entry = this.getEntry(j);
            int totalH = entry.getTotalHeight();

            if (rowTop + totalH >= this.y && rowTop <= (this.y + this.height)) {
                boolean updateState = this.draggingEntry == null;
                entry.render(rowTop, effectiveMouseX, effectiveMouseY, updateState, this.x);
            }

            rowTop += totalH;
        }
    }

    private Entry getEntry(int j) {
        return this.children.get(j);
    }

    protected boolean isValidClickButton(int i) {
        return i == 0;
    }

    protected static class Entry implements GuiEventListener {
        final VAbstractWidget widget;
        final int margin;
        final String headerTitle;

        private Entry(OptionWidget<?> widget, int margin, String headerTitle) {
            this.widget = widget;
            this.margin = margin;
            this.headerTitle = headerTitle;
        }

        public void render(int y, int mouseX, int mouseY, boolean updateState, int listX) {
            if (headerTitle != null && !headerTitle.isEmpty()) {
                int headerY = y + 4;
                GuiRenderer.drawString(
                        Minecraft.getInstance().font,
                        Component.literal(headerTitle),
                        listX + 8,
                        headerY,
                        0xFFFFFFFF
                );
                return;
            }

            if (widget == null)
                return;

            widget.y = y;

            if (updateState)
                widget.updateState(mouseX, mouseY);

            widget.render(mouseX, mouseY);
        }

        public int getTotalHeight() {
            if (headerTitle != null && !headerTitle.isEmpty()) {
                return Minecraft.getInstance().font.lineHeight + margin;
            }
            if (widget != null)
                return widget.height + margin;
            else
                return margin;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
            if (widget == null) return false;
            return widget.mouseClicked(event, bl);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (widget == null) return false;
            return widget.mouseReleased(event);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
            if (widget == null) return false;
            return widget.mouseDragged(event, deltaX, deltaY);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (widget == null) return false;
            return widget.keyPressed(event);
        }

        @Override
        public boolean isFocused() {
            return widget != null && widget.isFocused();
        }

        @Override
        public void setFocused(boolean bl) {
            if (widget != null)
                widget.setFocused(bl);
        }
    }
}
