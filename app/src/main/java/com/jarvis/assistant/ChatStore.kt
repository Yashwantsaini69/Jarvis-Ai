package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object ChatStore {
    enum class Role { USER, ASSISTANT }

    data class Message(
        val id: Long,
        val role: Role,
        var text: String,
        var final: Boolean,
        val time: Long
    )

    fun interface Listener {
        fun onChanged()
    }

    private const val PREFS = "jarvis_history"
    private const val KEY = "messages"

    private val messages = mutableListOf<Message>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var nextId = 1L
    private var userId: Long? = null
    private var assistantId: Long? = null
    private var userFinal = StringBuilder()
    private var userInterim = ""
    private var appContext: Context? = null

    @Volatile
    var sessionActive: Boolean = false
        set(value) {
            field = value
            notifyListeners()
        }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            load()
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun snapshot(): List<Message> = synchronized(this) { messages.map { it.copy() } }

    fun latest(): Message? = synchronized(this) {
        messages.lastOrNull { it.text.isNotBlank() }?.copy()
    }

    fun beginSession() {
        synchronized(this) {
            userId = null
            assistantId = null
            userFinal = StringBuilder()
            userInterim = ""
        }
    }

    fun appendUserInterim(text: String) {
        if (text.isBlank()) return
        synchronized(this) {
            if (assistantId != null) {
                finalizeAssistantLocked()
                resetUserLocked()
            }
            userInterim = text
            ensureUserLocked()
            updateUserLocked()
        }
        notifyListeners()
        persist()
    }

    fun appendUserFinal(text: String, finished: Boolean) {
        if (text.isBlank() && !finished) return
        synchronized(this) {
            if (assistantId != null) {
                finalizeAssistantLocked()
                resetUserLocked()
            }
            if (text.isNotEmpty()) userFinal.append(text)
            userInterim = ""
            ensureUserLocked()
            updateUserLocked()
            if (finished) finalizeUserLocked()
        }
        notifyListeners()
        persist()
    }

    fun appendModel(text: String) {
        if (text.isEmpty()) return
        synchronized(this) {
            if (assistantId == null) {
                finalizeUserLocked()
                resetUserLocked()
                val id = nextId++
                messages.add(Message(id, Role.ASSISTANT, "", false, System.currentTimeMillis()))
                assistantId = id
            }
            val msg = messages.firstOrNull { it.id == assistantId }
            if (msg != null) {
                msg.text += text
                msg.final = false
            }
        }
        notifyListeners()
        persist()
    }

    fun assistantDone() {
        synchronized(this) {
            finalizeUserLocked()
            finalizeAssistantLocked()
            resetUserLocked()
        }
        notifyListeners()
        persist()
    }

    fun interrupted() {
        synchronized(this) {
            finalizeAssistantLocked()
            resetUserLocked()
        }
        notifyListeners()
        persist()
    }

    fun clear() {
        synchronized(this) {
            messages.clear()
            userId = null
            assistantId = null
            userFinal = StringBuilder()
            userInterim = ""
            nextId = 1L
        }
        notifyListeners()
        persist()
    }

    private fun ensureUserLocked() {
        if (userId == null) {
            val id = nextId++
            messages.add(Message(id, Role.USER, "", false, System.currentTimeMillis()))
            userId = id
        }
    }

    private fun updateUserLocked() {
        val id = userId ?: return
        val msg = messages.firstOrNull { it.id == id } ?: return
        msg.text = (userFinal.toString() + userInterim).trim()
        msg.final = false
    }

    private fun finalizeUserLocked() {
        val id = userId ?: return
        val msg = messages.firstOrNull { it.id == id }
        if (msg != null && msg.text.isNotBlank()) {
            msg.final = true
        } else if (msg != null) {
            messages.remove(msg)
        }
        userId = null
        userFinal = StringBuilder()
        userInterim = ""
    }

    private fun finalizeAssistantLocked() {
        val id = assistantId ?: return
        val msg = messages.firstOrNull { it.id == id }
        if (msg != null && msg.text.isNotBlank()) {
            msg.final = true
        } else if (msg != null) {
            messages.remove(msg)
        }
        assistantId = null
    }

    private fun resetUserLocked() {
        userId = null
        userFinal = StringBuilder()
        userInterim = ""
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener.onChanged()
            } catch (_: Exception) {
            }
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        val snapshot = snapshot().takeLast(200)
        val array = JSONArray()
        for (msg in snapshot) {
            array.put(
                JSONObject()
                    .put("id", msg.id)
                    .put("role", msg.role.name)
                    .put("text", msg.text)
                    .put("final", msg.final)
                    .put("time", msg.time)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun load() {
        val ctx = appContext ?: return
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val array = JSONArray(raw)
            val loaded = mutableListOf<Message>()
            var maxId = 0L
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                if (id <= 0L) continue
                val role = if (obj.optString("role") == Role.USER.name) Role.USER else Role.ASSISTANT
                val text = obj.optString("text", "")
                if (text.isBlank()) continue
                loaded.add(Message(id, role, text, true, obj.optLong("time", 0L)))
                if (id > maxId) maxId = id
            }
            synchronized(this) {
                messages.clear()
                messages.addAll(loaded)
                nextId = maxId + 1
            }
        } catch (_: Exception) {
        }
    }
}
