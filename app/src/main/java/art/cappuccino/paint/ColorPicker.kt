package art.cappuccino.paint

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** A saturation and brightness square above a hue bar. */
class ColorPicker(context: Context, color: Int, private val onPick: (Int) -> Unit) : View(context) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        setColor(Color.WHITE)
    }
    private val barHeight = context.dp(28).toFloat()
    private val gap = context.dp(12).toFloat()
    private var draggingHue = false

    private val squareHeight get() = height - barHeight - gap

    override fun onDraw(canvas: Canvas) {
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val width = width.toFloat()
        paint.shader = LinearGradient(0f, 0f, width, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, squareHeight, paint)
        paint.shader = LinearGradient(
            0f, 0f, 0f, squareHeight, 0, Color.BLACK, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, squareHeight, paint)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(0f, 0f, width, 0f, hues, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height - barHeight, width, height.toFloat(), paint)
        paint.shader = null
        canvas.drawCircle(hsv[1] * width, (1f - hsv[2]) * squareHeight, gap * 0.7f, ring)
        canvas.drawCircle(hsv[0] / 360f * width, height - barHeight / 2f, gap * 0.7f, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) draggingHue = event.y > squareHeight
        val x = (event.x / width).coerceIn(0f, 1f)
        if (draggingHue) {
            hsv[0] = x * 359.9f
        } else {
            hsv[1] = x
            hsv[2] = 1f - (event.y / squareHeight).coerceIn(0f, 1f)
        }
        invalidate()
        onPick(Color.HSVToColor(hsv))
        return true
    }
}
