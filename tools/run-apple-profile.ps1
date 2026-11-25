<#
.SYNOPSIS  Validation + Vulkan Profiles layer simulating Apple Silicon / MoltenVK: API 1.2.268,
           VK_KHR_portability_subset emulation, dynamic rendering, and no logicOp / wideLines /
           geometryShader / shaderFloat64. Logs to logs\<stamp>-apple-profile\.
.DESCRIPTION
  By default the profile is applied on top of lavapipe. -Native applies it to the real GPU instead.
  The profile (tools\profiles\apple-m.json) is PROVISIONAL until real Mac Round 1 vulkaninfo is collected.
  SIMULATE_FORMATS_BIT is intentionally omitted because the provisional profile does not contain a
  complete VkFormat feature table. Note: khronos_profiles.emulate_portability requires Vulkan SDK
  versions earlier than 1.4.363 (removed in Vulkan Profiles 1.4.363); on newer SDKs this script
  still tests API 1.2.268 and missing logicOp/wideLines/geometryShader/shaderFloat64.
#>
param([switch]$Native, [string]$World, [int]$AutoQuitSeconds = 0, [string[]]$GameArgs = @())
. "$PSScriptRoot\common.ps1"
$sdk = Find-VulkanSdk
$dir = New-RunDir 'apple-profile'
$profile = Join-Path $PSScriptRoot 'profiles\apple-m.json'
$profileSettings = Get-ProfilesSettings `
    -ProfileFile $profile `
    -ProfileName 'VP_VOOLKAN_APPLE_M' `
    -SimulateCapabilities 'SIMULATE_API_VERSION_BIT,SIMULATE_PROPERTIES_BIT,SIMULATE_FEATURES_BIT' `
    -ExcludeDeviceExtensions '' `
    -EmulatePortability
$settings = Write-LayerSettings $dir ((Get-ValidationSettings $dir) + $profileSettings)
$e = @{
    VK_ADD_LAYER_PATH      = "$sdk\Bin"
    VK_INSTANCE_LAYERS     = 'VK_LAYER_KHRONOS_validation;VK_LAYER_KHRONOS_profiles'
    VK_LAYER_SETTINGS_PATH = $settings
}
if (-not $Native) { $e.VK_DRIVER_FILES = Find-LavapipeIcd }
$code = Invoke-RunClient -RunDir $dir -Env $e -World $World -GameArgs $GameArgs -AutoQuitSeconds $AutoQuitSeconds
Show-RunResult $dir $code
