package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceGeometryTest {
    @Test
    fun wideImageIsLimitedByWidth() {
        assertEquals(800, fitReferenceWidth(2000, 1000, 2000, 1400))
    }

    @Test
    fun tallImageFitsOnScreen() {
        val width = fitReferenceWidth(1000, 4000, 2000, 1400)
        assertTrue(width * 4 <= 1400)
    }

    @Test
    fun panStaysAtZeroWithoutZoom() {
        assertEquals(0f, clampReferencePan(300f, 1f, 500), 0f)
    }

    @Test
    fun panStopsAtTheImageEdge() {
        assertEquals(500f, clampReferencePan(900f, 3f, 500), 0f)
        assertEquals(-500f, clampReferencePan(-900f, 3f, 500), 0f)
    }

    @Test
    fun windowCantLeaveTheScreen() {
        assertEquals(1900, clampReferencePosition(5000, 400, 2000, 100))
        assertEquals(-300, clampReferencePosition(-5000, 400, 2000, 100))
    }

    @Test
    fun turnsNearAQuarterSettleOnIt() {
        assertEquals(90f, snapReferenceRotation(86f), 0.001f)
        assertEquals(0f, snapReferenceRotation(357f), 0.001f)
        assertEquals(40f, snapReferenceRotation(40f), 0.001f)
        assertEquals(270f, snapReferenceRotation(-90f), 0.001f)
    }

    @Test
    fun aReferenceOnItsSideSwapsItsWindow() {
        assertEquals(false, referenceSideways(0f))
        assertEquals(true, referenceSideways(90f))
        assertEquals(false, referenceSideways(180f))
        assertEquals(true, referenceSideways(270f))
    }
}
