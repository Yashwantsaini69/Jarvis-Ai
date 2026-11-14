package com.jarvis.assistant

import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors

object MemoryBrain {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-memory").apply { isDaemon = true }
    }

    private val seen = Collections.synchronizedSet(HashSet<Long>())

    private const val SYSTEM = """You are the long-term memory extractor for a personal AI assistant.
Read the user's message and extract durable facts worth remembering across conversations.
Return ONLY a JSON object of this exact shape:
{"facts":[{"type":"identity|location|work|education|preference|project|goal|note","key":"short lowercase slug","value":"concise fact (max 160 chars)","importance":1-5}]}

Rules:
- Save only durable facts about the user: name, city, job, studies, goals/aspirations, likes/dislikes, relationships, projects, skills, routines, important dates, strong preferences.
- For identity use key "name" or "birthday". For preference use "like:<thing>" or "dislike:<thing>". For goal use "goal:<slug>". For project use "project:<slug>". For note use "note:<slug>".
- Ignore questions, greetings, commands, small talk, temporary feelings or states, and anything the user did not state as a fact.
- Never store passwords, OTPs, PINs, card/account numbers, API keys, Aadhaar or any secrets.
- If nothing is worth remembering, return {"facts":[]}.
- Output JSON only. No markdown, no explanation."""

    fun observe(messageId: Long, text: String) {
        val clean = text.trim()
        if (clean.length < 12) return
        if (JarvisPrefs.groqApiKey.isBlank()) return
        if (!seen.add(messageId)) return
        executor.execute {
            try {
                val facts = request(clean)
                if (facts.isNotEmpty()) MemoryStore.saveEntries(facts)
            } catch (_: Throwable) {
            }
        }
    }

    private fun request(text: String): List<MemoryExtractor.MemoryEntry> {
        val key = JarvisPrefs.groqApiKey
        val models = buildList {
            val saved = JarvisPrefs.groqModel.trim()
            if (saved.isNotBlank()) add(saved)
            add("openai/gpt-oss-20b")
            add(JarvisPrefs.PREFERRED_GROQ_MODEL)
        }.distinct()
        for (model in models) {
            val raw = GroqClient.complete(key, model, SYSTEM, text) ?: continue
            val parsed = parse(raw)
            if (parsed != null) return parsed
        }
        return emptyList()
    }

    private fun parse(raw: String): List<MemoryExtractor.MemoryEntry>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = try {
            JSONObject(raw.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        val array = json.optJSONArray("facts") ?: return emptyList()
        val out = mutableListOf<MemoryExtractor.MemoryEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            var type = obj.optString("type", "note").trim().lowercase()
            if (!MemoryStore.allowedType(type)) type = "note"
            val value = normalizeFact(obj.optString("value", "")).take(200)
            if (value.length < 2) continue
            if (MemoryExtractor.isSensitiveValue(value)) continue
            var keyText = obj.optString("key", "").trim().lowercase()
                .replace(Regex("[^a-z0-9:_-]+"), "-")
                .trim('-')
                .take(80)
            if (keyText.isBlank()) keyText = MemoryExtractor.slugify(value)
            val fullKey = when {
                keyText.contains(':') -> keyText
                type == "preference" -> "like:$keyText"
                type == "goal" -> "goal:$keyText"
                type == "project" -> "project:$keyText"
                type == "note" -> "note:$keyText"
                else -> keyText
            }
            val importance = obj.optInt("importance", 3).coerceIn(1, 5)
            out.add(MemoryExtractor.MemoryEntry(type, fullKey, value, importance, 0.82))
        }
        return out.distinctBy { it.type + "|" + it.key }
    }

    private fun normalizeFact(raw: String): String = raw.trim()
        .replace(Regex("(?i)^(?:the\\s+)?user\\s+(?:is|was|has|likes|loves|wants|studies|works|prefers)\\s+"), "")
        .replace(Regex("(?i)^(?:i\\s+am|i'm|i\\s+like|i\\s+love|i\\s+prefer)\\s+"), "")
        .replace(Regex("(?i)^preparing\\s+for\\s+"), "")
        .replace(Regex("(?i)^(?:an?|the)\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', ',', '!', '?', ';', ':')
}
