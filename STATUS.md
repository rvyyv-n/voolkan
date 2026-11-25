# Status (open work only)

Read this file first. It is the short list; `ROADMAP.md` is the full history, so grep it by tag instead of reading it. Update this file whenever an item opens, closes or changes owner.

Last updated: 2026-11-25 (first real Apple Silicon test passed on M3 MacBook Air / macOS Tahoe 26.6.2 / MoltenVK 1.2.6 across ~1h45m with zero crashes; notched-MacBook borderless fullscreen top cutoff fixed and `apple-m.json` calibrated from M3 startup log on `feat/v1-5-macos`; next: dedicated macOS deep performance pass, borderless + uncapped Round 3 re-test, and R7 350 regression check before v1.5 release). Milestone `first-world`, `r7-checklist`, `vulkaninfo`, `harness-final`, `modpack`, and `release-1.0` (v1.0.0+1.21.11) complete.

## Released
- **voolkan 1.0.0+1.21.11**: Full release tag `v1.0.0+1.21.11` published with release jar, sources, and sanitized companion modpack (`.mrpack`).

## Skipped for later
| Tag | Task | Detail |
|---|---|---|
| `presentmon` | Take the R7 frame-time captures | `docs/presentmon.md`, script ready; skipped for later |

## Minor
| Tag | Task | Detail |
|---|---|---|
| `apache-text` | Verbatim Apache-2.0 text stays out of `licenses/` (commit hook rejects a phrase); URL reference stands in | no action unless the hook changes |

## Parked by owner decision
| Tag | Task | Detail |
|---|---|---|
| `settings-ui` | Settings UI overhaul + QoL features (first in parked queue; owner will describe the extra QoL items when built) | `docs/settings-ui-proposal.md` |
| `port-26-1` | 26.1 port, in the release after the first | `docs/mc-26-plus.md` |
| `port-26-2` | 26.2 and later, waits for an owner plan (parked at the very end) | `docs/mc-26-plus.md` |

## Future (v1.5)
Plan: `docs/v1-5-plan.md`. Owner decisions D1-D5 there are defaults until confirmed. All v1.5 passes land on the `plan/v1-5` branch (Windows/base passes first, then macOS). Automated verification on headless builder runners is strictly `gradlew build` (no window/client execution). In-game validation on Windows is performed manually by the owner; Mac testing (Rounds 1-3) is performed manually on M4/M5 MacBook Airs by the Mac tester.

| Tag | Task | Detail |
|---|---|---|
| `qol-upstream` | Pass 8: four upstream cherry-picks (fullscreen exclusive, screenshot sync, image view formats, settings registry) | built & R7-verified on `plan/v1-5` (FSE opt-out made opt-in via `disallowFullscreenExclusive`) |
| `qol-small` | Pass 9: memory on F3, shader binary cache, hardware blit | built & R7-verified on `plan/v1-5` |
| `frame-caps` | Pass 10: precise limiter, background cap, battery-cap hook, Battery preset | built & R7-verified on `plan/v1-5` |
| `res-scale` | Pass 11: resolution scale for the world | built, reviewed & R7-verified on `plan/v1-5` |
| `pipeline-warmup` | Pass 12: build pipelines during world load | built & R7-verified on `plan/v1-5` |
| `qol-options` | Pass 13: block-entity distance, restore defaults per page | built & R7-verified on `plan/v1-5` |
| `fps-opt` | Post-Pass 13 FPS optimization sweep (regression fixes, SPIR-V opts, state/descriptor cache, shared SectionData slab, async semaphore uploads, BAR streaming buffers, hot-path de-allocation) | merged & R7-verified on `plan/v1-5` (`66e9675..a5ed6a4`) |
| `mac-boot` | Pass 14: macOS starts (portability, depth format, logic-op guard, Apple profile) | M3 MacBook Air verified (MoltenVK 1.2.6 / Vulkan 1.2.261, 1h45m zero crashes, `apple-m.json` calibrated from startup log); awaiting `vulkaninfo` & R7 regression check |
| `mac-window` | Pass 15: Mac window, Retina, fullscreen, present modes, latency defaults, Mac battery probe, tester checklist | M3 MacBook Air tested; fixed notched-MacBook borderless fullscreen top cutoff via `glfwGetMonitorWorkarea` + non-resizable borderless; awaiting re-test & R7 regression check |
| `mac-perf` | Pass 16: tile-GPU attachment handling, unified-memory writes, MoltenVK diagnostics & indirect-draw default | M3 MacBook Air smoke-verified (60+ FPS VSync / 80-100 FPS tuned at full Retina 16-chunk; Indirect Draw OFF slightly ahead); awaiting uncapped VSync-off A/B, macOS deep perf sweep & R7 regression check |

## Old number to tag
0.5 `harness-final`, 0.6 `vulkaninfo`, 1.7 `r7-checklist`, M1 `first-world`, 3.3 `presentmon`, 4.5 `settings-ui`, 6.1 `port-26-1`, 6.2 `port-26-2`.
