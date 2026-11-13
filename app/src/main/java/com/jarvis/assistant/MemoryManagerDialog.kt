package com.jarvis.assistant

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MemoryManagerDialog(context: Context) : Dialog(context) {
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var countLabel: TextView
    private val chips = mutableListOf<TextView>()
    private var query = ""
    private var activeCategory: String? = null

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
            contentDescription = "Close memory manager"
            setOnClickListener { dismiss() }
        }
        top.addView(back, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(context.label("MEMORY MANAGER", 19f, Palette.ice, 0.2f, true))
        countLabel = context.label("0 MEMORIES", 9f, Palette.textDim, 0.18f, true)
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

        val add = JarvisButton(context, context.dp(42f), Palette.cyanDim).apply {
            setLabel("+", Palette.cyan)
            contentDescription = "Add memory"
            setOnClickListener { openEditor(null) }
        }
        top.addView(add, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))
        outer.addView(top)

        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 14f, 1f)
            setPadding(context.dp(12f), 0, context.dp(12f), 0)
        }
        val searchInput = EditText(context).apply {
            setSingleLine()
            hint = "Search title or content"
            setHintTextColor(withAlpha(Palette.textDim, 0.75f))
            setTextColor(Palette.ice)
            textSize = 13.5f
            typeface = context.font(13.5f)
            background = null
            includeFontPadding = false
            setPadding(0, context.dp(12f), 0, context.dp(12f))
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    refresh()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(
            searchBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(14f)
            }
        )

        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(2f), 0, context.dp(2f))
        }
        chipScroll.addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addChip(chipRow, "All", null)
        MemoryStore.categories.forEach { addChip(chipRow, it, it) }
        outer.addView(
            chipScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(10f)
            }
        )

        val scroll = ScrollView(context).apply {
            clipToPadding = false
            isFillViewport = true
        }
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10f), 0, context.dp(18f))
        }
        scroll.addView(
            listContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        emptyView = context.label(
            "No memories yet.\nTap + to add your first memory.",
            13f,
            Palette.textFaint,
            0.03f,
            false,
            Gravity.CENTER
        ).apply {
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.76f).toInt()
            setLineSpacing(0f, 1.25f)
        }

        setContentView(root)
        outer.applySystemBarPadding()
    }

    override fun onStart() {
        super.onStart()
        window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(Palette.bg))
            window.statusBarColor = Palette.bg
            window.navigationBarColor = Palette.bg
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }
        refresh()
    }

    private fun addChip(parent: LinearLayout, text: String, category: String?) {
        val chip = context.label(text, 11f, Palette.textDim, 0.04f, true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(14f), context.dp(8f), context.dp(14f), context.dp(8f))
            isClickable = true
            tag = category
            setOnClickListener {
                activeCategory = category
                refresh()
            }
        }
        chips.add(chip)
        parent.addView(
            chip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(8f)
            }
        )
    }

    private fun refresh() {
        refreshChips()
        val all = MemoryStore.snapshot()
        countLabel.text = if (all.size == 1) "1 MEMORY" else "${all.size} MEMORIES"

        val needle = query.trim()
        val filtered = all.filter { item ->
            val category = MemoryStore.displayCategory(item)
            val matchesCategory = activeCategory == null || category == activeCategory
            val matchesQuery = needle.isEmpty() ||
                MemoryStore.displayTitle(item).contains(needle, ignoreCase = true) ||
                item.value.contains(needle, ignoreCase = true)
            matchesCategory && matchesQuery
        }.sortedWith(
            compareBy(
                { item ->
                    val index = MemoryStore.categories.indexOf(MemoryStore.displayCategory(item))
                    if (index < 0) MemoryStore.categories.size else index
                },
                { item -> -item.updatedAt }
            )
        )

        listContainer.removeAllViews()
        if (filtered.isEmpty()) {
            listContainer.addView(
                emptyView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(48f)
                }
            )
            return
        }
        for (item in filtered) {
            listContainer.addView(
                memoryCard(item),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(10f)
                }
            )
        }
    }

    private fun refreshChips() {
        for (chip in chips) {
            val selected = (chip.tag as? String) == activeCategory
            chip.setTextColor(if (selected) Palette.bg else Palette.textDim)
            chip.background = context.roundedBackground(
                if (selected) Palette.cyan else Palette.panel,
                if (selected) Palette.cyan else withAlpha(Palette.cyan, 0.22f),
                20f,
                1f
            )
        }
    }

    private fun memoryCard(item: MemoryStore.MemoryItem): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 16f, 1f)
            setPadding(context.dp(14f), context.dp(13f), context.dp(14f), context.dp(9f))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = context.label(MemoryStore.displayTitle(item), 14.5f, Palette.ice, 0.01f, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = context.label(
            MemoryStore.displayCategory(item).uppercase(),
            8.5f,
            Palette.cyan,
            0.16f,
            true
        ).apply {
            background = context.roundedBackground(
                withAlpha(Palette.cyan, 0.10f),
                withAlpha(Palette.cyan, 0.30f),
                20f,
                1f
            )
            setPadding(context.dp(8f), context.dp(4f), context.dp(8f), context.dp(4f))
        }
        header.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(8f)
        })
        card.addView(header)

        val content = context.label(item.value, 12.5f, Palette.textDim, 0.01f).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        }
        card.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            }
        )

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val edit = context.label("EDIT", 9f, Palette.cyan, 0.18f, true).apply {
            setPadding(context.dp(10f), context.dp(8f), context.dp(10f), context.dp(8f))
            isClickable = true
            contentDescription = "Edit memory"
            setOnClickListener { openEditor(item) }
        }
        footer.addView(edit)
        val delete = context.label("DELETE", 9f, Palette.danger, 0.18f, true).apply {
            setPadding(context.dp(10f), context.dp(8f), context.dp(6f), context.dp(8f))
            isClickable = true
            contentDescription = "Delete memory"
            setOnClickListener { confirmDelete(item) }
        }
        footer.addView(delete)
        card.addView(
            footer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(4f)
            }
        )
        return card
    }

    private fun openEditor(item: MemoryStore.MemoryItem?) {
        var category = item?.let { MemoryStore.displayCategory(it) } ?: "Personal"
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20f), context.dp(10f), context.dp(20f), context.dp(2f))
        }

        val titleInput = editorInput("Title", multiline = false).apply {
            setText(item?.let { MemoryStore.displayTitle(it) }.orEmpty())
        }
        container.addView(
            titleInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val contentInput = editorInput("Content", multiline = true).apply {
            setText(item?.value.orEmpty())
        }
        container.addView(
            contentInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(10f)
            }
        )

        val categoryView = TextView(context).apply {
            text = "Category: $category"
            textSize = 13f
            typeface = context.font(13f, true)
            setTextColor(Palette.cyan)
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 12f, 1f)
            setPadding(context.dp(12f), context.dp(12f), context.dp(12f), context.dp(12f))
            isClickable = true
            contentDescription = "Choose category"
        }
        container.addView(
            categoryView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(10f)
            }
        )

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (item == null) "New memory" else "Edit memory")
            .setView(container)
            .setPositiveButton("SAVE", null)
            .setNegativeButton("CANCEL", null)
            .create()

        categoryView.setOnClickListener {
            val options = MemoryStore.categories.toTypedArray()
            AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Category")
                .setSingleChoiceItems(
                    options,
                    MemoryStore.categories.indexOf(category).coerceAtLeast(0)
                ) { choice, which ->
                    category = MemoryStore.categories[which]
                    categoryView.text = "Category: $category"
                    choice.dismiss()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanTitle = titleInput.text.toString().trim()
                val cleanContent = contentInput.text.toString().trim()
                if (cleanTitle.isEmpty() || cleanContent.isEmpty()) {
                    Toast.makeText(context, "Title and content cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val saved = if (item == null) {
                    MemoryStore.addManual(cleanTitle, cleanContent, category) > 0
                } else {
                    MemoryStore.updateMemory(item.id, cleanTitle, cleanContent, category)
                }
                if (!saved) {
                    Toast.makeText(context, "Could not save memory", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun editorInput(hint: String, multiline: Boolean): EditText = EditText(context).apply {
        this.hint = hint
        setHintTextColor(withAlpha(Palette.textDim, 0.75f))
        setTextColor(Palette.ice)
        textSize = 14.5f
        typeface = context.font(14.5f)
        background = context.roundedBackground(Palette.panel, withAlpha(Palette.cyan, 0.18f), 12f, 1f)
        setPadding(context.dp(12f), context.dp(10f), context.dp(12f), context.dp(10f))
        if (multiline) {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        } else {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
    }

    private fun confirmDelete(item: MemoryStore.MemoryItem) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete memory?")
            .setMessage("\"${MemoryStore.displayTitle(item)}\" will be removed from Jarvis memory.")
            .setPositiveButton("DELETE") { _, _ ->
                if (MemoryStore.deleteMemory(item.id)) {
                    refresh()
                } else {
                    Toast.makeText(context, "Could not delete memory", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
