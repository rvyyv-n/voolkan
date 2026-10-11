import os
import sys
import json
import hashlib
import zipfile
import shutil
import urllib.request

PROFILE_DIR = r"C:\Users\rayyan\AppData\Roaming\ModrinthApp\profiles\voolkan-1.21.11"
MODS_DIR = os.path.join(PROFILE_DIR, "mods")
CONFIG_DIR = os.path.join(PROFILE_DIR, "config")
DIST_DIR = r"D:\Code\Repos\vulkan\build\dist"
DOWNLOADS_DIR = r"C:\Users\rayyan\Downloads"

os.makedirs(DIST_DIR, exist_ok=True)

MRPACK_PATH = os.path.join(DIST_DIR, "voolkan-1.21.11.mrpack")
FULL_ZIP_PATH = os.path.join(DIST_DIR, "voolkan-1.21.11-modpack-full.zip")

print("1. Scanning jars in mods/...")
jars = [f for f in os.listdir(MODS_DIR) if f.endswith(".jar")]

file_info = {}
for j in jars:
    p = os.path.join(MODS_DIR, j)
    with open(p, "rb") as f:
        data = f.read()
    file_info[j] = {
        "path": p,
        "size": len(data),
        "sha1": hashlib.sha1(data).hexdigest(),
        "sha512": hashlib.sha512(data).hexdigest()
    }

print(f"Found {len(jars)} jars.")

# Query Modrinth API for companion mods
companion_hashes = {info["sha1"]: fname for fname, info in file_info.items() if not fname.startswith("voolkan")}
req_body = json.dumps({"hashes": list(companion_hashes.keys()), "algorithm": "sha1"}).encode()
req = urllib.request.Request(
    "https://api.modrinth.com/v2/version_files",
    data=req_body,
    headers={"Content-Type": "application/json", "User-Agent": "voolkan-modpack-packager/1.0"}
)

matched_modrinth = {}
try:
    with urllib.request.urlopen(req) as resp:
        matched_modrinth = json.loads(resp.read().decode())
    print(f"Matched {len(matched_modrinth)} of {len(companion_hashes)} mods on Modrinth CDN.")
except Exception as e:
    print(f"Warning: Failed to query Modrinth API ({e}). All mods will be placed in overrides.")

# Prepare modrinth.index.json
index_files = []
overrides_mods = []

for fname, info in file_info.items():
    sha1 = info["sha1"]
    if sha1 in matched_modrinth and not fname.startswith("voolkan"):
        v_data = matched_modrinth[sha1]
        primary_file = None
        for f in v_data.get("files", []):
            if f.get("hashes", {}).get("sha1") == sha1:
                primary_file = f
                break
        if primary_file and primary_file.get("url"):
            index_files.append({
                "path": f"mods/{fname}",
                "hashes": {
                    "sha1": sha1,
                    "sha512": info["sha512"]
                },
                "env": {
                    "client": "required",
                    "server": "unsupported"
                },
                "downloads": [primary_file["url"]],
                "fileSize": info["size"]
            })
            continue
    overrides_mods.append(fname)

print(f"Placing {len(index_files)} mods into modrinth.index.json (CDN download).")
print(f"Placing {len(overrides_mods)} mods into mrpack overrides/mods/ (bundled).")

index_data = {
    "formatVersion": 1,
    "game": "minecraft",
    "versionId": "1.0.0",
    "name": "voolkan 1.21.11 Modpack",
    "summary": "Competitive high-performance 1.21.11 Vulkan modpack powered by voolkan on Minecraft 1.21.11",
    "files": index_files,
    "dependencies": {
        "minecraft": "1.21.11",
        "fabric-loader": "0.19.5"
    }
}

# Sensitive blacklist check to ensure zero accounts/secrets are packaged
BLOCKED_PATTERNS = [
    "ias",
    "account",
    "usercache",
    "username-cache",
    "command_history",
    "corrupt",
    "hs_err",
    ".log"
]

def is_safe_config(file_name, rel_path):
    fn_lower = file_name.lower()
    rel_lower = rel_path.lower()
    for b in BLOCKED_PATTERNS:
        if b in fn_lower or b in rel_lower:
            return False
    return True

