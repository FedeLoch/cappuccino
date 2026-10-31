package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class PsdReaderTest {
    @Test
    fun readsBackWhatTheWriterProduces() {
        val width = 16
        val height = 12
        val red = ByteArray(width * height * 4)
        for (index in 0 until width * height) {
            if (index % 2 == 0) {
                red[index * 4] = -56
                red[index * 4 + 3] = -1
            }
        }
        val blue = ByteArray(width * height * 4) { if (it % 4 == 2 || it % 4 == 3) -1 else 0 }
        val layers = listOf(
            PsdLayer("Sketch", 0.5f, true, 1, false, red),
            PsdLayer("Color", 1f, false, 0, true, blue),
        )
        val output = ByteArrayOutputStream()
        PsdWriter(width, height).write(output, layers, blue)

        val image = PsdReader.read(output.toByteArray())
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        assertEquals(listOf("Sketch", "Color"), image.layers.map { it.name })
        assertEquals(1, image.layers[0].blend)
        assertEquals(0.5f, image.layers[0].opacity, 0.01f)
        assertEquals(false, image.layers[1].visible)
        assertEquals(true, image.layers[1].clipped)
        assertEquals(red.toList(), image.layers[0].rgba.toList())
    }

    @Test
    fun groupsSurviveARoundTrip() {
        val pixels = ByteArray(8 * 8 * 4)
        val plain = PsdLayer("Paper", 1f, true, 0, false, pixels)
        val hair = PsdLayer("Hair", 1f, true, 0, false, pixels)
        val face = PsdLayer("Face", 1f, true, 0, false, pixels)
        val groupOf = listOf(null, "Head", "Head")
        val layers = withPsdGroups(listOf(plain, hair, face), { groupOf[it] }, { psdFolder(it, 1f, true) })
        val output = ByteArrayOutputStream()
        PsdWriter(8, 8).write(output, layers, pixels)

        val image = PsdReader.read(output.toByteArray())
        assertEquals(listOf("Paper", "Hair", "Face"), image.layers.map { it.name })
        assertEquals(listOf("Head"), image.groups)
        assertEquals(listOf(0, 1, 1), image.layers.map { it.group })
    }
}
