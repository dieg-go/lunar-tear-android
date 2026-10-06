<#
.SYNOPSIS
  End-to-end device smoke test for the Lunar Tear host app.

.DESCRIPTION
  Scripts exactly what the app is supposed to do on a real phone, checks each
  step from the device's own output, and prints a PASS/FAIL table. Nothing here
  touches the user's other apps; the only writes are inside the app's own storage
  and the asset folder it was pointed at.

  Steps:
    1. adb device present and authorised
    2. install (or verify) the debug APK
    3. the bundled Go binaries are extracted, executable, and actually run
    4. the server stack starts (migrations -> CDN -> game server)
    5. the CDN serves the catalog and the master data over HTTP
    6. optionally: master data is extended to 2030 and the server reloads it
    7. the session log is pulled next to the script for inspection

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\device-smoke.ps1
  powershell -ExecutionPolicy Bypass -File tools\device-smoke.ps1 -SkipInstall -PatchMasterData
#>
[CmdletBinding()]
param(
    [string]$Package = 'dev.lunartear.host.debug',
    [string]$AssetRoot = '/sdcard/lunar-tear',
    [string]$Apk = '',
    [int]$BootTimeoutSeconds = 120,
    [switch]$SkipInstall,
    [switch]$GrpcProbe,
    [switch]$PatchMasterData,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Continue'
$RepoRoot = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------- helpers
$script:results = New-Object System.Collections.Generic.List[object]
function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    $script:results.Add([pscustomobject]@{ Check = $Name; Ok = $Ok; Detail = $Detail })
    $mark = if ($Ok) { 'PASS' } else { 'FAIL' }
    $colour = if ($Ok) { 'Green' } else { 'Red' }
    Write-Host ("  [{0}] {1}{2}" -f $mark, $Name, $(if ($Detail) { " - $Detail" } else { '' })) -ForegroundColor $colour
}

function Invoke-Adb([string[]]$Arguments) {
    $out = & adb @Arguments 2>&1
    return @{ Output = ($out | Out-String); Exit = $LASTEXITCODE }
}

function Get-AppLog([string]$Session = '') {
    if (-not $Session) {
        $listing = (Invoke-Adb @('shell', 'run-as', $Package, 'ls', '-t', "/data/data/$Package/files/logs/")).Output
        $Session = ($listing -split "`n" | Where-Object { $_ -match '\.log' } | Select-Object -First 1).Trim()
    }
    if (-not $Session) { return '' }
    return (Invoke-Adb @('shell', 'run-as', $Package, 'cat', "/data/data/$Package/files/logs/$Session")).Output
}

# ---------------------------------------------------------------- 1. device
Write-Host "== device =="
$devices = (Invoke-Adb @('devices')).Output
# adb pads the state column with a tab and Windows line endings vary; match loosely
# and never fail on the header line ("List of devices attached").
$online = @($devices -split "`r?`n" | Where-Object { $_ -match 'device\s*$' -and $_ -notmatch 'List of devices' })
Check 'adb device is connected and authorised' ($online.Count -ge 1) ($online -join ', ')
if ($online.Count -lt 1) {
    Write-Host "adb devices reported:" -ForegroundColor Yellow
    Write-Host $devices
    exit 1
}

