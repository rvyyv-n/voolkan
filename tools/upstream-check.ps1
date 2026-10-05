<#
.SYNOPSIS  Upstream rebase helper: fetch, list what upstream did since our base, optionally cherry-pick.
.PARAMETER Fetch      git fetch upstream --tags first.
.PARAMETER Ref        Upstream ref to compare against (default upstream/dev).
.PARAMETER CherryPick One or more upstream SHAs: creates fix/upstream-<sha> from HEAD and cherry-picks them with -x.
.PARAMETER TrialRebase  Try `git rebase --onto <Ref> upstream-base` in a throwaway worktree and report conflicts (nothing is kept).
#>
param([switch]$Fetch, [string]$Ref = 'upstream/dev', [string[]]$CherryPick, [switch]$TrialRebase)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repo
try {
    if ($Fetch) { git fetch upstream --tags }
    "Base: $(git rev-parse --short upstream-base)   Compare: $Ref ($(git rev-parse --short $Ref))"
    "`nOur commits (upstream-base..HEAD): $(git rev-list --count upstream-base..HEAD)"
    "Upstream commits not in our base (upstream-base..$Ref): $(git rev-list --count upstream-base..$Ref)"
    "`nNewest 25 upstream commits touching src/ or gradle files:"
    git log --oneline -25 "upstream-base..$Ref" -- src gradle.properties build.gradle
    "`nFiles we changed that upstream also changed (likely conflicts):"
    $ours = git diff --name-only upstream-base HEAD
    $theirs = git diff --name-only upstream-base $Ref
    $ours | Where-Object { $theirs -contains $_ } | ForEach-Object { "  $_" }

    if ($CherryPick) {
        $branch = 'fix/upstream-' + ($CherryPick[0].Substring(0, 7))
        git switch -c $branch
        foreach ($sha in $CherryPick) { git cherry-pick -x $sha }
        "On branch $branch. Resolve conflicts if any, add the LGPL notice (docs/conventions.md), then tools\check-notices.ps1."
    }
    if ($TrialRebase) {
        $tmp = Join-Path $env:TEMP ('voolkan-trial-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
        git worktree add --detach $tmp HEAD | Out-Null
        try {
            Push-Location $tmp
            git rebase --onto $Ref upstream-base 2>&1 | Select-Object -Last 5
            "Rebase exit code: $LASTEXITCODE (non-zero = conflicts)"
            git status --short | Where-Object { $_ -match '^(UU|AA|DU|UD)' }
            git rebase --abort 2>$null
            Pop-Location
        } finally { git worktree remove --force $tmp }
    }
} finally { Pop-Location }
