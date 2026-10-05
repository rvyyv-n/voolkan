<#
.SYNOPSIS  Unattended smoke run: validation, R7-profile and lavapipe startup runs, then a VUID summary diffed against a baseline.
.PARAMETER Seconds         Auto-quit each run after this long (proves startup only; the manual checklist is docs\smoke-test.md).
.PARAMETER SaveBaseline    Store this run's VUID set as tools\baselines\<mode>.txt.
.PARAMETER Modes           Subset of: validation, r7-profile, lavapipe.
#>
param([int]$Seconds = 120, [switch]$SaveBaseline, [string[]]$Modes = @('validation', 'r7-profile', 'lavapipe'), [string]$World)
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$baseDir = Join-Path $PSScriptRoot 'baselines'
New-Item -ItemType Directory -Force $baseDir | Out-Null
$extra = @(); if ($World) { $extra = @('-World', $World) }
$fail = 0
foreach ($m in $Modes) {
    "=== $m"
    switch ($m) {
        'validation' { $script = 'run-validation.ps1'; $args2 = @('-Lavapipe') }
        'r7-profile' { $script = 'run-r7-profile.ps1'; $args2 = @() }
        'lavapipe'   { $script = 'run-lavapipe.ps1';   $args2 = @() }
    }
    & powershell -NoProfile -File (Join-Path $PSScriptRoot $script) @args2 @extra -AutoQuitSeconds $Seconds | Out-Host
    $dir = Get-ChildItem (Join-Path $repo 'logs') -Directory | Where-Object Name -like "*-$m" | Sort-Object Name -Descending | Select-Object -First 1
    $game = Get-Content (Join-Path $dir.FullName 'game.log') -Raw -ErrorAction SilentlyContinue
    $crashed = $game -match 'Game crashed|Unsupported extension'
    $ids = @()
    $vlog = Join-Path $dir.FullName 'validation.log'
    if (Test-Path $vlog) {
        $ids = Select-String -Path $vlog -Pattern '\[\s*((?:VUID|UNASSIGNED|SYNC|BestPractices)[-A-Za-z0-9_]*)\s*\]' |
            ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique
    }
    $bf = Join-Path $baseDir "$m.txt"
    if ($SaveBaseline) { Set-Content $bf $ids; "Baseline saved: $bf ($($ids.Count) ids)" }
    elseif (Test-Path $bf) {
        $old = Get-Content $bf
        $new = $ids | Where-Object { $old -notcontains $_ }
        "VUIDs: $($ids.Count), new vs baseline: $(@($new).Count)"; $new | ForEach-Object { "  NEW $_" }
        if ($new) { $fail++ }
    } else { "VUIDs: $($ids.Count) (no baseline; use -SaveBaseline)" }
    if ($crashed) { "STARTUP FAILED (crash / unsupported extension) - see $($dir.FullName)\game.log"; $fail++ }
}
if ($fail) { "`n$fail problem(s)"; exit 1 } else { "`nOK" }
