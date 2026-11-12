package com.jarvis.assistant

import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import kotlin.math.min

class VoicePreview(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val callback: Callback
) {
    interface Callback {
        fun onLevel(level: Float)
        fun onFinished()
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var setupComplete = false

    @Volatile
    private var done = false

    private var audio: AudioEngine? = null
    private var receivedBytes = 0L

    private val timeoutRunnable = Runnable { complete("Voice preview timed out", 0L) }

    fun start() {
        if (done) return
        audio = AudioEngine(
            onMicChunk = {},
            onMicLevel = {},
            onPlaybackLevel = { level -> if (!done) callback.onLevel(level) }
        )
        val encodedKey = try {
            URLEncoder.encode(apiKey, "UTF-8")
        } catch (_: Throwable) {
            apiKey
        }
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$encodedKey"
        socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
        handler.postDelayed(timeoutRunnable, 20_000L)
    }

    fun stop() {
        complete(null, 0L)
    }

    private fun complete(error: String?, delayMs: Long) {
        if (done) return
        done = true
        handler.removeCallbacks(timeoutRunnable)
        val ws = socket
        socket = null
        try {
            ws?.close(1000, "preview complete")
        } catch (_: Throwable) {
        }
        if (error != null) {
            handler.post { callback.onError(error) }
        }
        handler.postDelayed({
            val engine = audio
            audio = null
            engine?.release()
            callback.onFinished()
        }, delayMs.coerceAtLeast(0L))
    }

    private fun sendSetup() {
        val ws = socket ?: return
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
        val modelName = if (model.startsWith("models/")) model else "models/$model"
        val setup = JSONObject()
            .put("model", modelName)
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            JarvisCore.assemble("", false)
                        )
                    )
                )
            )
        try {
            ws.send(JSONObject().put("setup", setup).toString())
        } catch (t: Throwable) {
            complete("Could not start the voice preview", 0L)
        }
    }

    private fun sendSampleText() {
        val ws = socket ?: return
        val prompt = "Say this line naturally, exactly as written: " +
            "\"Hello sir, I am Jarvis. This is how I sound. How may I assist you?\""
        try {
            ws.send(
                JSONObject().put(
                    "realtimeInput",
                    JSONObject().put("text", prompt)
                ).toString()
            )
        } catch (_: Throwable) {
            complete("Could not request the voice preview", 0L)
        }
    }

    private fun handlePayload(payload: String) {
        if (done) return
        try {
            val obj = JSONObject(payload)
            if (obj.has("setupComplete")) {
                setupComplete = true
                handler.removeCallbacks(timeoutRunnable)
                handler.postDelayed(timeoutRunnable, 20_000L)
                sendSampleText()
                return
            }

            obj.optJSONObject("serverContent")?.let { content ->
                content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                        val data = inline.optString("data")
                        if (data.isNotEmpty()) {
                            try {
                                val bytes = Base64.decode(data, Base64.DEFAULT)
                                if (bytes.isNotEmpty()) {
                                    receivedBytes += bytes.size
                                    audio?.play(bytes)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                if (content.optBoolean("turnComplete", false)) {
                    val delay = min(8_000L, receivedBytes * 1_000L / 48_000L + 800L)
                    complete(null, delay)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sendSetup()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handlePayload(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handlePayload(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            try {
                webSocket.close(code, reason)
            } catch (_: Throwable) {
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            complete(null, 0L)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (done) return
            val message = readFailure(response, t)
            complete(message, 0L)
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
            return body.take(240)
        }
        return t.message?.take(240) ?: "Voice preview failed"
    }
}
