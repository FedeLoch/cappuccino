package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupsTest {
    @Test
    fun warmupFollowsTheLatestNoteAndChangesByDay() {
        assertEquals("Values", warmupFor("Values", 0).topic)
        assertNotEquals(warmupFor("Values", 0), warmupFor("Values", 1))
        assertTrue(warmupFor(null, 3) in WARMUPS)
        assertTrue(warmupFor("Mood", 3) in WARMUPS)
    }

    @Test
    fun finishNoteNamesWhatGrew() {
        val first = ArtSnapshot(0, contrast = 0.2f, coverage = 0.1f, colored = 0f)
        assertTrue(finishNote(first, first.copy(contrast = 0.5f)).contains("values"))
        assertTrue(finishNote(first, first.copy(colored = 0.6f)).contains("color"))
        assertTrue(finishNote(first, first.copy(coverage = 0.8f)).contains("whole piece"))
        assertTrue(finishNote(first, first).startsWith("Finished!"))
    }
}
