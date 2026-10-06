<#
.SYNOPSIS
  Dumps the pre-image bytes at every libil2cpp.so offset the APK patcher
  rewrites, so the patcher can refuse to touch a binary it does not recognise.

.DESCRIPTION
  patch_apk.py writes these offsets unconditionally ("RVAs from dump.cs"), which
  would silently corrupt any other client build. We instead record the original
  instruction words from the 3.7.1 (versionCode 152, arm64-v8a) binary and verify
  before writing.

  The offsets are used as *file* offsets, exactly like the reference script does.

  Usage:
    powershell -ExecutionPolicy Bypass -File tools\dump-il2cpp-preimage.ps1 `
        -So tools\.cache\corpus\libil2cpp.so
#>
[CmdletBinding()]
param(
    [string]$So,
    [string]$OutFile,
    [switch]$EmitKotlin
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot is not populated while parameter defaults are evaluated, so the
# repo-relative defaults are resolved here instead.
if (-not $So) { $So = Join-Path $PSScriptRoot '.cache\corpus\libil2cpp.so' }
if (-not $OutFile) { $OutFile = Join-Path $PSScriptRoot '.cache\il2cpp-preimage.txt' }

# name, offset, patch length in bytes (must match Il2CppPatcher in the app)
$targets = @(
    @{ Name = 'ToNativeCredentials (SSL bypass)';                       Offset = 0x35C8670; Len = 8 }
    @{ Name = 'HandleNet.Encrypt (passthrough)';                        Offset = 0x279410C; Len = 8 }
    @{ Name = 'HandleNet.Decrypt (passthrough)';                        Offset = 0x279420C; Len = 8 }
    @{ Name = 'OctoManager.Internal.GetListAes (plain list)';           Offset = 0x4C27038; Len = 8 }
    @{ Name = 'PurchaseRealProductAsync.MoveNext (IsInitialized nop)';  Offset = 0x2831CA8; Len = 4 }
    @{ Name = 'Purchaser.IsExistProduct (always true)';                 Offset = 0x282CE78; Len = 8 }
    @{ Name = 'Purchaser.<BuyProduct>d__24.MoveNext (NRE redirect)';    Offset = 0x2834028; Len = 4 }
    @{ Name = 'PurchaseRealProductAsync.MoveNext (skip alert)';         Offset = 0x2831CAC; Len = 4 }
    @{ Name = 'Initialize.MoveNext (skip check)';                       Offset = 0x2830834; Len = 4 }
    @{ Name = 'TitleScreen.InitializeMenuButton (EOS bypass)';          Offset = 0x2F11900; Len = 4 }
    @{ Name = 'NetworkConfig.get_ServerPort (port override)';           Offset = 0x361D548; Len = 8 }
    @{ Name = 'InitializeApiClient.OnStateBegin (port override)';       Offset = 0x2DEAF68; Len = 4 }
    @{ Name = 'CalculatorNetworking.InitializeApiClient (port override)'; Offset = 0x2E1B278; Len = 4 }
)

if (-not (Test-Path $So)) { throw "libil2cpp.so not found: $So" }
$file = Get-Item $So
Write-Host "[preimage] $($file.FullName)  $($file.Length) bytes"

$stream = [System.IO.File]::OpenRead($file.FullName)
try {
    $rows = foreach ($t in $targets) {
        if ($t.Offset + $t.Len -gt $stream.Length) {
            Write-Warning "offset 0x$('{0:X}' -f $t.Offset) is past EOF"
            continue
        }
        $buf = New-Object byte[] $t.Len
        $stream.Seek($t.Offset, 'Begin') | Out-Null
        $read = $stream.Read($buf, 0, $t.Len)
        [pscustomobject]@{
            Name   = $t.Name
            Offset = $t.Offset
            Len    = $t.Len
            Hex    = (($buf | ForEach-Object { $_.ToString('x2') }) -join '')
            U32    = (& {
                    param($b)
                    $words = @()
                    for ($i = 0; $i + 4 -le $b.Length; $i += 4) {
                        $words += "0x{0:X8}" -f ([BitConverter]::ToUInt32($b, $i))
                    }
                    $words -join ' '
                } $buf)
        }
    }
} finally { $stream.Dispose() }

$rows | Format-Table -AutoSize Name, @{ n = 'Offset'; e = { '0x{0:X}' -f $_.Offset } }, Len, Hex, U32 | Out-String -Width 200 | Write-Host

$lines = $rows | ForEach-Object { "{0}`t0x{1:X}`t{2}`t{3}`t{4}" -f $_.Name, $_.Offset, $_.Len, $_.Hex, $_.U32 }
$header = @(
    "# libil2cpp.so pre-image table",
    "# source: $($file.FullName)",
    "# size:   $($file.Length) bytes",
    "# client: com.square_enix.android_googleplay.nierspww 3.7.1 (versionCode 152, arm64-v8a)",
    "# generated: $(Get-Date -Format o)",
    "# name`toffset`tlen`thex`tu32 words"
)
Set-Content -Path $OutFile -Value ($header + $lines) -Encoding utf8
Write-Host "[preimage] wrote $OutFile"

if ($EmitKotlin) {
    $kotlin = $rows | ForEach-Object {
        "        Patch(`"$($_.Name -replace '"', '')`", 0x$('{0:X}' -f $_.Offset)L, $($_.Len), `"$($_.Hex)`"),"
    }
    Write-Host "`n--- Kotlin rows ---"
    $kotlin | Write-Host
}
