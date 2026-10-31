package art.cappuccino.paint

import kotlin.math.max
import kotlin.math.min

private const val MAX_WIDTH_SHARE = 0.4f
private const val MAX_HEIGHT_SHARE = 0.7f

data class ReferenceState(
    val file: String,
    var x: Int = 0,
    var y: Int = 0,
    var width: Int = 0,
    var zoom: Float = 1f,
    var panX: Float = 0f,
    var panY: Float = 0f,
    var grayscale: Boolean = false,
    var flipped: Boolean = false,
    var opacity: Float = 1f,
    var pinned: Boolean = false,
)

/** Picks a starting width that shows a whole reference, even a very tall one, on screen. */
fun fitReferenceWidth(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Int {
    val byWidth = screenWidth * MAX_WIDTH_SHARE
    val byHeight = screenHeight * MAX_HEIGHT_SHARE * imageWidth / max(imageHeight, 1)
    return min(byWidth, byHeight).toInt()
}

/** Keeps a zoomed image covering its window. */
fun clampReferencePan(pan: Float, zoom: Float, viewSize: Int): Float {
    val limit = viewSize * (zoom - 1f) / 2f
    return pan.coerceIn(-limit, limit)
}

/** Keeps part of a window reachable, so it can't be lost off screen. */
fun clampReferencePosition(position: Int, windowSize: Int, screenSize: Int, visible: Int): Int =
    position.coerceIn(visible - windowSize, screenSize - visible)
