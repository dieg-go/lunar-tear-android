<#
.SYNOPSIS
  Generates the debug keystore the on-device APK patcher signs with.

.DESCRIPTION
  Patching the game APK invalidates its original signature, so the result has to
  be re-signed. That needs a private key; this script creates a throwaway one
  with keytool (part of the JDK) and drops the keystore into the app's assets.

  The keystore is gitignored: it is a debug key, and every user generating their
  own means patched APKs are not signed by a key that is published in a repo.

  The Kotlin side reads it from assets at patch time - see ApkSigning.
#>
[CmdletBinding()]
param(
    [string]$Alias = 'lunartear',
    [string]$Password = 'lunartear',
    [string]$DName = 'CN=Lunar Tear Debug,O=Lunar Tear,C=US',
    [int]$ValidityDays = 10000,
    [switch]$Force
)

$ErrorActionPreference = 'Continue'   # native tools write progress to stderr; see tools/_env.ps1

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Assets   = Join-Path $RepoRoot 'app\src\main\assets'
$Out      = Join-Path $Assets 'lunar-tear-debug.keystore'

if (-not $env:JAVA_HOME) {
    foreach ($c in @(
            'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
            'C:\Program Files\Eclipse Adoptium\jdk-21*',
            'C:\Program Files\Java\jdk-21*')) {
        $hit = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { $env:JAVA_HOME = $hit.FullName; break }
    }
}
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME not set and no JDK found' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { throw "keytool not found at $keytool" }

if ((Test-Path $Out) -and -not $Force) {
    Write-Host "[keystore] already present: $Out (use -Force to regenerate)"
    exit 0
}

New-Item -ItemType Directory -Force $Assets | Out-Null
Remove-Item $Out -ErrorAction SilentlyContinue

& $keytool -genkeypair `
    -keystore $Out `
    -storetype PKCS12 `
    -alias $Alias `
    -storepass $Password `
    -keypass $Password `
    -keyalg RSA `
    -keysize 2048 `
    -validity $ValidityDays `
    -dname $DName | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Out)) { throw "keytool failed to create the keystore (exit $LASTEXITCODE)" }

Write-Host "[keystore] wrote $Out ($((Get-Item $Out).Length) bytes, alias '$Alias')"
& $keytool -list -keystore $Out -storepass $Password | Select-String -Pattern 'PrivateKeyEntry|Your keystore' | ForEach-Object { "  $($_.Line.Trim())" }
