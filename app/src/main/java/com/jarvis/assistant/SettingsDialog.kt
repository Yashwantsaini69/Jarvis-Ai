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
    private lateinit var keyInput: EditText
    private lateinit var instructionInput: EditText
    private lateinit var modelValue: TextView
    private lateinit var voiceValue: TextView

    private var selectedModel = JarvisPrefs.model
    private var selectedVoice = JarvisPrefs.voice
    private var selectedOrb = JarvisPrefs.orbStyle
    private var selectedUncensored = JarvisPrefs.uncensored
    private val orbCells = mutableListOf<LinearLayout>()
    private var preview: VoicePreview? = null
    private var previewToken = 0

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
            context.label("GEMINI LIVE CONFIGURATION", 8.5f, Palette.cyanDim, 0.24f, false),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(3f)
            }
        )
        top.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(12f)
        })
        content.addView(top)

        addSection(content, "GEMINI API KEY")
        keyInput = EditText(context).apply {
            setSingleLine()
            hint = "AIza..."
            setHintTextColor(withAlpha(Palette.textDim, 0.75f))
            setTextColor(Palette.ice)
            textSize = 14.5f
            typeface = context.font(14.5f)
            background = null
            includeFontPadding = false
            setPadding(0, context.dp(10f), 0, context.dp(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(JarvisPrefs.apiKey)
            setSelection(text.length)
        }
        val keyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyRow.addView(keyInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val showKey = context.label("SHOW", 9f, Palette.cyan, 0.18f, true).apply {
            setPadding(context.dp(12f), context.dp(10f), context.dp(2f), context.dp(10f))
            isClickable = true
        }
        showKey.setOnClickListener {
            val revealing = keyInput.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val position = keyInput.selectionEnd.coerceAtMost(keyInput.text.length)
            keyInput.inputType = if (revealing) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            keyInput.setSelection(position)
            showKey.text = if (revealing) "HIDE" else "SHOW"
        }
        keyRow.addView(showKey)
        content.addView(
            inputBox(keyRow),
            matchWrap().apply { topMargin = context.dp(8f) }
        )
        val keyHelpRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyHelpRow.addView(
            helperText("Create your own key at Google AI Studio. It is stored only on this device."),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val getKeyLink = context.label("GET API KEY  →", 9.5f, Palette.cyan, 0.18f, true).apply {
            setPadding(context.dp(10f), context.dp(10f), 0, context.dp(10f))
            isClickable = true
            contentDescription = "Open Google AI Studio to create an API key"
            setOnClickListener {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                    )
                } catch (_: Throwable) {
                    Toast.makeText(context, "No browser available to open the link", Toast.LENGTH_SHORT).show()
                }
            }
        }
        keyHelpRow.addView(getKeyLink)
        content.addView(keyHelpRow, matchWrap().apply { topMargin = context.dp(8f) })

        modelValue = addSelector(content, "LIVE MODEL", JarvisPrefs.modelLabel()) { valueView ->
            val labels = JarvisPrefs.models.map { it.label }
            val checked = JarvisPrefs.models.indexOfFirst { it.id == selectedModel }.coerceAtLeast(0)
            showChoice("Live model", labels, checked) { index ->
                selectedModel = JarvisPrefs.models[index].id
                valueView.text = labels[index]
            }
        }

        voiceValue = addSelector(content, "VOICE", selectedVoice) { valueView ->
            showVoiceChooser(valueView)
        }
        content.addView(
            helperText("Tap a voice to select and preview it. The choice is saved right away and used for the next live session."),
            matchWrap().apply { topMargin = context.dp(8f) }
        )

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
        content.addView(
            helperText("When on, Jarvis uses a stronger prompt to drop refusals and disclaimers. Gemini's built-in safety may still block some topics. Applies to the next live session."),
            matchWrap().apply { topMargin = context.dp(8f) }
        )

        addSection(content, "SYSTEM INSTRUCTIONS")
        val instructionHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        instructionHeader.addView(
            helperText("Optional. The core Jarvis identity stays hidden and always takes priority."),
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
        content.addView(save, matchWrap().apply { topMargin = context.dp(28f) })

        val checkUpdates = TextView(context).apply {
            text = "CHECK FOR UPDATES"
            textSize = 10.5f
            letterSpacing = 0.22f
            typeface = context.font(10.5f, true)
            gravity = Gravity.CENTER
            setTextColor(Palette.cyan)
            setPadding(0, context.dp(14f), 0, context.dp(14f))
            isClickable = true
            contentDescription = "Check for app updates"
            setOnClickListener { onCheckUpdates() }
        }
        content.addView(checkUpdates, matchWrap().apply { topMargin = context.dp(10f) })

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

    private fun inputBox(contentView: View): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 15f, 1f)
            minimumHeight = context.dp(54f)
            setPadding(context.dp(14f), context.dp(2f), context.dp(14f), context.dp(2f))
        }
        box.addView(contentView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
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
        val key = keyInput.text.toString().trim()
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
            selectedModel,
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
        JarvisPrefs.apiKey = keyInput.text.toString()
        JarvisPrefs.model = selectedModel
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
