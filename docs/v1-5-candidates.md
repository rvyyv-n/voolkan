# v1.5 extra candidates

More performance and QoL ideas beyond the ranked list in `v1-5-plan.md`. Approved on 2026-10-18 and moved into the plan: P1, P2, P3, P5, P6, P8, Q4, Q6. The rest (P4, P7, P9, Q1, Q2, Q3, Q5) stay here as a backlog.

Already in voolkan, so not listed: GPU selector, builder threads, entity culling, advanced culling, indirect draw toggle, frame queue, low-latency mode, swapchain image count, presets.

Size: S = one pass target, M = a pass of its own, L = more than one pass.

## Performance

| ID | Idea | Size | R7 | Mac (M4/M5 Air) | Notes |
|---|---|---|---|---|---|
| P1 | **Resolution scale**: draw the world below screen resolution and scale up; GUI stays sharp | M | big FPS gain at 1080p | biggest lever: Air screens are ~2560x1664, so the GPU draws twice the pixels of 1080p | needs an offscreen target and an upscale pass; interacts with post effects (spider/creeper view, blur). Simple bilinear first, no copied upscalers |
| P2 | **Background and battery FPS**: lower the cap when the window is unfocused, hidden or minimised; optional "on battery" cap on Mac | S | less heat when tabbed out | fanless Airs throttle under heat; also saves battery | own code; the existing Dynamic FPS mod (MIT) shows demand, nothing copied |
| P3 | **Pipeline warm-up at load**: build pipelines for known states from the saved cache during world load, not on first use | M | fewer first-use hitches | more so on Mac: Metal compiles shaders at pipeline creation | depends on the pipeline cache (done) and the shader binary cache (Pass 12) |
| P4 | **Chunk upload budget per frame**: spread section uploads over frames when flying or teleporting | S | smooths stutter, eases 2 GB VRAM | smooths stutter | small part of parked `low-vram`; can be done alone |
| P5 | **Precise frame limiter**: replace sleep-based capping with a spin-and-sleep limiter timed to the present | S | steadier frame times when capped | same | needs before/after frame-time data (the Q1 overlay helps) |
| P6 | **Block-entity distance**: separate render distance for chests, signs, banners | S | helps in bases and villages | same | chunk-renderer adjacent; keep it a simple distance check |
| P7 | **Particle limit**: cap particles drawn per frame | S | helps in fights and explosions | same | vanilla's "particles" setting is coarse |
| P8 | **Indirect draw on Mac by default** | S | none (stays off on the R7) | fewer draw calls if MoltenVK handles it well | test only: measure in Mac round 3 before changing defaults |
| P9 | **Adaptive vsync** (`FIFO_RELAXED`) where the driver offers it | S | fewer stutters when FPS dips under refresh with vsync on | not offered by Metal | option hidden when not supported |

## Quality of life

| ID | Idea | Size | Benefit | Notes |
|---|---|---|---|---|
| Q1 | **Frame-time overlay**: small graph plus average and 1% low on F3 (or a toggle key) | S | the Mac testers can report numbers without PresentMon; useful on the R7 too | strongly supports Mac rounds 2 and 3 |
| Q2 | **Copy diagnostics**: one button that copies GPU, driver, MoltenVK version, chosen formats, settings and the last errors to the clipboard or a file | S | one paste answers most bug reports, especially from testers | reuses the startup capability log |
| Q3 | **Friendly startup failure screen**: when Vulkan setup fails, show a plain message (what failed, what to try) instead of a crash log | S | helps Mac users on unsupported setups and Windows users with old drivers | builds on the readable errors from 1.5 |
| Q4 | **Mac battery preset**: a "Battery" entry in the Presets row (60 FPS cap, shorter distance, low-latency off) | S | sensible one-click setting for Airs | small addition to the existing presets |
| Q5 | **Shader warm-up progress** on the loading screen | S | load feels less frozen on first start | only worth it with P3 |
| Q6 | **Restore-defaults per page** in voolkan's settings | S | easy recovery after experimenting | small slice of parked `settings-ui`; owner may prefer to keep that parked |

## Suggested picks

If you want a short list: **P1, P2, Q1, Q2** give the most for the Mac goal (pixels, heat, and test data), and **P4, Q3** are cheap stability wins. P3 and Q5 go together and suit a later pass after the shader cache lands.
