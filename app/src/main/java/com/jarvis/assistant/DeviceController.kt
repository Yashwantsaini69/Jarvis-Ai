package com.jarvis.assistant

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.KeyEvent
import org.json.JSONObject
import kotlin.math.roundToInt

object DeviceController {

    @Volatile
    private var torchOn = false

    fun handle(context: Context, name: String, args: JSONObject): String = when (name) {
        "open_app" -> openApp(context, args.optString("app"))
        "open_url" -> openUrl(context, args.optString("url"))
        else -> "Unknown device action: $name"
    }

    private val settingsHints = listOf("phone", "system", "android", "device", "mobile", "फोन", "सिस्टम")

    private val knownPackages = listOf(
        listOf("whatsapp", "whats app", "व्हाट्सएप", "व्हाट्सऐप") to "com.whatsapp",
        listOf("youtube music", "yt music", "यूट्यूब म्यूजिक") to "com.google.android.apps.youtube.music",
        listOf("youtube", "yt", "यूट्यूब") to "com.google.android.youtube",
        listOf("chrome", "क्रोम") to "com.android.chrome",
        listOf("instagram", "insta", "इंस्टाग्राम") to "com.instagram.android",
        listOf("facebook", "fb", "फेसबुक") to "com.facebook.katana",
        listOf("telegram", "टेलीग्राम") to "org.telegram.messenger",
        listOf("gmail") to "com.google.android.gm",
        listOf("google maps", "maps", "map", "नक्शा") to "com.google.android.apps.maps",
        listOf("photos", "gallery") to "com.google.android.apps.photos",
        listOf("spotify") to "com.spotify.music",
        listOf("netflix") to "com.netflix.mediaclient",
        listOf("dialer", "phone app") to "com.google.android.dialer",
        listOf("contacts", "contact", "संपर्क", "कॉन्टैक्ट") to "com.google.android.contacts",
        listOf("camera", "कैमरा") to "com.android.camera",
        listOf("calculator", "calc") to "com.google.android.calculator",
        listOf("messages", "sms") to "com.google.android.apps.messaging"
    )

