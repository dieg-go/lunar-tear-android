package dev.lunartear.host.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.lunartear.host.core.LunarHost

@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val snapshot by LunarHost.log.snapshot.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(snapshot.version) {
        if (snapshot.lines.isNotEmpty()) {
            listState.animateScrollToItem(snapshot.lines.lastIndex)
        }
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { LunarHost.log.clear() }) { Text("Clear view") }
            Button(onClick = { shareLogs(context) }) { Text("Share log file") }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(snapshot.lines) { line ->
                    Text(
                        "${line.source.padEnd(10)} ${line.text}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private fun shareLogs(context: android.content.Context) {
    val dir = LunarHost.logsDir
    val newest = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", newest)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, newest.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share log"))
}

