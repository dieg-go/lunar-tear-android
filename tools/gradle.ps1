<#
.SYNOPSIS
  Runs Gradle for this repo with a fully workspace-local environment.

.DESCRIPTION
  Every Gradle invocation should go through here so that JAVA_HOME, the Android
  SDK and GRADLE_USER_HOME are consistent (all inside tools/.cache) regardless of
  what the machine has configured.

  Usage:
    powershell -ExecutionPolicy Bypass -File tools\gradle.ps1 assembleDebug
    powershell -ExecutionPolicy Bypass -File tools\gradle.ps1 :app:testDebugUnitTest --info
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @('assembleDebug'),
    [switch]$UseSystemGradleUserHome
)

. "$PSScriptRoot\_env.ps1"

if ($UseSystemGradleUserHome) { Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue }

$RepoRoot  = $script:LunarTearRepoRoot
$Gradlew   = Join-Path $RepoRoot 'gradlew.bat'

if (-not (Test-Path $Gradlew)) {
    Write-Host "[gradle] gradlew.bat missing; bootstrapping the wrapper first"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'bootstrap-gradle.ps1') -SkipSdk
    if (-not (Test-Path $Gradlew)) { Write-Host '[gradle] ERROR: wrapper still missing' -ForegroundColor Red; exit 1 }
}

if (-not $env:JAVA_HOME) { Write-Host '[gradle] ERROR: no JDK found; set JAVA_HOME' -ForegroundColor Red; exit 1 }

Write-Host "[gradle] JAVA_HOME=$env:JAVA_HOME"
Write-Host "[gradle] ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "[gradle] GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Host "[gradle] ./gradlew $($GradleArgs -join ' ')"

Push-Location $RepoRoot
try {
    & $Gradlew --no-daemon @GradleArgs
    $code = $LASTEXITCODE
} finally { Pop-Location }

Write-Host "[gradle] exit=$code"
exit $code
