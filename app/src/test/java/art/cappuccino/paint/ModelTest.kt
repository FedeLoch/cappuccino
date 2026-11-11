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
    fun textureBrushesHaveTips() {
        assertTrue(BrushKind.TEXTURE_BRUSHES.all { it.tip?.size == 128 * 128 && it.tip.any { value -> value != 0.toByte() } })
    }

    @Test
    fun smallerCanvasesAllowMoreLayers() {
        val memory = 8_000_000_000L
        assertTrue(layerLimit(2048, 2048, memory) > layerLimit(4096, 2732, memory))
        assertEquals(8, layerLimit(8192, 8192, 1_000_000_000L))
    }

    @Test
    fun everyBrushShapeHasAnEraser() {
        val erasers = BrushKind.entries.filter { it.isEraser && it.mode == BrushMode.PAINT }
        val brushes = BrushKind.PAINT_BRUSHES
        assertEquals(brushes.map { it.label }, erasers.map { it.label })
    }

    @Test
    fun filterValuesReadInTheirOwnUnits() {
        fun slider(filter: String, name: String) = FILTERS.first { it.label == filter }.sliders.first { it.label == name }
        assertEquals("12 px", filterValueText(slider("Blur", "Radius"), 12f))
        assertEquals("90°", filterValueText(slider("Motion blur", "Angle"), 1.5708f))
        assertEquals("100%", filterValueText(slider("Sharpen", "Amount"), 1f))
        assertEquals("20%", filterValueText(slider("Noise", "Amount"), 0.1f))
        assertEquals("128", filterValueText(slider("Levels", "Black point"), 0.5f))
        assertEquals("1.00", filterValueText(slider("Levels", "Midtones"), 1f))
    }
}
