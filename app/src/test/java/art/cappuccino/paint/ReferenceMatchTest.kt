package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SIZE = 48
private const val WHITE = 0xFFFFFFFF.toInt()
private const val TEAL = 0xFF207070.toInt()
private const val ORANGE = 0xFFD8702A.toInt()
private const val DARK = 0xFF181820.toInt()

class ReferenceMatchTest {
    private fun image(paint: (Int, Int) -> Int) = IntArray(SIZE * SIZE) { paint(it % SIZE, it / SIZE) }

    private fun profile(paint: (Int, Int) -> Int) = profileImage(image(paint), SIZE, SIZE)

    private val reference = profile { x, y ->
        when {
            y < 24 -> TEAL
            x < 16 -> ORANGE
            else -> DARK
        }
    }

    @Test
    fun noReferencesMeansNoMatch() {
        assertNull(compareWithReferences(reference, emptyList()))
    }

    @Test
    fun anImageMatchesItself() {
        val match = compareWithReferences(reference, listOf(reference))!!
        assertEquals(1f, match.overall, 0.02f)
        assertTrue(match.summary.contains("close"))
    }

    @Test
    fun blankPaperIsFarAndGetsColorSuggestions() {
        val match = compareWithReferences(profile { _, _ -> WHITE }, listOf(reference))!!
        assertTrue(match.overall < 0.5f)
        assertTrue(match.summary.contains("early"))
        assertTrue(match.colors.isNotEmpty())
    }

    @Test
    fun aFewLinesCantLookCloseToACreamReference() {
        val cream = profile { _, _ -> 0xFFF7EEDC.toInt() }
        val match = compareWithReferences(profile { _, y -> if (y % 8 == 0) DARK else WHITE }, listOf(cream))!!
        assertTrue(match.summary.contains("early"))
        assertTrue(match.overall < 0.5f)
    }

    @Test
    fun scoreRisesAsTheArtworkApproachesTheReference() {
        val early = compareWithReferences(profile { _, y -> if (y < 24) TEAL else WHITE }, listOf(reference))!!
        val later = compareWithReferences(profile { x, y -> if (y < 24) TEAL else if (x < 16) WHITE else DARK }, listOf(reference))!!
        assertTrue(later.overall > early.overall)
    }

    @Test
    fun theClosestReferenceInASetCounts() {
        val other = profile { _, _ -> 0xFFE0D020.toInt() }
        val alone = compareWithReferences(reference, listOf(other))!!
        val set = compareWithReferences(reference, listOf(other, reference))!!
        assertTrue(set.overall > alone.overall)
    }

    @Test
    fun feedbackExplainsItsNumbersAndClosestReference() {
        val other = profile { _, _ -> 0xFFE0D020.toInt() }
        val match = compareWithReferences(reference, listOf(other, reference))!!
        assertEquals(1, match.closest)
        assertTrue(match.why.contains("Brightness: yours"))
    }

    @Test
    fun missingHueIsNamed() {
        val art = profile { x, y -> if (y < 24) WHITE else if (x < 16) ORANGE else DARK }
        val match = compareWithReferences(art, listOf(reference))!!
        assertTrue(match.areas.size == 4)
        assertTrue(match.colors.isNotEmpty())
    }
}
