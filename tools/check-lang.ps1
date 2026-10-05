<#
.SYNOPSIS  Fail if a user-facing string key used in the Java sources is missing from the language file.
.DESCRIPTION  Scans src/main/java for string literals that look like our translation keys ("vulkanmod.*" or
  "voolkan.*") and checks each against src/main/resources/assets/vulkanmod/lang/en_us.json.
  Keys in the language file that no source mentions are listed as warnings (they do not fail the check).
  Literals on lines that read system properties (-Dvoolkan.*) are not translation keys and are skipped.
  Keys built at run time by string concatenation cannot be seen.
#>
param([string]$Lang = 'src\main\resources\assets\vulkanmod\lang\en_us.json')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repo
$langKeys = @{}
(Get-Content $Lang -Raw -Encoding UTF8 | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $langKeys[$_.Name] = $true }
$used = @{}
foreach ($f in Get-ChildItem 'src\main\java' -Recurse -Filter *.java) {
    $n = 0
    foreach ($line in Get-Content $f.FullName) {
        $n++
        if ($line -match 'getProperty|getBoolean|Integer\.getInteger') { continue }
        foreach ($m in [regex]::Matches($line, '"((?:vulkanmod|voolkan)\.[A-Za-z0-9_.]+)"')) {
            $k = $m.Groups[1].Value
            if ($k.EndsWith('.')) { continue }   # prefix of a concatenated key
            if (-not $used.ContainsKey($k)) { $used[$k] = "$($f.FullName.Substring($repo.Length + 1)):$n" }
        }
    }
}
Pop-Location
$missing = $used.Keys | Where-Object { -not $langKeys.ContainsKey($_) } | Sort-Object
$unused = $langKeys.Keys | Where-Object { -not $used.ContainsKey($_) } | Sort-Object
"Keys used in code: $($used.Count), in language file: $($langKeys.Count), missing from language file: $(@($missing).Count)"
if ($missing) { $missing | ForEach-Object { "  MISSING $_  ($($used[$_]))" } }
if ($unused) { "Unused in code (warning only): $(@($unused).Count)"; $unused | ForEach-Object { "  unused $_" } }
if ($missing) { exit 1 }
