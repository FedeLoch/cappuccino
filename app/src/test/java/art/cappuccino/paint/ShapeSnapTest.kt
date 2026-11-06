package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ShapeSnapTest {
    private fun wobblyLine(endX: Float, endY: Float) = FloatArray(82) { index ->
        val share = (index / 2) / 40f
        if (index % 2 == 0) endX * share else endY * share + sin(share * 20f) * 3f
    }

    private fun circle(radius: Float) = FloatArray(122) { index ->
        val turn = (index / 2) / 60.0 * 2 * PI
        val wobble = radius + sin(turn * 7).toFloat() * radius * 0.03f
        if (index % 2 == 0) 300f + wobble * cos(turn).toFloat() else 300f + wobble * sin(turn).toFloat()
    }

    private fun box(width: Float, height: Float): FloatArray {
        val points = ArrayList<Float>()
        val corners = listOf(0f to 0f, width to 0f, width to height, 0f to height, 0f to 0f)
        for (side in 0 until 4) {
            for (step in 0 until 20) {
                val share = step / 20f
                points += corners[side].first + (corners[side + 1].first - corners[side].first) * share
                points += corners[side].second + (corners[side + 1].second - corners[side].second) * share
            }
        }
        return points.toFloatArray()
    }

    @Test
    fun aShakyLineBecomesStraight() {
        val line = snapShape(wobblyLine(400f, 120f))!!
        val middle = line.size / 2
        assertEquals(200f, line[middle - 1], 2f)
        assertEquals(60f, line[middle], 2f)
    }

    @Test
    fun aNearlyLevelLineBecomesLevel() {
        val line = snapShape(wobblyLine(400f, 10f))!!
        assertEquals(line[1], line[line.size - 1], 0.01f)
    }

    @Test
    fun linesLeanTowardAVanishingPoint() {
        val line = snapShape(wobblyLine(400f, 90f), listOf(1000f, 280f))!!
        val endX = line[line.size - 2]
        val endY = line[line.size - 1]
        assertEquals(endY / endX, 280f / 1000f, 0.001f)
    }

    @Test
    fun aRoughCircleBecomesARoundShape() {
        val shape = snapShape(circle(100f))!!
        for (index in shape.indices step 2) {
            assertEquals(100f, hypot(shape[index] - 300f, shape[index + 1] - 300f), 6f)
        }
    }

    @Test
    fun aRoughBoxKeepsItsCorners() {
        val shape = snapShape(box(300f, 160f))!!
        val nearCorner = (shape.indices step 2).any { hypot(shape[it] - 300f, shape[it + 1] - 160f) < 3f }
        assertTrue(nearCorner)
    }

    @Test
    fun freeStrokesStayAsTheyAre() {
        val wave = FloatArray(202) { index ->
            val share = (index / 2) / 100f
            if (index % 2 == 0) share * 600f else sin(share * 12f) * 120f
        }
        assertNull(snapShape(wave))
        assertNotNull(snapShape(wobblyLine(400f, 120f)))
        assertNull(snapShape(floatArrayOf(0f, 0f, 2f, 2f, 3f, 3f, 4f, 4f)))
    }

    @Test
    fun aLineHandleMovesItsEndAndKeepsItStraight() {
        val line = floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f)
        val anchors = shapeAnchors(line)
        val moved = reshapeShape(line, anchors, floatArrayOf(0f, 0f, 0f, 200f))
        assertEquals(0f, moved[2], 0.01f)
        assertEquals(100f, moved[3], 0.01f)
        assertEquals(200f, moved[5], 0.01f)
    }

    @Test
    fun aClosedShapeStretchesBetweenItsBoxCorners() {
        val box = floatArrayOf(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f, 0f, 0f)
        val anchors = shapeAnchors(box)
        assertEquals(100f, anchors[2], 0.01f)
        val moved = reshapeShape(box, anchors, floatArrayOf(10f, 10f, 210f, 110f))
        assertEquals(210f, moved[2], 0.01f)
        assertEquals(110f, moved[5], 0.01f)
    }

    @Test
    fun aSnappedEllipseGetsItsBoxCornersAsHandles() {
        val ellipse = snapShape(circle(100f))!!
        val anchors = shapeAnchors(ellipse)
        assertTrue(anchors[2] - anchors[0] > 180f)
        assertTrue(anchors[3] - anchors[1] > 180f)
        val moved = reshapeShape(ellipse, anchors, floatArrayOf(anchors[0], anchors[1], anchors[2] + 100f, anchors[3]))
        val right = moved.filterIndexed { index, _ -> index % 2 == 0 }.max()
        assertEquals(anchors[2] + 100f, right, 1f)
    }
}
