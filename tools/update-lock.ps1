<#
.SYNOPSIS
  Regenerates versions.lock.json: the exact upstream lunar-tear revision this
  app's native binaries were built from, plus content hashes of the inputs that
  can change binary behaviour (protos, migrations).

.DESCRIPTION
  The native build script refuses to build when these drift, so a binary in the
  APK can always be traced back to a specific upstream commit.
#>
[CmdletBinding()]
param(
    [string]$LunarTearSrc = $(if ($env:LUNAR_TEAR_SRC) { $env:LUNAR_TEAR_SRC } else { 'C:\Users\diego\lunar-tear' }),
    [string]$LunarScriptsSrc = $(if ($env:LUNAR_SCRIPTS_SRC) { $env:LUNAR_SCRIPTS_SRC } else { 'C:\Users\diego\lunar-scripts' })
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot

function Get-TreeHash([string]$Path, [string]$Filter) {
    $files = Get-ChildItem -Path $Path -Recurse -File -Filter $Filter -ErrorAction SilentlyContinue | Sort-Object FullName
    if (-not $files) { return $null }
    $sb = [System.Text.StringBuilder]::new()
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($Path.Length).TrimStart('\', '/') -replace '\\', '/'
        $h = (Get-FileHash -Algorithm SHA256 -Path $f.FullName).Hash.ToLower()
        [void]$sb.AppendLine("$rel $h")
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $sha = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    ($sha | ForEach-Object { $_.ToString('x2') }) -join ''
}

$serverDir = Join-Path $LunarTearSrc 'server'
if (-not (Test-Path $serverDir)) { throw "lunar-tear server not found at $serverDir" }

Push-Location $LunarTearSrc
try {
    $commit = (& git rev-parse HEAD).Trim()
    $short  = (& git rev-parse --short HEAD).Trim()
    $branch = (& git rev-parse --abbrev-ref HEAD).Trim()
    $dirty  = @(& git status --porcelain) | Where-Object { $_ -and $_ -notmatch '^\?\? server/gen/' }
    $remote = (& git remote get-url origin).Trim()
} finally { Pop-Location }

$lock = [ordered]@{
    generatedBy      = 'tools/update-lock.ps1'
    generatedAtUtc   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    lunarTear        = [ordered]@{
        path            = (Resolve-Path $LunarTearSrc).Path
        remote          = $remote
        branch          = $branch
        commit          = $commit
        shortCommit     = $short
        dirty           = [bool]$dirty
        dirtyDetail     = @($dirty)
        goVersionTarget = '1.25+'
        protosSha256    = Get-TreeHash (Join-Path $serverDir 'proto') '*.proto'
        migrationsSha256 = Get-TreeHash (Join-Path $serverDir 'migrations') '*.sql'
        migrationCount  = @(Get-ChildItem (Join-Path $serverDir 'migrations') -Filter '*.sql').Count
    }
    lunarScripts     = [ordered]@{
        path     = (Resolve-Path $LunarScriptsSrc -ErrorAction SilentlyContinue).Path
        patchesSha256 = Get-TreeHash (Join-Path $LunarScriptsSrc 'android') '*.py'
        masterdataSha256 = if (Test-Path (Join-Path $LunarScriptsSrc 'patch_masterdata.py')) { (Get-FileHash (Join-Path $LunarScriptsSrc 'patch_masterdata.py') -Algorithm SHA256).Hash.ToLower() } else { $null }
        listbinSha256 = if (Test-Path (Join-Path $LunarScriptsSrc 'assetbundles\patch_listbin.py')) { (Get-FileHash (Join-Path $LunarScriptsSrc 'assetbundles\patch_listbin.py') -Algorithm SHA256).Hash.ToLower() } else { $null }
    }
    client           = [ordered]@{
        packageName = 'com.square_enix.android_googleplay.nierspww'
        versionName = '3.7.1'
        versionCode = 152
        targetSdk   = 33
        abi         = 'arm64-v8a'
    }
    android          = [ordered]@{
        compileSdk = 35
        buildTools = '35.0.0'
        gradle     = '8.9'
        agp        = '8.7.3'
        kotlin     = '2.0.21'
    }
}

$out = Join-Path $RepoRoot 'versions.lock.json'
$lock | ConvertTo-Json -Depth 6 | Set-Content -Path $out -Encoding utf8
Write-Host "[lock] wrote $out"
Write-Host "[lock] lunar-tear $short ($branch) dirty=$([bool]$dirty) migrations=$($lock.lunarTear.migrationCount)"
