package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private enum class UiMode { IDLE, CONNECTING, ACTIVE, ERROR }

    private lateinit var orb: OrbView
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var latest: TextView
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
    private var chatDialog: ChatDialog? = null
    private var modalDialog: Dialog? = null
    private var modalKey: String? = null
    private var lastActionName = ""
    private var lastActionAt = 0L
    private var lastNoteSignature = ""
    private var lastNoteAt = 0L
    private var lastReminderSignature = ""
    private var lastReminderAt = 0L
    private val assistantTurnText = StringBuilder()
    private var connectionWatchdog: Runnable? = null
    private var updatePromptVisible = false
    private var connectedSoundPlayed = false
    private var disconnectedSoundPlayed = false
    private var homeBaselineTime = 0L

    private val chatListener = ChatStore.Listener {
        runOnUiThread { updateLatest() }
    }

    private val reminderListener = ReminderBus.Listener { note ->
        runOnUiThread { announceReminder(note) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisPrefs.init(applicationContext)
        ChatStore.init(applicationContext)
        MemoryStore.init(applicationContext)
        NoteStore.init(applicationContext)
        ReminderNotifier.ensureChannel(applicationContext)
        ReminderScheduler.rescheduleAll(applicationContext)
        ReminderBus.addListener(reminderListener)
        resetStaleSessionIfUpdated()
        homeBaselineTime = System.currentTimeMillis()
        ChatStore.beginSession()
        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.bg
        buildUi()
        applyIdleUi(firstRun = true)
        val openNotesFromIntent = intent?.getBooleanExtra("open_notes", false) == true
        if (openNotesFromIntent) intent.removeExtra("open_notes")
        if (openNotesFromIntent) window.decorView.post { openNotes() }
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed && !active && !connecting) startFlow()
        }, 150L)
        window.decorView.postDelayed({ checkForUpdates(manual = false) }, 1500L)
    }

    override fun onStart() {
        super.onStart()
        ChatStore.addListener(chatListener)
        if (!active && !connecting) {
            statusLabel.text = "STANDBY"
            statusLabel.setTextColor(Palette.cyan)
            statusDetail.text =
                if (JarvisPrefs.hasApiKey()) "Secure voice link ready"
                else "Add your Gemini API key in Settings"
        }
        updateLatest()
    }

    override fun onStop() {
        ChatStore.removeListener(chatListener)
        super.onStop()
    }

    override fun onDestroy() {
        ReminderBus.removeListener(reminderListener)
        if (client != null || audio != null) {
            stopRequested = true
            releaseSession()
            ChatStore.sessionActive = false
        }
        JarvisSessionService.stop(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("open_notes", false) == true) {
            intent.removeExtra("open_notes")
            openNotes()
        }
    }

    private fun resetStaleSessionIfUpdated() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Throwable) {
            0
        }
        if (version <= 0) return
        if (JarvisPrefs.lastVersionCode != version) {
            JarvisPrefs.clearSessionHandle()
            JarvisPrefs.lastVersionCode = version
        }
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
            contentDescription = "Start or stop live session"
            setOnClickListener { toggleSession() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate()
                        .scaleX(0.95f).scaleY(0.95f).alpha(0.85f)
                        .setDuration(90L)
                        .start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(180L)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                }
                false
            }
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

        val sideControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val notesButton = JarvisButton(this, dp(52f), Palette.cyanDim, R.drawable.ic_note).apply {
            contentDescription = "Notepad"
            setOnClickListener { openNotes() }
        }
        sideControls.addView(notesButton, LinearLayout.LayoutParams(dp(52f), dp(52f)))

        chatButton = JarvisButton(this, dp(52f), Palette.cyanDim, R.drawable.ic_chat).apply {
            contentDescription = "Toggle live transcript"
            setOnClickListener {
                if (chatDialog?.isShowing == true) closeChat() else showChat()
            }
        }
        sideControls.addView(chatButton, LinearLayout.LayoutParams(dp(52f), dp(52f)).apply {
            topMargin = dp(8f)
        })

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
        assistantTurnText.setLength(0)
        uiMode = UiMode.CONNECTING
        orb.mode = OrbView.Mode.CONNECTING
        orb.setLevel(0f)
        statusLabel.text = "CONNECTING"
        statusLabel.setTextColor(Palette.cyan)
        startupHandle = JarvisPrefs.sessionHandle
        statusDetail.text = if (startupHandle.isNotBlank()) {
            "Restoring your previous live session"
        } else {
            "Opening a secure channel to Gemini Live"
        }
        micMuted = false
        updateMicButton()
        JarvisSessionService.start(this)

        audio = AudioEngine(
            onMicChunk = { chunk ->
                client?.sendAudio(if (micMuted) ByteArray(chunk.size) else chunk)
            },
            onMicLevel = { level -> if (!micMuted) orb.post { orb.setLevel(level) } },
            onPlaybackLevel = { level -> orb.post { orb.setLevel(level) } }
        )

        val memoryBlock = MemoryStore.promptBlock()
        val baseInstruction = JarvisPrefs.effectiveInstruction() + "\n\n" + APP_CONTROL_INSTRUCTION
        val systemInstruction = if (memoryBlock.isBlank()) {
            baseInstruction
        } else {
            baseInstruction + "\n\n" + memoryBlock
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
        setMicMuted(!micMuted)
        toast(if (micMuted) "Microphone muted" else "Microphone on")
    }

    private fun setMicMuted(muted: Boolean) {
        if (micMuted == muted) return
        micMuted = muted
        updateMicButton()
        if (muted) {
            orb.setLevel(0f)
            if (active) {
                statusLabel.text = "MUTED"
                statusLabel.setTextColor(Palette.gold)
                statusDetail.text = "Microphone off — tap the mic to speak"
            }
        } else if (active) {
            orb.mode = OrbView.Mode.LISTENING
            statusLabel.text = "ONLINE"
            statusLabel.setTextColor(Palette.cyan)
            statusDetail.text = "Listening — speak naturally"
        }
    }

    private fun showChat() {
        val existing = chatDialog
        if (existing != null && existing.isShowing) return
        val dialog = ChatDialog(this) { text -> sendTextToLive(text) }
        dialog.setOnDismissListener {
            if (chatDialog === dialog) chatDialog = null
        }
        chatDialog = dialog
        dialog.show()
    }

    private fun closeChat() {
        val dialog = chatDialog ?: return
        chatDialog = null
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun performAppAction(action: AppCommandRouter.Action) {
        Log.d("JarvisDebug", "perform action ${action.name}")
        val now = SystemClock.elapsedRealtime()
        if (action.name == lastActionName && now - lastActionAt < 1800L) return
        lastActionName = action.name
        lastActionAt = now
        when (action) {
            AppCommandRouter.Action.OPEN_CHAT -> {
                showChat()
                toast("Opening chat")
            }
            AppCommandRouter.Action.CLOSE_CHAT -> {
                closeChat()
                toast("Chat closed")
            }
            AppCommandRouter.Action.CLEAR_CHAT -> clearChat()
            AppCommandRouter.Action.OPEN_SETTINGS -> showSettings()
            AppCommandRouter.Action.OPEN_MEMORY -> openMemory()
            AppCommandRouter.Action.OPEN_NOTES -> openNotes()
            AppCommandRouter.Action.OPEN_MULTIBRAIN -> openMultiBrain()
            AppCommandRouter.Action.CYCLE_ORB -> cycleOrb()
            AppCommandRouter.Action.MUTE_MIC -> {
                setMicMuted(true)
                toast("Microphone muted")
            }
            AppCommandRouter.Action.UNMUTE_MIC -> {
                setMicMuted(false)
                toast("Microphone on")
            }
            AppCommandRouter.Action.BACK -> goBack()
        }
    }

    private fun showModal(key: String, create: () -> Dialog) {
        val existing = modalDialog
        if (existing != null && existing.isShowing) {
            if (modalKey == key) return
            existing.dismiss()
            modalDialog = null
        }
        val dialog = create()
        dialog.setOnDismissListener {
            if (modalDialog === dialog) {
                modalDialog = null
                modalKey = null
            }
        }
        modalDialog = dialog
        modalKey = key
        dialog.show()
    }

    private fun openMemory() {
        showModal("memory") { MemoryManagerDialog(this) }
    }

    private fun openNotes() {
        showModal("notes") { NotepadDialog(this) }
    }

    private fun openMultiBrain() {
        showModal("multibrain") { MultiBrainManagerDialog(this) }
    }

    private fun clearChat() {
        ChatStore.clear()
        JarvisPrefs.clearSessionHandle()
        toast("Transcript cleared")
    }

    private fun cycleOrb() {
        val styles = JarvisPrefs.orbStyles
        if (styles.isEmpty()) return
        val next = (JarvisPrefs.orbStyle + 1) % styles.size
        JarvisPrefs.orbStyle = next
        orb.orbStyle = next
        toast("Orb style: ${styles[next]}")
    }

    private fun goBack() {
        if (chatDialog?.isShowing == true) {
            closeChat()
            toast("Chat closed")
            return
        }
        val modal = modalDialog
        if (modal != null && modal.isShowing) {
            modal.dismiss()
            return
        }
        toast("Nothing to close")
    }

    private fun detectSpokenAction(text: String) {
        if (text.isBlank()) return
        val t = text.lowercase()
        if (listOf("can't", "cannot", "not able", "unable", "nahi", "नहीं").any { t.contains(it) }) return
        fun has(vararg list: String) = list.any { t.contains(it) }
        val openConfirm = arrayOf(
            "opening", "opened", "is now open", "is open", "on ho gaya", "on kar diya",
            "khol diya", "khol raha", "khol rahi", "खोल दिया", "चालू कर दिया", "चालू है", "खुल गया"
        )
        val closeConfirm = arrayOf(
            "closing", "closed", "band kar diya", "band ho gaya", "band kar raha",
            "बंद कर दिया", "बंद हो गया"
        )
        val clearConfirm = arrayOf(
            "cleared", "deleted", "clear kar diya", "delete kar diya", "dlt kar diya",
            "साफ कर दिया", "मिटा दिया"
        )
        val muteConfirm = arrayOf("muted", "microphone off", "mic off", "माइक बंद")
        val unmuteConfirm = arrayOf("unmuted", "microphone on", "mic on", "माइक चालू")

        val isChat = has("chat", "transcript", "चैट")
        val isMemory = has("memory", "मेमोरी")
        val isNotes = has("note", "notepad", "नोट")
        val isSettings = has("settings", "setting", "सेटिंग")
        val isMulti = has("multi brain", "multibrain", "brain manager")
        val isMic = has("mic", "microphone", "माइक")

        if (isChat && has(*clearConfirm)) {
            performAppAction(AppCommandRouter.Action.CLEAR_CHAT)
            return
        }
        if (isMemory && has(*openConfirm)) {
            performAppAction(AppCommandRouter.Action.OPEN_MEMORY)
            return
        }
        if (isNotes && has(*openConfirm)) {
            performAppAction(AppCommandRouter.Action.OPEN_NOTES)
            return
        }
        if (isSettings && has(*openConfirm)) {
            performAppAction(AppCommandRouter.Action.OPEN_SETTINGS)
            return
        }
        if (isMulti && has(*openConfirm)) {
            performAppAction(AppCommandRouter.Action.OPEN_MULTIBRAIN)
            return
        }
        if (isMic) {
            if (has(*muteConfirm)) {
                performAppAction(AppCommandRouter.Action.MUTE_MIC)
                return
            }
            if (has(*unmuteConfirm)) {
                performAppAction(AppCommandRouter.Action.UNMUTE_MIC)
                return
            }
        }
        if (isChat && has(*closeConfirm)) {
            performAppAction(AppCommandRouter.Action.CLOSE_CHAT)
            return
        }
        if (isChat && has(*openConfirm)) {
            performAppAction(AppCommandRouter.Action.OPEN_CHAT)
        }
    }

    private fun handleAppCommand(raw: String, allowNoteCreate: Boolean = true): Boolean {
        if (allowNoteCreate && handleVoiceReminder(raw)) return true
        if (allowNoteCreate && handleNoteCreation(raw)) return true
        val action = AppCommandRouter.parse(raw)
        Log.d("JarvisDebug", "parse '$raw' -> ${action?.name ?: "none"}")
        if (action == null) return false
        performAppAction(action)
        return true
    }

    private fun handleVoiceReminder(raw: String): Boolean {
        val parsed = VoiceReminderParser.parse(raw) ?: return false
        val signature = parsed.title + "|" + parsed.atMillis
        val now = SystemClock.elapsedRealtime()
        if (signature == lastReminderSignature && now - lastReminderAt < 8000L) return true
        val id = NoteStore.add(parsed.title, parsed.body, parsed.atMillis)
        if (id <= 0L) return false
        lastReminderSignature = signature
        lastReminderAt = now
        NoteStore.get(id)?.let { ReminderScheduler.schedule(applicationContext, it) }
        val formatted = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
            .format(Date(parsed.atMillis))
        toast("Reminder set for $formatted")
        openNotes()
        return true
    }

    private fun announceReminder(note: NoteStore.Note) {
        val live = client ?: return
        if (!active) return
        val title = note.title.ifBlank { "your task" }
        val body = note.body.ifBlank { title }
        val prompt = "[SYSTEM] The user's scheduled task is due now: \"$title\". " +
            "Details: $body. Say one short sentence like: \"Sir, it's time for $title.\""
        live.sendText(prompt)
    }

    private fun handleNoteCreation(raw: String): Boolean {
        val lower = raw.lowercase()
        val prefixes = listOf(
            "note likho", "note likh", "note banao", "note bana", "note add",
            "add note", "new note", "save note",
            "नोट लिखो", "नोट बनाओ", "नोट ऐड"
        )
        for (prefix in prefixes) {
            val index = lower.indexOf(prefix)
            if (index < 0) continue
            val text = raw.substring(index + prefix.length)
                .trim(' ', ':', '-', ',', '.', '।')
            if (text.length < 2) continue
            val signature = text.lowercase()
            val now = SystemClock.elapsedRealtime()
            if (signature == lastNoteSignature && now - lastNoteAt < 8000L) return true
            val title = text.take(60)
            val id = NoteStore.add(title, text, 0L)
            if (id > 0L) {
                lastNoteSignature = signature
                lastNoteAt = now
                toast("Note saved")
                openNotes()
                return true
            }
        }
        return false
    }

    private fun sendTextToLive(text: String): Boolean {
        val live = client ?: return false
        if (!active || !live.sendText(text)) return false
        handleAppCommand(text)
        val id = ChatStore.addUserText(text)
        if (id > 0) {
            Thread {
                try {
                    MemoryStore.observeUserMessage(id, text, true)
                } catch (_: Throwable) {
                }
            }.start()
        }
        updateLatest()
        return true
    }

    private fun rememberCompletedSpeech() {
        val last = ChatStore.snapshot().lastOrNull {
            it.role == ChatStore.Role.USER && it.final && it.text.isNotBlank()
        } ?: return
        Thread {
            try {
                MemoryStore.observeUserMessage(last.id, last.text, last.final)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun updateLatest() {
        if (!::latest.isInitialized) return
        val last = ChatStore.latest()
        if (last == null || last.time < homeBaselineTime) {
            latest.text = "Live transcript will appear here"
            latest.setTextColor(Palette.textFaint)
        } else {
            val prefix = if (last.role == ChatStore.Role.USER) "You: " else "Jarvis: "
            latest.text = prefix + last.text + if (!last.final && last.text.isNotBlank()) "  ▍" else ""
            latest.setTextColor(Palette.ice)
        }
    }

    private fun showSettings() {
        showModal("settings") {
            SettingsDialog(this, {
                orb.orbStyle = JarvisPrefs.orbStyle
                if (!active && !connecting) {
                    statusDetail.text =
                        if (JarvisPrefs.hasApiKey()) "Configuration updated — ready when you are"
                        else "Add your Gemini API key in Settings"
                }
                toast("Configuration saved")
            }, {
                checkForUpdates(manual = true)
            })
        }
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
            Log.d("JarvisDebug", "input final=$finished text=$text")
            ChatStore.appendUserFinal(text, finished)
            if (finished) {
                rememberCompletedSpeech()
                val last = ChatStore.snapshot().lastOrNull {
                    it.role == ChatStore.Role.USER && it.final && it.text.isNotBlank()
                }
                if (last != null) handleAppCommand(last.text)
            }
            if (active) {
                orb.mode = OrbView.Mode.LISTENING
                statusLabel.text = "LISTENING"
                statusLabel.setTextColor(Palette.cyan)
                statusDetail.text = "Capturing your voice"
            }
            updateLatest()
        }

        override fun onInterimInputTranscript(text: String) {
            Log.d("JarvisDebug", "interim text=$text")
            ChatStore.appendUserInterim(text)
            ChatStore.latest()
                ?.takeIf { it.role == ChatStore.Role.USER }
                ?.let { handleAppCommand(it.text, allowNoteCreate = false) }
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
            assistantTurnText.append(text)
            detectSpokenAction(assistantTurnText.toString())
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
            assistantTurnText.setLength(0)
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
            assistantTurnText.setLength(0)
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

        override fun onAppCommand(name: String) {
            Log.d("JarvisDebug", "tool call $name")
            val action = when (name) {
                "open_chat" -> AppCommandRouter.Action.OPEN_CHAT
                "close_chat" -> AppCommandRouter.Action.CLOSE_CHAT
                "clear_chat" -> AppCommandRouter.Action.CLEAR_CHAT
                "open_settings" -> AppCommandRouter.Action.OPEN_SETTINGS
                "open_memory" -> AppCommandRouter.Action.OPEN_MEMORY
                "open_notes" -> AppCommandRouter.Action.OPEN_NOTES
                "open_multi_brain" -> AppCommandRouter.Action.OPEN_MULTIBRAIN
                "change_orb" -> AppCommandRouter.Action.CYCLE_ORB
                "mute_mic" -> AppCommandRouter.Action.MUTE_MIC
                "unmute_mic" -> AppCommandRouter.Action.UNMUTE_MIC
                "go_back" -> AppCommandRouter.Action.BACK
                else -> return
            }
            performAppAction(action)
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
        private const val APP_CONTROL_INSTRUCTION =
            "You control this app through the provided functions. You MUST call the matching " +
                "function when the user asks to: open chat (open_chat), close chat or say back/" +
                "close/band (close_chat or go_back), delete or clear the transcript (clear_chat), " +
                "open settings (open_settings), open memory (open_memory), open the notepad or " +
                "reminders (open_notes), open the multi brain manager (open_multi_brain), change " +
                "the orb/theme (change_orb), or mute/unmute the microphone (mute_mic/unmute_mic). " +
                "After a function call keep your reply empty or at most one short sentence. Never " +
                "repeat a confirmation, never say the same action twice, and do not say 'sir'. " +
                "Always reply normally to normal conversation and questions. Never claim you " +
                "cannot control the app."
    }
}
