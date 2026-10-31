package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveTest {
    private val point = listOf(1000f, 1000f)

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        FloatArray(22) { index ->
            val t = (index / 2) / 10f
            if (index % 2 == 0) x1 + (x2 - x1) * t else y1 + (y2 - y1) * t
        }

    @Test
    fun lineThroughTheVanishingPointIsAligned() {
        assertEquals(0f, strokeDrift(line(100f, 100f, 500f, 500f), point)!!, 0.01f)
    }

    @Test
    fun angledLineReportsItsDrift() {
        val drift = strokeDrift(line(100f, 300f, 500f, 500f), point)!!
        assertTrue(drift > PERSPECTIVE_TOLERANCE)
    }

    @Test
    fun verticalsAreAllowedWithOnePoint() {
        assertEquals(0f, strokeDrift(line(300f, 100f, 300f, 600f), point)!!, 0.01f)
    }

    @Test
    fun verticalsAreCheckedWithThreePoints() {
        val three = listOf(100f, 400f, 3000f, 400f, 1500f, 5000f)
        assertTrue(strokeDrift(line(300f, 100f, 300f, 600f), three)!! > 0f)
    }

    @Test
    fun shortStrokesAreIgnored() {
        assertNull(strokeDrift(line(100f, 100f, 150f, 120f), point))
    }

    @Test
    fun curvedStrokesAreIgnored() {
        val curve = FloatArray(22) { index ->
            val step = index / 2
            if (index % 2 == 0) step * 50f else 400f - (step - 5f) * (step - 5f) * 12f
        }
        assertNull(strokeDrift(curve, point))
    }

    @Test
    fun noGuideMeansNoCheck() {
        assertNull(strokeDrift(line(100f, 100f, 500f, 500f), emptyList()))
    }

    @Test
    fun summaryCountsDriftedLines() {
        val log = PerspectiveLog()
        log.add(1f)
        log.add(9f)
        assertTrue(log.summary()!!.startsWith("1 of your last 2"))
        log.reset()
        assertNull(log.summary())
    }

    @Test
    fun defaultsGiveOnePairPerPoint() {
        assertEquals(6, defaultVanishingPoints(3).size)
    }
}