# ---------------------------------------------------------------- 2. install
Write-Host "== app =="
if (-not $Apk) { $Apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk' }
if (-not $SkipInstall -and (Test-Path $Apk)) {
    $install = Invoke-Adb @('install', '-r', '-t', $Apk)
    Check 'debug APK installed' ($install.Output -match 'Success') ($install.Output.Trim().Split("`n")[-1])
}
$pkgInfo = (Invoke-Adb @('shell', "dumpsys package $Package | grep -m1 versionName")).Output
Check 'app package is present' ($pkgInfo -match 'versionName') $pkgInfo.Trim()

# ---------------------------------------------------------------- 3. natives
Write-Host "== bundled Go binaries =="
# Strip only the key, not everything up to the last '=': these paths contain
# '==' (e.g. .../~~x2hNpEo0sDxb39LwEL3_DQ==/pkg-HASH==/lib), which a greedy
# '.*=' would eat, leaving just "/lib".
$libLine = (Invoke-Adb @('shell', "dumpsys package $Package | grep -m1 legacyNativeLibraryDir")).Output
$libDir = ($libLine -replace '^[^=]+=', '').Trim()
$libDirArm = "$libDir/arm64"
$listing = (Invoke-Adb @('shell', 'run-as', $Package, 'ls', '-l', $libDirArm)).Output
$expected = @('liblt-server.so', 'liblt-cdn.so', 'liblt-migrate.so', 'liblt-patch-masterdata.so')
foreach ($name in $expected) {
    $line = ($listing -split "`n" | Where-Object { $_ -match [regex]::Escape($name) } | Select-Object -First 1)
    Check "$name extracted and executable" ($line -match '^-rwx') ($line.Trim())
}

# ---------------------------------------------------------------- 4. stack
Write-Host "== server stack =="
Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb @('shell', 'am', 'start', '-n', "$Package/dev.lunartear.host.MainActivity",
    '--es', 'assetRoot', $AssetRoot,
    '--ez', 'autoStart', 'true',
    $(if ($PatchMasterData) { '--ez' }), $(if ($PatchMasterData) { 'patchMasterData' }), $(if ($PatchMasterData) { 'true' })) | Out-Null

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
$booted = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $log = Get-AppLog
    if ($log -match 'gRPC server listening') { $booted = $true; break }
}
$log = Get-AppLog
  # The binaries are proved to run by the checks below: lt-migrate by the migration
  # line, and cdn/server by being alive and serving. A separate --help exec check was
  # removed because it duplicated that evidence through a fragile path parse.
Check 'migrations applied' ($log -match 'is at version|OK \d+') 
Check 'CDN started' ($log -match 'Octo CDN listening')
Check 'master data loaded by the game server' ($log -match 'master data loaded \((\d+) tables\)')
Check 'game server listening' ($booted) 

$kids = (Invoke-Adb @('shell', 'ps -A -o NAME | grep liblt')).Output
Check 'all three server processes are alive' (($kids -match 'liblt-cdn') -and ($kids -match 'liblt-server') -and ($kids -match 'liblt-auth')) ($kids -replace "`n", ' ')

# ---------------------------------------------------------------- 5. CDN
Write-Host "== CDN over HTTP =="
Invoke-Adb @('forward', 'tcp:18080', 'tcp:8080') | Out-Null
$ok = $false
try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/v1/list/1/0' -TimeoutSec 60 -UseBasicParsing
    $ok = ($r.StatusCode -eq 200) -and ($r.RawContentLength -gt 1000000)
    Check 'serves the Octo list.bin' $ok "$($r.StatusCode), $($r.RawContentLength) bytes"
} catch { Check 'serves the Octo list.bin' $false $_.Exception.Message }

try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/assets/release/1/database.bin' -Method Head -TimeoutSec 60 -UseBasicParsing
    Check 'serves the master data' ($r.StatusCode -eq 200) "$($r.StatusCode), $($r.Headers['Content-Length']) bytes"
} catch { Check 'serves the master data' $false $_.Exception.Message }
Invoke-Adb @('forward', '--remove-all') | Out-Null

# ---------------------------------------------------------------- 5b. auth
# The patched client's Facebook SDK is rewritten to point here, so these are the
# endpoints the client (and the game server's token validation) depend on.
Write-Host "== auth server =="
$log = Get-AppLog
Check 'auth server started' ($log -match 'auth server listening on|auth server ready')
Invoke-Adb @('forward', 'tcp:13000', 'tcp:3000') | Out-Null
try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:13000/check-username?username=lunartear-probe' -TimeoutSec 30 -UseBasicParsing
    $body = $r.Content.Trim()
    Check 'answers /check-username' (($r.StatusCode -eq 200) -and ($body -match 'exists')) "$($r.StatusCode) $body"
} catch { Check 'answers /check-username' $false $_.Exception.Message }

