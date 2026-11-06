package art.cappuccino.paint

import kotlin.math.max
import kotlin.math.min

private const val MAX_WIDTH_SHARE = 0.4f
private const val MAX_HEIGHT_SHARE = 0.7f
private const val SNAP_DEGREES = 6f

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
    var rotation: Float = 0f,
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

/** Turns that land within a few degrees of a quarter turn settle on it. */
fun snapReferenceRotation(degrees: Float): Float {
    val turned = ((degrees % 360f) + 360f) % 360f
    val quarter = Math.round(turned / 90f) * 90f
    return if (kotlin.math.abs(turned - quarter) < SNAP_DEGREES) quarter % 360f else turned
}

/** True when the image lies on its side, so its window swaps width and height. */
fun referenceSideways(degrees: Float): Boolean = Math.round(degrees / 90f).mod(2) == 1
