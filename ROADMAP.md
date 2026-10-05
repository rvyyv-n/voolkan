# Roadmap

Base: official VulkanMod 0.6.8 for 1.21.11 (`upstream-base`). Details: see `AGENTS.md`, `docs/`.

## Phase 0: Tooling and setup
- [x] 0.1 Install the dev toolchain, record versions (`docs/dev-setup.md`)
- [x] 0.2 Repo setup: `upstream` remote, tag `upstream-base`, base history rebuilt on `setup/phase-0` (moving `main` waits for the owner)
- [x] 0.3 Baseline: `gradlew build` passes; `runClient` runs on lavapipe, and fails on the R7 as expected (`Unsupported extension: [VK_KHR_dynamic_rendering]`)
- [x] 0.4 Re-verify line refs (corrections reported; plan file not edited)
- [ ] 0.5 Test harness: scripts, profile, summariser and docs done; **test world not yet generated** (needs `make-test-world.ps1 -AcceptEula`); profile is provisional until 0.6
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
- [ ] 1.7 Run the harness (forced legacy, R7 profile, lavapipe); fix validation findings; test on the R7. Done so far: main menu + window resize/maximise/minimise on lavapipe with forced legacy and the R7 profile, validation clean. **Still open:** test world (owner runs `make-test-world.ps1 -AcceptEula`), world/F3/fullscreen/post-effect checklist, real R7
- [ ] **M1** 1.21.11 starts and renders a world on the R7 350: menu → world → F3 → resize/fullscreen → post effect → quit, no validation errors under forced legacy and the R7 profile

## Phase 2: Stability
- [ ] 2.1 Port upstream fix PRs #827, #798, #872, #870
- [ ] 2.2 Per-mip image layout tracking; implement `copyTextureToTexture` and real fences
- [ ] 2.3 2 GB VRAM: VMA budget flag, out-of-memory handling, defaults, upload pacing

## Phase 3: Speed and input delay
- [ ] 3.1 Frame queue 1, low-latency mode, swapchain image-count option
- [ ] 3.2 Save pipeline cache to disk
- [ ] 3.3 PresentMon captures on the R7 + summary

## Phase 4: Minimal GUI (SKIP FOR NOW)
- [ ] 4.1 "Compatibility" block on the Video page
- [ ] 4.2 Language-file completeness check

## Phase 5: Release (private first)
- [ ] 5.1 LGPL pass: `LICENSE`, `COPYING`, modification notices, third-party notices, README credit, Modrinth draft with source link
- [ ] 5.2 Later: upstream PR with render-path detection + bug fixes

## Phase 6: Other Minecraft versions
- [ ] 6.1 26.1 port (ask upstream for source; otherwise decompiled reference; Java 25)
- [ ] 6.2 26.2: recheck vanilla Vulkan requirements; document OpenGL + Sodium for this GPU
