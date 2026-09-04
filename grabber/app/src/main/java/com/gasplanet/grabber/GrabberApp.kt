package com.gasplanet.grabber

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GrabberApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Downloads.load(this)
        // Unpacking Python and ffmpeg takes a few seconds the first time the
        // app is ever opened. Starting it here means it is usually finished
        // before the first link is pasted.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { Engine.ensureInit(this@GrabberApp) }
        }
    }
}
