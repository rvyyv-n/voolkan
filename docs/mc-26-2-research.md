# Minecraft 26.2: Vulkan requirements and the OpenGL + Sodium option (ROADMAP 6.2, research notes)

Desk research only, done 2026-10-05 from public pages. Nothing here was tested on the R7. Items marked **unverified** need a check before anyone relies on them.

## 1. What vanilla 26.2 asks of the GPU

| Fact | Source |
|---|---|
| Java Edition 26.2 (released 2026-06-16) ships an experimental Vulkan renderer. The "Graphics API" option has three values: Default, Prefer Vulkan (Experimental), Prefer OpenGL. | [Minecraft Wiki, Java Edition 26.2](https://minecraft.wiki/w/Java_Edition_26.2) |
| The wiki gives the current requirement as **Vulkan 1.2 with dynamic rendering and push descriptors**, and says it may rise or fall over time. | same |
| Fallback: Prefer Vulkan falls back to OpenGL if Vulkan does not work; Prefer OpenGL falls back to Vulkan. A startup crash with Prefer Vulkan resets the setting to Default, and a crash with Default switches it to Prefer OpenGL. | same |
| Under Vulkan, a discrete GPU is preferred over an integrated one. | same |
| Mojang's updated system requirements (reported for 2026-07-21): minimum = a **Vulkan 1.3 capable GPU with at least 2 GB VRAM**, 8 GB RAM (12 GB with integrated graphics), 4 cores, aimed at 1080p30 on Fast. Recommended = 16 GB RAM and 6 GB VRAM. The article says a system without Vulkan "might not" run the next release. | [GamingOnLinux, 2026-07](https://www.gamingonlinux.com/2026/07/minecraft-java-edition-system-requirements-get-bumped-up-ahead-of-vulkan-support/) |
| Other reports say OpenGL stays for now and Mojang plans to remove it once Vulkan is stable and fast enough. No removal date was found. | [VideoCardz](https://videocardz.com/newz/minecraft-java-edition-raises-recommended-memory-to-16gb-ahead-of-vulkan-transition) (secondary; **unverified** against Mojang's own post) |

### What this means for the R7 350

- The R7's Windows driver 23.11.1 exposes Vulkan **1.2.178 without `VK_KHR_dynamic_rendering`** (see `docs/gpu/r7-350/` once the owner adds `vulkaninfo`, ROADMAP 0.6). That fails the wiki's stated requirement (dynamic rendering) and the new "Vulkan 1.3" minimum. Vanilla 26.2 should therefore fall back to OpenGL on this card. **Unverified** until someone runs 26.2 on the R7.
- Push descriptors (`VK_KHR_push_descriptor`) may also be missing on that driver. Check it in the `vulkaninfo` output.
- 2 GB VRAM matches the stated minimum, so VRAM alone is not the blocker.
- The unknown is OpenGL: if Mojang removes it, the R7 cannot run vanilla at all, and the choices are staying on 1.21.11 or the voolkan legacy path ported forward (6.1 and later). The requirement text may change, so recheck before starting 6.1 (see section 3).

## 2. OpenGL + Sodium on this GPU (the alternative to a Vulkan mod)

- Sodium's own Modrinth page (read through the Modrinth API on 2026-10-05) says: "We only provide official support for desktop graphics cards which have up-to-date drivers compatible with OpenGL 4.5 or newer", and lists "AMD Radeon HD 7000 Series (GCN 1) or newer" among the cards that meet this. Source: [Sodium on Modrinth](https://modrinth.com/mod/sodium).
- The R7 350 is a GCN 1 part. Whether driver 23.11.1 exposes OpenGL 4.5 or 4.6 on it is **unverified**: check `F3` (the GL version line) or GPU-Z / OpenGL Extensions Viewer on the R7.
- OpenGL path on the R7 needs none of voolkan's work: install Fabric Loader, Fabric API and Sodium for the target version (and Iris only if shaders are wanted; not needed for PvP).
- Licence note: Sodium has been PolyForm Shield 1.0.0 since 2024-04-06. Using it as a separate mod is fine. Copying its code into voolkan is not (see `THIRD_PARTY_NOTICES.md`, `AGENTS.md`).
- Trade-offs against voolkan: Sodium is mature and maintained for new Minecraft versions quickly; voolkan on the R7 is beta and unfinished. Sodium gives no Vulkan-specific latency work. A fair comparison needs PresentMon captures of both on the R7 (`docs/presentmon.md`): this is the missing evidence.
- Sodium 0.9.x supports 26.1.2, 26.2 and 26.3 on Fabric (Modrinth API: 0.9.2 release for 26.1.2 and 26.2 on 2026-09-11, for 26.3 on 2026-09-15). Its page says (news dated 2026-06-16) that Sodium 0.9.x for 26.2 comes "with early support for Vulkan". So Sodium follows vanilla onto Vulkan. Whether its OpenGL path stays on 26.2+ is not stated on that page (**unverified**). If it does not, OpenGL + Sodium is only an option for 1.21.11 and 26.1.x, and an R7 without `VK_KHR_dynamic_rendering` has no Sodium route on newer versions.

## 3. Verified without the R7 (2026-10-05)

- Stable game versions on the Fabric meta API: 26.3, 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11; 26.4 snapshots exist. Fabric Loader 0.19.5 is current. Fabric API has builds for 26.2 and 26.3 (0.161.0).
- Upstream VulkanMod: no 26.x source branch; `dev` is 1.21.11. A binary 0.6.8+26.1.2 is on Modrinth; 26.2 exists only as test builds on the VulkanMod Discord (issue #866). Details and the measured 26.1 port size: `docs/26-1-port-plan.md`.
- Sodium versions for 26.x: see section 2.
- Not verified: vanilla 26.2 on lavapipe was not run, and nothing here says anything about the R7.

## 4. Checks to do later (owner or the R7 needed)

1. Run `vulkaninfo` on the R7 (ROADMAP 0.6): confirm API version, `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`.
2. Check the OpenGL version/renderer string on the R7 under 1.21.11.
3. When 26.2 or later is wanted: start vanilla with Graphics API = Default and see which backend the log reports.
4. Re-read the 26.x release notes for the Vulkan requirement and for any OpenGL removal date before starting 6.1.
5. If the target ever moves past OpenGL removal, decide between "stay on 1.21.11" and "port the legacy path forward" (6.1).
