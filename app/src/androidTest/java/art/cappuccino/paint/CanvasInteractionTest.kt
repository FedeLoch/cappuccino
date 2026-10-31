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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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

    private fun onStudio(action: (MainActivity) -> Unit) {
        scenario.onActivity(action)
        settle()
    }

    private fun layerCount(): Int {
        var count = 0
        scenario.onActivity { count = it.layerCount }
        return count
    }

    @Test
    fun undoStepsThroughStrokesAndLayerChanges() {
        val firstY = height * 0.45f
        val secondY = height * 0.55f
        val x = width / 2f
        stroke(width * 0.2f, firstY, width * 0.8f, firstY)
        settle()
        onStudio { it.addLayer() }
        stroke(width * 0.2f, secondY, width * 0.8f, secondY)
        settle()
        assertTrue("second stroke was not drawn", hasInk(x, secondY))

        onStudio { it.deleteLayer(it.activeLayer()!!) }
        assertFalse("deleted layer is still visible", hasInk(x, secondY))
        assertTrue("first layer should remain", hasInk(x, firstY))

        tap(2)
        settle()
        assertEquals(2, layerCount())
        assertTrue("undo did not restore the deleted layer", hasInk(x, secondY))

        tap(2)
        settle()
        assertFalse("undo did not remove the stroke", hasInk(x, secondY))

        tap(2)
        settle()
        assertEquals("undo did not remove the added layer", 1, layerCount())
        assertTrue("first stroke should remain", hasInk(x, firstY))

        tap(3)
        settle()
        assertEquals("redo did not add the layer back", 2, layerCount())
        tap(3)
        settle()
        assertTrue("redo did not restore the stroke", hasInk(x, secondY))
    }

    @Test
    fun undoRestoresLayersAfterMergeDown() {
        val firstY = height * 0.45f
        val secondY = height * 0.55f
        val x = width / 2f
        stroke(width * 0.2f, firstY, width * 0.8f, firstY)
        settle()
        onStudio { it.addLayer() }
        stroke(width * 0.2f, secondY, width * 0.8f, secondY)
        settle()

        onStudio { it.mergeDown(it.activeLayer()!!) }
        assertEquals(1, layerCount())
        assertTrue("merge lost the upper stroke", hasInk(x, secondY))
        assertTrue("merge lost the lower stroke", hasInk(x, firstY))

        tap(2)
        settle()
        assertEquals("undo did not separate the layers", 2, layerCount())
        assertTrue(hasInk(x, secondY))
        tap(2)
        settle()
        assertFalse("the lower layer kept the merged stroke", hasInk(x, secondY))
        assertTrue(hasInk(x, firstY))
    }

    @Test
    fun fillPaintsAnAreaAndCanBeUndone() {
        val x = width / 2f
        val y = height / 2f
        onStudio {
            it.setColor(Color.BLACK)
            it.selectTool(BrushKind.FILL)
        }
        tapPen(x, y)
        repeat(20) { if (!hasInk(x, y)) SystemClock.sleep(500) }
        assertTrue("fill didn't paint the empty layer", hasInk(x, y))
        tap(2)
        settle()
        assertFalse("undo didn't remove the fill", hasInk(x, y))
    }

    @Test
    fun clippedLayerOnlyShowsOverTheLayerBelow() {
        val lineY = height / 2f
        val crossX = width * 0.6f
        val offLineY = lineY + 60f
        stroke(width * 0.2f, lineY, width * 0.8f, lineY)
        settle()
        onStudio {
            it.addLayer()
            it.setClip(it.activeLayer()!!, true)
        }
        stroke(crossX, lineY - 90f, crossX, lineY + 90f)
        settle()
        assertFalse("clipped stroke leaked past the layer below", hasInk(crossX, offLineY))
        onStudio { it.setClip(it.activeLayer()!!, false) }
        assertTrue("unclipped stroke should show", hasInk(crossX, offLineY))
    }

    @Test
    fun transformMovesALayerAndCanBeUndone() {
        val y = height * 0.45f
        val movedY = y + 120f
        val x = width / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio { it.startTransform() }
        stroke(x, height * 0.3f, x, height * 0.3f + 120f)
        settle()
        onStudio { it.finishTransform(keep = true) }
        assertTrue("layer didn't move", hasInk(x, movedY))
        assertFalse("old position should be empty", hasInk(x, y))
        tap(2)
        settle()
        assertTrue("undo didn't restore the position", hasInk(x, y))
    }

    private val leftHalf = floatArrayOf(
        0f, 0f,
        DEFAULT_CANVAS_WIDTH / 2f, 0f,
        DEFAULT_CANVAS_WIDTH / 2f, DEFAULT_CANVAS_HEIGHT.toFloat(),
        0f, DEFAULT_CANVAS_HEIGHT.toFloat(),
    )

    @Test
    fun selectionKeepsPaintInside() {
        val y = height / 2f
        onStudio { it.finishLasso(leftHalf) }
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        assertTrue("paint inside was lost", hasInk(width * 0.3f, y))
        assertFalse("paint leaked outside", hasInk(width * 0.7f, y))
    }

    @Test
    fun cutToLayerMovesPixelsAndUndoRestoresThem() {
        val y = height / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio { it.finishLasso(leftHalf) }
        onStudio { it.selectionToLayer(cut = true, transform = false) }
        assertEquals(2, layerCount())
        assertTrue("cut pixels should show on the new layer", hasInk(width * 0.3f, y))
        onStudio { it.deleteLayer(it.activeLayer()!!) }
        assertFalse("pixels were copied, not cut", hasInk(width * 0.3f, y))
        assertTrue("uncut pixels should stay", hasInk(width * 0.7f, y))
        tap(2)
        settle()
        tap(2)
        settle()
        assertEquals(1, layerCount())
        assertTrue("undo should restore cut pixels", hasInk(width * 0.3f, y))
    }

    @Test
    fun gradientPaintsAndCanBeUndone() {
        val y = height / 2f
        onStudio {
            it.setColor(Color.BLACK)
            it.selectTool(BrushKind.GRADIENT)
        }
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        settle()
        assertTrue("gradient start should be dark", hasInk(width * 0.22f, y))
        assertFalse("gradient end should fade away", hasInk(width * 0.75f, y))
        tap(2)
        settle()
        assertFalse("undo didn't remove the gradient", hasInk(width * 0.22f, y))
    }

    @Test
    fun smudgePullsColorAcrossTheCanvas() {
        val y = height / 2f
        val x = width / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        val spot = y + 30f
        assertFalse("spot should start clean", isTinted(x, spot))
        onStudio { it.selectTool(BrushKind.SMUDGE) }
        stroke(x, y - 40f, x, y + 80f)
        settle()
        assertTrue("smudge didn't pull color", isTinted(x, spot))
    }

    @Test
    fun hiddenGroupHidesItsLayers() {
        val y = height / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio {
            it.addLayer()
            it.toggleGroup(it.activeLayer()!!)
            it.hideActiveGroup()
        }
        assertFalse("group didn't hide its layers", hasInk(width / 2f, y))
        tap(2)
        settle()
        assertTrue("undo didn't show the group", hasInk(width / 2f, y))
    }

    @Test
    fun adjustmentChangesPixelsAndCanBeUndone() {
        val y = height / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio { it.applyAdjustment(floatArrayOf(0f, 1f, 0.5f, 0f, 0f, 0f)) }
        assertFalse("brightness didn't change the stroke", hasInk(width / 2f, y))
        tap(2)
        settle()
        assertTrue("undo didn't restore the stroke", hasInk(width / 2f, y))
    }

    private fun isTinted(x: Float, y: Float): Boolean {
        val shot = screenshot()
        for (dx in -4..4) {
            for (dy in -4..4) {
                if (Color.red(shot.getPixel(x.toInt() + dx, y.toInt() + dy)) < 235) return true
            }
        }
        return false
    }

    @Test
    fun holdingThePenPicksAColorWithoutDrawing() {
        val x = width / 2f
        val y = height / 2f
        onStudio { it.setColor(Color.BLACK) }
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, 0, listOf(Pointer(0, x, y)), stylus = true)
        SystemClock.sleep(900)
        settle()
        inject(MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis() - down, listOf(Pointer(0, x, y)), stylus = true)
        settle()
        var color = 0
        scenario.onActivity { color = it.currentColor }
        assertEquals("the paper color should be picked", Color.WHITE, color)
        assertFalse("holding should leave no mark", hasInk(x, y))
    }

    @Test
    fun backupRestoresProjects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ArtworkStore(context)
        val project = store.createProject("Backup test")
        val file = ByteArrayOutputStream()
        store.backup(file)
        store.deleteProject(project.id)
        assertFalse(store.projects().any { it.id == project.id })
        store.restore(ByteArrayInputStream(file.toByteArray()))
        assertTrue("restore lost the project", store.projects().any { it.name == "Backup test" })
        store.deleteProject(project.id)
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
        return (shot.height / 4 until shot.height).first { Color.red(shot.getPixel(x, it)) > 100 }.toFloat()
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

    private fun tapPen(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, 0, listOf(Pointer(0, x, y)), stylus = true)
        inject(MotionEvent.ACTION_UP, down, 20, listOf(Pointer(0, x, y)), stylus = true)
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
