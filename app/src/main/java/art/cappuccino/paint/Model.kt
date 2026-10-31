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
) {
    INK("Ink", 0.12f, 0.08f, 1f, 0f, 1f, 0f),
    PENCIL("Pencil", 0.5f, 0.1f, 0.35f, 0.8f, 0.7f, 0.8f),
    CHALK("Chalk", 0.8f, 0.12f, 0.6f, 0.3f, 0.8f, 0.9f),
    SOFT("Soft", 0.6f, 0.06f, 0.08f, 0.7f, 0f, 0f),
    ERASER("Eraser", 0.5f, 0.08f, 1f, 0f, 0.9f, 0f);

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
    var file: String? = null,
)

data class Project(val id: String, var name: String)

data class ArtworkInfo(
    val id: String,
    val name: String,
    val projectId: String,
    val updated: Long,
)
