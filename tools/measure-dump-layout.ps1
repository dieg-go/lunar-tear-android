# Breaks the asset dump down by directory so partial extraction can be planned.
# Reads the archive's own index (no extraction).

$ErrorActionPreference = 'Continue'
$seven = 'C:\Program Files\7-Zip\7z.exe'
$arch = 'F:\bak\lunar-tear-assets\resource_dump_android.7z'
$tmp = Join-Path $env:TEMP 'lt-paths-sizes.txt'

Write-Host 'indexing archive ...'
& $seven l -slt $arch | findstr /b /c:"Path = " /c:"Size = " | Set-Content -Path $tmp

$agg = @{}
$count = @{}
$cur = $null
foreach ($line in [System.IO.File]::ReadLines($tmp)) {
    if ($line.StartsWith('Path = ')) { $cur = $line.Substring(7); continue }
    if ($line.StartsWith('Size = ') -and $cur) {
        $sz = [int64]$line.Substring(7)
        $parts = $cur -split '\\'
        $k = (($parts | Select-Object -First 3) -join '/')
        if (-not $agg.ContainsKey($k)) { $agg[$k] = [int64]0; $count[$k] = 0 }
        $agg[$k] = $agg[$k] + $sz
        $count[$k] = $count[$k] + 1
    }
}

Write-Host ''
Write-Host '== bytes by first three path components =='
$total = 0L
foreach ($e in ($agg.GetEnumerator() | Sort-Object Value -Descending)) {
    $total += $e.Value
    Write-Host ("{0,15:N0}  {1,8} files  {2}" -f $e.Value, $count[$e.Key], $e.Key)
}
Write-Host ("{0,15:N0}  TOTAL ({1:N2} GiB)" -f $total, ($total / 1GB))
