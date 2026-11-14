package com.jarvis.assistant

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NotepadDialog(context: Context) : Dialog(context) {
    private lateinit var topTitle: TextView
    private lateinit var countLabel: TextView
    private lateinit var listScroll: ScrollView
    private lateinit var listHost: LinearLayout
    private lateinit var editorPanel: LinearLayout
    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var reminderButton: TextView
    private lateinit var clearReminderButton: TextView
    private lateinit var deleteButton: TextView
    private lateinit var newButton: TextView
    private var editingId = 0L
    private var reminderAt = 0L

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context)
        root.addView(
            JarvisBackgroundView(context),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20f), context.dp(18f), context.dp(20f), context.dp(14f))
        }
        root.addView(
            outer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = JarvisButton(context, context.dp(42f), Palette.cyanDim, R.drawable.ic_back).apply {
            contentDescription = "Back"
            setOnClickListener { onBack() }
        }
        top.addView(back, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        topTitle = context.label("NOTEPAD", 19f, Palette.ice, 0.2f, true)
        titleColumn.addView(topTitle)
        countLabel = context.label("", 9f, Palette.textDim, 0.18f, true)
        titleColumn.addView(
            countLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(3f) }
        )
        top.addView(
            titleColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(12f)
            }
        )
        outer.addView(top)

        val bodyHost = FrameLayout(context)
        outer.addView(
            bodyHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = context.dp(12f)
            }
        )

        listScroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        listHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dp(4f))
        }
        listScroll.addView(
            listHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        bodyHost.addView(
            listScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        editorPanel = buildEditor()
        bodyHost.addView(
            editorPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        newButton = actionButton("NEW NOTE", Palette.cyan) { showEditor(null) }
        outer.addView(
            newButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(12f) }
        )

        setContentView(root)
        outer.applySystemBarPadding()
        showList()
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
    }

    override fun onBackPressed() {
        if (::editorPanel.isInitialized && editorPanel.visibility == View.VISIBLE) {
            showList()
            return
        }
        super.onBackPressed()
    }

    private fun buildEditor(): LinearLayout {
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        titleInput = input("Title", singleLine = true)
        panel.addView(
            titleInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        bodyInput = input("Write your note...", singleLine = false)
        panel.addView(
            bodyInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = context.dp(10f)
            }
        )

        val reminderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        reminderButton = actionButton("SET REMINDER", Palette.gold) { pickReminder() }
        reminderRow.addView(
            reminderButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        clearReminderButton = actionButton("CLEAR", Palette.danger) {
            reminderAt = 0L
            updateReminderControls()
        }
        clearReminderButton.visibility = View.GONE
        reminderRow.addView(
            clearReminderButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = context.dp(10f) }
        )
        panel.addView(
            reminderRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(10f) }
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val save = actionButton("SAVE NOTE", Palette.cyan) { save() }
        buttons.addView(
            save,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        deleteButton = actionButton("DELETE", Palette.danger) { confirmDelete() }
        deleteButton.visibility = View.GONE
        buttons.addView(
            deleteButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = context.dp(10f) }
        )
        panel.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(10f) }
        )

        panel.visibility = View.GONE
        return panel
    }

    private fun input(hintText: String, singleLine: Boolean): EditText = EditText(context).apply {
        hint = hintText
        setHintTextColor(withAlpha(Palette.textDim, 0.75f))
        setTextColor(Palette.ice)
        textSize = 15f
        typeface = context.font(15f)
        background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.20f), 14f, 1f)
        setPadding(context.dp(14f), context.dp(12f), context.dp(14f), context.dp(12f))
        gravity = Gravity.TOP or Gravity.START
        includeFontPadding = false
        setLineSpacing(0f, 1.2f)
        if (singleLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
        }
    }

    private fun showList() {
        editingId = 0L
        reminderAt = 0L
        hideKeyboard()
        listScroll.visibility = View.VISIBLE
        editorPanel.visibility = View.GONE
        newButton.visibility = View.VISIBLE
        topTitle.text = "NOTEPAD"
        refreshList()
    }

    private fun showEditor(note: NoteStore.Note?) {
        editingId = note?.id ?: 0L
        reminderAt = note?.remindAt ?: 0L
        titleInput.setText(note?.title.orEmpty())
        bodyInput.setText(note?.body.orEmpty())
        updateReminderControls()
        deleteButton.visibility = if (note != null) View.VISIBLE else View.GONE
        topTitle.text = if (note != null) "EDIT NOTE" else "NEW NOTE"
        countLabel.text = if (note != null && note.remindAt > 0) "REMINDER SET" else "WRITE OR SET A REMINDER"
        listScroll.visibility = View.GONE
        editorPanel.visibility = View.VISIBLE
        newButton.visibility = View.GONE
        titleInput.requestFocus()
        titleInput.setSelection(titleInput.text?.length ?: 0)
    }

    private fun refreshList() {
        if (!::listHost.isInitialized) return
        listHost.removeAllViews()
        val notes = NoteStore.all()
        countLabel.text = when {
            notes.isEmpty() -> "NO NOTES YET"
            notes.size == 1 -> "1 NOTE"
            else -> "${notes.size} NOTES"
        }
        if (notes.isEmpty()) {
            val hint = context.label(
                "No notes yet. Tap NEW NOTE or say \"note likho ...\".",
                12f,
                Palette.textDim,
                0f,
                false,
                Gravity.CENTER
            ).apply { setLineSpacing(0f, 1.25f) }
            listHost.addView(
                hint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(36f) }
            )
            return
        }
        notes.forEachIndexed { index, note ->
            listHost.addView(
                noteRow(note),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = context.dp(10f)
                }
            )
        }
    }

    private fun noteRow(note: NoteStore.Note): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14f), context.dp(13f), context.dp(14f), context.dp(13f))
            background = context.roundedBackground(
                Palette.panel,
                withAlpha(Palette.cyan, 0.18f),
                16f,
                1f
            )
            isClickable = true
            setOnClickListener { showEditor(note) }
        }
        row.addView(
            context.label(
                note.title.ifBlank { "Untitled note" },
                15f,
                Palette.ice,
                0.02f,
                true
            )
        )
        if (note.body.isNotBlank()) {
            val preview = context.label(
                note.body.replace('\n', ' '),
                12f,
                Palette.textDim,
                0f,
                false
            ).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.2f)
            }
            row.addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(5f) }
            )
        }
        if (note.remindAt > 0L) {
            row.addView(
                context.label(
                    "REMINDER  ${formatTime(note.remindAt)}",
                    10f,
                    Palette.gold,
                    0.12f,
                    true
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(7f) }
            )
        }
        return row
    }

    private fun updateReminderControls() {
        if (!::reminderButton.isInitialized) return
        if (reminderAt > 0L) {
            reminderButton.text = "REMINDER  ${formatTime(reminderAt)}"
            clearReminderButton.visibility = View.VISIBLE
        } else {
            reminderButton.text = "SET REMINDER"
            clearReminderButton.visibility = View.GONE
        }
    }

    private fun pickReminder() {
        val base = Calendar.getInstance().apply {
            if (reminderAt > 0L) timeInMillis = reminderAt
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    timeInMillis = base.timeInMillis
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        picked.set(Calendar.HOUR_OF_DAY, hour)
                        picked.set(Calendar.MINUTE, minute)
                        picked.set(Calendar.SECOND, 0)
                        picked.set(Calendar.MILLISECOND, 0)
                        if (picked.timeInMillis <= System.currentTimeMillis()) {
                            Toast.makeText(
                                context,
                                "Choose a future time",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            reminderAt = picked.timeInMillis
                            updateReminderControls()
                        }
                    },
                    base.get(Calendar.HOUR_OF_DAY),
                    base.get(Calendar.MINUTE),
                    false
                ).show()
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun save() {
        val title = titleInput.text?.toString()?.trim().orEmpty()
        val body = bodyInput.text?.toString()?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) {
            Toast.makeText(context, "Write something first", Toast.LENGTH_SHORT).show()
            return
        }
        val finalTitle = title.ifEmpty {
            body.lineSequence().firstOrNull()?.trim()?.take(60).orEmpty().ifEmpty { "Note" }
        }
        if (editingId == 0L) {
            val id = NoteStore.add(finalTitle, body, reminderAt)
            if (id <= 0L) {
                Toast.makeText(context, "Could not save note", Toast.LENGTH_SHORT).show()
                return
            }
            if (reminderAt > 0L) {
                NoteStore.get(id)?.let { ReminderScheduler.schedule(context, it) }
            }
        } else {
            if (!NoteStore.update(editingId, finalTitle, body, reminderAt)) {
                Toast.makeText(context, "Could not save note", Toast.LENGTH_SHORT).show()
                return
            }
            if (reminderAt > 0L) {
                NoteStore.get(editingId)?.let { ReminderScheduler.schedule(context, it) }
            } else {
                ReminderScheduler.cancel(context, editingId)
            }
        }
        Toast.makeText(
            context,
            if (reminderAt > 0L) "Note saved with reminder" else "Note saved",
            Toast.LENGTH_SHORT
        ).show()
        showList()
    }

    private fun confirmDelete() {
        if (editingId == 0L) return
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete note?")
            .setMessage("This note and its reminder will be removed.")
            .setPositiveButton("DELETE") { _, _ ->
                ReminderScheduler.cancel(context, editingId)
                NoteStore.delete(editingId)
                Toast.makeText(context, "Note deleted", Toast.LENGTH_SHORT).show()
                showList()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun onBack() {
        if (::editorPanel.isInitialized && editorPanel.visibility == View.VISIBLE) {
            showList()
        } else {
            hideKeyboard()
            dismiss()
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(window?.decorView?.windowToken, 0)
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(millis))

    private fun actionButton(text: String, accent: Int, onClick: () -> Unit): TextView {
        val filled = accent == Palette.cyan
        return context.label(
            text,
            12f,
            if (filled) Palette.bg else accent,
            0.08f,
            true,
            Gravity.CENTER
        ).apply {
            setPadding(context.dp(16f), context.dp(13f), context.dp(16f), context.dp(13f))
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
