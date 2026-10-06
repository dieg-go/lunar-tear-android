<#
.SYNOPSIS
  Extracts the two files the APK patcher rewrites from the game APK into a local
  corpus, and regenerates the libil2cpp.so pre-image table.

.DESCRIPTION
  The corpus is what the patcher unit tests run against (real bytes beat mock
  bytes for something this fiddly) and what tools/differential.ps1 compares the
  Python reference against. It is gitignored: it is game data, 140 MB, and must
  never be committed.

  Everything lands in tools/.cache/corpus/.
#>
[CmdletBinding()]
param(
    [string]$Apk,
    [string]$Corpus,
    [switch]$SkipPreimage
)

$ErrorActionPreference = 'Stop'

if (-not $Apk) {
    $Apk = 'F:\bak\lunar-tear-assets\com.square_enix.android_googleplay.nierspww_3.7.1-152_minAPI24(arm64-v8a)(nodpi)_apkmirror.com.apk'
}
if (-not $Corpus) { $Corpus = Join-Path $PSScriptRoot '.cache\corpus' }

if (-not (Test-Path $Apk)) { throw "game APK not found: $Apk (pass -Apk)" }
New-Item -ItemType Directory -Force $Corpus | Out-Null

Write-Host "[corpus] extracting from $Apk"
& 7z e $Apk 'assets/bin/Data/Managed/Metadata/global-metadata.dat' 'lib/arm64-v8a/libil2cpp.so' "AndroidManifest.xml" -o"$Corpus" -y |
    Select-String -Pattern 'Everything is Ok|Error|ERROR' | ForEach-Object { "  $_" }

$expected = @{
    'global-metadata.dat' = 23317064
    'libil2cpp.so'        = 127359872
    'AndroidManifest.xml' = 39228
}
foreach ($name in $expected.Keys) {
    $path = Join-Path $Corpus $name
    if (-not (Test-Path $path)) { throw "extraction did not produce $name" }
    $size = (Get-Item $path).Length
    $mark = if ($size -eq $expected[$name]) { 'ok' } else { "size differs from the 3.7.1 reference ($($expected[$name]))" }
    Write-Host ("[corpus] {0,-22} {1,12:N0} bytes  {2}" -f $name, $size, $mark)
}

# The manifest is a binary AXML; keep a decoded listing for the AXML editor tests
# so a regression in the encoder is visible without a device.
$aapt2 = Join-Path $PSScriptRoot '.cache\sdk\build-tools\35.0.0\aapt2.exe'
if (Test-Path $aapt2) {
    & $aapt2 dump xmltree --file AndroidManifest.xml $Apk > (Join-Path $Corpus 'AndroidManifest.xmltree.txt') 2>&1
    Write-Host "[corpus] wrote AndroidManifest.xmltree.txt (aapt2 reference decode)"
}

if (-not $SkipPreimage) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'dump-il2cpp-preimage.ps1') -So (Join-Path $Corpus 'libil2cpp.so') |
        Select-String -Pattern 'wrote|not found' | ForEach-Object { "  $_" }
}

Write-Host '[corpus] done'
