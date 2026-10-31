package art.cappuccino.paint

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private data class Pointer(val id: Int, val x: Float, val y: Float)

@RunWith(AndroidJUnit4::class)
class CanvasInteractionTest {
    private val automation: UiAutomation =
        InstrumentationRegistry.getInstrumentation().uiAutomation
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var width = 0f
    private var height = 0f

    @Before
    fun openApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            width = it.window.decorView.width.toFloat()
            height = it.window.decorView.height.toFloat()
        }
        waitForPaper()
    }

    @After
    fun closeApp() = scenario.close()

    @Test
    fun everyStrokeReachesItsLastPoint() {
        val startY = height * 0.55f
        val endY = height * 0.45f
        repeat(5) { attempt ->
            val offset = attempt * 30f
            val endX = width * 0.8f
            stroke(width * 0.2f, startY + offset, endX, endY + offset)
            settle()
            assertTrue("stroke $attempt stopped early", hasInk(endX - 12f, endY + offset + 3f))
        }
    }

    @Test
    fun twoFingerTapUndoesAndThreeFingerTapRedoes() {
        val y = height * 0.5f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        assertTrue("stroke was not drawn", hasInk(width / 2f, y))

        tap(2)
        settle()
        assertFalse("undo left ink behind", hasInk(width / 2f, y))

        tap(3)
        settle()
        assertTrue("redo did not restore ink", hasInk(width / 2f, y))
    }

    @Test
    fun pinchZoomsTheCanvas() {
        val edgeX = width * 0.5f
        val probeY = paperTop() - 30f
        assertFalse("probe should start outside the paper", isPaper(edgeX, probeY))
        pinch()
        settle()
        assertTrue("pinch did not enlarge the paper", isPaper(edgeX, probeY))
    }

    private fun waitForPaper() {
        repeat(40) {
            val shot = screenshot()
            if (Color.red(shot.getPixel(shot.width / 2, shot.height / 2)) > 200) return
            SystemClock.sleep(250)
        }
        throw AssertionError("the canvas never appeared")
    }

    private fun paperTop(): Float {
        val shot = screenshot()
        val x = shot.width / 2
        return (0 until shot.height).first { Color.red(shot.getPixel(x, it)) > 100 }.toFloat()
    }

    private fun stroke(startX: Float, startY: Float, endX: Float, endY: Float) {
        val down = SystemClock.uptimeMillis()
        val steps = 40
        inject(MotionEvent.ACTION_DOWN, down, 0, listOf(Pointer(0, startX, startY)), stylus = true)
        for (step in 1..steps) {
            val t = step / steps.toFloat()
            val pointer = Pointer(0, startX + (endX - startX) * t, startY + (endY - startY) * t)
            inject(MotionEvent.ACTION_MOVE, down, step * 8L, listOf(pointer), stylus = true)
        }
        inject(
            MotionEvent.ACTION_UP, down, (steps + 1) * 8L,
            listOf(Pointer(0, endX, endY)), stylus = true,
        )
    }

    private fun tap(fingers: Int) {
        val down = SystemClock.uptimeMillis()
        val pointers = (0 until fingers).map {
            Pointer(it, width * (0.3f + it * 0.2f), height * 0.2f)
        }
        press(pointers, down)
        release(pointers, down)
    }

    private fun pinch() {
        val down = SystemClock.uptimeMillis()
        val centerY = height * 0.5f
        val start = listOf(Pointer(0, width * 0.4f, centerY), Pointer(1, width * 0.6f, centerY))
        press(start, down)
        var last = start
        for (step in 1..30) {
            val spread = width * 0.1f + width * 0.3f * step / 30f
            last = listOf(
                Pointer(0, width / 2f - spread, centerY),
                Pointer(1, width / 2f + spread, centerY),
            )
            inject(MotionEvent.ACTION_MOVE, down, step * 8L, last, stylus = false)
        }
        release(last, down)
    }

    private fun press(pointers: List<Pointer>, down: Long) {
        inject(MotionEvent.ACTION_DOWN, down, 0, pointers.take(1), stylus = false)
        for (count in 2..pointers.size) {
            val action = MotionEvent.ACTION_POINTER_DOWN or
                ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            inject(action, down, 5, pointers.take(count), stylus = false)
        }
    }

    private fun release(pointers: List<Pointer>, down: Long) {
        val elapsed = SystemClock.uptimeMillis() - down
        for (count in pointers.size downTo 2) {
            val action = MotionEvent.ACTION_POINTER_UP or
                ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            inject(action, down, elapsed, pointers.take(count), stylus = false)
        }
        inject(MotionEvent.ACTION_UP, down, elapsed, pointers.take(1), stylus = false)
    }

    private fun inject(
        action: Int,
        down: Long,
        offset: Long,
        pointers: List<Pointer>,
        stylus: Boolean,
    ) {
        val properties = pointers.map {
            PointerProperties().apply {
                id = it.id
                toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coords = pointers.map {
            PointerCoords().apply {
                x = it.x
                y = it.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val source = if (stylus) {
            InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        } else {
            InputDevice.SOURCE_TOUCHSCREEN
        }
        val event = MotionEvent.obtain(
            down, down + offset, action, pointers.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, source, 0,
        )
        assertTrue("event was not delivered", automation.injectInputEvent(event, true))
        event.recycle()
    }

    private fun settle() = SystemClock.sleep(1200)

    private fun screenshot(): Bitmap = requireNotNull(automation.takeScreenshot())

    private fun hasInk(x: Float, y: Float): Boolean {
        val shot = screenshot()
        for (dx in -8..8) {
            for (dy in -8..8) {
                if (Color.red(shot.getPixel(x.toInt() + dx, y.toInt() + dy)) < 100) return true
            }
        }
        return false
    }

    private fun isPaper(x: Float, y: Float) =
        Color.red(screenshot().getPixel(x.toInt(), y.toInt())) > 200
}
