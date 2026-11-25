# Smoke test

Run after any render-path change. Use the fixed world (`test-world/README.md`, generate once with `tools\make-test-world.ps1 -AcceptEula`).

## Runs

| Run | Command | Expectation |
|---|---|---|
| Real GPU | `gradlew runClient` | follow the checklist |
| Validation | `tools\run-validation.ps1 -World voolkan-test` | no new VUIDs in `tools\summarise-vk-log.ps1` |
| Lavapipe | `tools\run-lavapipe.ps1 -World voolkan-test` | follow the checklist (slow, CPU-rendered) |
| R7 profile | `tools\run-r7-profile.ps1 -World voolkan-test` | base: fails with `Unsupported extension: [VK_KHR_dynamic_rendering]`; after Phase 1: follows the checklist |

Force the legacy path on a GPU that has dynamic rendering (e.g. lavapipe): set `$env:JAVA_TOOL_OPTIONS='-Dvoolkan.renderPath=legacy'` before the script (the startup log must say `Render path chosen: LEGACY`). `run-r7-profile.ps1` hides the extension, so it picks legacy by itself. `-Dvoolkan.validation=true` enables the validation layer inside the mod.

`-AutoQuitSeconds N` kills the game after N seconds for unattended runs (it only proves startup, not the checklist).

## Checklist

1. [ ] Game starts, main menu renders (panorama, logo, buttons).
2. [ ] Options → Video shows the VulkanMod settings pages.
3. [ ] Open world `voolkan-test` (singleplayer, or `-World` quick-play): terrain, sky and hand render.
4. [ ] F3 overlay opens and closes; GPU/Vulkan info looks right.
5. [ ] Resize the window (drag edges, maximise, restore): no crash, no black/garbled frame.
6. [ ] Toggle fullscreen (F11) and back.
7. [ ] Post effect: spectator mode (`/gamemode spectator`), spectate a creeper (or Esc → spectator menu): the creeper-vision shader renders.
8. [ ] Fast look-around for 30 s: no stutter spikes, no flicker.
9. [ ] Quit to title, quit game: clean exit, no crash report in `run/crash-reports/`.
10. [ ] Validation runs only: `tools\summarise-vk-log.ps1` shows no new error IDs versus the previous baseline.

## v1.5 checks (Passes 8-13)

11. [ ] Fullscreen exclusive opt-out & screenshots (Pass 8): switch Windowed / Borderless / Exclusive Fullscreen and Alt-Tab cleanly without freezing (Windows `VK_EXT_full_screen_exclusive` `DISALLOWED_EXT` is opt-in via `disallowFullscreenExclusive` in config or `-Dvoolkan.disallowFullscreenExclusive=true` for OBS/Discord capture compatibility, default `false` for best R7 fullscreen FPS); take F2 screenshots (at 100% and below 100% world scale) and exit/re-enter a singleplayer world to trigger the world thumbnail, checking colours and orientation.
12. [ ] F3 memory, caches & warm-up (Passes 9, 12): F3 shows live VRAM and shared-memory budget lines; mipmap level changes (0 to 4) reload cleanly; startup log shows chosen heap sizes and pipeline warm-up on world load; second launch hits the on-disk shader and pipeline-state caches with less hitching.
13. [ ] Frame limiter, background cap & Battery preset (Pass 10): capping FPS (e.g. 60, VSync off) gives steady F3 frame-time pacing; unfocusing and minimising drops FPS to Background FPS Cap and lower; Battery FPS Cap stays hidden on Windows; Preset -> Battery stages expected values and reverts cleanly with Undo.
14. [ ] World Resolution scaling (Pass 11): 100% matches v1.0; at 75% and 50%, the 3D world renders upright with normal culling while HUD, F3, crosshair and menus stay native sharp; block outlines, F3+B hitboxes and F3+G chunk borders stay readable; creeper/spider spectator post effects and pause-menu blur work; resizing, F3+T reload and returning to 100% recover cleanly.
15. [ ] Block-entity distance & Restore Defaults (Pass 13): at 16 blocks, nearby chests/signs/banners cull past ~16 blocks while an active beacon beam stays visible; at 128 blocks, chests remain visible past vanilla's 64 blocks; on each built-in settings page, Restore Defaults stages only that page (enabling Apply/Undo), disables when already at defaults, and hides in Search or third-party mod pages.

Record the result (date, commit, run type, pass/fail, notes) in the PR or commit message.
