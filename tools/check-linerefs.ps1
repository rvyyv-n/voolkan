<#
.SYNOPSIS  Check docs\line-refs.txt: does each file:line still contain the expected text?
.PARAMETER Report  For drifted entries, search the file and print the nearest matching line number.
#>
param([switch]$Report)
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = Join-Path $repo 'src\main\java\net\vulkanmod'
$bad = 0
foreach ($e in Get-Content (Join-Path $repo 'docs\line-refs.txt') | Where-Object { $_ -and $_ -notmatch '^\s*#' }) {
    $p = $e -split '\s*\|\s*', 3
    $file = Join-Path $root $p[0]; $n = [int]$p[1]
    $lines = if (Test-Path $file) { Get-Content $file } else { @() }
    if ($n -le $lines.Count -and $lines[$n - 1] -match $p[2]) { continue }
    $bad++
    $hint = ''
    if ($Report -and $lines) {
        $near = 0..($lines.Count - 1) | Where-Object { $lines[$_] -match $p[2] } | Sort-Object { [math]::Abs($_ + 1 - $n) } | Select-Object -First 1
        if ($null -ne $near) { $hint = " (found at line $($near + 1))" }
    }
    "DRIFT  $($p[0]):$n  /$($p[2])/$hint"
}
if ($bad) { "$bad drifted"; exit 1 } else { 'All line refs OK' }
