# voolkan

<img src="./icon.png" width="125" alt="voolkan logo"/>

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.2+-red.svg)](https://www.vulkan.org/)
[![Platform](https://img.shields.io/badge/Platform-Windows-informational.svg)](https://www.microsoft.com/windows)
[![License](https://img.shields.io/badge/License-LGPL--3.0-orange.svg)](./LICENSE)
[![Release](https://img.shields.io/github/v/release/rvyyv-n/voolkan?color=brightgreen)](https://github.com/rvyyv-n/voolkan/releases/tag/v1.0.0%2B1.21.11)

**voolkan** is a next-generation Vulkan renderer for Minecraft 1.21.11 (Fabric), built for high frame rates, low input latency and steady frame pacing, including on older GPUs and budget systems that other Vulkan renderers cannot run on.

## What voolkan solves

- **Driver bottlenecks:** Minecraft's OpenGL renderer is limited by driver overhead and single-threaded draw submission. voolkan replaces it with a native Vulkan engine that cuts CPU draw-call cost and submits GPU work more efficiently.
- **Older GPUs left behind:** Most Vulkan renderers require `VK_KHR_dynamic_rendering`. voolkan automatically falls back to a classic `VkRenderPass` path when that extension is missing, so hardware such as **AMD GCN 1 (Radeon R7 350)** and other Vulkan 1.2 devices gets full Vulkan rendering instead of a startup error.
- **Stutter and input lag:** chunk uploads are budgeted per frame, the presentation queue is kept to a single frame, and compiled pipelines are cached on disk.

## Highlights

- **Early-Z depth pre-pass:** opaque geometry is depth-tested first, cutting pixel shader work and overdraw on fillrate-limited GPUs.
- **Chunk upload budgeting:** chunk geometry uploads are capped at 2.5 ms per frame, removing micro-stutter while chunks load.
- **Ultra-low latency:** a single-frame queue (`Frame Queue: 1`) and configurable swapchain image count keep input response tight.
- **Disk-cached pipelines:** compiled pipelines are saved to disk, so later launches skip most shader compilation hitches.
- **Video presets:** in-game Performance, Balanced and Quality presets, with hardware capability logging and compatibility diagnostics.
- **Native borderless windowed mode:** built in, no extra mod needed.

## Download

Release [v1.0.0+1.21.11](https://github.com/rvyyv-n/voolkan/releases/tag/v1.0.0%2B1.21.11):

| File | What it is |
|---|---|
| [`voolkan-1.0.0+1.21.11.jar`](https://github.com/rvyyv-n/voolkan/releases/download/v1.0.0%2B1.21.11/voolkan-1.0.0%2B1.21.11.jar) | The mod. Drop it into your Fabric `mods` folder. |
| [`voolkan-1.21.11.mrpack`](https://github.com/rvyyv-n/voolkan/releases/download/v1.0.0%2B1.21.11/voolkan-1.21.11.mrpack) | Modrinth companion modpack (voolkan plus compatible companion mods). |
| [`voolkan-1.21.11-modpack-full.zip`](https://github.com/rvyyv-n/voolkan/releases/download/v1.0.0%2B1.21.11/voolkan-1.21.11-modpack-full.zip) | Full offline bundle, usable without a network connection. |

Requirements: Windows, Minecraft 1.21.11, Fabric Loader, Fabric API, and a GPU with Vulkan 1.2 drivers.

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
