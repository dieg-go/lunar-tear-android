# Waits for the on-device revision-0 extraction to finish, then reports.
# Kept as a background job so the session is notified on completion instead of
# polling the phone in the foreground.

$ErrorActionPreference = 'Continue'
$maxIterations = 400          # 400 * 45s = 5h ceiling
$i = 0
while ($i -lt $maxIterations) {
    $i++
    Start-Sleep -Seconds 45
    $out = (adb shell 'tail -c 300 /data/local/tmp/7z-extract.log' 2>&1) -join "`n"
    if ($out -match 'exit=') { break }
}

Write-Host '=== extraction finished ==='
adb shell 'tail -c 1500 /data/local/tmp/7z-extract.log'
Write-Host ''
Write-Host '=== destination ==='
adb shell 'du -sh /sdcard/lunar-tear-full 2>/dev/null'
adb shell 'ls -la /sdcard/lunar-tear-full/assets/ /sdcard/lunar-tear-full/assets/revisions/0/ 2>/dev/null'
adb shell 'df -h /sdcard | tail -1'
