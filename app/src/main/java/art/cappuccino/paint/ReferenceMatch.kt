package art.cappuccino.paint

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val HUE_BINS = 12
private const val NEUTRAL_BIN = HUE_BINS
private const val VALUE_BINS = 5
private const val GRID_COLUMNS = 6
private const val GRID_ROWS = 8
private const val MAX_SUGGESTIONS = 4
private const val CLOSE = 0.72f
private const val CLOSER = 0.5f
private const val FULL_COVERAGE = 0.5f
private const val EARLY_COVERAGE = 0.2f

private val HUE_NAMES = listOf(
    "red", "orange", "yellow", "lime", "green", "teal",
    "cyan", "blue", "indigo", "violet", "magenta", "pink",
)

/** A small summary of an image's art direction, compared without any network or AI model. */
class VisualProfile(
    val colors: FloatArray,
    val binColors: IntArray,
    val values: FloatArray,
    val layout: FloatArray,
    val brightness: Float,
    val saturation: Float,
    val warmth: Float,
    val contrast: Float,
    val coverage: Float,
)

data class MatchArea(val name: String, val score: Float)

class ReferenceMatch(
    val overall: Float,
    val areas: List<MatchArea>,
    val summary: String,
    val advice: String,
    val colors: List<Int>,
    val why: String,
    val closest: Int,
)

