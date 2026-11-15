package com.jarvis.assistant

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LearningOverlay(
    context: Context,
    private val onRepeat: (String) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private val languageView: TextView
    private val lessonView: TextView
    private val termView: TextView
    private val translitView: TextView
    private val meaningView: TextView
    private val exampleView: TextView

    init {
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        setBackgroundColor(withAlpha(Palette.bgDeep, 0.95f))
        setOnClickListener { }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(22f), context.dp(18f), context.dp(22f), context.dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(26f).toFloat()
                setColor(Palette.panel)
                setStroke(context.dp(1f), withAlpha(Palette.cyanDim, 0.45f))
            }
        }
        addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = context.dp(14f)
                rightMargin = context.dp(14f)
                topMargin = context.dp(18f)
                bottomMargin = context.dp(18f)
            }
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        languageView = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.18f
            setTextColor(Palette.cyan)
        }
        header.addView(languageView, LinearLayout.LayoutParams(0, context.dp(24f), 1f))
        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Palette.textDim)
            setPadding(context.dp(10f), 0, context.dp(4f), 0)
            isClickable = true
            setOnClickListener { onClose() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(context.dp(44f), context.dp(44f)))
        card.addView(header)

        lessonView = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.22f
            setTextColor(Palette.textFaint)
        }
        card.addView(lessonView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = context.dp(10f)
        })

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(4f), 0, context.dp(10f))
        }
        termView = TextView(context).apply {
            textSize = 46f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Palette.ice)
            setLineSpacing(0f, 1.05f)
            setPadding(0, context.dp(6f), 0, context.dp(6f))
        }
        column.addView(termView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        translitView = TextView(context).apply {
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Palette.gold)
            setPadding(0, context.dp(2f), 0, context.dp(10f))
        }
        column.addView(translitView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        meaningView = TextView(context).apply {
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Palette.ice)
            setLineSpacing(0f, 1.1f)
            setPadding(0, context.dp(2f), 0, context.dp(10f))
        }
        column.addView(meaningView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        exampleView = TextView(context).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            gravity = Gravity.CENTER
            setTextColor(Palette.textDim)
            setLineSpacing(0f, 1.15f)
            setPadding(0, context.dp(2f), 0, context.dp(4f))
        }
        column.addView(exampleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        footer.addView(pill("REPEAT", Palette.cyan) { onRepeat("Repeat the word slowly.") })
        footer.addView(pill("SLOWER", Palette.gold) { onRepeat("Say it more slowly.") })
        footer.addView(pill("CLOSE", Palette.danger) { onClose() })
        card.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = context.dp(8f)
        })
    }

    fun showLesson(title: String, lesson: Int) {
        languageView.text = title.uppercase()
        lessonView.text = "LESSON $lesson"
    }

    fun showWord(
        term: String,
        transliteration: String,
        meaning: String,
        example: String
    ) {
        termView.text = term
        translitView.text = transliteration
        meaningView.text = meaning
        exampleView.text = example
        exampleView.visibility = if (example.isBlank()) View.GONE else View.VISIBLE
    }

    fun show() {
        if (visibility == View.VISIBLE) return
        visibility = View.VISIBLE
        alpha = 0f
        translationY = context.dp(18f).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(180L).start()
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).translationY(context.dp(18f).toFloat()).setDuration(150L)
            .withEndAction { visibility = View.GONE }.start()
    }

    private fun pill(label: String, accent: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.14f
            setTextColor(Palette.ice)
            setPadding(context.dp(8f), context.dp(12f), context.dp(8f), context.dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(22f).toFloat()
                setColor(withAlpha(accent, 0.16f))
                setStroke(context.dp(1f), withAlpha(accent, 0.55f))
            }
            isClickable = true
            setOnClickListener {
                animate().scaleX(0.95f).scaleY(0.95f).setDuration(70L)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    }.start()
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(0, context.dp(46f), 1f).apply {
                leftMargin = context.dp(4f)
                rightMargin = context.dp(4f)
            }
        }
}
