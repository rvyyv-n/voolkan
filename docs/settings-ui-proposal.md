# Settings UI overhaul (`settings-ui`): implemented on `feat/v1-5-qol-ui`

Implemented in Pass 17 alongside the remaining v1.5 QoL and telemetry bundle. Keeps upstream's layout structure intact so future upstream cherry-picks remain clean while addressing usability, search, defaults, and compact-screen scaling.

## Implemented scope

1. **Regrouped options by purpose (`Options.java`, `PageDefaults.java`, `Preset.java`):**
   - **Video:** `Preset` row (now including **Competitive** alongside Performance, Balanced, Quality, and Battery), **Display** (Window Mode, macOS Native Fullscreen / Reduce Resolution, Resolution, Refresh Rate, World Resolution), dedicated **Latency** block (VSync, Adaptive VSync when supported, Max Framerate, Inactivity FPS Limit, Background FPS Cap, Battery FPS Cap when supported, Low Latency Mode, Render Queue Size, Swapchain Images), **Interface** (GUI Scale, Brightness, Attack Indicator, Autosave Indicator), and read-only **Compatibility** info.
   - **Graphics:** titled blocks (**Chunks & Distance**, **Visuals & Effects** including Particle Limit, **Entities & Environment**).
   - **Optimizations:** **Rendering & Uploads** (including Chunk Upload Budget).
   - **Other:** **Engine & Telemetry** (Chunk Builder Threads, Texture Animations, F3 Frame-Time & 1% Low) and **System & Device** (Use Wayland, Device Selector).
   - `PageDefaults` and `Preset` use two-pass dependent-option handling so linked options (VSync/Adaptive VSync, Texture Filtering/Max Anisotropy) stay consistent across Windows and macOS defaults, and `Preset` resets cleanly back to `Custom` after Apply.
2. **Per-option "Reset to Default" (`dev.voolkan.gui.OptionResetControl`, `OptionWidget.java`, `PageDefaults.java`):**
   - Renders a compact inline reset icon (font-independent pixel arrow) next to any active option differing from its platform-aware default, plus right-click reset anywhere on the row.
   - Displays `"Reset to default: <value>"` on icon hover and a subtle reset hint on row hover.
3. **Smarter Search (`VOptionScreen.performSearch`, `VTextInputWidget.java`):**
   - Multi-token matching across page names, block titles, unformatted base option names, tooltips, displayed values, and all cycling values.
   - Results show `"Page › Block"` headers, preserve the active page tab when search is cleared or dismissed with Escape, keep Apply/Undo/Reset consistent, and support spaces, symbols, clipboard paste, and macOS `Cmd` shortcuts via `charTyped` and `InputWithModifiers`.
4. **Scaling & compact-height fixes (`VOptionScreen.java`, `VOptionList.java`, `OptionWidget.java`, `VButtonWidget.java`):**
   - Responsive sidebar width, single-row / two-row bottom bar preventing any overlap among `Copy Diagnostics`, `Restore Defaults`, `Undo`, `Apply`, and `Done` down to small heights and large GUI scales.
   - Clamped side-panel and floating tooltips that never clip off-screen or cover buttons.
   - Live-offset list hit-testing, widened scrollbar drag area, safe compact-height scrollbar clamping, bottom scroll padding so Compatibility is always reachable, hoverable inactive/read-only rows for tooltips, and clipped/scrolling labels on narrow widths.

## Still intentionally out of scope

A full visual redesign (custom themes, new screen paradigm, controller navigation): high churn against upstream's GUI code for little gain.
