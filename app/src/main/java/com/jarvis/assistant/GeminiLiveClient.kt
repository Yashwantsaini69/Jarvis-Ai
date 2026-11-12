package com.jarvis.assistant

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemInstruction: String,
    private val resumeHandle: String?,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onSetupComplete()
        fun onAudio(data: ByteArray)
        fun onInputTranscript(text: String, finished: Boolean)
        fun onInterimInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onTurnComplete()
        fun onInterrupted()
        fun onVoiceActivity(active: Boolean)
        fun onGoAway(secondsLeft: Long)
        fun onSessionResumption(handle: String, resumable: Boolean) {}
        fun onClosed(reason: String)
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var closed = false

    @Volatile
    private var setupComplete = false

    private var closeReason = ""

    private companion object {
        const val EXTENDED_THINKING_MODEL = "gemini-3.8-live-extended-thinking"
    }

    fun connect() {
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$encodedKey"
        val request = Request.Builder().url(url).build()
        webSocket = http.newWebSocket(request, socketListener)
    }

    fun sendAudio(pcm: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!setupComplete || closed || pcm.isEmpty()) return false
        return try {
            val audio = JSONObject()
                .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                .put("mimeType", "audio/pcm;rate=16000")
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
        } catch (t: Throwable) {
            Log.w("GeminiLive", "audio send failed", t)
            false
        }
    }

    fun sendAudioStreamEnd() {
        val ws = webSocket ?: return
        if (!setupComplete || closed) return
        try {
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
        } catch (_: Throwable) {
        }
    }

    fun sendText(text: String): Boolean {
        val ws = webSocket ?: return false
        if (!setupComplete || closed) return false
        return try {
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
        } catch (_: Throwable) {
            false
        }
    }

    fun close() {
        closed = true
        val ws = webSocket
        webSocket = null
        try {
            ws?.close(1000, "Session ended")
        } catch (_: Throwable) {
        }
    }

    private fun buildSetup(): String {
        val modelName = if (model.startsWith("models/")) model else "models/$model"
        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put(
                "prebuiltVoiceConfig",
                JSONObject().put("voiceName", voice)
            )
        )
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("temperature", 1.0)
            .put("speechConfig", speechConfig)

        if (model == EXTENDED_THINKING_MODEL) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", "MEDIUM")
            )
        }

        val activityDetection = JSONObject()
            .put("disabled", false)
            .put("silenceDurationMs", 900)
            .put("prefixPaddingMs", 300)
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")

        val realtimeInputConfig = JSONObject()
            .put("automaticActivityDetection", activityDetection)

        val setup = JSONObject()
            .put("model", modelName)
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", realtimeInputConfig)
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put(
                "sessionResumption",
                JSONObject().apply {
                    if (!resumeHandle.isNullOrBlank()) put("handle", resumeHandle)
                }
            )

        return JSONObject().put("setup", setup).toString()
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) return
            handler.post { listener.onOpen() }
            try {
                webSocket.send(buildSetup())
            } catch (t: Throwable) {
                fail("Could not configure the live session: ${t.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handlePayload(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handlePayload(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeReason = reason
            try {
                webSocket.close(code, reason)
            } catch (_: Throwable) {
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed) return
            closed = true
            val text = when {
                reason.isNotBlank() -> reason
                closeReason.isNotBlank() -> closeReason
                code != 1000 -> "Connection closed (code $code)"
                else -> "Session closed"
            }
            handler.post { listener.onClosed(text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            closed = true
            val message = readFailure(response, t)
            handler.post { listener.onError(message) }
        }
    }

    private fun handlePayload(payload: String) {
        try {
            val obj = JSONObject(payload)

            obj.optJSONObject("error")?.let { error ->
                val code = error.optInt("code", 0)
                val message = error.optString("message").ifBlank { "Gemini Live rejected the request" }
                fail(if (code != 0) "$message (code $code)" else message)
                return
            }

            if (obj.has("setupComplete")) {
                setupComplete = true
                handler.post { listener.onSetupComplete() }
                return
            }

            obj.optJSONObject("serverContent")?.let { content ->
                content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        part.optJSONObject("inlineData")?.let { inline ->
                            val data = inline.optString("data")
                            if (data.isNotEmpty()) {
                                try {
                                    val audio = Base64.decode(data, Base64.DEFAULT)
                                    if (audio.isNotEmpty()) handler.post { listener.onAudio(audio) }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                        val text = part.optString("text")
                        if (text.isNotEmpty() && !part.optBoolean("thought", false)) {
                            handler.post { listener.onOutputTranscript(text) }
                        }
                    }
                }

                content.optJSONObject("inputTranscription")?.let { t ->
                    val text = t.optString("text")
                    val finished = t.optBoolean("finished", false)
                    if (text.isNotEmpty() || finished) {
                        handler.post { listener.onInputTranscript(text, finished) }
                    }
                }

                content.optJSONObject("interimInputTranscription")?.let { t ->
                    val text = t.optString("text")
                    if (text.isNotEmpty()) handler.post { listener.onInterimInputTranscript(text) }
                }

                content.optJSONObject("outputTranscription")?.let { t ->
                    val text = t.optString("text")
                    if (text.isNotEmpty()) handler.post { listener.onOutputTranscript(text) }
                }

                if (content.optBoolean("interrupted", false)) {
                    handler.post { listener.onInterrupted() }
                }

                if (content.optBoolean("turnComplete", false)) {
                    handler.post { listener.onTurnComplete() }
                }
            }

            obj.optJSONObject("voiceActivity")?.let { activity ->
                val type = activity.optString("type")
                val active = type == "ACTIVITY_START"
                handler.post { listener.onVoiceActivity(active) }
            }

            obj.optJSONObject("sessionResumptionUpdate")?.let { update ->
                val handle = update.optString("newHandle")
                val resumable = update.optBoolean("resumable", false)
                if (handle.isNotEmpty()) {
                    handler.post { listener.onSessionResumption(handle, resumable) }
                }
            }

            obj.optJSONObject("goAway")?.let { goAway ->
                val seconds = goAway.optJSONObject("timeLeft")?.optLong("seconds", 0L) ?: 0L
                handler.post { listener.onGoAway(seconds) }
            }
        } catch (t: Throwable) {
            Log.w("GeminiLive", "Could not parse server payload", t)
        }
    }

    private fun fail(message: String) {
        if (closed) return
        closed = true
        handler.post { listener.onError(message) }
        try {
            webSocket?.cancel()
        } catch (_: Throwable) {
        }
    }

    private fun readFailure(response: Response?, t: Throwable): String {
        val body = try {
            response?.body?.string()
        } catch (_: Throwable) {
            null
        }
        if (!body.isNullOrBlank()) {
            try {
                val message = JSONObject(body).optJSONObject("error")?.optString("message")
                if (!message.isNullOrBlank()) return message
            } catch (_: Throwable) {
            }
            return body.take(300)
        }
        return t.message?.take(300) ?: "Connection failed"
    }
}
