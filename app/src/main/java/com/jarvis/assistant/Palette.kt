package com.jarvis.assistant

import android.content.Context
import android.graphics.Color
import kotlin.math.min

object Palette {
    val bg = 0xFF04060C.toInt()
    val bgDeep = 0xFF020409.toInt()
    val panel = 0xFF0A131E.toInt()
    val panelSoft = 0xFF0E1A27.toInt()
    val cyan = 0xFF5FE8FF.toInt()
    val cyanDim = 0xFF2A8FAD.toInt()
    val gold = 0xFFFFC56B.toInt()
    val ice = 0xFFEAFBFF.toInt()
    val danger = 0xFFFF5C6C.toInt()
    val textDim = 0xFF8FA6B2.toInt()
    val textFaint = 0xFF556B78.toInt()
}

fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
    return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}

fun blend(a: Int, b: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

fun clamp(v: Float, lo: Float, hi: Float): Float = min(maxOf(v, lo), hi)
