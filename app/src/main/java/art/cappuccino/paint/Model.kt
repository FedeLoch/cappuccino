package art.cappuccino.paint

import kotlin.math.pow

const val DEFAULT_CANVAS_WIDTH = 4096
const val DEFAULT_CANVAS_HEIGHT = 2732
const val MAX_CANVAS_SIDE = 8192

private const val MIN_LAYERS = 8
private const val MAX_LAYER_LIMIT = 64

/** Layers use a share of device memory, so smaller canvases can hold more of them. */
fun layerLimit(width: Int, height: Int, deviceMemoryBytes: Long): Int {
    val perLayer = width.toLong() * height * 4
    return (deviceMemoryBytes / 5 / perLayer).toInt().coerceIn(MIN_LAYERS, MAX_LAYER_LIMIT)
}

enum class BrushMode { PAINT, FILL, SMUDGE, GRADIENT, LASSO }

class BrushKind(
    val id: String,
    val label: String,
    val minScale: Float,
    val spacing: Float,
    val flow: Float,
    val pressureFlow: Float,
    val hardness: Float,
    val grain: Float,
    val defaultSize: Float,
    val isEraser: Boolean = false,
    val mode: BrushMode = BrushMode.PAINT,
    val tip: ByteArray? = null,
    val custom: Boolean = false,
    val scatter: Float = 0f,
    val sizeJitter: Float = 0f,
    val angleJitter: Float = 0f,
    val shadeJitter: Float = 0f,
    val hueJitter: Float = 0f,
    val strokeJitter: Float = 0f,
) {
    val isTool get() = !isEraser && mode != BrushMode.PAINT

    /** Stamp alpha that builds up to about [opacity] across one stamp width. */
    fun stampFlow(opacity: Float): Float =
        if (opacity >= 1f) flow else flow * (1f - (1f - opacity).pow(spacing))

    /**
     * The color one stroke paints with. [strokeJitter] moves the hue, saturation, and value a little
     * for each stroke, so repeated marks such as leaves don't all match.
     */
    fun strokeColor(base: Int, random: java.util.Random): Int {
        if (strokeJitter <= 0f || isEraser) return base
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        fun spread() = random.nextFloat() * 2f - 1f
        hsv[0] = (hsv[0] + spread() * STROKE_HUE_DEGREES * strokeJitter + 360f) % 360f
        hsv[1] = (hsv[1] + spread() * STROKE_SPREAD * strokeJitter).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + spread() * STROKE_SPREAD * strokeJitter).coerceIn(0f, 1f)
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(base), hsv)
    }

    companion object {
        private const val STROKE_HUE_DEGREES = 24f
        private const val STROKE_SPREAD = 0.14f
        val INK = BrushKind("ink", "Ink", 0.12f, 0.08f, 1f, 0f, 1f, 0f, 24f)
        private val LINER = BrushKind("liner", "Liner", 0.85f, 0.06f, 1f, 0f, 1f, 0f, 12f)
        private val PENCIL = BrushKind("pencil", "Pencil", 0.5f, 0.1f, 0.35f, 0.8f, 0.7f, 0.8f, 16f)
        private val FINE_PENCIL = BrushKind("fine-pencil", "Fine pencil", 0.35f, 0.05f, 0.55f, 0.95f, 0.8f, 0.65f, 9f)
        private val SOFT_PENCIL = BrushKind("soft-pencil", "Soft pencil", 0.55f, 0.07f, 0.42f, 0.9f, 0.55f, 0.95f, 30f)
        private val CHARCOAL = BrushKind("charcoal", "Charcoal", 0.7f, 0.1f, 0.55f, 0.6f, 0.6f, 1f, 60f)
        private val CHALK = BrushKind("chalk", "Chalk", 0.8f, 0.12f, 0.6f, 0.3f, 0.8f, 0.9f, 80f)
        private val MARKER = BrushKind("marker", "Marker", 0.9f, 0.05f, 0.18f, 0.2f, 0.9f, 0f, 70f)
        private val SOFT = BrushKind("soft", "Soft", 0.6f, 0.06f, 0.08f, 0.7f, 0f, 0f, 160f)
        private val WASH = BrushKind("wash", "Wash", 0.7f, 0.08f, 0.05f, 0.5f, 0.2f, 0.35f, 220f)
        private const val TIP_SIZE = 128

        /** Scattered soft dots, repeatable because the seed is fixed. */
        private fun spongeTip(): ByteArray {
            val random = java.util.Random(11)
            val tip = ByteArray(TIP_SIZE * TIP_SIZE)
            repeat(420) {
                val x = random.nextInt(TIP_SIZE)
                val y = random.nextInt(TIP_SIZE)
                val radius = 1 + random.nextInt(4)
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val px = x + dx
                        val py = y + dy
                        if (px in 0 until TIP_SIZE && py in 0 until TIP_SIZE && dx * dx + dy * dy <= radius * radius) {
                            tip[py * TIP_SIZE + px] = -1
                        }
                    }
                }
            }
            return tip
        }

        /** Broken parallel bristle lines. */
        private fun dryBrushTip(): ByteArray {
            val random = java.util.Random(23)
            val tip = ByteArray(TIP_SIZE * TIP_SIZE)
            var row = 0
            while (row < TIP_SIZE) {
                val thickness = 1 + random.nextInt(3)
                val start = random.nextInt(24)
                val end = TIP_SIZE - random.nextInt(24)
                for (y in row until minOf(TIP_SIZE, row + thickness)) {
                    for (x in start until end) {
                        if (random.nextInt(9) != 0) tip[y * TIP_SIZE + x] = -1
                    }
                }
                row += thickness + 1 + random.nextInt(3)
            }
            return tip
        }

        private val SPONGE = BrushKind(
            "sponge", "Sponge", 0.8f, 0.45f, 0.55f, 0.5f, 0.5f, 0f, 120f, tip = spongeTip(),
        )
        private val DRY_BRUSH = BrushKind(
            "dry-brush", "Dry brush", 0.7f, 0.12f, 0.45f, 0.5f, 0.5f, 0.3f, 110f, tip = dryBrushTip(),
        )
        val TEXTURE_BRUSHES = listOf(SPONGE, DRY_BRUSH)

        private fun blobTip(paint: (Float, Float) -> Boolean) = ByteArray(TIP_SIZE * TIP_SIZE) { index ->
            val x = (index % TIP_SIZE) / (TIP_SIZE - 1f) * 2f - 1f
            val y = (index / TIP_SIZE) / (TIP_SIZE - 1f) * 2f - 1f
            if (paint(x, y)) -1 else 0
        }

        private val LEAVES = BrushKind(
            "leaves", "Leaves", 0.5f, 0.55f, 0.9f, 0.2f, 0.8f, 0f, 90f,
            tip = blobTip { x, y -> x * x * 7f + y * y < 0.95f },
            scatter = 0.7f, sizeJitter = 0.5f, angleJitter = 1f, shadeJitter = 0.25f, hueJitter = 0.3f, strokeJitter = 0.4f,
        )
        private val GRASS = BrushKind(
            "grass", "Grass", 0.6f, 0.2f, 0.9f, 0.2f, 0.8f, 0f, 120f,
            tip = blobTip { x, y -> kotlin.math.abs(x) < 0.09f * (1f - y) && y > -0.95f },
            scatter = 0.45f, sizeJitter = 0.5f, angleJitter = 0.09f, shadeJitter = 0.2f, hueJitter = 0.25f, strokeJitter = 0.3f,
        )
        private val ROCKS = BrushKind(
            "rocks", "Rocks", 0.6f, 0.6f, 1f, 0.1f, 0.9f, 0.25f, 110f,
            tip = blobTip { x, y -> kotlin.math.abs(x) + kotlin.math.abs(y) * 1.3f + kotlin.math.abs(x + y) * 0.35f < 1f },
            scatter = 0.6f, sizeJitter = 0.7f, angleJitter = 1f, shadeJitter = 0.3f, strokeJitter = 0.2f,
        )
        private val PARTICLES = BrushKind(
            "particles", "Particles", 0.4f, 0.5f, 1f, 0.4f, 0.5f, 0f, 90f,
            scatter = 1.1f, sizeJitter = 0.9f, shadeJitter = 0.15f,
        )
        val SCATTER_BRUSHES = listOf(LEAVES, GRASS, ROCKS, PARTICLES)

        val SMUDGE = BrushKind(
            "smudge", "Smudge", 0.6f, 0.2f, 0.65f, 0.4f, 0.5f, 0f, 90f, mode = BrushMode.SMUDGE,
        )
        val FILL = BrushKind("fill", "Fill", 1f, 1f, 1f, 0f, 1f, 0f, 40f, mode = BrushMode.FILL)
        val GRADIENT = BrushKind(
            "gradient", "Gradient", 1f, 1f, 1f, 0f, 1f, 0f, 40f, mode = BrushMode.GRADIENT,
        )
        val LASSO_FILL = BrushKind(
            "lasso-fill", "Lasso fill", 1f, 1f, 1f, 0f, 1f, 0f, 40f, mode = BrushMode.LASSO,
        )
        val ERASE_INK = BrushKind("erase-ink", "Ink", 0.12f, 0.08f, 1f, 0f, 1f, 0f, 40f, true)
        private val ERASERS = listOf(
            ERASE_INK,
            BrushKind("erase-liner", "Liner", 0.85f, 0.06f, 1f, 0f, 1f, 0f, 24f, true),
            BrushKind("erase-fine-pencil", "Fine pencil", 0.35f, 0.05f, 0.55f, 0.95f, 0.8f, 0.65f, 18f, true),
            BrushKind("erase-pencil", "Pencil", 0.5f, 0.1f, 0.35f, 0.8f, 0.7f, 0.8f, 30f, true),
            BrushKind("erase-soft-pencil", "Soft pencil", 0.55f, 0.07f, 0.42f, 0.9f, 0.55f, 0.95f, 50f, true),
            BrushKind("erase-charcoal", "Charcoal", 0.7f, 0.1f, 0.55f, 0.6f, 0.6f, 1f, 80f, true),
            BrushKind("erase-chalk", "Chalk", 0.8f, 0.12f, 0.6f, 0.3f, 0.8f, 0.9f, 80f, true),
            BrushKind("erase-marker", "Marker", 0.9f, 0.05f, 0.18f, 0.2f, 0.9f, 0f, 90f, true),
            BrushKind("erase-soft", "Soft", 0.6f, 0.06f, 0.15f, 0.5f, 0f, 0f, 160f, true),
            BrushKind("erase-wash", "Wash", 0.7f, 0.08f, 0.08f, 0.5f, 0.2f, 0.35f, 220f, true),
            BrushKind("erase-lasso", "Lasso", 1f, 1f, 1f, 0f, 1f, 0f, 40f, true, BrushMode.LASSO),
        )
        val PAINT_BRUSHES = listOf(INK, LINER, FINE_PENCIL, PENCIL, SOFT_PENCIL, CHARCOAL, CHALK, MARKER, SOFT, WASH)
        val BRUSH_FAMILIES = listOf(
            "Sketch" to PAINT_BRUSHES.take(7),
            "Paint" to PAINT_BRUSHES.drop(7) + TEXTURE_BRUSHES,
            "Scatter" to SCATTER_BRUSHES,
        )
        val entries = PAINT_BRUSHES + TEXTURE_BRUSHES + SCATTER_BRUSHES + listOf(SMUDGE, FILL, GRADIENT, LASSO_FILL) + ERASERS
    }
}

