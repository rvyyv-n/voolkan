# Mod Compatibility & Known Incompatibilities

This document details known mod incompatibilities, conflicts, and recommendations when running **voolkan** on Minecraft 1.21.11.

---

## 1. Architectural & Rendering Engine Incompatibilities

Because **voolkan** fundamentally replaces Minecraft's OpenGL rendering pipeline with a native Vulkan backend, mods that directly hook or rewrite OpenGL state, shaders, or window management are incompatible.

| Mod / Category | Status | Reason & Details | Recommended Alternative |
|---|---|---|---|
| **Cubes Without Borders** (`cwb`) / **Borderless Mining** | **Incompatible** | Hooks vanilla OpenGL window fullscreen management (`Window.disableFullscreen()`). Voolkan completely replaces window initialization and swapchain presentation with its own native Vulkan window manager. | **Built-in:** Use voolkan's native **Windowed Fullscreen** option in Video Settings (`Window Mode: Borderless`). |
| **Sodium** / **Embeddium** / **Rubidium** | **Incompatible** | Replaces Minecraft's chunk rendering pipeline with OpenGL-specific buffer implementations and GLSL pipelines. | voolkan provides its own multi-threaded Vulkan chunk mesher and upload budgeting. |
| **Iris Shaders** / **Oculus** | **Incompatible** | Couples to OpenGL GLSL shader pipelines and Sodium internals. Vulkan uses compiled SPIR-V pipelines. | Custom Vulkan shader pipeline support is planned for future architecture milestones. |
| **Canvas Renderer** / **OptiFine** | **Incompatible** | Modifies vanilla OpenGL rendering pipelines. | voolkan replaces the rendering engine. |

---

## 2. Minecraft 1.21.11 Mod Mismatches (External Mods)

Minecraft 1.21.11 introduced internal refactors to player entities and item model handling. The following mods compiled for 1.21.9 / 1.21.10 fail mixin injection on 1.21.11 and must be disabled until upstream updates:

| Mod | Version Tested | Failure Point | Resolution |
|---|---|---|---|
| **Custom HUD** (`custom_hud`) | `4.1.3+1.21.9` | Critical mixin injection failure: `PlayerEntity.logAttack` target method missing in 1.21.11. | Remove or disable (`.jar.disabled`) until a 1.21.11 release is published upstream. |
| **Model Fix** (`modelfix`) | `1.21.10-1.12` | Mixin overwrite failure: `ItemModelGenerator.method_3482` was refactored in 1.21.11. | Remove or disable (`.jar.disabled`) until updated for 1.21.11. |

---

## 3. Duplicate Keybinding & Utility Conflicts

| Mod Conflict | Failure Symptom | Resolution |
|---|---|---|
| **FullbrightFabric** + **Optimized Fullbright** | `IllegalArgumentException: Attempted to register two key bindings with equal ID: Fullbright!` | Fabric prohibits duplicate keybinding IDs. Keep only one fullbright mod (e.g. `optimized_fullbright-2.0.0+1.21.11-fabric.jar`). |

---

## 4. Fully Compatible & Recommended Companions

voolkan works out of the box with standard Fabric optimization, logic, and QoL mods, including:
- **Lithium** (Physics & game logic optimization)
- **C2ME** (Concurrent chunk management & threading)
- **FerriteCore** (Memory footprint reduction)
- **ImmediatelyFast** (UI & immediate mode acceleration)
- **ModernFix** (Dynamic resource & memory optimizations)
- **BadOptimizations** (Tick & particle optimizations)
- **Debugify** (Bugfix collection)
- **FastQuit** (Instant singleplayer disconnect)
- **Mod Menu** / **YACL** (Configuration interfaces)
