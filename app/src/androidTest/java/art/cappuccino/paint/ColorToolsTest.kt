package art.cappuccino.paint

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ColorToolsTest {
    private fun hue(color: Int) = FloatArray(3).also { Color.colorToHSV(color, it) }[0]

    @Test
    fun complementaryIsOppositeOnTheWheel() {
        val colors = harmoniesFor(Color.RED).first { it.name == "Complementary" }.colors
        assertEquals(180f, abs(hue(colors[0]) - hue(colors[1])), 1f)
    }

    @Test
    fun valueScaleRunsFromDarkToLight() {
        val scale = valueScale(Color.BLUE)
        assertTrue(Color.luminance(scale.first()) < Color.luminance(scale.last()))
    }

    @Test
    fun extractionFindsTheMainColorsAndSkipsPaper() {
        val pixels = IntArray(900) {
            when {
                it < 300 -> Color.RED
                it < 600 -> Color.BLUE
                else -> Color.WHITE
            }
        }
        val colors = extractPalette(pixels, 2)
        assertEquals(setOf(Color.RED, Color.BLUE), colors.toSet())
    }
}
