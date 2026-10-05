# Roadmap

Base: official VulkanMod 0.6.8 for 1.21.11 (`upstream-base`). Details: see `AGENTS.md`, `docs/`.

## Phase 0: Tooling and setup
- [x] 0.1 Install the dev toolchain, record versions (`docs/dev-setup.md`)
- [x] 0.2 Repo setup: `upstream` remote, tag `upstream-base`, base history rebuilt on `setup/phase-0` (moving `main` waits for the owner)
- [x] 0.3 Baseline: `gradlew build` passes; `runClient` runs on lavapipe, and fails on the R7 as expected (`Unsupported extension: [VK_KHR_dynamic_rendering]`)
- [x] 0.4 Re-verify line refs (corrections reported; plan file not edited)
- [ ] 0.5 Test harness: scripts, profile, summariser and docs done; the owner made a world by hand (`run/saves`), `make-test-world.ps1` not used; profile is provisional until 0.6
- [ ] 0.6 Owner: run `vulkaninfo` on the R7 and commit it to `docs/gpu/r7-350/`
- [x] 0.7 CI workflow (`.github/workflows/build.yml`; not yet run, repo not on GitHub Actions)
- [x] 0.8 Project docs: `AGENTS.md`, `docs/upstream.md`, `docs/conventions.md`

## Phase 1: 1.21.11 runs on the R7 350
- [x] 1.1 Runtime render path (config / `-Dvoolkan.renderPath` / device support); required set = swapchain only; gate indirect draw
- [x] 1.2 Legacy `RenderPass`: attachment indexing, clear values, mip-level render area
- [x] 1.3 Legacy `Framebuffer`: level image view + extent, cache by (pass, level, view), depth-only
- [x] 1.4 Pipelines keyed by render-pass compatibility; main-pass fallback; bind guard
- [x] 1.5 Startup capability log, readable errors, validation via system property
- [x] 1.6 Disable/repoint `UpdateChecker`; minimal rename (mod id `voolkan`, `provides vulkanmod`, icon, sources)
- [ ] 1.7 Run the harness (forced legacy, R7 profile, lavapipe); fix validation findings; test on the R7. Done so far: main menu + window resize/maximise/minimise on lavapipe with forced legacy and the R7 profile, validation clean. Owner entered a world on lavapipe without a crash (colours correct after the BGRA swapchain fix, 2.2). **Still open:** the rest of the world/F3/fullscreen/post-effect checklist, and the real R7: it does not appear in the GPU selector, so a run on it has not happened yet (find out why first)
- [ ] **M1** 1.21.11 starts and renders a world on the R7 350: menu → world → F3 → resize/fullscreen → post effect → quit, no validation errors under forced legacy and the R7 profile

## Phase 2: Stability
- [x] 2.1 Port upstream fix PRs #827, #798, #872, #870 (reimplemented from the PR descriptions)
- [x] 2.2 Per-mip image layout tracking; implement `copyTextureToTexture` and real fences (verified on lavapipe menu only: validation clean on the legacy R7 profile; copy and fence paths not exercised without a world, need the R7)

## Phase 3: Speed and input delay
- [x] 3.1 Frame queue 1, low-latency mode, swapchain image-count option (verified on lavapipe only; effect on the R7 needs PresentMon, see 3.3)
- [x] 3.2 Save pipeline cache to disk (`run/voolkan/pipeline_cache.bin`; header + checksum validated; lavapipe returns a header-only blob so the load path is covered by a jshell check, real load needs the R7)
- [ ] 3.3 PresentMon captures on the R7 + summary: capture script and `docs/presentmon.md` done; **the R7 captures are for the owner**

## Phase 4: Minimal GUI
- [x] 4.1 "Compatibility" block on the Video page
- [x] 4.2 Language-file completeness check
- [ ] 4.3 Presets button on the Video page: Performance (everything at the minimum, for PvP), Balanced, Quality; exact values per preset to be decided
- [ ] 4.4 Branding: replace the VulkanMod logo and name in the UI (settings screen header, window and mod list text) with the voolkan logo and name; keep upstream credit in the About text and README
- [ ] 4.5 Maybe: settings UI overhaul (layout, grouping, search), scope to be decided with the owner

## Phase 5: Release (private first)
- [ ] 5.1 LGPL pass: `LICENSE`, `COPYING`, modification notices, third-party notices, README credit, Modrinth draft with source link
- [ ] 5.2 Later: upstream PR with render-path detection + bug fixes

## Phase 6: Other Minecraft versions
- [ ] 6.1 26.1 port (ask upstream for source; otherwise decompiled reference; Java 25)
- [ ] 6.2 26.2: recheck vanilla Vulkan requirements; document OpenGL + Sodium for this GPU

## Phase 7: Low-VRAM handling (last, only if the R7 shows memory problems)
- [ ] 7.1 2 GB VRAM: VMA budget flag, out-of-memory handling, defaults, upload pacing (was 2.3)
