package com.jarvis.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class ReminderAlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getLongExtra(EXTRA_NOTE_ID, 0L) ?: 0L
        if (id <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        NoteStore.init(this)
        val note = NoteStore.get(id)
        if (note == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        ReminderNotifier.ensureChannels(this)
        startForeground(
            ReminderNotifier.notificationId(note.id),
            ReminderNotifier.build(this, note, ReminderNotifier.SILENT_CHANNEL_ID, true)
        )
        playAlarm()
        startVibration()
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, ALARM_DURATION_MS)
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jarvis:ReminderAlarm"
            ).apply { acquire(ALARM_DURATION_MS + 5_000L) }
        } catch (_: Throwable) {
        }
    }

    private fun playAlarm() {
        stopPlayer()
        try {
            val descriptor = resources.openRawResourceFd(R.raw.reminder_alarm)
                ?: return playAlarmFallback()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            descriptor.use {
                mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            mp.isLooping = true
            mp.setVolume(1f, 1f)
            mp.prepare()
            mp.start()
            player = mp
        } catch (_: Throwable) {
            playAlarmFallback()
        }
    }

    private fun playAlarmFallback() {
        try {
            val mp = MediaPlayer.create(this, R.raw.reminder_alarm) ?: return
            mp.isLooping = true
            mp.setVolume(1f, 1f)
            mp.start()
            player = mp
        } catch (_: Throwable) {
        }
    }

    private fun startVibration() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = v
            val pattern = longArrayOf(0L, 700L, 350L, 700L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopPlayer() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        stopPlayer()
        stopVibration()
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.assistant.STOP_REMINDER_ALARM"
        const val EXTRA_NOTE_ID = "note_id"
        private const val ALARM_DURATION_MS = 30_000L

        fun start(context: Context, noteId: Long) {
            val intent = Intent(context, ReminderAlarmService::class.java)
                .putExtra(EXTRA_NOTE_ID, noteId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                NoteStore.init(context)
                NoteStore.get(noteId)?.let { ReminderNotifier.show(context, it) }
            }
        }
    }
}
