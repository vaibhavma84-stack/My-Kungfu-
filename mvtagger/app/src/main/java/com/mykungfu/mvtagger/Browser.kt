package com.mykungfu.mvtagger

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * YouTube, inside the app, with a download button under it.
 *
 * The point is not to have a browser. It is that finding something and keeping
 * it should be one motion: watch a bit of it, decide, press Download, and it is
 * in the to-do folder being tagged -- without leaving for another app and
 * coming back with a link in the clipboard.
 *
 * ## What this is not
 *
 * It is not signed in to anything and does not try to be. Nothing here reads
 * the page, injects script into it, or touches its cookies: the address bar is
 * the only thing this takes from the browser, and the fetching is done the same
 * way it is done from a pasted link. That is deliberate -- a browser that
 * quietly harvested a logged-in session would be a different and much worse
 * program.
 *
 * JavaScript is on because YouTube is unusable without it. No Java object is
 * exposed to the page, and file and content access are off, so the page has no
 * way to reach anything of yours.
 */
@Composable
fun BrowserScreen(state: UiState, viewModel: AppViewModel) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    /** The last address the lock turned away, shown once and then forgotten. */
    var blocked by remember { mutableStateOf<String?>(null) }

    /**
     * The video the panel is about: the page on screen, or one long-pressed in
     * a list. Null means the panel is closed and the button is all there is.
     */
    var asked by remember { mutableStateOf<String?>(null) }

    // Back walks the pages first and leaves only when there is nowhere back to
    // go, which is what a back button means inside a browser.
    BackHandler {
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else viewModel.openBrowser(false)
    }

    // Leaving the video closes the panel with it: a download button offering
    // the video you were on two pages ago is worse than no button.
    LaunchedEffect(state.browserUrl) {
        if (state.get.progress == null) asked = null
    }

    DisposableEffect(Unit) {
        onDispose {
            // A WebView left alive keeps playing whatever was on the page.
            web?.loadUrl("about:blank")
            web?.destroy()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val view = web
                if (view != null && view.canGoBack()) view.goBack()
                else viewModel.openBrowser(false)
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                shortUrl(state.browserUrl),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { web?.loadUrl(YouTube.HOME) }) { Text("Home") }
            IconButton(onClick = { viewModel.openBrowser(false) }) {
                Icon(Icons.Default.Close, contentDescription = "Close the browser")
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).also { made ->
                        made.settings.javaScriptEnabled = true
                        made.settings.domStorageEnabled = true
                        // Nothing of the phone's own storage is reachable from
                        // a page. There is no reason for a video site to ask.
                        made.settings.allowFileAccess = false
                        made.settings.allowContentAccess = false
                        made.settings.mediaPlaybackRequiresUserGesture = true
                        made.webViewClient = object : WebViewClient() {
                            /*
                               The lock.

                               Every page the browser is asked to open goes
                               through this, and anything that is not YouTube
                               is refused rather than followed -- an advert, a
                               link in a description, or the page asking to be
                               handed to the YouTube app, which is the one
                               thing this exists to avoid.

                               Refused visibly. A tap that silently does
                               nothing reads as a broken app, so the bar
                               underneath says what happened.
                            */
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url?.toString() ?: return false
                                if (YouTube.looksLikeYouTube(target)) return false
                                blocked = target
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                loading = true
                                url?.let(viewModel::browsedTo)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                url?.let(viewModel::browsedTo)
                            }

                            /*
                               YouTube moves between videos without loading a
                               page, so the address changes with no page start
                               or finish to notice it. This is the one callback
                               that fires for that, and without it the download
                               button would keep offering the video you opened
                               the app on.
                            */
                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                url?.let(viewModel::browsedTo)
                            }
                        }
                        /*
                           Long-press a video in a list and it offers to
                           download that one, rather than the page you are on.

                           This is the per-item download button, arrived at
                           from the other side. Drawing a button next to every
                           row would mean injecting elements into YouTube's own
                           page and re-doing it every time they change their
                           markup. Android already knows what is under a
                           finger, and asking it costs nothing and cannot go
                           stale.

                           requestFocusNodeHref rather than the hit result's
                           own extra: on a thumbnail the extra is the image,
                           and the address wanted is the link wrapped around
                           it.
                        */
                        made.setOnLongClickListener {
                            val kind = made.hitTestResult.type
                            if (kind != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
                                kind != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                            ) return@setOnLongClickListener false

                            val note = Handler(Looper.getMainLooper()).obtainMessage()
                            made.requestFocusNodeHref(note)
                            val href = note.data?.getString("url")
                            if (!YouTube.isWatchable(href)) return@setOnLongClickListener false

                            asked = href
                            viewModel.lookUpLink(href!!)
                            true
                        }
                        made.loadUrl(YouTube.HOME)
                        web = made
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            /*
               The download button, floating over the page.

               It is the whole interface for this: no menu, no address to
               copy. It appears on a video and goes away on a list, because a
               download button on a page of search results can only
               disappoint -- and long-pressing any video in that list offers
               the same thing for that one.

               The arrow is the send icon turned a quarter turn, which is
               exactly what it looks like: the same gesture, pointing the
               other way.
            */
            if (YouTube.isWatchable(state.browserUrl) && asked == null &&
                state.get.progress == null
            ) {
                FloatingActionButton(
                    onClick = {
                        asked = state.browserUrl
                        viewModel.lookUpCurrent()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Download this video",
                        modifier = Modifier.graphicsLayer { rotationZ = 90f },
                    )
                }
            }
        }

        blocked?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(12.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "This browser opens YouTube and nothing else, so that link " +
                            "was not followed.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { blocked = null }) { Text("OK") }
            }
        }

        if (asked != null || state.get.progress != null) {
            DownloadBar(state, viewModel) { asked = null }
        }
    }
}

/**
 * What the button opened: the video it found, and the two ways to keep it.
 *
 * Only ever on screen because something was pressed, which is why it has a
 * close button and no rules of its own about when to appear.
 */
@Composable
private fun DownloadBar(state: UiState, viewModel: AppViewModel, onClose: () -> Unit) {
    val get = state.get

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                get.title ?: if (get.looking) "Asking YouTube…" else "This video",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose, enabled = get.progress == null) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        get.progress?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        // Why the button says 1080p on a video somebody knows is in 4K.
        get.video?.cappedFrom?.takeIf { it > 0 }?.let { tallest ->
            Text(
                "YouTube also has this in " + tallest + "p, but only as VP9 or AV1 " +
                        "with Opus sound — which cannot be tagged inside the file " +
                        "and needs an iPad to decode it in software.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        get.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val picture = get.video?.video
            if (picture == null) {
                if (!get.looking && get.progress == null) {
                    Button(onClick = { viewModel.lookUpCurrent() }) { Text("Try again") }
                }
            } else {
                Button(
                    onClick = { viewModel.fetch(audioOnly = false) },
                    enabled = get.progress == null,
                ) { Text("Video · " + picture.label) }

                get.audio?.let { sound ->
                    TextButton(
                        onClick = { viewModel.fetch(audioOnly = true) },
                        enabled = get.progress == null,
                    ) { Text("Sound only · " + sound.label) }
                }
            }

            if (get.progress != null) {
                TextButton(onClick = { viewModel.stopFetch() }) { Text("Stop") }
            }
        }
    }
}

/** The part of an address worth showing on a phone. */
private fun shortUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
