package com.gasplanet.grabber.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.gasplanet.grabber.DownloadService
import com.gasplanet.grabber.Downloads
import com.gasplanet.grabber.Job
import com.gasplanet.grabber.JobState
import com.gasplanet.grabber.MainActivity
import com.gasplanet.grabber.Settings
import java.io.File

@Composable
fun AppRoot(incomingUrl: String?, onIncomingConsumed: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    var tab by remember { mutableStateOf(0) }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var resolveUrl by remember { mutableStateOf<String?>(null) }

    // A link shared in from another app goes straight to the resolve sheet.
    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            resolveUrl = incomingUrl
            onIncomingConsumed()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val openBrowser = browserUrl
        if (openBrowser != null) {
            BrowserScreen(
                startUrl = openBrowser,
                onClose = { browserUrl = null },
                onGrab = { resolveUrl = it },
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Download, null) },
                            label = { Text("Downloads") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Language, null) },
                            label = { Text("Sites") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Settings, null) },
                            label = { Text("Settings") },
                        )
                    }
                },
            ) { inner ->
                when (tab) {
                    0 -> HomeScreen(inner) { resolveUrl = it }
                    1 -> SitesScreen(inner, settings) { browserUrl = it }
                    else -> SettingsScreen(inner, settings)
                }
            }
        }

        val target = resolveUrl
        if (target != null) {
            ResolveDialog(
                url = target,
                settings = settings,
                onDismiss = { resolveUrl = null },
            )
        }
    }
}

@Composable
private fun HomeScreen(padding: PaddingValues, onGrab: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val jobs by Downloads.jobs.collectAsState()
    var text by remember { mutableStateOf("") }

    fun grab() {
        val url = MainActivity.firstUrlIn(text.trim()) ?: text.trim()
        if (url.startsWith("http")) {
            onGrab(url)
            text = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Grabber",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Paste a link, or share one into this app from anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                clipboard.getText()?.text?.let { pasted ->
                    text = MainActivity.firstUrlIn(pasted) ?: pasted
                }
            }) { Text("Paste") }
            Button(
                onClick = { grab() },
                enabled = text.isNotBlank(),
            ) { Text("Grab") }
        }

        Spacer(Modifier.height(16.dp))

        if (jobs.isEmpty()) {
            EmptyState()
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Queue and history",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (jobs.any { it.state.isFinished }) {
                    TextButton(onClick = { Downloads.clearFinished() }) { Text("Clear finished") }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(jobs.reversed(), key = { it.id }) { job ->
                    JobCard(job, context)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Download,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("Nothing downloaded yet", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Open a video in any app, tap Share, and pick Grabber.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JobCard(job: Job, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.thumbnail != null) {
                    AsyncImage(
                        model = job.thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 72.dp, height = 44.dp)
                            .background(Color.Black, RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        job.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stateLabel(job),
                            style = MaterialTheme.typography.labelSmall,
                            color = stateColor(job.state),
                        )
                        if (job.vr.isVr) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                job.vr.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (job.state == JobState.DOWNLOADING || job.state == JobState.SAVING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = job.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (job.line.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        job.line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            job.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            job.savedTo?.takeIf { job.state == JobState.DONE }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    job.state == JobState.DONE -> {
                        IconButton(onClick = { openSaved(context, job) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                        }
                        IconButton(onClick = { shareSaved(context, job) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                    job.state.isRunning -> {
                        TextButton(onClick = { DownloadService.cancel(context, job.id) }) {
                            Text("Cancel")
                        }
                    }
                    job.state == JobState.FAILED || job.state == JobState.CANCELLED -> {
                        IconButton(onClick = {
                            Downloads.update(job.id) {
                                it.copy(state = JobState.QUEUED, error = null, progress = 0f)
                            }
                            DownloadService.start(context)
                        }) { Icon(Icons.Filled.Refresh, contentDescription = "Try again") }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (job.state.isFinished) {
                    IconButton(onClick = { Downloads.remove(job.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                }
            }
        }
    }
}

private fun stateLabel(job: Job): String = when (job.state) {
    JobState.QUEUED -> "Waiting"
    JobState.RESOLVING -> "Looking it up"
    JobState.DOWNLOADING -> "${(job.progress * 100).toInt()}%" +
        if (job.etaSeconds > 0) " · ${job.etaSeconds}s left" else ""
    JobState.SAVING -> "Saving to gallery"
    JobState.DONE -> "Saved"
    JobState.FAILED -> "Failed"
    JobState.CANCELLED -> "Cancelled"
}

@Composable
private fun stateColor(state: JobState): Color = when (state) {
    JobState.DONE -> MaterialTheme.colorScheme.primary
    JobState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * On Android 10 and up the saved file already has a content:// address the
 * gallery understands. Older versions got a plain path, which cannot be handed
 * to another app directly, so it goes out through the file provider instead.
 */
private fun viewableUri(context: Context, raw: String): Uri {
    val uri = Uri.parse(raw)
    if (uri.scheme != "file") return uri
    val path = uri.path ?: return uri
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    }.getOrDefault(uri)
}

private fun openSaved(context: Context, job: Job) {
    val raw = job.savedUri ?: return
    val uri = viewableUri(context, raw)
    val mime = if (job.quality.audioOnly) "audio/*" else "video/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Play with")) }
}

private fun shareSaved(context: Context, job: Job) {
    val raw = job.savedUri ?: return
    val uri = viewableUri(context, raw)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(if (job.quality.audioOnly) "audio/*" else "video/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}
