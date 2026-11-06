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
