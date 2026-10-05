# PresentMon captures (frame time and latency)

Phase 3 changes (frame queue, low-latency mode, swapchain image count, pipeline cache) are judged on the R7 with PresentMon, not on lavapipe: lavapipe renders on the CPU, so its timings say nothing about the GPU. Captures are taken by the owner on the R7.

PresentMon console 2.6.0 is installed by `winget` (`docs/dev-setup.md`). The script `tools\presentmon-summary.ps1` finds it, captures, and summarises.

## Capture

1. Close other overlays and recorders (OBS, Medal) so they do not skew the numbers.
2. Start the game on the R7 (`gradlew runClient`, or the launcher) and open the fixed world `voolkan-test` (`docs/smoke-test.md`). Stand still at the spawn point, with the same render distance and window mode for every capture.
3. In a second terminal, from the repo root:

   ```powershell
   tools\presentmon-summary.ps1 -Capture -Seconds 60 -Label baseline
   ```

   While it runs, do the same thing every time: for example 30 s standing still, then 30 s of continuous, fast look-around. Keep the game window visible (a minimised or occluded window does not present).
4. The CSV is written to `logs\presentmon-<label>-<timestamp>.csv` (git-ignored) and the summary is printed.

`java.exe` (from `gradlew runClient`) and `javaw.exe` (launchers) are both recorded. Pass `-ProcessName` to change that. PresentMon prints a warning about elevated privilege; for a game started from your own account it still works, and running the terminal as administrator removes the warning.

## What to capture

One capture per setting, same scene and duration. Use `-Label` to name them, then change **one** thing at a time in Options → Other (config `vulkanmod_settings.json`):

| Label | Settings |
|---|---|
| `baseline` | frame queue 2, low latency off, swapchain images auto |
| `fq1` | frame queue 1 |
| `fq1-lowlat` | frame queue 1, low latency mode on |
| `lowlat` | frame queue 2, low latency mode on |
| `img2` / `img3` | swapchain images 2 / 3 (everything else as baseline) |

Also compare the first minute after a fresh start (empty pipeline cache: delete `voolkan\pipeline_cache.bin` in the game directory) with the same minute on a second start (cache loaded). The startup log says `Loaded pipeline cache: N bytes`; `-Dvoolkan.pipelineCache=false` turns the cache off.

## Compare

```powershell
tools\presentmon-summary.ps1 -Csv logs\presentmon-baseline-*.csv, logs\presentmon-fq1-*.csv, logs\presentmon-fq1-lowlat-*.csv
```

One column per file. How to read it:

- **average / p50 ms**: throughput. Low latency mode costs some here when the GPU is the bottleneck, because the CPU waits for the GPU instead of running ahead.
- **p95 / p99 / p99.9 ms, 1% / 0.1% low, frames > 33ms**: stutter. Frame queue 1 and low latency mode can make these worse, because there is no queue to absorb a slow frame. Watch them.
- **DisplayLatency / MsUntilDisplayed, AllInputToPhotonLatency** (when the PresentMon version reports them): the closest thing to input delay. Present-to-screen latency should drop with queue 1 / low latency mode. Click/input-to-photon values are only filled when PresentMon can see the input, so `NA` is common; judge input delay mainly by the present-to-screen latency and by feel.
- **GPUBusy / CPUBusy**: which side is the limit.
- **present mode**: `Hardware: Independent Flip` is the best case; `Composed: Flip` adds a compositor frame of delay (windowed, or overlays present).

Send the CSVs (or the summaries) back; do not commit them.
