package com.jarvis.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DrawFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun View.applySystemBarPadding() {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = bars.bottom
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        view.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom)
        insets
    }
    requestApplyInsets()
}

fun Context.font(sizeSp: Float, bold: Boolean = false): Typeface =
    Typeface.create(if (bold) "sans-serif-condensed" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)

fun Context.label(
    text: String,
    sizeSp: Float,
    color: Int,
    letterSpacing: Float = 0f,
    bold: Boolean = false,
    gravity: Int = Gravity.START
): TextView = TextView(this).apply {
    this.text = text
    this.textSize = sizeSp
    this.setTextColor(color)
    this.letterSpacing = letterSpacing
    this.typeface = font(sizeSp, bold)
    this.gravity = gravity
    includeFontPadding = false
}

fun Context.roundedBackground(
    fill: Int,
    stroke: Int = Color.TRANSPARENT,
    radiusDp: Float = 18f,
    strokeWidthDp: Float = 1f
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dpf(radiusDp)
    setColor(fill)
    if (stroke != Color.TRANSPARENT) setStroke(dpf(strokeWidthDp).toInt().coerceAtLeast(1), stroke)
}

fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

fun verticalLayout(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
}

fun horizontalLayout(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
}

fun LinearLayout.addWithTopMargin(view: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, topMarginDp: Float = 0f) {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    lp.topMargin = context.dp(topMarginDp)
    addView(view, lp)
}

class JarvisBackgroundView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(0.6f)
        color = withAlpha(Palette.cyan, 0.035f)
    }
    private val startNs = System.nanoTime()
    private val random = Random(19)
    private val stars = List(54) {
        FloatArray(4).also {
            it[0] = random.nextFloat()
            it[1] = random.nextFloat()
            it[2] = random.nextFloat()
            it[3] = 0.5f + random.nextFloat() * 1.4f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat()

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Palette.bgDeep, 0xFF07131B.toInt(), Palette.bg, 0xFF020408.toInt()),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = RadialGradient(
            w * 0.5f, h * 0.38f, w * 0.95f,
            intArrayOf(withAlpha(Palette.cyan, 0.14f), withAlpha(Palette.cyan, 0.045f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = RadialGradient(
            w * 0.18f, h * 0.82f, w * 0.55f,
            intArrayOf(withAlpha(Palette.gold, 0.055f), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        val step = context.dpf(46f)
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }

        paint.shader = RadialGradient(
            w * 0.5f, h * 0.5f, maxOf(w, h) * 0.72f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(Color.BLACK, 0.62f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = null
        for (star in stars) {
            val twinkle = 0.5f + 0.5f * sin(t * star[3] + star[2] * 7f)
            paint.color = withAlpha(Palette.ice, 0.05f + 0.22f * twinkle)
            canvas.drawCircle(star[0] * w, star[1] * h, context.dpf(0.8f + star[2] * 0.9f), paint)
        }

        postInvalidateOnAnimation()
    }
}

class JarvisButton(
    context: Context,
    private val diameter: Int,
    initialAccent: Int,
    iconRes: Int = 0
) : View(context) {
    var accentColor: Int = initialAccent
        set(value) {
            field = value
            invalidate()
        }

    private var icon: Drawable? = if (iconRes != 0) context.getDrawable(iconRes)?.mutate() else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val startNs = System.nanoTime()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        color = Palette.ice
    }
    private var label: String? = null

    init {
        isClickable = true
        isFocusable = true
        icon?.setTint(Palette.ice)
    }

    fun setIcon(resId: Int, tint: Int = Palette.ice) {
        label = null
        icon = context.getDrawable(resId)?.mutate()
        icon?.setTint(tint)
        invalidate()
    }

    fun setLabel(text: String, tint: Int = Palette.ice) {
        label = text
        icon = null
        textPaint.color = tint
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(diameter, widthMeasureSpec)
        val height = resolveSize(diameter, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - context.dpf(3f)
        if (radius <= 0f) return
        val t = ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat()

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 1.35f,
            intArrayOf(withAlpha(accentColor, 0.22f), withAlpha(accentColor, 0.06f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.35f, paint)

        paint.shader = RadialGradient(
            cx, cy - radius * 0.32f, radius * 1.25f,
            intArrayOf(0xFF1B3E4E.toInt(), 0xFF09141E.toInt(), 0xFF04080E.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dpf(1.4f)
        paint.color = withAlpha(accentColor, 0.82f)
        canvas.drawCircle(cx, cy, radius, paint)

        paint.strokeWidth = context.dpf(0.8f)
        paint.color = withAlpha(accentColor, 0.22f)
        canvas.drawCircle(cx, cy, radius - context.dpf(4.5f), paint)

        rect.set(cx - radius + context.dpf(2f), cy - radius + context.dpf(2f), cx + radius - context.dpf(2f), cy + radius - context.dpf(2f))
        paint.strokeWidth = context.dpf(1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = withAlpha(accentColor, 0.75f)
        canvas.drawArc(rect, (t * 52f) % 360f, 72f, false, paint)

        paint.strokeWidth = context.dpf(1f)
        paint.color = withAlpha(accentColor, 0.32f)
        for (i in 0 until 24) {
            val angle = Math.toRadians((i * 15f).toDouble())
            val inner = radius - context.dpf(7f)
            val outer = radius - context.dpf(4f)
            canvas.drawLine(
                cx + cos(angle).toFloat() * inner,
                cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * outer,
                cy + sin(angle).toFloat() * outer,
                paint
            )
        }

        icon?.let { drawable ->
            val size = (width * 0.38f).toInt().coerceAtLeast(context.dp(12f))
            val left = (cx - size / 2f).toInt()
            val top = (cy - size / 2f).toInt()
            drawable.setBounds(left, top, left + size, top + size)
            drawable.draw(canvas)
        }

        label?.let { text ->
            textPaint.textSize = width * 0.2f
            val maxWidth = radius * 1.5f
            val measured = textPaint.measureText(text)
            if (measured > maxWidth && measured > 0f) {
                textPaint.textSize *= maxWidth / measured
            }
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, cx, baseline, textPaint)
        }

        if (isPressed) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Color.WHITE, 0.07f)
            canvas.drawCircle(cx, cy, radius, paint)
        }

        postInvalidateOnAnimation()
    }
}

fun Context.circleIconButton(iconRes: Int, sizeDp: Float, tint: Int, accent: Int): ImageButton {
    val size = dp(sizeDp)
    val background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(withAlpha(Palette.panel, 0.9f))
        setStroke(dp(1f), withAlpha(accent, 0.5f))
    }
    val ripple = RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 0.25f)), background, null)
    return ImageButton(this).apply {
        setImageResource(iconRes)
        setColorFilter(tint)
        this.background = ripple
        setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        layoutParams = LinearLayout.LayoutParams(size, size)
    }
}
