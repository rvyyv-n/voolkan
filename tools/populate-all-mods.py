import os
import json
import urllib.request
import time
import shutil

PROFILE_DIR = r"C:\Users\rayyan\AppData\Roaming\ModrinthApp\profiles\voolkan-1.21.11"
MODS_DIR = os.path.join(PROFILE_DIR, "mods")
CONFIG_DIR = os.path.join(PROFILE_DIR, "config")

HT1_CONFIG = r"C:\Users\rayyan\AppData\Roaming\ModrinthApp\profiles\Showy_s HT1 mods\config"
MODERN_CONFIG = r"C:\Users\rayyan\AppData\Roaming\ModrinthApp\profiles\modern\config"

with open(r"D:\Code\Repos\vulkan\build\dist\ht1_slugs.json") as f:
    ht1_map = json.load(f)
with open(r"D:\Code\Repos\vulkan\build\dist\modern_slugs.json") as f:
    modern_map = json.load(f)

# Priority: HT1 (1.21.4) first, then modern (26.2) for any extras
all_projects = {}
for jar, pid in ht1_map.items():
    all_projects[pid] = {"source": "HT1 (1.21.4)", "jar": jar}

for jar, pid in modern_map.items():
    if pid not in all_projects:
        all_projects[pid] = {"source": "modern (26.2)", "jar": jar}

# Incompatible or replaced projects to skip
EXCLUDE_PROJECTS = {
    "AANobbMI": "Sodium (incompatible with Vulkan)",
    "P7dR8mAc": "Sodium Extra (Sodium specific)",
    "Bh37bBMW": "Reese's Sodium Options (Sodium specific)",
    "mOgUt4GM": "VulkanMod (replaced by voolkan)",
    "Vlpaq4kU": "GPUBooster (breaks GLFW WindowMixin on 1.21.11)",
    "2N92X5uW": "ToadLib (GPUBooster dependency)",
}

print(f"Total projects to process: {len(all_projects)}")

installed_files = set(os.listdir(MODS_DIR))

results = {
    "already_installed": [],
    "downloaded_1_21_11": [],
    "no_1_21_11": [],
    "excluded": []
}

for pid, meta in all_projects.items():
    if pid in EXCLUDE_PROJECTS:
        results["excluded"].append((pid, meta["jar"], EXCLUDE_PROJECTS[pid]))
        continue

    url = f"https://api.modrinth.com/v2/project/{pid}/version?game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D"
    req = urllib.request.Request(url, headers={"User-Agent": "voolkan-pack-builder/1.0"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            if data:
                v = data[0]
                primary_file = [f for f in v["files"] if f.get("primary")]
                file_info = primary_file[0] if primary_file else v["files"][0]
                fname = file_info["filename"]
                target_p = os.path.join(MODS_DIR, fname)
                if os.path.exists(target_p):
                    results["already_installed"].append((meta["jar"], fname, meta["source"]))
                else:
                    print(f"Downloading {fname} (from {meta['source']})...")
                    dl_req = urllib.request.Request(file_info["url"], headers={"User-Agent": "voolkan-pack-builder/1.0"})
                    with urllib.request.urlopen(dl_req) as dl_resp, open(target_p, "wb") as out_f:
                        shutil.copyfileobj(dl_resp, out_f)
                    results["downloaded_1_21_11"].append((meta["jar"], fname, meta["source"]))
            else:
                results["no_1_21_11"].append((pid, meta["jar"], meta["source"]))
    except Exception as e:
        results["no_1_21_11"].append((pid, meta["jar"], f"Error: {e}"))
    time.sleep(0.04)

print("\n--- RESULTS SUMMARY ---")
print(f"Already Installed: {len(results['already_installed'])}")
print(f"Newly Downloaded for 1.21.11: {len(results['downloaded_1_21_11'])}")
print(f"Excluded: {len(results['excluded'])}")
print(f"No 1.21.11 build available: {len(results['no_1_21_11'])}")

print("\nNewly Downloaded:")
for orig, new_f, src in results["downloaded_1_21_11"]:
    print(f"  + {new_f:<45} (was {orig} in {src})")

print("\nNo 1.21.11 build available yet:")
for pid, orig, src in results["no_1_21_11"]:
    print(f"  - {orig:<45} ({src})")

# Copy configs from HT1 and modern for any newly installed mods
for src_cfg_dir in [MODERN_CONFIG, HT1_CONFIG]:
    if os.path.exists(src_cfg_dir):
        for root, _, files in os.walk(src_cfg_dir):
            for f in files:
                rel_p = os.path.relpath(os.path.join(root, f), src_cfg_dir)
                dest_p = os.path.join(CONFIG_DIR, rel_p)
                if not os.path.exists(dest_p):
                    os.makedirs(os.path.dirname(dest_p), exist_ok=True)
                    shutil.copy2(os.path.join(root, f), dest_p)

print("\nSynced configs from HT1 and modern into voolkan-1.21.11 config directory.")
