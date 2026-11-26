# Modrinth listing (draft, not published)

Draft text for a Modrinth project page. Nothing has been uploaded. Publishing is the owner's decision (see ROADMAP 5.0 and 5.1). Check the facts below against the release you actually publish.

## Project fields

| Field | Value |
|---|---|
| Name | voolkan |
| Slug (suggested) | `voolkan` |
| Summary (max 256 chars) | High-performance Vulkan renderer delivering FPS gains, low latency for old and new systems alike. |
| Categories | Optimization |
| Environment | Client |
| Loader | Fabric (Loader 0.14.14 or newer) |
| Game version | 1.21.11 |
| Licence | LGPL-3.0-only (`LGPL-3.0-only` in the licence field; link `https://github.com/rvyyv-n/voolkan/blob/main/LICENSE`) |
| Source | `https://github.com/rvyyv-n/voolkan` |
| Issues | `https://github.com/rvyyv-n/voolkan/issues` |
| Wiki / Discord / donations | leave empty (no voolkan Discord or donation page; do not link VulkanMod's) |
| Icon | `icon.png` in the repo root (the voolkan logo) |
| Release channel | Release for `1.5.0+1.21.11` |

## Description (Markdown)

```markdown
# voolkan

voolkan is an **unofficial, high-performance fork of [VulkanMod](https://github.com/xCollateral/VulkanMod)** by xCollateral and contributors, engineered for maximum frame rates, fluid frame pacing, and broad hardware support across Windows and Apple Silicon macOS. It is not affiliated with or endorsed by the VulkanMod project, Mojang, Microsoft or FabricMC. All credit for the Vulkan renderer goes to the VulkanMod authors; please support them.

**Source code: https://github.com/rvyyv-n/voolkan** (LGPL-3.0). Changes against upstream are listed in the git history (`git log upstream-base..main`), and every changed upstream file carries a modification notice.

## Why voolkan

Vanilla Minecraft's OpenGL pipeline is limited by single-threaded CPU bottlenecks and driver overhead. Upstream VulkanMod 0.6.8 requires `VK_KHR_dynamic_rendering` (leaving older GPUs behind) and lacks macOS MoltenVK portability support.

**voolkan solves all three:**
1. **Legacy GPU Support (Windows):** Repairs and optimizes the classic `VkRenderPass` pipeline so older Vulkan 1.2 GPUs lacking `VK_KHR_dynamic_rendering` (such as AMD Radeon R7 350 / GCN 1) get full Vulkan acceleration instead of crashing at launch.
2. **Apple Silicon macOS Support (MoltenVK):** Adds full Vulkan portability support, tile-GPU (TBDR) pass optimizations, unified-memory direct writes, notch-safe borderless and native macOS fullscreen, automatic battery FPS cap, and live Retina / 1× **Reduce Resolution** switching.
3. **Engine Performance, Pacing & QoL:** Delivers major FPS gains, low-latency frame pacing, shader/pipeline warm-up, built-in diagnostics, and an overhauled Settings UI.

## Key Features

- **Older GPU Fallback & Core Engine Optimizations:** Automatic `VkRenderPass` path, Early-Z depth pre-pass, descriptor/state caching, async chunk uploads, BAR streaming buffers, and hardware texture blits.
- **Apple Silicon / MoltenVK Tuning:** Verified on an M3 MacBook Air at **180 FPS** full 2× Retina and **250 FPS** with live **Reduce Resolution** ON (75-mod pack, 16-chunk view distance, zero Vulkan errors).
- **Latency & Pacing Controls:** Dedicated **Latency** settings group with precise sleep-then-spin frame limiting, Adaptive VSync (`FIFO_RELAXED` where supported), configurable render queue and swapchain images, background and battery FPS caps, and **Performance / Balanced / Quality / Battery / Competitive** presets.
- **Scalability & Burst Smoothing:** World Resolution scaling (50–100% with crisp HUD), configurable per-frame chunk upload budget (immediate player edits bypass the cap), per-frame particle limit, and separate block-entity render distance.
- **Fast Loads & Diagnostics:** On-disk SPIR-V shader and pipeline caches, world-load pipeline warm-up with progress indicator, F3 VRAM + average / 1% low frame-time readout, one-click privacy-sanitized **Copy Diagnostics**, and plain-English Vulkan startup failure guidance.
- **Refreshed Settings UI:** Multi-word search across tabs, sections, options, tooltips, and values, plus per-option and per-page reset to platform defaults.

## Requirements

- **Minecraft 1.21.11** and **Fabric Loader** (`0.14.14+`; tested with `0.18.4`).
- **Windows:** GPU and driver with Vulkan 1.2 (dynamic rendering optional).
- **macOS:** Apple Silicon (M1 or later; Intel Macs are untested) using Prism Launcher or the official Minecraft Launcher (`-XstartOnFirstThread`).
- voolkan replaces VulkanMod (it declares `provides: vulkanmod`). Do not install both. Sodium, Iris, OptiFine, and other renderer/borderless mods are incompatible.

## Status

Stable release (`1.5.0+1.21.11`). Verified in-game on both Windows (AMD Radeon R7 350, Vulkan 1.2.178) and Apple Silicon macOS (M3 MacBook Air, MoltenVK 1.2.6) with clean swapchain rebuilds, extended stress runs, and zero Vulkan errors. Please report issues on the GitHub issue tracker with your `Copy Diagnostics` output or `latest.log`.

## Licences and credits

voolkan is licensed under the GNU LGPL-3.0. It is built on VulkanMod (LGPL-3.0). It contains code adapted from Fabric API (Apache-2.0) and, through VulkanMod, from Sodium as it was licensed under LGPL-3.0 in early 2024, and bundles Fabric API modules and LWJGL (BSD-3-Clause). Details: `THIRD_PARTY_NOTICES.md` and `licenses/` in the source repository.
```

## Before publishing (owner checks)

- Confirm the GitHub repo is public and that `main` contains the release commit and the tag `v1.5.0+1.21.11`.
- Confirm the "Status" paragraph matches the passed Windows and macOS test results.
- Check Modrinth's current content rules for forks and for mods that use another mod's name, and use the text above ("unofficial fork", link to the original) as the disclosure.
- Decide whether to mention VulkanMod's donation or Discord links (the draft omits them on purpose).
- Version file name: `voolkan-1.5.0+1.21.11.jar` (check the name in `build/libs/` after `gradlew build`).
- Dependencies on the Modrinth version page: Fabric Loader only; mark VulkanMod as **incompatible**.
