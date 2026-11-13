package com.jarvis.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GroqClient {
    private const val BASE = "https://api.groq.com/openai/v1"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val skippedModels = listOf("whisper", "tts", "guard", "embedding", "moderation", "rerank")

    fun fetchModels(apiKey: String): List<String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return emptyList()
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

    fun streamChat(
        apiKey: String,
        model: String,
        messages: JSONArray,
        onDelta: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.7)
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
                    onError(httpMessage(response.code, detail))
                    false
                } else {
                    val source = response.body?.source()
                    if (source == null) {
                        onError("Empty response from Groq")
                        false
                    } else {
                        var sawContent = false
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            if (data == "[DONE]") break
                            try {
                                val obj = JSONObject(data)
                                val error = obj.optJSONObject("error")
                                if (error != null) {
                                    onError(error.optString("message", "Groq error"))
                                    continue
                                }
                                val choices = obj.optJSONArray("choices") ?: continue
                                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                                val content = delta.optString("content", "")
                                if (content.isNotEmpty()) {
                                    sawContent = true
                                    onDelta(content)
                                }
                            } catch (_: Exception) {
                            }
                        }
                        if (!sawContent) onError("Groq returned an empty answer")
                        true
                    }
                }
            }
        } catch (t: Throwable) {
            onError(t.message?.take(200) ?: "Network error")
            false
        }
    }

    private fun httpMessage(code: Int, detail: String): String {
        val hint = when (code) {
            401, 403 -> "Invalid Groq API key"
            429 -> "Groq rate limit reached, try again shortly"
            else -> "Groq error $code"
        }
        return if (detail.isBlank()) hint else "$hint: $detail"
    }
}
