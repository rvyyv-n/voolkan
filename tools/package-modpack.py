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

# --- Strict Privacy & Sanitization Filters ---
# Absolutely NO personal settings, servers, accounts, credentials, or multiplayer cache
BLOCKED_DIRS = {
    "minecraftcapes",
    "voicechat",
    "xaero",
    "nochatreports",
    "custom-hud"
}

BLOCKED_FILES = {
    "options.txt",
    "servers.dat",
    "servers.dat_old",
    "command_history.txt",
    "rpc-data.json",
    "ias.json",
    "cwb.json",
    "modelfix-client.json",
    "borderless_windowed_vulkan.json",
    "cape-provider.json5",
    "capes.json5",
    "mcca_state.json",
    "xaerohud.txt",
    "xaeropatreon.txt"
}

BLOCKED_NAME_SUBSTRINGS = [
    "backup",
    "bak",
    "corrupt",
    "legacy",
    "old",
    "account",
    "token",
    "secret",
    "usercache",
    "username-cache",
    "hs_err",
    ".log",
    ".tmp",
    "xaero",
    "sodium",
    "chloride",
    "reeses"
]

def is_safe_config(file_name, rel_path, full_path):
    fn_lower = file_name.lower()
    rel_lower = rel_path.lower().replace("\\", "/")
    
    # Check blocked directories
    parts = rel_lower.split("/")
    for p in parts:
        if p in BLOCKED_DIRS:
            return False

    # Check blocked filenames
    if fn_lower in BLOCKED_FILES:
        return False

    # Check blocked substrings in filename
    for sub in BLOCKED_NAME_SUBSTRINGS:
        if sub in fn_lower:
            return False

    # Inspect file content for personal info, local filesystem paths, or sensitive tokens
    try:
        with open(full_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read().lower()
            sensitive_keywords = [
                "rayyan",
                "c:\\users\\",
                "c:/users/",
                "appdata",
                "access_token",
                "refreshtoken",
                "client_token",
                "msa_token",
                "password",
                "private_key",
                "secret_key"
            ]
            for kw in sensitive_keywords:
                if kw in content:
                    return False
    except Exception:
        return False

    return True

# 2. Build .mrpack
print(f"\nBuilding sanitized {MRPACK_PATH}...")
packaged_configs = []

with zipfile.ZipFile(MRPACK_PATH, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(index_data, indent=2))
    
    # Add overrides/mods/ for bundled jars (voolkan)
    for fname in overrides_mods:
        z.write(file_info[fname]["path"], f"overrides/mods/{fname}")
        
    # Add sanitized configs into overrides/config/
    for root, _, files in os.walk(CONFIG_DIR):
        for f in sorted(files):
            full_p = os.path.join(root, f)
            rel_p = os.path.relpath(full_p, PROFILE_DIR)
            if is_safe_config(f, rel_p, full_p):
                clean_target = f"overrides/{rel_p.replace(chr(92), '/')}"
                z.write(full_p, clean_target)
                packaged_configs.append(rel_p)

    # Add profile icon if present (NO options.txt, NO servers.dat)
    icon_p = os.path.join(PROFILE_DIR, "icon.png")
    if os.path.exists(icon_p) and os.path.getsize(icon_p) > 0:
        z.write(icon_p, "overrides/icon.png")

print(f"Included {len(packaged_configs)} sanitized mod configuration files.")
print(f"Done. Size: {os.path.getsize(MRPACK_PATH) / (1024*1024):.2f} MB")

# 3. Build full portable zip
print(f"\nBuilding sanitized {FULL_ZIP_PATH}...")
with zipfile.ZipFile(FULL_ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as z:
    for fname, info in file_info.items():
        z.write(info["path"], f"mods/{fname}")

    for root, _, files in os.walk(CONFIG_DIR):
        for f in sorted(files):
            full_p = os.path.join(root, f)
            rel_p = os.path.relpath(full_p, PROFILE_DIR)
            if is_safe_config(f, rel_p, full_p):
                z.write(full_p, rel_p.replace(chr(92), "/"))

    icon_p = os.path.join(PROFILE_DIR, "icon.png")
    if os.path.exists(icon_p) and os.path.getsize(icon_p) > 0:
        z.write(icon_p, "icon.png")

print(f"Done. Size: {os.path.getsize(FULL_ZIP_PATH) / (1024*1024):.2f} MB")

# 4. Strict Security Verification on both generated packages
print("\n--- Comprehensive Security & Privacy Audit ---")
for pkg_name, pkg_path in [("mrpack", MRPACK_PATH), ("full_zip", FULL_ZIP_PATH)]:
    with zipfile.ZipFile(pkg_path, "r") as z:
        names = z.namelist()
        for n in names:
            nl = n.lower()
            # Mods folder contains jar binaries, skip for config blacklist
            if nl.startswith("mods/") or nl.startswith("overrides/mods/"):
                continue
            # Assert no personal files
            for banned in ["options.txt", "servers.dat", "command_history", "account", "usercache", "voicechat", "xaero", "minecraftcapes", "ias"]:
                if banned in nl:
                    raise RuntimeError(f"CRITICAL PRIVACY VIOLATION in {pkg_name}: Found forbidden file {n}")
            # Assert text files have no personal paths or tokens
            if nl.endswith((".txt", ".json", ".json5", ".toml", ".properties", ".cfg")):
                content = z.read(n).decode("utf-8", errors="ignore").lower()
                for kw in ["rayyan", "c:\\users", "c:/users", "access_token", "msa_token", "password"]:
                    if kw in content:
                        raise RuntimeError(f"CRITICAL PRIVACY VIOLATION in {pkg_name}: Found sensitive data in {n}")

print("AUDIT RESULT: PASSED! Zero personal info, zero servers, zero personal settings found.")

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
