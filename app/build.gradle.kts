import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Real game files used by the patcher unit tests: global-metadata.dat and
// libil2cpp.so extracted from the 3.7.1 APK (see
// tools/dump-il2cpp-preimage.ps1 / README). Tests that need them skip
// themselves when the directory is absent, so the build works without a copy of
// the game.
val corpusDir: String = System.getenv("LUNAR_CORPUS")
    ?: rootProject.file("tools/.cache/corpus").absolutePath

// The pristine 3.7.1 client used by the end-to-end patcher test. Not in the
// repository (263 MB of game data); the test skips itself if it is missing.
val GAME_APK: String = System.getenv("LUNAR_APK")
    ?: "F:\\bak\\lunar-tear-assets\\com.square_enix.android_googleplay.nierspww_3.7.1-152_minAPI24(arm64-v8a)(nodpi)_apkmirror.com.apk"

// The set of ABIs the APK ships is derived from what tools/build-native.ps1 has
// actually produced, so a missing native set fails the build instead of
// producing an APK whose servers cannot start.
// Note: only arm64-v8a is buildable with CGO_ENABLED=0; Go's android/amd64
// target requires cgo/external linking (NDK), so emulator ABIs are opt-in.
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val builtAbis = listOf("arm64-v8a", "x86_64", "armeabi-v7a")
    .filter { jniLibsDir.dir(it).asFile.isDirectory }
if (builtAbis.isEmpty()) {
    throw GradleException(
        "No native binaries found under app/src/main/jniLibs - run: " +
            "powershell -ExecutionPolicy Bypass -File tools\\build-native.ps1",
    )
}

// The real signing key for anything a user will install. Machine-local and not
// in the repository (see .gitignore), so a fresh clone has none - which is
// deliberate. See taskGraph.whenReady at the bottom for why a release refuses
// to build without it.
//
// Create one with: powershell -ExecutionPolicy Bypass -File tools\gen-release-keystore.ps1
val releaseKeystoreFile = rootProject.file("keystore.properties")
val releaseKeystore = Properties().apply {
    if (releaseKeystoreFile.exists()) releaseKeystoreFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = releaseKeystore.getProperty("storeFile") != null

// Escape hatch for smoke-testing release packaging on a machine with no key.
// Explicit so a debug-signed release can never happen by accident.
//
// No dot in the property name on purpose: PowerShell breaks an unquoted
// -Plt.allowDebugSignedRelease=true into "-Plt" and ".allowDebugSignedRelease=true"
// at the dot, and Gradle then reports the second half as an unknown task.
val allowDebugSignedRelease = findProperty("allowDebugSignedRelease") == "true"

// The debug key AGP generates lives wherever ANDROID_USER_HOME points. Every
// script in tools/ points that at tools/.cache/android-user, but Android Studio
// launched from the Start menu has no such variable and would sign with
// ~/.android/debug.keystore instead. Those are two different keys, so an APK
// built in the IDE cannot be installed over one built from the command line --
// and the only way forward from there is to uninstall, which deletes the host
// app's files/db/game.db, i.e. every account, quest and pull.
//
// Pinning the keystore takes the environment out of that decision. Guarded, so
// a clone whose tools/.cache has not been bootstrapped yet still falls back to
// AGP's own default rather than failing on a missing file.
val pinnedDebugKeystore = rootProject.file("tools/.cache/android-user/debug.keystore")

android {
    namespace = "dev.lunartear.host"
    compileSdk = 35

    // Only created when a real key is configured; the release build type below
    // picks it up then, and the guard at the bottom of this file refuses to
    // package a release otherwise. PKCS#12 keystores usually use one password
    // for both, so keyPassword falls back to storePassword.
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
                    ?: releaseKeystore.getProperty("storePassword")
            }
        }
        if (pinnedDebugKeystore.exists()) {
            getByName("debug") {
                storeFile = pinnedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "dev.lunartear.host"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Only the ABIs we actually cross-build natives for. The client APK is
        // arm64-v8a only, so arm64 is the shipping target.
        ndk {
            abiFilters += builtAbis
        }
    }

    // The Go server binaries are ordinary ELF executables named lib*.so so that
    // Android extracts them into nativeLibraryDir, the only path an app may exec
    // from on Android 10+ (app data dirs are mounted noexec).
    packaging {
        jniLibs {
            // Extract natives to disk instead of loading them from the APK.
            useLegacyPackaging = true
            // Without this AGP strips the Go PIE executables with the NDK strip
            // tool, which corrupts them.
            keepDebugSymbols += listOf("**/*.so")
        }
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // The real key once keystore.properties is present. Falling back to
            // the debug key here only keeps local packaging smoke-testable; the
            // guard at the bottom of this file refuses to actually package a
            // release that way unless it is explicitly opted into.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            it.systemProperty("lt.corpus", corpusDir)
            it.systemProperty("lt.axmlprobe", rootProject.file("tools/.cache/axmlprobe/probe.apk").absolutePath)
            it.systemProperty("lt.apk", System.getenv("LUNAR_APK") ?: GAME_APK)
            it.systemProperty("lt.reporoot", rootProject.projectDir.absolutePath)
            it.systemProperty("lt.buildtools", rootProject.file("tools/.cache/sdk/build-tools/35.0.0").absolutePath)
            it.testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = false
            }
        }
    }

    lint {
        // The app is a sideloaded research tool; Play-only lint rules are noise.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // APK signing (v1+v2+v3) — pure Java, the same library apksigner uses.
    implementation("com.android.tools.build:apksig:8.7.3")

    // DEX rewriting: the client's Java Facebook SDK keeps its URLs in the dex
    // string pool. Editing that pool by hand means re-packing the string-data
    // section, remapping shared items, and keeping string_ids sorted - all of
    // which ART verifies. dexlib2 is the library apktool/smali use for exactly
    // this, so the rewrite goes through it instead (see DexPatcher).
    implementation("com.android.tools.smali:smali-dexlib2:3.0.8")

    // Four of the reference patcher's five Facebook edits are *structural*: new
    // method bodies, a flipped enum constant, and an added shouldOverrideUrlLoading
    // overload. The reference does them as text edits on apktool-decoded smali. So
    // does this app: baksmali disassembles only the com/facebook/** classes,
    // DexPatcher applies the reference's needles and replacements verbatim, and
    // smali reassembles them. Hand-translating those bodies into dexlib2's builder
    // API would mean reproducing register allocation, branch targets and try blocks
    // by hand - exactly where a port silently diverges from the reference.
    implementation("com.android.tools.smali:smali:3.0.8")
    implementation("com.android.tools.smali:smali-baksmali:3.0.8")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}

