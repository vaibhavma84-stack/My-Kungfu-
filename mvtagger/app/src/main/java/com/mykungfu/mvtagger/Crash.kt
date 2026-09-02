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

    /**
     * First line of the file, so a crash left by an older build can be told
     * apart from one this build actually caused.
     */
    private const val VERSION_MARKER = "crashVersionCode="

    private fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // longVersionCode is API 28; this app runs back to 24.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(-1L)

    fun save(context: Context, throwable: Throwable, note: String? = null) {
        runCatching {
            val text = StringWriter().also { writer ->
                writer.append(VERSION_MARKER).append(versionCode(context).toString()).append('\n')
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

    /**
     * The last crash, but only if this build caused it.
     *
     * An upgrade is the usual way a crash gets fixed, so a report left by the
     * previous version is exactly the one that should no longer be shown --
     * otherwise the fixed build opens on the error screen from the broken one
     * and looks just as dead. Stale reports are discarded on sight.
     */
    fun read(context: Context): String? = runCatching {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return@runCatching null
        val text = f.readText()

        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        if (!firstLine.startsWith(VERSION_MARKER)) {
            f.delete()
            return@runCatching null
        }
        val from = firstLine.removePrefix(VERSION_MARKER).trim().toLongOrNull()
        if (from != versionCode(context)) {
            f.delete()
            return@runCatching null
        }
        text.substringAfter('\n').trim().ifBlank { null }
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