fun profileImage(pixels: IntArray, width: Int, height: Int): VisualProfile {
    val colors = FloatArray(HUE_BINS + 1)
    val reds = FloatArray(HUE_BINS + 1)
    val greens = FloatArray(HUE_BINS + 1)
    val blues = FloatArray(HUE_BINS + 1)
    val values = FloatArray(VALUE_BINS)
    val layout = FloatArray(GRID_COLUMNS * GRID_ROWS)
    val cellCounts = IntArray(layout.size)
    val luminances = FloatArray(pixels.size)
    var saturationTotal = 0f
    var warmTotal = 0f
    var brightnessTotal = 0f
    var marked = 0

    for (index in pixels.indices) {
        val color = pixels[index]
        val red = (color shr 16) and 255
        val green = (color shr 8) and 255
        val blue = color and 255
        val high = max(max(red, green), blue)
        val low = min(min(red, green), blue)
        if (low < 235) marked++
        val saturation = if (high == 0) 0f else (high - low) / high.toFloat()
        val luminance = (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f
        val bin = if (saturation < 0.18f || high < 30) NEUTRAL_BIN else hueBin(red, green, blue, high, low)
        colors[bin] += 1f
        reds[bin] += red.toFloat()
        greens[bin] += green.toFloat()
        blues[bin] += blue.toFloat()
        values[(luminance * VALUE_BINS).toInt().coerceAtMost(VALUE_BINS - 1)] += 1f
        val cell = (index % width) * GRID_COLUMNS / width + (index / width) * GRID_ROWS / height * GRID_COLUMNS
        layout[cell] += luminance
        cellCounts[cell]++
        saturationTotal += saturation
        warmTotal += (red - blue) / 255f
        brightnessTotal += luminance
        luminances[index] = luminance
    }

    val binColors = IntArray(colors.size) { bin ->
        val count = max(colors[bin], 1f)
        (0xFF shl 24) or ((reds[bin] / count).toInt() shl 16) or
            ((greens[bin] / count).toInt() shl 8) or (blues[bin] / count).toInt()
    }
    val total = max(pixels.size, 1).toFloat()
    for (bin in colors.indices) colors[bin] /= total
    for (bin in values.indices) values[bin] /= total
    for (cell in layout.indices) layout[cell] /= max(cellCounts[cell], 1)
    luminances.sort()
    val contrast = luminances[luminances.size * 9 / 10] - luminances[luminances.size / 10]
    return VisualProfile(
        colors, binColors, values, layout,
        brightnessTotal / total, saturationTotal / total, warmTotal / total, contrast, marked / total,
    )
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

private fun overlap(first: FloatArray, second: FloatArray): Float =
    first.indices.sumOf { min(first[it], second[it]).toDouble() }.toFloat()

private fun layoutScore(art: VisualProfile, reference: VisualProfile): Float {
    val artMean = art.layout.average().toFloat()
    val referenceMean = reference.layout.average().toFloat()
    val difference = art.layout.indices.sumOf {
        abs((art.layout[it] - artMean) - (reference.layout[it] - referenceMean)).toDouble()
    } / art.layout.size
    return (1f - difference.toFloat() * 3f).coerceIn(0f, 1f)
}

private fun moodScore(art: VisualProfile, reference: VisualProfile): Float {
    val difference = abs(art.saturation - reference.saturation) +
        abs(art.warmth - reference.warmth) + abs(art.contrast - reference.contrast)
    return (1f - difference).coerceIn(0f, 1f)
}

private fun mean(items: List<VisualProfile>, read: (VisualProfile) -> Float) =
    items.map(read).average().toFloat()

private fun missingBins(art: VisualProfile, references: List<VisualProfile>): List<Int> {
    val wanted = FloatArray(HUE_BINS) { bin -> references.map { it.colors[bin] }.average().toFloat() }
    return (0 until HUE_BINS)
        .filter { wanted[it] > 0.03f && wanted[it] > art.colors[it] * 1.5f }
        .sortedByDescending { wanted[it] - art.colors[it] }
}

private fun colorAdvice(art: VisualProfile, references: List<VisualProfile>): String {
    val names = missingBins(art, references).take(2).map { tr(HUE_NAMES[it]) }
    return when {
        names.isEmpty() -> tr("Your colors are on track. Check that the main color takes the most area.")
        names.size == 1 -> tr("Your references lean on %1. Try bringing more of it in.", names[0])
        else -> tr("Your references lean on %1 and %2. Try bringing more of them in.", names[0], names[1])
    }
}

private fun valueAdvice(art: VisualProfile, references: List<VisualProfile>): String {
    val brightness = mean(references) { it.brightness }
    val contrast = mean(references) { it.contrast }
    return when {
        art.brightness > brightness + 0.1f -> tr("Your references are darker overall. Block in deeper shadow shapes.")
        art.brightness < brightness - 0.1f -> tr("Your references are lighter overall. Open up more light areas.")
        art.contrast < contrast - 0.1f -> tr("Your references have stronger contrast. Push your darks and lights further apart.")
        else -> tr("Your values are on track. Keep the strongest contrast at your focus.")
    }
}

private fun layoutAdvice(art: VisualProfile, references: List<VisualProfile>): String {
    val half = GRID_ROWS / 2 * GRID_COLUMNS
    fun topMinusBottom(profile: VisualProfile) =
        profile.layout.take(half).average().toFloat() - profile.layout.drop(half).average().toFloat()
    val target = references.map(::topMinusBottom).average().toFloat()
    val current = topMinusBottom(art)
    return when {
        target > current + 0.08f -> tr("Your references keep more dark weight low. Try anchoring the bottom of the image.")
        target < current - 0.08f -> tr("Your references keep more dark weight high. Try a heavier top.")
        else -> tr("Compare your big light and dark shapes with the reference layout.")
    }
}

private fun moodAdvice(art: VisualProfile, references: List<VisualProfile>): String {
    val saturation = mean(references) { it.saturation }
    val warmth = mean(references) { it.warmth }
    return when {
        art.saturation < saturation - 0.1f -> tr("Your references feel more colorful. Try richer color in your focus areas.")
        art.saturation > saturation + 0.1f -> tr("Your references feel more muted. Try softening your strongest colors.")
        art.warmth < warmth - 0.08f -> tr("Your references feel warmer. Lean into warm lights.")
        art.warmth > warmth + 0.08f -> tr("Your references feel cooler. Try cooler shadows.")
        else -> tr("The mood is on track.")
    }
}

private fun percent(value: Float) = "${(value * 100).toInt()}%"

private fun matchWhy(art: VisualProfile, references: List<VisualProfile>): String {
    val notes = arrayListOf(
        tr("Canvas filled: %1.", percent(art.coverage)),
        tr("Brightness: yours %1, references %2.", percent(art.brightness), percent(mean(references) { it.brightness })),
        tr("Contrast: yours %1, references %2.", percent(art.contrast), percent(mean(references) { it.contrast })),
        tr("Color strength: yours %1, references %2.", percent(art.saturation), percent(mean(references) { it.saturation })),
    )
    val missing = missingBins(art, references).take(3).map { tr(HUE_NAMES[it]) }
    if (missing.isNotEmpty()) notes += tr("Stronger in references: %1.", missing.joinToString(", "))
    return notes.joinToString(" ")
}

/** Compares artwork with each reference, keeping the closest result for every area. */
fun compareWithReferences(art: VisualProfile, references: List<VisualProfile>): ReferenceMatch? {
    if (references.isEmpty()) return null
    // Empty paper can look like a cream background, so scores grow only as the page fills in.
    val developed = (art.coverage / FULL_COVERAGE).coerceIn(0f, 1f)
    val color = references.maxOf { overlap(art.colors, it.colors) } * developed
    val value = references.maxOf { overlap(art.values, it.values) } * developed
    val layout = references.maxOf { layoutScore(art, it) } * developed
    val mood = references.maxOf { moodScore(art, it) } * developed
    val areas = listOf(
        MatchArea("Palette", color),
        MatchArea("Values", value),
        MatchArea("Composition", layout),
        MatchArea("Mood", mood),
    )
    val overall = color * 0.35f + value * 0.3f + layout * 0.2f + mood * 0.15f
    val summary = when {
        art.coverage < EARLY_COVERAGE -> tr("It's still early to compare with your references.")
        overall >= CLOSE -> tr("You're close to your references.")
        overall >= CLOSER -> tr("You're getting closer to your references.")
        else -> tr("You're still far from your references.")
    }
    val advice = when (areas.minBy { it.score }.name) {
        "Palette" -> colorAdvice(art, references)
        "Values" -> valueAdvice(art, references)
        "Composition" -> layoutAdvice(art, references)
        else -> moodAdvice(art, references)
    }
    val suggestions = missingBins(art, references).take(MAX_SUGGESTIONS).map { bin ->
        references.maxBy { it.colors[bin] }.binColors[bin]
    }
    val closest = references.indices.maxBy { index ->
        val reference = references[index]
        overlap(art.colors, reference.colors) * 0.35f + overlap(art.values, reference.values) * 0.3f +
            layoutScore(art, reference) * 0.2f + moodScore(art, reference) * 0.15f
    }
    return ReferenceMatch(overall, areas, summary, advice, suggestions, matchWhy(art, references), closest)
}
