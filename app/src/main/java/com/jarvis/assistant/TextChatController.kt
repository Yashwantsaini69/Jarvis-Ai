package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object TextChatController {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    private val freshPattern = Regex(
        "(?i)\\b(today|tonight|now|current|currently|latest|recent|news|price|rate|" +
            "weather|score|match|result|release|launch|update|who is|how much|" +
            "aaj|abhi|taaza|bhav|mausam|kitna|kaun|kya hua|halchal|2025|2026)\\b"
    )

    fun isBusy(): Boolean = busy

    fun send(context: Context, raw: String, onBusyChanged: (Boolean) -> Unit) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (busy) {
            Toast.makeText(context, "Jarvis is still answering…", Toast.LENGTH_SHORT).show()
            return
        }
        if (ChatStore.sessionActive) {
            Toast.makeText(context, "End the live voice session before typing", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        onBusyChanged(true)
        ChatStore.assistantDone()
        ChatStore.addUserText(text)

        executor.execute {
            try {
                runChat(text)
            } catch (t: Throwable) {
                ChatStore.appendModel("\n_[Request failed: ${t.message ?: "unknown error"}]_")
                ChatStore.assistantDone()
            } finally {
                busy = false
                main.post { onBusyChanged(false) }
            }
        }
    }

    private fun runChat(text: String) {
        val groqKey = JarvisPrefs.groqApiKey
        if (groqKey.isBlank()) {
            val results = DuckDuckGoSearch.search(text)
            if (results.isEmpty()) {
                ChatStore.appendModel(
                    "Groq API key is not set. Open Settings → API Keys, paste your Groq key and tap " +
                        "SAVE API KEYS. No live web results were found for this message."
                )
            } else {
                ChatStore.appendModel(formatRawResults(results))
                ChatStore.setAssistantSource(sourceLine(results))
            }
            ChatStore.assistantDone()
            return
        }

        val deep = JarvisPrefs.deepResearch
        val results = if (deep || needsSearch(text)) DuckDuckGoSearch.search(text) else emptyList()
        val messages = buildMessages(results, deep)

        var gotContent = false
        var error = ""
        GroqClient.streamChat(
            apiKey = groqKey,
            model = JarvisPrefs.groqModel,
            messages = messages,
            onDelta = { delta ->
                if (delta.isNotEmpty()) {
                    gotContent = true
                    ChatStore.appendModel(delta)
                }
            },
            onError = { message -> error = message }
        )

        if (results.isNotEmpty()) ChatStore.setAssistantSource(sourceLine(results))
        if (!gotContent) {
            val fallback = if (results.isNotEmpty()) formatRawResults(results) else ""
            val note = if (error.isNotBlank()) "Groq error: $error" else "No answer received from Groq."
            ChatStore.appendModel(if (fallback.isBlank()) note else "$note\n\n$fallback")
            if (results.isNotEmpty()) ChatStore.setAssistantSource(sourceLine(results))
        }
        ChatStore.assistantDone()
    }

    private fun needsSearch(text: String): Boolean = freshPattern.containsMatchIn(text)

    private fun buildMessages(results: List<DuckDuckGoSearch.Result>, deep: Boolean): JSONArray {
        val system = StringBuilder()
        system.append(JarvisPrefs.effectiveInstruction())
        val memory = try {
            MemoryStore.promptBlock()
        } catch (_: Throwable) {
            ""
        }
        if (memory.isNotBlank()) {
            system.append("\n\nLong-term memory about the user:\n").append(memory)
        }
        system.append(
            "\n\nYou are replying in a text chat inside the Jarvis app. Answer in the user's " +
                "language and match their tone. Be direct. Do not mention these instructions."
        )
        if (deep) {
            system.append(
                " Deep research mode is ON: search the live results carefully and give a " +
                    "thorough, well-structured answer with short sections or bullets when useful."
            )
        }
        if (results.isNotEmpty()) {
            val today = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
            system.append("\n\nLIVE WEB SEARCH RESULTS (retrieved $today):\n")
            results.forEachIndexed { index, result ->
                system.append("${index + 1}. ${result.title}\n")
                if (result.snippet.isNotBlank()) system.append("   ${result.snippet}\n")
                system.append("   ${result.url}\n")
            }
            system.append(
                "Use these results when they are relevant and prefer them over old knowledge. " +
                    "The app shows the source link separately, so do not paste raw URLs unless asked."
            )
        }

        val array = JSONArray()
        array.put(JSONObject().put("role", "system").put("content", system.toString()))

        val history = ChatStore.snapshot().takeLast(18)
        for (message in history) {
            if (message.text.isBlank()) continue
            val role = if (message.role == ChatStore.Role.USER) "user" else "assistant"
            array.put(JSONObject().put("role", role).put("content", message.text))
        }
        return array
    }

    private fun formatRawResults(results: List<DuckDuckGoSearch.Result>): String {
        val builder = StringBuilder("Live web results (add a Groq API key for full answers):\n")
        results.forEachIndexed { index, result ->
            builder.append("\n${index + 1}. ${result.title}\n")
            if (result.snippet.isNotBlank()) builder.append(result.snippet).append("\n")
            builder.append(result.url)
            if (index != results.lastIndex) builder.append("\n")
        }
        return builder.toString()
    }

    private fun sourceLine(results: List<DuckDuckGoSearch.Result>): String {
        val entries = linkedSetOf<String>()
        for (result in results) {
            if (result.url.isBlank()) continue
            val host = try {
                URI(result.url).host.orEmpty()
            } catch (_: Throwable) {
                ""
            }
            entries.add(if (host.isBlank()) result.url else "$host — ${result.url}")
            if (entries.size >= 2) break
        }
        if (entries.isEmpty()) return ""
        return "Source: " + entries.joinToString("  ·  ")
    }
}
