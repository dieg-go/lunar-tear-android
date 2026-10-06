package dev.lunartear.host.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lunartear.host.core.AssetLayout
import java.io.File

/**
 * A raw filesystem browser.
 *
 * Android's Storage Access Framework hands out `content://` URIs, which the Go
 * server processes cannot open - they need absolute paths. With All-files access
 * granted, browsing raw paths is both possible and the only thing that works.
 */
@Composable
fun FolderPickerScreen(
    startIn: File?,
    onCancel: () -> Unit,
    onPicked: (File) -> Unit,
    /** When set, files with these extensions become selectable too (e.g. ".apk"). */
    fileExtensions: List<String> = emptyList(),
    onPickedFile: ((File) -> Unit)? = null,
    title: String = "Choose the folder that contains assets/",
) {
    val roots = remember {
        listOf("/storage/emulated/0", "/sdcard", "/storage")
            .map(::File)
            .filter { it.isDirectory && it.canRead() }
    }
    var current by remember {
        mutableStateOf(
            startIn?.takeIf { it.isDirectory && it.canRead() }
                ?: roots.firstOrNull()
                ?: File("/"),
        )
    }

    val files = remember(current, fileExtensions) {
        current.listFiles()
            ?.filter { it.isDirectory && it.canRead() && !it.name.startsWith('.') }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    val selectableFiles = remember(current, fileExtensions) {
        if (fileExtensions.isEmpty()) emptyList()
        else current.listFiles()
            ?.filter { it.isFile && fileExtensions.any { ext -> it.name.endsWith(ext, ignoreCase = true) } }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    val report = remember(current) { if (fileExtensions.isEmpty()) AssetLayout.validate(current) else null }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text(title, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(current.absolutePath, fontFamily = FontFamily.Monospace, fontSize = 11.sp)

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            OutlinedButton(
                enabled = current.parentFile != null,
                onClick = { current.parentFile?.let { current = it } },
            ) { Text("Up") }
            if (fileExtensions.isEmpty()) {
                Button(onClick = { onPicked(current) }) { Text("Use this folder") }
            }
        }

        if (report != null) {
            Spacer(Modifier.height(6.dp))
            val status = when {
                report.usable -> "usable: ${report.treeKind} tree, ${report.revisionCount} revisions"
                report.treeKind != null -> "partly there: ${report.treeKind} tree, ${report.revisionCount} revisions"
                else -> "no assets/ tree here yet"
            }
            Text(status, fontSize = 11.sp)
            report.hints.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary) }
        }

        if (fileExtensions.isNotEmpty() && onPickedFile != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (selectableFiles.isEmpty()) "No ${fileExtensions.joinToString("/")} files in this folder"
                else "Tap a file to select it",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(selectableFiles, key = { "f:" + it.absolutePath }) { file ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPickedFile?.invoke(file) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text("📄", fontSize = 14.sp)
                    Spacer(Modifier.padding(4.dp))
                    Text(file.name, fontSize = 13.sp)
                    Spacer(Modifier.padding(4.dp))
                    Text("${file.length() / 1024 / 1024} MB", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
            items(files, key = { "d:" + it.absolutePath }) { dir ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { current = dir }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text("📁", fontSize = 14.sp)
                    Spacer(Modifier.padding(4.dp))
                    Text(dir.name, fontSize = 13.sp)
                }
            }
        }
    }
}
