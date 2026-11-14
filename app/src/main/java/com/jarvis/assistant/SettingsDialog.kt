package com.jarvis.assistant

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsDialog(
    context: Context,
    private val onSaved: () -> Unit,
    private val onCheckUpdates: () -> Unit = {}
) : Dialog(context) {
    private lateinit var instructionInput: EditText
    private lateinit var voiceValue: TextView

    private var selectedVoice = JarvisPrefs.voice
    private var selectedOrb = JarvisPrefs.orbStyle
    private var selectedUncensored = JarvisPrefs.uncensored
    private val orbCells = mutableListOf<LinearLayout>()
    private var preview: VoicePreview? = null
    private var previewToken = 0

    private val helpText = """
        JARVIS HELP

        TALK
        - Open the app. Jarvis connects automatically and starts listening.
        - Speak normally. Jarvis answers with voice.
        - The live session keeps running when you go to another app.
        - Tap the orb to start or end the session.
        - Open the chat to see the transcript or type a message.

        MUTE AND UNMUTE
        - Say "mute karo" or "mic off karo" to mute.
        - While muted, say "Hey Jarvis" to unmute. No touch needed.
        - The "Hey Jarvis" detector works only while muted and only on this device.
        - When the app is fully closed, Jarvis does not listen.

        OPEN APPS AND LINKS
        - "YouTube kholo", "WhatsApp kholo", "Chrome kholo".
        - "Chrome me google.com kholo" or "open google.com".
        - To open apps while Jarvis is in the background, allow
          "Display over other apps" in Settings.

        APP CONTROLS
        - "Chat kholo", "chat band karo", "chat clear karo".
        - "Settings kholo" opens Jarvis settings.
        - "Phone settings kholo" opens Android settings.
        - "Memory manager kholo", "notes kholo", "multi brain kholo".
        - "Back karo" and "home screen par aao" work inside Jarvis.
        - "Bluetooth on karo" or "WiFi band karo". Android may open a system panel.
        - These commands run silently, without a spoken reply.

        REMINDERS
        - "Reminder set karo 5 baje", "reminder tomorrow 9 am",
          "reminder 16 nov 1 am".
        - English, Hindi and Hinglish are supported.
        - Jarvis saves the reminder, sets an alarm, and confirms naturally.
        - Reminders are restored after a restart.

        MEMORY
        - Jarvis remembers useful facts about you over time.
        - Open MEMORY MANAGER to view, add, edit, delete or clear memories.
        - These memories are used in future conversations.

        NOTEPAD
        - Open the notepad from the main screen or say "notes kholo".
        - Write quick notes and set a reminder for any note.

        MULTI-BRAIN
        - Open MULTI BRAIN MANAGER to add API keys and models for:
          Gemini Live, Groq and Uncensored AI.

        SETTINGS
        - RESPONSE MODE: turn Uncensored mode ON or OFF.
        - VOICE: choose and preview Jarvis's voice.
        - SYSTEM INSTRUCTIONS: add your own rules for how Jarvis should talk.
        - ORB DESIGN: choose the centre animation.
        - VOICE CONTROL: explains mute and "Hey Jarvis" unmute.
        - CHECK FOR UPDATES: looks for a new Jarvis APK.
        - SAVE CONFIGURATION: saves your changes.

        PRIVACY
        - Your API keys and settings stay on this device.
        - During a live session, your voice is sent to Gemini for the conversation.
        - While muted, no audio is sent to Gemini. The local "Hey Jarvis"
          detector is only for unmuting.
        - Jarvis does not read your screen. "Display over other apps" is only
          used to open apps from the background.
    """.trimIndent()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context)
        root.addView(
            JarvisBackgroundView(context),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20f), context.dp(18f), context.dp(20f), context.dp(38f))
        }
        scroll.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = JarvisButton(context, context.dp(42f), Palette.cyanDim, R.drawable.ic_back).apply {
            contentDescription = "Close settings"
            setOnClickListener { dismiss() }
        }
        top.addView(back, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(context.label("SETTINGS", 19f, Palette.ice, 0.22f, true))
        titleColumn.addView(
            context.label("MULTI-BRAIN CONTROL", 8.5f, Palette.cyanDim, 0.24f, false),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(3f)
            }
        )
        top.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(12f)
        })
        content.addView(top)

        val managerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 15f, 1f)
            setPadding(context.dp(14f), context.dp(13f), context.dp(14f), context.dp(13f))
            isClickable = true
            contentDescription = "Open multi brain manager"
            setOnClickListener { MultiBrainManagerDialog(context).show() }
        }
        val managerText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        managerText.addView(context.label("MULTI BRAIN MANAGER", 12f, Palette.ice, 0.12f, true))
        managerText.addView(
            context.label("Manage AI providers, API keys and models.", 10f, Palette.textFaint, 0.02f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(3f)
            }
        )
        managerRow.addView(managerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        managerRow.addView(context.label("›", 18f, Palette.cyan, 0f, true))
        content.addView(managerRow, matchWrap().apply { topMargin = context.dp(18f) })

        val checkUpdates = TextView(context).apply {
            text = "CHECK FOR UPDATES"
            textSize = 10.5f
            letterSpacing = 0.22f
            typeface = context.font(10.5f, true)
            gravity = Gravity.CENTER
            setTextColor(Palette.cyan)
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 15f, 1f)
            setPadding(context.dp(14f), context.dp(15f), context.dp(14f), context.dp(15f))
            isClickable = true
            contentDescription = "Check for app updates"
            setOnClickListener { onCheckUpdates() }
        }
        content.addView(checkUpdates, matchWrap().apply { topMargin = context.dp(8f) })

        voiceValue = addSelector(content, "VOICE", selectedVoice) { valueView ->
            showVoiceChooser(valueView)
        }
        content.addView(
            helperText("Tap a voice to select and preview it. The choice is saved right away and used for the next live session."),
            matchWrap().apply { topMargin = context.dp(8f) }
        )

        val memoryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 15f, 1f)
            setPadding(context.dp(14f), context.dp(13f), context.dp(14f), context.dp(13f))
            isClickable = true
            contentDescription = "Open memory manager"
            setOnClickListener { MemoryManagerDialog(context).show() }
        }
        val memoryText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        memoryText.addView(context.label("MEMORY MANAGER", 12f, Palette.ice, 0.12f, true))
        memoryText.addView(
            context.label("View, add, edit or delete long-term memories", 10f, Palette.textFaint, 0.02f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(3f)
            }
        )
        memoryRow.addView(memoryText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        memoryRow.addView(context.label("›", 18f, Palette.cyan, 0f, true))
        content.addView(memoryRow, matchWrap().apply { topMargin = context.dp(18f) })

        addSection(content, "RESPONSE MODE")
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 15f, 1f)
            setPadding(context.dp(14f), context.dp(13f), context.dp(14f), context.dp(13f))
        }
        val modeText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        modeText.addView(context.label("Uncensored mode", 14.5f, Palette.ice, 0.01f))
        modeText.addView(
            context.label("Direct answers, no extra disclaimers", 10f, Palette.textFaint, 0.02f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(3f)
            }
        )
        modeRow.addView(modeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val modeToggle = context.label("OFF", 11f, Palette.cyan, 0.16f, true, Gravity.CENTER).apply {
            setPadding(context.dp(18f), context.dp(8f), context.dp(18f), context.dp(8f))
            isClickable = true
            contentDescription = "Toggle uncensored mode"
        }
        fun refreshModeToggle() {
            val on = selectedUncensored
            modeToggle.text = if (on) "ON" else "OFF"
            modeToggle.setTextColor(if (on) Palette.bg else Palette.cyan)
            modeToggle.background = context.roundedBackground(
                if (on) Palette.cyan else Palette.panelSoft,
                if (on) Palette.cyan else withAlpha(Palette.cyan, 0.4f),
                20f,
                1f
            )
        }
        modeToggle.setOnClickListener {
            selectedUncensored = !selectedUncensored
            refreshModeToggle()
        }
        refreshModeToggle()
        modeRow.addView(modeToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(modeRow, matchWrap().apply { topMargin = context.dp(8f) })

        addSection(content, "BACKGROUND APP CONTROL")
        val overlayAllowed = Settings.canDrawOverlays(context)
        val overlayRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(
                Palette.panelSoft,
                withAlpha(Palette.cyan, 0.25f),
                15f,
                1f
            )
            setPadding(context.dp(14f), context.dp(12f), context.dp(14f), context.dp(12f))
            isClickable = true
        }
        val overlayText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        overlayText.addView(context.label("Display over other apps", 13.5f, Palette.ice, 0.01f))
        overlayText.addView(
            context.label(
                "Open another app while Jarvis is in the background",
                10f,
                Palette.textFaint,
                0.02f
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(3f) }
        )
        overlayRow.addView(
            overlayText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        overlayRow.addView(
            context.label(
                if (overlayAllowed) "ALLOWED" else "SET",
                10.5f,
                Palette.cyan,
                0.12f,
                true,
                Gravity.CENTER
            )
        )
        overlayRow.setOnClickListener {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                }
            }
        }
        content.addView(overlayRow, matchWrap().apply { topMargin = context.dp(8f) })
        content.addView(
            helperText("Required for \"WhatsApp kholo\" after YouTube. Android may show a Play Protect warning for this permission; Jarvis never reads your screen."),
            matchWrap().apply { topMargin = context.dp(8f) }
        )

        addSection(content, "VOICE CONTROL")
        content.addView(
            helperText("While muted, say \u201cHey Jarvis\u201d to unmute. Jarvis only listens for this locally during a muted live session; there is no background wake service."),
            matchWrap().apply { topMargin = context.dp(8f) }
        )

        addSection(content, "SYSTEM INSTRUCTIONS")
        val instructionHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        instructionHeader.addView(
            helperText("Optional."),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val clearInstructions = context.label("CLEAR", 9f, Palette.cyan, 0.18f, true).apply {
            setPadding(context.dp(12f), context.dp(6f), context.dp(2f), context.dp(6f))
            isClickable = true
            contentDescription = "Clear system instructions"
            setOnClickListener {
                instructionInput.setText("")
                instructionInput.requestFocus()
                Toast.makeText(context, "Instructions cleared — tap Save to apply", Toast.LENGTH_SHORT).show()
            }
        }
        instructionHeader.addView(clearInstructions)
        content.addView(instructionHeader, matchWrap().apply { topMargin = context.dp(4f) })
        instructionInput = EditText(context).apply {
            setText(JarvisPrefs.additionalInstructions)
            hint = "Add your own optional instructions"
            setHintTextColor(withAlpha(Palette.textDim, 0.75f))
            setTextColor(Palette.ice)
            textSize = 14f
            typeface = context.font(14f)
            gravity = Gravity.TOP
            background = null
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 8
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setPadding(0, context.dp(10f), 0, context.dp(10f))
        }
        val instructionBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 15f, 1f)
            setPadding(context.dp(14f), context.dp(4f), context.dp(14f), context.dp(4f))
        }
        instructionBox.addView(instructionInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(instructionBox, matchWrap().apply { topMargin = context.dp(8f) })
        content.addView(
            helperText("These are optional additions only. The permanent Jarvis core identity is never shown here and cannot be viewed, edited, cleared or overridden.").apply {
                maxLines = 6
            },
            matchWrap().apply { topMargin = context.dp(8f) }
        )

        addSection(content, "ORB DESIGN")
        content.addView(
            helperText("Choose the hologram that appears in the centre of the console."),
            matchWrap().apply { topMargin = context.dp(4f) }
        )
        val grid = GridLayout(context).apply { columnCount = 2 }
        content.addView(grid, matchWrap().apply { topMargin = context.dp(8f) })
        for (i in JarvisPrefs.orbStyles.indices) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(context.dp(8f), context.dp(12f), context.dp(8f), context.dp(10f))
                background = orbCard(i == selectedOrb)
                isClickable = true
                contentDescription = JarvisPrefs.orbStyles[i]
            }
            val preview = OrbView(context).apply {
                orbStyle = i
                mode = OrbView.Mode.LISTENING
            }
            cell.addView(preview, LinearLayout.LayoutParams(context.dp(92f), context.dp(92f)))
            cell.addView(
                context.label(
                    JarvisPrefs.orbStyles[i].uppercase(),
                    9f,
                    if (i == selectedOrb) Palette.ice else Palette.textDim,
                    0.16f,
                    true,
                    Gravity.CENTER
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(6f)
                }
            )
            cell.setOnClickListener { selectOrb(i) }
            orbCells.add(cell)
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(i % 2, 1f)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(context.dp(4f), context.dp(4f), context.dp(4f), context.dp(4f))
            })
        }

        val saveRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val save = TextView(context).apply {
            text = "SAVE CONFIGURATION"
            textSize = 12.5f
            letterSpacing = 0.24f
            typeface = context.font(12.5f, true)
            gravity = Gravity.CENTER
            setTextColor(Palette.ice)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(withAlpha(Palette.cyan, 0.28f), withAlpha(Palette.cyanDim, 0.14f))
            ).apply {
                cornerRadius = context.dpf(16f)
                setStroke(context.dpf(1.4f).toInt().coerceAtLeast(1), withAlpha(Palette.cyan, 0.75f))
            }
            setPadding(0, context.dp(16f), 0, context.dp(16f))
            isClickable = true
            setOnClickListener { save() }
        }
        saveRow.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val help = TextView(context).apply {
            text = "?"
            textSize = 20f
            typeface = context.font(20f, true)
            gravity = Gravity.CENTER
            setTextColor(Palette.cyan)
            background = context.roundedBackground(
                Palette.panelSoft,
                withAlpha(Palette.cyan, 0.55f),
                16f,
                1.2f
            )
            contentDescription = "Open Jarvis help"
            isClickable = true
            setOnClickListener { showHelp() }
        }
        saveRow.addView(
            help,
            LinearLayout.LayoutParams(context.dp(52f), context.dp(52f)).apply {
                marginStart = context.dp(10f)
            }
        )
        content.addView(saveRow, matchWrap().apply { topMargin = context.dp(28f) })

        content.addView(
            context.label(
                "Built with the Gemini Live API  •  jarvis.assistant",
                9f,
                Palette.textFaint,
                0.08f,
                false,
                Gravity.CENTER
            ),
            matchWrap().apply { topMargin = context.dp(18f) }
        )

        setContentView(root)
        content.applySystemBarPadding()
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(Palette.bg))
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.statusBarColor = Palette.bg
            window.navigationBarColor = Palette.bg
        }
    }

    private fun showHelp() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(22f), context.dp(20f), context.dp(22f), context.dp(16f))
            background = context.roundedBackground(Palette.bg, withAlpha(Palette.cyan, 0.45f), 22f, 1.2f)
        }
        root.addView(context.label("JARVIS HELP", 19f, Palette.ice, 0.22f, true))
        root.addView(
            context.label("All features in simple English", 9.5f, Palette.cyanDim, 0.18f),
            matchWrap().apply { topMargin = context.dp(3f) }
        )
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
        }
        val body = context.label(helpText, 12.5f, Palette.ice).apply {
            setLineSpacing(context.dpf(4f), 1f)
            setTextIsSelectable(true)
            setPadding(0, context.dp(14f), 0, context.dp(14f))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val close = TextView(context).apply {
            text = "CLOSE"
            textSize = 11f
            letterSpacing = 0.2f
            typeface = context.font(11f, true)
            gravity = Gravity.CENTER
            setTextColor(Palette.cyan)
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.4f), 15f, 1f)
            setPadding(0, context.dp(12f), 0, context.dp(12f))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close, matchWrap().apply { topMargin = context.dp(10f) })
        dialog.setContentView(root)
        dialog.setOnShowListener {
            root.applySystemBarPadding()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(
            context.label(title, 9.5f, Palette.cyanDim, 0.28f, true),
            matchWrap().apply { topMargin = context.dp(24f) }
        )
    }

    private fun addSelector(
        parent: LinearLayout,
        title: String,
        initial: String,
        onClick: (TextView) -> Unit
    ): TextView {
        addSection(parent, title)
        val valueView = context.label(initial, 14.5f, Palette.ice, 0.01f)
        val chevron = context.label("⌄", 16f, Palette.cyan, 0f, true)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 15f, 1f)
            setPadding(context.dp(14f), context.dp(15f), context.dp(14f), context.dp(15f))
            isClickable = true
            setOnClickListener { onClick(valueView) }
        }
        box.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(chevron)
        parent.addView(box, matchWrap().apply { topMargin = context.dp(8f) })
        return valueView
    }

    private fun helperText(text: String): TextView =
        context.label(text, 10.5f, Palette.textFaint, 0.02f, false).apply {
            maxLines = 3
        }

    private fun showVoiceChooser(valueView: TextView) {
        val labels = JarvisPrefs.voices
        var chosen = selectedVoice
        val previewOrb = OrbView(context).apply {
            orbStyle = selectedOrb
            mode = OrbView.Mode.LISTENING
            setLevel(0f)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(20f), context.dp(10f), context.dp(20f), context.dp(6f))
        }
        header.addView(previewOrb, LinearLayout.LayoutParams(context.dp(118f), context.dp(118f)))
        header.addView(
            context.label(
                "Tap a voice to hear the live Gemini preview",
                11f,
                Palette.textDim,
                0.02f,
                false,
                Gravity.CENTER
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(4f)
            }
        )

        val originalVoice = selectedVoice
        val checked = labels.indexOf(selectedVoice).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Voice preview")
            .setView(header)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { _, which ->
                chosen = labels[which]
                selectedVoice = chosen
                valueView.text = chosen
                JarvisPrefs.voice = chosen
                Toast.makeText(context, "Voice set to $chosen", Toast.LENGTH_SHORT).show()
                previewVoice(chosen, previewOrb)
            }
            .setPositiveButton("Use voice") { _, _ ->
                selectedVoice = chosen
                valueView.text = chosen
                JarvisPrefs.voice = chosen
                stopPreview()
            }
            .setNegativeButton("Cancel") { _, _ ->
                selectedVoice = originalVoice
                valueView.text = originalVoice
                JarvisPrefs.voice = originalVoice
                stopPreview()
            }
            .create()
        dialog.setOnDismissListener {
            stopPreview()
            previewOrb.setLevel(0f)
        }
        dialog.show()
    }

    private fun previewVoice(voiceName: String, previewOrb: OrbView) {
        val key = JarvisPrefs.apiKey.trim()
        if (key.isBlank()) {
            Toast.makeText(context, "Add your Gemini API key to preview voices", Toast.LENGTH_LONG).show()
            return
        }
        stopPreview()
        val token = ++previewToken
        previewOrb.mode = OrbView.Mode.LISTENING
        previewOrb.setLevel(0f)
        val player = VoicePreview(
            key,
            JarvisPrefs.model,
            voiceName,
            object : VoicePreview.Callback {
                override fun onLevel(level: Float) {
                    previewOrb.post { previewOrb.setLevel(level * 0.75f) }
                }

                override fun onFinished() {
                    if (token != previewToken) return
                    preview = null
                    previewOrb.post {
                        previewOrb.setLevel(0f)
                        previewOrb.mode = OrbView.Mode.LISTENING
                    }
                }

                override fun onError(message: String) {
                    if (token != previewToken) return
                    preview = null
                    previewOrb.post { previewOrb.setLevel(0f) }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        )
        preview = player
        player.start()
    }

    private fun stopPreview() {
        previewToken++
        val player = preview
        preview = null
        player?.stop()
    }

    private fun showChoice(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                onPick(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectOrb(index: Int) {
        selectedOrb = index
        orbCells.forEachIndexed { cellIndex, cell ->
            cell.background = orbCard(cellIndex == index)
            val text = cell.getChildAt(1) as? TextView ?: return@forEachIndexed
            text.setTextColor(if (cellIndex == index) Palette.ice else Palette.textDim)
        }
    }

    private fun orbCard(selected: Boolean): GradientDrawable = context.roundedBackground(
        if (selected) withAlpha(Palette.cyan, 0.10f) else Palette.panel,
        if (selected) withAlpha(Palette.cyan, 0.68f) else withAlpha(Palette.cyan, 0.13f),
        18f,
        if (selected) 1.5f else 1f
    )

    private fun save() {
        stopPreview()
        JarvisPrefs.voice = selectedVoice
        JarvisPrefs.additionalInstructions = instructionInput.text.toString()
        JarvisPrefs.orbStyle = selectedOrb
        JarvisPrefs.uncensored = selectedUncensored
        onSaved()
        dismiss()
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
