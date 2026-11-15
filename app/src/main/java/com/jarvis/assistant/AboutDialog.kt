package com.jarvis.assistant

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AboutDialog(context: Context) : Dialog(context) {

    private val downloadsStat = "10,000+"
    private val ratingStat = "4.8\u2605"

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
            contentDescription = "Close About"
            setOnClickListener { dismiss() }
        }
        top.addView(back, LinearLayout.LayoutParams(context.dp(42f), context.dp(42f)))

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(context.label("ABOUT ME", 19f, Palette.ice, 0.2f, true))
        titleColumn.addView(
            context.label("YASHWANT SAINI", 9f, Palette.textDim, 0.18f, true),
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

        val divider = View(context).apply {
            setBackgroundColor(withAlpha(Palette.cyanDim, 0.35f))
        }
        outer.addView(
            divider,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1f)).apply {
                topMargin = context.dp(16f)
            }
        )

        val stats = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = context.roundedBackground(
                Palette.panel,
                withAlpha(Palette.cyan, 0.16f),
                14f,
                1f
            )
            setPadding(context.dp(10f), context.dp(10f), context.dp(10f), context.dp(10f))
        }
        stats.addView(
            statCell("TOTAL DOWNLOADS", downloadsStat, Palette.cyan),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        stats.addView(
            View(context).apply { setBackgroundColor(withAlpha(Palette.cyanDim, 0.35f)) },
            LinearLayout.LayoutParams(context.dp(1f), context.dp(34f)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        stats.addView(
            statCell("RATING", ratingStat, Palette.gold),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        outer.addView(
            stats,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(14f) }
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(18f), 0, context.dp(22f))
        }
        val body = TextView(context).apply {
            text = buildBody()
            textSize = 14.5f
            setTextColor(Palette.ice)
            setLineSpacing(0f, 1.32f)
            setTextIsSelectable(true)
        }
        column.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        outer.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        outer.applySystemBarPadding()
    }

    override fun onStart() {
        super.onStart()
        window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            window.setBackgroundDrawable(ColorDrawable(Palette.bg))
            window.setGravity(Gravity.CENTER)
        }
    }

    private fun statCell(label: String, value: String, color: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(context.label(label, 8.5f, Palette.textFaint, 0.18f, true, Gravity.CENTER))
            addView(
                context.label(value, 18f, color, 0.04f, true, Gravity.CENTER),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(4f) }
            )
        }

    private fun buildBody(): CharSequence {
        val text = SpannableStringBuilder(
            "Hey, I'm Yashwant Saini (A commerce student)\u2014 a curious learner, " +
                "technology enthusiast, and an aspirant.\n\n" +
                "I'm passionate about AI, coding, and technology. I built JARVIS but for now " +
                "I have to drop the project in middle because life has other plans, and " +
                "responsibilities come first. Maybe I'll return to this world someday, maybe I " +
                "won't. Who knows?\n\n" +
                "I'm not a genius, not a billionaire, and definitely not Tony Stark. Just " +
                "someone who dared to turn an idea into something real.\n\n" +
                "I built an AI assistant, gave it a voice, a memory, and a little personality. " +
                "Maybe it wasn't perfect, but it was mine. And sometimes, the journey matters " +
                "more than the final result.\n\n" +
                "The system may be offline, but the mind behind it isn't.\n\n" +
                "This isn't a shutdown. Just an unexpected pause.\n\n" +
                "YASH, signing off... for now."
        )

        val nameStart = text.indexOf("Yashwant Saini")
        if (nameStart >= 0) {
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                nameStart,
                nameStart + "Yashwant Saini".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                ForegroundColorSpan(Palette.gold),
                nameStart,
                nameStart + "Yashwant Saini".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val signStart = text.indexOf("YASH, signing off")
        if (signStart >= 0) {
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                signStart,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                ForegroundColorSpan(Palette.cyan),
                signStart,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return text
    }
}
