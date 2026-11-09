package art.cappuccino.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintTest {
    @Test
    fun frontCoverAddsBleedOnEverySide() {
        val setup = PrintSetup(6f, 9f)
        assertEquals(1875, setup.pixelWidth)
        assertEquals(2775, setup.pixelHeight)
        assertNull(setup.folds())
        assertArrayEquals(intArrayOf(38, 38, 1838, 2738), setup.front())
    }

    @Test
    fun wrapPutsTheSpineBetweenBackAndFront() {
        val spine = spineWidth(240, BOOK_PAPERS.first())
        assertEquals(0.54f, spine, 0.001f)
        val setup = PrintSetup(6f, 9f, spine, wrap = true)
        assertEquals(3837, setup.pixelWidth)
        val folds = setup.folds()!!
        assertEquals(1837.5f, folds[0], 0.01f)
        assertEquals(1837.5f + spine * 300, folds[1], 0.01f)
        assertEquals(3, setup.safeAreas().size)
        assertEquals(kotlin.math.round(folds[1]).toInt(), setup.front()[0])
    }
}

class PreflightTest {
    private val setup = PrintSetup(6f, 9f, 0.5f, wrap = true)
    private val width = setup.pixelWidth / 10
    private val height = setup.pixelHeight / 10

    private fun canvas(color: (Int, Int) -> Int) = IntArray(width * height) { color(it % width, it / width) }

    @Test
    fun aCleanCoverPasses() {
        val pixels = canvas { _, _ -> 0xFF3A6EA5.toInt() }
        val title = PlacedBox("Title", setup.px(7f), setup.px(2f), setup.px(11f), setup.px(3f))
        assertEquals(emptyList<String>(), preflight(setup, pixels, width, height, listOf(title)))
    }

    @Test
    fun textNearTheEdgeOrOnTheBarcodeIsFlagged() {
        val pixels = canvas { _, _ -> 0xFF3A6EA5.toInt() }
        val clipped = PlacedBox("Title", setup.px(6.8f), setup.px(0.2f), setup.px(11f), setup.px(1f))
        val code = setup.barcode()!!
        val blurb = PlacedBox("Blurb", code[0], code[1], code[2], code[3])
        val issues = preflight(setup, pixels, width, height, listOf(clipped, blurb))
        assertEquals(2, issues.size)
    }

    @Test
    fun artThatStopsAtTheTrimIsFlagged() {
        val bleed = setup.px(setup.bleed) / 10f
        val pixels = canvas { x, y ->
            val inBleed = x < bleed || y < bleed || x >= width - bleed || y >= height - bleed
            if (inBleed) 0xFFFFFFFF.toInt() else 0xFF3A6EA5.toInt()
        }
        assertEquals(1, preflight(setup, pixels, width, height, emptyList()).size)
    }
}
