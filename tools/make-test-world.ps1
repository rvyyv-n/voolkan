<#
.SYNOPSIS  Generate the reproducible smoke-test world test-world\voolkan-test (fixed seed, creative, flat time).
.DESCRIPTION
  Downloads the vanilla 1.21.11 server jar (sha1-checked) into tools\bin, lets it generate the spawn area, then stops it.
  Running the server requires accepting the Mojang EULA (https://aka.ms/MinecraftEULA); pass -AcceptEula to confirm that
  YOU accept it. The generated world is git-ignored; this script + test-world\README.md are the source of truth.
#>
param([switch]$AcceptEula, [switch]$Force)
. "$PSScriptRoot\common.ps1"
if (-not $AcceptEula) { throw 'Pass -AcceptEula to confirm you accept the Mojang EULA (https://aka.ms/MinecraftEULA).' }

$name = 'voolkan-test'
$seed = '20261005'
$dest = Join-Path $script:Repo "test-world\$name"
if ((Test-Path $dest) -and -not $Force) { Write-Host "$dest already exists (use -Force to regenerate)."; return }

$jar = Join-Path $script:Repo 'tools\bin\server-1.21.11.jar'
$sha1 = '64bb6d763bed0a9f1d632ec347938594144943ed'
if (-not (Test-Path $jar)) {
    New-Item -ItemType Directory -Force (Split-Path $jar) | Out-Null
    Invoke-WebRequest "https://piston-data.mojang.com/v1/objects/$sha1/server.jar" -OutFile $jar
}
if ((Get-FileHash $jar -Algorithm SHA1).Hash.ToLower() -ne $sha1) { throw 'Server jar sha1 mismatch.' }

$work = Join-Path $script:Repo 'logs\world-gen'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force $work | Out-Null
Set-Content (Join-Path $work 'eula.txt') 'eula=true' -Encoding ascii
Set-Content (Join-Path $work 'server.properties') @(
    "level-name=$name", "level-seed=$seed", 'gamemode=creative', 'difficulty=peaceful', 'spawn-monsters=false',
    'generate-structures=false', 'online-mode=false', 'view-distance=8', 'spawn-protection=0', 'enable-command-block=true'
) -Encoding ascii

$java = Join-Path (Find-Java21) 'bin\java.exe'
$psi = New-Object Diagnostics.ProcessStartInfo $java, "-Xmx2G -jar `"$jar`" nogui"
$psi.WorkingDirectory = $work; $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.UseShellExecute = $false
$p = [Diagnostics.Process]::Start($psi)
$done = $false
while (-not $p.HasExited) {
    $line = $p.StandardOutput.ReadLine()
    if ($null -eq $line) { break }
    if ($line -match 'Done \(') { $done = $true; $p.StandardInput.WriteLine('stop') }
}
$p.WaitForExit()
if (-not $done) { throw 'Server did not reach "Done"; see logs\world-gen.' }

New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
Copy-Item (Join-Path $work $name) $dest -Recurse
Write-Host "World written to $dest"
