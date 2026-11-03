package art.cappuccino.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationTest {
    @Test
    fun onlyTheCurrentFrameShowsUnlessOnionSkinIsOn() {
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 0f), frameShares(4, 1, onion = false), 0f)
        assertArrayEquals(floatArrayOf(ONION_SHARE, 1f, ONION_SHARE, 0f), frameShares(4, 1, onion = true), 0f)
        assertArrayEquals(floatArrayOf(1f, ONION_SHARE), frameShares(2, 0, onion = true), 0f)
    }

    @Test
    fun timelineLoopsThroughEveryFrame() {
        assertEquals(listOf(0, 1, 2, 0, 1, 2), animationTimeline(3, 2))
    }
}
