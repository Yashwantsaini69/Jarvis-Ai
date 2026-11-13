package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private enum class UiMode { IDLE, CONNECTING, ACTIVE, ERROR }

    private lateinit var orb: OrbView
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var latest: TextView
    private lateinit var startButton: JarvisButton
    private lateinit var chatButton: JarvisButton
    private lateinit var micButton: JarvisButton

    private var client: GeminiLiveClient? = null
    private var audio: AudioEngine? = null
    private var connecting = false
    private var active = false
    private var stopRequested = false
    private var uiMode = UiMode.IDLE
    private var startupHandle = ""
    private var autoRecovered = false
    private var micMuted = false
    private var connectionWatchdog: Runnable? = null
    private var updatePromptVisible = false
    private var connectedSoundPlayed = false
    private var disconnectedSoundPlayed = false

    private val chatListener = ChatStore.Listener {
        runOnUiThread { updateLatest() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisPrefs.init(applicationContext)
        ChatStore.init(applicationContext)
        MemoryStore.init(applicationContext)
        ChatStore.beginSession()
        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.bg
        buildUi()
        applyIdleUi(firstRun = true)
        window.decorView.postDelayed({ checkForUpdates(manual = false) }, 1500L)
    }

    override fun onStart() {
        super.onStart()
        ChatStore.addListener(chatListener)
        updateLatest()
    }

    override fun onStop() {
        ChatStore.removeListener(chatListener)
        super.onStop()
    }

    override fun onDestroy() {
        if (client != null || audio != null) {
            stopRequested = true
            releaseSession()
            ChatStore.sessionActive = false
        }
        JarvisSessionService.stop(this)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(JarvisBackgroundView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(10f), dp(20f), dp(14f))
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("JARVIS AI", 23f, Palette.ice, 0.22f, true))
        titles.addView(
            label("Developed by Ysaini2004", 9f, Palette.textFaint, 0.04f, false),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            }
        )
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val settings = JarvisButton(this, dp(44f), Palette.cyanDim, R.drawable.ic_settings).apply {
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        content.addView(top)

        val center = FrameLayout(this)
        val centerParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        centerParams.topMargin = dp(6f)
        content.addView(center, centerParams)

        orb = OrbView(this).apply {
            orbStyle = JarvisPrefs.orbStyle
            mode = OrbView.Mode.IDLE
        }
        center.addView(orb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusLabel = label("STANDBY", 10.5f, Palette.cyan, 0.3f, true, Gravity.CENTER)
        content.addView(statusLabel, matchWrap())

        statusDetail = label("Secure voice link ready", 11.5f, Palette.textDim, 0f, false, Gravity.CENTER)
        content.addView(statusDetail, matchWrap().apply { topMargin = dp(4f) })

        latest = label("Live transcript will appear here", 13f, Palette.textFaint, 0f, false, Gravity.CENTER).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(42f)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
        }
        content.addView(latest, matchWrap().apply { topMargin = dp(8f) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        startButton = JarvisButton(this, dp(78f), Palette.cyan, 0).apply {
            contentDescription = "Start live session"
            setLabel("START", Palette.ice)
            setOnClickListener { toggleSession() }
        }
        bottom.addView(startButton, LinearLayout.LayoutParams(dp(78f), dp(78f)))

        val sideControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        chatButton = JarvisButton(this, dp(52f), Palette.cyanDim, R.drawable.ic_chat).apply {
            contentDescription = "Open live transcript"
            setOnClickListener { ChatDialog(this@MainActivity).show() }
        }
        sideControls.addView(chatButton, LinearLayout.LayoutParams(dp(52f), dp(52f)))

        micButton = JarvisButton(this, dp(60f), Palette.cyanDim, R.drawable.ic_mic).apply {
            contentDescription = "Mute microphone"
            setOnClickListener { toggleMic() }
        }
        sideControls.addView(micButton, LinearLayout.LayoutParams(dp(60f), dp(60f)).apply {
            topMargin = dp(8f)
        })
        bottom.addView(sideControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(14f)
        })

        content.addView(bottom, matchWrap().apply { topMargin = dp(4f) })

        setContentView(root)
        content.applySystemBarPadding()
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun toggleSession() {
        if (active || connecting) endSession() else startFlow()
    }

    private fun startFlow() {
        if (!JarvisPrefs.hasApiKey()) {
            toast("Add your Gemini API key in Settings")
            showSettings()
            return
        }
        autoRecovered = false
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startSession()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val micIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val micGranted = micIndex < 0 ||
                (micIndex < grantResults.size && grantResults[micIndex] == PackageManager.PERMISSION_GRANTED)
            if (micGranted) {
                startSession()
            } else {
                toast("Microphone permission is required for live voice")
            }
        }
    }

    private fun startSession() {
        if (active || connecting) return
        ChatStore.beginSession()
        ChatStore.sessionActive = true
        connecting = true
        active = false
        stopRequested = false
        connectedSoundPlayed = false
        disconnectedSoundPlayed = false
        uiMode = UiMode.CONNECTING
        orb.mode = OrbView.Mode.CONNECTING
        orb.setLevel(0f)
        statusLabel.text = "INITIALIZING"
        statusLabel.setTextColor(Palette.cyan)
        startupHandle = JarvisPrefs.sessionHandle
        statusDetail.text = if (startupHandle.isNotBlank()) {
            "Restoring your previous live session"
        } else {
            "Opening a secure channel to Gemini Live"
        }
        micMuted = false
        updateMicButton()
        startButton.setLabel("END", Palette.ice)
        startButton.accentColor = Palette.gold
        startButton.contentDescription = "End live session"
        JarvisSessionService.start(this)

        audio = AudioEngine(
            onMicChunk = { chunk ->
                client?.sendAudio(if (micMuted) ByteArray(chunk.size) else chunk)
            },
            onMicLevel = { level -> if (!micMuted) orb.post { orb.setLevel(level) } },
            onPlaybackLevel = { level -> orb.post { orb.setLevel(level) } }
        )

        val memoryBlock = MemoryStore.promptBlock()
        val systemInstruction = if (memoryBlock.isBlank()) {
            JarvisPrefs.effectiveInstruction()
        } else {
            JarvisPrefs.effectiveInstruction() + "\n\n" + memoryBlock
        }
        client = GeminiLiveClient(
            JarvisPrefs.apiKey,
            JarvisPrefs.model,
            JarvisPrefs.voice,
            systemInstruction,
            startupHandle.ifBlank { null },
            liveListener
        )
        connectionWatchdog?.let { orb.removeCallbacks(it) }
        val watchdog = Runnable {
            if (connecting) {
                endWithError(
                    "CONNECTION TIMEOUT",
                    "Gemini Live did not respond. Check your API key and internet connection."
                )
            }
        }
        connectionWatchdog = watchdog
        orb.postDelayed(watchdog, 25_000L)

        client?.connect()
    }

    private fun endSession() {
        stopRequested = true
        playDisconnectSoundOnce()
        releaseSession()
        ChatStore.sessionActive = false
        ChatStore.assistantDone()
        rememberCompletedSpeech()
        stopRequested = false
        applyIdleUi()
        statusDetail.text = "Session ended — saved for next time"
        JarvisSessionService.stop(this)
    }

    private fun releaseSession() {
        connectionWatchdog?.let { orb.removeCallbacks(it) }
        connectionWatchdog = null
        val currentClient = client
        client = null
        currentClient?.sendAudioStreamEnd()
        currentClient?.close()
        val currentAudio = audio
        audio = null
        currentAudio?.release()
        connecting = false
        active = false
    }

    private fun playDisconnectSoundOnce() {
        if (!connectedSoundPlayed || disconnectedSoundPlayed) return
        disconnectedSoundPlayed = true
        ConnectionSounds.playDisconnected()
    }

    private fun endWithError(title: String, message: String) {
        playDisconnectSoundOnce()
        JarvisSessionService.stop(this)
        releaseSession()
        ChatStore.sessionActive = false
        ChatStore.assistantDone()
        rememberCompletedSpeech()
        uiMode = UiMode.ERROR
        orb.mode = OrbView.Mode.ERROR
        orb.setLevel(0f)
        startButton.setLabel("START", Palette.ice)
        startButton.accentColor = Palette.cyan
        startButton.contentDescription = "Start live session"
        micMuted = false
        updateMicButton()
        statusLabel.text = title
        statusLabel.setTextColor(Palette.danger)
        statusDetail.text = message
        updateLatest()
        toast(message)
    }

    private fun applyIdleUi(firstRun: Boolean = false) {
        uiMode = UiMode.IDLE
        connecting = false
        active = false
        ChatStore.sessionActive = false
        orb.mode = OrbView.Mode.IDLE
        orb.setLevel(0f)
        startButton.setLabel("START", Palette.ice)
        startButton.accentColor = Palette.cyan
        startButton.contentDescription = "Start live session"
        micMuted = false
        updateMicButton()
        statusLabel.text = "STANDBY"
        statusLabel.setTextColor(Palette.cyan)
        statusDetail.text = if (JarvisPrefs.hasApiKey()) "Secure voice link ready" else "Add your Gemini API key in Settings"
        if (firstRun) updateLatest()
    }

    private fun updateMicButton() {
        if (micMuted) {
            micButton.setIcon(R.drawable.ic_mic_off, Palette.gold)
            micButton.accentColor = Palette.gold
            micButton.contentDescription = "Unmute microphone"
        } else {
            micButton.setIcon(R.drawable.ic_mic, Palette.ice)
            micButton.accentColor = Palette.cyanDim
            micButton.contentDescription = "Mute microphone"
        }
    }

    private fun toggleMic() {
        if (!active && !connecting) {
            toast("Start a live session to use the mic toggle")
            return
        }
        micMuted = !micMuted
        updateMicButton()
        if (micMuted) {
            orb.setLevel(0f)
            if (active) {
                statusLabel.text = "MUTED"
                statusLabel.setTextColor(Palette.gold)
                statusDetail.text = "Microphone off — tap the mic to speak"
            }
            toast("Microphone muted")
        } else {
            if (active) {
                orb.mode = OrbView.Mode.LISTENING
                statusLabel.text = "ONLINE"
                statusLabel.setTextColor(Palette.cyan)
                statusDetail.text = "Listening — speak naturally"
            }
            toast("Microphone on")
        }
    }

    private fun rememberCompletedSpeech() {
        val last = ChatStore.snapshot().lastOrNull {
            it.role == ChatStore.Role.USER && it.final && it.text.isNotBlank()
        } ?: return
        try {
            MemoryStore.observeUserMessage(last.id, last.text, last.final)
        } catch (_: Exception) {
        }
    }

    private fun updateLatest() {
        if (!::latest.isInitialized) return
        val last = ChatStore.latest()
        if (last == null) {
            latest.text = "Live transcript will appear here"
            latest.setTextColor(Palette.textFaint)
        } else {
            val prefix = if (last.role == ChatStore.Role.USER) "You: " else "Jarvis: "
            latest.text = prefix + last.text + if (!last.final && last.text.isNotBlank()) "  ▍" else ""
            latest.setTextColor(Palette.ice)
        }
    }

    private fun showSettings() {
        SettingsDialog(this, {
            orb.orbStyle = JarvisPrefs.orbStyle
            if (!active && !connecting) {
                statusDetail.text = if (JarvisPrefs.hasApiKey()) "Configuration updated — ready when you are" else "Add your Gemini API key in Settings"
            }
            toast("Configuration saved")
        }, {
            checkForUpdates(manual = true)
        }).show()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updatePromptVisible) return
        updatePromptVisible = true
        Updater.check(this) { info, error ->
            updatePromptVisible = false
            if (isFinishing || isDestroyed) return@check
            if (info == null) {
                if (manual) {
                    toast(if (error != null) "Update check failed: $error" else "You are on the latest version")
                }
                return@check
            }
            showUpdatePrompt(info)
        }
    }

    private fun showUpdatePrompt(info: Updater.UpdateInfo) {
        val message = buildString {
            append("Version ").append(info.versionName).append(" is available.")
            if (info.notes.isNotBlank()) append("\n\n").append(info.notes)
        }
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Update JARVIS AI")
            .setMessage(message)
            .setPositiveButton("Update") { _, _ -> startUpdateDownload(info) }
        if (!info.force) builder.setNegativeButton("Later", null)
        builder.setCancelable(!info.force)
        builder.show()
    }

    private fun startUpdateDownload(info: Updater.UpdateInfo) {
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Downloading update")
            .setMessage("Keep the connection active.")
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()
        Updater.download(
            this,
            info,
            { percent -> progress.progress = percent },
            { file ->
                dialog.dismiss()
                val error = Updater.install(this, file)
                if (error != null) toast(error)
            },
            { message ->
                dialog.dismiss()
                toast("Update failed: $message")
            }
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private val liveListener = object : GeminiLiveClient.Listener {
        override fun onOpen() {
            if (stopRequested) return
            statusDetail.text = "Handshake complete — configuring session"
        }

        override fun onSetupComplete() {
            if (stopRequested) return
            val engine = audio
            if (engine == null || !engine.startCapture()) {
                endWithError("MICROPHONE ERROR", "Could not open the microphone")
                return
            }
            connecting = false
            active = true
            uiMode = UiMode.ACTIVE
            orb.mode = OrbView.Mode.LISTENING
            statusLabel.text = "ONLINE"
            statusLabel.setTextColor(Palette.cyan)
            statusDetail.text = if (startupHandle.isNotBlank()) {
                "Previous session restored — listening"
            } else {
                "Listening — speak naturally"
            }
            if (!connectedSoundPlayed) {
                connectedSoundPlayed = true
                ConnectionSounds.playConnected()
            }
            if (ChatStore.latest() == null) {
                latest.text = "Say “Jarvis, are you there?”"
                latest.setTextColor(Palette.ice)
            }
        }

        override fun onAudio(data: ByteArray) {
            if (!active) return
            audio?.play(data)
            orb.mode = OrbView.Mode.SPEAKING
            statusLabel.text = "SPEAKING"
            statusLabel.setTextColor(Palette.gold)
            statusDetail.text = "Jarvis is responding"
        }

        override fun onInputTranscript(text: String, finished: Boolean) {
            ChatStore.appendUserFinal(text, finished)
            if (active) {
                orb.mode = OrbView.Mode.LISTENING
                statusLabel.text = "LISTENING"
                statusLabel.setTextColor(Palette.cyan)
                statusDetail.text = "Capturing your voice"
            }
            updateLatest()
        }

        override fun onInterimInputTranscript(text: String) {
            ChatStore.appendUserInterim(text)
            if (active) {
                orb.mode = OrbView.Mode.LISTENING
                statusLabel.text = "LISTENING"
                statusLabel.setTextColor(Palette.cyan)
                statusDetail.text = "Capturing your voice"
            }
            updateLatest()
        }

        override fun onOutputTranscript(text: String) {
            ChatStore.appendModel(text)
            if (active) {
                orb.mode = OrbView.Mode.SPEAKING
                statusLabel.text = "SPEAKING"
                statusLabel.setTextColor(Palette.gold)
                statusDetail.text = "Jarvis is responding"
            }
            updateLatest()
        }

        override fun onTurnComplete() {
            ChatStore.assistantDone()
            rememberCompletedSpeech()
            orb.setLevel(0f)
            if (active) {
                if (micMuted) {
                    orb.mode = OrbView.Mode.LISTENING
                    statusLabel.text = "MUTED"
                    statusLabel.setTextColor(Palette.gold)
                    statusDetail.text = "Microphone off — tap the mic to speak"
                } else {
                    orb.mode = OrbView.Mode.LISTENING
                    statusLabel.text = "ONLINE"
                    statusLabel.setTextColor(Palette.cyan)
                    statusDetail.text = "Listening — speak naturally"
                }
            }
            updateLatest()
        }

        override fun onInterrupted() {
            audio?.interrupt()
            ChatStore.interrupted()
            if (active) {
                orb.mode = OrbView.Mode.LISTENING
                statusLabel.text = "LISTENING"
                statusLabel.setTextColor(Palette.cyan)
                statusDetail.text = "Interrupted — go ahead"
            }
            updateLatest()
        }

        override fun onVoiceActivity(active: Boolean) {
            if (active && this@MainActivity.active) {
                orb.mode = OrbView.Mode.LISTENING
                orb.setLevel(0.5f)
            }
        }

        override fun onGoAway(secondsLeft: Long) {
            if (stopRequested) return
            if (secondsLeft > 0) {
                statusDetail.text = "Session ends in ${secondsLeft}s — restart when it closes"
                toast("Live session time limit approaching")
            }
        }

        override fun onSessionResumption(handle: String, resumable: Boolean) {
            if (handle.isNotBlank()) JarvisPrefs.sessionHandle = handle
        }

        override fun onClosed(reason: String) {
            if (stopRequested) return
            if (tryAutoRecover()) return
            endWithError("SESSION ENDED", reason)
        }

        override fun onError(message: String) {
            if (stopRequested) return
            if (tryAutoRecover()) return
            endWithError("CONNECTION LOST", message)
        }
    }

    private fun tryAutoRecover(): Boolean {
        if (autoRecovered) return false
        val saved = JarvisPrefs.sessionHandle
        if (saved.isBlank()) return false
        val wasConnecting = connecting
        autoRecovered = true
        if (wasConnecting && startupHandle.isNotBlank() && startupHandle == saved) {
            JarvisPrefs.clearSessionHandle()
            toast("Saved session expired — starting a new one")
        } else {
            toast("Reconnecting to your live session")
        }
        releaseSession()
        ChatStore.sessionActive = false
        startSession()
        return true
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 701
    }
}
