package com.jarvis.assistant

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UncensoredClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun modelsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
    }

    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val url = modelsUrl(baseUrl)
        if (url.isEmpty()) return emptyList()
        val builder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: ""
                val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
                (0 until data.length()).mapNotNull { index ->
                    data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
                }.sorted()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
