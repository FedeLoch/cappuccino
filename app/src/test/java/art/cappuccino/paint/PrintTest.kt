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
