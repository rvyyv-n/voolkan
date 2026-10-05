<#
.SYNOPSIS  Validation + Vulkan Profiles layer simulating the R7 350: API capped at 1.2.178 and
           VK_KHR_dynamic_rendering / VK_KHR_synchronization2 hidden. Logs to logs\<stamp>-r7-profile\.
.DESCRIPTION
  By default the profile is applied on top of lavapipe, because lavapipe exposes the extensions
  and the layer can therefore genuinely hide them. -Native applies it to the real GPU instead
  (on the actual R7 the extensions are already absent, so the base fails there regardless).
  The profile (tools\profiles\r7-350.json) is PROVISIONAL until docs\gpu\r7-350\ has the real vulkaninfo.
#>
param([switch]$Native, [string]$World, [int]$AutoQuitSeconds = 0, [string[]]$GameArgs = @())
. "$PSScriptRoot\common.ps1"
$sdk = Find-VulkanSdk
$dir = New-RunDir 'r7-profile'
$profile = Join-Path $PSScriptRoot 'profiles\r7-350.json'
$settings = Write-LayerSettings $dir ((Get-ValidationSettings $dir) + (Get-ProfilesSettings $profile))
$e = @{
    VK_ADD_LAYER_PATH      = "$sdk\Bin"
    VK_INSTANCE_LAYERS     = 'VK_LAYER_KHRONOS_validation;VK_LAYER_KHRONOS_profiles'
    VK_LAYER_SETTINGS_PATH = $settings
}
if (-not $Native) { $e.VK_DRIVER_FILES = Find-LavapipeIcd }
$code = Invoke-RunClient -RunDir $dir -Env $e -World $World -GameArgs $GameArgs -AutoQuitSeconds $AutoQuitSeconds
Show-RunResult $dir $code