/** Blend modes in the engine's order. The last nine read the colors underneath. */
val BLEND_MODES = listOf(
    "Normal", "Multiply", "Screen", "Additive", "Darken", "Lighten", "Soft light", "Subtract", "Exclusion",
    "Overlay", "Hard light", "Color dodge", "Color burn", "Difference", "Hue", "Saturation", "Color", "Luminosity",
)

/** How the blend list groups them: darkening, lightening, contrast, difference, and color parts. */
val BLEND_GROUPS = listOf(
    listOf(0),
    listOf(1, 4, 12, 7),
    listOf(2, 5, 11, 3),
    listOf(9, 6, 10),
    listOf(13, 8),
    listOf(14, 15, 16, 17),
)

data class LayerInfo(
    val id: Int,
    var name: String,
    var opacity: Float = 1f,
    var visible: Boolean = true,
    var blend: Int = 0,
    var alphaLock: Boolean = false,
    var file: String? = null,
    var clip: Boolean = false,
    var groupId: Int = 0,
    var maskId: Int = 0,
    var maskEnabled: Boolean = true,
    var maskFile: String? = null,
    var text: String? = null,
    var textSize: Float = 0f,
    var textColor: Int = 0,
    var sticker: Boolean = false,
    var textStyle: TextStyle = TextStyle(),
)

