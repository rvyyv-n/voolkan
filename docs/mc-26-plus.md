# Minecraft 26.x notes (ROADMAP `port-26-1`, `port-26-2`)

Desk research from 2026-10-05, plus one throwaway compile probe. Nothing was tested on the R7. **Unverified** marks things that could not be checked on this PC. Plans for 26.2 and later are the owner's, to be written separately.

## Decisions (owner, 2026-10-05)

- Newer versions (26.2 and later): set aside for now; the owner will plan them separately.
- Branch layout: keep the option that lets work on later versions start later. That is the `docs/conventions.md` model: `main` moves to the newest supported Minecraft version and `1.21.11/stable` is cut for the old one. (This is my reading of "keep the option that lets us work on later versions"; confirm when the move actually happens.)
- The 26.1 port, including reading upstream's 26.1.2 jar as a decompiled reference, belongs to the **release after the first one**, not to the current one.
- Version names: the usual Minecraft mod scheme, same as 1.21.11: `<modver>+<mc>`, tag `v<modver>+<mc>`.

## 1. Vanilla 26.x

| Fact | Source |
|---|---|
| 26.1 (2026-03-24) is the first version that requires Java 25 and is not obfuscated (Mojang names). It is still OpenGL only. Its light-level algorithm was rewritten. | [Minecraft Wiki, 26.1](https://minecraft.wiki/w/Java_Edition_26.1) |
| 26.2 (2026-06-16) ships an experimental Vulkan renderer. Option "Graphics API": Default, Prefer Vulkan, Prefer OpenGL, with fallbacks both ways and an automatic reset after a startup crash. Stated requirement: Vulkan 1.2 with dynamic rendering and push descriptors, "may increase or decrease". | [Minecraft Wiki, 26.2](https://minecraft.wiki/w/Java_Edition_26.2) |
| Mojang's new minimum requirements (reported 2026-07-21): Vulkan 1.3 capable GPU with 2 GB VRAM. OpenGL removal date: none found. | [GamingOnLinux](https://www.gamingonlinux.com/2026/07/minecraft-java-edition-system-requirements-get-bumped-up-ahead-of-vulkan-support/) |
| Fabric meta API today: stable game versions 26.3, 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11. Loader 0.19.5. | Fabric meta |

For the R7 350 (Vulkan 1.2.178, no `VK_KHR_dynamic_rendering`, see ROADMAP `vulkaninfo`): vanilla 26.2 should fall back to OpenGL (**unverified**, needs the card). If Mojang removes OpenGL, the card cannot run vanilla at all.

## 2. 26.1 port facts

- **Upstream source:** none public. The upstream `dev` branch is 1.21.11 (last commit 2026-08-10), with no 26.x branch or tag. A binary `VulkanMod-0.6.8+26.1.2.jar` is on [Modrinth](https://modrinth.com/mod/vulkanmod); 26.2 exists only as Discord test builds (upstream issue #866). That jar still has the `Unsupported extension:` check and `beginDynamicRendering`, so it most probably still needs `VK_KHR_dynamic_rendering` (**unverified**, class strings only).
- **Toolchain** ([Fabric blog](https://fabricmc.net/2026/03/14/261.html)): Gradle 9.4.0, Loom 1.15 with plugin `net.fabricmc.fabric-loom` (no remapping: `modImplementation` becomes `implementation`, `remapJar` becomes `jar`), JDK 25, no Yarn. Our build already uses official Mojang mappings.
- **Compile probe** (throwaway edit, reverted): JDK 25.0.4, Gradle 9.4.0, Loom 1.15.5, Fabric API 0.155.3+26.1.2, loader 0.19.5. Build fixes needed first: Fabric API module `fabric-block-view-api-v2` is now `fabric-block-getter-api-v2`, `fabric-resource-loader-v0` is gone, and the access widener header `named` must be `official`. Then `compileJava` gives about 525 distinct errors in about 150 of 321 Java files (80 in `render`, 50 in `mixin`, 10 in `vulkan`, 8 in `config`). Main causes: the Fabric Rendering API rework (`frapi` package and mixins), Blaze3D changes (`GpuDevice`, `CommandEncoder`, `RenderPass`, `ChunkSectionLayer`, 238 `@Override` errors), moved GUI render-state packages, and a newer LWJGL than our 3.3.3 (inferred, **unverified**). This is a real port, not a version bump.
- **Order of work, when it starts:** build config and CI to JDK 25; compile `vulkan` and `config`; then Blaze3D mixins and `render.engine`; then FRAPI; then chunk building and light classes; then lavapipe checks (`tools\smoke-run.ps1`); R7 checks with the owner.
- **Licence rules:** no Sodium code, nothing from unlicensed repos, no `initialLayout=UNDEFINED` change (`AGENTS.md`). Upstream's jar is LGPL-3.0, so reading it decompiled is allowed by the owner's decision above; write our own code, keep upstream's notices and the modification notice on changed upstream files.

## 3. OpenGL + Sodium on this GPU

- Sodium's Modrinth page says it officially supports desktop GPUs with up-to-date drivers for **OpenGL 4.5 or newer**, and lists "AMD Radeon HD 7000 Series (GCN 1) or newer". Source: [Sodium on Modrinth](https://modrinth.com/mod/sodium). The R7 350 is GCN 1; whether driver 23.11.1 reports OpenGL 4.5+ is **unverified** (check the GL line in F3).
- Sodium 0.9.2 exists for Fabric 26.1.2, 26.2 and 26.3, and its page says 0.9.x for 26.2 has "early support for Vulkan". Whether its OpenGL path stays on 26.2+ is not stated (**unverified**).
- Sodium is PolyForm Shield 1.0.0 since 2024-04-06: fine to use as a separate mod, never to copy from (`THIRD_PARTY_NOTICES.md`).
- On the R7 under 1.21.11 and 26.1.x, OpenGL + Fabric + Sodium needs none of voolkan's work. Comparing it with voolkan needs PresentMon captures on the R7 (`docs/presentmon.md`).

## 4. To check later (needs the R7 or the owner)

1. `vulkaninfo` on the R7 (ROADMAP `vulkaninfo`): API version, `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`.
2. OpenGL version string on the R7 (F3 under 1.21.11).
3. Which backend vanilla 26.2 picks on the R7.
4. Whether Sodium keeps an OpenGL path on 26.2+.
