package net.vulkanmod.config.gui;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * voolkan branding (Ko-fi button removed), per-page Restore Defaults and Copy Diagnostics buttons,
 * smarter block/page-aware search preserving active tab, per-option reset tooltip hints,
 * and responsive scaling/clamped tooltip layout for compact heights and large GUI scales.
 */

import com.google.common.collect.Lists;
import dev.voolkan.compat.DiagnosticsReport;
import dev.voolkan.gui.OptionResetControl;
import dev.voolkan.gui.PageDefaults;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.util.SearchHelper;
import net.vulkanmod.config.gui.util.VGuiConstants;
import net.vulkanmod.config.gui.widget.*;
import net.vulkanmod.config.option.*;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VOptionScreen extends Screen {
    public final static int MARGIN = 10;

    private final Screen parent;

    private final List<ModSettingsEntry> modSettingsEntries;

    private final List<OptionPage> optionPages;
    private OptionPage searchResultsPage;

    private int currentListIdx = 0;
    private boolean isSearchActive = false;

    // Responsive layout metrics recomputed in init()
    private int sidebarWidth = VGuiConstants.PAGE_BUTTON_WIDTH;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listHeight;
    private int bottomButtonsTopY;
    private boolean twoRowBottomBar;
    private boolean useSideTooltip;
    private int sideTooltipX;
    private int sideTooltipY;
    private int sideTooltipWidth;

    private VButtonWidget applyButton;
    private VButtonWidget undoButton;
    private VButtonWidget doneButton;
    private VButtonWidget restoreDefaultsButton;
    private VButtonWidget copyDiagnosticsButton;
    private boolean diagnosticsCopiedActive = false;
    private long diagnosticsCopiedStartNs = 0L;

    private static final long COPIED_FEEDBACK_NS = 2_500_000_000L;

    private VTextInputWidget searchField;

    private final List<VAbstractWidget> iconWidgets = Lists.newArrayList();
    private final List<VButtonWidget> pageButtons = Lists.newArrayList();
    private final List<VButtonWidget> buttons = Lists.newArrayList();

    public VOptionScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;

        this.optionPages = new ArrayList<>();
        this.modSettingsEntries = new ArrayList<>(ModSettingsRegistry.INSTANCE.getModEntries());
    }

    @Override
    protected void init() {
        String prevQuery = this.searchField != null ? this.searchField.getInput() : "";
        boolean wasSearchActive = this.isSearchActive && !prevQuery.trim().isEmpty();

        this.initOptionsPages();

        if (this.optionPages.isEmpty()) {
            throw new IllegalStateException("Default Options weren't added!");
        }
        if (this.currentListIdx >= this.optionPages.size()) {
            this.currentListIdx = 0;
        }

        this.computeLayout();

        int itemHeight = 20;
        this.buildLists(this.listLeft, this.listTop, this.listWidth, this.listHeight, itemHeight);

        this.searchField = createSearchField();

        if (wasSearchActive) {
            this.searchField.setInput(prevQuery);
            this.performSearch(prevQuery);
        } else {
            this.isSearchActive = false;
            this.buildPage();
        }
    }

    private void computeLayout() {
        int btnHeight = VGuiConstants.WIDGET_HEIGHT;
        int bottomPad = 6;
        int rowGap = 4;

        this.sidebarWidth = this.width < 380 ? 78 : VGuiConstants.PAGE_BUTTON_WIDTH;
        this.listLeft = MARGIN + this.sidebarWidth + 6;
        this.listTop = 28;

        // Determine whether bottom buttons fit on one row without overlapping at this scaled width
        int pad = this.width < 460 ? 6 : 10;
        int doneW = this.font.width(CommonComponents.GUI_DONE) + 2 * pad;
        int applyW = this.font.width(Component.translatable("vulkanmod.options.buttons.apply")) + 2 * pad;
        int undoW = this.font.width(Component.translatable("vulkanmod.options.buttons.undo")) + 2 * pad;
        int restoreW = this.font.width(Component.translatable("voolkan.options.buttons.restoreDefaults")) + 2 * pad;
        int rightClusterW = doneW + applyW + undoW + 2 * VGuiConstants.WIDGET_MARGIN;
        int rightStart = this.width - MARGIN - rightClusterW;

        int sidebarBottom = this.estimateSidebarBottomY();
        boolean copyUnderSidebar = (this.height - btnHeight - bottomPad) >= sidebarBottom + 4;
        int copyW = copyUnderSidebar
                ? this.sidebarWidth
                : Math.max(
                        this.font.width(Component.translatable("voolkan.options.buttons.copyDiagnostics")),
                        this.font.width(Component.translatable("voolkan.options.buttons.copied"))
                ) + 2 * pad;

        int leftEndOneRow = copyUnderSidebar
                ? (this.listLeft + restoreW)
                : (this.listLeft + copyW + VGuiConstants.WIDGET_MARGIN + restoreW);

        this.twoRowBottomBar = (leftEndOneRow + 6 > rightStart);
        int effBottomPad = this.twoRowBottomBar ? 4 : bottomPad;
        int effRowGap = this.twoRowBottomBar ? 2 : rowGap;
        this.bottomButtonsTopY = this.height - (this.twoRowBottomBar ? (2 * btnHeight + effRowGap + effBottomPad) : (btnHeight + effBottomPad));

        int availableWidth = Math.max(120, this.width - this.listLeft - MARGIN);
        this.listWidth = Math.min(availableWidth, 420);
        this.listHeight = Math.max(24, this.bottomButtonsTopY - this.listTop - 4);

        int sideX = this.listLeft + this.listWidth + 6;
        int sideW = Math.min(this.width - sideX - MARGIN, 380);
        this.useSideTooltip = sideW >= 170;
        this.sideTooltipX = sideX;
        this.sideTooltipY = this.listTop + 2;
        this.sideTooltipWidth = Math.max(120, sideW);
    }

    private int estimateSidebarBottomY() {
        int y = 4;
        for (var modEntry : this.modSettingsEntries) {
            y += 28;
            y += modEntry.getPages().size() * VGuiConstants.WIDGET_HEIGHT;
        }
        return y;
    }

    private void initOptionsPages() {
        this.optionPages.clear();

        for (var modPageSet : this.modSettingsEntries) {
            modPageSet.initPages();

            this.optionPages.addAll(modPageSet.getPages());
        }
    }

    private VTextInputWidget createSearchField() {
        int rightMargin = MARGIN;
        int padding = 10;
        int topBarRight = this.width - rightMargin;

        if (UpdateChecker.isUpdateAvailable()) {
            int updateWidth = minecraft.font.width(Component.translatable("vulkanmod.options.buttons.update_available")) + padding;
            topBarRight -= updateWidth + VGuiConstants.WIDGET_MARGIN;
        }

        int width = Math.max(80, Math.min(topBarRight - this.listLeft - 4, this.listWidth - 7));

        return new VTextInputWidget(
                this.listLeft, 4,
                width, VGuiConstants.WIDGET_HEIGHT,
                Component.translatable("vulkanmod.options.searchFieldPlaceholder"),
                widget -> performSearch(widget.getInput())
        );
    }

    private void buildLists(int left, int top, int listWidth, int listHeight, int itemHeight) {
        for (OptionPage page : this.optionPages) {
            page.createList(left, top, listWidth, listHeight, itemHeight);
            page.updateOptionStates();
        }
    }

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            this.isSearchActive = false;
            this.searchResultsPage = null;
            this.buildPage();
            return;
        }

        String[] tokens = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<OptionBlock> searchResults = new ArrayList<>();

        for (OptionPage page : this.optionPages) {
            String pageLower = page.name != null ? page.name.toLowerCase(Locale.ROOT) : "";
            List<Option<?>> untitledMatches = new ArrayList<>();

            for (OptionBlock block : page.optionBlocks) {
                String blockTitle = block.title() != null ? block.title() : "";
                String blockLower = blockTitle.toLowerCase(Locale.ROOT);
                List<Option<?>> blockMatches = new ArrayList<>();

                for (Option<?> option : block.options()) {
                    if (matchesSearch(option, pageLower, blockLower, tokens)) {
                        blockMatches.add(option);
                    }
                }

                if (!blockMatches.isEmpty()) {
                    if (blockTitle.isEmpty()) {
                        untitledMatches.addAll(blockMatches);
                    } else {
                        if (!untitledMatches.isEmpty()) {
                            searchResults.add(new OptionBlock("§l" + page.name,
                                    untitledMatches.toArray(new Option<?>[0])));
                            untitledMatches.clear();
                        }
                        searchResults.add(new OptionBlock("§l" + page.name + " §7› §r" + blockTitle,
                                blockMatches.toArray(new Option<?>[0])));
                    }
                }
            }

            if (!untitledMatches.isEmpty()) {
                searchResults.add(new OptionBlock("§l" + page.name,
                        untitledMatches.toArray(new Option<?>[0])));
            }
        }

        if (searchResults.isEmpty()) {
            searchResults.add(new OptionBlock(
                    "§7" + Component.translatable("voolkan.options.search.noResults").getString(),
                    new Option<?>[0]
            ));
        }

        this.searchResultsPage = new OptionPage(
                "Search Results",
                searchResults.toArray(new OptionBlock[0])
        );
        this.searchResultsPage.createList(this.listLeft, this.listTop, this.listWidth, this.listHeight, 20);
        this.searchResultsPage.updateOptionStates();

        this.isSearchActive = true;
        this.buildPage();
    }

    private static boolean matchesSearch(Option<?> option, String pageLower, String blockLower, String[] tokens) {
        String optionName = option.getBaseName().getString().toLowerCase(Locale.ROOT);
        Component tooltipComp = option.getTooltip();
        String optionTooltip = tooltipComp != null ? tooltipComp.getString().toLowerCase(Locale.ROOT) : "";
        String displayedValue = option.getDisplayedValue().getString().toLowerCase(Locale.ROOT);

        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            boolean tokenMatched = pageLower.contains(token)
                    || blockLower.contains(token)
                    || optionName.contains(token)
                    || optionTooltip.contains(token)
                    || displayedValue.contains(token);

            if (!tokenMatched && option instanceof CyclingOption<?> cycling) {
                tokenMatched = SearchHelper.matchesAnyValue(cycling, token);
            }

            if (!tokenMatched) {
                return false;
            }
        }
        return true;
    }

    private void buildPage() {
        this.buttons.clear();
        this.pageButtons.clear();
        this.iconWidgets.clear();

        boolean savedFocused = this.searchField != null && (this.searchField.focused || this.searchField.selected);

        this.clearWidgets();

        int x = MARGIN;
        int y = 4;

        int width = this.sidebarWidth;
        int j = 0;
        for (var modEntry : this.modSettingsEntries) {
            ModIconWidget iconWidget = new ModIconWidget(modEntry.modName, modEntry.getIcon(), x, y, width, 28);
            this.iconWidgets.add(iconWidget);
            this.addWidget(iconWidget);
            y += 28;

            var pages = modEntry.getPages();
            for (OptionPage page : pages) {
                final int finalIdx = j;
                VButtonWidget widget = new VButtonWidget(x, y, width, VGuiConstants.WIDGET_HEIGHT, Component.nullToEmpty(page.name), button -> this.setOptionList(finalIdx));
                widget.setTextLayout(false, width < VGuiConstants.PAGE_BUTTON_WIDTH ? 6 : 12);
                this.buttons.add(widget);
                this.pageButtons.add(widget);
                this.addWidget(widget);

                y += VGuiConstants.WIDGET_HEIGHT;
                j++;
            }
        }

        if (!isSearchActive) {
            if (this.currentListIdx >= 0 && this.currentListIdx < this.pageButtons.size()) {
                this.pageButtons.get(this.currentListIdx).setSelected(true);
            }
            OptionPage activePage = this.optionPages.get(this.currentListIdx);
            VOptionList currentList = activePage.getOptionList();
            this.addWidget(currentList);
            activePage.updateOptionStates();
        } else if (searchResultsPage != null) {
            VOptionList searchList = searchResultsPage.getOptionList();
            this.addWidget(searchList);
            searchResultsPage.updateOptionStates();
        }

        this.addButtonsWithSearchBar();

        if (savedFocused && this.searchField != null) {
            this.searchField.setFocused(true);
            this.searchField.setSelected(true);
            this.setFocused(this.searchField);
        }

        this.updateState();
    }

    @SuppressWarnings("DuplicatedCode")
    private void addButtonsWithSearchBar() {
        int rightMargin = MARGIN;
        int btnHeight = VGuiConstants.WIDGET_HEIGHT;
        int pad = this.width < 460 ? 6 : 10;
        int effBottomPad = this.twoRowBottomBar ? 4 : 6;
        int effRowGap = this.twoRowBottomBar ? 2 : 4;
        int bottomY = this.height - btnHeight - effBottomPad;
        int upperY = this.twoRowBottomBar ? (bottomY - btnHeight - effRowGap) : bottomY;

        int doneW = this.font.width(CommonComponents.GUI_DONE) + 2 * pad;
        int x0 = this.width - doneW - rightMargin;
        this.doneButton = new VButtonWidget(x0, bottomY, doneW, btnHeight,
                CommonComponents.GUI_DONE, button -> Minecraft.getInstance().setScreen(this.parent));

        int applyW = this.font.width(Component.translatable("vulkanmod.options.buttons.apply")) + 2 * pad;
        x0 -= (applyW + VGuiConstants.WIDGET_MARGIN);
        this.applyButton = new VButtonWidget(x0, bottomY, applyW, btnHeight,
                Component.translatable("vulkanmod.options.buttons.apply"), button -> this.applyOptions());

        int undoW = this.font.width(Component.translatable("vulkanmod.options.buttons.undo")) + 2 * pad;
        x0 -= (undoW + VGuiConstants.WIDGET_MARGIN);
        this.undoButton = new VButtonWidget(x0, bottomY, undoW, btnHeight,
                Component.translatable("vulkanmod.options.buttons.undo"), button -> undo());

        Component restoreLabel = Component.translatable("voolkan.options.buttons.restoreDefaults");
        int restoreW = this.font.width(restoreLabel) + 2 * pad;

        Component copyLabel = Component.translatable("voolkan.options.buttons.copyDiagnostics");
        Component copiedLabel = Component.translatable("voolkan.options.buttons.copied");
        int copyTextW = Math.max(this.font.width(copyLabel), this.font.width(copiedLabel));

        int sidebarBottom = this.estimateSidebarBottomY();
        boolean copyUnderSidebar = !this.twoRowBottomBar && bottomY >= sidebarBottom + 4;

        int copyX;
        int copyY;
        int copyW;
        int restoreX;
        int restoreY;

        if (copyUnderSidebar) {
            copyX = MARGIN;
            copyY = bottomY;
            copyW = Math.max(this.sidebarWidth, Math.min(copyTextW + 8, this.listLeft - MARGIN - 4));
            restoreX = this.listLeft;
            restoreY = bottomY;
        } else if (this.twoRowBottomBar) {
            // Stack utility buttons cleanly on the upper row, avoiding the sidebar on ultra-short screens.
            int rowLeft = upperY < sidebarBottom + 2 ? this.listLeft : MARGIN;
            int availRowW = Math.max(80, this.width - MARGIN - rowLeft);
            copyX = rowLeft;
            copyY = upperY;
            copyW = Math.min(copyTextW + 2 * pad, Math.max(40, (availRowW - VGuiConstants.WIDGET_MARGIN) / 2));
            restoreX = copyX + copyW + VGuiConstants.WIDGET_MARGIN;
            restoreY = upperY;
            restoreW = Math.min(restoreW, Math.max(40, this.width - MARGIN - restoreX));
        } else {
            // Single row where sidebar reaches bottom (very short, wide window): place Copy + Restore cleanly left of Undo.
            copyX = this.listLeft;
            copyY = bottomY;
            copyW = copyTextW + 2 * pad;
            restoreX = copyX + copyW + VGuiConstants.WIDGET_MARGIN;
            restoreY = bottomY;
        }

        this.restoreDefaultsButton = new VButtonWidget(restoreX, restoreY, restoreW, btnHeight,
                restoreLabel, button -> this.restoreCurrentPageDefaults());
        this.copyDiagnosticsButton = new VButtonWidget(copyX, copyY, copyW, btnHeight,
                this.isDiagnosticsCopiedActive() ? copiedLabel : copyLabel,
                button -> this.copyDiagnostics());
        this.copyDiagnosticsButton.setTooltip(Component.translatable("voolkan.options.buttons.copyDiagnostics.tooltip"));

        this.buttons.add(this.applyButton);
        this.buttons.add(this.doneButton);
        this.buttons.add(this.undoButton);
        this.buttons.add(this.restoreDefaultsButton);
        this.buttons.add(this.copyDiagnosticsButton);

        this.addWidget(this.applyButton);
        this.addWidget(this.doneButton);
        this.addWidget(this.undoButton);
        this.addWidget(this.restoreDefaultsButton);
        this.addWidget(this.copyDiagnosticsButton);
        this.addWidget(this.searchField);

        if (UpdateChecker.isUpdateAvailable()) {
            assert minecraft != null;
            int updateWidth = minecraft.font.width(Component.translatable("vulkanmod.options.buttons.update_available")) + pad;
            var updateButton = new VButtonWidget(
                    this.width - rightMargin - updateWidth, 4,
                    updateWidth, btnHeight,
                    Component.translatable("vulkanmod.options.buttons.update_available").withStyle(ChatFormatting.UNDERLINE),
                    button -> Util.getPlatform().openUri("https://modrinth.com/mod/vulkanmod")
            );
            this.buttons.add(updateButton);
            this.addWidget(updateButton);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        for (GuiEventListener element : this.children()) {
            if (element.mouseClicked(event, bl)) {
                if (event.button() == 0) {
                    this.setFocused(element);
                    this.setDragging(true);
                }
                if (element != this.searchField && this.searchField != null) {
                    this.searchField.setFocused(false);
                }

                this.refreshActivePageStates();
                this.updateState();
                return true;
            }
        }

        if (this.searchField != null) {
            this.searchField.setFocused(false);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        boolean handled = super.mouseDragged(event, deltaX, deltaY);
        if (handled) {
            this.updateState();
        }
        return handled;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.setDragging(false);
        boolean handled = false;
        VOptionList currentList = this.getActiveOptionList();
        if (currentList != null && currentList.mouseReleased(event)) {
            handled = true;
        } else {
            handled = this.getChildAt(event.x(), event.y())
                          .filter(guiEventListener -> guiEventListener.mouseReleased(event))
                          .isPresent();
        }
        this.refreshActivePageStates();
        this.updateState();
        return handled;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    private VOptionList getActiveOptionList() {
        if (this.isSearchActive && this.searchResultsPage != null) {
            return this.searchResultsPage.getOptionList();
        }
        if (this.currentListIdx >= 0 && this.currentListIdx < this.optionPages.size()) {
            return this.optionPages.get(this.currentListIdx).getOptionList();
        }
        return null;
    }

    private void refreshActivePageStates() {
        if (this.isSearchActive && this.searchResultsPage != null) {
            this.searchResultsPage.updateOptionStates();
        } else if (this.currentListIdx >= 0 && this.currentListIdx < this.optionPages.size()) {
            this.optionPages.get(this.currentListIdx).updateOptionStates();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        GuiRenderer.guiGraphics = guiGraphics;
        VRenderSystem.enableBlend();

        VOptionList currentList = this.getActiveOptionList();
        if (currentList != null) {
            currentList.updateState(mouseX, mouseY);
            currentList.renderWidget(mouseX, mouseY);
        }

        for (var widget : iconWidgets) {
            widget.render(mouseX, mouseY);
        }

        this.updateDiagnosticsButtonLabel();

        for (VButtonWidget button : buttons) {
            button.updateState(mouseX, mouseY);
            button.render(mouseX, mouseY);
        }
        searchField.updateState(mouseX, mouseY);
        searchField.render(mouseX, mouseY);

        VAbstractWidget hoveredWidget = null;

        for (var b : buttons) {
            if (b.visible && b.isMouseOver(mouseX, mouseY)) {
                hoveredWidget = b;
                break;
            }
        }

        if (hoveredWidget == null && currentList != null) {
            hoveredWidget = currentList.getHoveredWidget(mouseX, mouseY);
        }

        if (hoveredWidget != null) {
            this.renderTooltip(hoveredWidget, mouseX, mouseY);
        }
    }

    private boolean isBottomBarButton(VAbstractWidget widget) {
        return widget == this.applyButton
                || widget == this.undoButton
                || widget == this.doneButton
                || widget == this.restoreDefaultsButton
                || widget == this.copyDiagnosticsButton;
    }

    private void renderTooltip(VAbstractWidget widget, int mouseX, int mouseY) {
        int maxTextWidth = this.useSideTooltip
                ? this.sideTooltipWidth
                : Math.max(120, Math.min(this.width - 20, Math.max(this.listWidth - 16, 220)));

        var list = this.getWidgetTooltip(widget, maxTextWidth);
        if (list.isEmpty()) {
            return;
        }

        int lines = list.size();
        int padding = 4;
        int boxTextWidth = GuiRenderer.getMaxTextWidth(this.font, list);
        int boxTextHeight = lines * 10;

        int x;
        int y;
        boolean floating = !this.useSideTooltip;

        if (this.useSideTooltip && !this.isBottomBarButton(widget)) {
            x = this.sideTooltipX;
            y = this.sideTooltipY;
            int maxY = Math.max(padding + 2, this.bottomButtonsTopY - boxTextHeight - padding - 4);
            y = Mth.clamp(y, padding + 2, maxY);
        } else {
            floating = true;
            int maxX = Math.max(padding + 2, this.width - boxTextWidth - padding - 4);
            x = Mth.clamp(mouseX + 10, padding + 2, maxX);

            int maxBottomY = Math.max(padding + 2, this.bottomButtonsTopY - boxTextHeight - padding - 6);
            if (this.isBottomBarButton(widget)) {
                y = maxBottomY;
            } else {
                int belowY = widget.y + widget.height + padding + 4;
                if (belowY + boxTextHeight + padding <= this.bottomButtonsTopY - 4) {
                    y = belowY;
                } else {
                    int aboveY = widget.y - boxTextHeight - padding - 4;
                    y = Mth.clamp(aboveY, padding + 2, maxBottomY);
                }
            }
        }

        float intensity = 0.05f;
        float bgAlpha = floating ? 0.92f : 0.65f;
        int color = ColorUtil.ARGB.pack(intensity, intensity, intensity, bgAlpha);
        GuiRenderer.fill(x - padding, y - padding, x + boxTextWidth + padding, y + boxTextHeight + padding, color);

        color = VGuiConstants.COLOR_RED;
        GuiRenderer.renderBorder(x - padding, y - padding, x + boxTextWidth + padding, y + boxTextHeight + padding, 1, color);

        int yOffset = 0;
        for (var text : list) {
            GuiRenderer.drawString(this.font, text, x, y + yOffset, 0xffffffff);
            yOffset += 10;
        }
    }

    private List<FormattedCharSequence> getWidgetTooltip(VAbstractWidget widget, int maxTextWidth) {
        List<FormattedCharSequence> textList = new ArrayList<>();

        if (widget instanceof OptionWidget<?> optionWidget && optionWidget.isResetHovered()) {
            Component resetTip = optionWidget.getResetButtonTooltip();
            if (resetTip != null) {
                textList.addAll(this.font.split(resetTip, maxTextWidth));
            }
            return textList;
        }

        var tooltip = widget.getTooltip();
        var impact = widget.getImpact();

        if (tooltip != null && !tooltip.getString().isEmpty()) {
            textList.addAll(this.font.split(tooltip, maxTextWidth));
        }

        if (impact != null) {
            textList.addAll(this.font.split(
                    Component.translatable("vulkanmod.options.performanceImpact", impact.component()),
                    maxTextWidth
            ));
        }

        if (widget instanceof OptionWidget<?> optionWidget && optionWidget.canResetToDefault()) {
            textList.addAll(this.font.split(
                    OptionResetControl.getRowHint().copy().withStyle(ChatFormatting.GRAY),
                    maxTextWidth
            ));
        }

        return textList;
    }

    private void updateState() {
        if (this.applyButton == null || this.undoButton == null) return;
        boolean modified = false;
        for (var page : this.optionPages) {
            modified |= page.optionChanged();
        }

        this.applyButton.active = modified;
        this.undoButton.visible = modified;

        if (this.restoreDefaultsButton != null) {
            OptionPage currentPage = (!this.isSearchActive && this.currentListIdx >= 0 && this.currentListIdx < this.optionPages.size())
                    ? this.optionPages.get(this.currentListIdx)
                    : null;
            boolean showRestore = currentPage != null && PageDefaults.hasDefaults(currentPage);
            this.restoreDefaultsButton.visible = showRestore;
            this.restoreDefaultsButton.active = showRestore && !PageDefaults.isAtDefaults(currentPage);
        }
    }

    private boolean isDiagnosticsCopiedActive() {
        return this.diagnosticsCopiedActive
                && (System.nanoTime() - this.diagnosticsCopiedStartNs) < COPIED_FEEDBACK_NS;
    }

    private void updateDiagnosticsButtonLabel() {
        if (this.copyDiagnosticsButton == null) {
            return;
        }
        if (this.diagnosticsCopiedActive
                && (System.nanoTime() - this.diagnosticsCopiedStartNs) >= COPIED_FEEDBACK_NS) {
            this.diagnosticsCopiedActive = false;
            this.copyDiagnosticsButton.setMessage(Component.translatable("voolkan.options.buttons.copyDiagnostics"));
        }
    }

    private void copyDiagnostics() {
        if (DiagnosticsReport.copyToClipboard()) {
            this.diagnosticsCopiedActive = true;
            this.diagnosticsCopiedStartNs = System.nanoTime();
            if (this.copyDiagnosticsButton != null) {
                this.copyDiagnosticsButton.setMessage(Component.translatable("voolkan.options.buttons.copied"));
            }
        }
    }

    private void restoreCurrentPageDefaults() {
        if (this.isSearchActive || this.currentListIdx < 0 || this.currentListIdx >= this.optionPages.size()) {
            return;
        }

        OptionPage currentPage = this.optionPages.get(this.currentListIdx);
        PageDefaults.restore(currentPage);
        currentPage.updateOptionStates();
        this.buildPage();
    }

    private void setOptionList(int i) {
        this.currentListIdx = i;
        this.isSearchActive = false;
        this.searchResultsPage = null;

        this.searchField.setInput("");
        this.searchField.setFocused(false);

        this.buildPage();

        if (i >= 0 && i < this.pageButtons.size()) {
            this.pageButtons.get(i).setSelected(true);
        }
    }

    private void undo() {
        for (OptionPage page : this.optionPages) {
            page.resetToOriginalState();
            page.updateOptionStates();
        }
        if (this.isSearchActive && this.searchResultsPage != null) {
            this.searchResultsPage.updateOptionStates();
        }

        this.buildPage();
    }

    private void applyOptions() {
        List<OptionPage> pages = List.copyOf(this.optionPages);
        for (var page : pages) {
            page.applyOptionChanges();
            page.updateOptionStates();
        }
        if (this.isSearchActive && this.searchResultsPage != null) {
            this.searchResultsPage.updateOptionStates();
        }

        for (var modEntry : this.modSettingsEntries) {
            modEntry.runOnApply();
        }

        this.updateState();
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.searchField != null && (this.searchField.focused || this.searchField.selected)) {
            if (this.searchField.charTyped(event)) {
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (keyEvent.hasControlDown() && keyEvent.key() == GLFW.GLFW_KEY_L) {
            this.setFocused(searchField);
            searchField.setFocused(true);
            searchField.setSelected(true);
            searchField.selectAll();
            return true;
        }

        if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE && this.isSearchActive) {
            this.isSearchActive = false;
            this.searchResultsPage = null;
            this.searchField.setInput("");
            this.searchField.setFocused(false);
            this.buildPage();
            return true;
        }

        if (this.searchField != null && (this.searchField.focused || this.searchField.selected)) {
            if (this.searchField.keyPressed(keyEvent)) {
                return true;
            }
        }

        if ((this.searchField == null || (!this.searchField.focused && !this.searchField.selected))
                && keyEvent.key() == GLFW.GLFW_KEY_P
                && keyEvent.hasShiftDown()) {
            Minecraft.getInstance().setScreen(new VideoSettingsScreen(this, Minecraft.getInstance(), Minecraft.getInstance().options));
            return false;
        }

        boolean handled = super.keyPressed(keyEvent);
        if (handled) {
            this.refreshActivePageStates();
            this.updateState();
        }
        return handled;
    }
}
