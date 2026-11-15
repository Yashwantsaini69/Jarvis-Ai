package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object SystemClockController {

    fun setAlarm(context: Context, atMillis: Long, label: String? = null): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = atMillis }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return try {
            Diagnostics.log("ALARM", "start ACTION_SET_ALARM hour=$hour minute=$minute skipUi=true")
            context.startActivity(intent)
            "Alarm set for ${formatTime(hour, minute)}."
                .also { Diagnostics.log("ALARM", it) }
        } catch (t: Throwable) {
            "Could not set the alarm: ${t.message ?: "no alarm app found"}"
                .also { Diagnostics.log("ALARM", it) }
        }
    }

    fun setTimer(context: Context, seconds: Int, label: String? = null): String {
        val length = seconds.coerceIn(1, 86400)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, length)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return try {
            Diagnostics.log("ALARM", "start ACTION_SET_TIMER seconds=$length skipUi=true")
            context.startActivity(intent)
            "Timer set for ${formatDuration(length)}."
                .also { Diagnostics.log("ALARM", it) }
        } catch (t: Throwable) {
            "Could not set the timer: ${t.message ?: "no clock app found"}"
                .also { Diagnostics.log("ALARM", it) }
        }
    }

    fun showAlarms(context: Context): String = start(
        context,
        Intent(AlarmClock.ACTION_SHOW_ALARMS),
        "Alarms opened.",
        "Could not open alarms."
    )

    fun showTimers(context: Context): String = start(
        context,
        Intent(AlarmClock.ACTION_SHOW_TIMERS),
        "Timers opened.",
        "Could not open timers."
    )

    fun dismissAlarm(context: Context, atMillis: Long? = null): String {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (atMillis != null) {
            val calendar = Calendar.getInstance().apply { timeInMillis = atMillis }
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            intent.putExtra(AlarmClock.EXTRA_HOUR, hour)
            intent.putExtra(AlarmClock.EXTRA_MINUTES, calendar.get(Calendar.MINUTE))
            intent.putExtra(AlarmClock.EXTRA_IS_PM, hour >= 12)
        } else {
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        return try {
            Diagnostics.log(
                "ALARM",
                "start ACTION_DISMISS_ALARM at=${atMillis ?: "next"} skipUi=true"
            )
            context.startActivity(intent)
            "Alarm delete request sent."
                .also { Diagnostics.log("ALARM", it) }
        } catch (t: Throwable) {
            "Could not dismiss the alarm: ${t.message ?: "no alarm app found"}"
                .also { Diagnostics.log("ALARM", it) }
        }
    }

    fun parseDurationSeconds(raw: String): Int? {
        val tokens = VoiceReminderParser.tokenize(VoiceReminderParser.normalizeNumberWords(raw))
        var total = 0L
        var found = false
        var index = 0
        while (index < tokens.size - 1) {
            val number = tokens[index].toIntOrNull()
            val unit = VoiceReminderParser.unitSeconds[tokens[index + 1]]
            if (number != null && number > 0 && unit != null) {
                total += number * unit
                found = true
                index += 2
            } else {
                index++
            }
        }
        if (!found || total <= 0L) return null
        return total.coerceAtMost(86400L).toInt()
    }

    private fun start(
        context: Context,
        intent: Intent,
        success: String,
        failure: String
    ): String = try {
        Diagnostics.log("ALARM", "start ${intent.action}")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        success.also { Diagnostics.log("ALARM", it) }
    } catch (t: Throwable) {
        "$failure ${t.message ?: ""}".trim().also { Diagnostics.log("ALARM", it) }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("$hours hour" + if (hours == 1) "" else "s")
        if (minutes > 0) parts.add("$minutes minute" + if (minutes == 1) "" else "s")
        if (secs > 0 && hours == 0) parts.add("$secs second" + if (secs == 1) "" else "s")
        return parts.joinToString(" ")
    }
}
