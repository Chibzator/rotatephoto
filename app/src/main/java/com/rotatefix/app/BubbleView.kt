package com.rotatefix.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/** Round floating button with an arrow that should point to the sky. */
class BubbleView(context: Context) : View(context) {

    var mode: Mode = Mode.UP
        set(value) {
            field = value
            contentDescription = value.label
            invalidate()
        }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD91E1E1E.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(1.5f)
        val off = mode == Mode.OFF

        ring.color = if (off) 0x66FFFFFF else 0xFFFFC107.toInt()
        arrow.color = if (off) 0x80FFFFFF.toInt() else Color.WHITE

        canvas.drawCircle(cx, cy, r, bg)
        canvas.drawCircle(cx, cy, r, ring)

        if (off) {
            val s = r * 0.38f
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, arrow)
            canvas.drawLine(cx - s, cy + s, cx + s, cy - s, arrow)
            return
        }

        canvas.save()
        canvas.rotate(mode.arrowAngle, cx, cy)
        path.reset()
        path.moveTo(cx, cy + r * 0.5f)
        path.lineTo(cx, cy - r * 0.45f)
        path.moveTo(cx - r * 0.32f, cy - r * 0.12f)
        path.lineTo(cx, cy - r * 0.47f)
        path.lineTo(cx + r * 0.32f, cy - r * 0.12f)
        canvas.drawPath(path, arrow)
        canvas.restore()
    }
}
