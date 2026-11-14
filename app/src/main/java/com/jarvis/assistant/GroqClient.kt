package com.jarvis.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GroqClient {
    private const val BASE = "https://api.groq.com/openai/v1"
    private const val CACHE_MS = 600_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val skippedModels = listOf(
        "whisper", "tts", "audio", "speech", "transcribe", "orpheus", "playai",
        "guard", "embedding", "moderation", "rerank"
    )

    @Volatile
    private var cachedModels: List<String> = emptyList()

    @Volatile
    private var cachedKey = ""

    @Volatile
    private var cachedAt = 0L

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    data class StreamResult(
        val toolCalls: List<ToolCall> = emptyList(),
        val error: String = ""
    )

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    fun fetchModels(apiKey: String, force: Boolean = false): List<String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        if (!force && key == cachedKey && cachedModels.isNotEmpty() && now - cachedAt < CACHE_MS) {
            return cachedModels
        }
        val models = requestModels(key)
        if (models.isNotEmpty()) {
            cachedKey = key
            cachedModels = models
            cachedAt = now
        }
        return models
    }

    fun pickChatModel(models: List<String>, saved: String): String {
        if (saved.isBlank() || (!models.isEmpty() && !models.contains(saved))) {
            val first = models.firstOrNull()
            return if (models.contains(JarvisPrefs.PREFERRED_GROQ_MODEL) || first == null) {
                JarvisPrefs.PREFERRED_GROQ_MODEL
            } else {
                first
            }
        }
        return saved
    }

    private fun requestModels(key: String): List<String> {
        val request = Request.Builder()
            .url("$BASE/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emptyList()
                } else {
                    val body = response.body?.string().orEmpty()
                    val data = JSONObject(body).optJSONArray("data")
                    if (data == null) {
                        emptyList()
                    } else {
                        val ids = mutableListOf<String>()
                        for (i in 0 until data.length()) {
                            val id = data.optJSONObject(i)?.optString("id", "").orEmpty()
                            if (id.isBlank()) continue
                            if (skippedModels.any { id.contains(it, ignoreCase = true) }) continue
                            ids.add(id)
                        }
                        ids.distinct().sorted()
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun complete(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int = 700
    ): String? {
        val key = apiKey.trim()
        if (key.isEmpty() || model.isBlank()) return null
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.1)
            .put("max_tokens", maxTokens)
        val request = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    val body = response.body?.string().orEmpty()
                    val choices = JSONObject(body).optJSONArray("choices")
                    val content = choices?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        .orEmpty()
                    content.ifBlank { null }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun streamChat(
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        onDelta: (String) -> Unit
    ): StreamResult {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.7)
        if (tools != null && tools.length() > 0) {
            payload.put("tools", tools)
            payload.put("tool_choice", "auto")
        }
        val request = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty().take(400)
                    StreamResult(error = httpMessage(response.code, detail))
                } else {
                    val source = response.body?.source()
                    if (source == null) {
                        StreamResult(error = "Empty response from Groq")
                    } else {
                        parseStream(source, onDelta)
                    }
                }
            }
        } catch (t: Throwable) {
            StreamResult(error = t.message?.take(200) ?: "Network error")
        }
    }

    private fun parseStream(source: BufferedSource, onDelta: (String) -> Unit): StreamResult {
        val builders = LinkedHashMap<Int, ToolCallBuilder>()
        var error = ""
        var sawContent = false
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            try {
                val obj = JSONObject(data)
                val errorObj = obj.optJSONObject("error")
                if (errorObj != null) {
                    error = errorObj.optString("message", "Groq error")
                    continue
                }
                val choices = obj.optJSONArray("choices") ?: continue
                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    sawContent = true
                    try {
                        onDelta(content)
                    } catch (_: Exception) {
                    }
                }
                val calls = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val index = call.optInt("index", i)
                    val builder = builders.getOrPut(index) { ToolCallBuilder() }
                    val id = call.optString("id", "")
                    if (id.isNotBlank()) builder.id = id
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name", "")
                    if (name.isNotBlank()) builder.name = name
                    val arguments = function.optString("arguments", "")
                    if (arguments.isNotEmpty()) builder.arguments.append(arguments)
                }
            } catch (_: Exception) {
            }
        }
        val toolCalls = builders.entries
            .sortedBy { it.key }
            .map { (index, builder) ->
                val id = builder.id.ifBlank { "call_$index" }
                ToolCall(id, builder.name, builder.arguments.toString())
            }
            .filter { it.name.isNotBlank() }
        if (!sawContent && toolCalls.isEmpty() && error.isBlank()) {
            error = "Groq returned an empty answer"
        }
        return StreamResult(toolCalls, error)
    }

    private fun httpMessage(code: Int, detail: String): String {
        val hint = when (code) {
            401, 403 -> "Invalid Groq API key"
            404 -> "Groq model not found"
            429 -> "Groq rate limit reached, try again shortly"
            else -> "Groq error $code"
        }
        return if (detail.isBlank()) hint else "$hint: $detail"
    }
}
