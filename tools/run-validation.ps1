<#
.SYNOPSIS  Run the client with Khronos validation + synchronization validation. Logs to logs\<stamp>-validation\.
.PARAMETER Lavapipe  Run on Mesa lavapipe instead of the real GPU (VK_DRIVER_FILES, scoped to this process).
.PARAMETER World     Test world to quick-play (see tools\make-test-world.ps1).
.PARAMETER AutoQuitSeconds  Kill the game after N seconds (for unattended runs).
#>
param([switch]$Lavapipe, [string]$World, [int]$AutoQuitSeconds = 0, [string[]]$GameArgs = @())
. "$PSScriptRoot\common.ps1"
$sdk = Find-VulkanSdk
$dir = New-RunDir 'validation'
$settings = Write-LayerSettings $dir (Get-ValidationSettings $dir)
$e = @{
    VK_ADD_LAYER_PATH      = "$sdk\Bin"
    VK_INSTANCE_LAYERS     = 'VK_LAYER_KHRONOS_validation'
    VK_LAYER_SETTINGS_PATH = $settings
}
if ($Lavapipe) { $e.VK_DRIVER_FILES = Find-LavapipeIcd }
$code = Invoke-RunClient -RunDir $dir -Env $e -World $World -GameArgs $GameArgs -AutoQuitSeconds $AutoQuitSeconds
Show-RunResult $dir $code
