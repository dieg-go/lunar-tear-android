package dev.lunartear.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lunartear.host.core.BinaryCheck
import dev.lunartear.host.core.NativeProbe
import dev.lunartear.host.core.NativeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OkGreen = Color(0xFF7FD68A)

/**
 * Proves the bundled Go binaries survived packaging: present in
 * nativeLibraryDir, right size, matching SHA-256, executable, AArch64 PIE, and
 * actually runnable (each is started with `--help`, which Go's flag package
 * handles before any application code runs).
 */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<NativeReport?>(null) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val logScroll = rememberScrollState()

    suspend fun refresh() {
        busy = true
        report = withContext(Dispatchers.IO) { NativeProbe.probe(context) }
        busy = false
    }

    suspend fun runAll() {
        val current = report ?: return
        busy = true
        log = withContext(Dispatchers.IO) {
            buildString {
                append("running every bundled binary with --help\n\n")
                current.checks.forEach { check ->
                    if (!check.exists) {
                        append("${check.fileName}: SKIPPED - not extracted\n\n")
                    } else {
                        append(NativeProbe.smokeTest(context, check.fileName).summary())
                        append('\n')
                    }
                }
                append("done.")
            }
        }
        busy = false
    }

    LaunchedEffect(Unit) { refresh() }

    ScreenScroll {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { scope.launch { runAll() } }) {
                Text(if (busy) "working…" else "Run all binaries")
            }
            OutlinedButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Re-check files") }
        }

        val r = report
        if (r == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("checking bundled binaries…")
            }
            return@ScreenScroll
        }

        SectionCard("Build") {
            Line("device ABI", r.deviceAbi)
            Line("all ABIs", r.deviceAbis.joinToString(", "))
            Line("upstream", r.upstreamCommit)
            Line("built at", r.generatedAt)
            Line(
                "result",
                if (r.allPass) "all ${r.checks.size} binaries OK" else "${r.failing.size} of ${r.checks.size} FAILING",
            )
        }

        SectionCard("Bundled binaries") {
            r.checks.forEach { check ->
                BinaryRow(check)
                Spacer(Modifier.height(6.dp))
            }
        }

        if (log.isNotEmpty()) {
            SectionCard(null) {
                Text(
                    log,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(logScroll)
                        .padding(4.dp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BinaryRow(check: BinaryCheck) {
    val accent = if (check.pass) OkGreen else MaterialTheme.colorScheme.error
    androidx.compose.foundation.layout.Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (check.pass) "OK" else "FAIL", color = accent, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(check.fileName, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text("${check.sizeBytes / 1024 / 1024} MB", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
        }
        check.elf?.let { Line("elf", it.summary, 90) }
        check.problem?.let { Text(it, color = accent, fontSize = 11.sp) }
    }
}

