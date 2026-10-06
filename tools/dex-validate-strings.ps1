# Validate every DEX string item: data[textStart + declaredLength] must be NUL.
#
# ART and dexdump both reject the in-place-patched DEX with
# "String longer than indicated size 0", i.e. some string_id's item declares
# length 0 while its content continues. This walks the whole string_ids table and
# reports every item that violates the terminator rule, before and after patching.
#
#   powershell -ExecutionPolicy Bypass -File tools\dex-validate-strings.ps1 <file.dex>

param([Parameter(Mandatory = $true)][string]$Path)

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
Write-Host ("file: {0} ({1:N0} bytes), string_ids={2}" -f $Path, $data.Length, $count)

$bad = 0; $zeroLen = 0
for ($i = 0; $i -lt $count; $i++) {
    $start = [int](U32 ($off + 4 * $i))
    $r = Uleb $start
    $len = [int]$r[0]; $prefix = [int]$r[1]
    $textStart = $start + $prefix
    if ($len -eq 0) { $zeroLen++ }
    if ($textStart + $len -ge $data.Length) { Write-Host ("  #{0} runs past EOF" -f $i); $bad++; continue }
    if ($data[$textStart + $len] -ne 0) {
        $bad++
        if ($bad -le 12) {
            $sb = New-Object System.Text.StringBuilder
            for ($k = 0; $k -lt [Math]::Min($len + 8, 48); $k++) {
                [void]$sb.Append(('{0:X2} ' -f $data[$textStart + $k]))
            }
            $txt = [System.Text.Encoding]::UTF8.GetString($data, $textStart, [Math]::Min($len, 40))
            Write-Host ("  #{0} @0x{1:X} declaredLen={2} text='{3}' bytesAfter={4}" -f $i, $start, $len, $txt, $sb.ToString())
        }
    }
}
Write-Host ("--- items violating the NUL terminator rule: {0} (zero-length items present: {1}) ---" -f $bad, $zeroLen)
