# Targeted probe: who else points inside the DEX string item for a given text?
#
# DexPatcher shortens a string in place and zeroes the tail within the item's
# original extent. That is only safe if no other string_id points inside it -
# ART/dexdump reported "String longer than indicated size 0" after shortening
# "facebook.com", which is what a string_id landing on one of those new zeros
# looks like.
#
#   powershell -ExecutionPolicy Bypass -File tools\dex-string-item.ps1 <file.dex> <text>

param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Text
)

$ErrorActionPreference = 'Continue'
$data = [System.IO.File]::ReadAllBytes($Path)
function U32([int]$o) { return [BitConverter]::ToUInt32($data, $o) }
function Uleb([int]$o) {
    $result = 0; $shift = 0; $bytes = 0
    while ($true) {
        $b = $data[$o + $bytes]
        $result = $result -bor (($b -band 0x7F) -shl $shift)
        $bytes++
        if (($b -band 0x80) -eq 0) { break }
        $shift += 7
    }
    return @($result, $bytes)
}

$count = [int](U32 0x38); $off = [int](U32 0x3C)
$needle = [System.Text.Encoding]::UTF8.GetBytes($Text)

$target = -1; $targetLen = 0; $targetPrefix = 0
$entries = @()
for ($i = 0; $i -lt $count; $i++) {
    $start = [int](U32 ($off + 4 * $i))
    $r = Uleb $start
    $len = [int]$r[0]; $prefix = [int]$r[1]
    $textStart = $start + $prefix
    # Compare on the declared length only (ASCII targets).
    if ($len -eq $needle.Length) {
        $same = $true
        for ($k = 0; $k -lt $needle.Length; $k++) { if ($data[$textStart + $k] -ne $needle[$k]) { $same = $false; break } }
        if ($same -and $target -lt 0) { $target = $start; $targetLen = $len; $targetPrefix = $prefix }
    }
    $entries += [pscustomobject]@{ Index = $i; Start = $start; TextStart = $textStart; Len = $len; Prefix = $prefix }
}
if ($target -lt 0) { Write-Host "'$Text' not found"; exit 0 }

$end = $target + $targetPrefix + $targetLen + 1
Write-Host ("item for '{0}': start=0x{1:X} prefix={2} len={3} extent=[0x{1:X},0x{4:X})" -f $Text, $target, $targetPrefix, $targetLen, $end)
Write-Host ("bytes: {0}" -f (($data[$target..($end-1)] | ForEach-Object { '{0:X2}' -f $_ }) -join ' '))

$inside = $entries | Where-Object { $_.Start -ge $target -and $_.Start -lt $end }
Write-Host ("--- string_ids whose item starts inside that extent: {0} ---" -f @($inside).Count)
$inside | ForEach-Object {
    $ts = $_.TextStart
    $txt = if ($_.Len -gt 0) { [System.Text.Encoding]::UTF8.GetString($data, $ts, [Math]::Min($_.Len, 40)) } else { '' }
    Write-Host ("  #{0} start=0x{1:X} (item+0x{2:X}) declaredLen={3} text='{4}'" -f $_.Index, $_.Start, ($_.Start - $target), $_.Len, $txt)
}
Write-Host "--- all string_ids within +/- 4 bytes of the extent ---"
$entries | Where-Object { $_.Start -ge ($target - 4) -and $_.Start -le ($end + 4) } |
    Sort-Object Start | ForEach-Object {
        Write-Host ("  #{0} start=0x{1:X} offsetFromItem={2} len={3}" -f $_.Index, $_.Start, ($_.Start - $target), $_.Len)
    }
