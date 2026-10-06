<#
.SYNOPSIS
  Bootstraps a self-contained Android SDK for building this app. Nothing is
  installed machine-wide: everything lands under tools/.cache/.

.DESCRIPTION
  - Downloads Android command-line tools (pinned build) into tools/.cache/sdk
  - Accepts SDK licenses non-interactively
  - Installs platform-tools, platforms;android-35, build-tools;35.0.0
  - Optional: -WithEmulator also installs the emulator + an x86_64 system image
  - Writes local.properties (sdk.dir) so Gradle finds the SDK

  Idempotent: re-running skips anything already present.
#>
[CmdletBinding()]
param(
    # cmdline-tools build id. See https://developer.android.com/studio#command-line-tools-only
    [string]$CmdlineToolsBuild = '13114758',
    [string]$CompileSdk        = '35',
    [string]$BuildTools        = '35.0.0',
    [switch]$WithEmulator
)

# NOTE: Windows PowerShell 5.1 turns a native command's stderr into a
# terminating error when it is merged with 2>&1 under ErrorActionPreference
# 'Stop'. These bootstrap scripts therefore never merge native stderr into the
# pipeline; they check $LASTEXITCODE explicitly instead.
$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Cache    = Join-Path $PSScriptRoot '.cache'
$SdkRoot  = Join-Path $Cache 'sdk'
$LogDir   = Join-Path $Cache 'logs'
New-Item -ItemType Directory -Force -Path $Cache, $LogDir, $SdkRoot | Out-Null

function Write-Step($msg) { Write-Host "[sdk] $msg" }

# ---------------------------------------------------------------- Java
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $candidates = @(
        'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
        'C:\Program Files\Eclipse Adoptium\jdk-21*',
        'C:\Program Files\Java\jdk-21*',
        'C:\Program Files\Android\Android Studio\jbr'
    )
    foreach ($c in $candidates) {
        $hit = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit -and (Test-Path (Join-Path $hit.FullName 'bin\java.exe'))) {
            $env:JAVA_HOME = $hit.FullName
            break
        }
    }
}
if (-not $env:JAVA_HOME) { throw "JAVA_HOME not set and no JDK 17+ found. Install a JDK (e.g. Adoptium 21) first." }
Write-Step "JAVA_HOME=$env:JAVA_HOME"

# ---------------------------------------------------------------- cmdline-tools
$SdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $SdkManager)) {
    $zip = Join-Path $Cache "commandlinetools-win-$CmdlineToolsBuild`_latest.zip"
    if (-not (Test-Path $zip)) {
        $url = "https://dl.google.com/android/repository/commandlinetools-win-$CmdlineToolsBuild`_latest.zip"
        Write-Step "downloading $url"
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    }
    $bytes = [System.IO.File]::ReadAllBytes($zip)[0..1]
    if ($bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) { throw "Downloaded cmdline-tools is not a zip: $zip" }
    Write-Step "extracting cmdline-tools"
    $tmp = Join-Path $Cache 'cmdline-tools-extract'
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $latest = Join-Path $SdkRoot 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $latest) | Out-Null
    Remove-Item -Recurse -Force $latest -ErrorAction SilentlyContinue
    Move-Item (Join-Path $tmp 'cmdline-tools') $latest
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
if (-not (Test-Path $SdkManager)) { throw "sdkmanager not found at $SdkManager" }
Write-Step "sdkmanager: $SdkManager"

# ---------------------------------------------------------------- licenses
Write-Step 'accepting licenses'
$y = (1..40 | ForEach-Object { 'y' }) -join "`n"
$y | & $SdkManager --sdk_root=$SdkRoot --licenses | ForEach-Object { if ($_ -notmatch '^\s*$') { "  $_" } }

# ---------------------------------------------------------------- packages
$packages = @('platform-tools', "platforms;android-$CompileSdk", "build-tools;$BuildTools")
if ($WithEmulator) {
    $packages += @('emulator', 'system-images;android-33;google_apis;x86_64')
}
Write-Step "installing: $($packages -join ', ')"
& $SdkManager --sdk_root=$SdkRoot @packages | ForEach-Object { if ($_ -notmatch '^\s*$') { "  $_" } }
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }

# ---------------------------------------------------------------- local.properties
$props = Join-Path $RepoRoot 'local.properties'
$escaped = $SdkRoot -replace '\\', '\\'
Set-Content -Path $props -Value "sdk.dir=$escaped" -Encoding ascii
Write-Step "wrote $props"

# ---------------------------------------------------------------- verify
$zipalign = Join-Path $SdkRoot "build-tools\$BuildTools\zipalign.exe"
$apksigner = Join-Path $SdkRoot "build-tools\$BuildTools\apksigner.bat"
$aapt2 = Join-Path $SdkRoot "build-tools\$BuildTools\aapt2.exe"
foreach ($tool in @($zipalign, $apksigner, $aapt2)) {
    if (-not (Test-Path $tool)) { throw "expected tool missing: $tool" }
}
Write-Step 'build-tools verified: zipalign, apksigner, aapt2'
Write-Step 'done'
