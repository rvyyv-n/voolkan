<#
.SYNOPSIS  Fail if a Java file changed since upstream-base lacks the voolkan LGPL modification notice.
.DESCRIPTION  Files under src/main/java/dev/voolkan are new code and are skipped. See docs/conventions.md.
#>
param([string]$Base = 'upstream-base')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repo
$changed = git diff --name-only --diff-filter=M "$Base...HEAD" -- 'src/main/java' | Where-Object { $_ -like '*.java' }
$missing = @()
foreach ($f in $changed) {
    if ($f -like 'src/main/java/dev/voolkan/*') { continue }
    if (-not (Select-String -Path $f -Pattern 'Modified by the voolkan project' -Quiet)) { $missing += $f }
}
Pop-Location
"Modified upstream files: $(@($changed).Count), missing notice: $($missing.Count)"
if ($missing) { $missing | ForEach-Object { "  $_" }; exit 1 }
