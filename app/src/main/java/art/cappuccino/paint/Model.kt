package art.cappuccino.paint

import kotlin.math.pow

const val CANVAS_WIDTH = 4096
const val CANVAS_HEIGHT = 2732
const val MAX_LAYERS = 12

enum class BrushKind(
    val label: String,
    val minScale: Float,
    val spacing: Float,
    val flow: Float,
    val pressureFlow: Float,
    val hardness: Float,
    val grain: Float,
    val defaultSize: Float,
    val isEraser: Boolean = false,
    val isFill: Boolean = false,
) {
    INK("Ink", 0.12f, 0.08f, 1f, 0f, 1f, 0f, 24f),
    LINER("Liner", 0.85f, 0.06f, 1f, 0f, 1f, 0f, 12f),
    PENCIL("Pencil", 0.5f, 0.1f, 0.35f, 0.8f, 0.7f, 0.8f, 16f),
    CHARCOAL("Charcoal", 0.7f, 0.1f, 0.55f, 0.6f, 0.6f, 1f, 60f),
    CHALK("Chalk", 0.8f, 0.12f, 0.6f, 0.3f, 0.8f, 0.9f, 80f),
    MARKER("Marker", 0.9f, 0.05f, 0.18f, 0.2f, 0.9f, 0f, 70f),
    SOFT("Soft", 0.6f, 0.06f, 0.08f, 0.7f, 0f, 0f, 160f),
    WASH("Wash", 0.7f, 0.08f, 0.05f, 0.5f, 0.2f, 0.35f, 220f),
    FILL("Fill", 1f, 1f, 1f, 0f, 1f, 0f, 40f, isFill = true),
    ERASE_INK("Ink", 0.12f, 0.08f, 1f, 0f, 1f, 0f, 40f, true),
    ERASE_LINER("Liner", 0.85f, 0.06f, 1f, 0f, 1f, 0f, 24f, true),
    ERASE_PENCIL("Pencil", 0.5f, 0.1f, 0.35f, 0.8f, 0.7f, 0.8f, 30f, true),
    ERASE_CHARCOAL("Charcoal", 0.7f, 0.1f, 0.55f, 0.6f, 0.6f, 1f, 80f, true),
    ERASE_CHALK("Chalk", 0.8f, 0.12f, 0.6f, 0.3f, 0.8f, 0.9f, 80f, true),
    ERASE_MARKER("Marker", 0.9f, 0.05f, 0.18f, 0.2f, 0.9f, 0f, 90f, true),
    ERASE_SOFT("Soft", 0.6f, 0.06f, 0.15f, 0.5f, 0f, 0f, 160f, true),
    ERASE_WASH("Wash", 0.7f, 0.08f, 0.08f, 0.5f, 0.2f, 0.35f, 220f, true);

    /** Stamp alpha that builds up to about [opacity] across one stamp width. */
    fun stampFlow(opacity: Float): Float =
        if (opacity >= 1f) flow else flow * (1f - (1f - opacity).pow(spacing))
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
)

val VIEW_MODES = listOf("Color", "Grayscale", "Two values", "Three values")

val GUIDES = listOf("None", "Thirds", "Golden", "Grid")

class ToolSettings(var diameter: Float, var opacity: Float = 1f, var stabilizer: Float = 0f)

data class Project(val id: String, var name: String)

data class ArtworkInfo(
    val id: String,
    val name: String,
    val projectId: String,
    val updated: Long,
)
