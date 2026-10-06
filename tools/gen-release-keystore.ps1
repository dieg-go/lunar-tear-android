<#
.SYNOPSIS
  Creates the app's release signing key, plus the keystore.properties that
  points the build at it.

.DESCRIPTION
  Android identifies an app by its signing key. An APK signed with a different
  key cannot be installed over one that is already out: the user has to
  uninstall first, and uninstalling takes the host app's files/db/game.db -
  every account, quest and pull - with it.

  So this key, once shipped, is forever. Keep it, and back it up somewhere that
  is neither this repository nor tools/.cache.

  That is also why the key is written OUTSIDE the repository, so that no future
  .gitignore mistake can ever leak it:

      %USERPROFILE%\.lunar-tear\lunar-tear-release.jks

  keystore.properties (the passwords; gitignored) goes to the repository root
  for app/build.gradle.kts to read. Back that up too, or at least keep the
  password somewhere safe.

  This script refuses to overwrite an existing key unless -Force is passed,
  because replacing a key that has already been published is precisely the
  accident it exists to prevent.
#>
[CmdletBinding()]
param(
    [string]$OutDir = (Join-Path $env:USERPROFILE '.lunar-tear'),
    [string]$FileName = 'lunar-tear-release.jks',
    [string]$Alias = 'lunartear',
    [string]$DName = 'CN=Lunar Tear,O=Lunar Tear',
    [int]$ValidityDays = 10000,
    [switch]$Force
)

$ErrorActionPreference = 'Continue'   # native tools write progress to stderr; see tools/_env.ps1

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Out      = Join-Path $OutDir $FileName
$Props    = Join-Path $RepoRoot 'keystore.properties'

# ---------------------------------------------------------------- keytool
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
    Write-Host "[release-key] already exists: $Out"
    Write-Host '[release-key] Refusing to replace it: an app already shipped with this'
    Write-Host '[release-key] key could never be updated in place again. Re-run with -Force'
    Write-Host '[release-key] only if this key has never been published.'
    exit 1
}
if (Test-Path $Props) { Write-Host "[release-key] overwriting $Props" }

New-Item -ItemType Directory -Force $OutDir | Out-Null

# A random password rather than a memorable one: it only encrypts the key at
# rest, and the build reads it from the gitignored keystore.properties.
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$buf = New-Object byte[] 24
$rng.GetBytes($buf)
$Password = ([Convert]::ToBase64String($buf)) -replace '[^A-Za-z0-9]', ''

Remove-Item $Out -ErrorAction SilentlyContinue
& $keytool -genkeypair `
    -keystore $Out `
    -storetype PKCS12 `
    -alias $Alias `
    -storepass $Password `
    -keypass $Password `
    -keyalg RSA `
    -keysize 4096 `
    -validity $ValidityDays `
    -dname $DName | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Out)) { throw "keytool failed to create the keystore (exit $LASTEXITCODE)" }

# Forward slashes on purpose: java.util.Properties reads a backslash as an
# escape character, so C:\Users\... would load as C:Users... and the build would
# fail with a confusing "keystore not found".
$storePath = $Out -replace '\\', '/'
@"
# Machine-local signing key for app-release.apk. Gitignored - never commit it.
# Written by tools/gen-release-keystore.ps1 on $(Get-Date -Format 'yyyy-MM-dd').
storeFile=$storePath
storePassword=$Password
keyAlias=$Alias
keyPassword=$Password
"@ | Set-Content -Path $Props -Encoding ASCII

Write-Host "[release-key] key    : $Out"
Write-Host "[release-key] config : $Props"
Write-Host ''
Write-Host '[release-key] Fingerprint - record it, and compare it against future builds:'
& $keytool -list -v -keystore $Out -storepass $Password -alias $Alias |
    Select-String -Pattern 'SHA256:' | ForEach-Object { "  $($_.Line.Trim())" }
Write-Host ''
Write-Host '[release-key] Back up BOTH files now. Losing the key after a release means'
Write-Host '[release-key] every existing user has to uninstall, which deletes their save.'