data class LayerGroup(
    val id: Int,
    var name: String,
    var visible: Boolean = true,
    var opacity: Float = 1f,
    var collapsed: Boolean = false,
)

val VIEW_MODES = listOf("Color", "Grayscale", "Two values", "Three values")

val GUIDES = listOf("None", "Thirds", "Golden", "Grid", "Isometric", "Views", "Frames")

/**
 * A filter slider and how its value reads: "px" for pixels, "deg" for an angle in radians, "level"
 * for a 0 to 255 tone, "gamma" for a midtone power, "times" for a multiplier shown as a percentage,
 * "dir" for a direction named by where it comes from, and "%" for a share of the slider's range.
 */
class FilterSlider(val label: String, val low: Float, val high: Float, val start: Float, val unit: String = "%")

/** A filter's label, its engine number, one line on what it does, and its sliders in order. */
class FilterKind(val label: String, val id: Int, val about: String, val sliders: List<FilterSlider>, val usesColor: Boolean = false)

val FILTERS = listOf(
    FilterKind("Blur", 1, "Softens the whole layer.", listOf(FilterSlider("Radius", 0f, 120f, 12f, "px"))),
    FilterKind(
        "Motion blur", 2, "Smears the layer in one direction, as if it moved.",
        listOf(FilterSlider("Distance", 0f, 240f, 40f, "px"), FilterSlider("Angle", 0f, 3.1416f, 0f, "deg")),
    ),
    FilterKind("Sharpen", 3, "Crisps up soft edges.", listOf(FilterSlider("Amount", 0f, 3f, 1f, "times"))),
    FilterKind("Noise", 4, "Adds a fine grain.", listOf(FilterSlider("Amount", 0f, 0.5f, 0.1f))),
    FilterKind(
        "Gradient map", 5, "Recolors the layer: darks to black, midtones to your color, lights to white.",
        listOf(FilterSlider("Amount", 0f, 1f, 1f)), usesColor = true,
    ),
    FilterKind(
        "Levels", 6, "Sets the darkest and lightest points, and how bright the midtones are.",
        listOf(
            FilterSlider("Black point", 0f, 0.5f, 0f, "level"),
            FilterSlider("Midtones", 0.3f, 3f, 1f, "gamma"),
            FilterSlider("White point", 0.5f, 1f, 1f, "level"),
        ),
    ),
    FilterKind(
        "Depth fade", 7, "Washes the layer toward your color, so it sits farther back.",
        listOf(FilterSlider("Amount", 0f, 1f, 0.3f)), usesColor = true,
    ),
    FilterKind(
        "Light", LIGHT_FILTER, "Shades flat shapes as if they were rounded, on a layer of its own clipped to this one.",
        listOf(
            FilterSlider("Light from", 0f, 6.2832f, 2.36f, "dir"),
            FilterSlider("Light height", 0.1f, 1.45f, 0.6f, "deg"),
            FilterSlider("Roundness", 4f, 300f, 60f, "px"),
            FilterSlider("Strength", 0f, 1f, 0.8f),
        ),
    ),
)

