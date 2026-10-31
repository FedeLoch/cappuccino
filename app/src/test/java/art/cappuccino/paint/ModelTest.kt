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
    fun everyBrushShapeHasAnEraser() {
        val (erasers, brushes) = BrushKind.entries.filterNot { it.isFill }.partition { it.isEraser }
        assertEquals(brushes.map { it.label }, erasers.map { it.label })
    }
}
