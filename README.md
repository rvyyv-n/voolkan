# voolkan

<img src="./icon.png" width="125" alt="voolkan logo"/>

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.2+-red.svg)](https://www.vulkan.org/)
[![Platform](https://img.shields.io/badge/Platform-Windows-informational.svg)](https://www.microsoft.com/windows)
[![License](https://img.shields.io/badge/License-LGPL_3.0-orange.svg)](./LICENSE)

**voolkan** is a high-performance Vulkan-based rendering engine for Minecraft 1.21.11.

It replaces the default OpenGL pipeline with Vulkan to reduce CPU overhead, improve GPU utilization, and deliver smoother frame pacing and lower input latency. Built with broad hardware compatibility in mind, voolkan provides an automatic fallback render pass pipeline for older GPUs lacking dynamic rendering support (`VK_KHR_dynamic_rendering`), such as AMD GCN 1 / Radeon R7 350 graphics cards.

**Tags:** `minecraft`, `fabric-mod`, `vulkan`, `renderer`, `optimization`, `low-latency`, `legacy-gpu`, `windows`

---

## Features

- **Vulkan Rendering Pipeline:** Replaces OpenGL with Vulkan for reduced driver overhead, faster chunk meshing, and efficient draw calls.
- **Legacy GPU Fallback:** Automatic detection and support for `VkRenderPass` pipelines on hardware without dynamic rendering.
- **Latency & Frame Pacing:** Single-frame queue option, configurable swapchain buffers, and persistent disk-cached pipeline compilation.
- **Preset Controls:** Built-in video settings presets (Performance, Balanced, Quality) with hardware capability logging.

## Documentation

- **Build & Development Setup:** [`docs/dev-setup.md`](docs/dev-setup.md)
- **Project Roadmap & Status:** [`ROADMAP.md`](ROADMAP.md)
- **Documentation Index:** [`docs/README.md`](docs/README.md)
- **Code Conventions & Contributing:** [`docs/conventions.md`](docs/conventions.md)

## License and Credits

voolkan is licensed under **LGPL-3.0** (see [`LICENSE`](LICENSE)). It is an independent fork based on [VulkanMod](https://github.com/xCollateral/VulkanMod) by xCollateral and contributors, and bundles Fabric API modules and LWJGL. Full third-party notices and licenses are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`licenses/`](licenses/).