/** The light filter, whose sliders the engine reads in its own order. */
const val LIGHT_FILTER = 8

/** Where light comes from, by eighths of a turn counterclockwise from the right. */
val LIGHT_DIRECTIONS = listOf("right", "upper right", "top", "upper left", "left", "lower left", "bottom", "lower right")

/** The engine's values for a filter: the light filter wants roundness, height, strength, then direction. */
fun engineFilterValues(kind: FilterKind, values: FloatArray): FloatArray =
    if (kind.id == LIGHT_FILTER) floatArrayOf(values[2], values[1], values[3], values[0]) else values.copyOf()

/** How a filter slider's value reads beside it. */
fun filterValueText(slider: FilterSlider, value: Float): String = when (slider.unit) {
    "px" -> "${Math.round(value)} px"
    "deg" -> "${Math.round(Math.toDegrees(value.toDouble()))}°"
    "level" -> "${Math.round(value * 255)}"
    "gamma" -> String.format(java.util.Locale.US, "%.2f", value)
    "times" -> "${Math.round(value * 100)}%"
    "dir" -> LIGHT_DIRECTIONS[Math.floorMod(Math.round(value / (Math.PI / 4)).toInt(), 8)]
    else -> "${Math.round((value - slider.low) / (slider.high - slider.low) * 100)}%"
}

