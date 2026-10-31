package art.cappuccino.paint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Shows the lasso path while it's being drawn, above the canvas. */
class LassoOverlay(context: Context) : View(context) {
    private val path = Path()
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = Color.WHITE
    }
    private val dashes = Paint(base).apply {
        color = ACCENT
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private var started = false

    fun add(x: Float, y: Float) {
        if (started) path.lineTo(x, y) else path.moveTo(x, y)
        started = true
        invalidate()
    }

    fun clear() {
        path.reset()
        started = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, base)
        canvas.drawPath(path, dashes)
    }
}

/** Shows the reach of the liquify brush. */
class BrushRing(context: Context) : View(context) {
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = Color.BLACK
        alpha = 110
    }
    private val inner = Paint(outer).apply {
        strokeWidth = context.dp(1).toFloat()
        color = Color.WHITE
    }
    private var x = 0f
    private var y = 0f
    private var radius = 0f

    fun move(x: Float, y: Float, radius: Float) {
        this.x = x
        this.y = y
        this.radius = radius
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        canvas.drawCircle(x, y, radius, outer)
        canvas.drawCircle(x, y, radius, inner)
    }
}

/** A loupe ring: the new color on top and the previous color below. */
class PickRing(context: Context, private val previous: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()
    var picked = previous
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val thickness = width * 0.16f
        val inset = thickness / 2f + 2f
        bounds.set(inset, inset, width - inset, height - inset)
        paint.strokeWidth = thickness + 4f
        paint.color = Color.WHITE
        canvas.drawOval(bounds, paint)
        paint.strokeWidth = thickness
        paint.color = picked
        canvas.drawArc(bounds, 180f, 180f, false, paint)
        paint.color = previous
        canvas.drawArc(bounds, 0f, 180f, false, paint)
    }
}
