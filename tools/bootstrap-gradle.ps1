<#
.SYNOPSIS
  Downloads a pinned Gradle distribution into tools/.cache and generates the
  Gradle wrapper for this repo.

.DESCRIPTION
  Keeps the build self-contained: no system Gradle and no machine-wide installs.

  Note: this script intentionally does NOT hand-write gradle-wrapper.jar.
  The wrapper is produced by Gradle itself so the jar always matches the
  distribution it points at.
#>
[CmdletBinding()]
param(
    [string]$GradleVersion = '8.9',
    [switch]$SkipWrapper
)

. "$PSScriptRoot\_env.ps1"

$RepoRoot   = $script:LunarTearRepoRoot
$Cache      = $script:LunarTearCache
$GradleHome = Join-Path $Cache "gradle\gradle-$GradleVersion"
$GradleBat  = Join-Path $GradleHome 'bin\gradle.bat'

function Write-Step($msg) { Write-Host "[gradle] $msg" }
function Fail($msg) { Write-Host "[gradle] ERROR: $msg" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $GradleBat)) {
    $zip = Join-Path $Cache "gradle-$GradleVersion-bin.zip"
    if (-not (Test-Path $zip)) {
        $url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
        Write-Step "downloading $url"
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    }
    $bytes = [System.IO.File]::ReadAllBytes($zip)[0..1]
    if ($bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) { Fail "downloaded Gradle is not a zip: $zip" }
    Write-Step "extracting to $Cache\gradle"
    Expand-Archive -Path $zip -DestinationPath (Join-Path $Cache 'gradle') -Force
}
if (-not (Test-Path $GradleBat)) { Fail "gradle not found at $GradleBat" }
Write-Step "gradle distribution: $GradleBat"

if (-not $SkipWrapper) {
    Write-Step 'generating wrapper (gradlew, gradlew.bat, gradle-wrapper.jar/properties)'
    Push-Location $RepoRoot
    try {
        & $GradleBat wrapper --gradle-version $GradleVersion --distribution-type bin --no-daemon
        if ($LASTEXITCODE -ne 0) { Fail "gradle wrapper failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    $wrapperJar = Join-Path $RepoRoot 'gradle\wrapper\gradle-wrapper.jar'
    if (-not (Test-Path $wrapperJar)) { Fail "wrapper jar not generated at $wrapperJar" }
    Write-Step "wrapper ready: $wrapperJar"
    Write-Step "gradlew points at Gradle $GradleVersion; run builds via tools\gradle.ps1"
}

Write-Step 'done'
