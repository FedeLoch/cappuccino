package art.cappuccino.paint

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val BLACK_INK = 0.13f
private const val DULLER_BY = 0.14f

/** The typical color of each mix of cyan, magenta, and yellow ink on coated paper, without a printer's profile. */
private val INKS = arrayOf(
    floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0.68f, 0.94f), floatArrayOf(0.93f, 0f, 0.55f), floatArrayOf(0.18f, 0.19f, 0.57f),
    floatArrayOf(1f, 0.95f, 0f), floatArrayOf(0f, 0.65f, 0.32f), floatArrayOf(0.93f, 0.11f, 0.14f), floatArrayOf(0.23f, 0.21f, 0.21f),
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Cyan, magenta, yellow, and black, each from 0 to 1. */
fun toCmyk(color: Int) = rgbInks((color shr 16) and 255, (color shr 8) and 255, color and 255)

fun rgbInks(redValue: Int, greenValue: Int, blueValue: Int): FloatArray {
    val red = redValue / 255f
    val green = greenValue / 255f
    val blue = blueValue / 255f
    val black = 1f - max(red, max(green, blue))
    if (black >= 1f) return floatArrayOf(0f, 0f, 0f, 1f)
    val left = 1f - black
    return floatArrayOf((1f - red - black) / left, (1f - green - black) / left, (1f - blue - black) / left, black)
}

/** How these amounts of ink look on paper. The print preview's shader does the same mix. */
fun inkColor(cmyk: FloatArray): Int {
    val (cyan, magenta, yellow, black) = cmyk
    val channel = { index: Int ->
        val noYellow = lerp(lerp(INKS[0][index], INKS[1][index], cyan), lerp(INKS[2][index], INKS[3][index], cyan), magenta)
        val withYellow = lerp(lerp(INKS[4][index], INKS[5][index], cyan), lerp(INKS[6][index], INKS[7][index], cyan), magenta)
        (lerp(noYellow, withYellow, yellow) * lerp(1f, BLACK_INK, black) * 255f).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
}

fun printedColor(color: Int) = inkColor(toCmyk(color))

/** True when a color will look clearly different once printed. */
fun printsDuller(color: Int): Boolean {
    val printed = printedColor(color)
    val difference = maxOf(
        abs((color shr 16 and 255) - (printed shr 16 and 255)),
        abs((color shr 8 and 255) - (printed shr 8 and 255)),
        abs((color and 255) - (printed and 255)),
    ) / 255f
    return difference > DULLER_BY
}
