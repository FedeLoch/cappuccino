package art.cappuccino.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureTest {
    private val width = 3000
    private val height = 3000

    @Test
    fun theStandingFigureReadsWell() {
        val notes = analyzeBody(defaultBody(width, height))
        assertTrue(notes.map { it.text + it.values }.toString(), notes.none { it.warning })
        assertTrue(notes.map { it.text + it.values }.toString(), notes.any { it.text == "The figure is %1 heads tall." && it.values == listOf("7.4") })
    }

    @Test
    fun aLongArmIsNoted() {
        val body = defaultBody(width, height)
        // Drag the wrist on the right far down, stretching the forearm.
        moveJoint(body, 5, 0f, 400f)
        val notes = analyzeBody(body)
        val long = notes.first { it.text.contains("so it reads long") }
        assertEquals("forearm on the right", long.values[0])
        assertTrue(5 in long.joints)
    }

    @Test
    fun movingAJointCarriesWhatHangsFromIt() {
        val body = defaultBody(width, height)
        val before = body.copyOf()
        moveJoint(body, 3, 50f, 0f)
        assertEquals(before[3 * 2] + 50f, body[3 * 2], 0.01f)
        assertEquals(before[5 * 2] + 50f, body[5 * 2], 0.01f)
        assertEquals(before[6 * 2], body[6 * 2], 0.01f)
        moveJoint(body, PELVIS, 0f, 20f)
        assertTrue((0 until BODY_JOINTS).all { body[it * 2 + 1] != before[it * 2 + 1] })
    }

    @Test
    fun aLeaningFigureIsNotedAsTipping() {
        val body = defaultBody(width, height)
        for (joint in listOf(12, 15)) body[joint * 2] += 900f
        assertTrue(analyzeBody(body).any { it.text.startsWith("The neck isn't above the feet") })
    }

    @Test
    fun theFaceGuideReadsInProportionUntilTheEyesMove() {
        val face = defaultFace(width, height)
        assertEquals(listOf("The face reads in proportion."), analyzeFace(face).map { it.text })
        face[3] -= 300f
        face[5] -= 300f
        face[7] -= 300f
        assertTrue(analyzeFace(face).any { it.text.startsWith("The eyes sit high") })
    }

    @Test
    fun aShortArmLiftsTowardYou() {
        val body = defaultBody(width, height)
        // Shorten the forearm on the right by pulling the wrist up to just under the elbow.
        body[5 * 2 + 1] = body[4 * 2 + 1] + 40f
        val lifted = liftBody(body)
        assertTrue(lifted[5 * 3 + 2] > lifted[4 * 3 + 2] + 0.5f)
        assertFalse(lifted.any { it.isNaN() })
    }

    @Test
    fun aTurnedFaceReadsAsTurned() {
        val face = defaultFace(width, height)
        val (still, _) = faceTurn(face)
        assertEquals(0f, still, 0.01f)
        face[8] += 200f
        face[10] += 200f
        assertTrue(faceTurn(face).first > 0.3f)
    }

    @Test
    fun lightDirectionsReadByName() {
        val slider = FILTERS.first { it.id == LIGHT_FILTER }.sliders.first()
        assertEquals("upper left", filterValueText(slider, 2.36f))
        assertEquals("right", filterValueText(slider, 6.2f))
        assertEquals(listOf(0.6f, 2f), engineFilterValues(FILTERS.first { it.id == LIGHT_FILTER }, floatArrayOf(2f, 0.6f, 60f, 0.8f)).let { listOf(it[1], it[3]) })
    }
}
