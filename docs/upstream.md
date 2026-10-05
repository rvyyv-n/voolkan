# Working with upstream VulkanMod

voolkan is an unofficial fork of [xCollateral/VulkanMod](https://github.com/xCollateral/VulkanMod) (LGPL-3.0). Upstream history is kept intact: it is required for attribution and for rebasing.

## Layout

- Remote `upstream` = `https://github.com/xCollateral/VulkanMod.git` (read-only reference; never push to it).
- Tag `upstream-base` = `7f69043` ("Fix typo"), the last upstream commit that matches the official `0.6.8+1.21.11` release.
  Upstream later rewrote history, so the commit that bumps the version (`d3db079`) now sits above later work; we re-apply only its `gradle.properties` diff as our first commit.
- `git log upstream-base..main` shows exactly our changes.

## Fetch

```powershell
git fetch upstream --tags
git log --oneline upstream-base..upstream/dev     # what upstream has done since our base (branch name may differ; check `git branch -r`)
```

## Take specific upstream fixes (preferred)

```powershell
git switch -c fix/upstream-<topic> main
git cherry-pick -x <upstream-sha>      # -x records the original SHA in the message
```

Upstream fixes are often written against `dev`, so expect to adapt them by hand. Keep the `-x` line and add the LGPL modification notice if you changed the file (see `conventions.md`).

## Move to a newer upstream base (rare)

Only when we deliberately adopt a newer upstream state:

```powershell
git fetch upstream --tags
git switch -c setup/rebase-<sha> main
git rebase --onto <new-upstream-sha> upstream-base      # replays our commits onto the new base
# resolve conflicts, build, run the smoke test (docs/smoke-test.md)
git tag -f upstream-base <new-upstream-sha>              # only after the rebased branch is accepted
```

Check first: does the new base still match an official release jar? Does it keep `VK_KHR_dynamic_rendering` optional? Re-verify the line refs in the plan.

## Rules

- Do not copy code from Sodium (licence not LGPL-compatible) or from repos without a licence (e.g. Roti-Poulet/VulkanExtended).
- Do not copy Static-VulkanMod's `initialLayout=UNDEFINED` change: it wipes `LOAD` passes.
- Do not push to upstream or open PRs without the owner's go-ahead (Phase 5.2 plans an upstream PR later).
