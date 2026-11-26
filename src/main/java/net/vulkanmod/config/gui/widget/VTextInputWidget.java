package net.vulkanmod.config.gui.widget;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * fix focus state propagation, support spaces/symbols via charTyped and clipboard paste,
 * add Home/End navigation, and clip/scroll overflowing input text cleanly.
 */

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.util.VGuiConstants;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public class VTextInputWidget extends VAbstractWidget {
    public boolean selected = false;
    Consumer<VTextInputWidget> onSearch;
    private String text;
    private final Component placeholder;

    private int cursorPos = 0;
    private int selectionEnd = 0;
    private int scrollOffsetPx = 0;
    private long lastBlinkTime = 0;
    private boolean showCursor = true;

    private static final int CURSOR_BLINK_INTERVAL = 500; // ms
    private static final int TEXT_PAD_X = 8;

    public VTextInputWidget(int x, int y, int width, int height, Component placeholder, Consumer<VTextInputWidget> onSearch) {
        this.setPosition(x, y, width, height);

        this.placeholder = placeholder;
        this.onSearch = onSearch;
        this.text = "";
    }

    @Override
    public void renderWidget(double mouseX, double mouseY) {
        if (!this.isVisible()) return;

        Font font = Minecraft.getInstance().font;
        boolean hasText = !this.text.isEmpty();
        boolean isFocused = this.focused || this.selected;

        int backgroundColor = ColorUtil.ARGB.multiplyAlpha(VGuiConstants.COLOR_BLACK, 0.45f);
        int textColor = hasText ? VGuiConstants.COLOR_WHITE : VGuiConstants.COLOR_GRAY;

        GuiRenderer.fill(this.x, this.y, this.x + this.width, this.y + this.height, backgroundColor);
        this.renderHovering(0, 0);

        int innerWidth = Math.max(10, this.width - TEXT_PAD_X * 2);
        this.clampCursor();
        int cursorPx = font.width(this.text.substring(0, this.cursorPos));
        if (cursorPx - this.scrollOffsetPx > innerWidth - 2) {
            this.scrollOffsetPx = cursorPx - innerWidth + 2;
        } else if (cursorPx - this.scrollOffsetPx < 0) {
            this.scrollOffsetPx = cursorPx;
        }
        int totalTextPx = font.width(this.text);
        if (totalTextPx <= innerWidth) {
            this.scrollOffsetPx = 0;
        } else {
            this.scrollOffsetPx = Math.max(0, Math.min(this.scrollOffsetPx, totalTextPx - innerWidth + 2));
        }

        int textDrawX = this.x + TEXT_PAD_X - this.scrollOffsetPx;
        int clipLeft = this.x + 4;
        int clipRight = this.x + this.width - 4;
        if (clipRight > clipLeft) {
            GuiRenderer.enableScissor(clipLeft, this.y + 1, clipRight, this.y + this.height - 1);

            if (isFocused && cursorPos != selectionEnd) {
                int start = Math.min(cursorPos, selectionEnd);
                int end = Math.max(cursorPos, selectionEnd);
                String before = text.substring(0, start);
                String selectedStr = text.substring(start, end);

                int xBefore = textDrawX + font.width(before);
                int xSelected = font.width(selectedStr);

                int selColor = ColorUtil.ARGB.multiplyAlpha(VGuiConstants.COLOR_RED, 0.55f);
                GuiRenderer.fill(xBefore, this.y + 4, xBefore + xSelected, this.y + this.height - 4, selColor);
            }

            Component displayText = hasText ? Component.literal(this.text) : this.placeholder;
            int drawX = hasText ? textDrawX : this.x + TEXT_PAD_X;
            GuiRenderer.drawString(font, displayText,
                    drawX, this.y + (this.height - 8) / 2, textColor | 0xFF000000);

            if (isFocused && showCursor) {
                int cursorX = textDrawX + cursorPx;
                GuiRenderer.fill(cursorX, this.y + 5, cursorX + 1, this.y + this.height - 5,
                        VGuiConstants.COLOR_WHITE);
            }

            GuiRenderer.disableScissor();
        }

        if (isFocused) {
            int borderColor = ColorUtil.ARGB.multiplyAlpha(VGuiConstants.COLOR_RED, 0.8f);
            GuiRenderer.renderBorder(this.x, this.y, this.x + this.width, this.y + this.height, 1, borderColor);
        }

        if (isFocused) {
            long time = Util.getMillis();
            if (time - lastBlinkTime > CURSOR_BLINK_INTERVAL) {
                showCursor = !showCursor;
                lastBlinkTime = time;
            }
        } else {
            showCursor = true;
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!this.focused && !this.selected) {
            return false;
        }
        if (!event.isAllowedChatCharacter()) {
            return false;
        }
        String inserted = event.codepointAsString();
        if (inserted.isEmpty()) {
            return false;
        }
        this.insertText(inserted);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (!this.focused && !this.selected) return false;

        boolean shift = keyEvent.hasShiftDown();
        this.clampCursor();

        if (keyEvent.key() == GLFW.GLFW_KEY_ENTER || keyEvent.key() == GLFW.GLFW_KEY_KP_ENTER) {
            this.onSearch.accept(this);
            return true;
        }

        if (keyEvent.isPaste()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip != null && !clip.isEmpty()) {
                String clean = clip.replace('\r', ' ').replace('\n', ' ');
                this.insertText(clean);
            }
            return true;
        }

        if (keyEvent.isCopy()) {
            if (cursorPos != selectionEnd) {
                int start = Math.min(cursorPos, selectionEnd);
                int end = Math.max(cursorPos, selectionEnd);
                Minecraft.getInstance().keyboardHandler.setClipboard(text.substring(start, end));
            }
            return true;
        }

        if (cursorPos != selectionEnd) {
            int start = Math.min(cursorPos, selectionEnd);
            int end = Math.max(cursorPos, selectionEnd);

            if (keyEvent.key() == GLFW.GLFW_KEY_BACKSPACE || keyEvent.key() == GLFW.GLFW_KEY_DELETE) {
                this.text = text.substring(0, start) + text.substring(end);
                cursorPos = start;
                selectionEnd = start;
                this.resetBlink();
                this.onSearch.accept(this);
                return true;
            }
        }

        if (keyEvent.key() == GLFW.GLFW_KEY_BACKSPACE) {
            if (cursorPos > 0) {
                this.text = text.substring(0, cursorPos - 1) + text.substring(cursorPos);
                cursorPos--;
                selectionEnd = cursorPos;
                this.resetBlink();
                this.onSearch.accept(this);
            }
            return true;
        }

        if (keyEvent.key() == GLFW.GLFW_KEY_DELETE) {
            if (cursorPos < text.length()) {
                this.text = text.substring(0, cursorPos) + text.substring(cursorPos + 1);
                this.resetBlink();
                this.onSearch.accept(this);
            }
            return true;
        }

        if (keyEvent.isSelectAll()) {
            this.selectAll();
            return true;
        }

        if (keyEvent.key() == GLFW.GLFW_KEY_HOME) {
            cursorPos = 0;
            if (!shift) selectionEnd = cursorPos;
            this.resetBlink();
            return true;
        }

        if (keyEvent.key() == GLFW.GLFW_KEY_END) {
            cursorPos = text.length();
            if (!shift) selectionEnd = cursorPos;
            this.resetBlink();
            return true;
        }

        if (keyEvent.key() == GLFW.GLFW_KEY_LEFT) {
            if (cursorPos > 0) cursorPos--;
            if (!shift) selectionEnd = cursorPos;
            this.resetBlink();
            return true;
        }
        if (keyEvent.key() == GLFW.GLFW_KEY_RIGHT) {
            if (cursorPos < text.length()) cursorPos++;
            if (!shift) selectionEnd = cursorPos;
            this.resetBlink();
            return true;
        }

        return false;
    }

    private void insertText(String inserted) {
        this.clampCursor();
        int start = Math.min(cursorPos, selectionEnd);
        int end = Math.max(cursorPos, selectionEnd);
        this.text = text.substring(0, start) + inserted + text.substring(end);
        cursorPos = start + inserted.length();
        selectionEnd = cursorPos;
        this.resetBlink();
        this.onSearch.accept(this);
    }

    private void clampCursor() {
        int len = this.text.length();
        if (this.cursorPos < 0) this.cursorPos = 0;
        if (this.cursorPos > len) this.cursorPos = len;
        if (this.selectionEnd < 0) this.selectionEnd = 0;
        if (this.selectionEnd > len) this.selectionEnd = len;
    }

    private void resetBlink() {
        this.showCursor = true;
        this.lastBlinkTime = Util.getMillis();
    }

    public String getInput() {
        return this.text;
    }

    public void setInput(String input) {
        this.text = input != null ? input : "";
        this.clampCursor();
    }

    public void selectAll() {
        this.cursorPos = this.text.length();
        this.selectionEnd = 0;
        this.resetBlink();
    }

    @SuppressWarnings("unused")
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
        if (!this.active || !this.visible)
            return null;
        return super.nextFocusPath(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        if (!this.active || !this.visible) return false;

        boolean clicked = this.clicked(event.x(), event.y());
        if (clicked && event.button() == 0) {
            this.setFocused(true);
            this.selected = true;

            int relX = (int) event.x() - (this.x + TEXT_PAD_X) + this.scrollOffsetPx;
            int pos = 0;
            Font font = Minecraft.getInstance().font;
            for (int i = 0; i < text.length(); i++) {
                if (font.width(text.substring(0, i + 1)) > relX) break;
                pos = i + 1;
            }
            cursorPos = pos;
            selectionEnd = pos;
            this.resetBlink();

            return true;
        } else {
            this.setFocused(false);
            this.selected = false;
            return false;
        }
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
        if (!focused) {
            this.selected = false;
        }
    }

    @Override
    public boolean isFocused() {
        return this.focused;
    }
}
