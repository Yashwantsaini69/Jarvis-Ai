package com.jarvis.assistant

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

object DeviceController {

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
                label.contains(query) || pkg.contains(query) -> 60
                query.contains(label) && label.length >= 3 -> 50
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

    private fun stateWord(enable: Boolean): String = if (enable) "on" else "off"
}
