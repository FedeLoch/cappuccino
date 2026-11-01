package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerTest {
    private val size = 60

    private fun drawing(solid: (Int, Int) -> Boolean) =
        ByteArray(size * size) { if (solid(it % size, it / size)) -1 else 0 }

    private fun filled(shape: ByteArray, x: Int, y: Int) = shape[y * size + x].toInt() != 0

    @Test
    fun anEmptyDrawingMakesNoSticker() {
        assertNull(stickerShape(ByteArray(size * size), size, size, 5f))
    }

    @Test
    fun theBorderGrowsAroundTheDrawingByItsSize() {
        val shape = stickerShape(drawing { x, y -> x == 30 && y == 30 }, size, size, 5f)!!
        assertTrue(filled(shape, 34, 30))
        assertTrue(!filled(shape, 37, 30))
        assertTrue(!filled(shape, 30, 37))
    }

    @Test
    fun holesInsideTheDrawingAreFilled() {
        val ring = drawing { x, y ->
            val distance = (x - 30) * (x - 30) + (y - 30) * (y - 30)
            distance in 324..400
        }
        val shape = stickerShape(ring, size, size, 2f)
        assertNotNull(shape)
        assertEquals(-1, shape!![30 * size + 30].toInt())
        assertTrue(!filled(shape, 2, 2))
    }
}
