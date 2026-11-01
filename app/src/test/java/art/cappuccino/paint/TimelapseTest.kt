package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseTest {
    @Test
    fun aShortVideoSkipsEvenlyAndAlwaysEndsOnTheFinishedArt() {
        val timeline = timelapseTimeline(1000, 15)
        assertEquals(225 + 45, timeline.size)
        assertEquals(0, timeline.first())
        assertEquals(999, timeline.last())
        assertTrue(timeline.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun fewFramesAreKeptAsTheyAre() {
        assertEquals(10 + 45, timelapseTimeline(10, 60).size)
        assertEquals(1000 + 45, timelapseTimeline(1000, null).size)
        assertEquals(emptyList<Int>(), timelapseTimeline(0, 15))
    }
}
