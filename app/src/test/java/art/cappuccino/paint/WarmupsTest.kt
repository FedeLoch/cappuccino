package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupsTest {
    @Test
    fun warmupFollowsTheLatestNoteAndChangesByDay() {
        assertEquals("Values", warmupFor("Values", 0).topic)
        assertNotEquals(warmupFor("Values", 0), warmupFor("Values", 1))
        assertTrue(warmupFor(null, 3) in WARMUPS)
        assertTrue(warmupFor("Mood", 3) in WARMUPS)
    }

    @Test
    fun finishNoteNamesWhatGrew() {
        val first = ArtSnapshot(0, contrast = 0.2f, coverage = 0.1f, colored = 0f)
        assertTrue(finishNote(first, first.copy(contrast = 0.5f)).contains("values"))
        assertTrue(finishNote(first, first.copy(colored = 0.6f)).contains("color"))
        assertTrue(finishNote(first, first.copy(coverage = 0.8f)).contains("whole piece"))
        assertTrue(finishNote(first, first).startsWith("Finished!"))
    }

    private fun blank(color: Int = 0xFFFFFFFF.toInt()) = IntArray(64 * 64) { color }

    @Test
    fun straightLinesToTheDotsArePraised() {
        val line = FloatArray(40) { index -> if (index % 2 == 0) 0.10f + 0.52f * (index / 2) / 19f else 0.14f - 0.04f * (index / 2) / 19f }
        val note = warmupFeedback(WarmupKind.STRAIGHT_LINES, WarmupWork(listOf(line), blank(), 64, 64))
        assertTrue(note, note.contains("really straight") && note.contains("right on the dots"))
        val wavy = FloatArray(40) { index -> if (index % 2 == 0) 0.1f + 0.5f * (index / 2) / 19f else 0.5f + 0.05f * kotlin.math.sin((index / 2) * 1.3f) }
        assertTrue(wobble(wavy) > 0.03f)
    }

    @Test
    fun aCircleIsAnEvenEllipseAndALumpIsNot() {
        fun loop(bump: Float) = FloatArray(72) { index ->
            val angle = (index / 2) / 36f * 2f * Math.PI.toFloat()
            val radius = 0.2f * (1f + bump * kotlin.math.sin(angle * 5f))
            if (index % 2 == 0) 0.5f + radius * kotlin.math.cos(angle) else 0.5f + radius * 0.6f * kotlin.math.sin(angle)
        }
        assertTrue(ellipseUnevenness(loop(0f)) < 0.02f)
        assertTrue(ellipseUnevenness(loop(0.2f)) > 0.1f)
    }

    @Test
    fun threeBandsOfValueAreCounted() {
        val pixels = IntArray(64 * 64) { index ->
            when ((index % 64) / 22) {
                0 -> 0xFF101010.toInt()
                1 -> 0xFF808080.toInt()
                else -> 0xFFF0F0F0.toInt()
            }
        }
        assertEquals(3, valueBands(WarmupWork(emptyList(), pixels, 64, 64)))
        assertTrue(warmupFeedback(WarmupKind.THREE_VALUES, WarmupWork(emptyList(), pixels, 64, 64)).startsWith("Exactly three"))
    }

    @Test
    fun aWobblyLineIsMarked() {
        val wavy = FloatArray(40) { index -> if (index % 2 == 0) 0.1f + 0.5f * (index / 2) / 19f else 0.5f + 0.05f * kotlin.math.sin((index / 2) * 1.3f) }
        val marks = warmupMarks(WarmupKind.STRAIGHT_LINES, WarmupWork(listOf(wavy), blank(), 64, 64))
        assertTrue(marks.any { it.shape == MarkShape.LINE })
    }

    @Test
    fun silhouettesAreFoundAsSeparateShapes() {
        val pixels = blank()
        for (shape in 0 until 3) {
            for (y in 10 until 30) for (x in 4 + shape * 20 until 14 + shape * 20) pixels[y * 64 + x] = 0xFF000000.toInt()
        }
        assertEquals(3, darkShapes(WarmupWork(emptyList(), pixels, 64, 64)).size)
    }

    @Test
    fun anUntouchedGuideAsksYouToPaintFirst() {
        val note = warmupFeedback(WarmupKind.SPHERE_LIGHT, WarmupWork(emptyList(), blank(0xFFC6C5C3.toInt()), 64, 64, sunsFor(WarmupKind.SPHERE_LIGHT, 1)))
        assertTrue(note, note.contains("first"))
    }

    @Test
    fun eachSphereIsReadAgainstItsOwnSun() {
        val size = 192
        val right = SUN_SPOTS.first { it.name == "right" }
        val left = SUN_SPOTS.first { it.name == "left" }
        val top = SUN_SPOTS.first { it.name == "top" }
        val suns = listOf(right, left, top)
        val pixels = IntArray(size * size) { index ->
            val x = (index % size) / size.toFloat()
            val y = (index / size) / size.toFloat()
            val sphere = ((x - 0.05f) / 0.3f).toInt().coerceIn(0, 2)
            val cx = 0.2f + sphere * 0.3f
            val inside = kotlin.math.hypot(x - cx, y - 0.5f) < 0.11f
            val sun = suns[sphere]
            when {
                inside && (x - cx) * sun.x + (y - 0.5f) * sun.y > 0f -> 0xFFF0F0F0.toInt()
                inside -> 0xFF303030.toInt()
                y in 0.6f..0.66f -> 0xFF505050.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
        val good = warmupReview(WarmupKind.SPHERE_LIGHT, WarmupWork(emptyList(), pixels, size, size, suns))
        assertTrue(good.text, good.text.startsWith("Every form"))
        val flipped = warmupReview(WarmupKind.SPHERE_LIGHT, WarmupWork(emptyList(), pixels, size, size, listOf(left, left, top)))
        assertTrue(flipped.text, flipped.text.contains("left sphere"))
        assertTrue(flipped.marks.isNotEmpty())
    }

    @Test
    fun aFeatureAsksForBothTheLineAndTheShading() {
        val note = warmupFeedback(WarmupKind.FACE_LIPS, WarmupWork(emptyList(), blank(), 64, 64))
        assertTrue(note.contains("first"))
        val eye = WarmupWork(listOf(floatArrayOf(0.2f, 0.53f, 0.3f, 0.45f)), blank(), 64, 64, listOf(SUN_SPOTS.first { it.name == "left" }))
        assertTrue(warmupFeedback(WarmupKind.FACE_EYE, eye).contains("Keep going"))
    }
}
