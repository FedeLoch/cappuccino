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
    fun onlyEraserToolsErase() {
        assertEquals(3, BrushKind.entries.count { it.isEraser })
        assertTrue(BrushKind.entries.filterNot { it.isEraser }.size >= 8)
    }
}
