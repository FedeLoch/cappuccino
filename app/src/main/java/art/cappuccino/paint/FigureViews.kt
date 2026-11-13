package art.cappuccino.paint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The figure guide over the canvas: the body as bones and joints, or the face as an oval with
 * the levels where features usually sit. Points the notes are about turn the accent color.
 */
class FigureOverlay(context: Context, private val toScreen: (Float, Float) -> FloatArray) : View(context) {
    var body: FloatArray? = null
    var face: FloatArray? = null
    var flagged: Set<Int> = emptySet()
    var showFace = false
    var landmarks = true
    var editing = false

    private val density = resources.displayMetrics.density
    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val boneShadow = Paint(bone).apply {
        strokeWidth = 4.5f * density
        color = Color.argb(110, 30, 22, 18)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ACCENT
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val jointRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(160, 30, 22, 18)
    }
    private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        textSize = 11f * density
    }

    private fun screen(points: FloatArray, index: Int) = toScreen(points[index * 2], points[index * 2 + 1])

    override fun onDraw(canvas: Canvas) {
        if (showFace) face?.let { drawFace(canvas, it) } else body?.let { drawBody(canvas, it) }
        // Both guides show while you draw; only the one being edited shows while editing.
        if (!editing) {
            if (showFace) body?.let { drawBody(canvas, it) } else face?.let { drawFace(canvas, it) }
        }
    }

    private fun drawBody(canvas: Canvas, points: FloatArray) {
        val spots = Array(BODY_JOINTS) { screen(points, it) }
        val top = spots[HEAD_TOP]
        val chin = spots[CHIN]
        val head = hypot(chin[0] - top[0], chin[1] - top[1])
        if (landmarks && head > 2f) drawLandmarks(canvas, spots, head)
        for ((from, to) in BODY_BONES) {
            if (from == CHIN && to == HEAD_TOP) continue
            canvas.drawLine(spots[from][0], spots[from][1], spots[to][0], spots[to][1], boneShadow)
            canvas.drawLine(spots[from][0], spots[from][1], spots[to][0], spots[to][1], bone)
        }
        // The head as an oval from its top to the chin, about three quarters as wide as it's tall.
        val angle = Math.toDegrees(atan2((chin[1] - top[1]).toDouble(), (chin[0] - top[0]).toDouble())).toFloat() - 90f
        canvas.save()
        canvas.rotate(angle, (top[0] + chin[0]) / 2f, (top[1] + chin[1]) / 2f)
        val oval = RectF(
            (top[0] + chin[0]) / 2f - head * 0.38f, (top[1] + chin[1]) / 2f - head / 2f,
            (top[0] + chin[0]) / 2f + head * 0.38f, (top[1] + chin[1]) / 2f + head / 2f,
        )
        canvas.drawOval(oval, boneShadow)
        canvas.drawOval(oval, bone)
        canvas.restore()
        spots.forEachIndexed { index, spot -> drawPoint(canvas, spot, index in flagged) }
    }

    private fun drawLandmarks(canvas: Canvas, spots: Array<FloatArray>, head: Float) {
        // Ticks at each landmark's height, beside the figure, measured in heads from the top.
        val right = spots.maxOf { it[0] } + head * 0.6f
        val top = spots[HEAD_TOP][1]
        for ((heads, name) in BODY_LANDMARKS) {
            val y = top + heads * head
            canvas.drawLine(right - head * 0.25f, y, right + head * 0.25f, y, dashed)
            canvas.drawText(tr(name), right + head * 0.35f, y + caption.textSize / 3f, caption)
        }
    }

    private fun drawFace(canvas: Canvas, points: FloatArray) {
        val crown = screen(points, 0)
        val chin = screen(points, 1)
        val length = hypot(chin[0] - crown[0], chin[1] - crown[1])
        if (length < 2f) return
        val angle = Math.toDegrees(atan2((chin[1] - crown[1]).toDouble(), (chin[0] - crown[0]).toDouble())).toFloat() - 90f
        val cx = (crown[0] + chin[0]) / 2f
        val cy = (crown[1] + chin[1]) / 2f
        canvas.save()
        canvas.rotate(angle, cx, cy)
        val top = cy - length / 2f
        val half = length * 0.37f
        val oval = RectF(cx - half, top, cx + half, top + length)
        canvas.drawOval(oval, boneShadow)
        canvas.drawOval(oval, bone)
        canvas.drawLine(cx, top, cx, top + length, dashed)
        for ((level, name) in FACE_LEVELS) {
            val y = top + level * length
            canvas.drawLine(cx - half * 0.9f, y, cx + half * 0.9f, y, dashed)
            canvas.drawText(tr(name), cx + half + 6f * density, y + caption.textSize / 3f, caption)
        }
        canvas.restore()
        for (index in 0 until FACE_POINTS) drawPoint(canvas, screen(points, index), index in flagged)
    }

    private fun drawPoint(canvas: Canvas, spot: FloatArray, flaggedPoint: Boolean) {
        val radius = (if (editing) 6.5f else 4.5f) * density
        canvas.drawCircle(spot[0], spot[1], radius, if (flaggedPoint) warn else joint)
        canvas.drawCircle(spot[0], spot[1], radius, jointRing)
    }

    /** The point under a touch, within reach of a finger, or -1. */
    fun pointAt(x: Float, y: Float): Int {
        val points = (if (showFace) face else body) ?: return -1
        val reach = 28f * density
        var best = -1
        var bestDistance = reach
        for (index in 0 until points.size / 2) {
            val spot = screen(points, index)
            val distance = hypot(spot[0] - x, spot[1] - y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}

/**
 * The 3D model's preview over the canvas, drawn where the model will land. While a new frame
 * renders, the last one slides to wherever the model has moved since, so moving it follows the finger.
 */
class ModelPreview(
    context: Context,
    private val canvasMatrix: () -> Matrix,
    private val center: () -> FloatArray,
) : View(context) {
    var bitmap: Bitmap? = null
    var scale = 1f
    var renderedX = 0f
    var renderedY = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val place = Matrix()

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        val now = center()
        place.setScale(1f / scale, 1f / scale)
        place.postTranslate(now[0] - renderedX, now[1] - renderedY)
        place.postConcat(canvasMatrix())
        canvas.drawBitmap(image, place, paint)
    }
}
