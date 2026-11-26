# Settings presets (task 4.3, updated in Pass 17)

The Video page has a **Preset** row (Custom / Performance / Competitive / Balanced / Quality / Battery). Choosing one only *stages* values in the matching active options across all pages; nothing changes until the user presses Apply, and Undo drops them. The row returns to Custom after Apply and when the screen opens. Values live in `dev.voolkan.gui.Preset`.

Not touched by any preset: window mode, resolution, refresh rate (except Performance & Competitive in exclusive fullscreen), world resolution, GUI scale, brightness, attack/autosave indicator, inactivity FPS limit, graphics preset (vanilla Fast/Fancy), chunk update priority, culling toggles other than the culling mode, unique opaque layer, indirect draw, builder threads, F3 frame-time toggle, device selector.

| Option | Performance | Competitive | Balanced | Quality | Battery |
|---|---|---|---|---|---|
| Refresh rate (exclusive fullscreen) | highest offered | highest offered | unchanged | unchanged | unchanged |
| VSync | off | off | on | on | on |
| Adaptive VSync (when supported) | off | off | unchanged | unchanged | off |
| Max framerate | unlimited (260) | unlimited (260) | 120 | 144 | 60 |
| Background FPS cap | unchanged | unchanged | unchanged | unchanged | 15 |
| Battery FPS cap (when supported) | unchanged | unchanged | unchanged | unchanged | 60 |
| Low latency mode | on | on | off (default) | off | off |
| Render queue size | 1 | 1 | 2 (default) | 2 | 2 |
| Swapchain images | unchanged | 2 | auto (0) | auto (0) | auto (0) |
| Render distance | 2 (min) | 8 | 12 | 16 | 8 |
| Simulation distance | 5 (min) | 6 | 8 | 10 | 6 |
| Texture filtering | none | none | none | anisotropic | none |
| Max anisotropy | 2x | 2x | 2x | 4x | 2x |
| Particles | minimal | decreased | decreased | all | decreased |
| Particle limit | 500 | 250 | unlimited (0) | unlimited (0) | 500 |
| Clouds | off | off | fast | fancy | off |
| Cloud distance | 2 (min) | 2 | 32 | 64 | unchanged |
| Cutout leaves | off | off | on | on | on |
| Chunk fade | 0 | 0 | 0.5 s | 1 s | 0 |
| Ambient occlusion | off | off | smooth | sub-block | smooth |
| Biome blend | 1x1 | 1x1 | 5x5 | 9x9 | 5x5 |
| Entity shadows | off | off | on | on | off |
| Entity distance | 0.5x (min) | 1.0x | 1.0x | 1.5x | 1.0x |
| Block entity distance | 16 blocks | 48 blocks | 64 blocks | 64 blocks | 32 blocks |
| Mipmap levels | 0 | 2 | 2 | 4 | 2 |
| Weather radius | 3 (min) | 3 | 5 | 8 | 3 (min) |
| Vignette | off | off | on | on | on |
| Advanced chunk culling | aggressive | aggressive | normal | conservative | normal |
| Chunk upload budget | auto (0) | auto (0) | auto (0) | auto (0) | auto (0) |
| Texture animations | off | on | on | on | on |

Notes:

- Performance is the absolute minimum of every option it covers (render distance 2, entity distance 0.5x, etc.), chosen by the owner; Balanced and Quality were set by judgement.
- Competitive prioritizes minimum input latency (VSync off, uncapped FPS, render queue 1, low latency on, swapchain images 2) while keeping playable PvP visibility (render distance 8, 1.0x entity distance, 48-block block entities, 250 particle cap, animated textures on).
- Quality is sized for a 2 GB card (render distance 16, 4x anisotropy).
- Performance, Competitive, Balanced, and Quality leave Background FPS cap and Battery FPS cap unchanged (`null`).
- Battery caps framerate at 60 with VSync on, lowers Background FPS cap to 15, and stages Battery FPS cap to 60 when the platform supports battery detection (hidden and skipped otherwise).
