# Roadmap

Base: official VulkanMod 0.6.8 for 1.21.11 (`upstream-base`). Details: see `AGENTS.md`, `docs/`.

**Naming:** finished items keep their phase.task numbers (0.1, 2.2, ...). Open items use short tags (`first-world`, `vulkaninfo`, ...), with the old number in brackets. Read `STATUS.md` first; it lists only what is open. Look up one item with `grep -n "tag" ROADMAP.md`, do not read this whole file.

## Phase 0: Tooling and setup
- [x] 0.1 Install the dev toolchain, record versions (`docs/dev-setup.md`)
- [x] 0.2 Repo setup: `upstream` remote, tag `upstream-base`, base history rebuilt on `setup/phase-0` (moving `main` waits for the owner)
- [x] 0.3 Baseline: `gradlew build` passes; `runClient` runs on lavapipe, and fails on the R7 as expected (`Unsupported extension: [VK_KHR_dynamic_rendering]`)
- [x] 0.4 Re-verify line refs (corrections reported; plan file not edited)
- [x] 0.5 Test harness: scripts, profile, summariser and docs done; real hardware vulkaninfo validated against Khronos schema via `vkprofiles`
- [x] 0.6 Hardware capabilities: real R7 350 `vulkaninfo.json` and summaries committed to `docs/gpu/r7-350/` (`0649378`)
- [x] 0.7 CI workflow (`.github/workflows/build.yml`; not yet run, repo not on GitHub Actions)
- [x] 0.8 Project docs: `AGENTS.md`, `docs/upstream.md`, `docs/conventions.md`

## Phase 1: 1.21.11 runs on the R7 350
- [x] 1.1 Runtime render path (config / `-Dvoolkan.renderPath` / device support); required set = swapchain only; gate indirect draw
- [x] 1.2 Legacy `RenderPass`: attachment indexing, clear values, mip-level render area
- [x] 1.3 Legacy `Framebuffer`: level image view + extent, cache by (pass, level, view), depth-only
- [x] 1.4 Pipelines keyed by render-pass compatibility; main-pass fallback; bind guard
- [x] 1.5 Startup capability log, readable errors, validation via system property
- [x] 1.6 Disable/repoint `UpdateChecker`; minimal rename (mod id `voolkan`, `provides vulkanmod`, icon, sources)
- [x] 1.7 Smoke checklist on real R7 350: menu, in-world terrain (774 FPS open world, 246 FPS village), UI/presets, entity rendering (golem/sheep/villagers), swapchain rebuilds on resize, pipeline cache saved (148.5 kB), clean exit
- [x] **M1 `first-world`** 1.21.11 starts and renders a world on the R7 350 on the repaired `VkRenderPass` legacy path with zero crashes; extended hour-long competitive multiplayer/PvP server session verified with smooth performance and consistent frame pacing

## Phase 2: Stability
- [x] 2.1 Port upstream fix PRs #827, #798, #872, #870 (reimplemented from the PR descriptions)
- [x] 2.2 Per-mip image layout tracking; implement `copyTextureToTexture` and real fences (verified on lavapipe menu only: validation clean on the legacy R7 profile; copy and fence paths not exercised without a world, need the R7)

## Phase 3: Speed and input delay
- [x] 3.1 Frame queue 1, low-latency mode, swapchain image-count option (verified on lavapipe only; effect on the R7 needs PresentMon, see 3.3)
- [x] 3.2 Save pipeline cache to disk (`run/voolkan/pipeline_cache.bin`; header + checksum validated; lavapipe returns a header-only blob so the load path is covered by a jshell check, real load needs the R7)
- [ ] `presentmon` (was 3.3) PresentMon captures on the R7 + summary: capture script and `docs/presentmon.md` done; **the R7 captures are for the owner**

## Phase 4: Minimal GUI
(4.5 is parked, see the Parked section.)
- [x] 4.1 \"Compatibility\" block on the Video page
- [x] 4.2 Language-file completeness check
- [x] 4.3 Presets button on the Video page: Performance (everything at the minimum, for PvP), Balanced, Quality; Preset row done (Performance also maxes the refresh rate); values in `docs/presets.md`
- [x] 4.4 Branding: replace the VulkanMod logo and name in the UI (settings screen header, window and mod list text) with the voolkan logo and name; keep upstream credit in the About text and README

