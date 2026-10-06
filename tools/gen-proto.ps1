<#
.SYNOPSIS
  Generates the Go protobuf/gRPC stubs the lunar-tear server needs.

.DESCRIPTION
  server/gen/ is gitignored and is not published, so it must be generated before
  the server can be compiled. `make` is not assumed to exist on Windows, so this
  script runs protoc directly with the same PROTO_USED list and the same flags as
  server/Makefile:14-15 — the list is parsed from the Makefile so it stays in
  sync with upstream.

  Two-step generation on purpose: protoc writes into a staging directory inside
  this repository, and PowerShell then copies the result into the upstream
  checkout. protoc's code-generator plugins are spawned with piped stdio and are
  denied directory creation outside the repository, so a direct --go_out into
  $LUNAR_TEAR_SRC fails with:
      gen/proto/x.pb.go: while trying to create directory ...: Permission denied

  Only writes into $LunarTearSrc/server/gen (untracked, gitignored). No tracked
  file in the upstream checkout is modified.
#>
[CmdletBinding()]
param(
    [string]$ProtocVersion = $env:PROTOC_VERSION,
    [switch]$Force
)

. "$PSScriptRoot\_env.ps1"

$ServerDir  = Join-Path $env:LUNAR_TEAR_SRC 'server'
$Cache      = $script:LunarTearCache
$ProtocRoot = Join-Path $Cache 'protoc'
$ProtocExe  = Join-Path $ProtocRoot 'bin\protoc.exe'
$StageDir   = Join-Path $Cache 'protogen'

function Write-Step($msg) { Write-Host "[proto] $msg" }
function Fail($msg) { Write-Host "[proto] ERROR: $msg" -ForegroundColor Red; exit 1 }

if (-not (Test-Path (Join-Path $ServerDir 'Makefile'))) { Fail "lunar-tear server not found at $ServerDir (set LUNAR_TEAR_SRC)" }

# ---------------------------------------------------------------- protoc
if ($Force) { Remove-Item -Recurse -Force $ProtocRoot -ErrorAction SilentlyContinue }
if (-not (Test-Path $ProtocExe)) {
    if ($ProtocVersion) {
        $url = "https://github.com/protocolbuffers/protobuf/releases/download/v$ProtocVersion/protoc-$ProtocVersion-win64.zip"
    } else {
        Write-Step 'resolving latest protoc release'
        $rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/protocolbuffers/protobuf/releases/latest' -Headers @{ 'User-Agent' = 'lunar-tear-android-build' }
        $asset = $rel.assets | Where-Object { $_.name -match '^protoc-.*-win64\.zip$' } | Select-Object -First 1
        if (-not $asset) { Fail 'could not find a protoc win64 asset in the latest release' }
        $url = $asset.browser_download_url
        $ProtocVersion = $rel.tag_name.TrimStart('v')
    }
    $zip = Join-Path $Cache (Split-Path -Leaf $url)
    if (-not (Test-Path $zip)) {
        Write-Step "downloading $url"
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    }
    Write-Step "extracting protoc $ProtocVersion"
    Remove-Item -Recurse -Force $ProtocRoot -ErrorAction SilentlyContinue
    Expand-Archive -Path $zip -DestinationPath $ProtocRoot -Force
}
if (-not (Test-Path $ProtocExe)) { Fail "protoc not found at $ProtocExe" }
Write-Step "protoc: $(& $ProtocExe --version)"

# ---------------------------------------------------------------- plugins
$protoModVersion = (Select-String -Path (Join-Path $ServerDir 'go.mod') -Pattern 'google\.golang\.org/protobuf\s+v(\S+)' |
    Select-Object -First 1).Matches[0].Groups[1].Value
if (-not $protoModVersion) { Fail 'could not read google.golang.org/protobuf version from go.mod' }
Write-Step "pinning protoc-gen-go to v$protoModVersion (matches go.mod)"

Write-Step 'installing protoc-gen-go'
& go install "google.golang.org/protobuf/cmd/protoc-gen-go@v$protoModVersion"
if ($LASTEXITCODE -ne 0) { Fail 'go install protoc-gen-go failed' }
Write-Step 'installing protoc-gen-go-grpc'
& go install 'google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest'
if ($LASTEXITCODE -ne 0) { Fail 'go install protoc-gen-go-grpc failed' }

# ---------------------------------------------------------------- PROTO_USED (from the Makefile)
$raw = (Get-Content -Raw (Join-Path $ServerDir 'Makefile')) -replace "\\\r?\n", ' '
$m = [regex]::Match($raw, 'PROTO_USED\s*=\s*([^\r\n]+)')
if (-not $m.Success) { Fail 'PROTO_USED not found in server/Makefile' }
$protos = $m.Groups[1].Value -split '\s+' | Where-Object { $_ -like 'proto/*.proto' }
if ($protos.Count -lt 10) { Fail "suspiciously few protos parsed: $($protos.Count)" }
Write-Step "generating $($protos.Count) protos"

# ---------------------------------------------------------------- generate into staging
Remove-Item -Recurse -Force $StageDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $StageDir | Out-Null
Push-Location $ServerDir
try {
    & $ProtocExe -I . @protos --go_out=$StageDir --go_opt=module=lunar-tear/server --go-grpc_out=$StageDir --go-grpc_opt=module=lunar-tear/server
    $code = $LASTEXITCODE
} finally { Pop-Location }
if ($code -ne 0) { Fail "protoc failed with exit code $code" }

$staged = @(Get-ChildItem (Join-Path $StageDir 'gen\proto') -Filter '*.pb.go' -ErrorAction SilentlyContinue)
if ($staged.Count -lt $protos.Count) { Fail "staging produced $($staged.Count) files, expected >= $($protos.Count)" }
Write-Step "staged $($staged.Count) generated files"

# ---------------------------------------------------------------- publish into the upstream tree
$genDir = Join-Path $ServerDir 'gen'
Remove-Item -Recurse -Force $genDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $genDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $StageDir 'gen\*') $genDir

$pbgo = @(Get-ChildItem $genDir -Recurse -Filter '*.pb.go')
$grpc = @($pbgo | Where-Object { $_.Name -like '*_grpc.pb.go' })
if ($pbgo.Count -lt $protos.Count) { Fail "expected >= $($protos.Count) generated files in $genDir, found $($pbgo.Count)" }
Write-Step "generated $($pbgo.Count) .pb.go files ($($grpc.Count) with gRPC stubs) in $genDir"
Write-Step 'done'
