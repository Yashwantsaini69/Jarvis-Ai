package com.jarvis.assistant

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MultiBrainManagerDialog(context: Context) : Dialog(context) {
    private lateinit var geminiKeyInput: EditText
    private lateinit var geminiModelValue: TextView
    private lateinit var groqKeyInput: EditText
    private lateinit var groqModelValue: TextView
    private var groqModels: List<String> = emptyList()
    private var groqModelsKey: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var selectedGeminiModel = JarvisPrefs.model
    private var selectedGroqModel = JarvisPrefs.groqModel
    private var selectedUncensoredModel = JarvisPrefs.uncensoredModel
    private lateinit var uncensoredKeyInput: EditText
    private lateinit var uncensoredModelValue: TextView
    private var uncensoredModels: List<String> = emptyList()
    private var uncensoredModelsSignature: String? = null
    private lateinit var uncensoredUrlInput: EditText

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
            contentDescription = "Close multi brain manager"
            setOnClickListener { dismiss() }
        }
        top.addView(back, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(context.label("MULTI BRAIN MANAGER", 19f, Palette.ice, 0.2f, true))
        titleColumn.addView(
            context.label("PROVIDERS · KEYS · MODELS", 8.5f, Palette.cyanDim, 0.24f, false),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(3f)
            }
        )
        top.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(12f)
        })
        content.addView(top)

        addSection(content, "GEMINI", 24f)
        geminiKeyInput = addKeyField(content, "AIza...", JarvisPrefs.apiKey)
        addLinkRow(
            content,
            "Create your own key at Google AI Studio. It is stored only on this device.",
            "GET API KEY  →",
            "https://aistudio.google.com/apikey",
            "Open Google AI Studio to create an API key"
        )
        geminiModelValue = addSelector(content, "GEMINI LIVE MODEL", JarvisPrefs.modelLabel()) { valueView ->
            val labels = JarvisPrefs.models.map { it.label }
            val checked = JarvisPrefs.models.indexOfFirst { it.id == selectedGeminiModel }.coerceAtLeast(0)
            showChoice("Gemini live model", labels, checked) { index ->
                selectedGeminiModel = JarvisPrefs.models[index].id
                valueView.text = labels[index]
            }
        }
        addSaveButton(content, "SAVE GEMINI") { saveGemini() }

        addSection(content, "GROQ", 26f)
        groqKeyInput = addKeyField(content, "gsk_...", JarvisPrefs.groqApiKey)
        addLinkRow(
            content,
            "Create your own key at Groq Console. It is stored only on this device.",
            "GET KEY  →",
            "https://console.groq.com/keys",
            "Open Groq Console to create an API key"
        )
        groqModelValue = addSelector(content, "GROQ MODEL", selectedGroqModel.ifBlank { "Select model" }) { valueView ->
            showGroqChooser(valueView)
        }
        addSaveButton(content, "SAVE GROQ") { saveGroq() }

        addSection(content, "UNCENSORED AI", 26f)
        uncensoredKeyInput = addKeyField(content, "API key", JarvisPrefs.uncensoredApiKey)
        content.addView(
            context.label("BASE URL", 8.5f, Palette.cyanDim, 0.22f, true),
            matchWrap().apply { topMargin = context.dp(14f) }
        )
        uncensoredUrlInput = EditText(context).apply {
            setSingleLine()
            hint = "https://api.example.com/v1"
            setHintTextColor(withAlpha(Palette.textDim, 0.75f))
            setTextColor(Palette.ice)
            textSize = 14.5f
            typeface = context.font(14.5f)
            background = null
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(JarvisPrefs.uncensoredBaseUrl)
            setSelection(text.length)
        }
        content.addView(inputBox(uncensoredUrlInput), matchWrap().apply { topMargin = context.dp(8f) })
        uncensoredModelValue = addSelector(
            content,
            "AVAILABLE MODEL",
            selectedUncensoredModel.ifBlank { "Select model" }
        ) { valueView ->
            showUncensoredChooser(valueView)
        }
        addSaveButton(content, "SAVE UNCENSORED AI") { saveUncensored() }

        setContentView(root)
        content.applySystemBarPadding()
        resolveGroqModelOnOpen()
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

    private fun addSection(parent: LinearLayout, title: String, topMarginDp: Float) {
        parent.addView(
            context.label(title, 9.5f, Palette.cyanDim, 0.28f, true),
            matchWrap().apply { topMargin = context.dp(topMarginDp) }
        )
    }

    private fun addKeyField(parent: LinearLayout, hintText: String, initial: String): EditText {
        val input = EditText(context).apply {
            setSingleLine()
            hint = hintText
            setHintTextColor(withAlpha(Palette.textDim, 0.75f))
            setTextColor(Palette.ice)
            textSize = 14.5f
            typeface = context.font(14.5f)
            background = null
            includeFontPadding = false
            setPadding(0, context.dp(10f), 0, context.dp(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(initial)
            setSelection(text.length)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val showKey = context.label("SHOW", 9f, Palette.cyan, 0.18f, true).apply {
            setPadding(context.dp(12f), context.dp(10f), context.dp(2f), context.dp(10f))
            isClickable = true
        }
        showKey.setOnClickListener {
            val revealing = input.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val position = input.selectionEnd.coerceAtMost(input.text.length)
            input.inputType = if (revealing) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            input.setSelection(position)
            showKey.text = if (revealing) "HIDE" else "SHOW"
        }
        row.addView(showKey)
        parent.addView(inputBox(row), matchWrap().apply { topMargin = context.dp(8f) })
        return input
    }

    private fun addLinkRow(parent: LinearLayout, text: String, linkLabel: String, url: String, description: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            helperText(text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val link = context.label(linkLabel, 9.5f, Palette.cyan, 0.18f, true).apply {
            setPadding(context.dp(10f), context.dp(10f), 0, context.dp(10f))
            isClickable = true
            contentDescription = description
            setOnClickListener {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Throwable) {
                    Toast.makeText(context, "No browser available to open the link", Toast.LENGTH_SHORT).show()
                }
            }
        }
        row.addView(link)
        parent.addView(row, matchWrap().apply { topMargin = context.dp(8f) })
    }

    private fun addSelector(
        parent: LinearLayout,
        title: String,
        initial: String,
        onClick: (TextView) -> Unit
    ): TextView {
        addSection(parent, title, 20f)
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

    private fun addSaveButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val button = TextView(context).apply {
            text = label
            textSize = 10.5f
            letterSpacing = 0.22f
            typeface = context.font(10.5f, true)
            gravity = Gravity.CENTER
            setTextColor(Palette.bg)
            background = context.roundedBackground(Palette.cyan, Palette.cyan, 15f, 1f)
            setPadding(context.dp(14f), context.dp(15f), context.dp(14f), context.dp(15f))
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
        }
        parent.addView(button, matchWrap().apply { topMargin = context.dp(12f) })
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

    private fun saveGemini() {
        JarvisPrefs.apiKey = geminiKeyInput.text.toString()
        JarvisPrefs.model = selectedGeminiModel
        Toast.makeText(context, "Gemini configuration saved", Toast.LENGTH_SHORT).show()
    }

    private fun saveGroq() {
        JarvisPrefs.groqApiKey = groqKeyInput.text.toString()
        JarvisPrefs.groqModel = selectedGroqModel
        Toast.makeText(context, "Groq configuration saved", Toast.LENGTH_SHORT).show()
        if (selectedGroqModel.isBlank()) {
            resolveGroqModelOnOpen()
        }
    }

    private fun saveUncensored() {
        JarvisPrefs.uncensoredApiKey = uncensoredKeyInput.text.toString()
        JarvisPrefs.uncensoredBaseUrl = uncensoredUrlInput.text.toString()
        JarvisPrefs.uncensoredModel = selectedUncensoredModel
        Toast.makeText(context, "Uncensored AI configuration saved", Toast.LENGTH_SHORT).show()
    }

    private fun resolveGroqModelOnOpen() {
        val key = JarvisPrefs.groqApiKey
        if (key.isBlank()) return
        Thread {
            val fetched = GroqClient.fetchModels(key)
            main.post {
                if (!isShowing || fetched.isEmpty()) return@post
                groqModels = fetched
                groqModelsKey = key
                if (selectedGroqModel.isBlank() || !fetched.contains(selectedGroqModel)) {
                    selectedGroqModel = GroqClient.pickChatModel(fetched, selectedGroqModel)
                    JarvisPrefs.groqModel = selectedGroqModel
                    groqModelValue.text = selectedGroqModel
                }
            }
        }.start()
    }

    private fun showGroqChooser(valueView: TextView) {
        val key = groqKeyInput.text.toString().trim()
        if (groqModels.isNotEmpty() && groqModelsKey == key) {
            presentGroqChoices(valueView)
            return
        }
        if (key.isBlank()) {
            groqModels = JarvisPrefs.groqFallbackModels
            groqModelsKey = key
            presentGroqChoices(valueView)
            return
        }
        Toast.makeText(context, "Fetching Groq models…", Toast.LENGTH_SHORT).show()
        Thread {
            val fetched = GroqClient.fetchModels(key, true)
            main.post {
                if (!isShowing) return@post
                groqModels = if (fetched.isNotEmpty()) fetched else JarvisPrefs.groqFallbackModels
                groqModelsKey = key
                if (fetched.isEmpty()) {
                    Toast.makeText(context, "Could not fetch models — showing defaults", Toast.LENGTH_SHORT).show()
                }
                presentGroqChoices(valueView)
            }
        }.start()
    }

    private fun presentGroqChoices(valueView: TextView) {
        val items = groqModels.ifEmpty { JarvisPrefs.groqFallbackModels }
        if (items.isNotEmpty() && !items.contains(selectedGroqModel)) {
            selectedGroqModel = GroqClient.pickChatModel(items, selectedGroqModel)
            valueView.text = selectedGroqModel
            JarvisPrefs.groqModel = selectedGroqModel
        }
        val checked = items.indexOf(selectedGroqModel).coerceAtLeast(0)
        showChoice("Groq model", items, checked) { index ->
            selectedGroqModel = items[index]
            valueView.text = items[index]
        }
    }

    private fun showUncensoredChooser(valueView: TextView) {
        val key = uncensoredKeyInput.text.toString().trim()
        val base = uncensoredUrlInput.text.toString().trim()
        val signature = "$base|$key"
        if (uncensoredModels.isNotEmpty() && uncensoredModelsSignature == signature) {
            presentUncensoredChoices(valueView)
            return
        }
        if (base.isBlank()) {
            presentUncensoredChoices(valueView)
            return
        }
        Toast.makeText(context, "Fetching available models…", Toast.LENGTH_SHORT).show()
        Thread {
            val fetched = UncensoredClient.fetchModels(base, key)
            main.post {
                if (!isShowing) return@post
                uncensoredModels = fetched
                uncensoredModelsSignature = signature
                if (fetched.isEmpty()) {
                    Toast.makeText(context, "Could not fetch models — enter one manually", Toast.LENGTH_SHORT).show()
                }
                presentUncensoredChoices(valueView)
            }
        }.start()
    }

    private fun presentUncensoredChoices(valueView: TextView) {
        val items = uncensoredModels
        val choices = items + "ENTER MODEL MANUALLY…"
        val checked = items.indexOf(selectedUncensoredModel).takeIf { it >= 0 } ?: items.size
        showChoice("Available model", choices, checked) { index ->
            if (index < items.size) {
                selectedUncensoredModel = items[index]
                valueView.text = items[index]
            } else {
                promptUncensoredModel(valueView)
            }
        }
    }

    private fun promptUncensoredModel(valueView: TextView) {
        val input = EditText(context).apply {
            setSingleLine()
            hint = "model-id"
            setHintTextColor(withAlpha(Palette.textDim, 0.75f))
            setTextColor(Palette.ice)
            textSize = 14.5f
            typeface = context.font(14.5f)
            setText(selectedUncensoredModel)
            setSelection(text.length)
        }
        val wrapper = FrameLayout(context).apply {
            setPadding(context.dp(20f), context.dp(8f), context.dp(20f), 0)
        }
        wrapper.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Uncensored AI model")
            .setView(wrapper)
            .setPositiveButton("Use") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    Toast.makeText(context, "Model id cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    selectedUncensoredModel = value
                    valueView.text = value
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
