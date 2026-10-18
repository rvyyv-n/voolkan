# v1.5 plan: macOS and quality of life

Scope for voolkan v1.5 on Minecraft 1.21.11. It replaces the old `macos-perf` and `v1-5-qol` placeholders. Roadmap items are in `ROADMAP.md` under "Future: v1.5" (grep by tag); open work is in `STATUS.md`.

Written 2026-10-18 from three read-only scouts (code, MoltenVK, candidate features) whose main claims were checked against the code. Nothing here is built yet.

## Goals

1. **macOS through MoltenVK** (Vulkan on top of Apple's Metal), with performance close to Windows on the same class of hardware, and the usual Mac problems fixed: input lag, window and Retina handling, fullscreen.
2. **A small set of performance and QoL features** that help the R7 350 and the Mac, mostly upstream fixes we can cherry-pick.

The R7 350 on Windows stays the main target. Every macOS change is switched on only when the driver reports the matching capability or the OS is macOS, so Windows behaviour must not change.

## Decisions (defaults until the owner says otherwise)

| # | Decision | Default |
|---|---|---|
| D1 | Which Macs | Apple Silicon only (M1 and later). Intel Macs: "untested", not blocked. Our testers have M4 and M5 MacBook Airs |
| D2 | Mac fullscreen | Keep our borderless fullscreen (instant switching), plus an option for native macOS fullscreen. Testers judge which is the better default |
| D3 | Which QoL items | Four upstream cherry-picks and three small items (see "QoL ranking"). Large items wait |
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

The dev PC's only GPU is the R7 350. Validation happens in three layers:

1. **Apple profile on this PC (every pass).** A new Vulkan Profiles file, `tools/profiles/apple-m.json`, caps lavapipe to an Apple M-series feature set: no logic op, no wide lines, no geometry shader, no D24S8, no MAILBOX, BGRA swapchain, portability subset reported (using the profiles layer's portability emulation). A run script, `tools/run-apple-profile.ps1`, works like `run-r7-profile.ps1`. The validation layer then reports portability-subset violations. Values come from Apple Silicon reports on gpuinfo.org; the file is marked provisional until a tester sends a real `vulkaninfo`.
2. **R7 regression (every pass).** The usual `gradlew build` and an R7 smoke run, so Windows behaviour does not change. All macOS code is gated, so the R7 must take exactly the same paths as v1.0.
3. **Mac tester rounds (after `mac-boot`, `mac-window` and `mac-perf`).** A one-page checklist, `docs/mac-test.md`, written for non-developers: install with Prism Launcher or the official launcher plus Fabric, drop in the jar, run through menu, world, resize, fullscreen, F3 and a few minutes of play, then zip the log folder and send it back. The jar prints a capability block (MoltenVK version, portability features, memory heaps, chosen formats and present mode) so one log answers most questions. The first round also asks for `vulkaninfo` output to fix the profile.

Not used: GitHub's macOS CI runners have Apple Silicon but no reliable GPU display for running the game. CI keeps building only.

### Risky because there is no Mac here

- **Performance claims.** "Close to Windows" can only be measured by the testers. FPS and frame-time numbers come from their F3 and logs, not from us.
- **Input feel and fullscreen.** These are subjective. Tester feedback decides D2 and the latency defaults.
- **Slow turnaround.** Each Mac bug costs a tester round. Mitigation: keep Mac rounds to three, and make the startup log detailed enough to diagnose a failure from one run.
- **Profile accuracy.** The Apple profile approximates the hardware. Real driver bugs (shader translation, synchronisation) only show on a Mac.
- **Tile-GPU tuning.** Changing load/store operations touches rendering correctness on the R7 too. These changes are gated to tile GPUs and reviewed by Opus.

## Build passes

Passes continue the existing numbering (Pass 7 was the last). Each pass gets its own `pass-N` branch and pull request.

| Pass | Tag | Content | Tested by |
|---|---|---|---|
| 8 | `mac-boot` | Portability enumeration and subset (enabled only when offered); fix `isWindows()`; depth format order; logic-op guard plus text-highlight fallback; capability log block for portability/MoltenVK; Apple profile file and run script | build, Apple profile run clean, R7 smoke; Mac round 1 (starts, reaches a world, `vulkaninfo`) |
| 9 | `mac-window` | Restore needed macOS window hints; Retina and cursor scaling check; fullscreen option per D2; present-mode and image-count clamps (2-3 images, no MAILBOX); low-latency defaults on macOS; `docs/mac-test.md` | build, Apple profile, R7 smoke; Mac round 2 (resize, fullscreen, input feel) |
| 10 | `mac-perf` | Tile-friendly attachment handling on tile GPUs (don't store depth, clear instead of load at frame start); audit render-pass restarts per frame; unified-memory fast path (write directly instead of staging when memory is host-visible and device-local); MoltenVK settings through layer settings if measured to help | build, Apple profile, R7 smoke; Mac round 3 (FPS and frame times vs. R7 numbers, both render paths) |
| 11 | `qol-upstream` | Cherry-pick (`-x`) upstream `0663824` (opt out of Windows fullscreen exclusive: fixes OBS/Discord capture and Alt-Tab freezes), `47ed9cc` (screenshot sync fix), `c84950a` (multi image view formats), `796aef1` (settings registry entrypoint for other mods) | build, R7 smoke |
| 12 | `qol-small` | VRAM and memory use on the F3 screen; shader binary cache on disk (startup and first-load stutter, more so on Mac); hardware blit for texture copies where the format supports it | build, Apple profile, R7 smoke |

Order and reasons:
- `mac-boot` first, because nothing else can be tested on a Mac until it starts.
- `qol-upstream` (Pass 11) is independent of the Mac work. It can run in parallel in its own worktree any time after Pass 8, and merging it early lowers conflict risk with later upstream changes.
- `mac-perf` comes after `mac-window` because frame-time numbers mean little while windowing and present modes are still changing.
- Release prep (version, changelog, tag) stays with the owner after Pass 12.

Model use: Opus reviews Passes 8 and 10 (instance and device setup, attachment and memory changes touch correctness). Sonnet builds. Gemini runs the profile runs, log summaries and first-pass reviews.

## QoL ranking

| Rank | Candidate | Size | Benefit | Verdict |
|---|---|---|---|---|
| 1 | Upstream fullscreen-exclusive opt-out (`0663824`) | small | fixes capture and Alt-Tab on the R7 | **in** (Pass 11) |
| 2 | Upstream screenshot sync fix (`47ed9cc`) | small | removes sync errors on screenshots | **in** (Pass 11) |
| 3 | Upstream multi image view formats (`c84950a`) | small | format flexibility, helps MoltenVK | **in** (Pass 11) |
| 4 | Upstream settings registry entrypoint (`796aef1`) | small | other mods can add settings pages | **in** (Pass 11); may conflict with our Video page, resolve by hand |
| 5 | Memory use on F3 | small | watch the 2 GB ceiling and Mac unified memory | **in** (Pass 12) |
| 6 | Shader binary cache on disk | small | faster loads, fewer first-use stutters | **in** (Pass 12) |
| 7 | Hardware blit for texture copies | small | faster mipmaps and copies | **in** (Pass 12), only where the format supports blits |
| 8 | Skip unchanged uniform uploads | small | less CPU per draw | **out for now**: easy to break silently; revisit with frame-time data |
| 9 | Grow the transforms buffer ahead of time (upstream issue 721) | small | removes mid-frame reallocation stutter | **out for now**: needs a reproduction first |
| 10 | Translucent block sorting (water, glass) | medium | visual fix | **out**: meshing cost on weak CPUs; later release |
| 11 | Batched section uploads (upstream PR 778) | medium | fewer submission spikes | **out**: wait for upstream to merge it, then cherry-pick |
| 12 | Occlusion culling | large | fewer draws | **out**: too large for v1.5 |
| - | GPU selector | - | - | already exists |
| - | Raw mouse input | - | - | vanilla option already exists; only check it works on Mac |

Unchanged and still parked: `low-vram` (only if the R7 shows memory problems), `settings-ui`, `presentmon` and `upstream-pr` (skipped for later).

## Waits for the 26.x ports

- `port-26-1` and `port-26-2` themselves.
- Anything that rewrites the chunk renderer or pipeline state (occlusion culling, translucent sorting, batched uploads). The 26.1 port rewrites most of those files, so doing them now means doing them twice.
- `upstream-pr`: upstream's pipeline-state refactor would conflict.
- macOS work is not blocked by 26.x, but the 26.x ports will need a Mac tester round of their own.

## Licence

Only upstream VulkanMod cherry-picks (same licence, `git cherry-pick -x`) and our own code. Nothing from Sodium or unlicensed forks (such as Roti-Poulet/VulkanExtended). MoltenVK documentation and gpuinfo.org data are used as facts only; no code is copied.
