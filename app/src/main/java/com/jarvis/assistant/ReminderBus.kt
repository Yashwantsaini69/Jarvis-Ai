package com.jarvis.assistant

import java.util.concurrent.CopyOnWriteArrayList

object ReminderBus {
    fun interface Listener {
        fun onReminder(note: NoteStore.Note)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun dispatch(note: NoteStore.Note) {
        listeners.toList().forEach { it.onReminder(note) }
    }
}
