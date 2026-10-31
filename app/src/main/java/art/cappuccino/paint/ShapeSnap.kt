package art.cappuccino.paint

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val MIN_LENGTH = 40f
private const val CLOSED_GAP = 0.2f
private const val LINE_WOBBLE = 0.08f
private const val SHAPE_WOBBLE = 0.1f
private const val GUIDE_DEGREES = 6.0
private const val AXIS_DEGREES = 3.0
private const val OUTLINE_POINTS = 64
private const val LINE_POINTS = 32
private const val SIDE_POINTS = 12
private const val ELLIPSE_POINTS = 96
private const val SEARCH_STEP_DEGREES = 2

/**
 * Turns a hand-drawn stroke, as x and y pairs, into a clean line, ellipse, or rectangle. Lines
 * lean toward [vanishing] points and level axes. Returns null when it isn't close to one.
 */
fun snapShape(path: FloatArray, vanishing: List<Float> = emptyList()): FloatArray? {
    val count = path.size / 2
    if (count < 4) return null
    var length = 0f
    for (index in 1 until count) {
        length += hypot(path[index * 2] - path[index * 2 - 2], path[index * 2 + 1] - path[index * 2 - 1])
    }
    if (length < MIN_LENGTH) return null
    val gap = hypot(path[count * 2 - 2] - path[0], path[count * 2 - 1] - path[1])
    return if (gap < length * CLOSED_GAP) closedShape(path, length) else straightLine(path, vanishing)
}

private fun straightLine(path: FloatArray, vanishing: List<Float>): FloatArray? {
    val startX = path[0]
    val startY = path[1]
    val dx = path[path.size - 2] - startX
    val dy = path[path.size - 1] - startY
    val chord = hypot(dx, dy)
    for (index in path.indices step 2) {
        val away = abs((path[index] - startX) * dy - (path[index + 1] - startY) * dx) / chord
        if (away > chord * LINE_WOBBLE) return null
    }
    val heading = atan2(dy.toDouble(), dx.toDouble())
    fun offBy(direction: Double): Double {
        var turn = (heading - direction) % PI
        if (turn > PI / 2) turn -= PI
        if (turn < -PI / 2) turn += PI
        return Math.toDegrees(abs(turn))
    }
    val guides = vanishing.chunked(2).filter { it.size == 2 }
        .map { atan2((it[1] - startY).toDouble(), (it[0] - startX).toDouble()) }
    val aim = guides.filter { offBy(it) < GUIDE_DEGREES }.minByOrNull(::offBy)
        ?: (0..3).map { it * PI / 4 }.firstOrNull { offBy(it) < AXIS_DEGREES }
        ?: heading
    val reach = (dx * cos(aim) + dy * sin(aim)).toFloat()
    return FloatArray((LINE_POINTS + 1) * 2) { index ->
        val share = (index / 2) / LINE_POINTS.toFloat()
        if (index % 2 == 0) startX + (reach * cos(aim)).toFloat() * share else startY + (reach * sin(aim)).toFloat() * share
    }
}

/** Evenly spaced points along the stroke, so slow parts of it don't count more. */
private fun outline(path: FloatArray, length: Float): FloatArray {
    val step = length / OUTLINE_POINTS
    val points = FloatArray(OUTLINE_POINTS * 2)
    var filled = 0
    var walked = 0f
    for (index in 1 until path.size / 2) {
        val fromX = path[index * 2 - 2]
        val fromY = path[index * 2 - 1]
        val piece = hypot(path[index * 2] - fromX, path[index * 2 + 1] - fromY)
        while (filled < OUTLINE_POINTS && piece > 0f && walked + piece >= filled * step) {
            val share = (filled * step - walked) / piece
            points[filled * 2] = fromX + (path[index * 2] - fromX) * share
            points[filled * 2 + 1] = fromY + (path[index * 2 + 1] - fromY) * share
            filled++
        }
        walked += piece
    }
    return points.copyOf(filled * 2)
}

private fun closedShape(path: FloatArray, length: Float): FloatArray? {
    val points = outline(path, length)
    val count = points.size / 2
    if (count < 8) return null
    var bestArea = Float.MAX_VALUE
    var angle = 0.0
    var box = FloatArray(4)
    for (degrees in 0 until 90 step SEARCH_STEP_DEGREES) {
        val turn = Math.toRadians(degrees.toDouble())
        val along = FloatArray(count) { (points[it * 2] * cos(turn) + points[it * 2 + 1] * sin(turn)).toFloat() }
        val across = FloatArray(count) { (-points[it * 2] * sin(turn) + points[it * 2 + 1] * cos(turn)).toFloat() }
        val area = (along.max() - along.min()) * (across.max() - across.min())
        if (area < bestArea) {
            bestArea = area
            angle = turn
            box = floatArrayOf(along.min(), across.min(), along.max(), across.max())
        }
    }
    val halfWidth = (box[2] - box[0]) / 2f
    val halfHeight = (box[3] - box[1]) / 2f
    if (halfWidth < 1f || halfHeight < 1f) return null
    val centerU = (box[0] + box[2]) / 2f
    val centerV = (box[1] + box[3]) / 2f
    var ellipseMiss = 0f
    var boxMiss = 0f
    for (index in 0 until count) {
        val x = points[index * 2]
        val y = points[index * 2 + 1]
        val u = (x * cos(angle) + y * sin(angle)).toFloat() - centerU
        val v = (-x * sin(angle) + y * cos(angle)).toFloat() - centerV
        val spread = sqrt((u / halfWidth) * (u / halfWidth) + (v / halfHeight) * (v / halfHeight))
        ellipseMiss += abs(spread - 1f) * (halfWidth + halfHeight) / 2f
        boxMiss += minOf(abs(abs(u) - halfWidth), abs(abs(v) - halfHeight))
    }
    val size = halfWidth + halfHeight
    if (minOf(ellipseMiss, boxMiss) / count / size > SHAPE_WOBBLE) return null

    fun place(u: Float, v: Float, into: FloatArray, at: Int) {
        val fullU = u + centerU
        val fullV = v + centerV
        into[at * 2] = (fullU * cos(angle) - fullV * sin(angle)).toFloat()
        into[at * 2 + 1] = (fullU * sin(angle) + fullV * cos(angle)).toFloat()
    }
    if (ellipseMiss <= boxMiss) {
        val result = FloatArray((ELLIPSE_POINTS + 2) * 2)
        for (index in 0..ELLIPSE_POINTS + 1) {
            val turn = index * 2.0 * PI / ELLIPSE_POINTS
            place((halfWidth * cos(turn)).toFloat(), (halfHeight * sin(turn)).toFloat(), result, index)
        }
        return result
    }
    val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f, -1f to -1f)
    val result = FloatArray((SIDE_POINTS * 4 + 1) * 2)
    for (side in 0 until 4) {
        for (step in 0 until SIDE_POINTS) {
            val share = step / SIDE_POINTS.toFloat()
            val u = corners[side].first + (corners[side + 1].first - corners[side].first) * share
            val v = corners[side].second + (corners[side + 1].second - corners[side].second) * share
            place(u * halfWidth, v * halfHeight, result, side * SIDE_POINTS + step)
        }
    }
    place(-halfWidth, -halfHeight, result, SIDE_POINTS * 4)
    return result
}
