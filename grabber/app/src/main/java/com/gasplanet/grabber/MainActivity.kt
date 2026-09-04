package com.gasplanet.grabber

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gasplanet.grabber.ui.AppRoot
import com.gasplanet.grabber.ui.GrabberTheme

class MainActivity : ComponentActivity() {

    private var sharedUrl by mutableStateOf<String?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val askStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Downloads.load(this)
        requestPermissions()
        sharedUrl = urlFrom(intent)

        setContent {
            GrabberTheme {
                AppRoot(
                    incomingUrl = sharedUrl,
                    onIncomingConsumed = { sharedUrl = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        urlFrom(intent)?.let { sharedUrl = it }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Without this the progress notification is silently suppressed,
            // and a long download gives no sign it is still going.
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Only older Android needs permission to write into Movies.
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /**
     * A share from YouTube arrives as a sentence with a link somewhere inside
     * it, not as a bare URL, so the link is picked out of whatever text came.
     */
    private fun urlFrom(intent: Intent?): String? {
        if (intent == null) return null
        val candidate = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return firstUrlIn(candidate)
    }

    companion object {
        private val URL_PATTERN = Regex("""https?://\S+""")

        fun firstUrlIn(text: String): String? =
            URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
    }
}
