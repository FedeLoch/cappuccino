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

enum class BrushMode { PAINT, FILL, SMUDGE, GRADIENT }

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
        val SMUDGE = BrushKind(
            "smudge", "Smudge", 0.6f, 0.2f, 0.65f, 0.4f, 0.5f, 0f, 90f, mode = BrushMode.SMUDGE,
        )
        val FILL = BrushKind("fill", "Fill", 1f, 1f, 1f, 0f, 1f, 0f, 40f, mode = BrushMode.FILL)
        val GRADIENT = BrushKind(
            "gradient", "Gradient", 1f, 1f, 1f, 0f, 1f, 0f, 40f, mode = BrushMode.GRADIENT,
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
        )
        val PAINT_BRUSHES = listOf(INK, LINER, PENCIL, CHARCOAL, CHALK, MARKER, SOFT, WASH)
        val entries = PAINT_BRUSHES + listOf(SMUDGE, FILL, GRADIENT) + ERASERS
    }
}

val BLEND_MODES = listOf("Normal", "Multiply", "Screen", "Add")

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
)

data class LayerGroup(
    val id: Int,
    var name: String,
    var visible: Boolean = true,
    var opacity: Float = 1f,
    var collapsed: Boolean = false,
)

val VIEW_MODES = listOf("Color", "Grayscale", "Two values", "Three values")

val GUIDES = listOf("None", "Thirds", "Golden", "Grid")

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
)
