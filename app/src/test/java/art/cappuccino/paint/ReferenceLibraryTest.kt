package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceLibraryTest {
    private val names = (1..60).map { "ref$it" }

    @Test
    fun openReferencesComeFirst() {
        val notes = mapOf("ref2" to ReferenceNote(starred = true))
        val choice = companionReferences(names, setOf("ref5"), notes)
        assertEquals(listOf("ref5"), choice.names)
        assertEquals(ReferenceScope.OPEN, choice.scope)
    }

    @Test
    fun starsComeBeforeTheWholeLibrary() {
        val notes = mapOf("ref2" to ReferenceNote(starred = true), "ref9" to ReferenceNote(board = "Armor"))
        val choice = companionReferences(names, emptySet(), notes)
        assertEquals(listOf("ref2"), choice.names)
        assertEquals(ReferenceScope.STARRED, choice.scope)
    }

    @Test
    fun largeLibrariesUseOnlyTheNewestImages() {
        val choice = companionReferences(names, emptySet(), emptyMap())
        assertEquals(names.take(COMPANION_REFERENCE_LIMIT), choice.names)
        assertEquals(ReferenceScope.NEWEST, choice.scope)
    }

    @Test
    fun filtersKeepStarsOrOneBoard() {
        val notes = mapOf(
            "ref1" to ReferenceNote(board = "Armor"),
            "ref2" to ReferenceNote(starred = true, board = "Forest"),
        )
        assertEquals(names, filterReferences(names, notes, ""))
        assertEquals(listOf("ref2"), filterReferences(names, notes, STARRED_FILTER))
        assertEquals(listOf("ref1"), filterReferences(names, notes, "Armor"))
        assertEquals(listOf("Armor", "Forest"), referenceBoards(notes))
    }
}
