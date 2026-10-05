# Settings presets (task 4.3): PROPOSED, awaiting owner sign-off

The Video page has a **Preset** row (Custom / Performance / Balanced / Quality). Choosing one only *stages* values in the options below; nothing changes until the user presses Apply, and Undo drops them. The row always shows Custom when the screen opens. Values live in `dev.voolkan.gui.Preset`.

Not touched by any preset: window mode, resolution, refresh rate, GUI scale, brightness, attack/autosave indicator, inactivity FPS limit, graphics preset (vanilla Fast/Fancy), chunk update priority, culling toggles other than the culling mode, unique opaque layer, indirect draw, builder threads, swapchain images, device selector.

| Option | Performance | Balanced | Quality |
|---|---|---|---|
| Max framerate | unlimited (260) | 120 | 120 |
| VSync | off | on | on |
| Render distance | 8 | 12 | 20 |
| Simulation distance | 5 (min) | 8 | 12 |
| Texture filtering | none | none | anisotropic |
| Max anisotropy | 2x | 2x | 8x |
| Particles | minimal | decreased | all |
| Clouds | off | fast | fancy |
| Cloud distance | 2 (min) | 32 | 64 |
| Cutout leaves | off | on | on |
| Chunk fade | 0 | 0.5 s | 1 s |
| Ambient occlusion | off | smooth | sub-block |
| Biome blend | 1x1 | 5x5 | 11x11 |
| Entity shadows | off | on | on |
| Entity distance | 1.0x | 1.0x | 2.0x |
| Mipmap levels | 0 | 2 | 4 |
| Weather radius | 3 (min) | 5 | 10 |
| Vignette | off | on | on |
| Advanced chunk culling | aggressive | normal | conservative |
| Render queue size | 1 | 2 (default) | 2 |
| Low latency mode | on | off (default) | off |
| Texture animations | off | on | on |

Notes and open questions for the owner:

- "Everything at the minimum" is applied literally except where a minimum would hurt PvP: render distance 8 rather than 2, entity distance 1.0x (vanilla) rather than 0.5x, so players stay visible.
- Performance trades visual detail for latency (queue size 1, low latency on, VSync off, uncapped). Queue size 1 stops CPU/GPU overlap; check this on the real R7 before sign-off.
- Balanced is close to the mod defaults. Quality is sized for a 2 GB card; render distance 20 plus 8x anisotropy may be too much on the R7.
