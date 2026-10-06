<#
.SYNOPSIS
  Cross-compiles every native binary that ships inside the APK.

.DESCRIPTION
  Builds, per ABI, into app/src/main/jniLibs/<abi>/ using the same recipe as the
  upstream release workflow (GOOS/GOARCH + CGO_ENABLED=0 -trimpath -ldflags="-s -w"):

    upstream lunar-tear binaries   -> liblt-server.so, liblt-cdn.so, liblt-auth.so
    this repo's helper tools       -> liblt-migrate.so, liblt-patch-masterdata.so,
                                      liblt-patch-listbin.so

  Output names must match lib*.so because Android only extracts files with that
  name into nativeLibraryDir, which is the one place an app may exec from on
  Android 10+ (app data dirs are mounted noexec).

  Also:
    - copies $LUNAR_TEAR_SRC/server/migrations into the lt-migrate embed dir
    - verifies versions.lock.json still matches the upstream checkout
    - writes app/src/main/java/.../NativeManifest.kt with per-ABI size + SHA-256
      so the app can prove at runtime which binaries it is running

  Run it with:  powershell -ExecutionPolicy Bypass -File tools\build-native.ps1
#>
[CmdletBinding()]
param(
    [string[]]$Abi = @('arm64-v8a'),
    [switch]$SkipUpstream,
    [switch]$SkipTools,
    [switch]$StrictLock,
    # Try ABIs that the Go toolchain cannot build without cgo/NDK, instead of
    # skipping them with an explanation.
    [switch]$TryUnsupportedAbis
)

. "$PSScriptRoot\_env.ps1"

$RepoRoot   = $script:LunarTearRepoRoot
$Upstream   = Join-Path $env:LUNAR_TEAR_SRC 'server'
$NativeDir  = Join-Path $RepoRoot 'native'
$JniLibs    = Join-Path $RepoRoot 'app\src\main\jniLibs'
$ManifestKt = Join-Path $RepoRoot 'app\src\main\java\dev\lunartear\host\core\NativeManifest.kt'

