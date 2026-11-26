import os
import json
import urllib.request
import time

missing_mods = [
    ("clearvoid", "clearvoid"),
    ("commandkeys", "commandkeys"),
    ("crossbowoptimizer", "crossbowoptimizer"),
    ("crosshairaddons", "crosshair-addons"),
    ("custom_hud", "customhud"),
    ("disablehotbarwrap", "disable-hotbar-wrap"),
    ("fabric-language-kotlin", "fabric-language-kotlin"),
    ("forgeconfigapiport", "forge-config-api-port"),
    ("freelook", "freelook"),
    ("gpu_tape", "gpu-tape"),
    ("immediatelyfast", "immediatelyfast"),
    ("ixeris", "ixeris"),
    ("modelfix", "modelfix"),
    ("modernfix", "modernfix"),
    ("noisium", "noisium"),
    ("norefreshscroll", "norefreshscroll"),
    ("packetfixer", "packet-fixer"),
    ("placeholder-api", "placeholder-api"),
    ("ravenclawspingequalizer", "ping-equalizer"),
    ("rrls", "rrls"),
    ("scalablelux", "scalablelux"),
    ("scoreboardtweaks", "scoreboard-tweaks"),
    ("shieldstatus", "shieldstatus"),
    ("tick-sync", "tick-sync"),
    ("true-health", "true-health"),
    ("ukulib", "ukulib"),
    ("ukus-armor-hud", "ukus-armor-hud"),
    ("vmp", "vmp-fabric"),
    ("vulkanmod-extra", "vulkanmod-extra"),
    ("zume", "zume"),
    # Also modern / extra candidates:
    ("zoomify", "zoomify"),
    ("clumps", "clumps"),
    ("cullleaves", "cull-leaves"),
    ("cwb", "cubes-without-borders"),
    ("debugify", "debugify"),
    ("disableportalchecks", "disableportalchecks"),
    ("forcecloseloadingscreen", "forcecloseloadingscreen"),
    ("fpsdisplay", "fps-display"),
    ("nowheel", "nowheel"),
    ("shieldfixes", "shield-fixes"),
    ("consumableoptimizer", "consumableoptimizer")
]

results = {}
for mid, slug in missing_mods:
    url = f"https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D"
    req = urllib.request.Request(url, headers={"User-Agent": "voolkan-pack-builder/1.0"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            if data:
                v = data[0]
                primary_file = [f for f in v["files"] if f.get("primary")]
                file_info = primary_file[0] if primary_file else v["files"][0]
                results[mid] = {
                    "slug": slug,
                    "found_1_21_11": True,
                    "filename": file_info["filename"],
                    "url": file_info["url"]
                }
            else:
                results[mid] = {
                    "slug": slug,
                    "found_1_21_11": False
                }
    except Exception as e:
        results[mid] = {
            "slug": slug,
            "found_1_21_11": False,
            "error": str(e)
        }
    time.sleep(0.05)

print("=== 1.21.11 COMPATIBILITY RESULTS ===")
for mid, res in results.items():
    if res.get("found_1_21_11"):
        print(f"[YES 1.21.11] {mid:<25} -> {res['filename']}")
    else:
        print(f"[NO  1.21.11] {mid:<25} (slug: {res['slug']}) {res.get('error','')}")
