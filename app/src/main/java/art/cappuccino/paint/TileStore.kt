package art.cappuccino.paint

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

const val TILE_SIZE = 256

/** Where one compressed tile lives: its pack file's number, and its place inside it. */
data class TileRef(val pack: Long, val offset: Long, val length: Int)

/** Pixels for part of a layer, whole rows of RGBA starting at [left], [top]. */
class LayerRegion(val left: Int, val top: Int, val width: Int, val height: Int, val pixels: ByteBuffer)

fun packName(number: Long) = "pack-$number.bin"

fun tilesAcross(width: Int) = (width + TILE_SIZE - 1) / TILE_SIZE

/** Grows a left, top, right, bottom rectangle to whole tiles, staying inside the canvas. */
fun alignToTiles(rect: IntArray, width: Int, height: Int) = intArrayOf(
    rect[0] / TILE_SIZE * TILE_SIZE,
    rect[1] / TILE_SIZE * TILE_SIZE,
    minOf(width, (rect[2] + TILE_SIZE - 1) / TILE_SIZE * TILE_SIZE),
    minOf(height, (rect[3] + TILE_SIZE - 1) / TILE_SIZE * TILE_SIZE),
)

/** Collects compressed tiles into one file, which appears only once it's complete. */
class PackWriter(private val dir: File, val number: Long) {
    private val temporary = File(dir, packName(number) + ".tmp")
    private var output: FileOutputStream? = null
    private var written = 0L

    fun add(bytes: ByteArray): TileRef {
        val stream = output ?: FileOutputStream(temporary).also { output = it }
        stream.write(bytes)
        return TileRef(number, written, bytes.size).also { written += bytes.size }
    }

    fun finish() {
        val stream = output ?: return
        stream.fd.sync()
        stream.close()
        check(temporary.renameTo(File(dir, packName(number)))) { "Couldn't finish the pack" }
    }
}

/**
 * Stores a tile-aligned region in [tiles], keyed by tile number. Empty tiles take no space, so
 * a tile that was erased is simply removed.
 */
fun writeRegion(
    region: LayerRegion,
    canvasWidth: Int,
    canvasHeight: Int,
    tiles: MutableMap<Int, TileRef>,
    pack: PackWriter,
) {
    val across = tilesAcross(canvasWidth)
    val deflater = Deflater(Deflater.BEST_SPEED)
    val chunk = ByteArray(1 shl 16)
    val packed = ByteArrayOutputStream()
    val pixels = region.pixels.duplicate().order(ByteOrder.nativeOrder())
    var top = region.top
    while (top < region.top + region.height) {
        val tileHeight = minOf(TILE_SIZE, canvasHeight - top)
        var left = region.left
        while (left < region.left + region.width) {
            val tileWidth = minOf(TILE_SIZE, canvasWidth - left)
            val bytes = ByteArray(tileWidth * tileHeight * 4)
            for (row in 0 until tileHeight) {
                pixels.position(((top - region.top + row) * region.width + left - region.left) * 4)
                pixels.get(bytes, row * tileWidth * 4, tileWidth * 4)
            }
            val number = top / TILE_SIZE * across + left / TILE_SIZE
            if (bytes.all { it == 0.toByte() }) {
                tiles.remove(number)
            } else {
                deflater.reset()
                deflater.setInput(bytes)
                deflater.finish()
                packed.reset()
                while (!deflater.finished()) packed.write(chunk, 0, deflater.deflate(chunk))
                tiles[number] = pack.add(packed.toByteArray())
            }
            left += tileWidth
        }
        top += tileHeight
    }
    deflater.end()
}

/** Rebuilds a whole layer. Anything without a tile stays transparent. */
fun readTiles(dir: File, tiles: Map<Int, TileRef>, canvasWidth: Int, canvasHeight: Int, into: ByteBuffer) {
    val across = tilesAcross(canvasWidth)
    val inflater = Inflater()
    tiles.entries.groupBy { it.value.pack }.forEach { (pack, entries) ->
        RandomAccessFile(File(dir, packName(pack)), "r").use { file ->
            for ((number, ref) in entries) {
                val left = number % across * TILE_SIZE
                val top = number / across * TILE_SIZE
                val tileWidth = minOf(TILE_SIZE, canvasWidth - left)
                val tileHeight = minOf(TILE_SIZE, canvasHeight - top)
                val packed = ByteArray(ref.length)
                file.seek(ref.offset)
                file.readFully(packed)
                val bytes = ByteArray(tileWidth * tileHeight * 4)
                inflater.reset()
                inflater.setInput(packed)
                var filled = 0
                while (filled < bytes.size && !inflater.finished()) {
                    filled += inflater.inflate(bytes, filled, bytes.size - filled)
                }
                check(filled == bytes.size) { "A tile is damaged" }
                for (row in 0 until tileHeight) {
                    into.position(((top + row) * canvasWidth + left) * 4)
                    into.put(bytes, row * tileWidth * 4, tileWidth * 4)
                }
            }
        }
    }
    inflater.end()
    into.rewind()
}
