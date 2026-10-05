<#
.SYNOPSIS  Run the client on Mesa lavapipe (CPU Vulkan), no validation. Lavapipe is selected only through
           VK_DRIVER_FILES in this process; it is never registered as a system ICD.
#>
param([string]$World, [int]$AutoQuitSeconds = 0, [string[]]$GameArgs = @())
. "$PSScriptRoot\common.ps1"
$dir = New-RunDir 'lavapipe'
$code = Invoke-RunClient -RunDir $dir -Env @{ VK_DRIVER_FILES = (Find-LavapipeIcd) } -World $World -GameArgs $GameArgs -AutoQuitSeconds $AutoQuitSeconds
Show-RunResult $dir $code
