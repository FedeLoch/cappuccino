package art.cappuccino.paint

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Draws the guides for two-point boxes, a smooth blend, and a shape to mirror. */
internal fun drawExtraGuide(canvas: Canvas, kind: WarmupKind, layout: WarmupLayout, width: Float, height: Float, line: Paint) {
    val side = min(width, height)
    fun x(value: Float) = value * width
    fun y(value: Float) = value * height
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C7F72.toInt() }
    when (kind) {
        WarmupKind.PERSPECTIVE -> {
            canvas.drawLine(0f, y(layout.horizon), width, y(layout.horizon), Paint(line).apply { strokeWidth = side * 0.003f })
            listOf(layout.vanishLeft, layout.vanishRight).forEach { canvas.drawCircle(x(it.x), y(it.y), side * 0.011f, dot) }
            layout.corners.forEach { corner -> canvas.drawLine(x(corner[0]), y(corner[1]), x(corner[2]), y(corner[3]), Paint(line).apply { strokeWidth = side * 0.005f }) }
        }
        WarmupKind.GRADIENT -> {
            val box = layout.blendBox
            canvas.drawRect(x(box[0]), y(box[1]), x(box[2]), y(box[3]), line)
            val horizontal = box[2] - box[0] > box[3] - box[1]
            val ends = if (horizontal) {
                listOf(Spot(box[0] - 0.03f, (box[1] + box[3]) / 2f), Spot(box[2] + 0.03f, (box[1] + box[3]) / 2f))
            } else {
                listOf(Spot((box[0] + box[2]) / 2f, box[1] - 0.03f), Spot((box[0] + box[2]) / 2f, box[3] + 0.03f))
            }
            val (dark, light) = if (layout.blendDarkFirst) ends[0] to ends[1] else ends[1] to ends[0]
            canvas.drawCircle(x(dark.x), y(dark.y), side * 0.012f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A1D16.toInt() })
            canvas.drawCircle(x(light.x), y(light.y), side * 0.012f, Paint(line).apply { strokeWidth = side * 0.003f })
        }
        WarmupKind.MIRROR -> {
            val axis = Paint(line).apply {
                strokeWidth = side * 0.003f
                pathEffect = DashPathEffect(floatArrayOf(side * 0.015f, side * 0.012f), 0f)
            }
            canvas.drawLine(x(0.5f), y(0.08f), x(0.5f), y(0.92f), axis)
            val half = Path()
            layout.mirrorHalf.forEachIndexed { index, spot -> if (index == 0) half.moveTo(x(spot.x), y(spot.y)) else half.lineTo(x(spot.x), y(spot.y)) }
            canvas.drawPath(half, Paint(line).apply { strokeWidth = side * 0.006f; color = 0xFFB9B2A8.toInt() })
        }
        else -> Unit
    }
}

private fun degrees(radians: Double) = radians * 180 / PI

/** How far a line's direction is from pointing at [target], in degrees, ignoring which way it runs. */
private fun aimError(stroke: FloatArray, target: Spot): Double {
    val ax = stroke[0]
    val ay = stroke[1]
    val bx = stroke[stroke.size - 2]
    val by = stroke[stroke.size - 1]
    val direction = atan2((by - ay).toDouble(), (bx - ax).toDouble())
    val mx = (ax + bx) / 2f
    val my = (ay + by) / 2f
    val toward = atan2((target.y - my).toDouble(), (target.x - mx).toDouble())
    val difference = abs(((direction - toward) % PI + 1.5 * PI) % PI - PI / 2)
    return degrees(difference)
}

private fun FloatArray.span() = hypot(this[size - 2] - this[0], this[size - 1] - this[1])

/** Reads each box edge against the vanishing points, and the upright edges against true vertical. */
internal fun perspectiveReview(work: WarmupWork): WarmupReview {
    val layout = work.layout
    val lines = work.strokes.filter { it.size >= 4 && it.span() > 0.03f }
    if (lines.isEmpty()) return WarmupReview(tr("Draw the edges of a box first, then ask me again."), emptyList())
    val errors = lines.map { stroke ->
        val dx = abs(stroke[stroke.size - 2] - stroke[0])
        if (dx < stroke.span() * 0.2f) {
            degrees(atan2(dx.toDouble(), abs(stroke[stroke.size - 1] - stroke[1]).toDouble()))
        } else {
            minOf(aimError(stroke, layout.vanishLeft), aimError(stroke, layout.vanishRight))
        }
    }
    val mean = errors.average()
    val worst = lines[errors.indices.maxBy { errors[it] }]
    val marks = if (errors.max() > 4) listOf(NoteMark(tr("Aim at a vanishing point"), MarkShape.LINE, worst[0], worst[1], worst[worst.size - 2], worst[worst.size - 1])) else emptyList()
    val text = when {
        mean < 2.5 -> tr("Your edges run right into the vanishing points. Those boxes sit firmly in space.")
        mean < 6 -> tr("Most edges head to the vanishing points, a few drift by %1 degrees or so.", mean.toInt())
        else -> tr("The edges miss the vanishing points by about %1 degrees. Lay the pen toward the dot before each stroke.", mean.toInt())
    }
    return WarmupReview(text, marks)
}

