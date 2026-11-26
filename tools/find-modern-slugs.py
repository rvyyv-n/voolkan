import os
import json
import hashlib
import urllib.request

mods_dir = r"C:\Users\rayyan\AppData\Roaming\ModrinthApp\profiles\modern\mods"
jars = [f for f in os.listdir(mods_dir) if f.endswith(".jar")]

hashes = {}
jar_map = {}
for j in jars:
    p = os.path.join(mods_dir, j)
    with open(p, "rb") as f:
        data = f.read()
    h = hashlib.sha1(data).hexdigest()
    hashes[h] = j
    jar_map[h] = p

req_body = json.dumps({"hashes": list(hashes.keys()), "algorithm": "sha1"}).encode()
req = urllib.request.Request(
    "https://api.modrinth.com/v2/version_files",
    data=req_body,
    headers={"Content-Type": "application/json", "User-Agent": "voolkan-slug-finder/1.0"}
)

matched = {}
try:
    with urllib.request.urlopen(req) as resp:
        matched = json.loads(resp.read().decode())
except Exception as e:
    print("Error:", e)

print(f"Matched {len(matched)} of {len(jars)} jars on Modrinth API.")
slugs = {}
for h, v_data in matched.items():
    proj_id = v_data.get("project_id")
    slugs[hashes[h]] = proj_id

with open(r"D:\Code\Repos\vulkan\build\dist\modern_slugs.json", "w") as out:
    json.dump(slugs, out, indent=2)

print("Saved slug mapping to modern_slugs.json.")