// Fail fast with clear messages: a cryptic AAPT error when the SDK bootstrap has
// not run yet, and - far more importantly - a quietly debug-signed APK that no
// existing user could install as an update.
gradle.taskGraph.whenReady {
    val sdkDir = runCatching {
        val p = Properties()
        rootProject.file("local.properties").inputStream().use { p.load(it) }
        p.getProperty("sdk.dir")
    }.getOrNull()
    if (sdkDir.isNullOrBlank()) {
        throw GradleException("local.properties is missing sdk.dir - run: powershell -ExecutionPolicy Bypass -File tools\\bootstrap-sdk.ps1")
    }

    val packagingRelease = allTasks.any {
        it.name == "packageRelease" || it.name == "packageReleaseBundle"
    }
    if (packagingRelease && !hasReleaseKeystore && !allowDebugSignedRelease) {
        throw GradleException(
            """
            Refusing to package a release signed with the throwaway debug key.

            Android identifies an app by its signing key, so an APK signed with a
            different one cannot be installed over the copy your users already
            have. Their only way forward would be to uninstall it first - and that
            deletes the host app's files/db/game.db, which holds every account,
            quest and pull. That is data loss, not an inconvenience, so shipping
            one has to be a deliberate choice rather than a default.

            Create the real key (once - then keep it, and back it up somewhere
            that is neither this repository nor tools/.cache):
              powershell -ExecutionPolicy Bypass -File tools\gen-release-keystore.ps1

            To only smoke-test release packaging on this machine, opt in:
              tools\gradle.ps1 assembleRelease -PallowDebugSignedRelease=true
            """.trimIndent(),
        )
    }
}
