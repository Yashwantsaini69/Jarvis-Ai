package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REMIND) return
        val id = intent.getLongExtra(EXTRA_NOTE_ID, 0L)
        if (id <= 0L) return
        NoteStore.init(context)
        val note = NoteStore.get(id) ?: return
        NoteStore.clearReminder(id)
        ReminderBus.dispatch(note)
        ReminderAlarmService.start(context, note.id)
    }

    companion object {
        const val ACTION_REMIND = "com.jarvis.assistant.NOTE_REMINDER"
        const val EXTRA_NOTE_ID = "note_id"
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        ReminderNotifier.ensureChannels(context)
        ReminderScheduler.rescheduleAll(context)
    }
}