/** Reads the blend as strips from its dark end to its light end, looking for order and smooth steps. */
internal fun gradientReview(work: WarmupWork): WarmupReview {
    val box = work.layout.blendBox
    val horizontal = box[2] - box[0] > box[3] - box[1]
    val strips = 16
    val values = List(strips) { index ->
        val from = index / strips.toFloat()
        val to = (index + 1) / strips.toFloat()
        val area = if (horizontal) {
            floatArrayOf(box[0] + (box[2] - box[0]) * from, box[1] + 0.01f, box[0] + (box[2] - box[0]) * to, box[3] - 0.01f)
        } else {
            floatArrayOf(box[0] + 0.01f, box[1] + (box[3] - box[1]) * from, box[2] - 0.01f, box[1] + (box[3] - box[1]) * to)
        }
        area to meanBox(work, area)
    }.let { if (work.layout.blendDarkFirst) it else it.reversed() }
    val levels = values.map { it.second }
    if (levels.all { it > 0.95f }) return WarmupReview(tr("Paint the blend first, then ask me again."), emptyList())
    val range = levels.last() - levels.first()
    val steps = levels.zipWithNext { a, b -> b - a }
    val jump = steps.indices.maxBy { abs(steps[it]) }
    val backwards = steps.count { it < -0.04f }
    val marks = ArrayList<NoteMark>()
    val text = when {
        range < 0.25f -> tr("The blend doesn't travel far yet. Push the dark end darker and keep the light end light.")
        backwards > 1 -> {
            marks += NoteMark(tr("Smooth this"), MarkShape.BOX, values[jump].first[0], values[jump].first[1], values[jump].first[2], values[jump].first[3])
            tr("The blend goes back and forth. Keep every step a little lighter than the one before.")
        }
        abs(steps[jump]) > range * 0.3f -> {
            val area = values[jump].first
            marks += NoteMark(tr("Smooth this"), MarkShape.BOX, area[0], area[1], area[2], area[3])
            tr("There's a sudden jump in the middle. Soften it with a light, low-opacity pass.")
        }
        else -> tr("A smooth, even blend from dark to light. That control will show in every rendering.")
    }
    return WarmupReview(text, marks)
}

private fun meanBox(work: WarmupWork, area: FloatArray): Float {
    var total = 0f
    var count = 0
    val left = (area[0] * work.width).toInt().coerceIn(0, work.width - 1)
    val right = (area[2] * work.width).toInt().coerceIn(left, work.width - 1)
    val top = (area[1] * work.height).toInt().coerceIn(0, work.height - 1)
    val bottom = (area[3] * work.height).toInt().coerceIn(top, work.height - 1)
    for (y in top..bottom) {
        for (x in left..right) {
            total += work.luminance(x, y)
            count++
        }
    }
    return if (count == 0) 1f else total / count
}

/** Compares what you drew on the right with the guide's mirror image. */
internal fun mirrorReview(work: WarmupWork): WarmupReview {
    val target = work.layout.mirrorHalf.map { Spot(1f - it.x, it.y) }
    val points = work.strokes.flatMap { stroke -> (stroke.indices step 2).map { Spot(stroke[it], stroke[it + 1]) } }.filter { it.x > 0.5f }
    if (points.isEmpty()) return WarmupReview(tr("Draw the other half on the right first, then ask me again."), emptyList())
    val near = 0.015f
    val covered = target.count { spot -> points.any { hypot(it.x - spot.x, it.y - spot.y) < near * 1.5f } } / target.size.toFloat()
    val distances = points.map { point -> target.minOf { hypot(it.x - point.x, it.y - point.y) } }
    val onTarget = distances.count { it < near * 1.5f } / distances.size.toFloat()
    val marks = ArrayList<NoteMark>()
    val text = when {
        covered < 0.5f -> {
            target.filter { spot -> points.none { hypot(it.x - spot.x, it.y - spot.y) < near * 1.5f } }.getOrNull(0)
                ?.let { marks += NoteMark(tr("Keep going here"), MarkShape.RING, it.x, it.y, 0.04f) }
            tr("You've mirrored about %1% of the shape. Follow it all the way down.", (covered * 100).toInt())
        }
        onTarget < 0.6f -> {
            val worst = points[distances.indices.maxBy { distances[it] }]
            val mirror = target.minBy { hypot(it.x - worst.x, it.y - worst.y) }
            marks += NoteMark(if (abs(worst.x - 0.5f) > abs(mirror.x - 0.5f)) tr("Closer to the middle") else tr("Further out"), MarkShape.RING, worst.x, worst.y, 0.045f)
            tr("The halves don't match yet. Compare how far each curve sits from the middle line.")
        }
        else -> tr("The two halves match beautifully. That's a balanced, symmetric shape.")
    }
    return WarmupReview(text, marks)
}
