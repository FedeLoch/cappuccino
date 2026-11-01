package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionMemoryTest {
    private fun tips(vararg topics: String) = topics.map { Tip(it, "note $it") }

    private fun report(contrast: Float) =
        AnalysisReport(emptyList(), 1, 1, IntArray(1), IntArray(1), contrast = contrast)

    @Test
    fun stagesFollowHowMuchOfTheCanvasIsCovered() {
        assertEquals(ArtStage.SKETCH, artStage(0.1f))
        assertEquals(ArtStage.BLOCK_IN, artStage(0.4f))
        assertEquals(ArtStage.FINISHING, artStage(0.9f))
    }

    @Test
    fun eachStagePutsItsConcernFirst() {
        val all = tips("Palette", "Values", "Silhouette")
        assertEquals("Silhouette", orderForStage(all, ArtStage.SKETCH).first().topic)
        assertEquals("Values", orderForStage(all, ArtStage.BLOCK_IN).first().topic)
        assertEquals("Palette", orderForStage(all, ArtStage.FINISHING).first().topic)
    }

    @Test
    fun progressIsOnlyMentionedWhenSomethingReallyChanged() {
        val before = ArtSnapshot(0L, 0.3f, 0.3f, 0.3f)
        assertNull(progressNote(before, before.copy(contrast = 0.33f)))
        assertNotNull(progressNote(before, before.copy(contrast = 0.5f)))
        assertNotNull(progressNote(before, before.copy(coverage = 0.6f)))
        assertNotNull(progressNote(before, before.copy(colored = 0.6f)))
    }

    @Test
    fun mutedNotesAndTiredTopicsStayQuiet() {
        val all = tips("Values", "Palette", "Light")
        val left = withoutMuted(all, setOf("note Values"), mapOf("Palette" to 3, "Light" to 2))
        assertEquals(listOf("Light"), left.map { it.topic })
    }

    @Test
    fun theWidestValueRangeReadsBest() {
        assertEquals(1, clearestAtASmallSize(listOf(report(0.2f), report(0.7f), report(0.4f))))
        assertEquals(-1, clearestAtASmallSize(emptyList()))
    }
}
