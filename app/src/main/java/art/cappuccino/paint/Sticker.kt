package art.cappuccino.paint

import kotlin.math.min
import kotlin.math.sqrt

private const val SOLID_ALPHA = 24
private const val FAR = 1e12f
const val FULL_CANVAS_SHARE = 0.97f

/** Squared distance along one row or column to the nearest cell in [cost]. */
private fun nearest(cost: FloatArray, size: Int, output: FloatArray, sites: IntArray, edges: FloatArray) {
    var top = 0
    sites[0] = 0
    edges[0] = -FAR
    edges[1] = FAR
    for (cell in 1 until size) {
        var crossing: Float
        while (true) {
            val site = sites[top]
            crossing = ((cost[cell] + cell * cell) - (cost[site] + site * site)) / (2f * (cell - site))
            if (crossing > edges[top] || top == 0) break
            top--
        }
        top++
        sites[top] = cell
        edges[top] = crossing
        edges[top + 1] = FAR
    }
    top = 0
    for (cell in 0 until size) {
        while (edges[top + 1] < cell) top++
        val away = (cell - sites[top]).toFloat()
        output[cell] = away * away + cost[sites[top]]
    }
}

/** How far each pixel is from the drawing, in pixels. */
private fun distances(solid: BooleanArray, width: Int, height: Int): FloatArray {
    val squared = FloatArray(solid.size) { if (solid[it]) 0f else FAR }
    val longest = maxOf(width, height)
    val line = FloatArray(longest)
    val result = FloatArray(longest)
    val sites = IntArray(longest + 1)
    val edges = FloatArray(longest + 2)
    for (x in 0 until width) {
        for (y in 0 until height) line[y] = squared[y * width + x]
        nearest(line, height, result, sites, edges)
        for (y in 0 until height) squared[y * width + x] = result[y]
    }
    for (y in 0 until height) {
        for (x in 0 until width) line[x] = squared[y * width + x]
        nearest(line, width, result, sites, edges)
        for (x in 0 until width) squared[y * width + x] = result[x]
    }
    return squared
}

/**
 * The die-cut shape of a sticker: everything within [radius] of the drawing, with its holes filled
 * so the cut is one clean outline. Returns null when nothing is drawn.
 */
fun stickerShape(alpha: ByteArray, width: Int, height: Int, radius: Float): ByteArray? {
    val solid = BooleanArray(alpha.size) { alpha[it].toInt() and 0xFF >= SOLID_ALPHA }
    if (solid.none { it }) return null
    val squared = distances(solid, width, height)
    val shape = ByteArray(alpha.size) { index ->
        (min(1f, (radius - sqrt(squared[index]) + 0.5f).coerceAtLeast(0f)) * 255f).toInt().toByte()
    }
    val outside = BooleanArray(alpha.size)
    val pending = ArrayDeque<Int>()
    fun visit(index: Int) {
        if (!outside[index] && shape[index].toInt() == 0) {
            outside[index] = true
            pending.addLast(index)
        }
    }
    for (x in 0 until width) {
        visit(x)
        visit((height - 1) * width + x)
    }
    for (y in 0 until height) {
        visit(y * width)
        visit(y * width + width - 1)
    }
    while (pending.isNotEmpty()) {
        val index = pending.removeLast()
        val x = index % width
        if (x > 0) visit(index - 1)
        if (x < width - 1) visit(index + 1)
        if (index >= width) visit(index - width)
        if (index < alpha.size - width) visit(index + width)
    }
    for (index in shape.indices) if (shape[index].toInt() == 0 && !outside[index]) shape[index] = -1
    return shape
}

/** Shrinks a drawing's alpha, keeping thin marks, so the preview can follow a slider. */
fun shrinkAlpha(alpha: ByteArray, width: Int, height: Int, step: Int): ByteArray {
    val outWidth = width / step
    val outHeight = height / step
    val result = ByteArray(outWidth * outHeight)
    for (y in 0 until outHeight * step) {
        for (x in 0 until outWidth * step) {
            val target = y / step * outWidth + x / step
            val value = alpha[y * width + x]
            if (value.toInt() and 0xFF > result[target].toInt() and 0xFF) result[target] = value
        }
    }
    return result
}
