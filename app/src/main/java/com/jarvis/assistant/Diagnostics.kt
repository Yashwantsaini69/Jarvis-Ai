package com.jarvis.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object Diagnostics {
    private const val MAX_LINES = 1000
    private const val MIC_SUMMARY_MS = 1000L
    private const val EXPORT_DIR = "A_Ai.updates/Diagnostics"

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

    fun export(context: Context): String {
        val content = dump(context)
        val stamp = synchronized(timeFormat) {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }
        val fileName = "jarvis-diag-$stamp.txt"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, fileName, content)
            } else {
                saveLegacy(fileName, content)
            }
        } catch (t: Throwable) {
            "Export failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, content: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_DIR"
            )
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "Export failed: could not create file"
        val written = try {
            resolver.openOutputStream(uri)?.use { out -> out.write(content.toByteArray()) }
            true
        } catch (t: Throwable) {
            false
        }
        if (!written) {
            resolver.delete(uri, null, null)
            return "Export failed: could not write file"
        }
        return "Saved: Download/$EXPORT_DIR/$fileName"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(fileName: String, content: String): String {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(root, EXPORT_DIR)
        if (!dir.exists() && !dir.mkdirs()) return "Export failed: cannot create folder"
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(content.toByteArray()) }
        return "Saved: ${file.absolutePath}"
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
