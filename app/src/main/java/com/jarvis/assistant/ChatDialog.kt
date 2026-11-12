package com.jarvis.assistant

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatDialog(context: Context) : Dialog(context) {
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var emptyView: TextView
    private val rows = HashMap<Long, TextView>()
    private var lastIds = emptyList<Long>()
    private var emptyAttached = false
    private var autoScroll = true

    private val listener = ChatStore.Listener { refresh() }

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
        root.addView(outer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = JarvisButton(context, context.dp(42f), Palette.cyanDim, R.drawable.ic_back).apply {
            contentDescription = "Close transcript"
            setOnClickListener { dismiss() }
        }
        top.addView(back, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(context.label("LIVE TRANSCRIPT", 19f, Palette.ice, 0.2f, true))
        status = context.label("● OFFLINE", 9f, Palette.textDim, 0.18f, true)
        titleColumn.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(3f)
        })
        top.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(12f)
        })

        val clear = JarvisButton(context, context.dp(42f), Palette.cyanDim, R.drawable.ic_trash).apply {
            contentDescription = "Clear transcript"
            setOnClickListener { confirmClear() }
        }
        top.addView(clear, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))
        outer.addView(top)

        val divider = View(context).apply {
            setBackgroundColor(withAlpha(Palette.cyan, 0.09f))
        }
        outer.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1f)).apply {
            topMargin = context.dp(14f)
        })

        scroll = ScrollView(context).apply {
            clipToPadding = false
            isFillViewport = true
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0) ?: return@setOnScrollChangeListener
                autoScroll = scrollY + height >= child.height - context.dp(56f)
            }
        }
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10f), 0, context.dp(18f))
        }
        scroll.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        emptyView = context.label(
            "Start a live voice session and the conversation will appear here, word by word.",
            13f,
            Palette.textFaint,
            0.03f,
            false,
            Gravity.CENTER
        ).apply {
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.76f).toInt()
            setLineSpacing(0f, 1.2f)
        }

        outer.addView(
            context.label(
                "USER + JARVIS TRANSCRIPTS STREAM IN REAL TIME",
                8.5f,
                Palette.textFaint,
                0.2f,
                false,
                Gravity.CENTER
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            }
        )

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
        }
        ChatStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        ChatStore.removeListener(listener)
        super.onStop()
    }

    private fun refresh() {
        if (!::container.isInitialized) return
        val messages = ChatStore.snapshot()
        val ids = messages.map { it.id }

        if (ids.size < lastIds.size || (lastIds.isNotEmpty() && ids.take(lastIds.size) != lastIds)) {
            container.removeAllViews()
            rows.clear()
            emptyAttached = false
        }

        if (messages.isEmpty()) {
            if (!emptyAttached) {
                container.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(64f)
                })
                emptyAttached = true
            }
        } else {
            if (emptyAttached) {
                container.removeView(emptyView)
                emptyAttached = false
            }
            for (message in messages) {
                val bubble = rows[message.id]
                if (bubble == null) {
                    addRow(message)
                } else {
                    bubble.text = textFor(message)
                }
            }
        }

        lastIds = ids
        status.text = if (ChatStore.sessionActive) "● LIVE" else "● OFFLINE"
        status.setTextColor(if (ChatStore.sessionActive) Palette.cyan else Palette.textDim)

        if (autoScroll) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addRow(message: ChatStore.Message) {
        val isUser = message.role == ChatStore.Role.USER
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            context.label(
                if (isUser) "YOU" else "JARVIS",
                9f,
                if (isUser) Palette.gold else Palette.cyan,
                0.22f,
                true
            )
        )
        if (message.time > 0L) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.time))
            header.addView(
                context.label(time, 8.5f, Palette.textFaint, 0.04f),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dp(8f)
                }
            )
        }
        column.addView(header)

        val bubble = TextView(context).apply {
            text = textFor(message)
            textSize = 15f
            setTextColor(Palette.ice)
            typeface = context.font(15f)
            setLineSpacing(0f, 1.18f)
            setPadding(context.dp(14f), context.dp(11f), context.dp(14f), context.dp(11f))
            background = if (isUser) {
                context.roundedBackground(withAlpha(Palette.gold, 0.10f), withAlpha(Palette.gold, 0.4f), 18f, 1f)
            } else {
                context.roundedBackground(withAlpha(Palette.cyan, 0.075f), withAlpha(Palette.cyan, 0.32f), 18f, 1f)
            }
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.74f).toInt()
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6f)
        })

        wrap.addView(column)
        container.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(5f)
            bottomMargin = context.dp(5f)
        })
        rows[message.id] = bubble
    }

    private fun textFor(message: ChatStore.Message): String {
        val cursor = if (!message.final && message.text.isNotBlank()) "  ▍" else ""
        return message.text + cursor
    }

    private fun confirmClear() {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Clear transcript?")
            .setMessage("This removes the saved live conversation from this device.")
            .setPositiveButton("Clear") { _, _ ->
                ChatStore.clear()
                JarvisPrefs.clearSessionHandle()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
