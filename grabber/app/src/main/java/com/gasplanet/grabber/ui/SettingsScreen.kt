package com.gasplanet.grabber.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gasplanet.grabber.Engine
import com.gasplanet.grabber.Quality
import com.gasplanet.grabber.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(padding: PaddingValues, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var engineVersion by remember { mutableStateOf("checking…") }
    var updating by remember { mutableStateOf(false) }
    var updateNote by remember { mutableStateOf<String?>(null) }

    var quality by remember { mutableStateOf(settings.defaultQuality) }
    var vrBest by remember { mutableStateOf(settings.vrAlwaysBest) }
    var vrHints by remember { mutableStateOf(settings.vrNameHints) }
    var metadata by remember { mutableStateOf(settings.embedMetadata) }
    var thumbnail by remember { mutableStateOf(settings.embedThumbnail) }
    var subtitles by remember { mutableStateOf(settings.writeSubtitles) }
    var h264 by remember { mutableStateOf(settings.preferH264) }
    var playlists by remember { mutableStateOf(settings.grabWholePlaylist) }
    var extraArgs by remember { mutableStateOf(settings.extraArgs) }

    LaunchedEffect(Unit) {
        engineVersion = withContext(Dispatchers.IO) {
            runCatching {
                Engine.ensureInit(context)
                Engine.version(context)
            }.getOrElse { "not started: ${it.message}" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("Engine", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "yt-dlp $engineVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Sites change how they serve video constantly. An out-of-date engine " +
                "is the usual reason a link that worked last month stops working.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !updating,
            onClick = {
                updating = true
                updateNote = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            Engine.ensureInit(context)
                            Engine.update(context)
                        }
                    }
                    updateNote = result.getOrElse { "Update failed: ${it.message}" }
                    engineVersion = withContext(Dispatchers.IO) { Engine.version(context) }
                    settings.lastEngineUpdateCheck = System.currentTimeMillis()
                    updating = false
                }
            },
        ) { Text(if (updating) "Updating…" else "Update engine") }
        updateNote?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        SettingsDivider()

        Text("Downloads", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            label = "Default quality",
            current = quality.label,
            options = Quality.entries.map { it.label to it },
            onPick = { quality = it; settings.defaultQuality = it },
        )
        SwitchRow(
            "Ignore the quality cap for VR",
            "A 4K sphere fills your view at roughly 1080p, so VR always takes the best there is.",
            vrBest,
        ) { vrBest = it; settings.vrAlwaysBest = it }
        SwitchRow(
            "Label VR files for headset players",
            "Adds the layout to the file name — _360, _180x180_3dh and so on — which is how DeoVR, Skybox and Pigasus know how to show it.",
            vrHints,
        ) { vrHints = it; settings.vrNameHints = it }
        SwitchRow(
            "Prefer H.264",
            "Bigger files that play on anything, including older TVs and standalone headsets.",
            h264,
        ) { h264 = it; settings.preferH264 = it }
        SwitchRow("Embed title and artist", null, metadata) {
            metadata = it; settings.embedMetadata = it
        }
        SwitchRow("Embed the thumbnail as cover art", null, thumbnail) {
            thumbnail = it; settings.embedThumbnail = it
        }
        SwitchRow("Download subtitles where they exist", null, subtitles) {
            subtitles = it; settings.writeSubtitles = it
        }
        SwitchRow(
            "Take whole playlists",
            "When a link belongs to a playlist, queue every video in it rather than just that one.",
            playlists,
        ) { playlists = it; settings.grabWholePlaylist = it }

        SettingsDivider()

        Text("Advanced", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = extraArgs,
            onValueChange = { extraArgs = it; settings.extraArgs = it },
            label = { Text("Extra yt-dlp arguments") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Passed straight through, for anything this screen does not cover.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsDivider()

        Text("What this cannot do", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Netflix, Disney+, Prime Video and other subscription services encrypt " +
                "their video with DRM. Nothing here can open that, by design. Sites " +
                "that need you signed in will also refuse unless you are.\n\n" +
                "Downloading is for video you have the right to keep — your own " +
                "uploads, material published under a licence that allows it, and " +
                "anything a site offers for download itself.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(20.dp))
    Divider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
