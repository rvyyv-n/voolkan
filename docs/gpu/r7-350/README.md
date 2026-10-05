# AMD Radeon R7 350: GPU snapshot (owner task 0.6)

Capture the real device capabilities so the Profiles-layer config (`tools/profiles/r7-350.json`, currently provisional) can be rebuilt from facts.

Run on the R7 350 machine, from this folder, with the AMD driver 23.11.1 installed (use the SDK's vulkaninfo for the JSON, which is newer):

```powershell
cd docs\gpu\r7-350
& 'C:\VulkanSDK\1.4.363.0\Bin\vulkaninfoSDK.exe' --json    > vulkaninfo.json
& 'C:\VulkanSDK\1.4.363.0\Bin\vulkaninfoSDK.exe' --summary > vulkaninfo-summary.txt
& 'C:\Windows\System32\vulkaninfo.exe' --summary           > vulkaninfo-summary-driver.txt
```

If `--json` writes a `VP_VULKANINFO_*.json` file to the current directory instead of stdout, keep that file and rename it to `vulkaninfo.json`.

Save into this folder:

- `vulkaninfo.json`
- `vulkaninfo-summary.txt`
- `vulkaninfo-summary-driver.txt` (optional, from the driver's own tool)

Then commit them. Then rebuild the profile: `vkconfig` → Profiles, or `vulkaninfoSDK --json` → convert with the SDK's `vkprofiles.exe`.

## Already observed (2026-10-05, partial)

From `vulkaninfo --summary` on this machine: `AMD Radeon R7 200 Series`, apiVersion 1.2.178, driver 23.11.1 (AMD proprietary). `VK_KHR_dynamic_rendering` is absent (matches the crash on the base). Whether the device itself lacks `VK_KHR_synchronization2` is not yet confirmed: the SDK's `vulkaninfo` lists it only under the SDK's emulation layer, so check the "Device Extensions" section of the captured JSON.
