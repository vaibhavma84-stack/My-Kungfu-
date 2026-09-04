package com.mykungfu.mvtagger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the last run died, show why rather than starting up and, very
        // likely, dying the same way again.
        CrashLog.read(this)?.let {
            showError(it)
            return
        }

        try {
            setContent {
                MvTaggerTheme {
                    val state by viewModel.state.collectAsState()
                    AppScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenExternally = ::openInAnotherApp,
                    )
                }
            }
        } catch (t: Throwable) {
            // Composition failed. Record it and fall back to plain Android
            // views, which cannot depend on whatever just broke.
            CrashLog.save(this, t, "Failed while building the screen.")
            showError(CrashLog.read(this) ?: t.stackTraceToString())
        }
    }

    /**
     * The error screen, built without Compose on purpose: if Compose is what
     * failed, a Compose error screen would fail too.
     */
    private fun showError(text: String) {
        val dark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val background = if (dark) AndroidColor.parseColor("#101418") else AndroidColor.WHITE
        val foreground = if (dark) AndroidColor.parseColor("#E2E8F0") else AndroidColor.parseColor("#1A1C1E")

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
            setBackgroundColor(background)
        }

        column.addView(TextView(this).apply {
            setText("MV Tagger could not start")
            textSize = 20f
            setTextColor(foreground)
            setTypeface(null, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            setText("Copy this and send it over — it says exactly what went wrong.")
            textSize = 14f
            setTextColor(foreground)
            setPadding(0, 16, 0, 24)
        })
        column.addView(Button(this).apply {
            setText("Copy the error")
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MV Tagger crash", text))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        column.addView(Button(this).apply {
            setText("Clear and try again")
            setOnClickListener {
                CrashLog.clear(this@MainActivity)
                recreate()
            }
        })
        column.addView(TextView(this).apply {
            setText(text)
            textSize = 11f
            setTextColor(foreground)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(background)
            addView(column)
        })
    }

    /**
     * Hands the file to whatever the phone already plays video with. The app
     * deliberately has no player of its own -- there are good ones installed
     * already, and one more would be the least interesting part of this.
     *
     * [mimeType] is the specific type for the file rather than a bare
     * `video/*`: a player that declares only `video/x-matroska` will not offer
     * itself for the vague one, so being specific is what makes the right apps
     * appear in the chooser. If nothing answers the specific type -- some
     * players list only `video/*` -- it is worth asking again the vague way
     * before telling the user there is nothing to open it with.
     *
     * The read grant rides along on the intent, so the player can open a file
     * in a folder it was never given access to itself.
     */
    private fun openInAnotherApp(uri: android.net.Uri, mimeType: String) {
        if (launchViewer(uri, mimeType)) return
        if (mimeType != "video/*" && launchViewer(uri, "video/*")) return
        Toast.makeText(
            this,
            "No app on this phone offered to open that file.",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** Returns false when the chooser could not be started, rather than failing quietly. */
    private fun launchViewer(uri: android.net.Uri, mimeType: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) return false
        return try {
            startActivity(Intent.createChooser(intent, "Play with"))
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        } catch (t: Throwable) {
            // A player that is installed but refuses the handover: say what
            // happened, because a tap that does nothing looks like a bug here.
            Toast.makeText(this, "Could not open it: " + t.message, Toast.LENGTH_LONG).show()
            true
        }
    }
}

/**
 * A deliberately plain palette. The app is a working tool for a folder of
 * files, and the useful colour in it is the artwork.
 */
@Composable
fun MvTaggerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF9FCAFF),
            secondary = Color(0xFFB9C7DA),
            tertiary = Color(0xFFD6BEE4),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF23548F),
            secondary = Color(0xFF535F70),
            tertiary = Color(0xFF6B5778),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