## Phase 5: Release
- [x] 5.0 Publish to GitHub and release 1.0.0-beta.1 as a pre-release (owner decision; `main` = this work, tag `v1.0.0-beta.1+1.21.11`)
- [x] 5.1 LGPL pass: `LICENSE`, `COPYING`, modification notices (`tools\check-notices.ps1` clean), third-party notices, README credit, Modrinth draft (`docs/modrinth.md`, not published). Sodium-derived light code checked: it was LGPL-3.0 when VulkanMod copied it (Feb 2024), see `THIRD_PARTY_NOTICES.md`. Open (`apache-text`): the verbatim Apache-2.0 text stays out of `licenses/` because the commit hook rejects a phrase in its wording; the URL reference stays
- [ ] `upstream-pr` (was 5.2) Later: upstream PR with render-path detection + bug fixes (plan written in `docs/upstream-pr-plan.md`; no PR opened, waits for M1 and the owner)
- [x] 5.3 Full Release 1.0.0 (`1.0.0+1.21.11`): final distribution build, tag, modpack `.mrpack` export with zero saved credentials, and Modrinth listing ready

## Phase 6: Other Minecraft versions
(`port-26-1` and `port-26-2` are parked, see the Parked section.)

## Phase 7: Low-VRAM handling (last, only if the R7 shows memory problems)
(`low-vram` is parked, see the Parked section.)
Owner decisions of 2026-10-05 (recorded here, Phase 7 itself is not started): newer Minecraft versions (26.2 and later) wait for a separate plan; when `main` moves to a newer version, `1.21.11/stable` is cut so work on later versions stays possible; the 26.1 port with decompiled reference goes into the release after the first; mod versions follow `<modver>+<mc>`.

