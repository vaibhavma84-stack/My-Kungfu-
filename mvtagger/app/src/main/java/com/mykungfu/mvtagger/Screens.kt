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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mykungfu.mvtagger.core.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mykungfu.mvtagger.core.Languages
import com.mykungfu.mvtagger.core.Matching
import com.mykungfu.mvtagger.core.MediaKind
import com.mykungfu.mvtagger.core.RenameTemplate
import com.mykungfu.mvtagger.core.VideoTags

@Composable
fun AppScreen(
    state: UiState,
    viewModel: AppViewModel,
    onOpenExternally: (Uri, String) -> Unit,
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
        else -> MainScreen(
            state, viewModel, snackbar,
            onAddSource = { pickSource.launch(null) },
            onPickOutput = { pickOutput.launch(null) },
            onOpenExternally = onOpenExternally,
        )
    }
}

// ------------------------------------------------------------------- library

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: UiState,
    viewModel: AppViewModel,
    snackbar: SnackbarHostState,
    onAddSource: () -> Unit,
    onPickOutput: () -> Unit,
    onOpenExternally: (Uri, String) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("MV Tagger") },
                actions = {
                    IconButton(onClick = {
                        if (state.tab == MainTab.TO_DO) viewModel.rescan()
                        else viewModel.scanCollection()
                    }) {
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
            TabRow(selectedTabIndex = if (state.tab == MainTab.TO_DO) 0 else 1) {
                Tab(
                    selected = state.tab == MainTab.TO_DO,
                    onClick = { viewModel.showTab(MainTab.TO_DO) },
                    text = { Text("To do (" + state.items.count { it.status == ItemStatus.NEW } + ")") },
                )
                Tab(
                    selected = state.tab == MainTab.COLLECTION,
                    onClick = { viewModel.showTab(MainTab.COLLECTION) },
                    text = { Text("Collection (" + state.collection.size + ")") },
                )
            }

            state.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodyMedium)
            }

            if (!state.settings.isReady) {
                SetupCard(state, onAddSource, onPickOutput)
                return@Column
            }

            when (state.tab) {
                MainTab.TO_DO -> ToDoContent(state, viewModel, onAddSource)
                MainTab.COLLECTION -> CollectionContent(state, viewModel, onOpenExternally)
            }
        }
    }
}

