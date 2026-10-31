package art.cappuccino.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class TileStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val width = 600
    private val height = 300

    private fun blank(w: Int = width, h: Int = height) = ByteArray(w * h * 4)

    private fun paint(bytes: ByteArray, x: Int, y: Int) {
        for (channel in 0 until 4) bytes[(y * width + x) * 4 + channel] = (channel + 1).toByte()
    }

    private fun save(bytes: ByteArray, tiles: MutableMap<Int, TileRef>, number: Long) {
        val pack = PackWriter(folder.root, number)
        writeRegion(LayerRegion(0, 0, width, height, ByteBuffer.wrap(bytes)), width, height, tiles, pack)
        pack.finish()
    }

    private fun load(tiles: Map<Int, TileRef>): ByteArray {
        val buffer = ByteBuffer.allocate(width * height * 4)
        readTiles(folder.root, tiles, width, height, buffer)
        return buffer.array()
    }

    @Test
    fun aRoundTripKeepsEveryPixelIncludingShortEdgeTiles() {
        val image = blank()
        paint(image, 0, 0)
        paint(image, 599, 299)
        paint(image, 300, 260)
        val tiles = HashMap<Int, TileRef>()
        save(image, tiles, 1)
        assertArrayEquals(image, load(tiles))
    }

    @Test
    fun emptyAreasTakeNoSpace() {
        val image = blank()
        paint(image, 10, 10)
        val tiles = HashMap<Int, TileRef>()
        save(image, tiles, 1)
        assertEquals(setOf(0), tiles.keys)
        save(blank(), tiles, 2)
        assertTrue(tiles.isEmpty())
        assertFalse(File(folder.root, packName(2)).exists())
    }

    @Test
    fun aSmallChangeRewritesOnlyItsTile() {
        val image = blank()
        paint(image, 10, 10)
        paint(image, 400, 280)
        val tiles = HashMap<Int, TileRef>()
        save(image, tiles, 1)
        val untouched = tiles[3 + 1]

        val rect = alignToTiles(intArrayOf(20, 20, 30, 30), width, height)
        assertArrayEquals(intArrayOf(0, 0, 256, 256), rect)
        val region = blank(256, 256)
        region[(20 * 256 + 20) * 4 + 3] = 9
        val pack = PackWriter(folder.root, 2)
        writeRegion(LayerRegion(0, 0, 256, 256, ByteBuffer.wrap(region)), width, height, tiles, pack)
        pack.finish()

        assertEquals(2L, tiles[0]!!.pack)
        assertEquals(untouched, tiles[3 + 1])
        val result = load(tiles)
        assertEquals(9, result[(20 * width + 20) * 4 + 3].toInt())
        assertEquals(0, result[(10 * width + 10) * 4].toInt())
        assertEquals(1, result[(280 * width + 400) * 4].toInt())
    }

    @Test
    fun alignedAreasStopAtTheCanvasEdge() {
        assertArrayEquals(intArrayOf(512, 256, 600, 300), alignToTiles(intArrayOf(520, 260, 590, 299), width, height))
    }
}