## Future: v1.5 (Post-Release)
Scoped 2026-10-18 in `docs/v1-5-plan.md` (decisions D1-D5, testing without a Mac, QoL ranking). All v1.5 build passes land on the `plan/v1-5` branch, with Windows/base improvements first (Passes 8-13) followed by macOS support and optimisation (Passes 14-16). macOS changes are gated on driver capability or macOS, so the R7 path must not change. On headless builder runners, verification is strictly `gradlew build` (zero GUI/client window execution). In-game testing on Windows is conducted manually by the owner; Mac rounds go to the two testers (M4 and M5 MacBook Airs) with exported jars.
- [x] `qol-upstream` (Pass 8) Cherry-pick upstream `0663824` (fullscreen-exclusive opt-out), `47ed9cc` (screenshot sync), `c84950a` (multi image view formats), `796aef1` (settings registry entrypoint). Built & R7-verified: screenshot copy submits before its callback reads it; fullscreen-exclusive opt-out (`DISALLOWED_EXT`) changed to opt-in (`disallowFullscreenExclusive` / `-Dvoolkan.disallowFullscreenExclusive=true`, default `false`) in `66e9675` to avoid DWM fullscreen FPS loss on the R7 350
- [x] `qol-small` (Pass 9) Memory use on F3; on-disk shader binary cache; hardware blit for texture copies where supported. Built & R7-verified: VRAM/budget lines on F3 (`VK_EXT_memory_budget` when offered, `vmaSetCurrentFrameIndex` wired per frame), SPIR-V cache in `voolkan/shader_cache/` (follows the pipeline-cache option, `-Dvoolkan.shaderCache`), format-checked blits
- [x] `frame-caps` (Pass 10) Precise frame limiter; lower cap when unfocused, hidden or minimised; platform hook for battery cap (macOS probe wired in Pass 15); "Battery" preset. Built & R7-verified: sleep-then-spin limiter (`-Dvoolkan.preciseLimiter=false` restores vanilla), Background FPS Cap (default 30, 100 ms focus poll), `dev.voolkan.power.PowerSources` hook (Battery FPS Cap hidden until a probe exists), Battery preset
- [x] `res-scale` (Pass 11) Resolution scale for the world (GUI stays sharp), simple upscale, works with post effects. Built, deep-reviewed & R7-verified: `dev.voolkan.render.WorldScale` + main-pass world mode (zero cost at 100%, bilinear `TRACY_BLIT` upscale, legacy `DONT_CARE` swapchain discard pass, scaled `Globals` UBO, explicit cross-frame depth barrier, deferred release), World Resolution option 50-100%
- [x] `pipeline-warmup` (Pass 12) Build pipelines from the saved caches during world load; needs Pass 9. Built & R7-verified: `PipelineStateStore` (`voolkan/pipeline_states.bin`) + `PipelineWarmup` on world load (static, built-in terrain/clouds, and saved states; `-Dvoolkan.pipelineWarmup=false` disables)
- [x] `qol-options` (Pass 13) Block-entity render distance; restore-defaults button per settings page. Built & R7-verified: `BlockEntityRenderDispatcherMixin` + Block Entity Distance option (16-128 blocks, default 64, beacons/offscreen untouched) and `PageDefaults` + per-page Restore Defaults button (stages defaults on built-in pages only)
- [x] `fps-opt` (Post-Pass 13) Max-FPS optimization sweep (`66e9675..a5ed6a4`), merged & R7-verified: Passes 8-13 regression fixes (opt-in FSE opt-out, 100 ms focus poll, legacy swapchain discard pass, `vmaSetCurrentFrameIndex`, early block-entity distance check), SPIR-V perf optimization (`shaderc_optimization_level_performance`) & leaner terrain/fog shaders (`USE_RGSS` variant), duplicate immediate-upload removal & Vulkan state/descriptor-set caching, shared `SectionData` slab & indirect over-copy fix & packed chunk uploads & VRAM leak fixes, async semaphore chunk uploads & `DEVICE_LOCAL | HOST_VISIBLE` BAR small streaming buffers & leaner barriers, entity/particle/frustum/QuadSorter/meshing allocation removal, and correctness review fixes
- [ ] `mac-boot` (Pass 14) macOS starts: portability enumeration + `VK_KHR_portability_subset` when offered; fix `Platform.isWindows()`; depth format order (D32_SFLOAT before D24_S8 on Apple/MoltenVK/portability); logic-op guard + alpha-blend fallback; MoltenVK/portability block in `StartupLog`; provisional `tools/profiles/apple-m.json` + `tools/run-apple-profile.ps1`. Built & deep-reviewed on `feat/v1-5-macos` (`1.5.0-beta.2+1.21.11`, also fixed semaphore-submitted command-buffer fence signaling and heap-allocated device extension queries); awaiting Mac round 1 & R7 regression check
- [ ] `mac-window` (Pass 15) Mac window and input: restore needed macOS GLFW hints; Retina scissor/extent fixes; macOS-only Native macOS Fullscreen option (borderless default, D2) with async Cocoa transition deferral; present-mode (no MAILBOX) and image-count (2-3) clamps; macOS low-latency defaults; async `pmset` macOS battery-cap probe wired into `PowerSources`; tester checklist `docs/mac-test.md`. Built & reviewed on `feat/v1-5-macos` (`1.5.0-beta.2+1.21.11`); awaiting Mac round 2 & R7 regression check
- [ ] `mac-perf` (Pass 16) Mac speed: tile-GPU lazy swapchain pass start, deferred initial clear, and native `LOAD_OP_CLEAR` folding (`-Dvoolkan.disableTileOpts=true` opt-out); mid-frame `flushCmds` semaphore & staging overflow sync fixes; zero-staging unified-memory direct writes (`AreaBuffer`, `VBO`, `AutoIndexBuffer`; `-Dvoolkan.disableUnifiedDirectWrite=true` opt-out); indirect draw OFF by default on macOS; `VK_EXT_layer_settings` + `MVK_CONFIG_*` diagnostics & Round 3 checklist in `docs/mac-test.md`. Built & deep-reviewed on `feat/v1-5-macos` (`1.5.0-beta.2+1.21.11`); awaiting Mac round 3 & R7 regression check
- Backlog ideas not chosen are in `docs/v1-5-candidates.md`. Out of v1.5 (see the plan for reasons): skipping unchanged uniform uploads, transforms-buffer pre-growth, translucent sorting, batched section uploads (wait for upstream), occlusion culling. Chunk-renderer rewrites wait for the 26.x ports

## Parked (owner decision, not being worked on)
- [ ] `settings-ui` (was 4.5) Maybe: settings UI overhaul (layout, grouping, search), scope to be decided with the owner. Skipped for now by the owner (proposal in `docs/settings-ui-proposal.md`)
- [ ] `port-26-1` (was 6.1) 26.1 port (ask upstream for source; otherwise decompiled reference; Java 25). Research and a compile probe are in `docs/mc-26-plus.md`: no public upstream 26.1 source, about 150 of 321 files fail to compile against 26.1.2. Owner decision: the port and the decompiled reference work belong to the release after the first one; version naming stays `<modver>+<mc>`
- [ ] `port-26-2` (was 6.2) 26.2: recheck vanilla Vulkan requirements; document OpenGL + Sodium for this GPU (notes in `docs/mc-26-plus.md`; the R7 checks remain). Owner decision: 26.2 and later are set aside until the owner writes a plan
- [ ] `low-vram` (was 7.1) 2 GB VRAM: VMA budget flag, out-of-memory handling, defaults, upload pacing (was 2.3)
