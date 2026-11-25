# macOS test checklist

One-page test pass for Apple Silicon Macs (M1 and later; Intel Macs are untested).

## Setup

1. Install **Minecraft 1.21.11** with **Fabric Loader 0.18.4** (or newer) in **Prism Launcher** or the official **Minecraft Launcher**. Both launchers add `-XstartOnFirstThread` automatically on macOS.
2. Drop `voolkan-1.5.0-beta.2+1.21.11.jar` into the instance's `mods/` folder. Remove any other rendering mods (Sodium, Iris, upstream VulkanMod) for this test.
3. For a clean defaults test, delete or move any old `config/vulkanmod_settings.json` before launching.

## Checklist

1. [ ] **Boot & title screen:** Launch the game. The title screen, panorama, logo, and buttons render with normal colours and no flicker.
2. [ ] **F3 & startup capability log:** Open `logs/latest.log` and confirm the `Vulkan startup capabilities:` block and `Swapchain:` line appear (Apple GPU, MoltenVK version, `VK_KHR_portability_subset=enabled`, `BGRA8`, 2 or 3 swapchain images, `IMMEDIATE` or `FIFO` present mode — never `MAILBOX`).
3. [ ] **World load & text-selection highlight:** Load a singleplayer world. Terrain, sky, hand, and HUD render normally. Open chat (`T`), type a few words, and press `Cmd+A` / `Shift+Left` to select text: the selection highlight is clearly visible (verifies the logic-op fallback).
4. [ ] **Retina sharpness & cursor scaling:** Check that text, crosshair, HUD, and menus look crisp on the Retina display, and that clicking buttons, inventory slots, and scrolling option labels matches the cursor position everywhere (no offset or clipped labels).
5. [ ] **Window resize:** Drag window edges, maximise/zoom, and restore several times (both on title screen and in-world). No crash, no black or stretched frame, no clipped HUD.
6. [ ] **Fullscreen modes:**
   - Press `F11` (or choose `Windowed Fullscreen` in Options → Video): enters instant borderless fullscreen without a Space animation, staying cleanly below the menu bar / camera notch on notched MacBooks (no clipped top menu text or FPS counter). Press `F11` again to return to windowed bounds.
   - In Options → Video, enable **Native macOS Fullscreen** and press Apply, then test `F11` and `Window Mode` switching: enters and exits a native macOS fullscreen Space cleanly, and switching back to Windowed or turning Native macOS Fullscreen off returns cleanly without getting stuck. Note which fullscreen mode feels better as a default.
7. [ ] **Input feel & low-latency defaults:** In Options → Other, verify fresh macOS defaults: **Low latency mode** is ON, **Render queue size** is `2`, and **Swapchain images** is `Auto` (slider capped at `3`). Turn rapidly in-world for 30 s with VSync off and VSync on, and note mouse feel / latency and any stutter.
8. [ ] **World Resolution Scale:** In Options → Video, test **World Resolution** at `75%` and `50%`, then back to `100%`. The 3D world renders upright at lower resolution while HUD, F3, crosshair, and menus stay Retina-sharp; resizing and returning to `100%` recover cleanly.
9. [ ] **Battery FPS Cap (MacBooks):**
   - Verify **Battery FPS Cap** is visible in Options → Video (hidden on desktop Macs without a battery).
   - Select **Preset → Battery** (stages 60 FPS cap / 60 Battery FPS Cap / 15 Background FPS Cap), press Apply (or set Battery FPS Cap to `40` manually with VSync off).
   - Unplug power: within ~5–10 s, FPS drops to the Battery FPS Cap. Plug AC back in: within ~5–10 s, FPS returns to the normal limit.
10. [ ] **Both render paths:** Default is Dynamic (`renderPath: "auto"`). Optionally test Legacy by adding `-Dvoolkan.renderPath=legacy` to JVM arguments, loading the same world, and comparing F3 FPS and visuals (remove the flag afterwards).
11. [ ] **Clean quit:** Quit to title, then quit the game. Process exits cleanly with no hang and no crash report in `crash-reports/`.

## Round 3: performance & tuning comparisons

Once the base checklist above passes, run these quick A/B comparisons from the **same world and viewpoint** (stand still for ~15 s per run, **VSync OFF** — confirm `present mode IMMEDIATE` in `logs/latest.log` so a 60 Hz display doesn't mask differences — same render distance, and record F3 FPS / frame times):

1. [ ] **Indirect Draw OFF (macOS default) vs ON:**
   - Default on a fresh macOS config is **Indirect Draw: OFF** (`Options → Optimizations → Indirect Draw`, also restored by **Restore Defaults**).
   - Measure FPS/frametimes with Indirect Draw **OFF**, then toggle it **ON**, press Apply, and measure the exact same viewpoint. Note whether ON is faster, slower, or causes any visual issues.
2. [ ] **Dynamic vs Legacy render path:**
   - Default is **Dynamic** (`renderPath: "auto"`).
   - Add `-Dvoolkan.renderPath=legacy` to JVM arguments, launch into the same viewpoint, compare F3 FPS and visuals, then remove the flag.
3. [ ] **Optional A/B diagnostic JVM flags (if troubleshooting or measuring gains):**
   - `-Dvoolkan.disableTileOpts=true` — disables tile-GPU lazy swapchain start, deferred initial clear, and native `LOAD_OP_CLEAR` folding.
   - `-Dvoolkan.disableUnifiedDirectWrite=true` — reverts chunk/VBO/index uploads to the staged transfer path.
4. [ ] **Optional MoltenVK environment variables (set in the launcher's environment settings for A/B comparison, one at a time or together):**
   - `MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS=0`
   - `MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=1`
   - Active `MVK_CONFIG_*` variables, `Tile GPU opts`, and `Unified direct-write` status are logged automatically in the `Vulkan startup capabilities:` block of `logs/latest.log`.

## What to send back

- `logs/latest.log` (and anything in `crash-reports/` if a crash occurred)
- `vulkaninfo` output if you have the Vulkan SDK installed (`vulkaninfo --summary` or full `vulkaninfo`)
- Mac model & chip (e.g. MacBook Air M4) and macOS version
- Short notes on: FPS & frame times (F3), input feel, Retina sharpness, and whether Borderless or Native macOS Fullscreen feels better
