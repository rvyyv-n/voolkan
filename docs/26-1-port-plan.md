# 26.1 port: research and plan (ROADMAP 6.1)

Research and a measured feasibility probe, done 2026-10-05. **No port was committed:** it needs owner decisions (section 5). Items marked **unverified** could not be checked on this PC.

## 1. What changed in Minecraft 26.1 for a mod like this

| Fact | Source |
|---|---|
| 26.1 "Tiny Takeover" was released 2026-03-24. First version with the `YY.D.H` numbering, the first to require **Java 25**, and the first **not obfuscated** (it ships with Mojang names). The later releases 26.1.1 and 26.1.2 exist. | [Minecraft Wiki, Java Edition 26.1](https://minecraft.wiki/w/Java_Edition_26.1) |
| Fabric: use **Loom 1.15** and **Gradle 9.4.0** (blog's wording "at the time of writing"), plugin id `net.fabricmc.fabric-loom` (no remapping; `modImplementation` becomes `implementation`, `remapJar` becomes `jar`), Java 25 for the Gradle JVM. Yarn is no longer supported. "No mods from 1.21.11 or before will work without, at a minimum, recompilation." | [Fabric blog, 26.1](https://fabricmc.net/2026/03/14/261.html) |
| Rendering: 26.1 is still the OpenGL era. The new vanilla Vulkan backend arrives in 26.2 (see `mc-26-2-research.md`). The light-level to brightness algorithm was rewritten in 26.1 (affects anything that reads light, such as our light pipelines). | wiki page above |
| Versions available now (checked on the Fabric meta API today): stable game versions 26.3, 26.2, 26.1.2, 26.1.1, 26.1. Fabric Loader 0.19.5 is current and has 26.1.2 and 26.3 entries. Fabric API 0.155.3+26.1.2 (26.1.2), 0.161.0+26.3. | Fabric meta / maven |

Our build already uses `loom.officialMojangMappings()`, so the Mojang-name switch costs little; the Yarn property in `gradle.properties` is unused.

## 2. Does upstream VulkanMod have 26.1 source we can build on?

**No public source.**

- The upstream repository has branches `1.18.2`, `1.19.2`, `1.19.4`, `1.20.x`, `1.21`, `dev`, `web`. `dev` is still Minecraft 1.21.11 (`build.gradle` Java 21, `mod_version 0.6.8+1.21.11-dev`, last commit 2026-08-10). There is no 26.x branch or tag, and no GitHub release after 0.6.0.
- A binary `VulkanMod-0.6.8+26.1.2.jar` (published 2026-06-14, depends on `minecraft >=26.1`, fabricloader `>=0.18.6`) is on [Modrinth](https://modrinth.com/mod/vulkanmod). Upstream issues #699 and #772 were closed with "released on 26.1.x". Issue #866 says 26.2 builds exist only as test builds on the VulkanMod Discord and the maintainer's 26.2 work is in progress.
- The 26.1.2 jar still contains the string `Unsupported extension:` and `beginDynamicRendering`, same as 1.21.11. So it most probably still requires `VK_KHR_dynamic_rendering` and would not start on the R7 (**unverified**, from class strings only; not run).
- Per ROADMAP 6.1: "ask upstream for source; otherwise decompiled reference". Decompiling the LGPL jar for reading is possible with the Vineflower from `docs/dev-setup.md`, but it was **not done**: it is an owner decision (section 5).

## 3. Measured size of the port (compile probe, not committed)

A throwaway edit of the 1.21.11 tree (reverted afterwards) was compiled against 26.1.2 with JDK 25.0.4, Gradle 9.4.0 and Loom 1.15.5:

| Change needed | Result |
|---|---|
| `gradle-wrapper.properties` 9.2.1 to 9.4.0; plugin `net.fabricmc.fabric-loom` 1.15; drop `mappings` and `mod*` configurations; `options.release` 25; loader 0.19.5; Fabric API `0.155.3+26.1.2`; `minecraft_version 26.1.2` | Configures. Needed extra fixes below |
| Fabric API module `fabric-block-view-api-v2` no longer exists: it is now `fabric-block-getter-api-v2`. `fabric-resource-loader-v0` is gone (only `-v1`). | build script fails until changed |
| `vulkanmod.accesswidener` header namespace `named` must be `official` (Loom 1.15 error: "Expected official namespace") | fixed in the probe |
| `compileJava` | **about 525 distinct errors in about 150 of 321 Java files** (about 80 in `render`, 50 in `mixin`, 10 in `vulkan`, 8 in `config`, 1 in `Initializer`). The probe counted 1050 `error:` lines, each error printed twice. |

Main error groups:

1. **Fabric Rendering API (FRAPI) rework.** `net.fabricmc.fabric.api.renderer.v1.mesh`, `.render` and `.model` packages are different or gone. Our `net.vulkanmod.render.chunk.build.frapi.**` (copied from Fabric API/Indigo, Apache-2.0) and the FRAPI mixins must be rebased on the 26.1 API. The Fabric blog warned the renderer modules may differ at first release.
2. **Blaze3D API changes.** `RenderPass`/`VkRenderPass`, `GpuDevice`/`VkGpuDevice`, `CommandEncoder`/`VkCommandEncoder`, `ChunkSectionLayer` vs `TerrainRenderType` no longer line up; many `@Override` methods have no supertype method any more (238 errors); GUI render-state packages moved (`net.minecraft.client.gui.render.state`); `GlStateManager._colorMask`, `RenderSystem.flipFrame`, `ChunkPos.x/z` access changed.
3. **LWJGL.** `VkDebugUtilsMessengerCallbackEXTI is not a functional interface` and the shaderc `ShaderIncluder` / `ShaderReleaser` not implementing `getDescriptor()` point to a newer LWJGL than our 3.3.3 (probably 3.4.x; inferred, **unverified**). The bundled `lwjglVersion` must follow what Minecraft 26.1 ships.
4. **Fluid and quad API.** `FluidRenderHandlerRegistry` package missing, `color(...)` and `lightmap(...)` signatures changed.

Reading: this is a real porting task of a size comparable to the original 1.21.11 port (upstream's own "Port to 1.21.11" commit on 2026-03-30), not a version bump. Without upstream's source, it means re-deriving about 150 files by hand against Mojang-named 26.1 classes. Our legacy render-pass fixes (Phase 1/2 code in `vulkan.framebuffer`, `Renderer`, `PipelineState` and so on) are in the 10 `vulkan` files and would need to be re-applied on top of whatever the 26.1 `Framebuffer`/`RenderPass` look like.

## 4. Plan if the owner says go

1. Decide the branch layout (section 5), then cut `26.1` work from the agreed base.
2. Build config: Gradle 9.4.0, Loom 1.15 plugin `net.fabricmc.fabric-loom`, JDK 25 in `docs/dev-setup.md` and CI (`.github/workflows/build.yml`: Temurin 25), `options.release = 25`, loader/Fabric API versions above, module renames above, access widener header `official`.
3. Make it compile in this order: `vulkan` and `config` (small), then Blaze3D mixins and `render.engine`, then FRAPI (rebuild on the 26.1 API, check the Fabric API source for the licence), then chunk building and the light classes (re-check the 26.1 light algorithm change).
4. Check lavapipe: forced legacy path, R7 profile, validation clean (`tools\smoke-run.ps1`).
5. Re-run the R7 checks (world, F3, fullscreen, post effect) when the owner has the card at hand. 26.1 on the R7 is useful because vanilla 26.1 has no Vulkan renderer and OpenGL is the alternative.
6. Licence rules (AGENTS.md): no Sodium code, nothing from unlicensed repos, no `initialLayout=UNDEFINED` change. If the decompiled upstream jar is used, it is LGPL-3.0 and may be read for reference only if the owner approves; write our own code and keep upstream's notices.
7. Notices: changed upstream files keep the modification notice; check `tools\check-notices.ps1`.

## 5. Needs the owner before any port work

1. **Is 26.1 worth it?** 26.1 is older than 26.2 and 26.3 (stable now). Possible targets: 26.1.x only (last pre-Vulkan vanilla, same OpenGL fallback story as 1.21.11), or skip to 26.2/26.3 where vanilla has its own Vulkan renderer and the backend interface changed again.
2. **Branch policy:** new `26.1` line next to `main`, or move `main` to 26.1 and cut `1.21.11/stable` (`docs/conventions.md` describes the second option).
3. **Dropping 1.21.11?** Not planned here. The probe suggests two code lines will diverge a lot (FRAPI, Blaze3D), so keeping both has a maintenance cost.
4. **Reference source:** ask upstream for the 26.1 source, or approve decompiling the upstream 26.1.2 jar for reference, or port from our own tree only.
5. **Release:** version number and release of any 26.1 build.
6. **R7 checks:** need the card.
