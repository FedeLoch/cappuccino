package art.cappuccino.paint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

enum class Mood { IDLE, TIP, THINKING, NAP }

enum class Mascot { PAINT_DROP, INK_BLOT }

private const val BLINK_EVERY_MS = 4200L
private const val BLINK_MS = 140L
private const val CREAM = 0xFFF1EFE8.toInt()
private const val INK = 0xFF2C2C2A.toInt()

/** Draws the mascot in a 120 by 120 box, with its center at the origin. */
class CompanionView(context: Context) : View(context) {
    var mascot = Mascot.PAINT_DROP
        set(value) { field = value; invalidate() }
    var mood = Mood.IDLE
        set(value) { field = value; invalidate() }
    var bodyColor = ACCENT
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private var blinking = false

    private val drop = Path().apply {
        moveTo(0f, -50f)
        cubicTo(0f, -50f, -34f, -8f, -34f, 14f)
        cubicTo(-34f, 36f, -18f, 46f, 0f, 46f)
        cubicTo(18f, 46f, 34f, 36f, 34f, 14f)
        cubicTo(34f, -8f, 0f, -50f, 0f, -50f)
        close()
    }
    private val blot = Path().apply {
        moveTo(0f, -42f)
        cubicTo(24f, -48f, 36f, -28f, 40f, -10f)
        cubicTo(50f, 8f, 42f, 30f, 22f, 36f)
        cubicTo(8f, 48f, -18f, 46f, -30f, 34f)
        cubicTo(-50f, 30f, -54f, 0f, -40f, -14f)
        cubicTo(-38f, -34f, -18f, -44f, 0f, -42f)
        close()
    }

    private val blinkLoop = object : Runnable {
        override fun run() {
            blinking = !blinking
            invalidate()
            postDelayed(this, if (blinking) BLINK_MS else BLINK_EVERY_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blinkLoop, BLINK_EVERY_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkLoop)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(width / 120f, width / 120f)
        val faceY: Float
        val face: Int
        if (mascot == Mascot.PAINT_DROP) {
            fill.color = bodyColor
            canvas.drawPath(drop, fill)
            line.color = CREAM
            canvas.drawPath(drop, line)
            faceY = 10f
            val luminance = Color.luminance(bodyColor)
            face = if (luminance > 0.35f) INK else CREAM
        } else {
            fill.color = INK
            canvas.drawPath(blot, fill)
            line.color = CREAM
            canvas.drawPath(blot, line)
            canvas.drawCircle(46f, -34f, 5f, fill)
            canvas.drawCircle(-48f, 38f, 4f, fill)
            faceY = 2f
            face = CREAM
        }
        drawFace(canvas, faceY, face)
    }

    private fun drawFace(canvas: Canvas, y: Float, color: Int) {
        fill.color = color
        line.color = color
        when (mood) {
            Mood.NAP -> {
                canvas.drawArc(-19f, y - 6f, -7f, y + 6f, 20f, 140f, false, line)
                canvas.drawArc(7f, y - 6f, 19f, y + 6f, 20f, 140f, false, line)
            }
            Mood.TIP -> {
                canvas.drawCircle(-13f, y, 6.5f, fill)
                canvas.drawCircle(13f, y, 6.5f, fill)
                canvas.drawOval(-5f, y + 12f, 5f, y + 20f, fill)
                fill.color = 0xFFFAC775.toInt()
                canvas.drawCircle(42f, -42f, 8f, fill)
                return
            }
            Mood.THINKING -> {
                canvas.drawCircle(-10f, y - 4f, 4.5f, fill)
                canvas.drawCircle(16f, y - 4f, 4.5f, fill)
                canvas.drawLine(-5f, y + 15f, 7f, y + 13f, line)
                return
            }
            Mood.IDLE -> {
                if (blinking) {
                    canvas.drawLine(-18f, y, -8f, y, line)
                    canvas.drawLine(8f, y, 18f, y, line)
                } else {
                    canvas.drawCircle(-13f, y, 4.5f, fill)
                    canvas.drawCircle(13f, y, 4.5f, fill)
                }
            }
        }
        canvas.drawArc(-8f, y + 4f, 8f, y + 18f, 30f, 120f, false, line)
    }
}
