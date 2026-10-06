# Runs the patched client against the on-device server and captures everything
# needed to explain where it stops:
#   - the host app's own session log (which contains every server-side request,
#     because the Go servers' stdout is tee'd into it)
#   - logcat filtered to the game's pid, for the client's own errors
#   - a screenshot of the client's current screen
#
#   powershell -ExecutionPolicy Bypass -File tools\device\run-client-test.ps1
#   powershell -ExecutionPolicy Bypass -File tools\device\run-client-test.ps1 -ObserveSeconds 240
#
# Nothing here modifies the game's data: it restarts the host app's foreground
# service and launches the game, then reads logs.

[CmdletBinding()]
param(
    [string]$Package = 'dev.lunartear.host.debug',
    [string]$GamePackage = 'com.square_enix.android_googleplay.nierspww',
    [string]$GameActivity = 'com.google.firebase.MessagingUnityPlayerActivity',
    [string]$AssetRoot = '/sdcard/lunar-tear-full',
    [int]$ServerTimeoutSeconds = 150,
    [int]$ObserveSeconds = 150
)

$ErrorActionPreference = 'Continue'
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $RepoRoot "tools\.cache\client-test-$stamp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Adb([string[]]$Arguments) { return ((& adb @Arguments 2>&1) | Out-String) }

Write-Host "== output dir: $outDir =="

# ---------------------------------------------------------------- 1. host app
Write-Host '== restart host app with the new asset root =='
Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Start-Sleep -Seconds 3
Adb @('shell', 'am', 'start', '-n', "$Package/dev.lunartear.host.MainActivity",
    '--es', 'assetRoot', $AssetRoot,
    '--ez', 'autoStart', 'true') | Out-Null

# ---------------------------------------------------------------- 2. wait up
Write-Host '== waiting for both servers to listen =='
$deadline = (Get-Date).AddSeconds($ServerTimeoutSeconds)
$ports = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $listen = Adb @('shell', 'netstat -ltn 2>/dev/null | grep -E ":8003|:8080"')
    if (($listen -match ':8003') -and ($listen -match ':8080')) { $ports = $true; break }
}
Write-Host ("   ports listening: {0}" -f $ports)

# ---------------------------------------------------------------- 3. game
Write-Host '== launching game (fresh) =='
Adb @('logcat', '-c') | Out-Null
Adb @('shell', 'am', 'force-stop', $GamePackage) | Out-Null
Start-Sleep -Seconds 3
Adb @('shell', 'am', 'start', '-n', "$GamePackage/$GameActivity") | Out-Null
Write-Host ("== observing for {0}s ==" -f $ObserveSeconds)
Start-Sleep -Seconds $ObserveSeconds

# ---------------------------------------------------------------- 4. evidence
$shot = Join-Path $outDir 'screen.png'
& cmd /c "adb exec-out screencap -p > `"$shot`"" | Out-Null
Write-Host ("   screenshot: {0}" -f $shot)

$listing = Adb @('shell', 'run-as', $Package, 'ls', '-t', "/data/data/$Package/files/logs/")
$session = ($listing -split "`n" | Where-Object { $_ -match '\.log' } | Select-Object -First 1)
if ($session) { $session = $session.Trim() }
$serverLogPath = Join-Path $outDir 'server-session.log'
if ($session) {
    Adb @('shell', 'run-as', $Package, 'cat', "/data/data/$Package/files/logs/$session") |
        Set-Content -Path $serverLogPath -Encoding UTF8
    Write-Host ("   server session log: {0} ({1})" -f $serverLogPath, $session)
} else {
    Write-Host '   no server session log found' -ForegroundColor Yellow
}

$logcatPath = Join-Path $outDir 'logcat.txt'
Adb @('logcat', '-d', '-v', 'time') | Set-Content -Path $logcatPath -Encoding UTF8
$all = Get-Content $logcatPath
$gamePid = (Adb @('shell', "pidof $GamePackage")).Trim()
Write-Host ("   logcat: {0} lines, game pid {1}" -f $all.Count, $gamePid)

# ---------------------------------------------------------------- 5. analysis
if (Test-Path $serverLogPath) {
    $srv = Get-Content $serverLogPath
    Write-Host ''
    Write-Host '== server-side requests the client made (first 60) =='
    $srv | Select-String -Pattern '^\[?(HTTP|OctoV2)|>>> |^\[admin\]|UNHANDLED|error|Error|failed|panic' |
        Select-Object -First 60 | ForEach-Object { $_.Line }
    Write-Host ''
    Write-Host '== server log tail (last 25) =='
    $srv | Select-Object -Last 25
}

Write-Host ''
Write-Host '== client logcat (errors / network) =='
$all | Select-String -Pattern 'Unity|il2cpp|WebRequest|Exception|curl|SSL|TLS|Cannot|Refused|refused|Unable to|SocketException|nier|Rein|CRIWARE' |
    Where-Object { $_.Line -notmatch 'LOWIDiagLog|AppsFilter|WindowManager|TopTaskTracker|InputManager|CoreBackPreview' } |
    Select-Object -Last 50 | ForEach-Object { $_.Line }

Write-Host ''
Write-Host "== raw artifacts in $outDir =="
Get-ChildItem $outDir | Select-Object Name, Length | Format-Table -AutoSize
