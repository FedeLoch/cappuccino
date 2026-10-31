package art.cappuccino.paint

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

data class Tip(val topic: String, val text: String, val why: String = "")

class AnalysisReport(
    val tips: List<Tip>,
    val width: Int,
    val height: Int,
    val values: IntArray,
    val silhouette: IntArray,
)

private const val PAPER_DISTANCE = 18
private const val HUE_BINS = 12
private val HUE_NAMES = listOf(
    "red", "orange", "yellow", "lime", "green", "teal",
    "cyan", "blue", "indigo", "violet", "magenta", "pink",
)

private fun percent(value: Float) = "${(value * 100).toInt()}%"

/** Reads a small flattened copy of the artwork on white paper, without any network or AI model. */
fun analyzeArtwork(pixels: IntArray, width: Int, height: Int, lightDegrees: Int? = null): AnalysisReport {
    val values = IntArray(pixels.size)
    val silhouette = IntArray(pixels.size)
    val hues = FloatArray(HUE_BINS)
    val luminances = ArrayList<Float>()
    var markCount = 0
    var coloredWeight = 0f
    var saturationTotal = 0f
    var weightTotal = 0f
    var weightX = 0f
    var weightY = 0f
    var lightTotal = 0f
    var lightX = 0f
    var lightY = 0f
    var left = width
    var top = height
    var right = -1
    var bottom = -1

    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            val red = (color shr 16) and 255
            val green = (color shr 8) and 255
            val blue = color and 255
            val luminance = (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f
            val level = (luminance * 255f).toInt()
            values[index] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            val marked = max(max(255 - red, 255 - green), 255 - blue) > PAPER_DISTANCE
            silhouette[index] = if (marked) 0xFF2C2C2A.toInt() else 0xFFFFFFFF.toInt()
            if (!marked) continue

            markCount++
            luminances.add(luminance)
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
            val weight = 1f - luminance + 0.1f
            weightTotal += weight
            weightX += x * weight
            weightY += y * weight
            lightTotal += luminance
            lightX += x * luminance
            lightY += y * luminance

            val high = max(max(red, green), blue)
            val low = min(min(red, green), blue)
            val saturation = if (high == 0) 0f else (high - low) / high.toFloat()
            if (saturation > 0.2f && high > 40) {
                hues[hueBin(red, green, blue, high, low)] += 1f
                coloredWeight += 1f
                saturationTotal += saturation
            }
        }
    }

    if (markCount < pixels.size * 0.015f) {
        return AnalysisReport(emptyList(), width, height, values, silhouette)
    }
    val coverage = markCount / pixels.size.toFloat()
    val lightDirection = Math.toDegrees(
        atan2(
            (lightY / max(lightTotal, 1f) - weightY / weightTotal).toDouble(),
            (lightX / max(lightTotal, 1f) - weightX / weightTotal).toDouble(),
        ),
    ).toFloat()
    val tips = listOfNotNull(
        stageTip(coverage, coloredWeight, markCount)?.withWhy(
            "Marks cover ${percent(coverage)} of the canvas, and ${percent(coloredWeight / markCount)} of them have color.",
        ),
        lightDegrees?.let { lightTip(it, lightDirection, lightTotal) }?.withWhy(
            "Your lighter marks gather ${directionName(lightDirection)} of the darker ones.",
        ),
        paletteTip(hues, coloredWeight, saturationTotal, markCount).withWhy(paletteWhy(hues, coloredWeight, saturationTotal)),
        valueTip(luminances).withWhy(valueWhy(luminances)),
        compositionTip(weightX / weightTotal / width, weightY / weightTotal / height).withWhy(
            "Your visual weight sits ${percent(weightX / weightTotal / width)} across and " +
                "${percent(weightY / weightTotal / height)} down the canvas.",
        ),
        silhouetteTip(markCount, left, top, right, bottom).withWhy(
            "Your marks fill ${percent(markCount / ((right - left + 1f) * (bottom - top + 1f)))} of the area around them.",
        ),
    )
    return AnalysisReport(tips, width, height, values, silhouette)
}

private fun Tip.withWhy(reason: String) = copy(why = reason)

private fun directionName(degrees: Float): String {
    val normalized = (degrees + 360f) % 360f
    return when {
        normalized < 45f || normalized >= 315f -> "to the right"
        normalized < 135f -> "below"
        normalized < 225f -> "to the left"
        else -> "above"
    }
}

private fun paletteWhy(hues: FloatArray, colored: Float, saturation: Float): String {
    if (colored <= 0f) return "None of your marks are strongly colored."
    val main = hues.indices.sortedByDescending { hues[it] }.take(3).filter { hues[it] > 0f }
    val list = main.joinToString(", ") { "${HUE_NAMES[it]} ${percent(hues[it] / colored)}" }
    return "Your main hues are $list. Average strength is ${percent(saturation / colored)}."
}

