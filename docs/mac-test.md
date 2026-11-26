# macOS test checklist

One-page test pass for Apple Silicon Macs (M1 and later; Intel Macs are untested).

## Setup

1. Install **Minecraft 1.21.11** with **Fabric Loader 0.18.4** (or newer) in **Prism Launcher** or the official **Minecraft Launcher**. Both launchers add `-XstartOnFirstThread` automatically on macOS.
2. Drop `voolkan-1.5.0+1.21.11.jar` into the instance's `mods/` folder. Remove any other rendering mods (Sodium, Iris, upstream VulkanMod) for this test.
3. For a clean defaults test, delete or move any old `config/vulkanmod_settings.json` before launching.

## Checklist

1. [ ] **Boot & title screen:** Launch the game. The title screen, panorama, logo, and buttons render with normal colours and no flicker.
2. [ ] **F3 & startup capability log:** Open `logs/latest.log` and confirm the `Vulkan startup capabilities:` block (including `macOS perf opts:`) and `Swapchain:` line appear (Apple GPU, MoltenVK version, `VK_KHR_portability_subset=enabled`, `BGRA8`, 2 or 3 swapchain images, `IMMEDIATE` or `FIFO` present mode — never `MAILBOX`).
3. [ ] **World load & text-selection highlight:** Load a singleplayer world. Terrain, sky, hand, and HUD render normally. Open chat (`T`), type a few words, and press `Cmd+A` / `Shift+Left` to select text: the selection highlight is clearly visible (verifies the logic-op fallback).
4. [ ] **Retina sharpness & cursor scaling:** Check that text, crosshair, HUD, and menus look crisp on the Retina display, and that clicking buttons, inventory slots, and scrolling option labels matches the cursor position everywhere (no offset or clipped labels).
5. [ ] **Window resize:** Drag window edges, maximise/zoom, and restore several times (both on title screen and in-world). No crash, no black or stretched frame, no clipped HUD.
6. [ ] **Fullscreen modes:**
   - Press `F11` (or choose `Windowed Fullscreen` in Options → Video): enters instant borderless fullscreen without a Space animation, staying cleanly below the menu bar / camera notch on notched MacBooks (no clipped top menu text or FPS counter). Press `F11` again to return to windowed bounds.
   - In Options → Video, enable **Native macOS Fullscreen** and press Apply, then test `F11` and `Window Mode` switching: enters and exits a native macOS fullscreen Space cleanly, and switching back to Windowed or turning Native macOS Fullscreen off returns cleanly without getting stuck. Note which fullscreen mode feels better as a default.
7. [ ] **Input feel, macOS defaults & menu blur:** Verify fresh macOS defaults:
   - In Options → Other: **Low latency mode** is OFF (enables CPU/GPU overlap with per-frame depth attachments), **Render queue size** is `2`, and **Swapchain images** is `Auto` (slider capped at `3`).
   - In Options → Optimizations: **Unique opaque layer** is OFF (enables Apple TBDR early-Z / HSR) and **Indirect Draw** is OFF.
   - Turn rapidly in-world for 30 s with VSync off and VSync on, noting mouse feel / latency and any stutter.
   - Press `ESC` (pause menu) and `E` (inventory) on first world join and repeatedly while facing dense terrain: the darkened/blurred background renders cleanly with no first-open hitch or frame-rate drop.
8. [ ] **Reduce Resolution & World Resolution Scale:**
   - In Options → Video, toggle **Reduce Resolution** ON and press Apply: switches live (no restart) from full Retina to 1× logical resolution (confirm `Swapchain:` drops from e.g. `2940x1846` to `1470x923` in `logs/latest.log`), cursor hitboxes and UI scaling stay aligned, and toggling it back OFF restores full Retina cleanly. Also verify quitting with it ON and relaunching boots straight into 1× resolution.
   - In Options → Video, test **World Resolution** at `75%` and `50%`, then back to `100%`. The 3D world renders upright at lower resolution while HUD, F3, crosshair, and menus stay Retina-sharp; resizing and returning to `100%` recover cleanly.
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
2. [ ] **Low latency mode OFF (macOS default) vs ON:**
   - Default on a fresh macOS config is **Low latency mode: OFF** (`Options → Other → Low latency mode`) with **Render queue size: 2**.
   - Compare uncapped FPS/frametimes and mouse feel with Low latency mode **OFF** vs **ON** from the same viewpoint.
3. [ ] **Unique opaque layer OFF (macOS default) vs ON:**
   - Default on a fresh macOS config is **Unique opaque layer: OFF** (`Options → Optimizations → Unique opaque layer`, splits solid terrain onto the no-`discard` early-Z shader for Apple TBDR HSR).
   - Compare uncapped FPS/frametimes looking across dense terrain with Unique opaque layer **OFF** vs **ON**.
4. [ ] **Reduce Resolution OFF (full Retina, default) vs ON (1× non-Retina):**
   - Toggle **Reduce Resolution** (`Options → Video`) ON and press Apply (live, no restart). Compare uncapped FPS/frametimes and menu/inventory blur smoothness against full Retina (`OFF`) and against `World Resolution` at `65%`/`50%`.
5. [ ] **Dynamic vs Legacy render path:**
   - Default is **Dynamic** (`renderPath: "auto"`).
   - Add `-Dvoolkan.renderPath=legacy` to JVM arguments, launch into the same viewpoint, compare F3 FPS and visuals (including menu blur), then remove the flag.
