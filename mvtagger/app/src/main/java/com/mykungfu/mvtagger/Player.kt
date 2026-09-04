package com.mykungfu.mvtagger

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Playing a file, inside the app.
 *
 * The app deliberately had no player for a long time, on the grounds that the
 * phone already has good ones. That turned out to be wrong in practice: handing
 * a file to another app goes through a permission grant on a folder URI, and
 * enough players open the file in a component that never received the grant
 * that "cannot play this video" was a common answer for a file that is
 * perfectly fine.
 *
 * Media3 reads the containers this library holds -- MP4 and M4V, MKV, WebM,
 * AVI, MOV, TS -- and audio files besides. What it can decode *inside* them is
 * still the phone's own hardware and software codecs, so an unusual stream can
 * play on one device and not another. When that happens the error says so
 * plainly and the file can still be handed to another app, which may have its
 * own decoders and succeed where this cannot.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    playing: Playing,
    onClose: () -> Unit,
    onOpenExternally: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    var failure by remember(playing.uri) { mutableStateOf<String?>(null) }

    val player = remember(playing.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playing.uri))
            playWhenReady = true
            prepare()
        }
    }

    // Release it whatever ends the screen -- back, a rotation, or the file
    // being swapped for another. A player left holding a codec keeps the
    // hardware decoder, and the next file then fails for no visible reason.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = describe(error)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                playing.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onOpenExternally(playing.uri, playing.mimeType) }) {
                Text("Another app")
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                // `also` rather than `apply`: inside `apply` the name `player`
                // would resolve to PlayerView's own property, which is null at
                // that moment, and the view would sit there black forever.
                factory = { context ->
                    PlayerView(context).also { view ->
                        view.player = player
                        view.useController = true
                        view.setShowNextButton(false)
                        view.setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            failure?.let {
                Column(Modifier.background(Color.Black).padding(24.dp)) {
                    Text(
                        "This file would not play here",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        it,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { onOpenExternally(playing.uri, playing.mimeType) }) {
                        Text("Try another app")
                    }
                }
            }
        }
    }
}

/**
 * What went wrong, in words rather than an error code.
 *
 * The distinction that matters is whether the file is unreadable or merely
 * undecodable here: one is a broken file, the other is a codec this phone does
 * not have, and only the second is worth trying another app for.
 */
private fun describe(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
        "This phone has no decoder for the video or audio inside it. Another " +
                "player may carry its own and manage it."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "The container could not be read. If this file was tagged by the app, " +
                "that is worth knowing about — the original should still play."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
        "The file could not be opened. It may have been moved or deleted since " +
                "the collection was last read."
    else -> error.errorCodeName + ": " + (error.message ?: "no further detail")
}