function Write-Step($msg) { Write-Host "[native] $msg" }
function Fail($msg) { Write-Host "[native] ERROR: $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- ABI map
$abiMap = @{
    'arm64-v8a'   = @{ GoArch = 'arm64'; Android = 'arm64-v8a' }
    'x86_64'      = @{ GoArch = 'amd64'; Android = 'x86_64' }
    'armeabi-v7a' = @{ GoArch = 'arm';   Android = 'armeabi-v7a' }
}

# The Go linker only supports android/arm64 with internal linking. For
# android/amd64 (and 386) it insists on external (cgo) linking, which needs the
# NDK cross toolchain; with CGO_ENABLED=0 the build fails with
#   "android/amd64 requires external (cgo) linking, but cgo is not enabled"
# Emulator builds would need `-Abi x86_64 -TryUnsupportedAbis` plus a configured
# NDK. The client APK is arm64-v8a only, so arm64 is the shipping target.
$unsupportedByGo = @{ 'x86_64' = 'amd64 requires cgo/external linking (NDK)'; 'armeabi-v7a' = 'arm requires cgo/external linking (NDK)' }

$effectiveAbi = @()
foreach ($abiChk in $Abi) {
    if (-not $abiMap.ContainsKey($abiChk)) { Fail "unsupported ABI '$abiChk' (known: $($abiMap.Keys -join ', '))" }
    if ($unsupportedByGo.ContainsKey($abiChk) -and -not $TryUnsupportedAbis) {
        Write-Step "skip ${abiChk}: $($unsupportedByGo[$abiChk]) - pass -TryUnsupportedAbis to attempt anyway"
        continue
    }
    $effectiveAbi += $abiChk
}
if ($effectiveAbi.Count -eq 0) { Fail 'no buildable ABIs requested' }
$Abi = $effectiveAbi
Write-Step "ABIs: $($Abi -join ', ')"

# ---------------------------------------------------------------- lock check
$lockPath = Join-Path $RepoRoot 'versions.lock.json'
if (-not (Test-Path $lockPath)) {
    Write-Step 'versions.lock.json missing; run tools/update-lock.ps1 (lock check skipped)'
    $lock = $null
} else {
    $lock = Get-Content -Raw $lockPath | ConvertFrom-Json
    Push-Location $env:LUNAR_TEAR_SRC
    try { $head = (& git rev-parse HEAD).Trim() } finally { Pop-Location }
    if ($head -ne $lock.lunarTear.commit) {
        $msg = "upstream moved: lock=$($lock.lunarTear.shortCommit) checkout=$($head.Substring(0,7))"
        if ($StrictLock) { Fail $msg } else { Write-Step "WARNING: $msg" }
    } else {
        Write-Step "upstream pinned at $($lock.lunarTear.shortCommit)"
    }
}

# ---------------------------------------------------------------- proto stubs
$genProto = Join-Path $Upstream 'gen\proto'
if (-not (Test-Path $genProto)) { Fail "proto stubs missing at $genProto - run tools\gen-proto.ps1 first" }

# ---------------------------------------------------------------- embedded migrations
$embedDir  = Join-Path $NativeDir 'cmd\lt-migrate\migrations'
$srcMigr   = Join-Path $Upstream 'migrations'
$sql = @(Get-ChildItem $srcMigr -Filter '*.sql' -ErrorAction SilentlyContinue)
if ($sql.Count -eq 0) { Fail "no migrations found in $srcMigr" }
New-Item -ItemType Directory -Force -Path $embedDir | Out-Null
Get-ChildItem $embedDir -Filter '*.sql' | Remove-Item -Force
Copy-Item (Join-Path $srcMigr '*.sql') $embedDir -Force
Write-Step "embedded $($sql.Count) migrations from $srcMigr"

# ---------------------------------------------------------------- targets
$allTargets = @(
    [pscustomobject]@{ Module = 'upstream'; Dir = $Upstream;  Pkg = './cmd/lunar-tear'; Name = 'liblt-server.so';           Kind = 'upstream' }
    [pscustomobject]@{ Module = 'upstream'; Dir = $Upstream;  Pkg = './cmd/octo-cdn';   Name = 'liblt-cdn.so';              Kind = 'upstream' }
    # The client's Unity/Java Facebook SDK is redirected to this fake OAuth
    # endpoint (see PatchRecipe + DexPatcher); the game server also validates
    # Facebook tokens against it (--auth-url).
    [pscustomobject]@{ Module = 'upstream'; Dir = $Upstream;  Pkg = './cmd/auth-server'; Name = 'liblt-auth.so';            Kind = 'upstream' }
    [pscustomobject]@{ Module = 'native';   Dir = $NativeDir; Pkg = './cmd/lt-migrate'; Name = 'liblt-migrate.so';          Kind = 'tool' }
    [pscustomobject]@{ Module = 'native';   Dir = $NativeDir; Pkg = './cmd/patch-masterdata'; Name = 'liblt-patch-masterdata.so'; Kind = 'tool' }
    [pscustomobject]@{ Module = 'native';   Dir = $NativeDir; Pkg = './cmd/patch-listbin';    Name = 'liblt-patch-listbin.so';    Kind = 'tool' }
)

$targets = @()
foreach ($t in $allTargets) {
    if ($t.Kind -eq 'upstream' -and $SkipUpstream) { continue }
    if ($t.Kind -eq 'tool' -and $SkipTools) { continue }
    $pkgDir = Join-Path $t.Dir ($t.Pkg -replace '^\./', '' -replace '/', '\')
    if (-not (Test-Path $pkgDir)) {
        Write-Step "skip $($t.Name): $($t.Pkg) not present yet"
        continue
    }
    $targets += $t
}
if ($targets.Count -eq 0) { Fail 'nothing to build' }

# ---------------------------------------------------------------- build
# NOTE: loop variables deliberately do NOT reuse the parameter names. In
# PowerShell, `foreach ($abi in $Abi)` assigns to the same variable it is
# iterating (names are case-insensitive), which silently replaces the array.
$env:CGO_ENABLED = '0'
$records = @()
foreach ($abiName in $Abi) {
    $goArch = $abiMap[$abiName].GoArch
    $outDir = Join-Path $JniLibs $abiName
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Write-Step "=== $abiName (GOARCH=$goArch) ==="

    foreach ($t in $targets) {
        if (-not $t.Pkg) { Fail "target $($t.Name) has no package" }
        $outFile = Join-Path $outDir $t.Name
        $env:GOOS = 'android'
        $env:GOARCH = $goArch
        Push-Location $t.Dir
        try {
            $sw = [Diagnostics.Stopwatch]::StartNew()
            Write-Step "  go build -o $($t.Name) $($t.Pkg)  (cwd=$($t.Dir))"
            & go build -trimpath -ldflags='-s -w' -o $outFile $t.Pkg
            $code = $LASTEXITCODE
            $sw.Stop()
        } finally { Pop-Location }
        if ($code -ne 0) { Fail "build failed: $($t.Pkg) for $abiName (exit $code)" }
        if (-not (Test-Path $outFile)) { Fail "expected output missing: $outFile" }

        $fi = Get-Item $outFile
        $sha = (Get-FileHash -Algorithm SHA256 -Path $outFile).Hash.ToLower()
        $records += [pscustomobject]@{
            Abi = $abiName; Name = $t.Name; Package = $t.Pkg
            Bytes = $fi.Length; Sha256 = $sha; Seconds = [int]$sw.Elapsed.TotalSeconds
        }
        Write-Step ("  {0,-30} {1,10:N0} bytes  {2}s" -f $t.Name, $fi.Length, [int]$sw.Elapsed.TotalSeconds)
    }
}
$env:GOOS = $null; $env:GOARCH = $null; $env:CGO_ENABLED = $null

# ---------------------------------------------------------------- ELF sanity check
function Read-ElfInfo([string]$Path) {
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        $b = New-Object byte[] 20
        [void]$fs.Read($b, 0, 20)
    } finally { $fs.Dispose() }
    if ($b[0] -ne 0x7F -or $b[1] -ne 0x45 -or $b[2] -ne 0x4C -or $b[3] -ne 0x46) { return $null }
    $le = $b[5] -eq 1
    $u16 = { param($o) if ($le) { [BitConverter]::ToUInt16($b, $o) } else { [BitConverter]::ToUInt16(@($b[$o+1], $b[$o]), 0) } }
    $u32 = { param($o) if ($le) { [BitConverter]::ToUInt32($b, $o) } else { [BitConverter]::ToUInt32(@($b[$o+3], $b[$o+2], $b[$o+1], $b[$o]), 0) } }
    [pscustomobject]@{
        Class64 = $b[4] -eq 2
        Machine = & $u16 18
        EType   = & $u16 16
    }
}
$bad = @()
foreach ($r in $records) {
    $p = Join-Path (Join-Path $JniLibs $r.Abi) $r.Name
    $info = Read-ElfInfo $p
    if (-not $info) { $bad += "$($r.Name) is not an ELF file"; continue }
    if (-not $info.Class64) { $bad += "$($r.Name) is not 64-bit" }
    if ($info.Machine -ne 183) { $bad += "$($r.Name) machine=$($info.Machine), expected 183 (AArch64)" }
    if ($info.EType -ne 3) { $bad += "$($r.Name) e_type=$($info.EType), expected 3 (ET_DYN / PIE)" }
}
if ($bad.Count -gt 0) { $bad | ForEach-Object { Write-Host "[native] ELF CHECK: $_" -ForegroundColor Red }; Fail 'ELF sanity check failed' }
Write-Step "ELF check passed for $($records.Count) binaries (AArch64, 64-bit, ET_DYN PIE)"

# ---------------------------------------------------------------- NativeManifest.kt
$entries = foreach ($r in $records) {
    "        Binary(`"$($r.Abi)`", `"$($r.Name)`", `"$($r.Package)`", $($r.Bytes)L, `"$($r.Sha256)`"),"
}
$kt = @"
package dev.lunartear.host.core

// GENERATED by tools/build-native.ps1 - do not edit by hand.
//
// Provenance for every native binary packaged into this APK: the upstream
// lunar-tear revision it was built from and its SHA-256, so the app can verify
// at runtime that the file it is about to exec is the file that was built.
object NativeManifest {
    data class Binary(
        val abi: String,
        val fileName: String,
        val goPackage: String,
        val bytes: Long,
        val sha256: String,
    )

    const val UPSTREAM_COMMIT: String = "$(if ($lock) { $lock.lunarTear.commit } else { "unknown" })"
    const val UPSTREAM_SHORT: String = "$(if ($lock) { $lock.lunarTear.shortCommit } else { "unknown" })"
    const val GENERATED_AT: String = "$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')"

    val binaries: List<Binary> = listOf(
$($entries -join "`n")
    )

    fun forAbi(abi: String): List<Binary> = binaries.filter { it.abi == abi }

    /** The binary backing a role, e.g. "liblt-server.so". */
    fun byFileName(fileName: String): Binary? = binaries.firstOrNull { it.fileName == fileName }
}
"@
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ManifestKt) | Out-Null
Set-Content -Path $ManifestKt -Value $kt -Encoding utf8
Write-Step "wrote $ManifestKt"

# ---------------------------------------------------------------- summary
$records | Sort-Object Abi, Name | Format-Table -AutoSize Abi, Name, @{ n = 'MB'; e = { [math]::Round($_.Bytes / 1MB, 1) } }, @{ n = 'sha256'; e = { $_.Sha256.Substring(0, 12) } } | Out-String | Write-Host
Write-Step "done: $($records.Count) binaries -> $JniLibs"
