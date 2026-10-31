package art.cappuccino.paint

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

const val PERSPECTIVE_TOLERANCE = 4f

private const val MIN_LINE_LENGTH = 160f
private const val MAX_BEND = 0.04f
private const val RECENT_STROKES = 10

/** Starting vanishing points in canvas coordinates, as x and y pairs. */
fun defaultVanishingPoints(count: Int): List<Float> {
    val horizon = CANVAS_HEIGHT * 0.45f
    return when (count) {
        0 -> emptyList()
        1 -> listOf(CANVAS_WIDTH * 0.5f, horizon)
        2 -> listOf(CANVAS_WIDTH * 0.12f, horizon, CANVAS_WIDTH * 0.88f, horizon)
        else -> listOf(
            CANVAS_WIDTH * 0.12f, horizon,
            CANVAS_WIDTH * 0.88f, horizon,
            CANVAS_WIDTH * 0.5f, CANVAS_HEIGHT * 0.92f,
        )
    }
}

private fun lineGap(first: Double, second: Double): Float {
    val gap = abs(first - second) % PI
    return Math.toDegrees(min(gap, PI - gap)).toFloat()
}

/**
 * Finds how many degrees a straight stroke is from the nearest allowed perspective direction.
 * Returns null when the stroke is short or curved, since those aren't construction lines.
 * Verticals are always allowed with 1 or 2 points, and horizontals are allowed with 1 point.
 */
fun strokeDrift(path: FloatArray, vanishingPoints: List<Float>): Float? {
    if (vanishingPoints.isEmpty() || path.size < 4) return null
    val startX = path[0]
    val startY = path[1]
    val endX = path[path.size - 2]
    val endY = path[path.size - 1]
    val length = hypot(endX - startX, endY - startY)
    if (length < MIN_LINE_LENGTH) return null
    for (index in path.indices step 2) {
        val offset = abs((endX - startX) * (startY - path[index + 1]) - (startX - path[index]) * (endY - startY))
        if (offset / length > length * MAX_BEND) return null
    }

    val direction = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
    val middleX = (startX + endX) / 2f
    val middleY = (startY + endY) / 2f
    var best = Float.MAX_VALUE
    for (index in vanishingPoints.indices step 2) {
        val toPoint = atan2(
            (vanishingPoints[index + 1] - middleY).toDouble(),
            (vanishingPoints[index] - middleX).toDouble(),
        )
        best = min(best, lineGap(direction, toPoint))
    }
    val pointCount = vanishingPoints.size / 2
    if (pointCount <= 2) best = min(best, lineGap(direction, PI / 2))
    if (pointCount == 1) best = min(best, lineGap(direction, 0.0))
    return best
}

/** Remembers recent straight strokes, so the companion can describe them calmly. */
class PerspectiveLog {
    private val drifts = ArrayDeque<Float>()

    fun add(drift: Float) {
        drifts.addLast(drift)
        if (drifts.size > RECENT_STROKES) drifts.removeFirst()
    }

    fun reset() = drifts.clear()

    fun summary(): String? {
        if (drifts.isEmpty()) return null
        val off = drifts.filter { it > PERSPECTIVE_TOLERANCE }
        return if (off.isEmpty()) {
            "Your last ${drifts.size} straight lines follow the guide."
        } else {
            "${off.size} of your last ${drifts.size} straight lines drift from the guide, " +
                "up to ${off.max().roundToInt()}°. That can be a deliberate choice."
        }
    }
}
