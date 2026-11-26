import os
import sys
import json
import urllib.request
import sqlite3
import shutil
import time

profile_name = "voolkan-1.21.11"
profile_dir = os.path.join(r"C:\Users\rayyan\AppData\Roaming\ModrinthApp\profiles", profile_name)
mods_dir = os.path.join(profile_dir, "mods")
os.makedirs(mods_dir, exist_ok=True)

voolkan_jar = r"D:\Code\Repos\vulkan\build\libs\voolkan-1.0.0-beta.1+1.21.11.jar"
shutil.copy2(voolkan_jar, os.path.join(mods_dir, os.path.basename(voolkan_jar)))
print("Voolkan jar installed.")

# Slugs to query on Modrinth for 1.21.11 Fabric
mod_slugs = [
    "fabric-api",
    "not-enough-vulkan",
    "lithium",
    "ferrite-core",
    "krypton",
    "modmenu",
    "entityculling",
    "cloth-config",
    "yacl",
    "badoptimizations",
    "moreculling",
    "modernfix",
    "fast-ip-ping",
    "voicechat",
    "nochatreports",
    "noisium",
    "fastquit",
    "fadeless",
    "language-reload",
    "customhud",
    "walksylib",
    "shieldstatus",
    "betterhitreg",
    "autosprintfix",
    "clearvoid",
    "c2me-fabric"
]

downloaded = []
for slug in mod_slugs:
    url = f"https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D"
    req = urllib.request.Request(url, headers={"User-Agent": "voolkan-pack-builder/1.0"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            if data:
                v = data[0]
                primary_file = [f for f in v["files"] if f.get("primary")]
                file_info = primary_file[0] if primary_file else v["files"][0]
                target_path = os.path.join(mods_dir, file_info["filename"])
                if not os.path.exists(target_path):
                    print(f"Downloading {slug}: {file_info['filename']}...")
                    dl_req = urllib.request.Request(file_info["url"], headers={"User-Agent": "voolkan-pack-builder/1.0"})
                    with urllib.request.urlopen(dl_req) as dl_resp, open(target_path, "wb") as out_f:
                        shutil.copyfileobj(dl_resp, out_f)
                else:
                    print(f"Already present {slug}: {file_info['filename']}")
                downloaded.append(slug)
            else:
                print(f"[No 1.21.11 build on Modrinth] {slug}")
    except Exception as e:
        print(f"[Error querying {slug}] {e}")
    time.sleep(0.05)

print(f"\nSuccessfully downloaded/verified {len(downloaded)} mods for 1.21.11.")
