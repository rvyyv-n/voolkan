# Dev setup (Windows)

Installed on 2026-10-05 via `winget` unless noted. Re-create with the commands below.

| Tool | Version | Install |
|---|---|---|
| Git | 2.54.0.windows.1 | already present |
| GitHub CLI | 2.98.0 | already present |
| Eclipse Temurin JDK 21 | 21.0.12.1+1 | `winget install -e --id EclipseAdoptium.Temurin.21.JDK` |
| Eclipse Temurin JDK 25 (for the 26.1 port, later) | 25.0.4.1+1 | `winget install -e --id EclipseAdoptium.Temurin.25.JDK` |
| LunarG Vulkan SDK (validation layers, Profiles layer, vkconfig, vulkaninfo, SPIR-V tools, glslc) | 1.4.363.0 | `winget install -e --id KhronosGroup.VulkanSDK` (UAC prompt) |
| RenderDoc | 1.46 | `winget install -e --id BaldurKarlsson.RenderDoc` |
| PresentMon console | 2.6.0 | `winget install -e --id Intel.PresentMon.Console` |
| Mesa lavapipe (mesa-dist-win, release-msvc) | 26.2.4 | downloaded, see below |
| Vineflower decompiler | 1.12.0 | downloaded, see below |
| Gradle | 9.2.1 | repo wrapper only (`gradlew`), no global install |
| Fabric Loom | 1.14-SNAPSHOT | via `build.gradle` |

The machine's default `java` is still Java 8. The scripts and the commands below set `JAVA_HOME` to JDK 21 explicitly; nothing global was changed.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew build
.\gradlew runClient
```

JDK 25 lives at `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot`.

## Downloaded tools (git-ignored)

```powershell
gh release download 26.2.4 -R pal1000/mesa-dist-win -p 'mesa3d-26.2.4-release-msvc.7z' -D tools\bin
& 'C:\Program Files\7-Zip\7z.exe' x -otools\mesa tools\bin\mesa3d-26.2.4-release-msvc.7z
gh release download 1.12.0 -R Vineflower/vineflower -p 'vineflower-1.12.0.jar' -D tools\bin
```

Lavapipe is **never** registered as a system Vulkan driver. The scripts select it only for their own process:
`VK_DRIVER_FILES=tools\mesa\x64\lvp_icd.x86_64.json`. Do not run Mesa's `systemwidedeploy.cmd`.

On this PC Mesa lives at `D:\Code\Tools\voolkan\mesa` (`VOOLKAN_MESA`, a user environment variable), vineflower in `D:\Code\Tools\voolkan\bin`, and the Gradle home is `D:\Code\Tools\gradle` (`GRADLE_USER_HOME`). A shell opened before those variables were set needs them set by hand.

Paths used by the scripts can be overridden in a git-ignored `tools\local.env.ps1`:
`$env:VOOLKAN_JAVA21`, `$env:VOOLKAN_MESA`, `$env:VULKAN_SDK`.

## Notes

- Lavapipe is a CPU renderer (`GPU: llvmpipe`): low frame rates are expected and say nothing about the R7. On Windows it shows an RGBA swapchain with red and blue swapped, so the swapchain prefers BGRA (`SwapChain.getFormat`).
- Smoke runs default to 45 s (`tools\smoke-run.ps1 -Seconds N`). `tools\capture-window.ps1` saves a picture of just the game window to `logs\window.png`.
- `tools\baselines\*.txt` hold the accepted validation IDs per mode (validation mode: two dynamic-rendering IDs on lavapipe; R7 profile: none). Refresh with `-SaveBaseline`.
- `C:\Windows\System32\vulkaninfo.exe` (from the AMD driver) ignores `VK_DRIVER_FILES`/layers set by us; use the SDK's `C:\VulkanSDK\1.4.363.0\Bin\vulkaninfoSDK.exe` when testing layers or lavapipe.
- Other software on this PC (OBS, Medal) injects Vulkan layers; harmless but they add loader warnings.
- Optional: IntelliJ IDEA Community + the "Minecraft Development" plugin for debugging `runClient`. Not installed.
- Decompiling a jar: `java -jar tools\bin\vineflower-1.12.0.jar <in.jar> <outdir>`.
- PresentMon: `%LOCALAPPDATA%\Microsoft\WinGet\Links\presentmon.exe` (alias added by winget; open a new shell).
- RenderDoc: `C:\Program Files\RenderDoc\qrenderdoc.exe`. Note: the base has no RenderDoc/debug-label switch (debug labels are gated on the compile-time `ENABLE_VALIDATION_LAYERS`).
- The R7 is in the mod's GPU selector on a normal run (checked: `Selectable device 0: AMD Radeon R7 200 Series`, logged at startup). The scripts that set `VK_DRIVER_FILES` (lavapipe, the R7 profile without `-Native`, `run-validation.ps1 -Lavapipe`) hide the real GPU from the Vulkan loader, so only lavapipe appears there. `run-validation.ps1` without `-Lavapipe` runs on the real GPU.