val LIQUIFY_TOOLS = listOf("Push", "Grow", "Shrink", "Restore")

class ToolSettings(var diameter: Float, var opacity: Float = 1f, var stabilizer: Float = 0f)

fun findArtworks(artworks: List<ArtworkInfo>, query: String) =
    artworks.filter { it.name.contains(query.trim(), ignoreCase = true) }

data class Project(val id: String, var name: String, var color: Int = 0)

enum class Light(val label: String, val degrees: Int) {
    OFF("Off", 0),
    LEFT("Left", 180),
    TOP("Top", 270),
    RIGHT("Right", 0),
    BOTTOM("Bottom", 90),
}

data class ArtworkInfo(
    val id: String,
    val name: String,
    val projectId: String,
    val updated: Long,
    val variationOf: String = "",
    val width: Int = DEFAULT_CANVAS_WIDTH,
    val height: Int = DEFAULT_CANVAS_HEIGHT,
    val layers: Int = 0,
)

/** Every button that can live on a bar, by key. */
val BAR_KEYS = listOf(
    "projects", "more", "references", "view", "tools", "brushes", "eraser", "layers",
    "create", "study", "artwork", "export", "settings",
)

/** The bar the companion holds, the one with the color, and the one that opens under More. */
val DEFAULT_BARS = listOf(
    listOf("projects", "more", "references", "view", "tools"),
    listOf("brushes", "eraser", "layers"),
    listOf("create", "study", "artwork", "export", "settings"),
)

const val MORE_BAR = 2

/** Reads saved bars, written as keys with commas between them and bars between bars, keeping every button once and no bar empty. */
fun readBars(saved: String?): List<List<String>> {
    val bars = saved?.split('|')?.map { bar -> bar.split(',').filter { it in BAR_KEYS } } ?: return DEFAULT_BARS
    if (bars.size != DEFAULT_BARS.size || bars.any { it.isEmpty() }) return DEFAULT_BARS
    val seen = HashSet<String>()
    val clean = bars.map { bar -> bar.filter(seen::add) }.map { it.toMutableList() }
    // A button added in an update lands where it starts out.
    BAR_KEYS.filter { it !in seen }.forEach { key -> clean[DEFAULT_BARS.indexOfFirst { key in it }].add(key) }
    if (clean.any { it.isEmpty() } || "more" in clean[MORE_BAR]) return DEFAULT_BARS
    return clean
}

fun writeBars(bars: List<List<String>>) = bars.joinToString("|") { it.joinToString(",") }

/**
 * Moves a button to another place, or returns null when that would leave its bar empty or put
 * More inside the bar it opens.
 */
fun moveBarItem(bars: List<List<String>>, key: String, toBar: Int, index: Int): List<List<String>>? {
    val from = bars.indexOfFirst { key in it }
    if (from < 0 || toBar !in bars.indices) return null
    if (from != toBar && bars[from].size == 1) return null
    if (key == "more" && toBar == MORE_BAR) return null
    val moved = bars.map { bar -> bar.filter { it != key }.toMutableList() }
    moved[toBar].add(index.coerceIn(0, moved[toBar].size), key)
    return moved
}
