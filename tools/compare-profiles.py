import os
import zipfile
import json

def inspect_profile(pname):
    mods_dir = os.path.join(r"C:\Users\rayyan\AppData\Roaming\ModrinthApp\profiles", pname, "mods")
    mods = {}
    if not os.path.exists(mods_dir):
        return mods
    for f in os.listdir(mods_dir):
        if not f.endswith('.jar'):
            continue
        jar_path = os.path.join(mods_dir, f)
        mod_id = f
        mod_name = f
        version = ""
        contact = {}
        try:
            with zipfile.ZipFile(jar_path, 'r') as z:
                if 'fabric.mod.json' in z.namelist():
                    data = json.loads(z.read('fabric.mod.json').decode('utf-8', errors='ignore'))
                    mod_id = data.get('id', f)
                    mod_name = data.get('name', mod_id)
                    version = data.get('version', '')
                    contact = data.get('contact', {})
        except Exception as e:
            pass
        mods[f] = {
            'file': f,
            'id': mod_id,
            'name': mod_name,
            'version': version,
            'contact': contact
        }
    return mods

ht1 = inspect_profile("Showy_s HT1 mods")
modern = inspect_profile("modern")
voolkan = inspect_profile("voolkan-1.21.11")

print(f"Total in Showy_s HT1 mods (1.21.4): {len(ht1)}")
print(f"Total in modern (1.21.2): {len(modern)}")
print(f"Total in voolkan-1.21.11: {len(voolkan)}")

voolkan_ids = {info['id'] for info in voolkan.values()}

print("\n--- MODS IN SHOWY_S HT1 MODS (1.21.4) MISSING FROM VOOLKAN 1.21.11 ---")
missing_from_ht1 = []
for f, info in sorted(ht1.items(), key=lambda x: x[1]['id'].lower()):
    if info['id'] not in voolkan_ids:
        missing_from_ht1.append(info)
        print(f"ID: {info['id']:<25} | Name: {info['name']:<35} | Jar: {f}")

print(f"\nTotal missing from HT1: {len(missing_from_ht1)}")

modern_ids = {info['id'] for info in modern.values()}
print("\n--- MODS IN MODERN (1.21.2) NOT IN HT1 ---")
for f, info in sorted(modern.items(), key=lambda x: x[1]['id'].lower()):
    if info['id'] not in {i['id'] for i in ht1.values()} and info['id'] not in voolkan_ids:
        print(f"ID: {info['id']:<25} | Name: {info['name']:<35} | Jar: {f}")
