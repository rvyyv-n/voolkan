# voolkan

<img src="./icon.png" width="125" alt="voolkan logo"/>

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.2%2B-red.svg)](https://www.vulkan.org/)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20macOS-informational.svg)](https://github.com/rvyyv-n/voolkan)
[![License](https://img.shields.io/badge/License-LGPL--3.0-orange.svg)](./LICENSE)
[![Release](https://img.shields.io/badge/Release-v1.5.0%2B1.21.11-brightgreen.svg)](https://github.com/rvyyv-n/voolkan/releases/tag/v1.5.0%2B1.21.11)

**voolkan** is a high-performance Vulkan renderer for Minecraft 1.21.11 (Fabric) on **Windows** and **Apple Silicon macOS**, built for high frame rates, low input latency, and steady frame pacing — including on older GPUs and budget systems that other Vulkan renderers cannot run on.

## What voolkan solves

- **Driver bottlenecks:** Minecraft's OpenGL renderer is limited by driver overhead and single-threaded draw submission. voolkan replaces it with a native Vulkan engine that cuts CPU draw-call cost and submits GPU work more efficiently.
- **Older GPUs left behind:** Most Vulkan renderers require `VK_KHR_dynamic_rendering`. voolkan automatically falls back to a repaired classic `VkRenderPass` path when that extension is missing, so hardware such as **AMD GCN 1 (Radeon R7 350)** and other Vulkan 1.2 devices gets full Vulkan rendering instead of a startup error.
- **Apple Silicon macOS via MoltenVK:** Full Vulkan portability support, tile-based GPU (TBDR) pass optimizations, zero-staging unified-memory writes, notch-safe borderless and native macOS fullscreen modes, and a live 1× **Reduce Resolution** toggle (verified at **180 FPS** full 2× Retina and **250 FPS** at 1× on an M3 MacBook Air).
- **Stutter and input lag:** Configurable per-frame chunk upload budgeting, async chunk uploads, a precise sleep-then-spin frame limiter, disk-cached SPIR-V shaders and pipelines, and world-load pipeline warm-up keep frame times smooth.

## Highlights

- **Legacy & modern Vulkan paths:** Automatic `VkRenderPass` fallback on older GPUs without dynamic rendering, plus deep performance optimizations (Early-Z pre-pass, descriptor/state caching, shared section slab, and BAR streaming buffers) across Windows and macOS.
- **Apple Silicon tuning:** Tile-GPU attachment folding, unified-memory direct writes, automatic battery FPS cap, and live Retina / 1× **Reduce Resolution** switching without restarting.
- **Frame pacing & latency controls:** Dedicated **Latency** settings group with precise frame limiting, Adaptive VSync (`FIFO_RELAXED` where supported), configurable render queue and swapchain images, background/battery FPS caps, and **Performance / Balanced / Quality / Battery / Competitive** presets.
- **Scalability & burst control:** World Resolution scaling (50–100% with crisp HUD), configurable per-frame chunk upload budget (immediate player edits bypass the cap), per-frame particle limit, and separate block-entity render distance.
- **Faster loads & diagnostics:** On-disk shader and pipeline caches, world-load pipeline warm-up with a live progress bar, F3 VRAM + average / 1% low frame-time stats, one-click privacy-sanitized **Copy Diagnostics**, and plain-English startup failure guidance.
- **Overhauled Settings UI:** Multi-word search across tabs, sections, options, tooltips, and values, plus per-option and per-page reset to platform defaults.

## Download

Release [v1.5.0+1.21.11](https://github.com/rvyyv-n/voolkan/releases/tag/v1.5.0%2B1.21.11):

| File | What it is |
|---|---|
| [`voolkan-1.5.0+1.21.11.jar`](https://github.com/rvyyv-n/voolkan/releases/download/v1.5.0%2B1.21.11/voolkan-1.5.0%2B1.21.11.jar) | The mod. Drop it into your Fabric `mods` folder. |
| [`voolkan-1.5.0+1.21.11-sources.jar`](https://github.com/rvyyv-n/voolkan/releases/download/v1.5.0%2B1.21.11/voolkan-1.5.0%2B1.21.11-sources.jar) | Corresponding source archive (LGPL-3.0). |

Requirements:
- **Minecraft 1.21.11** and **Fabric Loader** (`0.14.14+`; tested with `0.18.4`). Required Fabric API modules and LWJGL/MoltenVK natives are bundled inside the mod jar.
- **Windows:** A GPU and driver supporting Vulkan 1.2 (works with or without `VK_KHR_dynamic_rendering`).
- **macOS:** Apple Silicon (M1 or later; Intel Macs are untested) using Prism Launcher or the official Minecraft Launcher (for `-XstartOnFirstThread`).

## Tested hardware

- **Windows (legacy `VkRenderPass` path):** AMD Radeon R7 350 (GCN 1, Vulkan 1.2.178) — verified across extended gameplay and clean swapchain rebuilds.
- **Windows (modern `VK_KHR_dynamic_rendering` path):** NVIDIA GeForce RTX 2060 SUPER.
- **macOS (Apple Silicon / MoltenVK):** Apple M3 MacBook Air (macOS Tahoe 26.6.2, MoltenVK 1.2.6 / Vulkan 1.2.261 — extended play and stress runs with zero crashes or Vulkan errors) and Apple M5 MacBook Air.

## Compatibility

voolkan replaces Minecraft's OpenGL renderer, so mods that hook OpenGL, window management or Sodium internals do not work with it:

- **Cubes Without Borders / borderless mods:** incompatible. Use voolkan's built-in borderless windowed mode instead.
- **Sodium** (and Embeddium, Rubidium): incompatible. voolkan has its own chunk mesher and upload budgeting.
- **Iris / Oculus:** incompatible (they depend on OpenGL GLSL pipelines and Sodium).
- **OptiFine / Canvas:** incompatible.

The full list, including mods that break on 1.21.11 and recommended companions, is in [`docs/compatibility.md`](docs/compatibility.md).

## Documentation

- **Build & development setup:** [`docs/dev-setup.md`](docs/dev-setup.md)
- **Mod compatibility:** [`docs/compatibility.md`](docs/compatibility.md)
- **Documentation index:** [`docs/README.md`](docs/README.md)
- **Roadmap & status:** [`ROADMAP.md`](ROADMAP.md)
- **Code conventions & contributing:** [`docs/conventions.md`](docs/conventions.md)

## License and credits

voolkan is licensed under **LGPL-3.0** (see [`LICENSE`](LICENSE)). It is an unofficial, independent fork of [VulkanMod](https://github.com/xCollateral/VulkanMod) by **xCollateral and contributors**, whose original copyright notices are preserved. voolkan is not affiliated with or endorsed by the VulkanMod authors. It also bundles Fabric API modules and LWJGL; full third-party notices and licenses are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`licenses/`](licenses/).
