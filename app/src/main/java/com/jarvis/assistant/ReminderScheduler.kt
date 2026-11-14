package com.jarvis.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build

object ReminderScheduler {

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.canScheduleExactAlarms()
        } catch (_: Throwable) {
            false
        }
    }

    fun schedule(context: Context, note: NoteStore.Note) {
        if (note.remindAt <= 0L) {
            cancel(context, note.id)
            return
        }
        val manager = try {
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        } catch (_: Throwable) {
            return
        }
        val pending = pendingIntent(context, note.id)
        try {
            if (canScheduleExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.remindAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.remindAt, pending)
            }
        } catch (_: Throwable) {
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, note.remindAt, pending)
            } catch (_: Throwable) {
            }
        }
    }

    fun cancel(context: Context, noteId: Long) {
        val manager = try {
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        } catch (_: Throwable) {
            return
        }
        try {
            manager.cancel(pendingIntent(context, noteId))
        } catch (_: Throwable) {
        }
    }

    fun rescheduleAll(context: Context) {
        NoteStore.init(context)
        NoteStore.upcoming().forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, noteId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
        }
        return PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

object ReminderNotifier {
    private const val CHANNEL_ID = "jarvis_reminders_alarm_v3"
    const val SILENT_CHANNEL_ID = "jarvis_reminders_alarm_v4"
    private const val OLD_CHANNEL_ID = "jarvis_reminders_alarm_v2"
    private const val NOTIFICATION_BASE = 5400

    fun ensureChannel(context: Context) {
        ensureChannels(context)
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val sound = alarmSound(context)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarm = NotificationChannel(
            CHANNEL_ID,
            "Notes and reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alarm-style reminder notifications from your Jarvis notepad"
            setSound(sound, attributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 700L, 350L, 700L)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val silent = NotificationChannel(
            SILENT_CHANNEL_ID,
            "Reminder alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminder notifications while Jarvis plays the alarm sound"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(alarm)
        manager.createNotificationChannel(silent)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
    }

    private fun alarmSound(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.reminder_alarm}")

    fun notificationId(noteId: Long): Int = NOTIFICATION_BASE + noteId.toInt()

    fun build(
        context: Context,
        note: NoteStore.Note,
        channelId: String,
        withStop: Boolean
    ): Notification {
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("open_notes", true)
        }
        val pending = PendingIntent.getActivity(
            context,
            note.id.toInt(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val details = note.title.trim().ifBlank { note.body.trim() }
        val generic = details.isBlank() || details.equals("Reminder", ignoreCase = true)
        val title = if (generic) "Reminder" else "Reminder: $details"
        val body = if (generic) "It's time for your reminder" else "It's time for $details"
        val sound = alarmSound(context)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val silent = channelId == SILENT_CHANNEL_ID
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).apply {
                if (!silent) {
                    setSound(sound)
                    setVibrate(longArrayOf(0L, 700L, 350L, 700L))
                }
            }
        }
        builder
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(!withStop)
            .setOngoing(withStop)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(note.remindAt)
            .setShowWhen(true)
        if (!silent) {
            builder.setSound(sound, attributes)
        }
        if (withStop) {
            val stopIntent = Intent(context, ReminderAlarmService::class.java)
                .setAction(ReminderAlarmService.ACTION_STOP)
            val stopPending = PendingIntent.getService(
                context,
                note.id.toInt(),
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_note),
                    "Stop",
                    stopPending
                ).build()
            )
        }
        return builder.build()
    }

    fun show(context: Context, note: NoteStore.Note) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(notificationId(note.id), build(context, note, CHANNEL_ID, false))
        } catch (_: Throwable) {
        }
    }

    fun showSilent(context: Context, note: NoteStore.Note) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(notificationId(note.id), build(context, note, SILENT_CHANNEL_ID, false))
        } catch (_: Throwable) {
        }
    }
}
