package com.gasplanet.grabber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.gasplanet.grabber.DownloadService
import com.gasplanet.grabber.Downloads
import com.gasplanet.grabber.Engine
import com.gasplanet.grabber.Job
import com.gasplanet.grabber.Probe
import com.gasplanet.grabber.Quality
import com.gasplanet.grabber.Settings
import com.gasplanet.grabber.VrProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What appears between pasting a link and starting a download: what the link
 * turned out to be, at what sizes, and -- the point of the whole exercise --
 * whether it is VR and in which layout.
 */
@Composable
fun ResolveDialog(url: String, settings: Settings, onDismiss: () -> Unit) {
    val context = LocalContext.current

    var probe by remember { mutableStateOf<Probe?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf(settings.defaultQuality) }
    var vr by remember { mutableStateOf(VrProfile.NONE) }
    var vrTouched by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        probe = null
        error = null
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                Engine.ensureInit(context)
                Engine.probe(url, settings)
            }
        }
        outcome.onSuccess {
            probe = it
            if (!vrTouched) vr = it.vr
        }.onFailure {
            error = it.message ?: "Could not read that link"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val found = probe
                when {
                    error != null -> {
                        Text("That did not work", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = onDismiss) { Text("Close") }
                        }
                    }

                    found == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(12.dp))
                            Text("Reading the link…", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    found.isPlaylist -> PlaylistBody(found, quality, onDismiss) { q ->
                        val jobs = found.playlist.map { entry ->
                            Job(
                                id = Downloads.newId(),
                                url = entry.url,
                                title = entry.title,
                                quality = q,
                                // Each entry is looked up on its way through
                                // the queue, which is where VR is detected.
                                resolved = false,
                            )
                        }
                        Downloads.addAll(jobs)
                        DownloadService.start(context)
                        onDismiss()
                    }

                    else -> VideoBody(
                        probe = found,
                        quality = quality,
                        onQuality = { quality = it },
                        vr = vr,
                        onVr = { vr = it; vrTouched = true },
                        onCancel = onDismiss,
                        onDownload = {
                            Downloads.add(
                                Job(
                                    id = Downloads.newId(),
                                    url = found.url,
                                    title = found.title,
                                    thumbnail = found.thumbnail,
                                    quality = quality,
                                    vr = vr,
                                )
                            )
                            DownloadService.start(context)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoBody(
    probe: Probe,
    quality: Quality,
    onQuality: (Quality) -> Unit,
    vr: VrProfile,
    onVr: (VrProfile) -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
) {
    if (probe.thumbnail != null) {
        AsyncImage(
            model = probe.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.Black, RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(10.dp))
    }

    Text(
        probe.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        buildString {
            probe.uploader?.let { append(it) }
            if (probe.durationLabel.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(probe.durationLabel)
            }
            if (probe.height > 0) {
                if (isNotEmpty()) append(" · ")
                append("${probe.width}×${probe.height}")
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (probe.isLive) {
        Spacer(Modifier.height(6.dp))
        Text(
            "This is a live stream. It will record from now until you stop it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(14.dp))
    ChoiceRow(
        label = "Quality",
        current = quality.label,
        options = availableQualities(probe).map { it.label to it },
        onPick = onQuality,
    )

    Spacer(Modifier.height(6.dp))
    ChoiceRow(
        label = "Layout",
        current = vr.label,
        options = VrProfile.entries.map { it.label to it },
        onPick = onVr,
    )

    if (probe.vr.isVr) {
        Spacer(Modifier.height(6.dp))
        Text(
            if (probe.vrConfident) {
                "Detected as ${probe.vr.label} from the frame shape. " +
                    "The layout is written into the file name so headset players pick it up."
            } else {
                "This might be VR — the shape is suggestive but nothing says so outright. " +
                    "Check the layout above before downloading."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.size(8.dp))
        Button(onClick = onDownload) { Text("Download") }
    }
}

@Composable
private fun PlaylistBody(
    probe: Probe,
    quality: Quality,
    onCancel: () -> Unit,
    onQueueAll: (Quality) -> Unit,
) {
    var chosen by remember { mutableStateOf(quality) }

    Text(probe.title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "${probe.playlist.size} videos in this playlist",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    ChoiceRow(
        label = "Quality",
        current = chosen.label,
        options = Quality.entries.map { it.label to it },
        onPick = { chosen = it },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Each one is looked up as it reaches the front of the queue, so VR clips " +
            "are still detected and labelled.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(18.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.size(8.dp))
        Button(onClick = { onQueueAll(chosen) }) { Text("Queue all") }
    }
}

/** Offering a 4K cap on a video that tops out at 720p only invites confusion. */
private fun availableQualities(probe: Probe): List<Quality> {
    val best = probe.heights.maxOrNull() ?: 0
    return Quality.entries.filter { q ->
        q == Quality.BEST || q.audioOnly || best == 0 || (q.maxHeight ?: 0) <= best
    }
}
