# voolkan

<img src="./icon.png" width="125" alt="voolkan logo"/>

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.2+-red.svg)](https://www.vulkan.org/)
[![Platform](https://img.shields.io/badge/Platform-Windows-informational.svg)](https://www.microsoft.com/windows)
[![License](https://img.shields.io/badge/License-LGPL_3.0-orange.svg)](./LICENSE)

**voolkan** is a next-generation high-performance Vulkan rendering engine for Minecraft 1.21.11, built for maximum frame rates, ultra-low input latency, and consistent frame pacing.

By rewriting Minecraft's rendering backend from OpenGL to Vulkan, voolkan bypasses driver overhead, slashes CPU draw-call bottlenecks, and unlocks substantial framerate improvements across both modern hardware and budget rigs. Crucially, voolkan delivers dedicated performance improvements and first-class support for older systems and legacy GPUs—featuring an automatic `VkRenderPass` fallback pipeline for devices lacking modern dynamic rendering (`VK_KHR_dynamic_rendering`), such as AMD GCN 1 / Radeon R7 350 GPUs and legacy Vulkan 1.2 hardware.

**Tags:** `minecraft`, `fabric-mod`, `vulkan`, `renderer`, `optimization`, `low-latency`, `legacy-gpu`, `fps-boost`, `windows`

---

## Performance & Architecture

- **Vulkan Core Pipeline:** Replaces OpenGL to dramatically reduce CPU rendering bottlenecks, accelerate multi-threaded chunk meshing, and optimize GPU command submission.
- **Dedicated Legacy GPU & System Support:** Automatic fallback pipeline providing complete Vulkan acceleration and stability on older GPUs and entry-level systems without dynamic rendering.
- **Early-Z Depth Rejection:** Dedicated early-Z shader pre-pass for opaque geometry, drastically cutting pixel shader workload and overdraw on fillrate-limited older GPUs.
- **Chunk Upload Budgeting:** Frame-time budgeting (2.5 ms cap) on chunk geometry uploads to eliminate chunk loading micro-stutter and frame drops.
- **Ultra-Low Latency Mode:** Single-frame presentation queue (`Frame Queue: 1`), configurable swapchain images, and disk-cached pipeline compilation for instantaneous input response.
- **Fast Video Presets:** In-game quick presets (Performance, Balanced, Quality) with hardware capability logging and compatibility diagnostics.

## Documentation

- **Build & Development Setup:** [`docs/dev-setup.md`](docs/dev-setup.md)
- **Project Roadmap & Status:** [`ROADMAP.md`](ROADMAP.md)
- **Documentation Index:** [`docs/README.md`](docs/README.md)
- **Code Conventions & Contributing:** [`docs/conventions.md`](docs/conventions.md)

## License and Credits

voolkan is licensed under **LGPL-3.0** (see [`LICENSE`](LICENSE)). It is an independent fork based on [VulkanMod](https://github.com/xCollateral/VulkanMod) by xCollateral and contributors, and bundles Fabric API modules and LWJGL. Full third-party notices and licenses are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`licenses/`](licenses/).
