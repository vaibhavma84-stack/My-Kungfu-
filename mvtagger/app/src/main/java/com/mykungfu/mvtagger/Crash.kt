package com.mykungfu.mvtagger

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash so it can be read on the phone.
 *
 * A sideloaded app has no Play Console behind it. When it dies on launch, all
 * the owner sees is "MV Tagger keeps stopping", and the stack trace -- the one
 * thing that would explain it -- is only reachable over adb from a computer.
 *
 * So the trace is written to a file here, and [MainActivity] shows it on the
 * next launch instead of starting normally.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    fun file(context: Context) = File(context.filesDir, FILE)

    fun save(context: Context, throwable: Throwable, note: String? = null) {
        runCatching {
            val text = StringWriter().also { writer ->
                note?.let { writer.append(it).append("\n\n") }
                writer.append("MV Tagger ").append(versionOf(context)).append('\n')
                writer.append("Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT.toString()).append(")\n")
                writer.append(android.os.Build.MANUFACTURER).append(' ')
                    .append(android.os.Build.MODEL).append("\n\n")
                throwable.printStackTrace(PrintWriter(writer))
            }.toString()
            file(context).writeText(text)
        }
    }

    fun read(context: Context): String? = runCatching {
        val f = file(context)
        if (f.exists() && f.length() > 0) f.readText() else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun versionOf(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v" + info.versionName + " (" + info.versionCode + ")"
    }.getOrDefault("version unknown")
}

/**
 * Installs the handler before any of the app's own code runs, so a failure
 * during startup is recorded rather than lost.
 */
class MvTaggerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.save(this, throwable, "Crashed on thread: " + thread.name)
            // Still let Android do its usual thing, so the process dies
            // cleanly rather than being left in a half-torn-down state.
            previous?.uncaughtException(thread, throwable)
        }
    }
}
