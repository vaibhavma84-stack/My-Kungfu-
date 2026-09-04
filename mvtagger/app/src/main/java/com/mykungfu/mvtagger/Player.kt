package com.mykungfu.mvtagger

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How far a double tap jumps, which is what every other player uses. */
private const val SKIP_MS = 10_000L

/** Playing at this while a finger is held down. */
private const val HELD_SPEED = 2f

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
 * The gestures are the ones every other player uses, because those habits are
 * worth more than anything invented here:
 *
 *     left, up and down       brightness
 *     right, up and down      volume
 *     middle, left and right  scrub
 *     double tap left/right   back or forward ten seconds
 *     double tap middle       pause and play
 *     tap                     the ordinary controls
 *
 * Paused, two buttons step a frame at a time.
 *
 * And a lock, which matters more the more of those there are: a phone held in
 * two hands catches palms constantly, and without a lock every one of these is
 * a way to lose your place.
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

    var failure by remember(playing.uri) { mutableStateOf<String?>(null) }
    var indicator by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var controlsUp by remember { mutableStateOf(true) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var subtitles by remember { mutableStateOf<List<TextTrack>>(emptyList()) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var running by remember { mutableStateOf(true) }

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

            override fun onTracksChanged(tracks: Tracks) {
                subtitles = textTracksOf(tracks)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                running = isPlaying
            }
        }
        player.addListener(listener)
        subtitles = textTracksOf(player.currentTracks)
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

    // Locking takes the ordinary controls away with it. Leaving them up would
    // put a pause button on a screen that is meant to ignore being touched.
    LaunchedEffect(locked, view) {
        view?.let { it.useController = !locked }
        if (locked) {
            view?.hideController()
            subtitleMenu = false
        }
    }

    // The level shown while a drag is happening, gone shortly after it stops.
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(900)
            indicator = null
        }
    }

    LaunchedEffect(showUnlock) {
        if (showUnlock) {
            delay(2500)
            showUnlock = false
        }
    }

    // Locked, back reveals the way out rather than taking it -- otherwise the
    // lock would be a button that stops nothing at all.
    BackHandler {
        if (locked) showUnlock = true else onClose()
    }

    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).also { made ->
                    made.player = player
                    made.useController = true
                    made.controllerShowTimeoutMs = 3500
                    made.setShowNextButton(false)
                    made.setShowPreviousButton(false)
                    made.keepScreenOn = true
                    made.setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsUp = visibility == View.VISIBLE
                        }
                    )
                    made.showController()
                    view = made
                }
            },
            modifier = Modifier
                .fillMaxSize()
                /*
                   Taps: one shows the controls, two skip. They have to be
                   handled together, because telling them apart is the whole
                   problem -- a single tap cannot be acted on until enough time
                   has passed to know a second one is not coming.

                   That means the controls no longer toggle themselves and this
                   has to do it, which is why the view is kept in hand.
                */
                .pointerInput(locked) {
                    // Whether the two-speed was this gesture's doing. Without
                    // it, every tap would put the speed back to one and undo a
                    // speed chosen from the controls.
                    var boosted = false

                    detectTapGestures(
                        onTap = {
                            if (locked) {
                                showUnlock = true
                            } else {
                                view?.let { if (controlsUp) it.hideController() else it.showController() }
                            }
                        },
                        onDoubleTap = { at ->
                            if (locked) return@detectTapGestures
                            when (sideAt(at.x, size.width)) {
                                Side.LEFT -> {
                                    player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0))
                                    indicator = "− 10s"
                                }
                                Side.RIGHT -> {
                                    player.seekTo(player.currentPosition + SKIP_MS)
                                    indicator = "+ 10s"
                                }
                                Side.NONE -> {
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                            }
                        },
                        /*
                           Two speed while held, which is the fastest way
                           through a slow stretch without losing your place.
                           The speed is put back on release whatever happens,
                           including a gesture that is cancelled -- a player
                           left at double speed would look broken.
                        */
                        onPress = {
                            // Waits for the finger to come up, or for the
                            // gesture to be cancelled -- the speed has to go
                            // back either way.
                            tryAwaitRelease()
                            if (boosted) {
                                player.setPlaybackSpeed(1f)
                                boosted = false
                            }
                        },
                        onLongPress = {
                            if (!locked) {
                                boosted = true
                                player.setPlaybackSpeed(HELD_SPEED)
                                indicator = "2×"
                            }
                        },
                    )
                }
                /*
                   Brightness on the left of the picture, volume on the right,
                   which is the arrangement every other player uses.

                   Only the drag is consumed. A tap goes on through, so the
                   handler above still sees it.
                */
                .pointerInput(maxVolume, locked) {
                    var side = Side.NONE
                    var level = 0f

                    detectVerticalDragGestures(
                        onDragStart = { at ->
                            side = if (locked) Side.NONE else sideAt(at.x, size.width)
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
                .pointerInput(locked) {
                    var scrubbing = false
                    var target = 0L
                    var from = 0L

                    detectHorizontalDragGestures(
                        onDragStart = { at ->
                            scrubbing = !locked &&
                                    sideAt(at.x, size.width) == Side.NONE &&
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

        // The top row comes and goes with the transport controls, so sideways
        // the picture is uninterrupted until you ask for something.
        if (controlsUp && !locked) {
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

                if (subtitles.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { subtitleMenu = true }) {
                            Text("Subtitles", color = Color.White)
                        }
                        DropdownMenu(
                            expanded = subtitleMenu,
                            onDismissRequest = { subtitleMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (subtitles.none { it.on }) "Off  ✓" else "Off") },
                                onClick = {
                                    player.trackSelectionParameters =
                                        player.trackSelectionParameters.buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                    subtitleMenu = false
                                },
                            )
                            for (track in subtitles) {
                                DropdownMenuItem(
                                    text = { Text(track.label + if (track.on) "  ✓" else "") },
                                    onClick = {
                                        player.trackSelectionParameters =
                                            player.trackSelectionParameters.buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .setOverrideForType(
                                                    TrackSelectionOverride(
                                                        track.group.mediaTrackGroup, track.index
                                                    )
                                                )
                                                .build()
                                        subtitleMenu = false
                                    },
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = { locked = true }) {
                    Icon(Icons.Default.Lock, contentDescription = "Lock the screen", tint = Color.White)
                }
                TextButton(onClick = { onOpenExternally(playing.uri, playing.mimeType) }) {
                    Text("Another app", color = Color.White)
                }
            }
        }

        /*
           A frame at a time, which only means anything while paused.

           There is no API for stepping a frame: what there is, is an exact
           seek. So this works out how long one frame lasts from the video's
           own frame rate and moves by that much. A file that does not state
           its rate is assumed to be 25 -- wrong for some, and still close
           enough to land on a different frame, which is the whole point.

           Backwards is the expensive direction. An exact seek back has to
           decode from the previous keyframe, so on a long-GOP file it takes a
           moment; that is the format's doing rather than something to fix
           here.
        */
        if (controlsUp && !locked && !running) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val step = frameMs(player)
                TextButton(onClick = {
                    player.seekTo((player.currentPosition - step).coerceAtLeast(0L))
                }) { Text("◀ frame", color = Color.White) }
                Text(
                    clock(player.currentPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = {
                    val end = player.duration
                    val to = player.currentPosition + step
                    player.seekTo(if (end > 0L) to.coerceAtMost(end) else to)
                }) { Text("frame ▶", color = Color.White) }
            }
        }

        // Locked, the only thing on screen is the way out of it, and only
        // after a tap -- otherwise the lock would be its own distraction.
        if (locked && showUnlock) {
            IconButton(
                onClick = { locked = false; showUnlock = false },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
            ) {
                Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = Color.White)
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

/** One subtitle track a file carries, and whether it is the one showing. */
private class TextTrack(
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val on: Boolean,
)

/**
 * The subtitle tracks inside the file.
 *
 * The app goes to real trouble to write these into an MP4 and until now gave
 * no way to turn one on, which made the whole exercise invisible.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun textTracksOf(tracks: Tracks): List<TextTrack> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) return@mapNotNull null
            val format = group.getTrackFormat(index)
            TextTrack(
                group = group,
                index = index,
                label = format.label
                    ?: format.language?.takeIf { it.isNotBlank() && it != "und" }
                    ?: ("Subtitles " + (index + 1)),
                on = group.isTrackSelected(index),
            )
        }
    }

/**
 * How long one frame lasts, from the video's own rate.
 *
 * Unstated rates are common enough in this library that a fallback is needed
 * rather than a disabled button; 25 is the safe middle, and being wrong about
 * it costs a step of the wrong size rather than a step that does nothing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun frameMs(player: ExoPlayer): Long {
    val rate = player.videoFormat?.frameRate ?: -1f
    val perFrame = if (rate > 0f) (1000f / rate) else 40f
    return perFrame.toLong().coerceAtLeast(1L)
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
