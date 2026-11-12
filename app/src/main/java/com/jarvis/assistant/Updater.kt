package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object Updater {
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
        val force: Boolean,
        val minVersionCode: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    fun check(context: Context, callback: (UpdateInfo?, String?) -> Unit) {
        Thread {
            var info: UpdateInfo? = null
            var error: String? = null
            try {
                val request = Request.Builder().url(UpdateConfig.MANIFEST_URL).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Update check failed (${response.code})")
                    }
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val remote = UpdateInfo(
                        versionCode = json.optInt("versionCode", 0),
                        versionName = json.optString("versionName", ""),
                        apkUrl = json.optString("apkUrl", ""),
                        sha256 = json.optString("sha256", ""),
                        notes = json.optString("notes", ""),
                        force = json.optBoolean("force", false),
                        minVersionCode = json.optInt("minVersionCode", 0)
                    )
                    val current = currentVersionCode(context.applicationContext)
                    val force = remote.force || (remote.minVersionCode > 0 && current < remote.minVersionCode)
                    info = if (remote.versionCode > current && remote.apkUrl.isNotBlank()) {
                        remote.copy(force = force)
                    } else {
                        null
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: "Update check failed"
            }
            handler.post { callback(info, error) }
        }.start()
    }

    fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            var done: File? = null
            var error: String? = null
            try {
                val dir = File(context.cacheDir, "updates")
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("Could not prepare update folder")
                }
                val safeName = if (info.versionName.isBlank()) "latest" else info.versionName
                val target = File(dir, "Jarvis-$safeName.apk")
                val request = Request.Builder().url(info.apkUrl).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed (${response.code})")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty download")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                received += read
                                if (total > 0) {
                                    val percent = ((received * 100) / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        handler.post { onProgress(percent) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (info.sha256.isNotBlank()) {
                    val actual = sha256(target)
                    if (!actual.equals(info.sha256, ignoreCase = true)) {
                        target.delete()
                        throw IllegalStateException("Downloaded update failed verification")
                    }
                }
                done = target
            } catch (t: Throwable) {
                error = t.message ?: "Download failed"
            }
            val result = done
            val message = error
            handler.post {
                if (result != null) onDone(result) else onError(message ?: "Download failed")
            }
        }.start()
    }

    fun install(context: Context, apk: File): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
                return "Allow installs from Jarvis, then check for the update again"
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        } catch (t: Throwable) {
            t.message ?: "Could not open the installer"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(String.format("%02x", b))
        }
        return out.toString()
    }
}
