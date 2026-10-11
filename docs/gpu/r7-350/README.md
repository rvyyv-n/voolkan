# AMD Radeon R7 350: GPU snapshot (owner task `vulkaninfo`)

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

## Observed capabilities (2026-10-10)

Captured on the physical AMD Radeon R7 350 (`AMD Radeon R7 200 Series`, deviceID `0x6610`, GCN 1) with AMD proprietary driver 23.11.1 (driverVersion `2.0.188`, apiVersion `1.2.178`):

- `VK_KHR_dynamic_rendering`: **Absent** from device extensions. Confirms the legacy `VkRenderPass` path is required.
- `VK_KHR_synchronization2`: **Present** (revision 1) directly in device extensions, confirmed by both SDK and driver tools.

