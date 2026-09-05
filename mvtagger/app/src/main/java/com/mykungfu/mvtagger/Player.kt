package com.mykungfu.mvtagger

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.mykungfu.mvtagger.core.FrameShot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** How far a double tap jumps, which is what every other player uses. */
private const val SKIP_MS = 10_000L

/** Playing at this while a finger is held down. */
private const val HELD_SPEED = 2f

/**
 * Where a double tap lands while looking at a frame.
 *
 * Close enough to see what a face is doing, far enough back that the whole
 * picture is still in mind. Anything more is what the pinch is for.
 */
private const val DOUBLE_TAP_ZOOM = 3f

/**
 * How hard a saved frame is compressed.
 *
 * High, because the point of saving a frame is usually to look at it closely,
 * and the file is one picture rather than a library of them.
 */
private const val QUALITY = 95

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
 * Paused, two buttons step a frame at a time -- and pressing one drops the
 * player into frame mode, where the picture is the only thing on screen. That
 * mode is its own small tool: pinch to zoom into the still, drag to move about
 * it, and save the frame out as a picture.
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
    var frameMode by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(FrameShot.MIN_ZOOM) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    // Where the player is, as a value composition can see. Reading
    // currentPosition straight off the player during composition looks like it
    // works and does not: nothing about it is observable, so the clock would
    // sit still while the frames moved.
    var position by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

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
    // Long enough to read where a frame was saved, short enough not to sit on
    // the picture.
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(1400)
            indicator = null
        }
    }

    // A seek finishes when it finishes, so the clock is read back rather than
    // assumed. Only while stepping: the ordinary controls have their own.
    LaunchedEffect(frameMode) {
        while (frameMode) {
            position = player.currentPosition
            delay(120)
        }
    }

    LaunchedEffect(showUnlock) {
        if (showUnlock) {
            delay(2500)
            showUnlock = false
        }
    }

    /*
       Frame mode.

       Entered by asking for a frame rather than by finding a button for it:
       wanting the next frame and wanting the play button out of the way are
       the same wish. On the way out everything it changed is put back, so
       leaving it cannot leave the picture zoomed into a corner.
    */
    fun enterFrames() {
        player.pause()
        subtitleMenu = false
        position = player.currentPosition
        frameMode = true
    }

    fun leaveFrames() {
        frameMode = false
        zoom = FrameShot.MIN_ZOOM
        offsetX = 0f
        offsetY = 0f
    }

    /*
       A frame at a time, which only means anything while paused.

       There is no API for stepping a frame: what there is, is an exact seek.
       So this works out how long one frame lasts from the video's own frame
       rate and moves by that much. A file that does not state its rate is
       assumed to be 25 -- wrong for some, and still close enough to land on a
       different frame, which is the whole point.

       Backwards is the expensive direction. An exact seek back has to decode
       from the previous keyframe, so on a long-GOP file it takes a moment;
       that is the format's doing rather than something to fix here.
    */
    fun step(direction: Int) {
        val end = player.duration
        val to = (player.currentPosition + frameMs(player) * direction).coerceAtLeast(0L)
        val landing = if (end > 0L) to.coerceAtMost(end) else to
        player.seekTo(landing)
        position = landing
    }

    // Locked, back reveals the way out rather than taking it -- otherwise the
    // lock would be a button that stops nothing at all.
    BackHandler {
        when {
            locked -> showUnlock = true
            frameMode -> leaveFrames()
            else -> onClose()
        }
    }

    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (frameMode) {
            /*
               Stepping through frames, with nothing else on the screen.

               Everything the ordinary player puts up -- the play button, the
               scrub bar, the row along the top -- is gone here, and so is the
               shade the controls draw over the picture to make themselves
               readable. That shade is why a paused frame looked darker than
               the film does; with the controls gone the frame is shown at the
               brightness it was shot at, which is the point of looking at it
               closely.

               Pinch zooms and a drag moves about; a double tap does the same
               in one gesture. The picture cannot be pushed past its own edge,
               so there is no way to end up lost in the black.
            */
            Box(Modifier.fillMaxSize().clipToBounds()) {
                AndroidView(
                    factory = { viewContext ->
                        val made = LayoutInflater.from(viewContext)
                            .inflate(R.layout.frame_player, null) as PlayerView
                        // Inflating without a parent throws the size in the
                        // layout away, and a view left to wrap its content
                        // measures to nothing at all -- which looks exactly
                        // like a player that failed to open the file.
                        made.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        made.player = player
                        made.useController = false
                        made.keepScreenOn = true
                        made
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier
                        .fillMaxSize()
                        /*
                           The gestures sit outside the transform below, which
                           is the whole reason the order of these matters: a
                           finger that moves an inch should move the picture an
                           inch, at any zoom. Inside the transform the same
                           movement would be reported divided by the zoom, and
                           panning would crawl exactly when it was needed most.
                        */
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panned, pinched, _ ->
                                val next = FrameShot.zoom(zoom, pinched)
                                zoom = next
                                offsetX = FrameShot.pan(
                                    offsetX + panned.x, size.width.toFloat(), next
                                )
                                offsetY = FrameShot.pan(
                                    offsetY + panned.y, size.height.toFloat(), next
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoom > FrameShot.MIN_ZOOM) {
                                        zoom = FrameShot.MIN_ZOOM
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        zoom = DOUBLE_TAP_ZOOM
                                    }
                                },
                            )
                        }
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
            }
        } else {
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
                // Frame mode swaps this view out for one that can be
                // transformed. A view left holding the player would keep the
                // surface it is no longer showing on, and a stale one left in
                // hand would have the lock talking to a view nobody can see.
                onRelease = {
                    it.player = null
                    view = null
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
        }

        // The top row comes and goes with the transport controls, so sideways
        // the picture is uninterrupted until you ask for something. Frame mode
        // takes it away entirely along with everything else.
        if (controlsUp && !locked && !frameMode) {
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

        // Paused, the way in to stepping. Asking for a frame is what puts the
        // player into the mode, because wanting the next frame and wanting the
        // play button out of the way are the same wish.
        if (controlsUp && !locked && !running && !frameMode) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { enterFrames(); step(-1) }) {
                    Text("◀ frame", color = Color.White)
                }
                Text(
                    clock(player.currentPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = { enterFrames(); step(1) }) {
                    Text("frame ▶", color = Color.White)
                }
            }
        }

        /*
           The whole of frame mode's furniture: one row, and no player controls
           anywhere.

           It does not hide itself after a few seconds the way the transport
           controls do. This is a tool being used rather than a film being
           watched, and a row that vanished between frames would have to be
           summoned back for every step.
        */
        if (frameMode && !locked) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Plain tappable words rather than buttons: six buttons with
                // Material's own spacing do not fit across a phone held
                // upright, and a row that runs off the edge takes the save
                // with it.
                FrameKey("✕", onClick = { leaveFrames() })
                FrameKey("◀", onClick = { step(-1) })
                Text(
                    exact(position),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                FrameKey("▶", onClick = { step(1) })

                // Tapping the zoom puts it back to life size, which is quicker
                // than pinching back out and lands exactly rather than nearly.
                FrameKey(
                    ((zoom * 10).roundToInt() / 10f).toString() + "×",
                    onClick = {
                        zoom = FrameShot.MIN_ZOOM
                        offsetX = 0f
                        offsetY = 0f
                    },
                )

                /*
                   Saving reads the frame from the file again rather than
                   copying what is on the screen. The screen holds whatever
                   fitted on it, already scaled down; the file holds the frame
                   at the size it was encoded, which is what anybody saving a
                   frame is after.
                */
                FrameKey(
                    "Save",
                    enabled = !saving,
                    onClick = {
                        val at = player.currentPosition
                        saving = true
                        scope.launch {
                            val said = saveFrame(context, playing.uri, at, playing.title)
                            saving = false
                            indicator = said
                        }
                    },
                )
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

        if (saving) {
            Text(
                "Reading the frame…",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(20.dp, 12.dp),
            )
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

/**
 * One word in the frame-mode row, tappable.
 *
 * The padding is what makes it a target: the text is small on purpose, and a
 * thumb aiming at a glyph twelve pixels across would miss most of the time.
 */
@Composable
private fun FrameKey(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
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

/**
 * A position with the milliseconds still on it.
 *
 * The ordinary clock rounds to the second, which is right everywhere else and
 * useless here: several frames share a second, so a clock that hides the
 * milliseconds shows the same time three times in a row while the picture
 * plainly changes.
 */
private fun exact(ms: Long): String =
    clock(ms) + "." + (ms.coerceAtLeast(0L) % 1000).toString().padStart(3, '0')

/**
 * Saves the frame at [positionMs] as a picture, and says where it went.
 *
 * Read from the file rather than off the screen. What is on the screen has
 * already been fitted to the phone and, in frame mode, possibly zoomed into;
 * the file still holds the frame at the size it was encoded, which is what
 * anyone saving a frame wants. It costs a decode from the previous keyframe,
 * which is why this says it is working.
 *
 * The exception is wide on purpose. This runs against whatever container and
 * codec the library happens to hold, and a frame that cannot be read is worth
 * a sentence rather than a crash in the middle of a film.
 */
private suspend fun saveFrame(
    context: Context,
    uri: Uri,
    positionMs: Long,
    title: String,
): String = withContext(Dispatchers.IO) {
    val reader = MediaMetadataRetriever()
    try {
        reader.setDataSource(context, uri)
        // CLOSEST rather than CLOSEST_SYNC: the sync option would quietly hand
        // back the nearest keyframe, which on a long-GOP file can be seconds
        // from the frame being looked at -- a different picture entirely.
        val frame = reader.getFrameAtTime(
            positionMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return@withContext "That frame could not be read from the file"

        val name = FrameShot.fileName(title, positionMs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intoGallery(context, frame, name)
        } else {
            besideTheApp(context, frame, name)
        }
    } catch (e: Exception) {
        "The frame could not be saved: " + (e.message ?: e.javaClass.simpleName)
    } finally {
        runCatching { reader.release() }
    }
}

/**
 * Into the gallery, under a folder of the app's own.
 *
 * Nothing here needs a storage permission: MediaStore lets an app add its own
 * pictures without one, which is the whole reason the app asks for no storage
 * permission at all. Pending until the bytes are written, so a gallery that is
 * watching does not show half a picture.
 */
private fun intoGallery(context: Context, frame: Bitmap, name: String): String {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/Media Centre",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return "The gallery would not take the frame"

    val written = runCatching {
        resolver.openOutputStream(target)?.use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        } ?: false
    }.getOrDefault(false)

    if (!written) {
        runCatching { resolver.delete(target, null, null) }
        return "The frame could not be written"
    }

    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    runCatching { resolver.update(target, values, null, null) }
    return "Saved to Pictures › Media Centre"
}

/**
 * Before Android 10 there is no way into the gallery without asking for the
 * whole of external storage, which this app has never done and is not going to
 * start doing to save a picture. So it goes in the app's own folder, and the
 * scanner is told about it in case the gallery will take it anyway.
 */
private fun besideTheApp(context: Context, frame: Bitmap, name: String): String {
    val folder = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        ?: return "There is nowhere to save it on this phone"
    if (!folder.exists() && !folder.mkdirs()) return "That folder could not be made"

    val file = File(folder, name)
    FileOutputStream(file).use { out -> frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
    runCatching {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    }
    return "Saved to " + file.absolutePath
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
