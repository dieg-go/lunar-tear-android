package dev.lunartear.host.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.lunartear.host.core.LunarHost
import java.io.File

private val LunarColors = darkColorScheme(
    primary = Color(0xFF9BD1FF),
    onPrimary = Color(0xFF00344F),
    secondary = Color(0xFFC9C6A8),
    background = Color(0xFF101418),
    surface = Color(0xFF171C21),
    surfaceVariant = Color(0xFF212830),
    error = Color(0xFFFFB4AB),
    onBackground = Color(0xFFE2E6EA),
    onSurface = Color(0xFFE2E6EA),
)

private enum class Screen(val title: String) {
    SERVER("Server"),
    PATCH("Patch"),
    BUILD("Build"),
    LOGS("Logs"),
}

@Composable
fun LunarTearApp() {
    MaterialTheme(colorScheme = LunarColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Android 15 draws targetSdk-35 apps edge to edge whether they ask for it or
            // not, and this window never consumed the insets. The tab row sat inside the
            // status bar's rectangle, where the status bar window - not the app - gets the
            // touches, so on the phone the tabs could not be tapped at all.
            // safeDrawingPadding() keeps every screen clear of the bars and the cutout.
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                var screen by remember { mutableStateOf(Screen.SERVER) }
                var pickingFolder by remember { mutableStateOf(false) }
                var pickingApk by remember { mutableStateOf(false) }

                if (pickingFolder) {
                    FolderPickerScreen(
                        startIn = LunarHost.config.value.assetRoot?.let(::File),
                        onCancel = { pickingFolder = false },
                        onPicked = { folder ->
                            LunarHost.updateConfig { it.copy(assetRoot = folder.absolutePath) }
                            LunarHost.log.append("ui", "asset folder set to ${folder.absolutePath}")
                            pickingFolder = false
                        },
                    )
                    return@Box
                }

                if (pickingApk) {
                    FolderPickerScreen(
                        startIn = File("/sdcard/Download"),
                        title = "Choose the game APK",
                        fileExtensions = listOf(".apk", ".apks", ".xapk"),
                        onCancel = { pickingApk = false },
                        onPicked = { pickingApk = false },
                        onPickedFile = { file ->
                            LunarHost.updateConfig { it.copy(sourceApk = file.absolutePath) }
                            LunarHost.log.append("ui", "source APK set to ${file.absolutePath}")
                            pickingApk = false
                        },
                    )
                    return@Box
                }

                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = screen.ordinal) {
                        Screen.entries.forEach { entry ->
                            Tab(
                                selected = screen == entry,
                                onClick = { screen = entry },
                                text = { Text(entry.title) },
                            )
                        }
                    }
                    when (screen) {
                        Screen.SERVER -> ServerScreen(onPickFolder = { pickingFolder = true })
                        Screen.PATCH -> PatchScreen(onPickApk = { pickingApk = true })
                        Screen.BUILD -> DiagnosticsScreen()
                        Screen.LOGS -> LogsScreen()
                    }
                }
            }
        }
    }
}
