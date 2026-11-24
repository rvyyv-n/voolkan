# Settings presets (task 4.3)

The Video page has a **Preset** row (Custom / Performance / Balanced / Quality / Battery). Choosing one only *stages* values in the options below; nothing changes until the user presses Apply, and Undo drops them. The row always shows Custom when the screen opens. Values live in `dev.voolkan.gui.Preset`.

Not touched by any preset: window mode, resolution, refresh rate (except Performance), world resolution, GUI scale, brightness, attack/autosave indicator, inactivity FPS limit, graphics preset (vanilla Fast/Fancy), chunk update priority, culling toggles other than the culling mode, unique opaque layer, indirect draw, builder threads, swapchain images, device selector.

| Option | Performance | Balanced | Quality | Battery |
|---|---|---|---|---|
| Refresh rate (exclusive fullscreen) | highest offered | unchanged | unchanged | unchanged |
| Max framerate | unlimited (260) | 120 | 144 | 60 |
| VSync | off | on | on | on |
| Background FPS cap | unchanged | unchanged | unchanged | 15 |
| Battery FPS cap | unchanged | unchanged | unchanged | 60 (when supported) |
| Render distance | 2 (min) | 12 | 16 | 8 |
| Simulation distance | 5 (min) | 8 | 10 | 6 |
| Texture filtering | none | none | anisotropic | none |
| Max anisotropy | 2x | 2x | 4x | 2x |
| Particles | minimal | decreased | all | decreased |
| Clouds | off | fast | fancy | off |
| Cloud distance | 2 (min) | 32 | 64 | unchanged |
| Cutout leaves | off | on | on | on |
| Chunk fade | 0 | 0.5 s | 1 s | 0 |
| Ambient occlusion | off | smooth | sub-block | smooth |
| Biome blend | 1x1 | 5x5 | 9x9 | 5x5 |
| Entity shadows | off | on | on | off |
| Entity distance | 0.5x (min) | 1.0x | 1.5x | 1.0x |
| Block entity distance | 16 blocks | 64 blocks | 64 blocks | 32 blocks |
| Mipmap levels | 0 | 2 | 4 | 2 |
| Weather radius | 3 (min) | 5 | 8 | 3 (min) |
| Vignette | off | on | on | on |
| Advanced chunk culling | aggressive | normal | conservative | normal |
| Render queue size | 1 | 2 (default) | 2 | 2 |
| Low latency mode | on | off (default) | off | off |
| Texture animations | off | on | on | on |

Notes:

- Performance is the absolute minimum of every option it covers (render distance 2, entity distance 0.5x, etc.), chosen by the owner; Balanced and Quality were set by judgement.
- Performance also sets render queue 1, low latency on, VSync off and uncapped framerate. Check queue size 1 on the R7.
- Quality is sized for a 2 GB card (render distance 16, 4x anisotropy).
- Performance, Balanced and Quality leave Background FPS cap and Battery FPS cap unchanged (`null`).
- Battery caps framerate at 60 with VSync on, lowers Background FPS cap to 15, and stages Battery FPS cap to 60 when the platform supports battery detection (hidden and skipped otherwise).
