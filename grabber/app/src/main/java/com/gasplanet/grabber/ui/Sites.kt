package com.gasplanet.grabber.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gasplanet.grabber.Settings
import com.gasplanet.grabber.Site

/**
 * Shortcuts to the sites you actually use. Tapping one opens it in the built-in
 * browser, where any video on the page is one button away from downloading.
 *
 * The list is only a starting point. The engine handles roughly 1,800 sites,
 * and anything missing can be added here or simply shared into the app.
 */
@Composable
fun SitesScreen(padding: PaddingValues, settings: Settings, onOpen: (String) -> Unit) {
    var sites by remember { mutableStateOf(settings.sites) }
    var adding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Sites",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Browse, then tap Grab on any page with a video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a site")
            }
        }
        Spacer(Modifier.height(8.dp))

        val grouped = sites.groupBy { it.category }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            grouped.forEach { (category, entries) ->
                item(key = "header-$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                }
                item(key = "grid-$category") {
                    // A grid inside a scrolling column needs a fixed height, so
                    // it is worked out from the number of rows it will take.
                    val rows = (entries.size + 1) / 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.height((rows * 60).dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        userScrollEnabled = false,
                    ) {
                        items(entries, key = { it.name + it.url }) { site ->
                            SiteTile(
                                site = site,
                                onOpen = { onOpen(site.url) },
                                onRemove = {
                                    sites = sites.filterNot { it.url == site.url && it.name == site.name }
                                    settings.sites = sites
                                },
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    settings.resetSites()
                    sites = settings.sites
                }) { Text("Reset to the built-in list") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (adding) {
        AddSiteDialog(
            onDismiss = { adding = false },
            onAdd = { name, url ->
                sites = sites + Site(name, url, "Custom")
                settings.sites = sites
                adding = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteTile(site: Site, onOpen: () -> Unit, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.height(54.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                site.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { confirming = true }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove ${site.name}?") },
            text = { Text("It can be added back, or brought back with Reset.") },
            confirmButton = {
                TextButton(onClick = { confirming = false; onRemove() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun AddSiteDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a site") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Address") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    val full = if (url.startsWith("http")) url else "https://$url"
                    onAdd(name.trim(), full.trim())
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A plain browser with one extra button. Whatever page is open when Grab is
 * tapped is handed to the engine, which works out what video is on it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(startUrl: String, onClose: () -> Unit, onGrab: (String) -> Unit) {
    var currentUrl by remember { mutableStateOf(startUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else onClose()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                currentUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onGrab(currentUrl) },
                enabled = currentUrl.startsWith("http"),
            ) { Text("Grab") }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, url: String?, icon: Bitmap?) {
                            if (url != null) currentUrl = url
                            canGoBack = v?.canGoBack() ?: false
                        }

                        override fun doUpdateVisitedHistory(
                            v: WebView?,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            // Sites that swap pages without a reload -- most of
                            // them now -- only announce it here.
                            if (url != null) currentUrl = url
                            canGoBack = v?.canGoBack() ?: false
                        }
                    }
                    loadUrl(startUrl)
                    webView = this
                }
            },
        )
    }
}
