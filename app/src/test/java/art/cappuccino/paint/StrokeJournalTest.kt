package art.cappuccino.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StrokeJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun stroke(x: Float) = JournalEntry.Stroke(3, "ink", 24f, -1, 0.5f, true, 0.75f, floatArrayOf(x, 2f, 3f, 4f, 5f))

    @Test
    fun strokesAndUndoComeBackInOrder() {
        val journal = StrokeJournal(folder.root)
        journal.append(1, stroke(7f))
        journal.append(1, JournalEntry.Undo)
        journal.append(2, stroke(9f))
        val entries = journal.read(0)
        assertEquals(3, entries.size)
        val first = entries[0] as JournalEntry.Stroke
        assertEquals("ink", first.brushId)
        assertTrue(first.alphaLock)
        assertArrayEquals(floatArrayOf(7f, 2f, 3f, 4f, 5f), first.samples, 0f)
        assertEquals(JournalEntry.Undo, entries[1])
    }

    @Test
    fun strokesInASavedFileAreSkippedAndCleared() {
        val journal = StrokeJournal(folder.root)
        journal.append(1, stroke(1f))
        journal.append(2, stroke(2f))
        assertEquals(1, journal.read(1).size)
        journal.clear(1)
        assertEquals(1, journal.read(0).size)
        assertEquals(2, journal.latest())
    }

    @Test
    fun aRecordCutShortByACrashIsDropped() {
        val journal = StrokeJournal(folder.root)
        journal.append(1, stroke(1f))
        journal.append(1, stroke(2f))
        val file = File(folder.root, "journal-1.bin")
        file.writeBytes(file.readBytes().dropLast(6).toByteArray())
        assertEquals(1, journal.read(0).size)
    }
}
