package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSheetTest {
    @Test
    fun framesStayInsideTheCanvasWithoutTouching() {
        val frames = thumbnailFrames(3000, 2000)
        assertEquals(6, frames.size)
        assertTrue(frames.all { it[0] > 0f && it[1] > 0f && it[2] < 3000f && it[3] < 2000f })
        assertTrue(frames[0][2] < frames[1][0])
        assertTrue(frames[0][3] < frames[3][1])
        assertEquals(frames[0][2] - frames[0][0], frames[5][2] - frames[5][0], 0.01f)
    }

    @Test
    fun variationsCountThroughTheAlphabet() {
        assertEquals("A", variationLetter(0))
        assertEquals("Z", variationLetter(25))
        assertEquals("AA", variationLetter(26))
    }

    @Test
    fun hueTurnsThroughTheWheel() {
        val red = 0xFFFF0000.toInt()
        assertEquals(0xFF00FF00.toInt(), shiftHue(intArrayOf(red), 1f / 3f)[0])
        assertEquals(0xFF0000FF.toInt(), shiftHue(intArrayOf(red), 2f / 3f)[0])
        assertEquals(0xFF00FFFF.toInt(), shiftHue(intArrayOf(red), 0.5f)[0])
    }

    @Test
    fun grayAndAlphaStayTheSame() {
        val gray = 0x80777777.toInt()
        assertEquals(gray, shiftHue(intArrayOf(gray), 0.4f)[0])
        assertEquals(0x80, shiftHue(intArrayOf(0x80FF0000.toInt()), 0.5f)[0] ushr 24)
    }
}
