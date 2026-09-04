package com.mykungfu.mvtagger

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mykungfu.mvtagger.core.Artwork
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.Matching
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.RenameTemplate
import com.mykungfu.mvtagger.core.VideoTags

@Composable
fun AppScreen(
    state: UiState,
    viewModel: AppViewModel,
    onOpenExternally: (Item) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val pickSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let(viewModel::addSourceFolder) }

    val pickOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let(viewModel::setOutputFolder) }

    val detail = state.detail
    when {
        detail != null -> {
            BackHandler { viewModel.closeDetail() }
            DetailScreen(state, detail, viewModel, onOpenExternally, snackbar)
        }
        state.showSettings -> {
            BackHandler { viewModel.showSettings(false) }
            SettingsScreen(state, viewModel, snackbar, { pickSource.launch(null) }) {
                pickOutput.launch(null)
            }
        }
        else -> LibraryScreen(
            state, viewModel, snackbar,
            onAddSource = { pickSource.launch(null) },
            onPickOutput = { pickOutput.launch(null) },
        )
    }
}

// ------------------------------------------------------------------- library

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: UiState,
    viewModel: AppViewModel,
    snackbar: SnackbarHostState,
    onAddSource: () -> Unit,
    onPickOutput: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Library")
                        if (state.items.isNotEmpty()) {
                            val done = state.items.count { it.status == ItemStatus.SAVED }
                            Text(
                                "" + state.items.size + " videos · " + done + " done",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.rescan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                    IconButton(onClick = { viewModel.showSettings(true) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodyMedium)
            }

            if (!state.settings.isReady) {
                SetupCard(state, onAddSource, onPickOutput)
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.runBatch() },
                    enabled = state.busy == null,
                ) { Text("Auto-tag everything new") }
                OutlinedButton(onClick = onAddSource) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Folder")
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    ItemRow(item) { viewModel.open(item) }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(state: UiState, onAddSource: () -> Unit, onPickOutput: () -> Unit) {
    Column(
        Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Two folders to choose", style = MaterialTheme.typography.headlineSmall)
        Text(
            "One to read your videos from, and one to write the tidied, tagged " +
                    "copies into. Your originals are never changed or moved.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAddSource, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.settings.sourceTrees.isEmpty()) "1. Choose the folder with your videos"
                else "Videos folder chosen ✓  (add another)"
            )
        }
        Button(onClick = onPickOutput, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.settings.outputTree == null) "2. Choose where tagged files should go"
                else "Output folder chosen ✓  (change)"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(item: Item, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.guess.ifBlank { item.name },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusDot(item.status)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(item.kind.label)
                Label(item.extension.uppercase())
                if (item.size > 0) Label(readableSize(item.size))
            }
            item.note?.takeIf { item.status != ItemStatus.NEW }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp, 2.dp),
    )
}

@Composable
private fun StatusDot(status: ItemStatus) {
    val (text, colour) = when (status) {
        ItemStatus.NEW -> "New" to MaterialTheme.colorScheme.secondary
        ItemStatus.MATCHED -> "Needs a look" to MaterialTheme.colorScheme.tertiary
        ItemStatus.SAVED -> "Done" to MaterialTheme.colorScheme.primary
        ItemStatus.SKIPPED -> "Skipped" to MaterialTheme.colorScheme.outline
        ItemStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = colour)
}

private fun readableSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "" + (bytes / 100_000_000) / 10.0 + " GB"
    bytes >= 1_000_000 -> "" + bytes / 1_000_000 + " MB"
    else -> "" + bytes / 1000 + " kB"
}

