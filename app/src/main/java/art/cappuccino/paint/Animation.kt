package art.cappuccino.paint

const val ONION_SHARE = 0.25f
const val DEFAULT_FPS = 8
val ANIMATION_SPEEDS = listOf(4, 8, 12, 24)

/**
 * How much of each frame shows while animating. Every layer is a frame, from the bottom up. The
 * current one shows fully, and with [onion] on, its neighbors show faintly so you can trace them.
 */
fun frameShares(count: Int, current: Int, onion: Boolean): FloatArray = FloatArray(count) { index ->
    when {
        index == current -> 1f
        onion && kotlin.math.abs(index - current) == 1 -> ONION_SHARE
        else -> 0f
    }
}

/** Frame order for an exported video that plays the animation [loops] times. */
fun animationTimeline(frameCount: Int, loops: Int): List<Int> = List(frameCount * loops) { it % frameCount }
