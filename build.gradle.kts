// Top-level build configuration.
//
// Versions are pinned here and mirrored in versions.lock.json so a built APK
// can always be traced back to a specific toolchain.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