// -------------------------------------------------------------------- detail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(
    state: UiState,
    detail: Detail,
    viewModel: AppViewModel,
    onOpenExternally: (Item) -> Unit,
    snackbar: SnackbarHostState,
) {
    val tags = detail.tags
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeDetail() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(detail.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { onOpenExternally(detail.item) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play in another app")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            detail.loading?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ArtworkView(tags.artwork)
                Column(Modifier.weight(1f)) {
                    Text(
                        tags.title ?: "Not identified yet",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    tags.artist?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    tags.album?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label(tags.mediaKind.label)
                        tags.year?.let { Label(it) }
                        tags.language?.let { Label(Languages.displayName(it)) }
                    }
                }
            }

            MediaKindPicker(tags.mediaKind) { viewModel.editTags(tags.copy(mediaKind = it)) }

            Button(
                onClick = { viewModel.lookup() },
                enabled = detail.loading == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Look up online")
            }

            if (detail.candidates.isNotEmpty()) {
                Text("Matches", style = MaterialTheme.typography.titleMedium)
                for (scored in detail.candidates.take(8)) {
                    CandidateRow(scored, detail.chosen?.id == scored.candidate.id) {
                        viewModel.choose(scored)
                    }
                }
                HorizontalDivider()
            }

            Text("Details", style = MaterialTheme.typography.titleMedium)
            Field("Song / episode title", tags.title) { viewModel.editTags(tags.copy(title = it)) }
            Field("Artist", tags.artist) { viewModel.editTags(tags.copy(artist = it)) }
            Choices(detail.artistChoices, tags.artist) {
                viewModel.editTags(tags.copy(artist = it))
            }
            Field(
                if (tags.mediaKind == MediaKind.MUSIC_VIDEO) "Album / film" else "Album",
                tags.album,
            ) { viewModel.editTags(tags.copy(album = it)) }
            Choices(detail.albumChoices, tags.album) {
                viewModel.editTags(tags.copy(album = it))
            }
            Field("Release date or year", tags.date) { viewModel.editTags(tags.copy(date = it)) }
            Field("Genre", tags.genre) { viewModel.editTags(tags.copy(genre = it)) }

            if (tags.mediaKind == MediaKind.TV_EPISODE) {
                Field("Series", tags.showName) { viewModel.editTags(tags.copy(showName = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        Field("Season", tags.seasonNumber?.toString()) {
                            viewModel.editTags(tags.copy(seasonNumber = it?.toIntOrNull()))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        Field("Episode", tags.episodeNumber?.toString()) {
                            viewModel.editTags(tags.copy(episodeNumber = it?.toIntOrNull()))
                        }
                    }
                }
            } else {
                Field("Composer / music director", tags.composer) {
                    viewModel.editTags(tags.copy(composer = it))
                }
                Field("Lyricist", tags.lyricist) { viewModel.editTags(tags.copy(lyricist = it)) }
            }

            LanguagePicker(tags.language) { viewModel.editTags(tags.copy(language = it)) }

            Field("Lyrics", tags.lyrics, singleLine = false) {
                viewModel.editTags(tags.copy(lyrics = it))
            }

            tags.artistBio?.let { Background("About the artist", it) }
            tags.albumInfo?.let { Background("About the album or film", it) }

            HorizontalDivider()
            detail.destination(state.settings)?.let {
                Text("Will be saved as", style = MaterialTheme.typography.labelMedium)
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }

            // Only ever shown for a container that cannot hold tags, which is
            // exactly when the user needs to know what is about to happen.
            detail.conversion?.let { verdict ->
                val converting = detail.willConvert(state.settings)
                Text(
                    if (converting)
                        "Will be repackaged as MP4 so the artwork and details go inside " +
                                "the file. Nothing is re-encoded, so the picture is " +
                                "unchanged. " + verdict.reason
                    else
                        "This container cannot hold tags inside it, so the details will " +
                                "be written to files alongside. " + verdict.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.save() },
                    enabled = detail.loading == null && state.settings.outputTree != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save to output folder")
                }
                OutlinedButton(onClick = { viewModel.skip(detail.item) }) { Text("Skip") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ArtworkView(artwork: Artwork?) {
    val image = remember(artwork) {
        artwork?.let {
            runCatching { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }
                .getOrNull()?.asImageBitmap()
        }
    }
    Box(
        Modifier.size(110.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = "Cover", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        } else {
            Text("No cover", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateRow(scored: Matching.Scored, chosen: Boolean, onClick: () -> Unit) {
    val c = scored.candidate
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (chosen) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "" + (scored.score * 100).toInt() + "%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                listOfNotNull(c.artist, c.album, c.year).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (scored.reasons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    scored.reasons.joinToString(", ") + " · " + c.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String?,
    singleLine: Boolean = true,
    onChange: (String?) -> Unit,
) {
    OutlinedTextField(
        value = value ?: "",
        onValueChange = { onChange(it.ifBlank { null }) },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The names this field could hold, one tap each.
 *
 * A Hindi credit is one string holding the music director, the singer and the
 * lyricist, and nothing in it says which is which. Where the roles are known
 * the field is already right; where they are not, this is better than guessing
 * -- the person looking at the song knows who sang it.
 */
@Composable
private fun Choices(options: List<String>, current: String?, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in options) {
            FilterChip(
                selected = option.equals(current, ignoreCase = true),
                onClick = { onPick(option) },
                label = { Text(option) },
            )
        }
    }
}

@Composable
private fun Background(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MediaKindPicker(current: MediaKind, onPick: (MediaKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in MediaKind.entries) {
            FilterChip(
                selected = kind == current,
                onClick = { onPick(kind) },
                label = { Text(kind.label) },
            )
        }
    }
}

@Composable
private fun LanguagePicker(current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Language: " + (current?.let { Languages.displayName(it) } ?: "not set"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Not set") }, onClick = { onPick(null); open = false })
            for (language in Languages.ALL) {
                DropdownMenuItem(
                    text = { Text(language.english + "  " + language.native) },
                    onClick = { onPick(language.code); open = false },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: UiState,
    viewModel: AppViewModel,
    snackbar: SnackbarHostState,
    onAddSource: () -> Unit,
    onPickOutput: () -> Unit,
) {
    val settings = state.settings
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.showSettings(false) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Folders", style = MaterialTheme.typography.titleMedium)
            for (tree in settings.sourceTrees) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        friendlyTree(tree),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { viewModel.removeSourceFolder(tree) }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = onAddSource) { Text("Add a videos folder") }
            Text(
                "Output: " + (settings.outputTree?.let(::friendlyTree) ?: "not chosen"),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onPickOutput) { Text("Choose the output folder") }

            HorizontalDivider()
            Text("What to fetch", style = MaterialTheme.typography.titleMedium)
            Toggle("Cover art", settings.fetchArtwork) {
                viewModel.applySettings(settings.copy(fetchArtwork = it))
            }
            Toggle("Lyrics", settings.fetchLyrics) {
                viewModel.applySettings(settings.copy(fetchLyrics = it))
            }
            Toggle("Artist and album background", settings.fetchBackground) {
                viewModel.applySettings(settings.copy(fetchBackground = it))
            }
            Toggle(
                "Repackage MKV and similar as MP4",
                settings.convertToMp4,
            ) { viewModel.applySettings(settings.copy(convertToMp4 = it)) }
            Text(
                "Only MP4 has a standard place for artwork and details. When a file " +
                        "cannot hold them, its audio and video are moved into an MP4 " +
                        "container untouched -- nothing is re-encoded, so no quality is " +
                        "lost. VP9 or Opus (most .webm) cannot be moved and are left alone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Toggle(
                "Write .json/.lrc alongside files that cannot hold tags",
                settings.writeSidecars,
            ) { viewModel.applySettings(settings.copy(writeSidecars = it)) }

            HorizontalDivider()
            Text("Naming", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tokens: {artist} {title} {album} {year} {genre} {track2} {show} " +
                        "{season2} {episode2}. Anything in [square brackets] is dropped " +
                        "if it has nothing to fill it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Field("Music video filename", settings.musicNameTemplate) {
                viewModel.applySettings(settings.copy(musicNameTemplate = it ?: RenameTemplate.DEFAULT))
            }
            Field("Music video folder", settings.musicFolderTemplate) {
                viewModel.applySettings(settings.copy(musicFolderTemplate = it ?: ""))
            }
            Field("Movie filename", settings.movieNameTemplate) {
                viewModel.applySettings(settings.copy(movieNameTemplate = it ?: ""))
            }
            Field("Movie folder", settings.movieFolderTemplate) {
                viewModel.applySettings(settings.copy(movieFolderTemplate = it ?: ""))
            }
            Field("Episode filename", settings.episodeNameTemplate) {
                viewModel.applySettings(settings.copy(episodeNameTemplate = it ?: ""))
            }
            Field("Episode folder", settings.episodeFolderTemplate) {
                viewModel.applySettings(settings.copy(episodeFolderTemplate = it ?: ""))
            }

            HorizontalDivider()
            Text("Matching", style = MaterialTheme.typography.titleMedium)
            LanguagePicker(settings.preferredLanguage) {
                viewModel.applySettings(settings.copy(preferredLanguage = it))
            }
            Text(
                "Auto-tag only when at least " +
                        (settings.autoApplyThreshold * 100).toInt() + "% sure",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (threshold in listOf(0.6, 0.7, 0.8, 0.9)) {
                    FilterChip(
                        selected = kotlin.math.abs(settings.autoApplyThreshold - threshold) < 0.01,
                        onClick = {
                            viewModel.applySettings(settings.copy(autoApplyThreshold = threshold))
                        },
                        label = { Text("" + (threshold * 100).toInt() + "%") },
                    )
                }
            }

            HorizontalDivider()
            Text("Film posters (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything works without this. A free TMDb key adds proper film " +
                        "posters, which for a Hindi song is the film's own poster rather " +
                        "than the soundtrack cover.",
                style = MaterialTheme.typography.bodySmall,
            )
            Field("TMDb API key", settings.tmdbApiKey) {
                viewModel.applySettings(settings.copy(tmdbApiKey = it ?: ""))
            }

            HorizontalDivider()
            OutlinedButton(onClick = { viewModel.forgetProgress() }) {
                Text("Forget what has been done")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A tree URI is unreadable; show the part of it a person recognises. */
private fun friendlyTree(uri: String): String =
    Uri.decode(uri).substringAfterLast(':').ifBlank { uri }
