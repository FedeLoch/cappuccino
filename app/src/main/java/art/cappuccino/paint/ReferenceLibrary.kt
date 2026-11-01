package art.cappuccino.paint

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.EditText
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/** The companion compares with at most this many images, so saves stay quick. */
const val COMPANION_REFERENCE_LIMIT = 40
const val STARRED_FILTER = "*"
private const val THUMBNAIL_CACHE_BYTES = 48 * 1024 * 1024

data class ReferenceNote(val starred: Boolean = false, val board: String = "")

enum class ReferenceScope { OPEN, BOARDS, STARRED, NEWEST }

/** What a board teaches the companion. Inspiration boards are only for you to look at. */
enum class BoardPurpose(val label: String) {
    PALETTE("Palette"),
    VALUES("Values"),
    COMPOSITION("Composition"),
    MOOD("Mood"),
    INSPIRATION("Inspiration"),
}

val COMPARED_PURPOSES = BoardPurpose.entries - BoardPurpose.INSPIRATION

/** Ready-made blocks, each already telling the companion what to read in it. Sorting is optional. */
val REFERENCE_BLOCKS = linkedMapOf(
    "Color palettes" to setOf(BoardPurpose.PALETTE),
    "Lights" to setOf(BoardPurpose.VALUES),
    "Composition" to setOf(BoardPurpose.COMPOSITION),
    "Mood" to setOf(BoardPurpose.MOOD),
    "Character design" to setOf(BoardPurpose.PALETTE, BoardPurpose.VALUES),
    "Environment" to setOf(BoardPurpose.PALETTE, BoardPurpose.VALUES, BoardPurpose.COMPOSITION, BoardPurpose.MOOD),
    "Perspective" to setOf(BoardPurpose.INSPIRATION),
    "Inspiration" to setOf(BoardPurpose.INSPIRATION),
)

/** A block's own purposes, unless you changed them. */
fun withBlockPurposes(saved: Map<String, Set<BoardPurpose>>) = REFERENCE_BLOCKS + saved

/** The ready-made blocks first, then the ones you named. */
fun referenceBlocks(notes: Map<String, ReferenceNote>) =
    REFERENCE_BLOCKS.keys.toList() + referenceBoards(notes).filter { it !in REFERENCE_BLOCKS }

/** Which images the companion compares with for each kind of note, and the boards they came from. */
class AspectChoice(
    val names: List<String>,
    val aspects: Map<BoardPurpose, List<Int>>,
    val boards: Map<BoardPurpose, List<String>>,
    val scope: ReferenceScope,
)

class ReferenceChoice(val names: List<String>, val scope: ReferenceScope)

/** Open references come first, then starred ones, then the newest of the library. */
fun companionReferences(
    newestFirst: List<String>,
    open: Set<String>,
    notes: Map<String, ReferenceNote>,
): ReferenceChoice {
    val opened = newestFirst.filter { it in open }
    if (opened.isNotEmpty()) return ReferenceChoice(opened, ReferenceScope.OPEN)
    val starred = newestFirst.filter { notes[it]?.starred == true }
    if (starred.isNotEmpty()) {
        return ReferenceChoice(starred.take(COMPANION_REFERENCE_LIMIT), ReferenceScope.STARRED)
    }
    return ReferenceChoice(newestFirst.take(COMPANION_REFERENCE_LIMIT), ReferenceScope.NEWEST)
}

/**
 * Open references still answer everything, because you chose them just now. Otherwise each kind of
 * note reads the boards made for it, and falls back on references that aren't in a purposed board.
 * [followed] narrows the choice to the boards of one artwork. When it's empty, all boards count.
 */
