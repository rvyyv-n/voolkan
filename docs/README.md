# Docs map

| Path | What |
|---|---|
| `dev-setup.md` | toolchain, versions, install commands |
| `smoke-test.md` | the manual/automated smoke checklist |
| `upstream.md` | how to fetch/rebase/cherry-pick from upstream VulkanMod |
| `conventions.md` | branches, tags, commits, packages, properties, LGPL notice |
| `modrinth.md` | draft Modrinth listing (not published) |
| `gpu/r7-350/` | vulkaninfo snapshot of the target GPU (owner task 0.6) |

Elsewhere in the repo:

| Path | What |
|---|---|
| `AGENTS.md` | rules for automated agents working here |
| `ROADMAP.md` | phases 0-6 checklist |
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