@Composable
private fun ToDoContent(state: UiState, viewModel: AppViewModel, onAddSource: () -> Unit) {
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

/**
 * The finished library, grouped the way someone looks for something rather than
 * the way it is stored: music videos by language, episodes by series, films by
 * year.
 */
@Composable
private fun CollectionContent(
    state: UiState,
    viewModel: AppViewModel,
    onOpen: (Uri, String) -> Unit,
) {
    val outputTree = state.settings.outputUri

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp, 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (kind in MediaKind.entries) {
            val count = Catalogue.count(state.collection, kind)
            FilterChip(
                selected = state.collectionKind == kind,
                onClick = { viewModel.setCollectionKind(kind) },
                label = { Text(plural(kind) + "  " + count) },
            )
        }
    }

    // Music videos get a second row: the languages actually present, so a
    // library of mostly Hindi does not offer thirty empty choices.
    if (state.collectionKind == MediaKind.MUSIC_VIDEO) {
        val languages = Catalogue.languagesPresent(state.collection)
        if (languages.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(16.dp, 0.dp, 16.dp, 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = state.collectionLanguage == null,
                    onClick = { viewModel.setCollectionLanguage(null) },
                    label = { Text("All") },
                )
                for ((code, count) in languages) {
                    FilterChip(
                        selected = state.collectionLanguage == code && code != null,
                        onClick = { viewModel.setCollectionLanguage(code) },
                        label = {
                            Text(
                                (code?.let { Languages.displayName(it) } ?: "Not known") +
                                        "  " + count
                            )
                        },
                    )
                }
            }
        }
    }

    val groups = Catalogue.group(state.collection, state.collectionKind, state.collectionLanguage)

    if (groups.isEmpty()) {
        Text(
            if (state.collectionScanned)
                "Nothing here yet. Files appear once you have saved them."
            else "Pull the refresh button to read the output folder.",
            Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (group in groups) {
            item(key = "group:" + group.label) {
                Text(
                    group.label + "  ·  " + group.entries.size,
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            for (section in group.sections) {
                section.label?.let { label ->
                    item(key = "section:" + group.label + "/" + label) {
                        Text(
                            label,
                            Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(section.entries, key = { it.documentId }) { entry ->
                    CollectionRow(
                        entry,
                        subheading = entry.subheadingExcluding(
                            listOf(group.label, section.label)
                        ),
                        onPlay = {
                            outputTree?.let { tree ->
                                onOpen(
                                    Saf.documentUri(tree, entry.documentId),
                                    Saf.mimeForName(entry.name),
                                )
                            }
                        },
                        onEdit = { viewModel.openCollectionEntry(entry) },
                    )
                }
            }
        }
    }
}

private fun plural(kind: MediaKind): String = when (kind) {
    MediaKind.MUSIC_VIDEO -> "Music videos"
    MediaKind.MOVIE -> "Movies"
    MediaKind.TV_EPISODE -> "Series"
}

/**
 * Tapping the row hands the file to a player; the pencil opens it to be
 * corrected. Two things one tap apart, because both are wanted often and
 * neither should need a menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionRow(
    entry: Entry,
    subheading: String?,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(entry)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.heading,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subheading?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.kind == MediaKind.MUSIC_VIDEO) {
                    Text(
                        entry.languageLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Correct the details")
            }
        }
    }
}

/**
 * The cover, loaded off the main thread.
 *
 * Decoding even a small image while the list is scrolling is enough to make it
 * stutter, so this reads the cached thumbnail on a background thread and shows
 * a placeholder until it arrives.
 */
@Composable
private fun Thumbnail(entry: Entry) {
    val context = LocalContext.current
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = entry.documentId,
    ) {
        value = if (!entry.hasArtwork) null else withContext(Dispatchers.IO) {
            ArtCache.load(context, entry.documentId)?.asImageBitmap()
        }
    }

    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                entry.heading.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    onOpenExternally: (Uri, String) -> Unit,
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
                    IconButton(onClick = {
                        onOpenExternally(detail.item.uri, Saf.mimeForName(detail.item.name))
                    }) {
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
                Text(
                    if (detail.editingExisting) "Will be filed as" else "Will be saved as",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }

            if (detail.editingExisting) {
                Text(
                    "The details live inside the file, so saving writes it again -- " +
                            "about as long as copying it. The file you have now is kept " +
                            "until the new one has been checked, and if anything goes " +
                            "wrong it is left exactly as it is. Correcting the artist or " +
                            "the title also renames and refiles it; correcting only the " +
                            "language leaves it where it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only ever shown for a container that cannot hold tags, which is
            // exactly when the user needs to know what is about to happen.
            detail.subtitles?.takeIf { !it.isEmpty }?.let { subs ->
                Text(
                    "Subtitles: " + subs.cues.size + " lines" +
                            (subs.language?.let { " in " + Languages.displayName(it) } ?: "") +
                            (subs.source?.let { ", from " + it } ?: "") +
                            ". These will be written into the file and to an .srt beside it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                    Text(
                        if (detail.editingExisting) "Save the corrections"
                        else "Save to output folder"
                    )
                }
                // Skipping is a to-do list idea. A file already in the
                // collection is not waiting to be dealt with.
                if (!detail.editingExisting) {
                    OutlinedButton(onClick = { viewModel.skip(detail.item) }) { Text("Skip") }
                }
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
            Text("Subtitles", style = MaterialTheme.typography.titleMedium)
            Text(
                "Subtitles already in the file, or in an .srt next to it, are always " +
                        "kept and written into the MP4. Fetching the ones a file does " +
                        "not have needs an OpenSubtitles account, because they ration " +
                        "downloads per user. Your password is stored on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Toggle("Fetch missing subtitles", settings.fetchSubtitles) {
                viewModel.applySettings(settings.copy(fetchSubtitles = it))
            }
            Field("Subtitle languages (e.g. en, hi)", settings.subtitleLanguages) {
                viewModel.applySettings(settings.copy(subtitleLanguages = it ?: "en"))
            }
            if (settings.fetchSubtitles) {
                Field("OpenSubtitles API key", settings.openSubtitlesApiKey) {
                    viewModel.applySettings(settings.copy(openSubtitlesApiKey = it ?: ""))
                }
                Field("OpenSubtitles username", settings.openSubtitlesUsername) {
                    viewModel.applySettings(settings.copy(openSubtitlesUsername = it ?: ""))
                }
                Field("OpenSubtitles password", settings.openSubtitlesPassword) {
                    viewModel.applySettings(settings.copy(openSubtitlesPassword = it ?: ""))
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
            Text("After saving", style = MaterialTheme.typography.titleMedium)
            Toggle(
                "Delete the original once the new file is saved",
                settings.deleteOriginalAfterSaving,
            ) { viewModel.applySettings(settings.copy(deleteOriginalAfterSaving = it)) }
            Text(
                if (settings.deleteOriginalAfterSaving)
                    "ON. This is the one thing here that cannot be undone. The " +
                            "original is deleted only after the new file is checked: it " +
                            "must exist, be a sensible size, and -- where tags went " +
                            "inside it -- open and read back correctly. If any of that " +
                            "fails the original is kept and the app says so."
                else
                    "Off. Your originals are left exactly as they are, and every " +
                            "result is a new file in the output folder. Turn this on " +
                            "only once you are happy with how the files are coming out.",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.deleteOriginalAfterSaving)
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
