# v1.5 plan: macOS and quality of life

Scope for voolkan v1.5 on Minecraft 1.21.11. It replaces the old `macos-perf` and `v1-5-qol` placeholders. Roadmap items are in `ROADMAP.md` under "Future: v1.5" (grep by tag); open work is in `STATUS.md`.

Written 2026-10-18 from three read-only scouts (code, MoltenVK, candidate features) whose main claims were checked against the code. Windows/base passes 8-13 and the post-Pass-13 FPS optimization sweep (`66e9675..a5ed6a4`) are merged on `plan/v1-5` and verified in-game on the R7 350; macOS passes 14-16 are built and deep-reviewed on `feat/v1-5-macos` (`voolkan-1.5.0-beta.2+1.21.11.jar`) and await Mac tester rounds and an R7 350 regression smoke check.

## Goals

1. **macOS through MoltenVK** (Vulkan on top of Apple's Metal), with performance close to Windows on the same class of hardware, and the usual Mac problems fixed: input lag, window and Retina handling, fullscreen.
2. **A small set of performance and QoL features** that help the R7 350 and the Mac, mostly upstream fixes we can cherry-pick.

The R7 350 on Windows stays the main target. Every macOS change is switched on only when the driver reports the matching capability or the OS is macOS, so Windows behaviour must not change.

## Decisions (defaults until the owner says otherwise)

| # | Decision | Default |
|---|---|---|
| D1 | Which Macs | Apple Silicon only (M1 and later). Intel Macs: "untested", not blocked. Our testers have M4 and M5 MacBook Airs |
| D2 | Mac fullscreen | Keep our borderless fullscreen (instant switching), plus an option for native macOS fullscreen. Testers judge which is the better default |
| D3 | Which QoL items | Four upstream cherry-picks, three small items, and the extras approved on 2026-10-18 (see "QoL ranking" and "Approved extras"). Large renderer items wait |
| D4 | Render path on Mac | Dynamic rendering (the upstream path). The repaired legacy render-pass path stays available through the existing render-path option and is measured on Mac as a comparison |
| D5 | Version | Decided by the owner at release time (likely `1.5.0+1.21.11`) |

## What the code does today on a Mac

Already fine:
- The build already ships the macOS (Intel and ARM) native libraries, including the MoltenVK library.
- The screen format prefers BGRA, which is what Metal uses.
- The swapchain size comes from the framebuffer size, so Retina pixels are counted correctly.
- Triangle fans are turned into triangle lists, and wide lines are gated on the GPU feature.
- The device code recognises Apple GPUs; the GPU selector option already exists.
- The uncapped present mode tries IMMEDIATE before MAILBOX. Metal has no MAILBOX, so that order is right.

Must change:
- **Startup fails.** Instance creation never asks for portability devices (`VK_KHR_portability_enumeration` and the matching create flag), so the Vulkan loader hides MoltenVK and no GPU is found. Device creation never enables `VK_KHR_portability_subset`, which the spec requires when the device offers it.
- **Depth format.** The search tries `D24_UNORM_S8_UINT` first. Apple GPUs lack it; the fallback works but should pick `D32_SFLOAT_S8_UINT` (or `D32_SFLOAT`) on purpose.
- **Logic op.** Apple GPUs have no logic-op blending. The feature is only enabled when reported, but pipelines can still request it. It needs a guard, and a fallback for the text-selection highlight that vanilla draws with it.
- **Window hints.** A window mixin swallows every vanilla GLFW window hint. Check which macOS hints matter (Retina framebuffer, Cocoa frame name) and set them again.
- **Fullscreen.** Our borderless fullscreen moves a frameless window over the monitor. On macOS this bypasses Spaces and the notch area. See D2.
- **Small bug.** `Platform.isWindows()` returns true on Linux. Nothing calls it today; fix it before Mac code starts using platform checks.

Not a problem, despite first impressions:
- Memory type selection. Apple Silicon has one heap that is device-local and host-visible at the same time. The exact-match search misses it and falls back to "device mappable" memory, which is the right choice for unified memory. Later tuning can skip staging copies there (see `mac-perf`).

## What MoltenVK does differently (Apple Silicon)

- Reports Vulkan 1.2 by default, with dynamic rendering, synchronization2, timeline semaphores and extended dynamic state available. Check the MoltenVK version in the LWJGL 3.3.3 natives on the first Mac run (the startup log will print it).
- Missing: geometry shaders, wide lines, logic op, 64-bit floats in shaders, point polygon mode, sampler mip LOD bias, `D24_UNORM_S8_UINT`.
- Swapchain: BGRA8 (UNORM or SRGB) only; FIFO and IMMEDIATE present modes (no MAILBOX); 2 or 3 images.
- GPU is tile-based. Loading or storing attachments that are not needed, clearing in a separate pass, and restarting render passes within a frame all cost far more than on the R7.
- Shaders are translated to Metal and compiled at pipeline creation, so pipeline stalls are worse. MoltenVK keeps compiled pipelines through the pipeline cache we already save to disk.
- Settings such as synchronous queue submits and argument buffers come from `MVK_CONFIG_*` environment variables or from `VK_EXT_layer_settings`. The game cannot set environment variables for itself, so any tuning goes through layer settings, or is documented for testers.
- Input lag on Macs comes from the display queueing frames (blocking on the next drawable) and from pointer acceleration. Our existing low-latency mode and frame-queue option, plus vanilla's raw mouse input option, are the tools.
- LWJGL on macOS needs `-XstartOnFirstThread`. The official launcher and Prism Launcher add it; testers must use one of them.

## How we validate without a Mac

The dev PC's only GPU is the R7 350. Validation happens in distinct layers:

0. **Headless builder runners (build passes).** Automated builder agents running in headless or multi-project environments must NOT run `runClient`, launch interactive worlds, or spawn GLFW/GUI windows. Verification on the builder machine is strictly non-GUI: `.\gradlew build` must pass cleanly with zero compiler warnings or errors.
1. **Apple profile (offline/profile testing).** A new Vulkan Profiles file, `tools/profiles/apple-m.json`, caps lavapipe to an Apple M-series feature set: no logic op, no wide lines, no geometry shader, no D24S8, no MAILBOX, BGRA swapchain, portability subset reported (using the profiles layer's portability emulation). A run script, `tools/run-apple-profile.ps1`, works like `run-r7-profile.ps1`. The validation layer then reports portability-subset violations. Values come from Apple Silicon reports on gpuinfo.org; the file is marked provisional until a tester sends a real `vulkaninfo`.
2. **R7 regression & in-game testing.** The usual `gradlew build` and in-game R7 smoke testing are conducted manually by the repo owner on the real R7 350 machine, ensuring Windows behaviour does not change. All macOS code is gated, so the R7 must take exactly the same paths as v1.0.
3. **Mac tester rounds (after `mac-boot`, `mac-window`, and `mac-perf`).** A one-page checklist, `docs/mac-test.md`, written for non-developers: install with Prism Launcher or the official launcher plus Fabric, drop in the jar, run through menu, world, resize, fullscreen, F3 and a few minutes of play, then zip the log folder and send it back. The jar prints a capability block (MoltenVK version, portability features, memory heaps, chosen formats and present mode) so one log answers most questions. The first round also asks for `vulkaninfo` output to fix the profile. Because Passes 8-13 land before macOS work, Mac testers get all QoL/perf features baked in from Round 1. Tested manually by sending exported mod jars to the Mac tester with Apple Silicon hardware.

Not used: GitHub's macOS CI runners have Apple Silicon but no reliable GPU display for running the game. CI keeps building only.

### Risky because there is no Mac here

- **Performance claims.** "Close to Windows" can only be measured by the testers. FPS and frame-time numbers come from their F3 and logs, not from us.
- **Input feel and fullscreen.** These are subjective. Tester feedback decides D2 and the latency defaults.
- **Slow turnaround.** Each Mac bug costs a tester round. Mitigation: keep Mac rounds to four, and make the startup log detailed enough to diagnose a failure from one run.
- **Profile accuracy.** The Apple profile approximates the hardware. Real driver bugs (shader translation, synchronisation) only show on a Mac.
- **Tile-GPU tuning.** Changing load/store operations touches rendering correctness on the R7 too. These changes are gated to tile GPUs and reviewed by Opus.

## Build passes

Passes continue the existing numbering (Pass 7 was the last). All v1.5 passes land on the `plan/v1-5` branch (Windows/base passes first, then macOS).

| Pass | Tag | Content | Tested by |
|---|---|---|---|
| 8 | `qol-upstream` | Cherry-pick (`-x`) upstream `0663824` (opt out of Windows fullscreen exclusive: opt-in via `disallowFullscreenExclusive` / `-Dvoolkan.disallowFullscreenExclusive=true`, default `false` to preserve R7 fullscreen FPS), `47ed9cc` (screenshot sync fix), `c84950a` (multi image view formats), `796aef1` (settings registry entrypoint for other mods) | build, R7 smoke (verified) |
| 9 | `qol-small` | VRAM and memory use on the F3 screen; shader binary cache on disk (startup and first-load stutter, more so on Mac); hardware blit for texture copies where the format supports it | build, R7 smoke (verified) |
| 10 | `frame-caps` | Precise frame limiter (sleep then short spin, timed to the present); lower frame cap when the window is unfocused, hidden or minimised; platform hook for optional battery cap (macOS probe wired in Pass 15); "Battery" entry in the Presets row | build, R7 smoke with frame times (verified) |
| 11 | `res-scale` | Resolution scale: draw the world below screen resolution and scale it up with a simple filter; GUI stays at full resolution; works with post effects | build, R7 smoke (verified) |
| 12 | `pipeline-warmup` | Build pipelines for known states from the saved caches during world load instead of on first use (needs Pass 9) | build, R7 smoke (verified) |
| 13 | `qol-options` | Separate render distance for block entities (chests, signs, banners); restore-defaults button on each voolkan settings page | build, R7 smoke (verified) |
| 14 | `mac-boot` | Portability enumeration and subset (enabled only when offered); fix `isWindows()`; depth format order; logic-op guard plus alpha-blend fallback; capability log block for portability/MoltenVK; Apple profile file and run script | built & deep-reviewed on `feat/v1-5-macos` (`1.5.0-beta.2+1.21.11`); awaiting Mac round 1 & R7 smoke |
| 15 | `mac-window` | Restore needed macOS window hints; Retina and cursor scaling check; fullscreen option per D2 (borderless default + macOS-only Native macOS Fullscreen); present-mode and image-count clamps (2-3 images, no MAILBOX); low-latency defaults on macOS; wire macOS battery-cap probe from Pass 10; `docs/mac-test.md` | built & reviewed on `feat/v1-5-macos` (`1.5.0-beta.2+1.21.11`); awaiting Mac round 2 & R7 smoke |
| 16 | `mac-perf` | Tile-GPU lazy swapchain start, deferred initial clear, and native `LOAD_OP_CLEAR` folding on tile GPUs; mid-frame semaphore/staging sync fixes; unified-memory direct writes (`AreaBuffer`, `VBO`, `AutoIndexBuffer`); indirect draw OFF by default on macOS; `VK_EXT_layer_settings`/`MVK_CONFIG_*` diagnostics & Round 3 A/B checklist in `docs/mac-test.md` | built & deep-reviewed on `feat/v1-5-macos` (`1.5.0-beta.2+1.21.11`); awaiting Mac round 3 & R7 smoke |

Order and reasons:
- **Windows/base first (Passes 8-13):** `qol-upstream` (Pass 8) goes first so cherry-picks land cleanly before later edits. `qol-small` (Pass 9) through `qol-options` (Pass 13) can all be verified directly on the R7 350 without waiting on Mac tester turnarounds. `pipeline-warmup` (Pass 12) depends on the shader binary cache from Pass 9. `res-scale` (Pass 11) is the largest base pass and gets a deep review.
- **macOS second (Passes 14-16):** `mac-boot` (Pass 14) gets macOS starting with all base QoL/perf features and the post-Pass-13 FPS optimizations (`66e9675..a5ed6a4`) already included. `mac-window` (Pass 15) handles windowing, present modes, input, and the macOS battery probe. `mac-perf` (Pass 16) comes last so frame-time measurements happen on settled windowing and present modes.
  - **Integration notes from `66e9675..a5ed6a4`:** `MemoryTypes` already places small streaming buffers in `DEVICE_LOCAL | HOST_VISIBLE` (BAR) memory with automatic fallback (coordinate Pass 16 unified-memory writes with it); `DefaultMainPass` / `WorldScale` uses a legacy `DONT_CARE` swapchain discard pass when returning from scaled world (keep Pass 16 tile-GPU attachment handling aligned on both paths); `Synchronization` / `UploadManager` / `Renderer` use async semaphores for chunk uploads and `DescriptorSets` caches descriptor sets across draws (verify MoltenVK compatibility during Pass 14/16 reviews).
- Release prep (version, changelog, tag) stays with the owner after Pass 16.

Review gates: Passes 11, 14, and 16 (resolution scaling, instance/device setup, and tile-GPU attachment/memory changes) get a deep correctness review before moving on.

## QoL ranking

| Rank | Candidate | Size | Benefit | Verdict |
|---|---|---|---|---|
| 1 | Upstream fullscreen-exclusive opt-out (`0663824`) | small | fixes capture and Alt-Tab on the R7 | **in** (Pass 8) |
| 2 | Upstream screenshot sync fix (`47ed9cc`) | small | removes sync errors on screenshots | **in** (Pass 8) |
| 3 | Upstream multi image view formats (`c84950a`) | small | format flexibility, helps MoltenVK | **in** (Pass 8) |
| 4 | Upstream settings registry entrypoint (`796aef1`) | small | other mods can add settings pages | **in** (Pass 8); may conflict with our Video page, resolve by hand |
| 5 | Memory use on F3 | small | watch the 2 GB ceiling and Mac unified memory | **in** (Pass 9) |
| 6 | Shader binary cache on disk | small | faster loads, fewer first-use stutters | **in** (Pass 9) |
| 7 | Hardware blit for texture copies | small | faster mipmaps and copies | **in** (Pass 9), only where the format supports blits |
| 8 | Skip unchanged uniform uploads | small | less CPU per draw | **out for now**: easy to break silently; revisit with frame-time data |
| 9 | Grow the transforms buffer ahead of time (upstream issue 721) | small | removes mid-frame reallocation stutter | **out for now**: needs a reproduction first |
| 10 | Translucent block sorting (water, glass) | medium | visual fix | **out**: meshing cost on weak CPUs; later release |
| 11 | Batched section uploads (upstream PR 778) | medium | fewer submission spikes | **out**: wait for upstream to merge it, then cherry-pick |
| 12 | Occlusion culling | large | fewer draws | **out**: too large for v1.5 |
| - | GPU selector | - | - | already exists |
| - | Raw mouse input | - | - | vanilla option already exists; only check it works on Mac |

## Approved extras

Chosen on 2026-10-18 from `v1-5-candidates.md`: resolution scale (P1, Pass 11), background and battery frame caps (P2, Pass 10), pipeline warm-up (P3, Pass 12), precise frame limiter (P5, Pass 10), block-entity distance (P6, Pass 13), indirect draw on Mac as a test item (P8, Pass 16), Mac battery preset (Q4, Pass 10), restore defaults per page (Q6, Pass 13). Ideas not chosen stay in the candidates file as backlog.

Testers report frame rates with vanilla's F3 screen and its frame-time chart; a voolkan overlay was not chosen.

Unchanged and still parked: `settings-ui` (first in the parked queue, along with owner-specified QoL features); `presentmon` (skipped for later).

## Waits for the 26.x ports

- `port-26-1` and `port-26-2` themselves (`port-26-2` is parked at the very end).
- Anything that rewrites the chunk renderer or pipeline state (occlusion culling, translucent sorting, batched uploads). The 26.1 port rewrites most of those files, so doing them now means doing them twice.
- macOS work is not blocked by 26.x, but the 26.x ports will need a Mac tester round of their own.

## Licence

Only upstream VulkanMod cherry-picks (same licence, `git cherry-pick -x`) and our own code. Nothing from Sodium or unlicensed forks (such as Roti-Poulet/VulkanExtended). MoltenVK documentation and gpuinfo.org data are used as facts only; no code is copied.
