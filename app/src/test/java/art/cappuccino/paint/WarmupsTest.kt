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
        val layout = WarmupLayout(5)
        val (from, to) = layout.dots.first()
        val line = FloatArray(40) { index -> if (index % 2 == 0) from.x + (to.x - from.x) * (index / 2) / 19f else from.y + (to.y - from.y) * (index / 2) / 19f }
        val note = warmupFeedback(WarmupKind.STRAIGHT_LINES, WarmupWork(listOf(line), blank(), 64, 64, layout))
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
        val note = warmupFeedback(WarmupKind.SPHERE_LIGHT, WarmupWork(emptyList(), blank(0xFFC6C5C3.toInt()), 64, 64, WarmupLayout(1)))
        assertTrue(note, note.contains("first"))
    }

    /** Paints each sphere lit from [lightFrom], one sun per sphere, on white paper with dark cast shadows. */
    private fun litSpheres(layout: WarmupLayout, lightFrom: List<SunSpot>, size: Int) = IntArray(size * size) { index ->
        val x = (index % size) / size.toFloat()
        val y = (index / size) / size.toFloat()
        val sphere = layout.spheres.indexOfFirst { (cx, cy, r) -> kotlin.math.hypot(x - cx, y - cy) < r }
        when {
            sphere >= 0 -> {
                val (cx, cy) = layout.spheres[sphere]
                val sun = lightFrom[sphere]
                if ((x - cx) * sun.x + (y - cy) * sun.y > 0f) 0xFFF0F0F0.toInt() else 0xFF303030.toInt()
            }
            layout.spheres.any { (cx, cy, r) -> y in cy + r - 0.02f..cy + r + 0.06f && kotlin.math.abs(x - cx) < r * 2.2f } -> 0xFF505050.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    }

    @Test
    fun eachSphereIsReadAgainstItsOwnSun() {
        val size = 192
        val layout = WarmupLayout(11)
        val good = warmupReview(WarmupKind.SPHERE_LIGHT, WarmupWork(emptyList(), litSpheres(layout, layout.sphereSuns, size), size, size, layout))
        assertTrue(good.text, good.text.startsWith("Every form"))
        val wrong = layout.sphereSuns.mapIndexed { index, sun -> if (index == 0) SunSpot("opposite", -sun.x, -sun.y) else sun }
        val flipped = warmupReview(WarmupKind.SPHERE_LIGHT, WarmupWork(emptyList(), litSpheres(layout, wrong, size), size, size, layout))
        assertTrue(flipped.text, flipped.text.contains("left sphere"))
        assertTrue(flipped.marks.isNotEmpty())
    }

    @Test
    fun everySessionLaysOutTheExerciseFresh() {
        val first = WarmupLayout(1)
        val second = WarmupLayout(2)
        assertNotEquals(first.dots, second.dots)
        assertNotEquals(first.tracePaths.first(), second.tracePaths.first())
        listOf(first, second, WarmupLayout(99)).forEach { layout ->
            assertTrue(layout.dots.size >= 4)
            assertTrue(layout.figures.size in 4..6)
            layout.dots.flatMap { listOf(it.first, it.second) }.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
            for (boxes in listOf(layout.hatchBoxes, layout.ellipseBoxes)) {
                assertTrue(boxes.size >= 3)
                boxes.forEach { a ->
                    boxes.filter { it !== a }.forEach { b -> assertTrue(a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1]) }
                }
            }
            layout.figures.forEach { figure -> assertTrue(figure.box[0] > -0.05f && figure.box[2] < 1.05f) }
        }
    }

    @Test
    fun aFeatureAsksForBothTheLineAndTheShading() {
        val note = warmupFeedback(WarmupKind.FACE_LIPS, WarmupWork(emptyList(), blank(), 64, 64))
        assertTrue(note.contains("first"))
        val eye = WarmupWork(listOf(floatArrayOf(0.2f, 0.53f, 0.3f, 0.45f)), blank(), 64, 64, WarmupLayout(3))
        assertTrue(warmupFeedback(WarmupKind.FACE_EYE, eye).contains("Keep going"))
    }

    @Test
    fun edgesAimedAtTheVanishingPointsArePraised() {
        val layout = WarmupLayout(8)
        val corner = layout.corners.first()
        fun toward(target: Spot) = floatArrayOf(corner[0], corner[1], corner[0] + (target.x - corner[0]) * 0.4f, corner[1] + (target.y - corner[1]) * 0.4f)
        val aimed = WarmupWork(listOf(toward(layout.vanishLeft), toward(layout.vanishRight)), blank(), 64, 64, layout)
        assertTrue(warmupFeedback(WarmupKind.PERSPECTIVE, aimed).startsWith("Your edges run right"))
        val off = WarmupWork(listOf(floatArrayOf(0.3f, 0.8f, 0.6f, 0.95f)), blank(), 64, 64, layout)
        assertTrue(warmupMarks(WarmupKind.PERSPECTIVE, off).isNotEmpty())
    }

    @Test
    fun aSmoothBlendIsPraisedAndABandIsMarked() {
        val layout = WarmupLayout(4)
        val box = layout.blendBox
        val horizontal = box[2] - box[0] > box[3] - box[1]
        val size = 128
        fun paint(banded: Boolean) = IntArray(size * size) { index ->
            val x = (index % size) / size.toFloat()
            val y = (index / size) / size.toFloat()
            if (x !in box[0]..box[2] || y !in box[1]..box[3]) return@IntArray 0xFFFFFFFF.toInt()
            var t = if (horizontal) (x - box[0]) / (box[2] - box[0]) else (y - box[1]) / (box[3] - box[1])
            if (!layout.blendDarkFirst) t = 1f - t
            if (banded) t = if (t < 0.5f) 0.1f else 0.9f
            val level = (30 + t * 200).toInt()
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }
        assertTrue(warmupFeedback(WarmupKind.GRADIENT, WarmupWork(emptyList(), paint(false), size, size, layout)).startsWith("A smooth"))
        assertTrue(warmupMarks(WarmupKind.GRADIENT, WarmupWork(emptyList(), paint(true), size, size, layout)).isNotEmpty())
    }

    @Test
    fun anExactMirrorMatches() {
        val layout = WarmupLayout(6)
        val mirrored = layout.mirrorHalf.flatMap { listOf(1f - it.x, it.y) }.toFloatArray()
        assertTrue(warmupFeedback(WarmupKind.MIRROR, WarmupWork(listOf(mirrored), blank(), 64, 64, layout)).startsWith("The two halves match"))
    }
}
