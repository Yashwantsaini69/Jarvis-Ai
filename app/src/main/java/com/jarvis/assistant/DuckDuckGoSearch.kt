package com.jarvis.assistant

import android.text.Html
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object DuckDuckGoSearch {
    data class Result(val title: String, val snippet: String, val url: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Mobile Safari/537.36"

    private val anchorRegex = Regex("<a\\b([^>]*)>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val blockRegex = Regex("<(a|div)\\b([^>]*)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val hrefRegex = Regex("href\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("<[^>]+>")

    fun search(query: String, limit: Int = 4): List<Result> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val htmlResults = try {
            htmlSearch(clean, limit)
        } catch (_: Throwable) {
            emptyList()
        }
        if (htmlResults.isNotEmpty()) return htmlResults
        return try {
            apiSearch(clean, limit)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun htmlSearch(query: String, limit: Int): List<Result> {
        val form = FormBody.Builder()
            .add("q", query)
            .build()
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Origin", "https://html.duckduckgo.com")
            .header("Referer", "https://html.duckduckgo.com/")
            .post(form)
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) "" else response.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()

        val links = mutableListOf<Pair<String, String>>()
        for (match in anchorRegex.findAll(body)) {
            val attrs = match.groupValues[1]
            if (!attrs.contains("result__a", ignoreCase = true)) continue
            val href = hrefRegex.find(attrs)?.groupValues?.get(1) ?: continue
            val title = cleanHtml(match.groupValues[2])
            if (title.isBlank()) continue
            links.add(title to decodeHref(href))
        }
        val snippets = mutableListOf<String>()
        for (match in blockRegex.findAll(body)) {
            val attrs = match.groupValues[2]
            if (!attrs.contains("result__snippet", ignoreCase = true)) continue
            snippets.add(cleanHtml(match.groupValues[3]))
        }

        val out = mutableListOf<Result>()
        for (i in links.indices) {
            if (out.size >= limit) break
            val (title, link) = links[i]
            if (link.isBlank() || link.contains("duckduckgo.com/y.js")) continue
            out.add(Result(title, snippets.getOrNull(i).orEmpty().take(360), link))
        }
        return out
    }

    private fun apiSearch(query: String, limit: Int): List<Result> {
        val url = "https://api.duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8") +
            "&format=json&no_html=1&skip_disambig=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) "" else response.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()

        val root = JSONObject(body)
        val out = mutableListOf<Result>()
        val abstractText = root.optString("AbstractText", "").trim()
        val abstractUrl = root.optString("AbstractURL", "").trim()
        val heading = root.optString("Heading", "").trim()
        if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
            out.add(Result(heading.ifBlank { abstractUrl }, abstractText.take(360), abstractUrl))
        }
        val related = root.optJSONArray("RelatedTopics")
        if (related != null) {
            for (i in 0 until related.length()) {
                if (out.size >= limit) break
                collectTopics(related.optJSONObject(i), out, limit)
            }
        }
        return out
    }

    private fun collectTopics(topic: JSONObject?, out: MutableList<Result>, limit: Int) {
        if (topic == null || out.size >= limit) return
        val nested = topic.optJSONArray("Topics")
        if (nested != null) {
            for (i in 0 until nested.length()) {
                if (out.size >= limit) break
                collectTopics(nested.optJSONObject(i), out, limit)
            }
            return
        }
        val text = topic.optString("Text", "").trim()
        val url = topic.optString("FirstURL", "").trim()
        if (text.isBlank() || url.isBlank()) return
        out.add(Result(text.substringBefore(" - ").take(90), text.take(360), url))
    }

    private fun cleanHtml(value: String): String {
        val stripped = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        return stripped.replace(tagRegex, " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeHref(href: String): String {
        var value = href.trim()
        if (value.startsWith("//")) value = "https:$value"
        if (value.startsWith("/")) value = "https://duckduckgo.com$value"
        val marker = "uddg="
        val index = value.indexOf(marker)
        if (index >= 0) {
            val encoded = value.substring(index + marker.length).substringBefore("&")
            try {
                value = URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Throwable) {
            }
        }
        return value
    }
}
