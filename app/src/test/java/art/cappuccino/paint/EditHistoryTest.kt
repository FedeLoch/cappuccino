package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EditHistoryTest {
    private fun state(opacity: Float) = LayerState(listOf(LayerInfo(1, "Layer 1", opacity)), 1)

    @Test
    fun strokesAndLayerEditsUndoInReverseOrder() {
        val history = EditHistory()
        val layers = Edit.Layers(state(1f), state(0.5f))
        history.record(Edit.Stroke)
        history.record(layers)
        history.record(Edit.Stroke)
        assertSame(Edit.Stroke, history.undo())
        assertSame(layers, history.undo())
        assertSame(Edit.Stroke, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun redoReplaysUndoneEdits() {
        val history = EditHistory()
        history.record(Edit.Stroke)
        history.undo()
        assertSame(Edit.Stroke, history.redo())
        assertNull(history.redo())
    }

    @Test
    fun newEditClearsRedo() {
        val history = EditHistory()
        history.record(Edit.Stroke)
        history.undo()
        history.record(Edit.Stroke)
        assertNull(history.redo())
    }

    @Test
    fun sliderDragBecomesOneEdit() {
        val history = EditHistory()
        history.record(Edit.Layers(state(1f), state(0.8f), mergeKey = "opacity1"))
        history.record(Edit.Layers(state(0.8f), state(0.4f), mergeKey = "opacity1"))
        val merged = history.undo() as Edit.Layers
        assertEquals(1f, merged.before.layers[0].opacity, 0f)
        assertEquals(0.4f, merged.after.layers[0].opacity, 0f)
        assertNull(history.undo())
    }

    @Test
    fun savedStatesDontChangeWithTheDocument() {
        val layer = LayerInfo(1, "Layer 1")
        val saved = LayerState(listOf(layer), 1)
        layer.opacity = 0.2f
        assertEquals(1f, saved.layers[0].opacity, 0f)
    }
}