fun aspectReferences(
    newestFirst: List<String>,
    open: Set<String>,
    notes: Map<String, ReferenceNote>,
    purposes: Map<String, Set<BoardPurpose>>,
    followed: Set<String>,
): AspectChoice {
    val allowed = { board: String -> followed.isEmpty() || board in followed }
    val purposeful = purposes.filterValues { it.isNotEmpty() }
    val inPurposedBoard = { name: String -> notes[name]?.board in purposeful }
    val inFollowedBoard = { name: String -> followed.isEmpty() || notes[name]?.board in followed }
    val general = companionReferences(newestFirst.filter { !inPurposedBoard(it) && inFollowedBoard(it) }, open, notes)
    val fromOpen = newestFirst.any { it in open }
    if (fromOpen) {
        val opened = companionReferences(newestFirst, open, notes).names
        return AspectChoice(opened, COMPARED_PURPOSES.associateWith { opened.indices.toList() }, emptyMap(), ReferenceScope.OPEN)
    }
    val chosen = LinkedHashMap<BoardPurpose, List<String>>()
    val boards = HashMap<BoardPurpose, List<String>>()
    COMPARED_PURPOSES.forEach { purpose ->
        val named = purposeful.filter { (board, uses) -> purpose in uses && allowed(board) }.keys
        val fromBoards = newestFirst.filter { notes[it]?.board in named }.take(COMPANION_REFERENCE_LIMIT)
        if (fromBoards.isNotEmpty()) boards[purpose] = named.filter { board -> fromBoards.any { notes[it]?.board == board } }.sorted()
        chosen[purpose] = fromBoards.ifEmpty { general.names }
    }
    val union = chosen.values.flatten().distinct()
    return AspectChoice(
        union,
        chosen.mapValues { (_, names) -> names.map(union::indexOf) },
        boards,
        if (boards.isEmpty()) general.scope else ReferenceScope.BOARDS,
    )
}

fun referenceBoards(notes: Map<String, ReferenceNote>): List<String> =
    notes.values.map { it.board }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

/** An empty [filter] keeps everything, [STARRED_FILTER] keeps stars, and others name a board. */
fun filterReferences(
    names: List<String>,
    notes: Map<String, ReferenceNote>,
    filter: String,
): List<String> = when (filter) {
    "" -> names
    STARRED_FILTER -> names.filter { notes[it]?.starred == true }
    else -> names.filter { notes[it]?.board == filter }
}

/** Decodes small previews away from the main thread, so large libraries open at once. */
class ReferenceThumbnails {
    private val worker = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun show(file: File, size: Int, view: ImageView) {
        val key = "${file.path}:${file.lastModified()}:$size"
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        worker.execute {
            val bitmap = decode(file, size) ?: return@execute
            cache.put(key, bitmap)
            view.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    fun close() = worker.shutdown()

    private fun decode(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** The actions for one reference, shared by the home screen and the studio. */
fun Activity.referenceOptions(
    store: ArtworkStore,
    projectId: String,
    file: File,
    changed: () -> Unit,
    moved: () -> Unit,
    delete: () -> Unit,
) {
    val note = store.referenceNotes(projectId)[file.name] ?: ReferenceNote()
    choiceSheet(
        tr("Reference"),
        listOf(
            SheetChoice(if (note.board.isEmpty()) tr("Move to a block") else tr("Change block"), R.drawable.ic_gallery) {
                chooseBoard(store, projectId, listOf(file), changed)
            },
            SheetChoice(if (note.starred) tr("Remove star") else tr("Add star"), R.drawable.ic_pin) {
                store.updateReferenceNote(projectId, file.name) { it.copy(starred = !it.starred) }
                changed()
            },
            SheetChoice(tr("Move to another sketchbook"), R.drawable.ic_copy) {
                chooseSketchbook(store, projectId, listOf(file), moved)
            },
            SheetChoice(tr("Delete"), R.drawable.ic_trash, danger = true) { delete() },
        ),
    )
}

fun Activity.chooseBoard(store: ArtworkStore, projectId: String, files: List<File>, changed: () -> Unit) {
    fun assign(board: String) {
        files.forEach { file -> store.updateReferenceNote(projectId, file.name) { it.copy(board = board) } }
        changed()
    }
    choiceSheet(
        tr("Block"),
        referenceBlocks(store.referenceNotes(projectId)).map { block -> SheetChoice(tr(block)) { assign(block) } } +
            SheetChoice(tr("New block")) { askSheet(tr("New block"), "", ::assign) } +
            SheetChoice(tr("Not in a block")) { assign("") },
    )
}

fun Activity.chooseSketchbook(store: ArtworkStore, projectId: String, files: List<File>, moved: () -> Unit) {
    val targets = (store.projects().map { it.id to it.name } + ("" to tr("Unsorted")))
        .filter { it.first != projectId }
    if (targets.isEmpty()) return
    AlertDialog.Builder(this)
        .setTitle(tr("Move to"))
        .setItems(targets.map { it.second }.toTypedArray()) { _, choice ->
            if (files.count { store.moveReference(it, projectId, targets[choice].first) } > 0) moved()
        }
        .show()
}
