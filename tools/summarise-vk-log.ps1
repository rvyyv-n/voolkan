<#
.SYNOPSIS  Group Vulkan validation messages by VUID / message id.
.PARAMETER Path  A validation.log, or a run folder containing one. Defaults to the newest run in logs\.
.PARAMETER Markdown  Emit a markdown table.
#>
param([string]$Path, [switch]$Markdown)
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Path) {
    $latest = Get-ChildItem (Join-Path $repo 'logs') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'validation.log') } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { Write-Error 'No validation.log found under logs\'; exit 1 }
    $Path = $latest.FullName
}
if (Test-Path $Path -PathType Container) { $Path = Join-Path $Path 'validation.log' }
if (-not (Test-Path $Path)) { Write-Error "Not found: $Path"; exit 1 }

$idRx = '\[\s*((?:VUID|UNASSIGNED|SYNC|BestPractices|Validation)[-A-Za-z0-9_]*)\s*\]'
$groups = @{}
$total = 0
foreach ($line in Get-Content $Path) {
    if ($line -notmatch $idRx) { continue }
    $id = $Matches[1]
    $sev = if ($line -match 'Error') { 'error' } elseif ($line -match 'Performance') { 'perf' } elseif ($line -match 'Warning') { 'warn' } else { 'info' }
    $total++
    if (-not $groups.ContainsKey($id)) {
        $msg = $line -replace '^.*?\]\s*', ''
        if ($msg.Length -gt 140) { $msg = $msg.Substring(0, 140) + '...' }
        $groups[$id] = [pscustomobject]@{ Count = 0; Severity = $sev; Id = $id; Example = $msg }
    }
    $groups[$id].Count++
}
$rows = @($groups.Values | Sort-Object Count -Descending)
"Log: $Path"
"Messages: $total, distinct ids: $($rows.Count)"
if ($rows.Count -eq 0) { 'No validation messages. Clean.'; exit 0 }
if ($Markdown) {
    '| Count | Sev | ID | Example |'; '|---|---|---|---|'
    $rows | ForEach-Object { '| {0} | {1} | `{2}` | {3} |' -f $_.Count, $_.Severity, $_.Id, ($_.Example -replace '\|', '/') }
} else {
    $rows | Format-Table Count, Severity, Id, Example -AutoSize -Wrap
}
