# One-off diagnostic: are any DEX string items shared/overlapping?
#
# DexPatcher rewrites a string item in place and zero-pads the tail inside the
# item. That is only safe if no other string_id points into that item. ART's
# verifier rejected the patched file with "String longer than indicated size 0",
# which is what a string_id landing on one of those padding zeros would look
# like - so this checks the original file for exactly that situation.
#
#   powershell -ExecutionPolicy Bypass -File tools\dex-strings-overlap.ps1 <file.dex>

param([Parameter(Mandatory = $true)][string]$Path)

$ErrorActionPreference = 'Continue'
$data = [System.IO.File]::ReadAllBytes($Path)
Write-Host ("file: {0} ({1:N0} bytes)" -f $Path, $data.Length)

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

$count = [int](U32 0x38)
$off = [int](U32 0x3C)
Write-Host ("string_ids: {0} entries at 0x{1:X}" -f $count, $off)

$items = New-Object 'System.Collections.Generic.List[object]'
for ($i = 0; $i -lt $count; $i++) {
    $start = [int](U32 ($off + 4 * $i))
    $r = Uleb $start
    $len = [int]$r[0]; $prefix = [int]$r[1]
    $textStart = $start + $prefix
    $end = $textStart + $len + 1        # + NUL
    $text = [System.Text.Encoding]::UTF8.GetString($data, $textStart, [Math]::Min($len, 60))
    $items.Add([pscustomobject]@{ Index = $i; Start = $start; TextStart = $textStart; End = $end; Len = $len; Text = $text })
}

$sorted = $items | Sort-Object Start
Write-Host "--- first 5 items ---"
$sorted | Select-Object -First 5 | ForEach-Object { "  #{0} 0x{1:X}..0x{2:X} len={3} '{4}'" -f $_.Index, $_.Start, $_.End, $_.Len, $_.Text }

# Any start that falls strictly inside another item's extent?
$starts = @{}
foreach ($it in $sorted) { $starts[$it.Start] = $true }
$inside = @()
for ($k = 0; $k -lt $sorted.Count; $k++) {
    $a = $sorted[$k]
    for ($j = $k + 1; $j -lt $sorted.Count; $j++) {
        $b = $sorted[$j]
        if ($b.Start -ge $a.End) { break }
        if ($b.Start -gt $a.Start -and $b.Start -lt $a.End) { $inside += , @($a, $b) }
    }
}
Write-Host ("--- string_ids starting inside another item: {0} ---" -f $inside.Count)
$inside | Select-Object -First 12 | ForEach-Object {
    $a = $_[0]; $b = $_[1]
    "  #{0} '{1}' (0x{2:X}..0x{3:X}) contains start of #{4} '{5}' @0x{6:X}" -f $a.Index, $a.Text, $a.Start, $a.End, $b.Index, $b.Text, $b.Start
}