# 2. Build .mrpack
print(f"\nBuilding {MRPACK_PATH}...")
with zipfile.ZipFile(MRPACK_PATH, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(index_data, indent=2))
    
    # Add overrides/mods/ for non-Modrinth or voolkan jars
    for fname in overrides_mods:
        z.write(file_info[fname]["path"], f"overrides/mods/{fname}")
        
    # Add sanitized configs into overrides/config/
    for root, _, files in os.walk(CONFIG_DIR):
        for f in files:
            full_p = os.path.join(root, f)
            rel_p = os.path.relpath(full_p, PROFILE_DIR)
            if is_safe_config(f, rel_p):
                z.write(full_p, f"overrides/{rel_p.replace(chr(92), '/')}")

    # Add safe options.txt, servers.dat, icon.png
    for extra in ["options.txt", "servers.dat", "icon.png"]:
        p = os.path.join(PROFILE_DIR, extra)
        if os.path.exists(p) and os.path.getsize(p) > 0:
            z.write(p, f"overrides/{extra}")

print(f"Done. Size: {os.path.getsize(MRPACK_PATH) / (1024*1024):.2f} MB")

# 3. Build full portable zip
print(f"\nBuilding {FULL_ZIP_PATH}...")
with zipfile.ZipFile(FULL_ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as z:
    for fname, info in file_info.items():
        z.write(info["path"], f"mods/{fname}")

    for root, _, files in os.walk(CONFIG_DIR):
        for f in files:
            full_p = os.path.join(root, f)
            rel_p = os.path.relpath(full_p, PROFILE_DIR)
            if is_safe_config(f, rel_p):
                z.write(full_p, rel_p.replace(chr(92), "/"))

    for extra in ["options.txt", "servers.dat", "icon.png"]:
        p = os.path.join(PROFILE_DIR, extra)
        if os.path.exists(p) and os.path.getsize(p) > 0:
            z.write(p, extra)

print(f"Done. Size: {os.path.getsize(FULL_ZIP_PATH) / (1024*1024):.2f} MB")

# 4. Strict Security Verification on generated .mrpack
print("\n--- Security & Account Privacy Audit ---")
with zipfile.ZipFile(MRPACK_PATH, "r") as z:
    names = z.namelist()
    for n in names:
        nl = n.lower()
        for b in ["account", "ias", "usercache", "token", "secret", "password"]:
            if b in nl:
                raise RuntimeError(f"SECURITY VIOLATION: Found blacklisted path in mrpack: {n}")
        if nl.endswith((".txt", ".json", ".properties", ".cfg")):
            content = z.read(n).decode("utf-8", errors="ignore").lower()
            for kw in ["access_token", "refreshtoken", "client_token", "msa_token"]:
                if kw in content:
                    raise RuntimeError(f"SECURITY VIOLATION: Found sensitive token in {n}")

print("Security Audit PASSED: 0 accounts, 0 tokens, 0 user credentials found in package.")

# 5. Copy to user Downloads folder
if os.path.exists(DOWNLOADS_DIR):
    dst_mrpack = os.path.join(DOWNLOADS_DIR, "voolkan-1.21.11.mrpack")
    dst_fullzip = os.path.join(DOWNLOADS_DIR, "voolkan-1.21.11-modpack-full.zip")
    dst_jar = os.path.join(DOWNLOADS_DIR, "voolkan-1.0.0+1.21.11.jar")
    
    shutil.copy2(MRPACK_PATH, dst_mrpack)
    shutil.copy2(FULL_ZIP_PATH, dst_fullzip)
    src_jar = r"D:\Code\Repos\vulkan\build\libs\voolkan-1.0.0+1.21.11.jar"
    if os.path.exists(src_jar):
        shutil.copy2(src_jar, dst_jar)
    print(f"\nSuccessfully copied release packages to: {DOWNLOADS_DIR}")
    print(f"  - {dst_mrpack}")
    print(f"  - {dst_fullzip}")
    print(f"  - {dst_jar}")
