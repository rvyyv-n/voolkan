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

Record the result (date, commit, run type, pass/fail, notes) in the PR or commit message.
