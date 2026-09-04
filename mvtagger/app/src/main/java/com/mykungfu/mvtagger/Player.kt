package com.mykungfu.mvtagger

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
 *
 * The screen behaves the way every other video player does, because those
 * habits are worth more than any invention here: the system bars go away, the
 * title bar goes away when the phone is turned sideways, dragging up and down
 * the left of the picture changes brightness and the right changes volume, and
 * the transport controls are the standard ones with a scrubber.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    playing: Playing,
    onClose: () -> Unit,
    onOpenExternally: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var failure by remember(playing.uri) { mutableStateOf<String?>(null) }
    var indicator by remember { mutableStateOf<String?>(null) }

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

    /*
       Full screen, and putting the phone back as it was afterwards.

       The activity handles rotation itself rather than being recreated, so
       turning the phone sideways only recomposes -- which is why it stayed
       letterboxed under the status bar until this hid the bars outright.

       Everything here is undone on the way out. A brightness override left
       behind would follow the user around the rest of the app, and a screen
       kept awake would stay awake.
    */
    DisposableEffect(activity) {
        val window = activity?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        bars?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // The level shown while a drag is happening, gone shortly after it stops.
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(900)
            indicator = null
        }
    }

    BackHandler { onClose() }

    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).also { view ->
                    view.player = player
                    view.useController = true
                    view.controllerShowTimeoutMs = 3500
                    view.setShowNextButton(false)
                    view.setShowPreviousButton(false)
                    view.keepScreenOn = true
                    view.showController()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                /*
                   Brightness on the left of the picture, volume on the right,
                   which is the arrangement every other player uses.

                   Only the drag is consumed. A tap goes on through to the view
                   underneath, so the ordinary transport controls -- including
                   the scrubber -- still appear on a tap, which is what they are
                   for.
                */
                .pointerInput(maxVolume) {
                    var side = Side.NONE
                    var level = 0f

                    detectVerticalDragGestures(
                        onDragStart = { at ->
                            side = sideAt(at.x, size.width)
                            level = when (side) {
                                Side.LEFT -> brightnessOf(activity)
                                Side.RIGHT ->
                                    audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                Side.NONE -> 0f
                            }
                        },
                    ) { change, dragAmount ->
                        // The middle belongs to seeking. Not consuming here is
                        // what lets that gesture have it.
                        if (side == Side.NONE) return@detectVerticalDragGestures
                        change.consume()
                        // Up is more. A full sweep of the screen covers the
                        // whole range twice over, which is about right: fine
                        // enough to land on a value, quick enough to get from
                        // silent to loud without several strokes.
                        val sweep = -dragAmount / size.height * 2f

                        if (side == Side.LEFT) {
                            level = (level + sweep).coerceIn(0.01f, 1f)
                            activity?.window?.let { window ->
                                window.attributes = window.attributes.apply {
                                    screenBrightness = level
                                }
                            }
                            indicator = "Brightness " + (level * 100).roundToInt() + "%"
                        } else {
                            level = (level + sweep * maxVolume).coerceIn(0f, maxVolume.toFloat())
                            val step = level.roundToInt()
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
                            indicator = "Volume " + step + " / " + maxVolume
                        }
                    }
                }
                /*
                   Dragging across the middle scrubs, and only across the
                   middle: the sides are already spoken for, and a gesture that
                   does two things depending on how straight your thumb was is
                   worse than one that does nothing.

                   The seek is applied on release rather than as it moves. A
                   player asked to jump on every frame of a drag spends the
                   whole gesture re-buffering and arrives late; this shows where
                   it is going and goes there once.
                */
                .pointerInput(Unit) {
                    var scrubbing = false
                    var target = 0L
                    var from = 0L

                    detectHorizontalDragGestures(
                        onDragStart = { at ->
                            scrubbing = sideAt(at.x, size.width) == Side.NONE &&
                                    player.duration > 0
                            from = player.currentPosition
                            target = from
                        },
                        onDragEnd = {
                            if (scrubbing) player.seekTo(target)
                            scrubbing = false
                        },
                        onDragCancel = { scrubbing = false },
                    ) { change, dragAmount ->
                        if (!scrubbing) return@detectHorizontalDragGestures
                        change.consume()
                        // Re-read rather than trust the value the gesture
                        // started with: a duration can go unknown mid-stream,
                        // and coercing into a backwards range throws.
                        val duration = player.duration
                        if (duration <= 0L) return@detectHorizontalDragGestures
                        // A sweep of the whole width covers a minute and a half,
                        // or a tenth of the film if that is longer -- so a short
                        // clip is not unusable and a three-hour one does not need
                        // twenty strokes.
                        val across = maxOf(90_000L, duration / 10)
                        val moved = (dragAmount / size.width * across).toLong()
                        target = (target + moved).coerceIn(0L, duration)
                        val delta = (target - from) / 1000
                        indicator = clock(target) + "   " +
                                (if (delta >= 0) "+" else "−") +
                                kotlin.math.abs(delta) + "s"
                    }
                },
        )

        // Sideways, the picture is the point and the title bar is in its way.
        if (!landscape) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
        }

        indicator?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(20.dp, 12.dp),
            )
        }

        failure?.let {
            Column(
                Modifier.align(Alignment.Center).background(Color.Black).padding(24.dp)
            ) {
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
                TextButton(onClick = onClose) { Text("Back") }
            }
        }
    }
}

/** Which third of the picture a gesture started in. */
private enum class Side { LEFT, RIGHT, NONE }

private fun sideAt(x: Float, width: Int): Side {
    val third = width / 3f
    return when {
        x < third -> Side.LEFT
        x > width - third -> Side.RIGHT
        else -> Side.NONE
    }
}

/** A position as a person reads one. */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val mm = minutes.toString().padStart(if (hours > 0) 2 else 1, '0')
    return (if (hours > 0) hours.toString() + ":" else "") +
            mm + ":" + seconds.toString().padStart(2, '0')
}

/**
 * The brightness a drag should start from.
 *
 * A window that has not been overridden reports -1, meaning "whatever the
 * system says". There is no way to read the system's own value from here
 * without a permission this app does not want, so the first drag starts from
 * the middle -- which is one slightly odd stroke, once, against asking for a
 * settings permission to save it.
 */
private fun brightnessOf(activity: Activity?): Float {
    val current = activity?.window?.attributes?.screenBrightness ?: -1f
    return if (current < 0f) 0.5f else current
}

/** The Activity behind whatever context Compose happens to hand out. */
private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
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
