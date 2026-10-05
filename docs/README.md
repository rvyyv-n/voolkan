# Docs map

| Path | What |
|---|---|
| `dev-setup.md` | toolchain, versions, install commands |
| `smoke-test.md` | the manual/automated smoke checklist |
| `upstream.md` | how to fetch/rebase/cherry-pick from upstream VulkanMod |
| `conventions.md` | branches, tags, commits, packages, properties, LGPL notice |
| `upstream-pr-plan.md` | plan for sending fixes to upstream VulkanMod (nothing opened) |
| `presets.md` | values of the Video page Presets row |
| `presentmon.md` | how to capture and compare frame times on the R7 |
| `settings-ui-proposal.md` | scope proposal for the settings UI overhaul (4.5, skipped) |
| `line-refs.txt` | plan file:line references checked by `tools\check-linerefs.ps1` |
| `26-1-port-plan.md` | research, measured size and plan for the 26.1 port (6.1, no port done) |
| `release-checklist.md` | steps for a release, including the Modrinth listing |
| `mc-26-2-research.md` | notes on 26.2 Vulkan requirements and OpenGL + Sodium (6.2) |
| `modrinth.md` | draft Modrinth listing (not published) |
| `gpu/r7-350/` | vulkaninfo snapshot of the target GPU (owner task 0.6) |

Elsewhere in the repo:

| Path | What |
|---|---|
| `AGENTS.md` | rules for automated agents working here |
| `ROADMAP.md` | phases 0-7 checklist |
| `tools/` | harness scripts (`run-*.ps1`, `summarise-vk-log.ps1`, `make-test-world.ps1`), `profiles/` |
| `tools/mesa`, `tools/bin` | downloaded tools, git-ignored |
| `test-world/` | test-world notes; the generated world is git-ignored |
| `logs/`, `run/` | run output and the game folder, git-ignored |

## No-AI helper scripts (`tools/`)

| Script | What |
|---|---|
| `smoke-run.ps1` | unattended validation / R7-profile / lavapipe startup runs, VUID diff against `tools/baselines/` (`-SaveBaseline` to record) |
| `upstream-check.ps1` | fetch, list upstream commits since our base, likely conflicts, `-CherryPick <sha>`, `-TrialRebase` |
| `check-notices.ps1` | fail if a changed upstream Java file lacks the LGPL modification notice |
| `check-lang.ps1` | fail if a `vulkanmod.*` / `voolkan.*` string key used in the code is missing from `en_us.json` |
| `check-linerefs.ps1` | verify `docs/line-refs.txt` (plan file:line refs); `-Report` shows where drifted lines moved |
| `presentmon-summary.ps1` | `-Capture -Seconds N` or `-Csv file`: avg/p95/p99/1%-low frame times and latency columns |
