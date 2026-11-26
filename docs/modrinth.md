# Modrinth listing

Mirrors the live Modrinth project configuration and Markdown description for `voolkan` (`1.5.0+1.21.11`, queued for public review).

## Project fields

| Field | Value |
|---|---|
| Name | voolkan |
| Slug | `voolkan` |
| Summary (max 256 chars) | High-performance Vulkan renderer for Minecraft 1.21.11 on Windows (including legacy GPUs) and Apple Silicon macOS. |
| Categories | Optimization, Utility |
| Environment | Client (`client_side: required`, `server_side: unsupported`) |
| Loader | Fabric (`0.14.14+`; tested on `0.18.4`) |
| Game version | `1.21.11` |
| Licence | `LGPL-3.0-only` (`https://github.com/rvyyv-n/voolkan/blob/main/LICENSE`) |
| Source | `https://github.com/rvyyv-n/voolkan` |
| Issues | `https://github.com/rvyyv-n/voolkan/issues` |
| Wiki / Discord / donations | empty (no voolkan Discord or donation page; do not link VulkanMod's) |
| Icon | `icon.png` in the repo root |
| Featured gallery banner | `docs/gallery/voolkan-banner.png` (regenerable via `python tools/build-banner.py`) |
| Target visibility | Public (`requested_status: approved`) |
| Featured release | `1.5.0+1.21.11` (`voolkan-1.5.0+1.21.11.jar`) |
| Version relations | Incompatible: VulkanMod, Sodium, Iris, Cubes Without Borders, Borderless Mining. Optional: Not Enough Vulkan. |

## Description (Markdown)

```markdown
<p align="center">
  <img src="https://cdn.modrinth.com/data/K8M1fo7I/images/24393f4f8676a3aed60077f5220e5e7d16bc5af2.png" alt="voolkan 1.5 banner" width="100%"/>
</p>

<p align="center">
  <a href="https://minecraft.net/"><img src="https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg" alt="Minecraft 1.21.11"/></a>
  <a href="https://fabricmc.net/"><img src="https://img.shields.io/badge/Loader-Fabric-blue.svg" alt="Fabric Loader"/></a>
  <a href="https://www.vulkan.org/"><img src="https://img.shields.io/badge/Vulkan-1.2%2B-red.svg" alt="Vulkan 1.2+"/></a>
  <a href="https://github.com/rvyyv-n/voolkan"><img src="https://img.shields.io/badge/Platform-Windows%20%7C%20macOS-informational.svg" alt="Windows and macOS"/></a>
  <a href="https://github.com/rvyyv-n/voolkan/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-LGPL--3.0-orange.svg" alt="LGPL-3.0 License"/></a>
  <a href="https://github.com/rvyyv-n/voolkan"><img src="https://img.shields.io/badge/GitHub-rvyyv--n%2Fvoolkan-181717.svg?logo=github&logoColor=white" alt="GitHub"/></a>
</p>

---

**voolkan** is an **unofficial, high-performance LGPL-3.0 fork of [VulkanMod](https://github.com/xCollateral/VulkanMod)** by xCollateral and contributors, built for high framerates, low input latency, and steady frame pacing across **Windows** (including older Vulkan 1.2 GPUs) and **Apple Silicon macOS (MoltenVK)**.

voolkan is not affiliated with or endorsed by the VulkanMod authors, Mojang, Microsoft, or FabricMC. All credit for the original Vulkan renderer goes to the VulkanMod authors. Full source code and commit history against upstream (`git log upstream-base..main`) are public on [GitHub](https://github.com/rvyyv-n/voolkan), and every modified upstream file carries an LGPL modification notice.

---

## What voolkan Solves

- **OpenGL Driver Bottlenecks:** Replaces Minecraft's single-threaded OpenGL renderer with a native Vulkan engine that reduces CPU draw-call overhead and improves GPU utilization.
- **Older Windows GPUs Left Behind:** Upstream VulkanMod 0.6.8 requires `VK_KHR_dynamic_rendering` and fails to start on older hardware. voolkan repairs and optimizes the classic `VkRenderPass` path as an automatic fallback so Vulkan 1.2 GPUs lacking dynamic rendering (such as AMD Radeon R7 350 / GCN 1) run cleanly.
- **Apple Silicon macOS Support (MoltenVK):** Adds full Vulkan portability support (`VK_KHR_portability_subset`), tile-based GPU (TBDR) pass optimizations, zero-staging unified-memory direct writes, notch-safe borderless and native macOS fullscreen, automatic battery FPS cap, and a live 1× **Reduce Resolution** toggle without restarting.
- **Stutter & Input Latency:** Combines async chunk uploads, configurable per-frame chunk upload budgeting, a precise sleep-then-spin frame limiter, on-disk SPIR-V shader/pipeline caches, and world-load pipeline warm-up.

---

## Key Features (v1.5)

- **Legacy & Modern Vulkan Paths:** Automatic `VkRenderPass` fallback on older GPUs plus core engine optimizations (Early-Z depth pre-pass, descriptor/state caching, shared section slab, BAR streaming buffers, and hardware texture blits).
- **Apple Silicon / MoltenVK Tuning:** Verified on an M3 MacBook Air (75-mod pack, 16-chunk view distance, VSync OFF) at **180 FPS** full 2× Retina (`2940×1846`) and **250 FPS** with live **Reduce Resolution** ON (`1470×923`), with zero crashes or Vulkan errors.
- **Latency & Frame Pacing Controls:** Dedicated **Latency** group in Video Settings with precise frame limiting, Adaptive VSync (`FIFO_RELAXED` where supported), configurable render queue and swapchain images, background and battery FPS caps, and **Performance / Balanced / Quality / Battery / Competitive** presets.
- **Scalability & Burst Control:** World Resolution scaling (50–100% while keeping HUD, F3, and menus crisp), configurable per-frame chunk upload budget (player block edits bypass the cap), per-frame particle limit, and separate block-entity render distance.
- **Fast Loads & Built-in Diagnostics:** On-disk SPIR-V shader and pipeline caches, world-load pipeline warm-up with progress indicator, F3 VRAM + rolling average / 1% low frame-time stats, one-click privacy-sanitized **Copy Diagnostics**, and plain-English startup failure dialogs.
- **Overhauled Settings UI:** Multi-word search across tabs, sections, options, tooltips, and values, plus per-option and per-page reset to platform-aware defaults.

---

## Requirements

- **Minecraft:** `1.21.11`
- **Loader:** Fabric Loader (`0.14.14+`; tested on `0.18.4`). Required Fabric API modules and LWJGL/MoltenVK natives are bundled inside the mod jar.
- **Windows:** 64-bit Windows with any GPU and driver supporting **Vulkan 1.2+** (`VK_KHR_dynamic_rendering` optional).
- **macOS:** Apple Silicon (M1 or later; Intel Macs untested) using Prism Launcher or the official Minecraft Launcher (`-XstartOnFirstThread`).

---

## Compatibility

Because voolkan replaces Minecraft's OpenGL renderer and declares `provides: vulkanmod`, mods that hook OpenGL, Sodium internals, or external windowing are incompatible:

| Incompatible Mod | Notes |
|---|---|
| **VulkanMod** | Replaced by voolkan (`provides: vulkanmod`). Do not install both. |
| **Sodium / Embeddium** | Incompatible. voolkan includes its own multi-threaded Vulkan mesher and upload budgeting. |
| **Iris / Oculus** | Incompatible (depends on OpenGL GLSL pipelines and Sodium). |
| **Cubes Without Borders / Borderless Mining** | Incompatible. Use voolkan's built-in borderless fullscreen mode. |
| **OptiFine / Canvas** | Incompatible. |

See the full [Compatibility Guide](https://github.com/rvyyv-n/voolkan/blob/main/docs/compatibility.md) for companion recommendations and notes.

---

## Tested Hardware

- **Windows (legacy `VkRenderPass` path):** AMD Radeon R7 350 (GCN 1, Vulkan 1.2.178) — verified across extended gameplay and clean swapchain rebuilds.
- **Windows (modern `VK_KHR_dynamic_rendering` path):** NVIDIA GeForce RTX 2060 SUPER.
- **macOS (Apple Silicon / MoltenVK):** Apple M3 MacBook Air (macOS Tahoe 26.6.2, MoltenVK 1.2.6 / Vulkan 1.2.261 — extended play and stress runs with zero crashes or Vulkan errors) and Apple M5 MacBook Air.

Please report issues on the [GitHub issue tracker](https://github.com/rvyyv-n/voolkan/issues) with your **Copy Diagnostics** output or `latest.log`.

---

## License & Credits

voolkan is licensed under the **[GNU LGPL-3.0](https://github.com/rvyyv-n/voolkan/blob/main/LICENSE)**. It is an unofficial, independent fork built on [VulkanMod](https://github.com/xCollateral/VulkanMod) (LGPL-3.0) by **xCollateral and contributors**, whose original copyright notices are preserved. It also contains code adapted from Fabric API (Apache-2.0) and, via VulkanMod, from early-2024 LGPL-3.0 Sodium, and bundles Fabric API modules and LWJGL (BSD-3-Clause). Full third-party notices are in [`THIRD_PARTY_NOTICES.md`](https://github.com/rvyyv-n/voolkan/blob/main/THIRD_PARTY_NOTICES.md) and `licenses/` inside both the repository and mod jar.
```
