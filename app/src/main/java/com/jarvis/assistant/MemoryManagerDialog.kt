package com.jarvis.assistant

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MemoryManagerDialog(context: Context) : Dialog(context) {
    private lateinit var editor: EditText
    private lateinit var countLabel: TextView
    private lateinit var editButton: TextView

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

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20f), context.dp(18f), context.dp(20f), context.dp(14f))
        }
        root.addView(
            outer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = JarvisButton(context, context.dp(42f), Palette.cyanDim, R.drawable.ic_back).apply {
            contentDescription = "Close memory"
            setOnClickListener { dismiss() }
        }
        top.addView(back, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(context.label("MEMORY", 19f, Palette.ice, 0.2f, true))
        countLabel = context.label("", 9f, Palette.textDim, 0.18f, true)
        titleColumn.addView(
            countLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(3f)
            }
        )
        top.addView(
            titleColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(12f)
            }
        )
        val clearTop = actionButton("CLEAR", Palette.danger) { confirmClear() }
        top.addView(
            clearTop,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = context.dp(10f) }
        )
        outer.addView(top)

        val hintLine = context.label(
            "One long memory. Jarvis reads all of it. New facts are added automatically; tap EDIT to change it.",
            11f,
            Palette.textDim,
            0f,
            false
        ).apply {
            setLineSpacing(0f, 1.25f)
        }
        outer.addView(
            hintLine,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(12f)
            }
        )

        editor = EditText(context).apply {
            setText(MemoryStore.document())
            hint = "Name, city, goals, likes, projects... anything Jarvis should remember."
            setHintTextColor(withAlpha(Palette.textDim, 0.75f))
            setTextColor(Palette.ice)
            textSize = 14f
            typeface = context.font(14f)
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.20f), 16f, 1f)
            setPadding(context.dp(14f), context.dp(14f), context.dp(14f), context.dp(14f))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            setLineSpacing(0f, 1.25f)
            includeFontPadding = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateCount()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        outer.addView(
            editor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = context.dp(12f)
            }
        )

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        editButton = actionButton("EDIT", Palette.cyanDim) { setEditing(true) }
        bottom.addView(editButton)
        val save = actionButton("SAVE MEMORY", Palette.cyan) { save() }
        bottom.addView(
            save,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(10f)
            }
        )
        outer.addView(
            bottom,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(12f)
            }
        )

        setContentView(root)
        outer.applySystemBarPadding()
        setEditing(false)
    }

    override fun onStart() {
        super.onStart()
        window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(Palette.bg))
            window.statusBarColor = Palette.bg
            window.navigationBarColor = Palette.bg
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }
        updateCount()
    }

    private fun updateCount() {
        if (!::countLabel.isInitialized || !::editor.isInitialized) return
        val length = editor.text?.length ?: 0
        countLabel.text = if (length == 0) "EMPTY - JARVIS REMEMBERS NOTHING YET" else "$length CHARACTERS"
    }

    private fun setEditing(enabled: Boolean) {
        if (!::editor.isInitialized || !::editButton.isInitialized) return
        editor.isFocusable = enabled
        editor.isFocusableInTouchMode = enabled
        editor.isCursorVisible = enabled
        editor.setTextIsSelectable(enabled)
        editor.movementMethod =
            if (enabled) ArrowKeyMovementMethod.getInstance() else ScrollingMovementMethod.getInstance()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (enabled) {
            editButton.text = "EDITING"
            editButton.alpha = 0.5f
            editButton.isEnabled = false
            editor.requestFocus()
            editor.post { imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            editButton.text = "EDIT"
            editButton.alpha = 1f
            editButton.isEnabled = true
            editor.clearFocus()
            imm.hideSoftInputFromWindow(editor.windowToken, 0)
        }
    }

    private fun save() {
        val ok = MemoryStore.saveDocument(editor.text?.toString().orEmpty())
        if (ok) {
            editor.setText(MemoryStore.document())
            updateCount()
            setEditing(false)
            Toast.makeText(context, "Memory saved", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Could not save memory", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Clear all memory?")
            .setMessage("This removes everything Jarvis remembers about you.")
            .setPositiveButton("CLEAR") { _, _ ->
                MemoryStore.clearDocument()
                editor.setText("")
                updateCount()
                Toast.makeText(context, "Memory cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun actionButton(text: String, accent: Int, onClick: () -> Unit): TextView {
        val filled = accent == Palette.cyan
        return context.label(
            text,
            13f,
            if (filled) Palette.bg else accent,
            0.08f,
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(18f), context.dp(14f), context.dp(18f), context.dp(14f))
            background = context.roundedBackground(
                if (filled) Palette.cyan else withAlpha(accent, 0.12f),
                if (filled) Palette.cyan else withAlpha(accent, 0.55f),
                14f,
                1f
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
}