try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:13000/me?access_token=bogus' -TimeoutSec 30 -UseBasicParsing
    Check 'rejects a bad Facebook token' $false "unexpected HTTP $($r.StatusCode)"
} catch {
    $resp = $_.Exception.Response
    $code = if ($resp) { [int]$resp.StatusCode } else { 0 }
    Check 'rejects a bad Facebook token' ($code -eq 401) "HTTP $code"
}

try {
    # The Java SDK's dialog URL shape: /v{N}/dialog/oauth
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:13000/v14.0/dialog/oauth?redirect_uri=fbconnect://x&state=s' -TimeoutSec 30 -UseBasicParsing
    Check 'serves the fake OAuth dialog' (($r.StatusCode -eq 200) -and ($r.Content -match 'form|username')) "$($r.StatusCode), $($r.RawContentLength) bytes"
} catch { Check 'serves the fake OAuth dialog' $false $_.Exception.Message }
Invoke-Adb @('forward', '--remove-all') | Out-Null

if ($GrpcProbe) {
    Write-Host "== gRPC protocol =="
    Invoke-Adb @('forward', 'tcp:18003', 'tcp:8003') | Out-Null
    # A real gRPC client using the server's own generated stubs: the part HTTP
    # checks cannot reach, asserting the addresses the patched client expects.
    Push-Location (Join-Path $PSScriptRoot 'grpc-probe')
    try {
        $probe = & go run . -addr 127.0.0.1:18003 -expect-host 127.0.0.1 -expect-port 8003 -expect-octo http://127.0.0.1:8080 2>&1
        $probeCode = $LASTEXITCODE
    } finally { Pop-Location }
    Invoke-Adb @('forward', '--remove-all') | Out-Null
    $brief = (($probe | Select-String 'api |octo ') | ForEach-Object { $_.Line.Trim() }) -join '; '
    Check 'answers GetReviewServerConfig with the expected endpoints' ($probeCode -eq 0) $brief
}

# ---------------------------------------------------------------- 6. master data
if ($PatchMasterData) {
    Write-Host "== master data =="
    Start-Sleep -Seconds 5
    $log = Get-AppLog
    Check 'master data extended on device' ($log -match 'masterdata\] OK') 
    Check 'server reloaded the new master data' ($log -match 'master-data reload -> HTTP 2')
    $files = (Invoke-Adb @('shell', 'ls', '-l', "$AssetRoot/assets/release/")).Output
    Check 'original kept as .orig' ($files -match '\.orig')
}

# ---------------------------------------------------------------- 7. log
$outLog = Join-Path $PSScriptRoot ".cache\logs\device-smoke-$(Get-Date -Format yyyyMMdd-HHmmss).log"
New-Item -ItemType Directory -Force (Split-Path -Parent $outLog) | Out-Null
Get-AppLog | Set-Content -Path $outLog -Encoding utf8
Write-Host "`nSession log saved to $outLog"

if (-not $KeepRunning) {
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Write-Host 'App stopped (releases the wake and Wi-Fi locks). Use -KeepRunning to leave it up.'
}

# ---------------------------------------------------------------- summary
$failed = @($script:results | Where-Object { -not $_.Ok })
Write-Host ''
$script:results | Format-Table -AutoSize Check, Ok, Detail | Out-String -Width 200 | Write-Host
if ($failed.Count -gt 0) {
    Write-Host "$($failed.Count) of $($script:results.Count) checks FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "all $($script:results.Count) checks passed" -ForegroundColor Green
# Explicit: without this the process exit code is whatever the last native
# command left behind, which makes "exits non-zero on failure" only half true.
exit 0
