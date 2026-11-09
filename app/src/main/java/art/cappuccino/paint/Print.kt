package art.cappuccino.paint

import org.json.JSONObject
import kotlin.math.roundToInt

/** Print resolution for book covers, in pixels per inch. */
const val PRINT_DPI = 300

/** The standard bleed past the trim line, in inches (3 mm). */
const val PRINT_BLEED = 0.125f

/** How far inside the trim line titles and faces should stay, in inches. */
const val PRINT_SAFE = 0.25f

/** How far text on the spine should stay from each fold, in inches. */
const val SPINE_SAFE = 0.0625f

/** The space printers keep for the ISBN barcode on the back cover, in inches. */
const val BARCODE_WIDTH = 2f
const val BARCODE_HEIGHT = 1.2f

class BookTrim(val label: String, val width: Float, val height: Float)

/** Common trim sizes for printed books, in inches. */
val BOOK_TRIMS = listOf(
    BookTrim("5 × 8 in", 5f, 8f),
    BookTrim("5.5 × 8.5 in", 5.5f, 8.5f),
    BookTrim("6 × 9 in", 6f, 9f),
    BookTrim("7 × 10 in", 7f, 10f),
    BookTrim("8.5 × 11 in", 8.5f, 11f),
    BookTrim("8.5 × 8.5 in", 8.5f, 8.5f),
    BookTrim("A5", 5.83f, 8.27f),
)

/** Paper for the inside pages, with the thickness one page adds to the spine, in inches. */
class BookPaper(val label: String, val perPage: Float)

val BOOK_PAPERS = listOf(
    BookPaper("White paper", 0.002252f),
    BookPaper("Cream paper", 0.0025f),
    BookPaper("Color paper", 0.002347f),
)

fun spineWidth(pages: Int, paper: BookPaper): Float = pages * paper.perPage

/**
 * A book cover laid out for print: the front alone, or back, spine, and front side by side,
 * with bleed all around. Sizes are in inches.
 */
