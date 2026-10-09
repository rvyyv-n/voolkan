# voolkan: agent rules

## Start here
Read `STATUS.md` (open work, one page) first. Do not read `ROADMAP.md` or `docs/` in full: grep ROADMAP.md by tag, and use the map in `docs/README.md` to open only the one doc you need. Keep `STATUS.md` current when an item opens, closes or changes owner.

## Goal
voolkan is an unofficial, private (for now) **LGPL-3.0** fork of [VulkanMod](https://github.com/xCollateral/VulkanMod) by xCollateral. It makes Minecraft **1.21.11** run on an **AMD Radeon R7 350** (GCN1, 2 GB VRAM, Windows driver 23.11.1, Vulkan 1.2.178, no `VK_KHR_dynamic_rendering`) by repairing VulkanMod's legacy `VkRenderPass` path, then adds stability and low-latency work. Windows only. PvP features live in a separate mod, not here. The plan is `ROADMAP.md` (phases 0-6, milestone `first-world` = a world renders on the R7).

## Base and upstream
- Base = upstream `7f69043` ("Fix typo", tag `upstream-base`) + the `gradle.properties` version bump. It matches the official VulkanMod 0.6.8+1.21.11 release.
- `upstream` remote is read-only. `git log upstream-base..main` = our changes. Keep upstream history intact. See `docs/upstream.md`, `docs/conventions.md`.
- Upstream code stays in `net.vulkanmod`; new voolkan-only code goes in `dev.voolkan`. Changed upstream files get the LGPL modification notice.

## Build, run, test
Use JDK 21 and the Gradle wrapper only (no global Gradle). `JAVA_HOME` = `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`.
```powershell
.\gradlew build
.\gradlew runClient
tools\run-validation.ps1 [-Lavapipe] [-World voolkan-test] [-AutoQuitSeconds N]
tools\run-r7-profile.ps1          # profiles layer on lavapipe: no dynamic rendering, API 1.2.178
tools\run-lavapipe.ps1
tools\summarise-vk-log.ps1 [path] # group validation messages by VUID
tools\make-test-world.ps1 -AcceptEula   # owner accepts the Mojang EULA
```
Checklist: `docs/smoke-test.md`. Setup details: `docs/dev-setup.md`. This PC's only GPU is the R7, so on the base `runClient` fails with `Unsupported extension: [VK_KHR_dynamic_rendering]` by design until Phase 1.

## Git rules
- Commit locally with the repo's configured identity. One commit per logical step.
- Push only your own work branch (never `main`). Changes reach `main` through a pull request: open it against `main` when the work is finished, with a short description and the test results, and let the owner merge it (or merge when told to). Don't create GitHub repos or releases, and don't push tags, unless the owner says so.
- **No AI attribution** anywhere: no Co-Authored-By, no "generated with", no assistant badges or mentions in commits, PRs or files. Commits are authored by `rvyyv-n` only.
- Never commit or push directly to `main`; work on `feat/*`, `fix/*`, `setup/*` branches (`pass-N` for build passes).
- Ask before anything destructive outside your worktree.

## One folder, one chat
Two chats must never change code in the same folder at once. Before the first edit, commit or branch switch, check `t3_worktree_status` / `t3_thread_list`; if another chat is active in the same folder, move to your own worktree (`t3_worktree_handoff`, `baseRef` = current branch, `startFromOrigin: false`). Dev servers/ports of other chats are not yours. Merge back into the branch you came from, then remove the worktree.

## Model guidance
- **Opus**: rendering correctness, synchronization, memory, architecture and ADRs, anything that decides what ships (versions, merges, tags). 1-3 turns per task; no file reading or test-fix loops.
- **Sonnet 5.5** (medium effort or higher): extended build passes, scripts, docs, summaries, cherry-picks. Opus reviews before anything builds on them.
- **Gemini Flash**: repo scouting, validation and test runs (`tools\run-validation.ps1`, `tools\summarise-vk-log.ps1`), fast debug loops, CI.
- Don't use smaller models or lower effort than Sonnet 5.5 medium for helpers.
- Retire a thread at 8 turns or 150k tokens: commit, then fork from a checkpoint (`t3_thread_fork`) or start fresh. Build passes run in a worktree (`pass-N`).
- Fork checkpoints replace auto-delegation loops. Don't chain `delegate_task` between models.
- Never leave `runClient`, a Gradle daemon you started, or any watcher running at the end of a turn.

## Licence rules
- voolkan is LGPL-3.0. Keep `LICENSE` and upstream copyright notices.
- **Never copy code from Sodium** (licence not LGPL-compatible) **or from unlicensed repos** such as Roti-Poulet/VulkanExtended.
- **Don't copy Static-VulkanMod's `initialLayout=UNDEFINED` change**: it wipes `LOAD` passes.
- Cherry-picks from upstream VulkanMod are fine (same licence); use `git cherry-pick -x`.

## Housekeeping
Large downloads, logs and run folders are git-ignored (`logs/`, `run/`, `tools/mesa/`, `tools/bin/`, `test-world/*/`). Lavapipe is never registered system-wide; scripts use `VK_DRIVER_FILES` per process.
