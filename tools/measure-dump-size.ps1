# Measures the uncompressed size of the lunar-tear asset dump on the PC.
# Needed because extracting on the phone requires room for the archive AND the
# tree at the same time; if that does not fit, the PC must do the extraction.

$ErrorActionPreference = 'Continue'
$seven = 'C:\Program Files\7-Zip\7z.exe'
$arch = 'F:\bak\lunar-tear-assets\resource_dump_android.7z'
$out = Join-Path $env:TEMP '7z-sizes.txt'

Write-Host "listing $arch ..."
& $seven l -slt $arch | Select-String '^Size = ' | ForEach-Object { $_.Line.Substring(7) } | Set-Content -Path $out

$sum = 0L
$n = 0
foreach ($line in [System.IO.File]::ReadLines($out)) {
    if ($line -match '^\d+$') { $sum += [int64]$line; $n++ }
}
Write-Host ("entries   = {0}" -f $n)
Write-Host ("extracted = {0} bytes = {1:N2} GiB = {2:N2} GB" -f $sum, ($sum / 1GB), ($sum / 1e9))
$archLen = (Get-Item $arch).Length
Write-Host ("archive   = {0} bytes = {1:N2} GiB" -f $archLen, ($archLen / 1GB))
Write-Host ("peak if extracted on phone = {0:N2} GiB" -f (($sum + $archLen) / 1GB))
