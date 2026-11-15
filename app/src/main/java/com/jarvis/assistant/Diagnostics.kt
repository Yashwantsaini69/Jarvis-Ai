package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object Diagnostics {
    private const val MAX_LINES = 1000
    private const val MIC_SUMMARY_MS = 1000L

    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var micSource = "unknown"

    private var startedAt = System.currentTimeMillis()
    private var lastMicAt = 0L
    private var micPeak = 0f
    private var micChunks = 0

    fun log(tag: String, message: String) {
        val line = "${time()} [$tag] $message"
        synchronized(lock) {
            appendLocked(line)
        }
        Log.d("JarvisDiag", line)
    }

    fun setMicSource(source: String) {
        micSource = source
        log("MIC", "active source=$source")
    }

    fun micLevel(level: Float) {
        val now = SystemClock.elapsedRealtime()
        var line: String? = null
        synchronized(lock) {
            if (level > micPeak) micPeak = level
            micChunks++
            if (now - lastMicAt >= MIC_SUMMARY_MS) {
                val peak = micPeak
                val chunks = micChunks
                lastMicAt = now
                micPeak = 0f
                micChunks = 0
                line = "${time()} [MIC] peak=${String.format(Locale.US, "%.2f", peak)} chunks=$chunks"
                appendLocked(line)
            }
        }
        if (line != null) Log.d("JarvisDiag", line)
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            startedAt = System.currentTimeMillis()
        }
        log("DIAG", "log cleared")
    }

    fun dump(context: Context): String {
        val snapshot: List<String>
        val source: String
        synchronized(lock) {
            snapshot = lines.toList()
            source = micSource
        }
        val packageInfo = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Throwable) {
            null
        }
        @Suppress("DEPRECATION")
        val versionCode = packageInfo?.versionCode ?: 0
        val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(context)
        return buildString {
            append("JARVIS DIAGNOSTICS\n")
            append("generated=").append(time()).append('\n')
            append("app=").append(packageInfo?.versionName ?: "?").append(" (")
                .append(versionCode).append(")\n")
            append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n')
            append("android=").append(Build.VERSION.RELEASE).append(" sdk=")
                .append(Build.VERSION.SDK_INT).append('\n')
            append("micPermission=").append(micGranted).append('\n')
            append("overlayPermission=").append(overlay).append('\n')
            append("micSource=").append(source).append('\n')
            append("logStarted=").append(time(startedAt)).append('\n')
            append("logLines=").append(snapshot.size).append('\n')
            append("--- LOG ---\n")
            snapshot.forEach { append(it).append('\n') }
        }
    }

    private fun appendLocked(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    private fun time(): String = synchronized(timeFormat) {
        timeFormat.format(Date())
    }

    private fun time(millis: Long): String = synchronized(timeFormat) {
        timeFormat.format(Date(millis))
    }
}
