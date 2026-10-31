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
) {
    /** Stamp alpha that builds up to about [opacity] across one stamp width. */
    fun stampFlow(opacity: Float): Float =
        if (opacity >= 1f) flow else flow * (1f - (1f - opacity).pow(spacing))

    companion object {
        val INK = BrushKind("ink", "Ink", 0.12f, 0.08f, 1f, 0f, 1f, 0f, 24f)
        private val LINER = BrushKind("liner", "Liner", 0.85f, 0.06f, 1f, 0f, 1f, 0f, 12f)
        private val PENCIL = BrushKind("pencil", "Pencil", 0.5f, 0.1f, 0.35f, 0.8f, 0.7f, 0.8f, 16f)
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
            scatter = 0.7f, sizeJitter = 0.5f, angleJitter = 1f, shadeJitter = 0.25f,
        )
        private val GRASS = BrushKind(
            "grass", "Grass", 0.6f, 0.2f, 0.9f, 0.2f, 0.8f, 0f, 120f,
            tip = blobTip { x, y -> kotlin.math.abs(x) < 0.09f * (1f - y) && y > -0.95f },
            scatter = 0.45f, sizeJitter = 0.5f, angleJitter = 0.09f, shadeJitter = 0.2f,
        )
        private val ROCKS = BrushKind(
            "rocks", "Rocks", 0.6f, 0.6f, 1f, 0.1f, 0.9f, 0.25f, 110f,
            tip = blobTip { x, y -> kotlin.math.abs(x) + kotlin.math.abs(y) * 1.3f + kotlin.math.abs(x + y) * 0.35f < 1f },
            scatter = 0.6f, sizeJitter = 0.7f, angleJitter = 1f, shadeJitter = 0.3f,
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
            BrushKind("erase-pencil", "Pencil", 0.5f, 0.1f, 0.35f, 0.8f, 0.7f, 0.8f, 30f, true),
            BrushKind("erase-charcoal", "Charcoal", 0.7f, 0.1f, 0.55f, 0.6f, 0.6f, 1f, 80f, true),
            BrushKind("erase-chalk", "Chalk", 0.8f, 0.12f, 0.6f, 0.3f, 0.8f, 0.9f, 80f, true),
            BrushKind("erase-marker", "Marker", 0.9f, 0.05f, 0.18f, 0.2f, 0.9f, 0f, 90f, true),
            BrushKind("erase-soft", "Soft", 0.6f, 0.06f, 0.15f, 0.5f, 0f, 0f, 160f, true),
            BrushKind("erase-wash", "Wash", 0.7f, 0.08f, 0.08f, 0.5f, 0.2f, 0.35f, 220f, true),
            BrushKind("erase-lasso", "Lasso", 1f, 1f, 1f, 0f, 1f, 0f, 40f, true, BrushMode.LASSO),
        )
        val PAINT_BRUSHES = listOf(INK, LINER, PENCIL, CHARCOAL, CHALK, MARKER, SOFT, WASH)
        val entries = PAINT_BRUSHES + TEXTURE_BRUSHES + SCATTER_BRUSHES + listOf(SMUDGE, FILL, GRADIENT, LASSO_FILL) + ERASERS
    }
}

val BLEND_MODES = listOf("Normal", "Multiply", "Screen", "Additive")

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

/** A filter's label, its engine number, and its sliders in order. */
class FilterSlider(val label: String, val low: Float, val high: Float, val start: Float)

class FilterKind(val label: String, val id: Int, val sliders: List<FilterSlider>, val usesColor: Boolean = false)

val FILTERS = listOf(
    FilterKind("Blur", 1, listOf(FilterSlider("Radius", 0f, 120f, 12f))),
    FilterKind(
        "Motion blur", 2,
        listOf(FilterSlider("Distance", 0f, 240f, 40f), FilterSlider("Angle", 0f, 3.1416f, 0f)),
    ),
    FilterKind("Sharpen", 3, listOf(FilterSlider("Amount", 0f, 3f, 1f))),
    FilterKind("Noise", 4, listOf(FilterSlider("Amount", 0f, 0.5f, 0.1f))),
    FilterKind("Gradient map", 5, listOf(FilterSlider("Amount", 0f, 1f, 1f)), usesColor = true),
    FilterKind(
        "Levels", 6,
        listOf(
            FilterSlider("Black point", 0f, 0.5f, 0f),
            FilterSlider("Midtones", 0.3f, 3f, 1f),
            FilterSlider("White point", 0.5f, 1f, 1f),
        ),
    ),
    FilterKind("Depth fade", 7, listOf(FilterSlider("Amount", 0f, 1f, 0.3f)), usesColor = true),
)

val LIQUIFY_TOOLS = listOf("Push", "Grow", "Shrink", "Restore")

class ToolSettings(var diameter: Float, var opacity: Float = 1f, var stabilizer: Float = 0f)

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
