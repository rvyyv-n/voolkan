# Modrinth listing (draft, not published)

Draft text for a Modrinth project page. Nothing has been uploaded. Publishing is the owner's decision (see ROADMAP 5.0 and 5.1). Check the facts below against the release you actually publish.

## Project fields

| Field | Value |
|---|---|
| Name | voolkan |
| Slug (suggested) | `voolkan` |
| Summary (max 256 chars) | Unofficial VulkanMod fork for Minecraft 1.21.11 with a legacy render path for older GPUs without VK_KHR_dynamic_rendering (for example AMD Radeon R7 350), plus stability and low-latency work. Windows only. |
| Categories | Optimization |
| Environment | Client |
| Loader | Fabric (Loader 0.14.14 or newer) |
| Game version | 1.21.11 |
| Licence | LGPL-3.0-only (`LGPL-3.0-only` in the licence field; link `https://github.com/rvyyv-n/voolkan/blob/main/LICENSE`) |
| Source | `https://github.com/rvyyv-n/voolkan` |
| Issues | `https://github.com/rvyyv-n/voolkan/issues` |
| Wiki / Discord / donations | leave empty (no voolkan Discord or donation page; do not link VulkanMod's) |
| Icon | `icon.png` in the repo root (the voolkan logo) |
| Release channel | Beta for `1.0.0-beta.1+1.21.11` |

## Description (Markdown)

```markdown
# voolkan

voolkan is an **unofficial fork of [VulkanMod](https://github.com/xCollateral/VulkanMod)** by xCollateral and contributors. It is not affiliated with or endorsed by the VulkanMod project, Mojang, Microsoft or FabricMC. All credit for the Vulkan renderer goes to the VulkanMod authors; please support them.

**Source code: https://github.com/rvyyv-n/voolkan** (LGPL-3.0). Changes against upstream are listed in the git history (`git log upstream-base..main`), and every changed upstream file carries a modification notice.

## Why a fork

VulkanMod 0.6.8 for 1.21.11 needs `VK_KHR_dynamic_rendering`. Some older GPUs (the target here is an AMD Radeon R7 350, GCN 1, Vulkan 1.2.178 driver) do not have it, so the game stops at startup with `Unsupported extension: [VK_KHR_dynamic_rendering]`. voolkan repairs VulkanMod's older `VkRenderPass` path so it can be used instead, and picks the path automatically at startup.

## What is different

- **Legacy render path** (render passes and framebuffers) for GPUs without dynamic rendering. Chosen automatically, or forced with `-Dvoolkan.renderPath=legacy`.
- **Stability:** per-mip image layout tracking, working texture copies and real fences, and fixes ported from upstream pull requests.
- **Latency and speed:** frame queue of 1, optional low-latency mode, swapchain image count option, pipeline cache saved to disk.
- **Pass 7 optimizations:** early-Z terrain shader pre-pass for fast depth rejection, 2.5 ms chunk upload time budgeting, and thread pool scaling.
- **Compatibility block** on the Video settings page, a **Presets** row (Performance, Balanced, Quality) and voolkan branding.
- Startup capability log and readable errors.

## Requirements

- Minecraft **1.21.11**, Fabric Loader, **Windows only** for now.
- A GPU and driver with Vulkan 1.2.
- voolkan replaces VulkanMod (it declares `provides: vulkanmod`). Do not install both. Sodium and other mods that replace the renderer are not compatible, same as VulkanMod.

## Status

Beta. Fully verified and tested in-world on real hardware (AMD Radeon R7 350, Vulkan 1.2.178 driver 23.11.1) and lavapipe. Milestone `first-world` and in-game smoke checks passed with clean validation, swapchain rebuilds, and stable entity/terrain rendering. Please report issues on the GitHub issue tracker with your `latest.log` and GPU model.

## Licences and credits

voolkan is licensed under the GNU LGPL-3.0. It is built on VulkanMod (LGPL-3.0). It contains code adapted from Fabric API (Apache-2.0) and, through VulkanMod, from Sodium as it was licensed under LGPL-3.0 in early 2024, and bundles Fabric API modules and LWJGL (BSD-3-Clause). Details: `THIRD_PARTY_NOTICES.md` and `licenses/` in the source repository.
```

## Before publishing (owner checks)

- Confirm the GitHub repo is public and that `main` contains the release commit and the tag `v1.0.0-beta.1+1.21.11`.
- Confirm the "Status" paragraph still matches the R7 test results (ROADMAP `r7-checklist` and `first-world` are open at the time of writing).
- Check Modrinth's current content rules for forks and for mods that use another mod's name, and use the text above ("unofficial fork", link to the original) as the disclosure.
- Decide whether to mention VulkanMod's donation or Discord links (the draft omits them on purpose).
- Version file name: `voolkan-1.0.0-beta.1+1.21.11.jar` (check the name in `build/libs/` after `gradlew build`).
- Dependencies on the Modrinth version page: Fabric Loader only; mark VulkanMod as **incompatible**.