data class PrintSetup(
    val trimWidth: Float,
    val trimHeight: Float,
    val spine: Float = 0f,
    val wrap: Boolean = false,
    val bleed: Float = PRINT_BLEED,
    val dpi: Int = PRINT_DPI,
) {
    /** The whole cover's width once trimmed, in inches. */
    val trimmedWidth get() = if (wrap) trimWidth * 2 + spine else trimWidth
    val pixelWidth get() = ((trimmedWidth + bleed * 2) * dpi).roundToInt()
    val pixelHeight get() = ((trimHeight + bleed * 2) * dpi).roundToInt()

    fun px(inches: Float) = inches * dpi

    /** Left, top, right, bottom of the trim line, in canvas pixels. */
    fun trim(): FloatArray = floatArrayOf(px(bleed), px(bleed), px(bleed + trimmedWidth), px(bleed + trimHeight))

    /** Where the spine's folds fall, in canvas pixels, or nothing for a front cover. */
    fun folds(): FloatArray? = if (!wrap || spine <= 0f) null else floatArrayOf(px(bleed + trimWidth), px(bleed + trimWidth + spine))

    /** Each panel with art that must stay clear of the edges: back, spine, and front for a wrap. */
    fun safeAreas(): List<FloatArray> {
        val top = px(bleed + PRINT_SAFE)
        val bottom = px(bleed + trimHeight - PRINT_SAFE)
        if (!wrap) return listOf(floatArrayOf(px(bleed + PRINT_SAFE), top, px(bleed + trimWidth - PRINT_SAFE), bottom))
        val back = floatArrayOf(px(bleed + PRINT_SAFE), top, px(bleed + trimWidth - PRINT_SAFE), bottom)
        val frontLeft = bleed + trimWidth + spine
        val front = floatArrayOf(px(frontLeft + PRINT_SAFE), top, px(frontLeft + trimWidth - PRINT_SAFE), bottom)
        if (spine <= SPINE_SAFE * 2) return listOf(back, front)
        val spineArea = floatArrayOf(px(bleed + trimWidth + SPINE_SAFE), top, px(bleed + trimWidth + spine - SPINE_SAFE), bottom)
        return listOf(back, spineArea, front)
    }

    /** Where the barcode goes: the back cover's lower corner by the spine, in canvas pixels. */
    fun barcode(): FloatArray? {
        if (!wrap) return null
        val right = bleed + trimWidth - PRINT_SAFE
        val bottom = bleed + trimHeight - PRINT_SAFE
        return floatArrayOf(px(right - BARCODE_WIDTH), px(bottom - BARCODE_HEIGHT), px(right), px(bottom))
    }

    /** The front panel, trimmed, in canvas pixels: what an online shop shows as the cover. */
    fun front(): IntArray {
        val left = px(bleed + if (wrap) trimWidth + spine else 0f)
        return intArrayOf(left.roundToInt(), px(bleed).roundToInt(), (left + px(trimWidth)).roundToInt(), px(bleed + trimHeight).roundToInt())
    }

    fun describe(): String {
        fun inches(value: Float) = "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
        val size = "${inches(trimWidth)} × ${inches(trimHeight)} in"
        return if (wrap) tr("%1 book, %2 in spine", size, inches(spine)) else tr("%1 front cover", size)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("trimWidth", trimWidth.toDouble())
        .put("trimHeight", trimHeight.toDouble())
        .put("spine", spine.toDouble())
        .put("wrap", wrap)
        .put("bleed", bleed.toDouble())
        .put("dpi", dpi)

    companion object {
        fun fromJson(item: JSONObject?): PrintSetup? {
            if (item == null || !item.has("trimWidth")) return null
            return PrintSetup(
                item.getDouble("trimWidth").toFloat(),
                item.getDouble("trimHeight").toFloat(),
                item.optDouble("spine", 0.0).toFloat(),
                item.optBoolean("wrap"),
                item.optDouble("bleed", PRINT_BLEED.toDouble()).toFloat(),
                item.optInt("dpi", PRINT_DPI),
            )
        }
    }
}

/** A box around something on the canvas, in canvas pixels, with a name to point to it by. */
class PlacedBox(val name: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

private const val BLANK_ALPHA = 12
private const val PAPER_WHITE = 245
private const val BLEED_SAMPLES = 60
private const val MISSING_BLEED_SHARE = 0.15f
private const val DULL_SHARE = 0.2f
private const val EDGE_SLACK = 2f

private fun blank(color: Int): Boolean {
    if ((color ushr 24) < BLANK_ALPHA) return true
    return (color shr 16 and 255) > PAPER_WHITE && (color shr 8 and 255) > PAPER_WHITE && (color and 255) > PAPER_WHITE
}

/**
 * Checks a cover before it goes to print: text that could be trimmed or covers the barcode, art
 * that stops at the trim instead of running into the bleed, and colors that print duller.
 * [pixels] is a flattened copy of the canvas, [width] by [height], as ARGB.
 */
fun preflight(setup: PrintSetup, pixels: IntArray, width: Int, height: Int, texts: List<PlacedBox>): List<String> {
    val issues = ArrayList<String>()
    val safe = setup.safeAreas()
    texts.forEach { box ->
        val inside = safe.any { area ->
            box.left >= area[0] - EDGE_SLACK && box.top >= area[1] - EDGE_SLACK &&
                box.right <= area[2] + EDGE_SLACK && box.bottom <= area[3] + EDGE_SLACK
        }
        if (!inside) issues += tr("“%1” runs outside the safe area, so the cut could clip it.", box.name)
        setup.barcode()?.let { code ->
            if (box.left < code[2] && box.right > code[0] && box.top < code[3] && box.bottom > code[1]) {
                issues += tr("“%1” overlaps the barcode area.", box.name)
            }
        }
    }

    // Compares each edge's bleed with the art just inside the trim.
    val scaleX = width / setup.pixelWidth.toFloat()
    val scaleY = height / setup.pixelHeight.toFloat()
    val bleedX = setup.px(setup.bleed) * scaleX
    val bleedY = setup.px(setup.bleed) * scaleY
    fun at(x: Float, y: Float) = pixels[y.toInt().coerceIn(0, height - 1) * width + x.toInt().coerceIn(0, width - 1)]
    var checked = 0
    var missing = 0
    for (index in 0 until BLEED_SAMPLES) {
        val t = (index + 0.5f) / BLEED_SAMPLES
        val x = bleedX + t * (width - bleedX * 2)
        val y = bleedY + t * (height - bleedY * 2)
        listOf(
            at(x, bleedY / 3f) to at(x, bleedY * 2f),
            at(x, height - bleedY / 3f) to at(x, height - bleedY * 2f),
            at(bleedX / 3f, y) to at(bleedX * 2f, y),
            at(width - bleedX / 3f, y) to at(width - bleedX * 2f, y),
        ).forEach { (outside, inside) ->
            if (blank(inside)) return@forEach
            checked++
            if (blank(outside)) missing++
        }
    }
    if (checked > 0 && missing > checked * MISSING_BLEED_SHARE) {
        issues += tr("Your art stops at the trim line in places. Paint it out to the canvas edge, so no white sliver shows after the cut.")
    }

    val trim = setup.trim()
    var colored = 0
    var dull = 0
    for (y in (trim[1] * scaleY).toInt() until (trim[3] * scaleY).toInt() step 2) {
        for (x in (trim[0] * scaleX).toInt() until (trim[2] * scaleX).toInt() step 2) {
            val color = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
            if (blank(color)) continue
            colored++
            if (printsDuller(color)) dull++
        }
    }
    if (colored > 0 && dull > colored * DULL_SHARE) {
        issues += tr("About %1 of the cover uses colors that print duller than on screen. Check them with the Print view.", "${dull * 100 / colored}%")
    }
    return issues
}

private const val SHELF_WIDTH = 60
private const val FLAT_COVER = 0.3f

/**
 * Looks at the front cover the way a store list shows it, about [SHELF_WIDTH] pixels wide, and
 * notes when its lights and darks sit too close together to stand out there.
 */
fun coverRead(setup: PrintSetup, pixels: IntArray, width: Int, height: Int): Tip? {
    val front = setup.front()
    val scaleX = width / setup.pixelWidth.toFloat()
    val scaleY = height / setup.pixelHeight.toFloat()
    val left = front[0] * scaleX
    val top = front[1] * scaleY
    val frontWidth = (front[2] - front[0]) * scaleX
    val frontHeight = (front[3] - front[1]) * scaleY
    if (frontWidth < 4f || frontHeight < 4f) return null
    val shelfHeight = (SHELF_WIDTH * frontHeight / frontWidth).toInt().coerceAtLeast(1)
    val values = FloatArray(SHELF_WIDTH * shelfHeight)
    var painted = 0
    for (row in 0 until shelfHeight) {
        for (column in 0 until SHELF_WIDTH) {
            val x = (left + (column + 0.5f) * frontWidth / SHELF_WIDTH).toInt().coerceIn(0, width - 1)
            val y = (top + (row + 0.5f) * frontHeight / shelfHeight).toInt().coerceIn(0, height - 1)
            val color = pixels[y * width + x]
            val red = color shr 16 and 255
            val green = color shr 8 and 255
            val blue = color and 255
            if (red < 245 || green < 245 || blue < 245) painted++
            values[row * SHELF_WIDTH + column] = (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f
        }
    }
    // A cover that's barely started has nothing to read yet.
    if (painted < values.size / 4) return null
    values.sort()
    val spread = values[values.size * 9 / 10] - values[values.size / 10]
    if (spread >= FLAT_COVER) return null
    val mark = NoteMark(
        tr("Your front cover"), MarkShape.BOX,
        front[0] / setup.pixelWidth.toFloat(), front[1] / setup.pixelHeight.toFloat(),
        front[2] / setup.pixelWidth.toFloat(), front[3] / setup.pixelHeight.toFloat(),
    )
    return Tip(
        "Cover",
        tr("Shrunk to the size a store shows it, your front cover reads flat. Push the title's value against what's behind it, much lighter or much darker."),
        tr("At %1 pixels wide, the lightest and darkest tenths of the cover are only %2 apart. Covers that stand out in a list are usually over %3 apart.", SHELF_WIDTH, "${(spread * 100).toInt()}%", "${(FLAT_COVER * 100).toInt()}%"),
        listOf(mark),
        NoteAction.SEE_VALUES,
    )
}
