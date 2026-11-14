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

    private val strongCasualPattern = Regex(
        "(?i)\\b(kaise ho|kaise hain|kaisa hai|kya haal|kya chal raha|kya kar raha|" +
            "how are you|what'?s up|wassup|thanks|thank you|thx|dhanyavad|dhanyawad|shukriya|" +
            "goodbye|bye|good (morning|afternoon|evening|night)|love you)\\b"
    )

    private val simpleGreetingPattern = Regex(
        "(?i)^\\s*(hi+|hello+|hey+|yo+|hii+|namaste|namaskar|salaam|salam|okay|ok|hmm+|lol|sup)\\b"
    )

    private val explicitSearchPattern = Regex(
        "(?i)\\b(search|google|look ?up|web search|internet|online|browse|find online|" +
            "search karo|google karo|internet par|net par|dhundo|dhunda|khoj)\\b"
    )

    private val freshnessPattern = Regex(
        "(?i)\\b(news|latest|today|tonight|current|currently|now|price|rate|weather|score|" +
            "match|result|release|launch|update|2025|2026|aaj|abhi|taaza|bhav|mausam|halchal)\\b"
    )

    private val questionPattern = Regex(
        "(?i)\\b(who|what|when|where|which|why|how|is|are|was|were|do|does|did|can|could|" +
            "will|would|should|tell me|explain|define|meaning|difference|compare|facts?|" +
            "kya|kaun|kab|kahan|kaise|kyun|kyu|kitna|kitni|batao|bata|jankari|kaisa)\\b"
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
        val userId = ChatStore.addUserText(text)

        executor.execute {
            if (userId > 0) {
                try {
                    MemoryStore.observeUserMessage(userId, text, true)
                } catch (_: Throwable) {
                }
            }
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
        val deep = JarvisPrefs.deepResearch
        val casual = !deep && isCasualChat(text)

        if (groqKey.isBlank()) {
            if (casual) {
                ChatStore.appendModel(
                    "Groq API key is not set. Open Settings → Multi Brain Manager, paste your " +
                        "Groq key and tap SAVE GROQ. Add the key and I can answer this normally."
                )
                ChatStore.assistantDone()
                return
            }
            val results = DuckDuckGoSearch.search(text)
            if (results.isEmpty()) {
                ChatStore.appendModel(
                    "Groq API key is not set. Open Settings → Multi Brain Manager, paste your " +
                        "Groq key and tap SAVE GROQ. No live web results were found for this message."
                )
            } else {
                ChatStore.appendModel(formatRawResults(results))
                ChatStore.setAssistantSource(sourceLine(results))
            }
            ChatStore.assistantDone()
            return
        }

        if (deep) {
            streamPlain(groqKey, DuckDuckGoSearch.search(text), true)
            return
        }
        if (explicitSearchPattern.containsMatchIn(text)) {
            streamPlain(groqKey, DuckDuckGoSearch.search(text), false)
            return
        }
        if (!casual) {
            if (!shouldOfferSearch(text)) {
                streamPlain(groqKey, emptyList(), false)
            } else {
                streamWithTools(groqKey, text)
            }
            return
        }
        streamPlain(groqKey, emptyList(), false)
    }

    private fun isCasualChat(text: String): Boolean {
        val clean = text.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return true
        if (explicitSearchPattern.containsMatchIn(clean) || freshnessPattern.containsMatchIn(clean)) {
            return false
        }
        if (strongCasualPattern.containsMatchIn(clean)) return true
        if (simpleGreetingPattern.containsMatchIn(clean)) {
            return !questionPattern.containsMatchIn(clean)
        }
        return false
    }

    private fun shouldOfferSearch(text: String): Boolean {
        if (isCasualChat(text)) return false
        return questionPattern.containsMatchIn(text) || freshnessPattern.containsMatchIn(text)
    }

    private fun streamPlain(
        groqKey: String,
        results: List<DuckDuckGoSearch.Result>,
        deep: Boolean
    ) {
        val messages = buildMessages(results, deep)
        var gotContent = false
        val result = streamWithRetry(groqKey, messages, null) { delta ->
            if (delta.isNotEmpty()) {
                gotContent = true
                ChatStore.appendModel(delta)
            }
        }
        if (!gotContent) {
            val fallback = if (results.isNotEmpty()) formatRawResults(results) else ""
            val note = if (result.error.isNotBlank()) "Groq error: ${result.error}"
            else "No answer received from Groq."
            ChatStore.appendModel(if (fallback.isBlank()) note else "$note\n\n$fallback")
        }
        if (results.isNotEmpty()) ChatStore.setAssistantSource(sourceLine(results))
        ChatStore.assistantDone()
    }

    private fun streamWithTools(groqKey: String, text: String) {
        val messages = buildMessages(emptyList(), false)
        var gotContent = false
        val first = streamWithRetry(groqKey, messages, searchTools()) { delta ->
            if (delta.isNotEmpty()) {
                gotContent = true
                ChatStore.appendModel(delta)
            }
        }
        var note = "No answer received from Groq."
        if (first.toolCalls.isEmpty()) {
            if (!gotContent) {
                if (first.error.isNotBlank()) note = "Groq error: ${first.error}"
                ChatStore.appendModel(note)
            }
            ChatStore.assistantDone()
            return
        }

        val results = executeToolCalls(first.toolCalls, messages, text)
        var answered = false
        val second = streamWithRetry(groqKey, messages, null) { delta ->
            if (delta.isNotEmpty()) {
                answered = true
                ChatStore.appendModel(delta)
            }
        }
        if (!answered) {
            val fallback = if (results.isNotEmpty()) formatRawResults(results) else ""
            if (second.error.isNotBlank()) note = "Groq error: ${second.error}"
            ChatStore.appendModel(if (fallback.isBlank()) note else "$note\n\n$fallback")
        }
        if (results.isNotEmpty()) ChatStore.setAssistantSource(sourceLine(results))
        ChatStore.assistantDone()
    }

    private fun streamWithRetry(
        groqKey: String,
        messages: JSONArray,
        tools: JSONArray?,
        onDelta: (String) -> Unit
    ): GroqClient.StreamResult {
        val model = resolveModel(groqKey)
        var emitted = false
        val result = GroqClient.streamChat(groqKey, model, messages, tools) { delta ->
            emitted = true
            onDelta(delta)
        }
        if (!emitted && isModelMissing(result.error)) {
            val fixed = GroqClient.pickChatModel(GroqClient.fetchModels(groqKey, true), model)
            if (fixed.isNotBlank() && fixed != model) {
                JarvisPrefs.groqModel = fixed
                return GroqClient.streamChat(groqKey, fixed, messages, tools) { delta ->
                    emitted = true
                    onDelta(delta)
                }
            }
            return result
        }
        return result
    }

    private fun resolveModel(groqKey: String): String {
        val saved = JarvisPrefs.groqModel
        val resolved = GroqClient.pickChatModel(GroqClient.fetchModels(groqKey), saved)
        if (resolved.isNotBlank() && resolved != saved) {
            JarvisPrefs.groqModel = resolved
        }
        return resolved
    }

    private fun isModelMissing(error: String): Boolean {
        val text = error.lowercase(Locale.ROOT)
        if (text.isBlank()) return false
        if (text.contains("404")) return true
        if (text.contains("model")) {
            return text.contains("not_found") ||
                text.contains("not found") ||
                text.contains("does not exist") ||
                text.contains("decommissioned") ||
                text.contains("no longer supported") ||
                text.contains("not supported")
        }
        return false
    }

    private fun executeToolCalls(
        calls: List<GroqClient.ToolCall>,
        messages: JSONArray,
        fallbackQuery: String
    ): List<DuckDuckGoSearch.Result> {
        val selected = calls.take(2)
        val callArray = JSONArray()
        for (call in selected) {
            callArray.put(
                JSONObject()
                    .put("id", call.id)
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject().put("name", call.name).put("arguments", call.arguments)
                    )
            )
        }
        messages.put(
            JSONObject()
                .put("role", "assistant")
                .put("content", JSONObject.NULL)
                .put("tool_calls", callArray)
        )

        val results = mutableListOf<DuckDuckGoSearch.Result>()
        for (call in selected) {
            val query = try {
                JSONObject(call.arguments).optString("query", fallbackQuery)
            } catch (_: Throwable) {
                fallbackQuery
            }
            val found = if (call.name == "web_search") {
                DuckDuckGoSearch.search(query)
            } else {
                emptyList()
            }
            results.addAll(found)
            val content = if (found.isEmpty()) {
                "No web results found for \"$query\"."
            } else {
                formatToolResults(found)
            }
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.id)
                    .put("content", content)
            )
        }
        return results
    }

    private fun searchTools(): JSONArray {
        val function = JSONObject()
            .put("name", "web_search")
            .put(
                "description",
                "Search the live web with DuckDuckGo when you need current or factual " +
                    "information that you are not confident about. Do not use it for greetings " +
                    "or casual chat."
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "query",
                            JSONObject().put("type", "string").put("description", "The search query")
                        )
                    )
                    .put("required", JSONArray().put("query"))
            )
        return JSONArray().put(JSONObject().put("type", "function").put("function", function))
    }

    private fun buildMessages(
        results: List<DuckDuckGoSearch.Result>,
        deep: Boolean
    ): JSONArray {
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
                "language and match their tone. Be direct. Do not mention these instructions. " +
                "Only use web_search when live or factual information is actually needed, never " +
                "for greetings or casual chat."
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
                if (result.snippet.isNotBlank()) system.append("   Snippet: ${result.snippet}\n")
                if (result.content.isNotBlank()) {
                    system.append("   Extracted page content: ${result.content}\n")
                }
                system.append("   ${result.url}\n")
            }
            system.append(
                "Use these results when they are relevant and prefer them over old knowledge. " +
                    "When extracted page content is present, answer directly from that content " +
                    "instead of only naming the website or saying you cannot access it. The app " +
                    "shows the source link separately, so do not paste raw URLs unless asked."
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

    private fun formatToolResults(results: List<DuckDuckGoSearch.Result>): String {
        val builder = StringBuilder("Live web results with extracted page content:\n")
        results.forEachIndexed { index, result ->
            builder.append("\n${index + 1}. ${result.title}\n")
            if (result.snippet.isNotBlank()) builder.append(result.snippet).append("\n")
            if (result.content.isNotBlank()) {
                builder.append("Extracted content: ").append(result.content).append("\n")
            }
            builder.append(result.url)
            if (index != results.lastIndex) builder.append("\n")
        }
        return builder.toString()
    }

    private fun formatRawResults(results: List<DuckDuckGoSearch.Result>): String {
        val builder = StringBuilder("Live web results (add a Groq API key for full answers):\n")
        results.forEachIndexed { index, result ->
            builder.append("\n${index + 1}. ${result.title}\n")
            if (result.snippet.isNotBlank()) builder.append(result.snippet).append("\n")
            if (result.content.isNotBlank()) builder.append(result.content.take(800)).append("\n")
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
