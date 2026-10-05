# Third-party notices and credits

voolkan is an unofficial fork of **VulkanMod** and is not affiliated with or endorsed by the VulkanMod project, Mojang, Microsoft, FabricMC or any other party named here.

## Base project

- **VulkanMod** (https://github.com/xCollateral/VulkanMod), Copyright its authors, led by **xCollateral**, licensed under the **GNU LGPL-3.0**. voolkan is based on upstream commit `7f69043` (VulkanMod 0.6.8+1.21.11). Files we changed carry a modification notice. Licence texts: `LICENSE` (LGPL-3.0) and `COPYING` (GPL-3.0, which the LGPL incorporates).
- The VulkanMod logo (`src/main/resources/assets/vulkanmod/vlogo.png`) is upstream's and is kept only for the upstream README section below the voolkan introduction.

## Code derived from other projects (inside the source tree)

| Where | Origin | Licence |
|---|---|---|
| `net/vulkanmod/render/chunk/build/frapi/**` (15 files carrying the header) | Fabric API / Indigo renderer, Copyright (c) 2016-2019 FabricMC | Apache-2.0, text: https://www.apache.org/licenses/LICENSE-2.0.txt |
| `net/vulkanmod/render/chunk/build/light/smooth/SmoothLightPipeline.java` | "Re-adapted Sodium's smooth light pipeline" (comment in the upstream file; Sodium by JellySquid / CaffeineMC) | Inherited from VulkanMod upstream. **To verify before a stable release:** that the Sodium code this file was adapted from was published under a licence compatible with LGPL-3.0 (early Sodium versions were LGPL-3.0). voolkan adds no new Sodium code (see `AGENTS.md`). |

## Libraries bundled inside the mod jar (jar-in-jar)

| Library | Licence | Text |
|---|---|---|
| Fabric API modules (`fabric-api-base`, `fabric-resource-loader-v0/v1`, `fabric-rendering-v1`, `fabric-renderer-api-v1`, `fabric-rendering-fluids-v1`, `fabric-block-view-api-v2`, `fabric-lifecycle-events-v1`, `fabric-transitive-access-wideners-v1`) | Apache-2.0, FabricMC | https://www.apache.org/licenses/LICENSE-2.0.txt (a copy in `licenses/` is pending, see below) |
| LWJGL: `lwjgl-vulkan`, `lwjgl-vma`, `lwjgl-shaderc` and their Windows/Linux/macOS natives | BSD-3-Clause, Lightweight Java Game Library | `licenses/BSD-3-Clause-LWJGL.txt` |
| Vulkan Memory Allocator (inside `lwjgl-vma`) | MIT, AMD GPUOpen | `licenses/MIT-VulkanMemoryAllocator.txt` |
| Shaderc (inside `lwjgl-shaderc`) | Apache-2.0, Google | https://www.apache.org/licenses/LICENSE-2.0.txt (a copy in `licenses/` is pending, see below) |

The full list and versions are in `build.gradle`; each bundled jar also carries its own licence files.

## Used at run time but not distributed by voolkan

Minecraft (Mojang Studios / Microsoft, governed by the Minecraft EULA), Fabric Loader (Apache-2.0), and the libraries Minecraft ships (Log4j, Apache Commons Lang, and others).

## Development tooling (not in the repo or the jar)

Mesa lavapipe (MIT), Vineflower (Apache-2.0), LunarG Vulkan SDK and validation layers (Apache-2.0), RenderDoc (MIT), PresentMon (MIT). They are downloaded locally for testing only.

## Note

The Apache-2.0 text is referenced by URL for now: committing the verbatim file was blocked by the maintainer's commit hook, which flags the licence's own wording. The bundled Fabric API jars carry their own copy. Adding `licenses/Apache-2.0.txt` is an open item under ROADMAP 5.1.
