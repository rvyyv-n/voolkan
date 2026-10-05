# Upstream PR plan (ROADMAP 5.2, plan only)

Nothing here has been opened or sent. Opening any PR needs the owner's go-ahead. Written against voolkan `pass-5` and upstream `dev` as fetched on 2026-10-05.

## Goal

Give upstream VulkanMod the parts of voolkan that help everyone, as small PRs, so the fork carries less. Upstream stays LGPL-3.0, same as us.

## What to extract (in this order)

| # | PR | Our commits | Files (upstream paths) | Why upstream may want it |
|---|---|---|---|---|
| 1 | Readable device errors and capability log | `3b14249` | `Vulkan.java`, `DeviceManager.java` | small, no behaviour change, helps bug reports |
| 2 | Swapchain: prefer a BGRA format and log it | `59e20ba` | `SwapChain.java` | fixes swapped colours on drivers that list RGBA first (seen on lavapipe) |
| 3 | Image layout and copy fixes | `7f077f5` (split: per-mip layout tracking; `copyTextureToTexture`; real fences) | `VulkanImage.java`, `ImageUtil.java`, `VkCommandEncoder.java`, `Renderer.java` | correctness fixes that do not depend on the render path. Split into three PRs, review is easier |
| 4 | Runtime render path detection | `cf44a00` (`dev.voolkan.compat.RenderPath` would move into `net.vulkanmod`) | `Device.java`, `DeviceManager.java`, `Vulkan.java`, `Config.java`, `Options.java`, `WorldRenderer.java` | makes `VK_KHR_dynamic_rendering` optional instead of a hard requirement; the main reason this fork exists |
| 5 | Legacy `VkRenderPass` path fixes | `64b60a2`, `729d331`, `e4841e4`, `ee07d01` | `RenderPass.java`, `Framebuffer.java`, `PipelineState.java`, `GraphicsPipeline.java`, `Renderer.java`, `DefaultMainPass.java`, `MainPass.java` | only useful together with PR 4; send after it is accepted or as one stacked PR |
| 6 | Pipeline cache on disk (optional) | `c57354b` (`dev.voolkan.cache.PipelineCacheStore` moves to `net.vulkanmod`) | `Pipeline.java`, `Config.java` | faster second start. Add after the others, upstream may already have its own approach |

Not for upstream: renaming and branding, the `UpdateChecker` change, Presets and Compatibility GUI blocks, the frame queue / low-latency options (a design choice for PvP; could be offered later as a separate discussion), docs and tools.

Upstream fix PRs we already reimplemented from their descriptions (#827, #798, #872, #870, commit `e223e37`) are theirs. Do not send them back.

## How to prepare each PR

1. Branch from `upstream/dev` (not from our `main`): `git switch -c pr/<topic> upstream/dev`.
2. Re-apply our change by hand or with `git cherry-pick -n <sha>` and resolve conflicts. Drop our modification notices and voolkan-only names (`dev.voolkan`, `voolkan.*` properties become `vulkanmod.*` or plain config keys), and follow upstream's code style.
3. `git cherry-pick -x` is for upstream-to-us only. For us-to-upstream write a normal commit message and mention the problem, the hardware and how it was tested.
4. Test on the upstream branch: `gradlew build`, then the smoke run on lavapipe with the validation layer (`docs/smoke-test.md`). Per-PR evidence: validation output before and after, and for the legacy path the R7 profile run.
5. Check AGENTS.md licence rules: no Sodium code, nothing copied from unlicensed repos, no Static-VulkanMod `initialLayout=UNDEFINED` change.

## Risks

- **Upstream moved.** `upstream/dev` has 10 commits past our base, including "Refactor PipelineState", "Refactor instance e device classes" and "Implement multi image view formats". 13 Java files we changed were also changed upstream (`Device`, `DeviceManager`, `Vulkan`, `Renderer`, `PipelineState`, `GraphicsPipeline`, `Pipeline`, `SwapChain`, `VulkanImage`, `ImageUtil`, `VkCommandEncoder`, `WorldRenderer`, `ModSettingsRegistry`; `build.gradle` and `gradle.properties` too). Expect real conflicts in PRs 1, 3, 4 and 5. Run `tools\upstream-check.ps1 -TrialRebase` first to see the size of the problem.
- **Upstream may already be fixing the same things.** Check `upstream/dev` and open issues/PRs before each PR, and do not duplicate.
- **Hardware evidence is thin.** The legacy path is verified on lavapipe (forced legacy, R7 profile). The world, F3, fullscreen and post-effect checks on the real R7 (ROADMAP 1.7, M1) are still open. Do not send PRs 3-5 before M1; maintainers will ask whether it works on real hardware.
- **Maintainer appetite.** Upstream targets modern GPUs, and the legacy path adds code they must maintain. Open an issue or discussion first and offer the PR 4+5 split (detection, then legacy path), keeping the legacy path off by default on GPUs that support dynamic rendering.
- **Attribution and licence.** Our commits stay under LGPL-3.0, compatible with upstream. Authorship follows the repo's configured identity; no AI attribution lines in commits (project rule).
- **Maintenance cost for voolkan.** After upstream takes a PR, rebase the fork onto the new upstream so the code is not kept twice (see `docs/upstream.md`, "Move to a newer upstream base").

## Decision for the owner

Whether to contact upstream at all, and whether PR 1-3 (small fixes) go first as goodwill before the render-path PRs. Suggested order: after M1, send PRs 1-2, then open an issue about PR 4+5.
