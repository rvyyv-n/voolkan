<#
.SYNOPSIS  Capture Minecraft frame data with PresentMon and/or summarise a PresentMon CSV.
.PARAMETER Csv      Existing CSV to summarise.
.PARAMETER Capture  Capture for -Seconds from the running Minecraft java process, then summarise. Output: logs\presentmon-<stamp>.csv
.EXAMPLE   tools\presentmon-summary.ps1 -Capture -Seconds 60
#>
param([string]$Csv, [switch]$Capture, [int]$Seconds = 60, [string]$ProcessName = 'javaw.exe')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Capture) {
    $pm = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter 'PresentMon*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $pm) { throw 'PresentMon console not found (winget install Intel.PresentMon.Console).' }
    New-Item -ItemType Directory -Force (Join-Path $repo 'logs') | Out-Null
    $Csv = Join-Path $repo ("logs\presentmon-{0}.csv" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    & $pm.FullName --process_name $ProcessName --output_file $Csv --timed $Seconds --terminate_after_timed --stop_existing_session
}
if (-not $Csv -or -not (Test-Path $Csv)) { throw 'Give -Csv <file> or -Capture.' }

$rows = Import-Csv $Csv
$col = @('FrameTime', 'MsBetweenPresents') | Where-Object { $rows[0].PSObject.Properties.Name -contains $_ } | Select-Object -First 1
if (-not $col) { throw 'No FrameTime/MsBetweenPresents column.' }
$ft = $rows | ForEach-Object { [double]$_.$col } | Where-Object { $_ -gt 0 } | Sort-Object
function Pct($a, $p) { $a[[math]::Min($a.Count - 1, [int][math]::Floor($p / 100 * $a.Count))] }
$avg = ($ft | Measure-Object -Average).Average
"File: $Csv   frames: $($ft.Count)   column: $col"
'{0,-22}{1,8:N2} ms ({2:N0} fps)' -f 'average', $avg, (1000 / $avg)
foreach ($p in 50, 95, 99, 99.9) { '{0,-22}{1,8:N2} ms' -f "p$p", (Pct $ft $p) }
'{0,-22}{1,8:N0} fps' -f '1% low', (1000 / (Pct $ft 99))
'{0,-22}{1,8:N0} fps' -f '0.1% low', (1000 / (Pct $ft 99.9))
'{0,-22}{1,8:N2} ms' -f 'max', $ft[-1]
'{0,-22}{1,8}' -f 'frames > 33 ms', ($ft | Where-Object { $_ -gt 33.3 }).Count
foreach ($c in 'MsUntilDisplayed', 'MsClickToPhotonLatency', 'MsAllInputToPhotonLatency') {
    if ($rows[0].PSObject.Properties.Name -contains $c) {
        $v = $rows | ForEach-Object { $_.$c } | Where-Object { $_ -and $_ -ne 'NA' } | ForEach-Object { [double]$_ } | Sort-Object
        if ($v.Count) { '{0,-22}avg {1:N2} ms, p95 {2:N2} ms' -f $c, ($v | Measure-Object -Average).Average, (Pct $v 95) }
    }
}

