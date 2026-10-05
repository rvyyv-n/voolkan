<#
.SYNOPSIS  Capture Minecraft frame data with PresentMon and/or summarise PresentMon CSV files.
.PARAMETER Csv          One or more existing CSVs to summarise (several = side-by-side comparison).
.PARAMETER Capture      Capture for -Seconds from the running Minecraft process, then summarise. Output: logs\presentmon-<label>-<stamp>.csv
.PARAMETER Label        Short name for the capture (e.g. fq1-lowlat), put in the file name and the summary header.
.PARAMETER ProcessName  Process(es) to record. Default java.exe and javaw.exe (gradlew runClient uses java.exe, launchers use javaw.exe).
.EXAMPLE   tools\presentmon-summary.ps1 -Capture -Seconds 60 -Label baseline
.EXAMPLE   tools\presentmon-summary.ps1 -Csv logs\presentmon-baseline-*.csv, logs\presentmon-fq1-*.csv
#>
param([string[]]$Csv, [switch]$Capture, [int]$Seconds = 60, [string]$Label = 'run', [string[]]$ProcessName = @('java.exe', 'javaw.exe'))
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Find-PresentMon {
    $cmd = Get-Command presentmon -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $f = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter 'presentmon*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($f) { return $f.FullName }
    throw 'PresentMon console not found (winget install -e --id Intel.PresentMon.Console).'
}

if ($Capture) {
    $pm = Find-PresentMon
    New-Item -ItemType Directory -Force (Join-Path $repo 'logs') | Out-Null
    $out = Join-Path $repo ("logs\presentmon-{0}-{1}.csv" -f $Label, (Get-Date -Format 'yyyyMMdd-HHmmss'))
    $pmArgs = @()
    foreach ($n in $ProcessName) { $pmArgs += @('--process_name', $n) }
    $pmArgs += @('--output_file', $out, '--timed', $Seconds, '--terminate_after_timed', '--stop_existing_session', '--no_console_stats')
    & $pm @pmArgs
    if (-not (Test-Path $out)) { throw ("PresentMon wrote no file: no frames from $($ProcessName -join ', ') were presented. Start the game first and keep its window visible.") }
    $Csv = @($out)
}
if (-not $Csv) { throw 'Give -Csv <file>... or -Capture.' }

function Pct($a, $p) { $a[[math]::Min($a.Count - 1, [int][math]::Floor($p / 100 * $a.Count))] }

function Get-Column($rows, [string[]]$names) {
    $names | Where-Object { $rows[0].PSObject.Properties.Name -contains $_ } | Select-Object -First 1
}

function Get-Values($rows, [string]$col) {
    $rows | ForEach-Object { $_.$col } | Where-Object { $_ -and $_ -ne 'NA' } |
        ForEach-Object { [double]$_ } | Where-Object { $_ -gt 0 } | Sort-Object
}

function Summarise([string]$file) {
    $rows = @(Import-Csv $file)
    if (-not $rows.Count) { throw "No rows in $file (was the game running and presenting during the capture?)" }
    $col = Get-Column $rows @('FrameTime', 'MsBetweenPresents')
    if (-not $col) { throw "No FrameTime/MsBetweenPresents column in $file" }

    $ft = Get-Values $rows $col
    $avg = ($ft | Measure-Object -Average).Average
    $r = [ordered]@{
        'file'          = Split-Path $file -Leaf
        'frames'        = $ft.Count
        'average ms'    = '{0:N2}' -f $avg
        'average fps'   = '{0:N0}' -f (1000 / $avg)
        'p50 ms'        = '{0:N2}' -f (Pct $ft 50)
        'p95 ms'        = '{0:N2}' -f (Pct $ft 95)
        'p99 ms'        = '{0:N2}' -f (Pct $ft 99)
        'p99.9 ms'      = '{0:N2}' -f (Pct $ft 99.9)
        '1% low fps'    = '{0:N0}' -f (1000 / (Pct $ft 99))
        '0.1% low fps'  = '{0:N0}' -f (1000 / (Pct $ft 99.9))
        'max ms'        = '{0:N2}' -f $ft[-1]
        'frames > 33ms' = ($ft | Where-Object { $_ -gt 33.3 }).Count
    }

    # Present-to-screen and input latency columns exist only in some PresentMon versions/metric sets
    foreach ($c in @(
            @('MsUntilDisplayed', 'DisplayLatency'), @('MsClickToPhotonLatency', 'ClickToPhotonLatency'),
            @('MsAllInputToPhotonLatency', 'AllInputToPhotonLatency'), @('GPUBusy'), @('CPUBusy'))) {
        $lc = Get-Column $rows $c
        if ($lc) {
            $v = Get-Values $rows $lc
            if ($v.Count) { $r["$lc avg/p95 ms"] = '{0:N2} / {1:N2}' -f ($v | Measure-Object -Average).Average, (Pct $v 95) }
        }
    }

    $pmode = Get-Column $rows @('PresentMode')
    if ($pmode) { $r['present mode'] = ($rows | Group-Object $pmode | Sort-Object Count -Descending | Select-Object -First 1).Name }
    [pscustomobject]$r
}

$results = @($Csv | ForEach-Object { Get-Item $_ } | ForEach-Object { Summarise $_.FullName })

if ($results.Count -eq 1) {
    $results[0].PSObject.Properties | ForEach-Object { '{0,-26}{1}' -f $_.Name, $_.Value }
}
else {
    # One column per capture
    $names = $results[0].PSObject.Properties.Name
    foreach ($n in $names) {
        '{0,-26}{1}' -f $n, (($results | ForEach-Object { '{0,-34}' -f $_.$n }) -join '')
    }
}
