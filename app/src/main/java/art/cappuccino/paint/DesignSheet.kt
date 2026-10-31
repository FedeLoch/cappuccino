package art.cappuccino.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val FRAME_COLUMNS = 3
const val FRAME_ROWS = 2
private const val FRAME_GAP_SHARE = 0.04f
private const val SHEET_CELL = 1000
private const val SHEET_MARGIN = 80
private const val SHEET_LABEL = 90
private const val SHEET_COLUMNS = 3

/** Hue turns offered as color variants, as shares of the color wheel. */
val COLOR_VARIANTS = listOf(1f / 12f, 1f / 6f, 1f / 3f, 1f / 2f, 2f / 3f, 5f / 6f)

/** Evenly spaced frames for thumbnail sketches, as left, top, right, bottom in canvas pixels. */
fun thumbnailFrames(width: Int, height: Int, columns: Int = FRAME_COLUMNS, rows: Int = FRAME_ROWS): List<FloatArray> {
    val gap = min(width, height) * FRAME_GAP_SHARE
    val frameWidth = (width - gap * (columns + 1)) / columns
    val frameHeight = (height - gap * (rows + 1)) / rows
    return (0 until rows).flatMap { row ->
        (0 until columns).map { column ->
            val left = gap + column * (frameWidth + gap)
            val top = gap + row * (frameHeight + gap)
            floatArrayOf(left, top, left + frameWidth, top + frameHeight)
        }
    }
}

/** Names variations A, B, C, and so on, then AA, AB, past the alphabet. */
fun variationLetter(index: Int): String =
    if (index < 26) ('A' + index).toString() else variationLetter(index / 26 - 1) + ('A' + index % 26)

/** Turns the hue of ARGB pixels in place of a copy, keeping saturation, value, and alpha. */
fun shiftHue(pixels: IntArray, turn: Float): IntArray = IntArray(pixels.size) { index ->
    val pixel = pixels[index]
    val red = (pixel shr 16 and 0xFF) / 255f
    val green = (pixel shr 8 and 0xFF) / 255f
    val blue = (pixel and 0xFF) / 255f
    val high = max(red, max(green, blue))
    val low = min(red, min(green, blue))
    val range = high - low
    if (range == 0f) return@IntArray pixel
    val hue = when (high) {
        red -> ((green - blue) / range + 6f) % 6f
        green -> (blue - red) / range + 2f
        else -> (red - green) / range + 4f
    }
    val turned = (hue + turn * 6f) % 6f
    val spread = range * (1f - abs(turned % 2f - 1f))
    val (r, g, b) = when (turned.toInt()) {
        0 -> Triple(range, spread, 0f)
        1 -> Triple(spread, range, 0f)
        2 -> Triple(0f, range, spread)
        3 -> Triple(0f, spread, range)
        4 -> Triple(spread, 0f, range)
        else -> Triple(range, 0f, spread)
    }
    (pixel and 0xFF000000.toInt()) or
        (((r + low) * 255f + 0.5f).toInt() shl 16) or
        (((g + low) * 255f + 0.5f).toInt() shl 8) or
        ((b + low) * 255f + 0.5f).toInt()
}

/** Lays out named images on a white page, ready to send to a client. */
fun contactSheet(title: String, images: List<Pair<String, Bitmap>>): Bitmap {
    val columns = min(SHEET_COLUMNS, max(1, images.size))
    val rows = ceil(images.size / columns.toFloat()).toInt()
    val tallest = images.maxOf { (_, image) -> SHEET_CELL * image.height / image.width }
    val rowHeight = tallest + SHEET_LABEL
    val page = Bitmap.createBitmap(
        SHEET_MARGIN + columns * (SHEET_CELL + SHEET_MARGIN),
        SHEET_MARGIN * 2 + SHEET_LABEL + rows * (rowHeight + SHEET_MARGIN),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(page)
    canvas.drawColor(Color.WHITE)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2C2C2A.toInt()
        textSize = 64f
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText(title, SHEET_MARGIN.toFloat(), SHEET_MARGIN + 64f, text)
    text.textSize = 44f
    text.typeface = Typeface.DEFAULT
    val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    images.forEachIndexed { index, (name, image) ->
        val left = SHEET_MARGIN + index % columns * (SHEET_CELL + SHEET_MARGIN).toFloat()
        val top = SHEET_MARGIN + SHEET_LABEL * 1f + index / columns * (rowHeight + SHEET_MARGIN)
        val height = SHEET_CELL * image.height / image.width
        canvas.drawBitmap(image, null, RectF(left, top, left + SHEET_CELL, top + height), filter)
        canvas.drawText(name, left, top + height + 60f, text)
    }
    return page
}
