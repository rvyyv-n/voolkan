# Shared helpers for the voolkan test harness. Dot-source from the other scripts.
# Local overrides (git-ignored): tools/local.env.ps1, e.g. $env:VOOLKAN_JAVA21 = 'D:\jdk21'

$ErrorActionPreference = 'Stop'
$script:Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

$localEnv = Join-Path $PSScriptRoot 'local.env.ps1'
if (Test-Path $localEnv) { . $localEnv }

function Find-Java21 {
    if ($env:VOOLKAN_JAVA21) { return $env:VOOLKAN_JAVA21 }
    $c = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($c) { return $c.FullName }
    throw 'JDK 21 not found. Install Temurin 21 or set VOOLKAN_JAVA21 (see docs/dev-setup.md).'
}

function Find-VulkanSdk {
    if ($env:VULKAN_SDK -and (Test-Path $env:VULKAN_SDK)) { return $env:VULKAN_SDK }
    $m = [Environment]::GetEnvironmentVariable('VULKAN_SDK', 'Machine')
    if ($m -and (Test-Path $m)) { return $m }
    $c = Get-ChildItem 'C:\VulkanSDK' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($c) { return $c.FullName }
    throw 'Vulkan SDK not found (see docs/dev-setup.md).'
}

function Find-LavapipeIcd {
    $p = if ($env:VOOLKAN_MESA) { $env:VOOLKAN_MESA } else { Join-Path $script:Repo 'tools\mesa' }
    $icd = Join-Path $p 'x64\lvp_icd.x86_64.json'
    if (-not (Test-Path $icd)) { throw "Lavapipe ICD not found at $icd (see docs/dev-setup.md, 'Mesa lavapipe')." }
    return $icd
}

function New-RunDir([string]$Mode) {
    $d = Join-Path $script:Repo ("logs\{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Mode)
    New-Item -ItemType Directory -Force $d | Out-Null
    return $d
}

# Writes a vk_layer_settings.txt and returns its path.
function Write-LayerSettings([string]$RunDir, [string[]]$Lines) {
    $f = Join-Path $RunDir 'vk_layer_settings.txt'
    Set-Content -Path $f -Value $Lines -Encoding ascii
    return $f
}

function Get-ValidationSettings([string]$RunDir) {
    $log = (Join-Path $RunDir 'validation.log') -replace '\\', '/'
    @(
        'khronos_validation.debug_action = VK_DBG_LAYER_ACTION_LOG_MSG',
        "khronos_validation.log_filename = $log",
        'khronos_validation.report_flags = error,warn,perf',
        'khronos_validation.validate_core = true',
        'khronos_validation.validate_sync = true',
        'khronos_validation.thread_safety = true',
        'khronos_validation.duplicate_message_limit = 10'
    )
}

function Get-ProfilesSettings([string]$ProfileFile) {
    $p = $ProfileFile -replace '\\', '/'
    @(
        "khronos_profiles.profile_file = $p",
        'khronos_profiles.profile_name = VP_VOOLKAN_R7_350',
        'khronos_profiles.simulate_capabilities = SIMULATE_API_VERSION_BIT,SIMULATE_PROPERTIES_BIT',
        'khronos_profiles.exclude_device_extensions = VK_KHR_dynamic_rendering,VK_KHR_synchronization2',
        'khronos_profiles.emulate_portability = false',
        'khronos_profiles.debug_actions = DEBUG_ACTION_STDOUT_BIT'
    )
}

# Runs `gradlew runClient` with the given environment. Returns the process exit code
# (or -1 if killed by -AutoQuitSeconds). Output goes to $RunDir\game.log.
function Invoke-RunClient {
    param(
        [string]$RunDir,
        [hashtable]$Env = @{},
        [string]$World,
        [string[]]$GameArgs = @(),
        [int]$AutoQuitSeconds = 0
    )
    $java = Find-Java21
    $env:JAVA_HOME = $java
    $env:Path = "$java\bin;$env:Path"
    foreach ($k in $Env.Keys) { Set-Item -Path "Env:$k" -Value $Env[$k] }

    if ($World) {
        $src = Join-Path $script:Repo "test-world\$World"
        $dst = Join-Path $script:Repo "run\saves\$World"
        if (-not (Test-Path $src)) { throw "Test world '$World' missing. Run tools\make-test-world.ps1 first." }
        New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
        if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
        Copy-Item $src $dst -Recurse
        $GameArgs += @('--quickPlaySingleplayer', $World)
    }

    $argList = @('runClient', '--console=plain')
    if ($GameArgs.Count) { $argList += "--args=`"$($GameArgs -join ' ')`"" }

    $out = Join-Path $RunDir 'game.log'
    $err = Join-Path $RunDir 'game.err.log'
    Write-Host "Run dir: $RunDir"
    $proc = Start-Process -FilePath (Join-Path $script:Repo 'gradlew.bat') -ArgumentList $argList `
        -WorkingDirectory $script:Repo -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Minimized
    if ($AutoQuitSeconds -gt 0) {
        if (-not $proc.WaitForExit($AutoQuitSeconds * 1000)) {
            Write-Host "Auto-quit after $AutoQuitSeconds s"
            & taskkill /T /F /PID $proc.Id | Out-Null
            Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
                Where-Object { $_.CommandLine -match 'KnotClient' } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            return -1
        }
    } else { $proc.WaitForExit() }
    return $proc.ExitCode
}

function Show-RunResult([string]$RunDir, [int]$Code) {
    $log = Join-Path $RunDir 'game.log'
    Write-Host "Exit code: $Code"
    if (Test-Path $log) {
        Select-String -Path $log -Pattern 'Unsupported extension|Failed to find a suitable GPU|Game crashed|Device:|Vulkan Version' |
            Select-Object -First 8 | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
    }
    $v = Join-Path $RunDir 'validation.log'
    if (Test-Path $v) { Write-Host "Validation log: $v  (summarise with tools\summarise-vk-log.ps1)" }
}


