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
Rudimentary outline. Detailed scoping and planning will be expanded after v1.0 is fully deployed and released.
- [ ] `macos-perf` macOS (MoltenVK) support and performance tuning: Metal surface/format quirks (BGRA swapchain), render pass layout constraints, memory heaps
- [ ] `v1-5-qol` Additional performance and QoL features (to be evaluated and scoped after v1.0 release)

## Parked (owner decision, not being worked on)
- [ ] `settings-ui` (was 4.5) Maybe: settings UI overhaul (layout, grouping, search), scope to be decided with the owner. Skipped for now by the owner (proposal in `docs/settings-ui-proposal.md`)
- [ ] `port-26-1` (was 6.1) 26.1 port (ask upstream for source; otherwise decompiled reference; Java 25). Research and a compile probe are in `docs/mc-26-plus.md`: no public upstream 26.1 source, about 150 of 321 files fail to compile against 26.1.2. Owner decision: the port and the decompiled reference work belong to the release after the first one; version naming stays `<modver>+<mc>`
- [ ] `port-26-2` (was 6.2) 26.2: recheck vanilla Vulkan requirements; document OpenGL + Sodium for this GPU (notes in `docs/mc-26-plus.md`; the R7 checks remain). Owner decision: 26.2 and later are set aside until the owner writes a plan
- [ ] `low-vram` (was 7.1) 2 GB VRAM: VMA budget flag, out-of-memory handling, defaults, upload pacing (was 2.3)
