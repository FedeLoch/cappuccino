package art.cappuccino.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CmykTest {
    @Test
    fun colorsBreakDownIntoInks() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), rgbInks(255, 255, 255), 0.01f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), rgbInks(0, 0, 0), 0.01f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), rgbInks(0, 255, 255), 0.01f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0.5f), rgbInks(128, 0, 0), 0.01f)
    }
}
