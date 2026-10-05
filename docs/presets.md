# Settings presets (task 4.3)

The Video page has a **Preset** row (Custom / Performance / Balanced / Quality). Choosing one only *stages* values in the options below; nothing changes until the user presses Apply, and Undo drops them. The row always shows Custom when the screen opens. Values live in `dev.voolkan.gui.Preset`.

Not touched by any preset: window mode, resolution, refresh rate, GUI scale, brightness, attack/autosave indicator, inactivity FPS limit, graphics preset (vanilla Fast/Fancy), chunk update priority, culling toggles other than the culling mode, unique opaque layer, indirect draw, builder threads, swapchain images, device selector.

| Option | Performance | Balanced | Quality |
|---|---|---|---|
| Refresh rate (exclusive fullscreen) | highest offered | unchanged | unchanged |
| Max framerate | unlimited (260) | 120 | 144 |
| VSync | off | on | on |
| Render distance | 2 (min) | 12 | 16 |
| Simulation distance | 5 (min) | 8 | 10 |
| Texture filtering | none | none | anisotropic |
| Max anisotropy | 2x | 2x | 4x |
| Particles | minimal | decreased | all |
| Clouds | off | fast | fancy |
| Cloud distance | 2 (min) | 32 | 64 |
| Cutout leaves | off | on | on |
| Chunk fade | 0 | 0.5 s | 1 s |
| Ambient occlusion | off | smooth | sub-block |
| Biome blend | 1x1 | 5x5 | 9x9 |
| Entity shadows | off | on | on |
| Entity distance | 0.5x (min) | 1.0x | 1.5x |
| Mipmap levels | 0 | 2 | 4 |
| Weather radius | 3 (min) | 5 | 8 |
| Vignette | off | on | on |
| Advanced chunk culling | aggressive | normal | conservative |
| Render queue size | 1 | 2 (default) | 2 |
| Low latency mode | on | off (default) | off |
| Texture animations | off | on | on |

Notes:

- Performance is the absolute minimum of every option it covers (render distance 2, entity distance 0.5x, etc.), chosen by the owner; Balanced and Quality were set by judgement.
- Performance also sets render queue 1, low latency on, VSync off and uncapped framerate. Check queue size 1 on the R7.
- Quality is sized for a 2 GB card (render distance 16, 4x anisotropy).
