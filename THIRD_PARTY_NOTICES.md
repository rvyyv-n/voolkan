# Third-party notices and credits

voolkan is an unofficial fork of **VulkanMod** and is not affiliated with or endorsed by the VulkanMod project, Mojang, Microsoft, FabricMC or any other party named here.

## Base project

- **VulkanMod** (https://github.com/xCollateral/VulkanMod), Copyright its authors, led by **xCollateral**, licensed under the **GNU LGPL-3.0**. voolkan is based on upstream commit `7f69043` (VulkanMod 0.6.8+1.21.11). Files we changed carry a modification notice. Licence texts: `LICENSE` (LGPL-3.0) and `COPYING` (GPL-3.0, which the LGPL incorporates).
- The VulkanMod logo (`src/main/resources/assets/vulkanmod/vlogo.png`) is upstream's and is kept only for the upstream README section below the voolkan introduction.

## Code derived from other projects (inside the source tree)

| Where | Origin | Licence |
|---|---|---|
| `net/vulkanmod/render/chunk/build/frapi/**` (15 files carrying the header) | Fabric API / Indigo renderer, Copyright (c) 2016-2019 FabricMC | Apache-2.0, text: https://www.apache.org/licenses/LICENSE-2.0.txt |
| `net/vulkanmod/render/chunk/build/light/smooth/SmoothLightPipeline.java`, and by the same comparison the sibling light classes (see "Sodium-derived light code" below) | Adapted by VulkanMod from Sodium (JellySquid / CaffeineMC) as it stood in February 2024 | LGPL-3.0 at the time of copying (Sodium switched to PolyForm Shield 1.0.0 on 2024-04-06). Compatible; verified, see below. |

## Sodium-derived light code (finding for ROADMAP 5.1, checked 2026-10-05)

Question: was the Sodium code that VulkanMod adapted `SmoothLightPipeline.java` from under a licence compatible with LGPL-3.0?

Answer: **yes**, with one caveat.

- VulkanMod added the file in upstream commit `57d629c` ("Rework chunk building", 2024-02-28). Its Javadoc is word for word the Javadoc of Sodium's `net/caffeinemc/mods/sodium/client/model/light/smooth/SmoothLightPipeline.java` as of the last Sodium commit on or before that day (`435a6bd`), whose last change before that was `82d6217` (2024-02-12, "Rename base package to net.caffeinemc.mods"). A line diff against that Sodium version shows only the package, import and type renames (`ModelQuadView` to `QuadView`, `Direction` to `SimpleDirection`) plus a few small edits.
- At that commit Sodium's README states "Except where otherwise stated, the files in this repository are provided under the terms of the GNU LGPLv3", and the repo carried `COPYING` and `COPYING.LESSER` (split on 2023-12-09, commit `b3ff4ff`, which says it changes nothing about the licensing terms). So the code was LGPL-3.0 when VulkanMod took it.
- Sodium changed its licence to **PolyForm Shield 1.0.0** on 2024-04-06 (commit `d70c104`, then `3736120`), about five weeks after VulkanMod's copy. PolyForm Shield is not LGPL-compatible. The relicence applies to Sodium's later versions; a copy obtained under LGPL-3.0 stays LGPL-3.0 (the grant is not revocable), so VulkanMod's February 2024 copy and voolkan's use of it under LGPL-3.0 are fine.
- The Sodium file changed again on 2024-08-08 (multi-loader merge, already PolyForm Shield). No evidence that VulkanMod took anything from that version: the VulkanMod file's Sodium-derived text matches the February 2024 version.

Caveats (not legal advice, to be re-checked if anything changes):

1. Upstream VulkanMod itself has no per-file licence header or note recording the origin; the evidence above is from comparing the code and the Sodium repository history. VulkanMod's own later edits to the file (for example 2024-11-25, "Use level renderer light values") are VulkanMod's, under LGPL-3.0.
2. Several siblings in `net/vulkanmod/render/chunk/build/light/` are structurally close to Sodium's February 2024 versions (compared after removing `package` and `import` lines): `data/QuadLightData` is identical, `LightPipeline` and `flat/FlatLightPipeline` differ in a handful of lines, `data/ArrayLightDataCache`, `data/LightDataAccess`, `smooth/AoFaceData` and `smooth/AoNeighborInfo` are reworked. They carry no Sodium comment. They date from the same upstream commit, so the same LGPL-3.0 finding applies to them.
3. Never copy newer Sodium code into this tree (see `AGENTS.md`): from 2024-04-06 on it is PolyForm Shield, which is not LGPL-compatible. Because the licence status is clear, no replacement implementation is proposed.

## Libraries bundled inside the mod jar (jar-in-jar)

| Library | Licence | Text |
|---|---|---|
| Fabric API modules (`fabric-api-base`, `fabric-resource-loader-v0/v1`, `fabric-rendering-v1`, `fabric-renderer-api-v1`, `fabric-rendering-fluids-v1`, `fabric-block-view-api-v2`, `fabric-lifecycle-events-v1`, `fabric-transitive-access-wideners-v1`) | Apache-2.0, FabricMC | https://www.apache.org/licenses/LICENSE-2.0.txt (no copy in `licenses/`, see Note) |
| LWJGL: `lwjgl-vulkan`, `lwjgl-vma`, `lwjgl-shaderc` and their Windows/Linux/macOS natives | BSD-3-Clause, Lightweight Java Game Library | `licenses/BSD-3-Clause-LWJGL.txt` |
| Vulkan Memory Allocator (inside `lwjgl-vma`) | MIT, AMD GPUOpen | `licenses/MIT-VulkanMemoryAllocator.txt` |
| Shaderc (inside `lwjgl-shaderc`) | Apache-2.0, Google | https://www.apache.org/licenses/LICENSE-2.0.txt (no copy in `licenses/`, see Note) |

The full list and versions are in `build.gradle`; each bundled jar also carries its own licence files.

## Used at run time but not distributed by voolkan

Minecraft (Mojang Studios / Microsoft, governed by the Minecraft EULA), Fabric Loader (Apache-2.0), and the libraries Minecraft ships (Log4j, Apache Commons Lang, and others).

## Development tooling (not in the repo or the jar)

Mesa lavapipe (MIT), Vineflower (Apache-2.0), LunarG Vulkan SDK and validation layers (Apache-2.0), RenderDoc (MIT), PresentMon (MIT). They are downloaded locally for testing only.

## Note

The Apache-2.0 text is referenced by URL for now: committing the verbatim file was blocked by the maintainer's commit hook, which flags the licence's own wording. The bundled Fabric API jars carry their own copy. Adding `licenses/Apache-2.0.txt` is an open item under ROADMAP 5.1.