    fun openApp(context: Context, appName: String): String {
        val query = appName.trim().lowercase()
        if (query.isEmpty()) return "No app name was given."

        if (query.contains("setting")) {
            val wantsPhone = settingsHints.any { query.contains(it) }
            if (wantsPhone) return openSystemSettings(context)
            return "App settings are handled inside Jarvis."
        }

        val pm = context.packageManager

        if (query in setOf("phone", "dialer", "call", "फोन", "डायलर", "कॉल")) {
            return openDialer(context, pm, appName)
        }

        if (query.contains('.')) {
            launchPackage(context, pm, query, appName)?.let { return it }
        }

        val known = knownPackages.firstOrNull { (keywords, _) ->
            keywords.any { query == it || query.contains(it) }
        }?.second
        if (known != null) {
            launchPackage(context, pm, known, appName)?.let { return it }
        }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
        var best = -1
        var bestScore = 0
        for (i in apps.indices) {
            val info = apps[i]
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            val score = when {
                label == query || pkg == query -> 100
                label.startsWith(query) || pkg.startsWith(query) -> 80
                label.contains(query) -> 60
                query.length >= 4 && query.contains(label) && label.length >= 4 -> 50
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return "App \"$appName\" is not installed or was not found."
        val info = apps[best]
        val label = info.loadLabel(pm).toString()
        val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            "Opened $label."
        } catch (t: Throwable) {
            "Could not open $label: ${t.message}"
        }
    }

    private fun openDialer(
        context: Context,
        pm: android.content.pm.PackageManager,
        requested: String
    ): String {
        val candidates = mutableListOf<String>()
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        } catch (_: Throwable) {
        }
        val dialIntent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            dialIntent.resolveActivity(pm)?.packageName?.let { candidates.add(it) }
        } catch (_: Throwable) {
        }
        candidates.addAll(
            listOf(
                "com.google.android.dialer",
                "com.android.phone",
                "com.xiaomi.phone",
                "com.android.dialer"
            )
        )
        for (pkg in candidates.distinct()) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                Diagnostics.log("DEVICE", "opened dialer pkg=$pkg")
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Throwable) {
                    requested
                }
                return "Opened $label."
            } catch (_: Throwable) {
            }
        }
        return try {
            context.startActivity(dialIntent)
            Diagnostics.log("DEVICE", "opened dialer via ACTION_DIAL")
            "Opened dialer."
        } catch (t: Throwable) {
            "Could not open the dialer: ${t.message ?: "not available"}"
        }
    }

    private fun launchPackage(
        context: Context,
        pm: android.content.pm.PackageManager,
        packageName: String,
        requested: String
    ): String? {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Throwable) {
            requested
        }
        return try {
            context.startActivity(intent)
            "Opened $label."
        } catch (t: Throwable) {
            "Could not open $label: ${t.message}"
        }
    }

    fun openSystemSettings(context: Context): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Phone settings opened."
        } catch (t: Throwable) {
            "Could not open phone settings: ${t.message}"
        }
    }

    fun openUrl(context: Context, rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.isEmpty()) return "No URL or search was given."
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(' ') || !url.contains('.')) {
                "https://www.google.com/search?q=" + Uri.encode(url)
            } else {
                "https://$url"
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Opened $url."
        } catch (t: Throwable) {
            "Could not open the browser: ${t.message}"
        }
    }

    fun setBluetooth(context: Context, enable: Boolean): String {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        return try {
            if (adapter == null) return openBluetoothPanel(context)
            if (adapter.isEnabled == enable) return "Bluetooth is already ${stateWord(enable)}."
            @Suppress("DEPRECATION")
            val ok = if (enable) adapter.enable() else adapter.disable()
            if (ok) "Bluetooth turned ${stateWord(enable)}." else openBluetoothPanel(context)
        } catch (_: Throwable) {
            openBluetoothPanel(context)
        }
    }

    fun setWifi(context: Context, enable: Boolean): String {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return try {
            if (manager == null) return openWifiPanel(context)
            if (manager.isWifiEnabled == enable) return "WiFi is already ${stateWord(enable)}."
            @Suppress("DEPRECATION")
            val ok = manager.setWifiEnabled(enable)
            if (ok) "WiFi turned ${stateWord(enable)}." else openWifiPanel(context)
        } catch (_: Throwable) {
            openWifiPanel(context)
        }
    }

    private fun openBluetoothPanel(context: Context): String {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Bluetooth controls opened."
        } catch (_: Throwable) {
            "Could not open Bluetooth controls."
        }
    }

    private fun openWifiPanel(context: Context): String {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "WiFi controls opened."
        } catch (_: Throwable) {
            "Could not open WiFi controls."
        }
    }

    fun setFlashlight(context: Context, enable: Boolean? = null): String {
        Diagnostics.log("DEVICE", "setFlashlight enable=${enable?.toString() ?: "toggle"}")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "Flashlight is not available.".also { Diagnostics.log("DEVICE", it) }
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (t: Throwable) {
            return "Could not find the flashlight: ${t.message ?: "camera unavailable"}"
                .also { Diagnostics.log("DEVICE", it) }
        } ?: return "This device has no flashlight.".also { Diagnostics.log("DEVICE", it) }
        val target = enable ?: !torchOn
        return try {
            manager.setTorchMode(cameraId, target)
            torchOn = target
            (if (target) "Flashlight on." else "Flashlight off.")
                .also { Diagnostics.log("DEVICE", it) }
        } catch (t: SecurityException) {
            "Flashlight permission is not available.".also { Diagnostics.log("DEVICE", it) }
        } catch (t: Throwable) {
            "Could not change the flashlight: ${t.message ?: "camera busy"}"
                .also { Diagnostics.log("DEVICE", it) }
        }
    }

    fun mediaControl(context: Context, action: String): String {
        Diagnostics.log("DEVICE", "mediaControl action=$action")
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Media control is not available.".also { Diagnostics.log("DEVICE", it) }
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        return try {
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            "Media control sent.".also { Diagnostics.log("DEVICE", it) }
        } catch (t: Throwable) {
            "Could not control media: ${t.message ?: "no active player"}"
                .also { Diagnostics.log("DEVICE", it) }
        }
    }

    fun adjustVolume(context: Context, direction: String, percent: Int? = null): String {
        Diagnostics.log("DEVICE", "adjustVolume direction=$direction percent=$percent")
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Volume control is not available.".also { Diagnostics.log("DEVICE", it) }
        return try {
            if (percent != null) {
                val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = (max * percent.coerceIn(0, 100) / 100f)
                    .roundToInt()
                    .coerceIn(0, max)
                manager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    level,
                    AudioManager.FLAG_SHOW_UI
                )
                "Volume set to ${percent.coerceIn(0, 100)}%."
                    .also { Diagnostics.log("DEVICE", it) }
            } else {
                val adjust = when (direction) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    "mute" -> AudioManager.ADJUST_MUTE
                    "unmute" -> AudioManager.ADJUST_UNMUTE
                    else -> null
                } ?: return "Unknown volume action.".also { Diagnostics.log("DEVICE", it) }
                manager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    adjust,
                    AudioManager.FLAG_SHOW_UI
                )
                when (direction) {
                    "up" -> "Volume up."
                    "down" -> "Volume down."
                    "mute" -> "Volume muted."
                    else -> "Volume unmuted."
                }.also { Diagnostics.log("DEVICE", it) }
            }
        } catch (t: Throwable) {
            "Could not change the volume: ${t.message ?: "audio unavailable"}"
                .also { Diagnostics.log("DEVICE", it) }
        }
    }

    private fun stateWord(enable: Boolean): String = if (enable) "on" else "off"
}
