package com.mykungfu.mvtagger

import android.content.Intent
import android.os.Bundle
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
    }

    /**
     * Hands the file to whatever the phone already plays video with. The app
     * deliberately has no player of its own -- there are good ones installed
     * already, and one more would be the least interesting part of this.
     */
    private fun openInAnotherApp(item: Item) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Play with")) }
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
