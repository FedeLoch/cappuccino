package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ProToolsTest {
    @Test
    fun zeroStabilizerFollowsThePen() {
        val stabilizer = LineStabilizer().apply { reset(0f) }
        val point = floatArrayOf(0f, 0f).also(stabilizer::next)
        point[0] = 100f
        stabilizer.next(point)
        assertEquals(100f, point[0], 0f)
    }

    @Test
    fun stabilizerSmoothsAJump() {
        val stabilizer = LineStabilizer().apply { reset(1f) }
        stabilizer.next(floatArrayOf(0f, 0f))
        val point = floatArrayOf(100f, 0f).also(stabilizer::next)
        assertTrue(point[0] < 20f)
    }

    @Test
    fun psdStartsWithAValidHeaderAndWritesToFile() {
        val width = 8
        val height = 6
        val rgba = ByteArray(width * height * 4) { if (it % 4 == 3) -1 else 40 }
        val bytes = ByteArrayOutputStream()
        PsdWriter(width, height).write(bytes, listOf(PsdLayer("Layer 1", 1f, true, 0, false, rgba)), rgba)
        val data = bytes.toByteArray()
        assertEquals("8BPS", String(data, 0, 4))
        System.getProperty("psd.output")?.let { File(it).writeBytes(data) }
    }
}
