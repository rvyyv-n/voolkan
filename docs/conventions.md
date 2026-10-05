# Conventions

## Branches

| Branch | Use |
|---|---|
| `main` | newest supported Minecraft version (1.21.11 now) |
| `<mc>/stable` | maintenance line for an older Minecraft version, e.g. `1.21.11/stable`; cut when `main` moves to a newer MC; bug fixes only, cherry-picked from `main` |
| `feat/*` | feature work, e.g. `feat/render-path` |
| `fix/*` | bug fixes, e.g. `fix/framebuffer-mip-level` |
| `setup/*` | tooling, docs, repo setup, e.g. `setup/phase-0` |
| `wip/*` | throwaway worktree branches |

Branches are short-lived and merged into `main`. `upstream/*` remote branches are read-only. Tag `upstream-base` marks the upstream commit we are based on.

## Tags and versions

- Release tags: `v<modver>+<mc>`, e.g. `v0.6.8-voolkan.1+1.21.11`. The mod version is `<upstream version>-voolkan.<n>` until we diverge enough to number independently.
- Don't reuse or move release tags. `upstream-base` is the only tag that moves (see `upstream.md`).

## Commits

- Imperative, short subject (<= 72 chars), no trailing period: `Fix depth-only attachment index in legacy RenderPass`.
- Optional body explains why. One logical change per commit.
- Prefix with an area when helpful: `render:`, `vulkan:`, `config:`, `tools:`, `docs:`, `ci:`.
- Cherry-picks from upstream use `git cherry-pick -x`.
- No AI attribution (no Co-Authored-By, no "generated with").

## Identity

- Mod id: `voolkan`, name "voolkan", `"provides": ["vulkanmod"]` in `fabric.mod.json` (task 1.6). Existing `vulkanmod.*` asset namespaces, mixin config and access widener names stay as upstream for now to keep diffs small.

## Packages

- Upstream code stays in `net.vulkanmod` (keeps rebases and cherry-picks clean).
- New voolkan-only code goes in **`dev.voolkan`** (proposed), e.g. `dev.voolkan.compat` (render-path detection), `dev.voolkan.latency`. Small changes to upstream classes stay in their upstream package.

## Config and properties

- System properties: `voolkan.*`, e.g. `-Dvoolkan.renderPath=legacy`, `-Dvoolkan.validation=true`.
- Config keys: `voolkan.*` for new options; existing keys unchanged.
- Env vars for tooling: `VOOLKAN_*` (see `docs/dev-setup.md`).

## LGPL modification notice

Upstream VulkanMod is LGPL-3.0. Every upstream file we change gets this comment directly below the existing header/package line (or at the top if none), once, with the year of the first change:

```java
/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * <one-line summary of the change>.
 */
```

New files under `dev.voolkan` use a standard LGPL-3.0 header naming "voolkan". Don't remove existing copyright notices. `LICENSE` stays; `COPYING` (GPL-3.0 text) is added at release time (Phase 5.1).
