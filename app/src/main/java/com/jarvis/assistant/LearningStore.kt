package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object LearningStore {

    private const val PREFS = "jarvis_learning_v1"
    private const val KEY = "state"

    data class Progress(
        val lesson: Int = 0,
        val level: Int = 1,
        val words: Int = 0,
        val recent: List<String> = emptyList(),
        val weak: List<String> = emptyList(),
        val summary: String = "",
        val next: String = "",
        val updated: Long = 0L
    )

    private val languages = linkedMapOf(
        "bn" to "Bengali",
        "fr" to "French",
        "ru" to "Russian"
    )

    fun displayName(code: String): String = languages[code] ?: "Language"

    fun nativeName(code: String): String = when (code) {
        "bn" -> "বাংলা"
        "fr" -> "Français"
        "ru" -> "Русский"
        else -> ""
    }

    fun normalize(raw: String): String? {
        val t = raw.trim().lowercase()
        if (t.isBlank()) return null
        return when {
            t == "bn" || t.contains("bengali") || t.contains("bangla") ||
                t.contains("বাংলা") || t.contains("बांग्ला") || t.contains("बंगाली") -> "bn"
            t == "fr" || t.contains("french") || t.contains("français") ||
                t.contains("francais") || t.contains("फ्रेंच") || t.contains("फ़्रेंच") -> "fr"
            t == "ru" || t.contains("russian") || t.contains("русск") ||
                t.contains("रशियन") || t.contains("रूसी") -> "ru"
            else -> null
        }
    }

    fun lastLanguage(context: Context): String? {
        val code = state(context).optString("last", "")
        return if (languages.containsKey(code)) code else null
    }

    fun setLast(context: Context, code: String) {
        val state = state(context)
        state.put("last", code)
        write(context, state)
    }

    fun progress(context: Context, code: String): Progress {
        val obj = state(context).optJSONObject(code) ?: return Progress()
        return Progress(
            lesson = obj.optInt("lesson", 0),
            level = obj.optInt("level", 1),
            words = obj.optInt("words", 0),
            recent = readList(obj, "recent"),
            weak = readList(obj, "weak"),
            summary = obj.optString("summary", ""),
            next = obj.optString("next", ""),
            updated = obj.optLong("updated", 0L)
        )
    }

    fun save(
        context: Context,
        code: String,
        lesson: Int,
        summary: String,
        nextPlan: String,
        weakWords: String,
        newWords: String
    ) {
        val state = state(context)
        val previous = progress(context, code)
        val added = split(newWords)
        val recent = (previous.recent + added)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(12)
        val weak = split(weakWords).ifEmpty { previous.weak }
        val obj = JSONObject()
            .put("lesson", maxOf(lesson, previous.lesson))
            .put("level", previous.level)
            .put("words", previous.words + added.size)
            .put("recent", JSONArray(recent))
            .put("weak", JSONArray(weak))
            .put("summary", summary.ifBlank { previous.summary })
            .put("next", nextPlan.ifBlank { previous.next })
            .put("updated", System.currentTimeMillis())
        state.put(code, obj)
        state.put("last", code)
        write(context, state)
    }

    fun promptBlock(context: Context): String {
        val state = state(context)
        val parts = ArrayList<String>()
        for (code in languages.keys) {
            val p = progress(context, code)
            parts += if (p.lesson <= 0) {
                "${languages[code]}: not started"
            } else {
                "${languages[code]}: lesson ${p.lesson} done, ${p.words} words"
            }
        }
        val last = lastLanguage(context)
        val lastLine = if (last == null) {
            "Last language: none."
        } else {
            "Last language: ${languages[last]}."
        }
        return "LANGUAGE PROGRESS (persisted): $lastLine ${parts.joinToString(". ")}. " +
            "Languages available: Bengali, French, Russian. When the user asks to learn, " +
            "start or continue a language, call start_lesson."
    }

    fun tutorContext(context: Context, code: String): String {
        val p = progress(context, code)
        val nextLesson = p.lesson + 1
        val recent = p.recent.takeLast(6).joinToString(", ").ifBlank { "none yet" }
        val weak = p.weak.take(5).joinToString(", ").ifBlank { "none yet" }
        val summary = p.summary.ifBlank { "no previous summary" }
        val plan = p.next.ifBlank { "start from basics" }
        return "LEARNER PROGRESS — ${displayName(code)}: level ${p.level}, " +
            "${p.lesson} lessons completed, ${p.words} words learned. " +
            "Recent words: $recent. Weak words: $weak. Last summary: $summary. " +
            "Next plan: $plan. Start lesson $nextLesson now. Revise one or two weak words, " +
            "teach 5 new words one at a time with show_word, then run a short quiz. " +
            "Speak slowly and keep each sentence short."
    }

    private fun state(context: Context): JSONObject {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun write(context: Context, state: JSONObject) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, state.toString())
            .commit()
    }

    private fun readList(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) list += value
        }
        return list
    }

    private fun split(raw: String): List<String> =
        raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
