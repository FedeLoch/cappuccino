package art.cappuccino.paint

private const val MAX_EDITS = 300

class LayerState(
    layers: List<LayerInfo>,
    val activeLayer: Int,
    groups: List<LayerGroup> = emptyList(),
) {
    val layers: List<LayerInfo> = layers.map { it.copy() }
    val groups: List<LayerGroup> = groups.map { it.copy() }
}

sealed interface Edit {
    data object Stroke : Edit

    /**
     * A change to the layer list or its settings. [pixels] means the change also has a pixel
     * entry in the engine's history, and [mergeKey] joins a run of edits, such as a slider drag.
     */
    class Layers(
        val before: LayerState,
        val after: LayerState,
        val pixels: Boolean = false,
        val mergeKey: String? = null,
    ) : Edit
}

/** One timeline for strokes and layer changes, so undo always steps back in drawing order. */
class EditHistory {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    fun record(edit: Edit) {
        redoStack.clear()
        val last = undoStack.lastOrNull()
        if (edit is Edit.Layers && edit.mergeKey != null &&
            last is Edit.Layers && last.mergeKey == edit.mergeKey
        ) {
            undoStack.removeLast()
            undoStack.addLast(Edit.Layers(last.before, edit.after, false, edit.mergeKey))
            return
        }
        undoStack.addLast(edit)
        if (undoStack.size > MAX_EDITS) undoStack.removeFirst()
    }

    fun undo(): Edit? = undoStack.removeLastOrNull()?.also(redoStack::addLast)

    fun redo(): Edit? = redoStack.removeLastOrNull()?.also(undoStack::addLast)

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