private fun valueWhy(luminances: List<Float>): String {
    val sorted = luminances.sorted()
    val middle = luminances.count { it in 0.35f..0.65f } / luminances.size.toFloat()
    return "Your marks run from ${percent(sorted[sorted.size / 10])} to ${percent(sorted[sorted.size * 9 / 10])} " +
        "brightness, and ${percent(middle)} are midtones."
}

private fun stageTip(coverage: Float, colored: Float, marks: Int): Tip? = when {
    coverage < 0.12f && colored < marks * 0.1f ->
        Tip("Next step", "This looks like an early sketch. Block in the biggest shapes before adding detail.")
    coverage < 0.4f && colored < marks * 0.1f ->
        Tip("Next step", "The drawing has a clear structure. A simple light and dark pass could give it form next.")
    else -> null
}

/** Compares where the lighter marks gather with the light direction the artist chose. */
private fun lightTip(chosen: Int, measured: Float, light: Float): Tip? {
    if (light <= 0f) return null
    val gap = abs(((measured - chosen + 540f) % 360f) - 180f)
    val text = if (gap < 70f) {
        "Your lighter areas follow the chosen light direction."
    } else {
        "Your lighter areas gather away from the chosen light. Check which side your shadows fall on."
    }
    return Tip("Light", text)
}

private fun hueBin(red: Int, green: Int, blue: Int, high: Int, low: Int): Int {
    val delta = (high - low).toFloat()
    val hue = when (high) {
        red -> ((green - blue) / delta + 6f) % 6f
        green -> (blue - red) / delta + 2f
        else -> (red - green) / delta + 4f
    } * 60f
    return (hue / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
}

private fun paletteTip(hues: FloatArray, colored: Float, saturation: Float, marks: Int): Tip {
    val topic = "Palette"
    if (colored < marks * 0.1f) {
        return Tip(topic, "This is almost neutral. One small accent color could guide the eye.")
    }
    val main = hues.indices.filter { hues[it] >= colored * 0.08f }.sortedByDescending { hues[it] }
    if (saturation / colored > 0.75f && main.size > 1) {
        return Tip(topic, "Most colors are at full strength. Muting the secondary ones lets the focus stand out.")
    }
    if (main.size >= 5) {
        return Tip(topic, "There are many competing hues. Try keeping one main hue and two supporting ones.")
    }
    if (main.size <= 1) {
        return Tip(topic, "This palette uses one hue family, which feels unified. Vary lightness to add depth.")
    }
    val gap = abs(main[0] - main[1]).let { min(it, HUE_BINS - it) }
    if (gap >= 5) {
        return if (hues[main[1]] > hues[main[0]] * 0.7f) {
            Tip(topic, "These opposite hues are almost equal in area. Let one dominate so they don't fight.")
        } else {
            Tip(topic, "This is a complementary palette with a clear main color. It should read with energy.")
        }
    }
    return Tip(topic, "These neighboring hues feel harmonious. A small cooler or warmer note could add interest.")
}

private fun valueTip(luminances: List<Float>): Tip {
    val sorted = luminances.sorted()
    val low = sorted[sorted.size / 10]
    val high = sorted[sorted.size * 9 / 10]
    val middle = luminances.count { it in 0.35f..0.65f } / luminances.size.toFloat()
    val text = when {
        high - low < 0.2f -> "The marks sit close in value. Stronger darks or lights would separate the shapes."
        middle > 0.7f -> "Most of this is in the midtones. Try grouping it into clear lights and darks."
        else -> "The values have a clear range. Check that the strongest contrast is at your focal point."
    }
    return Tip("Values", text)
}

private fun compositionTip(x: Float, y: Float): Tip {
    val text = when {
        abs(x - 0.5f) < 0.06f && abs(y - 0.5f) < 0.06f ->
            "The visual weight sits at the center, which feels stable. Shift it toward a third for more movement."
        x < 0.22f -> "The weight leans hard to the left. A small accent on the right could balance it."
        x > 0.78f -> "The weight leans hard to the right. A small accent on the left could balance it."
        y < 0.22f -> "The weight sits high. Something in the lower area could ground the image."
        y > 0.78f -> "The weight sits low. Some detail in the upper area could open up the image."
        else -> "The visual weight is off-center, which helps movement. Check that the eye has a clear path."
    }
    return Tip("Composition", text)
}

private fun silhouetteTip(marks: Int, left: Int, top: Int, right: Int, bottom: Int): Tip {
    val area = (right - left + 1) * (bottom - top + 1)
    val fill = marks / area.toFloat()
    val text = if (fill > 0.8f) {
        "The overall shape is quite solid. Breaking the outline can make it easier to recognize."
    } else {
        "Look at the silhouette preview. If the subject still reads in flat black, the shape is working."
    }
    return Tip("Silhouette", text)
}
