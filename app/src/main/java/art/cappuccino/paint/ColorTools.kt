package art.cappuccino.paint

import android.graphics.Color

data class Palette(val id: String, val name: String, val colors: List<Int>)

data class Harmony(val name: String, val colors: List<Int>)

val STARTER_PALETTES_NAMES = listOf("Warm dusk", "Cool night", "Earth tones", "Skin range")

val HARMONY_NAMES = listOf("Complementary", "Analogous", "Split complementary", "Triadic")

val STARTER_PALETTES = listOf(
    Palette("warm", "Warm dusk", listOf(0xFF2B1B2E, 0xFF6D2E46, 0xFFC8553D, 0xFFF28F3B, 0xFFFFD5C2).map { it.toInt() }),
    Palette("cool", "Cool night", listOf(0xFF0B132B, 0xFF1C2541, 0xFF3A506B, 0xFF5BC0BE, 0xFFD8E2DC).map { it.toInt() }),
    Palette("earth", "Earth tones", listOf(0xFF3A2E27, 0xFF6B4F3A, 0xFFA47148, 0xFFC9A66B, 0xFFEFE3C8).map { it.toInt() }),
    Palette("skin", "Skin range", listOf(0xFF4A2C22, 0xFF8D5A3B, 0xFFC68863, 0xFFE8B896, 0xFFF9DCC4).map { it.toInt() }),
)

private fun rotate(hsv: FloatArray, degrees: Float): Int =
    Color.HSVToColor(floatArrayOf((hsv[0] + degrees + 360f) % 360f, hsv[1], hsv[2]))

fun harmoniesFor(color: Int): List<Harmony> {
    val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
    val base = color or 0xFF000000.toInt()
    return listOf(
        Harmony("Complementary", listOf(base, rotate(hsv, 180f))),
        Harmony("Analogous", listOf(rotate(hsv, -30f), base, rotate(hsv, 30f))),
        Harmony("Split complementary", listOf(base, rotate(hsv, 150f), rotate(hsv, 210f))),
        Harmony("Triadic", listOf(base, rotate(hsv, 120f), rotate(hsv, 240f))),
    )
}

/** Steps from dark to light at the color's hue, for planning values. */
fun valueScale(color: Int, steps: Int = 7): List<Int> {
    val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
    return (0 until steps).map { step ->
        val t = (step + 1f) / (steps + 1f)
        Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * (1f - t * 0.6f), t))
    }
}

/** Groups pixels into the main colors of an image, most common first. Paper white is skipped. */
fun extractPalette(pixels: IntArray, count: Int = 6): List<Int> {
    val samples = pixels.asSequence()
        .filterIndexed { index, _ -> index % 3 == 0 }
        .filter { Color.red(it) < 245 || Color.green(it) < 245 || Color.blue(it) < 245 }
        .toList()
    if (samples.isEmpty()) return emptyList()
    val spaced = samples.sortedBy { Color.luminance(it) }
    val centers = Array(count) { index ->
        val color = spaced[index * (spaced.size - 1) / maxOf(1, count - 1)]
        floatArrayOf(Color.red(color).toFloat(), Color.green(color).toFloat(), Color.blue(color).toFloat())
    }
    val sizes = IntArray(count)
    repeat(8) {
        val totals = Array(count) { FloatArray(3) }
        sizes.fill(0)
        for (sample in samples) {
            val red = Color.red(sample).toFloat()
            val green = Color.green(sample).toFloat()
            val blue = Color.blue(sample).toFloat()
            var best = 0
            var bestDistance = Float.MAX_VALUE
            for (index in 0 until count) {
                val center = centers[index]
                val dr = red - center[0]
                val dg = green - center[1]
                val db = blue - center[2]
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            sizes[best]++
            totals[best][0] += red
            totals[best][1] += green
            totals[best][2] += blue
        }
        for (index in 0 until count) {
            if (sizes[index] == 0) continue
            for (channel in 0..2) centers[index][channel] = totals[index][channel] / sizes[index]
        }
    }
    return (0 until count).filter { sizes[it] > 0 }.sortedByDescending { sizes[it] }.map {
        Color.rgb(centers[it][0].toInt(), centers[it][1].toInt(), centers[it][2].toInt())
    }.distinct()
}