6. [ ] **Optional A/B diagnostic JVM flags (if troubleshooting or isolating gains):**
   - `-Dvoolkan.disableTileOpts=true` — disables tile-GPU lazy swapchain start, deferred initial clear, and native `LOAD_OP_CLEAR` folding.
   - `-Dvoolkan.disableUnifiedDirectWrite=true` — reverts chunk/VBO/index uploads to the staged transfer path.
   - `-Dvoolkan.disableUnifiedGpuBuffers=true` — reverts `VkGpuBuffer` uniform writes and staging buffer sizing (16 MB → 64 MB) to staged transfer submits.
   - `-Dvoolkan.disableInlineImageUploads=true` — reverts pre-pass texture uploads to the separate `ImageUploadHelper` command buffer + semaphore submit.
   - `-Dvoolkan.disablePerFrameDepth=true` — reverts swapchain depth to a single shared attachment instead of per-frame-slot depth attachments.
   - `-Dvoolkan.disableLateAcquire=true` — acquires the swapchain image at frame start instead of deferring until swapchain access.
   - `-Dvoolkan.disableDescriptorCachePersist=true` — resets per-frame descriptor set caches every frame instead of persisting across frames with epoch invalidation.
   - `-Dvoolkan.disableDrawCoalescing=true` — disables empty-facing terrain draw coalescing, lazy indirect buffer allocation, zero-alloc `PipelineState` lookup, and `DynamicUniforms` pre-sizing.
   - `-Dvoolkan.disableTbdrPassOpts=true` — disables narrowed TBDR fragment barriers, mip-exact dynamic rendering, `GuiRenderer.prepare()` offscreen batching, and deferred `WorldScale` pass start / clean-depth folding.
   - `-Dvoolkan.disableMenuBlurOpts=true` — disables depth-less / `LOAD_OP_DONT_CARE` post-processing passes, menu blur PSO warm-up, deferred offscreen color clears, and stale `ScreenM.clearDepth()` skip.
   - `-Dvoolkan.disableShaderOptsMac=true` — reverts macOS shaderc target-environment options to upstream behavior.
7. [ ] **Optional MoltenVK environment variables (set in the launcher's environment settings for A/B comparison, one at a time or together):**
   - `MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS=0`
   - `MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=1`
   - Active `MVK_CONFIG_*` variables, `Tile GPU opts`, `Unified direct-write`, and `macOS perf opts` status are logged automatically in the `Vulkan startup capabilities:` block of `logs/latest.log`.

## What to send back

- `logs/latest.log` (and anything in `crash-reports/` if a crash occurred)
- `vulkaninfo` output if you have the Vulkan SDK installed (`vulkaninfo --summary` or full `vulkaninfo`)
- Mac model & chip (e.g. MacBook Air M4) and macOS version
- Short notes on: FPS & frame times (F3), input feel, Retina sharpness, and whether Borderless or Native macOS Fullscreen feels better

## Verified results (Round 3 — 2026-11-26)

- **Hardware & stack:** Apple M3 MacBook Air (16 GB unified memory), macOS Tahoe 26.6.2, MoltenVK 1.2.6 / Vulkan 1.2.261 (`VK_KHR_portability_subset=enabled`, `VK_FORMAT_B8G8R8A8_UNORM`, `VK_FORMAT_D32_SFLOAT`, 3 swapchain images, supported present modes `[FIFO, IMMEDIATE]`).
- **Build & modpack:** `voolkan-1.5.0-beta.2+1.21.11` (`40dd9b3`), 75 active mods, 16-chunk view distance. All 9 `macOS perf opts` (`unifiedGpuBuf`, `inlineImgUp`, `perFrameDepth`, `lateAcquire`, `persistDesc`, `drawCoalesce`, `tbdrPass`, `menuBlur`, `shaderTarget`), `Tile GPU opts`, and `Unified direct-write` confirmed active in `logs/latest.log`.
- **Uncapped FPS (VSync OFF / `IMMEDIATE`):**
  - **Full 2× Retina (`2940x1846`, Reduce Resolution OFF):** **180 FPS** (`5.56 ms/frame`, +50% over ~120 FPS beta 1 peak)
  - **1× Logical (`1470x923`, Reduce Resolution ON):** **250 FPS** (`4.00 ms/frame`, 2.1× beta 1 peak / +39% over full Retina)
- **Windowing, live scaling & stability:**
  - Notch-safe borderless fullscreen (`F11`) confirmed cleanly below the camera notch (`2940x1790`, zero top UI cutoff).
  - Live **Reduce Resolution** toggled cleanly without restart (`2940x1846 → 1470x923 → 2940x1846`), with all menus working and smoother overall feel.
  - Pipeline warm-up built 95 pipelines in `2705 ms` and saved a `458 KB` pipeline cache (`182` shader cache hits / `198` misses on first run).
  - Survived a 25-minute session including a stress test spawning 5,507 Chickens and 4,621 Primed TNT with zero crashes, zero Vulkan errors, and a clean exit.
- **Note for future uncapped tests:** The `Balanced`, `Quality`, and `Battery` presets re-enable VSync (`FIFO`, locking to 60 Hz even when Max Framerate is set to Unlimited). Always switch **VSync: OFF → Apply** (confirming `present mode IMMEDIATE` in `logs/latest.log`) before measuring uncapped FPS.
