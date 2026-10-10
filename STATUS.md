# Status (open work only)

Read this file first. It is the short list; `ROADMAP.md` is the full history, so grep it by tag instead of reading it. Update this file whenever an item opens, closes or changes owner.

Last updated: 2026-10-11. Milestone `first-world`, `r7-checklist`, `vulkaninfo`, and `harness-final` are verified on hardware and done.

## Needs the owner on the R7
| Tag | Task | Detail |
|---|---|---|
| `presentmon` | Take the R7 frame-time captures | `docs/presentmon.md`, script ready |

## Ready for owner go-ahead
| Tag | Task | Detail |
|---|---|---|
| `upstream-pr` | Open the upstream PR (render-path detection + fixes) | plan in `docs/upstream-pr-plan.md` |
| `modpack` | Modpack setup for multiplayer server testing | server test parked for modpack |

## Minor
| Tag | Task | Detail |
|---|---|---|
| `apache-text` | Verbatim Apache-2.0 text stays out of `licenses/` (commit hook rejects a phrase); URL reference stands in | no action unless the hook changes |

## Parked by owner decision
| Tag | Task | Detail |
|---|---|---|
| `settings-ui` | Settings UI overhaul | `docs/settings-ui-proposal.md` |
| `port-26-1` | 26.1 port, in the release after the first | `docs/mc-26-plus.md` |
| `port-26-2` | 26.2 and later, waits for an owner plan | `docs/mc-26-plus.md` |
| `low-vram` | 2 GB VRAM handling, only if the R7 shows memory problems | not started |

## Old number to tag
0.5 `harness-final`, 0.6 `vulkaninfo`, 1.7 `r7-checklist`, M1 `first-world`, 3.3 `presentmon`, 5.2 `upstream-pr`, 4.5 `settings-ui`, 6.1 `port-26-1`, 6.2 `port-26-2`, 7.1 `low-vram`.
