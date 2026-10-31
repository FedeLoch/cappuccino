package art.cappuccino.paint

private const val MAX_SMOOTHING = 0.92f

/** Eases the pen position toward the input, so slow lines lose their wobble. */
class LineStabilizer {
    private var follow = 1f
    private var x = 0f
    private var y = 0f
    private var started = false

    val isActive get() = follow < 1f

    fun reset(strength: Float) {
        follow = 1f - MAX_SMOOTHING * strength.coerceIn(0f, 1f)
        started = false
    }

    fun next(point: FloatArray) {
        if (!started) {
            x = point[0]
            y = point[1]
            started = true
        } else {
            x += (point[0] - x) * follow
            y += (point[1] - y) * follow
        }
        point[0] = x
        point[1] = y
    }
}
