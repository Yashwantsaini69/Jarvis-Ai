package com.jarvis.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Mode { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var orbStyle: Int = 0
        set(value) {
            field = value.coerceIn(0, 4)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val startNs = System.nanoTime()
    private var level = 0f
    private var targetLevel = 0f

    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        level += (targetLevel - level) * 0.14f
        targetLevel *= 0.94f

        val baseTime = ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat()
        val t = baseTime * speed()
        val e = energy() + level * 0.6f
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f * 0.76f
        val accent = accentColor()

        drawHalo(canvas, cx, cy, radius, t, e, accent)

        when (orbStyle) {
            0 -> drawArcReactor(canvas, cx, cy, radius, t, e, accent)
            1 -> drawPlasmaSphere(canvas, cx, cy, radius, t, e, accent)
            2 -> drawEnergyCore(canvas, cx, cy, radius, t, e, accent)
            3 -> drawNeuralSphere(canvas, cx, cy, radius, t, e, accent)
            else -> drawPulsar(canvas, cx, cy, radius, t, e, accent)
        }

        postInvalidateOnAnimation()
    }

    private fun energy(): Float = when (mode) {
        Mode.IDLE -> 0.72f
        Mode.CONNECTING -> 0.8f
        Mode.LISTENING -> 1f
        Mode.THINKING -> 0.92f
        Mode.SPEAKING -> 1.05f
        Mode.ERROR -> 0.78f
    }

    private fun speed(): Float = when (mode) {
        Mode.IDLE -> 0.55f
        Mode.CONNECTING -> 0.9f
        Mode.LISTENING -> 1.15f
        Mode.THINKING -> 1.7f
        Mode.SPEAKING -> 1.4f
        Mode.ERROR -> 0.45f
    }

    private fun accentColor(): Int = when (mode) {
        Mode.ERROR -> Palette.danger
        Mode.CONNECTING -> 0xFF4B8AA6.toInt()
        Mode.SPEAKING -> blend(Palette.cyan, Palette.gold, 0.5f)
        else -> Palette.cyan
    }

    private fun drawHalo(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float, e: Float, accent: Int) {
        val breath = 0.5f + 0.5f * sin(t * 1.7f)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 1.55f,
            intArrayOf(
                withAlpha(accent, (0.16f + 0.07f * breath) * e),
                withAlpha(accent, 0.045f * e),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.55f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.004f
        paint.color = withAlpha(accent, 0.12f * e)
        canvas.drawCircle(cx, cy, radius * 1.12f, paint)
    }

    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, coreRadius: Float, e: Float, accent: Int, hot: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, coreRadius * 3.4f,
            intArrayOf(
                withAlpha(hot, 0.95f * e),
                withAlpha(accent, 0.5f * e),
                withAlpha(accent, 0.14f * e),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.16f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius * 3.4f, paint)

        paint.shader = RadialGradient(
            cx, cy, coreRadius,
            intArrayOf(Color.WHITE, withAlpha(hot, 0.96f * e), withAlpha(accent, 0.6f * e)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = coreRadius * 0.12f
        paint.color = withAlpha(Palette.ice, 0.48f * e)
        canvas.drawCircle(cx, cy, coreRadius * 1.5f, paint)
    }

    private fun drawArcReactor(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float, e: Float, accent: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        paint.strokeWidth = radius * 0.026f
        paint.color = withAlpha(accent, 0.62f * e)
        rect.set(cx - radius * 0.93f, cy - radius * 0.93f, cx + radius * 0.93f, cy + radius * 0.93f)
        for (i in 0 until 8) {
            canvas.drawArc(rect, i * 45f + t * 7f, 31f, false, paint)
        }

        paint.strokeWidth = radius * 0.008f
        paint.color = withAlpha(accent, 0.32f * e)
        val tickRadius = radius * 0.83f
        for (i in 0 until 60) {
            val angle = Math.toRadians((i * 6f + t * 4f).toDouble())
            canvas.drawLine(
                cx + cos(angle).toFloat() * (tickRadius - radius * 0.018f),
                cy + sin(angle).toFloat() * (tickRadius - radius * 0.018f),
                cx + cos(angle).toFloat() * tickRadius,
                cy + sin(angle).toFloat() * tickRadius,
                paint
            )
        }

        paint.strokeWidth = radius * 0.013f
        paint.color = withAlpha(accent, 0.58f * e)
        rect.set(cx - radius * 0.69f, cy - radius * 0.69f, cx + radius * 0.69f, cy + radius * 0.69f)
        for (i in 0 until 12) {
            canvas.drawArc(rect, -i * 30f - t * 13f, 18f, false, paint)
        }

        paint.strokeWidth = radius * 0.006f
        paint.color = withAlpha(accent, 0.28f * e)
        for (i in 0 until 3) {
            val angle = Math.toRadians((i * 120f - 90f).toDouble())
            canvas.drawLine(
                cx + cos(angle).toFloat() * radius * 0.38f,
                cy + sin(angle).toFloat() * radius * 0.38f,
                cx + cos(angle).toFloat() * radius * 0.88f,
                cy + sin(angle).toFloat() * radius * 0.88f,
                paint
            )
        }

        val triangleRadius = radius * 0.37f
        path.reset()
        for (i in 0 until 3) {
            val angle = Math.toRadians((i * 120f - 90f).toDouble())
            val px = cx + cos(angle).toFloat() * triangleRadius
            val py = cy + sin(angle).toFloat() * triangleRadius
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx, cy - triangleRadius, cx, cy + triangleRadius,
            withAlpha(accent, 0.55f * e),
            withAlpha(0xFF061821.toInt(), 0.9f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.016f
        paint.color = withAlpha(Palette.ice, 0.78f * e)
        canvas.drawPath(path, paint)

        val innerTriangle = radius * 0.24f
        path.reset()
        for (i in 0 until 3) {
            val angle = Math.toRadians((i * 120f - 90f).toDouble())
            val px = cx + cos(angle).toFloat() * innerTriangle
            val py = cy + sin(angle).toFloat() * innerTriangle
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        paint.strokeWidth = radius * 0.007f
        paint.color = withAlpha(Palette.gold, 0.62f * e)
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45f + t * 7f + 15f).toDouble())
            paint.color = withAlpha(Palette.ice, 0.6f * e)
            canvas.drawCircle(
                cx + cos(angle).toFloat() * radius * 0.93f,
                cy + sin(angle).toFloat() * radius * 0.93f,
                radius * 0.013f,
                paint
            )
        }

        drawCore(canvas, cx, cy, radius * 0.14f, e, accent, Palette.gold)
    }

    private fun drawPlasmaSphere(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float, e: Float, accent: Int) {
        val sphere = radius * 0.63f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - sphere * 0.28f, cy - sphere * 0.32f, sphere * 1.55f,
            intArrayOf(
                withAlpha(Palette.ice, 0.85f * e),
                withAlpha(accent, 0.42f * e),
                withAlpha(0xFF062430.toInt(), 0.9f),
                withAlpha(0xFF02070C.toInt(), 0.96f)
            ),
            floatArrayOf(0f, 0.22f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, sphere, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 5) {
            val rr = sphere * (0.32f + i * 0.14f)
            rect.set(cx - rr, cy - rr * 0.62f, cx + rr, cy + rr * 0.62f)
            paint.strokeWidth = radius * 0.005f
            paint.color = withAlpha(Palette.ice, (0.22f - i * 0.028f) * e)
            canvas.drawArc(rect, 18f + i * 47f + t * 15f, 105f + i * 12f, false, paint)
        }

        val orbits = floatArrayOf(-26f, 27f, 88f)
        for (i in orbits.indices) {
            canvas.save()
            canvas.rotate(orbits[i] + t * (if (i == 1) -12f else 9f), cx, cy)
            rect.set(cx - radius * 0.89f, cy - radius * 0.27f, cx + radius * 0.89f, cy + radius * 0.27f)
            paint.strokeWidth = radius * 0.007f
            paint.color = withAlpha(accent, (0.46f - i * 0.07f) * e)
            canvas.drawOval(rect, paint)
            val angle = (t * 0.9f + i * 2.1f).toDouble()
            val px = cx + cos(angle).toFloat() * radius * 0.89f
            val py = cy + sin(angle).toFloat() * radius * 0.27f
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Palette.ice, 0.9f * e)
            canvas.drawCircle(px, py, radius * 0.016f, paint)
            paint.style = Paint.Style.STROKE
            canvas.restore()
        }

        paint.strokeWidth = radius * 0.012f
        paint.color = withAlpha(accent, 0.5f * e)
        canvas.drawCircle(cx, cy, radius * 0.95f, paint)

        paint.strokeWidth = radius * 0.005f
        paint.color = withAlpha(accent, 0.22f * e)
        for (i in 0 until 36) {
            val angle = Math.toRadians((i * 10f + t * 3f).toDouble())
            canvas.drawLine(
                cx + cos(angle).toFloat() * radius * 0.97f,
                cy + sin(angle).toFloat() * radius * 0.97f,
                cx + cos(angle).toFloat() * radius,
                cy + sin(angle).toFloat() * radius,
                paint
            )
        }

        drawCore(canvas, cx, cy, radius * 0.11f, e, accent, Palette.ice)
    }

    private fun drawEnergyCore(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float, e: Float, accent: Int) {
        val pulse = 0.5f + 0.5f * sin(t * 2.1f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * (0.007f + 0.007f * pulse)
        paint.color = withAlpha(accent, (0.22f + 0.35f * pulse) * e)
        canvas.drawCircle(cx, cy, radius * (0.95f + 0.015f * pulse), paint)

        for (i in 0 until 3) {
            val hexRadius = radius * (0.79f - i * 0.15f)
            val rotation = t * (if (i == 1) -11f else 8f) + i * 15f
            buildHexagon(path, cx, cy, hexRadius, rotation)
            paint.strokeWidth = radius * (0.013f - i * 0.002f)
            paint.color = withAlpha(if (i == 1) Palette.gold else accent, (0.55f - i * 0.1f) * e)
            canvas.drawPath(path, paint)
            if (i == 0) {
                paint.style = Paint.Style.FILL
                for (k in 0 until 6) {
                    val angle = Math.toRadians((rotation + k * 60f).toDouble())
                    paint.color = withAlpha(Palette.ice, 0.68f * e)
                    canvas.drawCircle(
                        cx + cos(angle).toFloat() * hexRadius,
                        cy + sin(angle).toFloat() * hexRadius,
                        radius * 0.012f,
                        paint
                    )
                }
                paint.style = Paint.Style.STROKE
            }
        }

        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30f + t * 5f).toDouble())
            val length = radius * (0.44f + 0.25f * ((sin(t * 2.6f + i * 0.7f) + 1f) / 2f))
            paint.strokeWidth = radius * 0.005f
            paint.color = withAlpha(accent, 0.18f * e)
            canvas.drawLine(
                cx + cos(angle).toFloat() * radius * 0.36f,
                cy + sin(angle).toFloat() * radius * 0.36f,
                cx + cos(angle).toFloat() * length,
                cy + sin(angle).toFloat() * length,
                paint
            )
        }

        buildHexagon(path, cx, cy, radius * 0.35f, -t * 16f)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * 0.36f,
            withAlpha(accent, 0.65f * e),
            withAlpha(0xFF071820.toInt(), 0.86f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.009f
        paint.color = withAlpha(Palette.ice, 0.68f * e)
        canvas.drawPath(path, paint)

        drawCore(canvas, cx, cy, radius * 0.13f, e, accent, Palette.gold)
    }

    private fun drawNeuralSphere(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float, e: Float, accent: Int) {
        val sphere = radius * 0.64f
        val rotation = t * 9f
        paint.style = Paint.Style.STROKE

        for (latDeg in intArrayOf(-60, -30, 0, 30, 60)) {
            val lat = Math.toRadians(latDeg.toDouble())
            val rr = sphere * cos(lat).toFloat()
            val yy = cy + sphere * sin(lat).toFloat()
            rect.set(cx - rr, yy - rr * 0.22f, cx + rr, yy + rr * 0.22f)
            paint.strokeWidth = radius * 0.005f
            paint.color = withAlpha(accent, 0.2f * e)
            canvas.drawOval(rect, paint)
        }

        for (k in 0 until 4) {
            canvas.save()
            canvas.rotate(rotation + k * 45f, cx, cy)
            rect.set(cx - sphere, cy - sphere * 0.42f, cx + sphere, cy + sphere * 0.42f)
            paint.strokeWidth = radius * 0.005f
            paint.color = withAlpha(accent, 0.17f * e)
            canvas.drawOval(rect, paint)
            canvas.restore()
        }

        paint.style = Paint.Style.FILL
        for (lat in -60..60 step 30) {
            val la = Math.toRadians(lat.toDouble())
            for (lon in 0..330 step 30) {
                val lo = Math.toRadians((lon + rotation * 2f).toDouble())
                val x3 = cos(la) * cos(lo)
                val y3 = sin(la)
                val z3 = cos(la) * sin(lo)
                val depth = ((z3 + 1.0) / 2.0).toFloat()
                val scale = 0.62f + 0.38f * depth
                val px = cx + x3.toFloat() * sphere * scale
                val py = cy + y3.toFloat() * sphere * scale
                paint.color = withAlpha(if (z3 > 0.25) Palette.ice else accent, (0.18f + 0.62f * depth) * e)
                canvas.drawCircle(px, py, radius * 0.01f * scale, paint)
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.003f
        paint.color = withAlpha(accent, 0.1f * e)
        for (lon in 0..330 step 30) {
            val lo = Math.toRadians((lon + rotation * 2f).toDouble())
            var prevX = 0f
            var prevY = 0f
            var hasPrevious = false
            for (lat in -75..75 step 15) {
                val la = Math.toRadians(lat.toDouble())
                val x3 = cos(la) * cos(lo)
                val y3 = sin(la)
                val z3 = cos(la) * sin(lo)
                val depth = ((z3 + 1.0) / 2.0).toFloat()
                val scale = 0.62f + 0.38f * depth
                val px = cx + x3.toFloat() * sphere * scale
                val py = cy + y3.toFloat() * sphere * scale
                if (hasPrevious) canvas.drawLine(prevX, prevY, px, py, paint)
                prevX = px
                prevY = py
                hasPrevious = true
            }
        }

        paint.strokeWidth = radius * 0.012f
        paint.color = withAlpha(accent, 0.42f * e)
        canvas.drawCircle(cx, cy, radius * 0.95f, paint)
        paint.strokeWidth = radius * 0.005f
        paint.color = withAlpha(accent, 0.18f * e)
        canvas.drawCircle(cx, cy, radius * 0.99f, paint)

        drawCore(canvas, cx, cy, radius * 0.11f, e, accent, Palette.ice)
    }

    private fun drawPulsar(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float, e: Float, accent: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        for (i in 0 until 4) {
            val p = (t * 0.24f + i * 0.25f) % 1f
            val rr = radius * (0.16f + 0.78f * p)
            paint.strokeWidth = radius * (0.005f + 0.012f * (1f - p))
            paint.color = withAlpha(accent, 0.42f * e * (1f - p))
            canvas.drawCircle(cx, cy, rr, paint)
        }

        rect.set(cx - radius * 0.55f, cy - radius * 0.55f, cx + radius * 0.55f, cy + radius * 0.55f)
        paint.strokeWidth = radius * 0.012f
        paint.color = withAlpha(accent, 0.5f * e)
        for (i in 0 until 10) {
            canvas.drawArc(rect, i * 36f + t * 15f, 21f, false, paint)
        }

        paint.strokeWidth = radius * 0.005f
        for (i in 0 until 16) {
            val angle = Math.toRadians((i * 22.5).toDouble())
            val inner = radius * 0.62f
            val outer = radius * (0.82f + 0.06f * ((sin(t * 2.5f + i * 0.9f) + 1f) / 2f))
            paint.color = withAlpha(accent, 0.16f * e)
            canvas.drawLine(
                cx + cos(angle).toFloat() * inner,
                cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * outer,
                cy + sin(angle).toFloat() * outer,
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx - radius * 0.95f, cy, cx + radius * 0.95f, cy,
            intArrayOf(Color.TRANSPARENT, withAlpha(Palette.ice, 0.42f * e), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - radius * 0.95f, cy - radius * 0.011f, cx + radius * 0.95f, cy + radius * 0.011f, paint)
        paint.shader = LinearGradient(
            cx, cy - radius * 0.95f, cx, cy + radius * 0.95f,
            intArrayOf(Color.TRANSPARENT, withAlpha(Palette.ice, 0.28f * e), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - radius * 0.007f, cy - radius * 0.95f, cx + radius * 0.007f, cy + radius * 0.95f, paint)
        paint.shader = null

        val angle = (t * 0.7f).toDouble()
        paint.color = withAlpha(Palette.gold, 0.9f * e)
        canvas.drawCircle(
            cx + cos(angle).toFloat() * radius * 0.72f,
            cy + sin(angle).toFloat() * radius * 0.72f,
            radius * 0.022f,
            paint
        )

        drawCore(canvas, cx, cy, radius * 0.13f, e, accent, Palette.gold)
    }

    private fun buildHexagon(path: Path, cx: Float, cy: Float, radius: Float, rotationDeg: Float) {
        path.reset()
        for (i in 0 until 6) {
            val angle = Math.toRadians((rotationDeg + i * 60f).toDouble())
            val px = cx + cos(angle).toFloat() * radius
            val py = cy + sin(angle).toFloat() * radius
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }
}
