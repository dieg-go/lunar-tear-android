<#
.SYNOPSIS
  Shared, workspace-local toolchain environment. Dot-source this from every
  build script:  . "$PSScriptRoot\_env.ps1"

.DESCRIPTION
  Everything the build needs is redirected into tools/.cache so that
  - no machine-wide state is touched (no global SDK, no global Go caches), and
  - every write happens inside the repository, which is the only path reliably
    writable by grandchild processes spawned through pipes (protoc plugins, go
    toolchain helpers). Verified failure mode if this is ignored:
    "gen/proto/x.pb.go: while trying to create directory ...: Permission denied".
#>

$ErrorActionPreference = 'Continue'

$script:LunarTearRepoRoot = Split-Path -Parent $PSScriptRoot
$script:LunarTearCache    = Join-Path $PSScriptRoot '.cache'

# ---------------------------------------------------------------- source repos
if (-not $env:LUNAR_TEAR_SRC)    { $env:LUNAR_TEAR_SRC    = 'C:\Users\diego\lunar-tear' }
if (-not $env:LUNAR_SCRIPTS_SRC) { $env:LUNAR_SCRIPTS_SRC = 'C:\Users\diego\lunar-scripts' }

# ---------------------------------------------------------------- Go
$env:GOPATH     = Join-Path $LunarTearCache 'go\path'
$env:GOCACHE    = Join-Path $LunarTearCache 'go\build'
$env:GOMODCACHE = Join-Path $LunarTearCache 'go\mod'
$env:GOBIN      = Join-Path $LunarTearCache 'bin'
$env:GOTMPDIR   = Join-Path $LunarTearCache 'go\tmp'
$env:GOTELEMETRY = 'off'

# ---------------------------------------------------------------- Java
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    foreach ($c in @(
            'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
            'C:\Program Files\Eclipse Adoptium\jdk-21*',
            'C:\Program Files\Java\jdk-21*',
            'C:\Program Files\Android\Android Studio\jbr')) {
        $hit = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit -and (Test-Path (Join-Path $hit.FullName 'bin\java.exe'))) { $env:JAVA_HOME = $hit.FullName; break }
    }
}

# ---------------------------------------------------------------- Android SDK
$script:LunarTearSdkRoot = Join-Path $LunarTearCache 'sdk'
$env:ANDROID_HOME     = $script:LunarTearSdkRoot
$env:ANDROID_SDK_ROOT = $script:LunarTearSdkRoot
$env:ANDROID_USER_HOME = Join-Path $LunarTearCache 'android-user'

# ---------------------------------------------------------------- Gradle
# Keep Gradle's own cache (distributions, AGP, Compose artifacts - several GB)
# inside the repository instead of ~/.gradle. tools/gradle.ps1 sets this for
# every build it runs; a bare .\gradlew.bat uses the standard ~/.gradle.
if (-not $env:LUNAR_GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $LunarTearCache 'gradle-home'
}

# ---------------------------------------------------------------- dirs + PATH
foreach ($d in @($env:GOPATH, $env:GOCACHE, $env:GOMODCACHE, $env:GOBIN, $env:GOTMPDIR,
                 $LunarTearSdkRoot, (Join-Path $LunarTearCache 'protoc\bin'),
                 (Join-Path $LunarTearCache 'go\path\bin'), (Join-Path $LunarTearCache 'logs'))) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
}

$pathParts = @(
    $env:GOBIN,
    (Join-Path $LunarTearCache 'protoc\bin'),
    (Join-Path $LunarTearSdkRoot 'platform-tools'),
    (Join-Path $LunarTearSdkRoot 'build-tools\35.0.0')
) | Where-Object { $_ -and (Test-Path $_) }
foreach ($p in $pathParts) {
    if ($env:PATH -notlike "*$p*") { $env:PATH = "$p;$env:PATH" }
}

$script:LunarTearProtoc = Join-Path $LunarTearCache 'protoc\bin\protoc.exe'
$script:LunarTearSdkManager = Join-Path $LunarTearSdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
