package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class JarvisSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::LiveSession").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live voice session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Jarvis Gemini Live session running in the background"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(muted: Boolean = false): Notification =
        buildNotification(this, muted)

    companion object {
        private const val CHANNEL_ID = "jarvis_live_session"
        private const val NOTIFICATION_ID = 4310
        private const val ACTION_STOP = "com.jarvis.assistant.STOP_SESSION"

        fun setMuted(context: Context, muted: Boolean) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            try {
                manager.notify(NOTIFICATION_ID, buildNotification(context, muted))
            } catch (_: Throwable) {
            }
        }

        private fun buildNotification(context: Context, muted: Boolean): Notification {
            val open = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            return builder
                .setSmallIcon(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
                .setContentTitle(if (muted) "Jarvis is muted" else "Jarvis is live")
                .setContentText(
                    if (muted) "Say \"Hey Jarvis\" to unmute"
                    else "Voice link active — tap to return"
                )
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }

        fun start(context: Context) {
            val intent = Intent(context, JarvisSessionService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, JarvisSessionService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
