package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun fullOpacityKeepsTheBrushFlow() {
        assertEquals(1f, BrushKind.INK.stampFlow(1f), 0.0001f)
    }

    @Test
    fun lowerOpacityLowersStampAlpha() {
        assertTrue(BrushKind.INK.stampFlow(0.2f) < BrushKind.INK.stampFlow(0.8f))
    }

    @Test
    fun smallerCanvasesAllowMoreLayers() {
        val memory = 8_000_000_000L
        assertTrue(layerLimit(2048, 2048, memory) > layerLimit(4096, 2732, memory))
        assertEquals(8, layerLimit(8192, 8192, 1_000_000_000L))
    }

    @Test
    fun everyBrushShapeHasAnEraser() {
        val erasers = BrushKind.entries.filter { it.isEraser }
        val brushes = BrushKind.PAINT_BRUSHES
        assertEquals(brushes.map { it.label }, erasers.map { it.label })
    }
}
