package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarsTest {
    @Test
    fun savedBarsReadBackAndBadOnesFallBack() {
        val moved = moveBarItem(DEFAULT_BARS, "layers", 0, 1)!!
        assertEquals(moved, readBars(writeBars(moved)))
        assertEquals(DEFAULT_BARS, readBars(null))
        assertEquals(DEFAULT_BARS, readBars("projects||create"))
        assertEquals(DEFAULT_BARS, readBars("nonsense"))
    }

    @Test
    fun aMissingButtonReturnsToItsBar() {
        val saved = "projects,more,references,view,tools|brushes,eraser|create,study,artwork,export,settings"
        assertEquals(listOf("brushes", "eraser", "layers"), readBars(saved)[1])
    }

    @Test
    fun aButtonMovesToItsSpot() {
        val moved = moveBarItem(DEFAULT_BARS, "study", 1, 0)!!
        assertEquals(listOf("study", "brushes", "eraser", "layers"), moved[1])
        assertEquals(listOf("create", "artwork", "export", "settings"), moved[2])
        val within = moveBarItem(DEFAULT_BARS, "tools", 0, 0)!!
        assertEquals(listOf("tools", "projects", "more", "references", "view"), within[0])
    }

    @Test
    fun noBarIsEmptiedAndMoreStaysOutOfItsOwnBar() {
        var bars = DEFAULT_BARS
        bars = moveBarItem(bars, "brushes", 0, 0)!!
        bars = moveBarItem(bars, "eraser", 0, 0)!!
        assertNull(moveBarItem(bars, "layers", 0, 0))
        assertNull(moveBarItem(DEFAULT_BARS, "more", MORE_BAR, 0))
    }
}
