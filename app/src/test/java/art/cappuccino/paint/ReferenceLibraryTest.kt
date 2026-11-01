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

    private val boards = mapOf(
        "sky" to ReferenceNote(board = "Colors"),
        "dune" to ReferenceNote(board = "Layouts"),
        "hero" to ReferenceNote(board = "Ideas"),
    )
    private val files = listOf("sky", "dune", "hero", "loose")
    private val purposes = mapOf(
        "Colors" to setOf(BoardPurpose.PALETTE),
        "Layouts" to setOf(BoardPurpose.COMPOSITION),
        "Ideas" to setOf(BoardPurpose.INSPIRATION),
    )

    private fun AspectChoice.names(purpose: BoardPurpose) = aspects.getValue(purpose).map { names[it] }

    @Test
    fun eachKindOfNoteReadsItsOwnBoard() {
        val choice = aspectReferences(files, emptySet(), boards, purposes, emptySet())
        assertEquals(ReferenceScope.BOARDS, choice.scope)
        assertEquals(listOf("sky"), choice.names(BoardPurpose.PALETTE))
        assertEquals(listOf("dune"), choice.names(BoardPurpose.COMPOSITION))
        assertEquals(listOf("Colors"), choice.boards[BoardPurpose.PALETTE])
    }

    @Test
    fun otherNotesSkipPurposedBoardsAndInspirationIsNeverCompared() {
        val choice = aspectReferences(files, emptySet(), boards, purposes, emptySet())
        assertEquals(listOf("loose"), choice.names(BoardPurpose.VALUES))
        assertEquals(false, "hero" in choice.names)
    }

    @Test
    fun openReferencesStillAnswerEverything() {
        val choice = aspectReferences(files, setOf("hero"), boards, purposes, emptySet())
        assertEquals(ReferenceScope.OPEN, choice.scope)
        assertEquals(listOf("hero"), choice.names(BoardPurpose.PALETTE))
    }

    @Test
    fun anArtworkCanFollowOnlySomeBoards() {
        val choice = aspectReferences(files, emptySet(), boards, purposes, setOf("Layouts"))
        assertEquals(listOf("dune"), choice.names(BoardPurpose.COMPOSITION))
        assertEquals(emptyList<String>(), choice.names(BoardPurpose.PALETTE))
    }

    @Test
    fun withoutPurposesNothingChanges() {
        val choice = aspectReferences(files, emptySet(), emptyMap(), emptyMap(), emptySet())
        assertEquals(ReferenceScope.NEWEST, choice.scope)
        assertEquals(files, choice.names(BoardPurpose.MOOD))
    }

    @Test
    fun blocksComeReadyAndYourChoiceWins() {
        val used = withBlockPurposes(emptyMap())
        assertEquals(setOf(BoardPurpose.PALETTE), used["Color palettes"])
        assertEquals(setOf(BoardPurpose.VALUES), withBlockPurposes(mapOf("Color palettes" to setOf(BoardPurpose.VALUES)))["Color palettes"])
    }

    @Test
    fun aPerspectiveBlockIsNeverCompared() {
        val notes = mapOf("line" to ReferenceNote(board = "Perspective"), "loose" to ReferenceNote())
        val choice = aspectReferences(listOf("line", "loose"), emptySet(), notes, withBlockPurposes(emptyMap()), emptySet())
        assertEquals(listOf("loose"), choice.names)
    }

    @Test
    fun yourOwnBlocksFollowTheReadyMadeOnes() {
        val blocks = referenceBlocks(mapOf("a" to ReferenceNote(board = "Dragons"), "b" to ReferenceNote(board = "Lights")))
        assertEquals(REFERENCE_BLOCKS.keys.toList() + "Dragons", blocks)
    }
}
