package art.cappuccino.paint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/** Shows the lasso path while it's being drawn, above the canvas, with a faint line back to where it closes. */
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
    private val closing = Paint(dashes).apply { alpha = 120 }
    private val start = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val startRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var started = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun add(x: Float, y: Float) {
        if (started) {
            path.lineTo(x, y)
        } else {
            path.moveTo(x, y)
            startX = x
            startY = y
        }
        started = true
        lastX = x
        lastY = y
        invalidate()
    }

    fun clear() {
        path.reset()
        started = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!started) return
        canvas.drawLine(lastX, lastY, startX, startY, closing)
        canvas.drawPath(path, base)
        canvas.drawPath(path, dashes)
        val radius = context.dp(4).toFloat()
        canvas.drawCircle(startX, startY, radius + context.dp(1), startRing)
        canvas.drawCircle(startX, startY, radius, start)
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

/** Draws what a companion note is pointing at. It follows the canvas as it pans and zooms. */
class NoteOverlay(
    context: Context,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val toScreen: (Float, Float) -> FloatArray,
) : View(context) {
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(4).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        alpha = 90
    }
    private val line = Paint(halo).apply {
        strokeWidth = context.dp(2).toFloat()
        color = Color.WHITE
    }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textSize = context.dp(12).toFloat()
    }
    private val shape = Path()
    private val box = RectF()
    private var marks: List<NoteMark> = emptyList()

    fun show(next: List<NoteMark>) {
        if (next == marks) return
        marks = next
        invalidate()
        alpha = 0f
        animate().alpha(1f).setDuration(FADE_MS).start()
    }

    private fun place(x: Float, y: Float) = toScreen(x * canvasWidth, y * canvasHeight)

    override fun onDraw(canvas: Canvas) {
        for (mark in marks) {
            shape.reset()
            val anchor: FloatArray
            when (mark.shape) {
                MarkShape.RING -> {
                    val center = place(mark.x1, mark.y1)
                    val edge = place(mark.x1 + mark.x2, mark.y1)
                    val radius = hypot(edge[0] - center[0], edge[1] - center[1])
                    shape.addCircle(center[0], center[1], radius, Path.Direction.CW)
                    anchor = floatArrayOf(center[0], center[1] + radius)
                }
                MarkShape.BOX -> {
                    val corners = listOf(mark.x1 to mark.y1, mark.x2 to mark.y1, mark.x2 to mark.y2, mark.x1 to mark.y2)
                        .map { (x, y) -> place(x, y) }
                    shape.moveTo(corners[0][0], corners[0][1])
                    corners.drop(1).forEach { shape.lineTo(it[0], it[1]) }
                    shape.close()
                    anchor = floatArrayOf((corners[2][0] + corners[3][0]) / 2f, (corners[2][1] + corners[3][1]) / 2f)
                }
                MarkShape.LINE -> {
                    val start = place(mark.x1, mark.y1)
                    val end = place(mark.x2, mark.y2)
                    shape.moveTo(start[0], start[1])
                    shape.lineTo(end[0], end[1])
                    anchor = floatArrayOf((start[0] + end[0]) / 2f, (start[1] + end[1]) / 2f)
                }
            }
            canvas.drawPath(shape, halo)
            canvas.drawPath(shape, line)
            drawLabel(canvas, mark.label, anchor[0], anchor[1] + context.dp(8))
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float) {
        val padding = context.dp(8).toFloat()
        val tagWidth = text.measureText(label) + padding * 2f
        val tagHeight = context.dp(24).toFloat()
        val edge = context.dp(TOOL_MARGIN_DP).toFloat()
        val left = (x - tagWidth / 2f).coerceIn(edge, max(edge, width - tagWidth - edge))
        val top = y.coerceIn(edge, max(edge, height - tagHeight))
        box.set(left, top, left + tagWidth, top + tagHeight)
        canvas.drawRoundRect(box, tagHeight / 2f, tagHeight / 2f, tag)
        canvas.drawText(label, left + padding, top + tagHeight / 2f - (text.ascent() + text.descent()) / 2f, text)
    }

    private companion object {
        const val FADE_MS = 180L
        const val TOOL_MARGIN_DP = 72
    }
}

/** Book cover guides over the canvas: the bleed shaded past the trim, the safe areas, and the spine folds. */
class PrintGuideOverlay(
    context: Context,
    private val setup: PrintSetup,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val toScreen: (Float, Float) -> FloatArray,
) : View(context) {
    private val bleed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 40, 30, 25) }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = Color.argb(90, 0, 0, 0)
    }
    private val trimLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat() * 1.5f
        color = Color.WHITE
    }
    private val safeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = ACCENT
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val barcodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        textSize = context.dp(11).toFloat()
        textAlign = Paint.Align.CENTER
    }

    private fun quad(left: Float, top: Float, right: Float, bottom: Float) = Path().apply {
        val corners = listOf(left to top, right to top, right to bottom, left to bottom).map { toScreen(it.first, it.second) }
        moveTo(corners[0][0], corners[0][1])
        corners.drop(1).forEach { lineTo(it[0], it[1]) }
        close()
    }

    override fun onDraw(canvas: Canvas) {
        val trim = setup.trim()
        val trimPath = quad(trim[0], trim[1], trim[2], trim[3])
        val bleedPath = quad(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()).apply {
            fillType = Path.FillType.EVEN_ODD
            addPath(trimPath)
        }
        canvas.drawPath(bleedPath, bleed)
        canvas.drawPath(trimPath, shadow)
        canvas.drawPath(trimPath, trimLine)
        setup.folds()?.forEach { x ->
            val fold = quad(x, trim[1], x, trim[3])
            canvas.drawPath(fold, shadow)
            canvas.drawPath(fold, trimLine)
        }
        val areas = setup.safeAreas()
        val names = when (areas.size) {
            3 -> listOf(tr("Back cover"), tr("Spine"), tr("Front"))
            2 -> listOf(tr("Back cover"), tr("Front"))
            else -> emptyList()
        }
        setup.barcode()?.let { box ->
            val path = quad(box[0], box[1], box[2], box[3])
            canvas.drawPath(path, barcodeFill)
            canvas.drawPath(path, safeLine)
            val spot = toScreen((box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f)
            canvas.drawText(tr("Barcode"), spot[0], spot[1] + caption.textSize / 3f, caption)
        }
        areas.forEachIndexed { index, area ->
            canvas.drawPath(quad(area[0], area[1], area[2], area[3]), safeLine)
            val name = names.getOrNull(index) ?: return@forEachIndexed
            val spot = toScreen((area[0] + area[2]) / 2f, area[1])
            canvas.drawText(name, spot[0], spot[1] - context.dp(6), caption)
        }
    }
}
