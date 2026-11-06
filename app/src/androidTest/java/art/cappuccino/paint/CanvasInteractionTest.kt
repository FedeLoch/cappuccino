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

private const val WARMUP_SIZE_FOR_TEST = 2048f

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
    fun quickTapsInARowAndHoldingKeepUndoing() {
        val rows = (0 until 5).map { height * (0.4f + it * 0.05f) }
        val x = width / 2f
        for (y in rows) {
            stroke(width * 0.2f, y, width * 0.8f, y)
            settle()
            assertTrue("stroke was not drawn", hasInk(x, y))
        }
        val a = Pointer(0, width * 0.3f, height * 0.2f)
        val b = Pointer(1, width * 0.5f, height * 0.2f)
        val c = Pointer(2, width * 0.4f, height * 0.25f)
        val d = Pointer(3, width * 0.6f, height * 0.25f)
        fun pointerAction(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        // The second tap lands while a finger from the first is still resting.
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, 0, listOf(a), stylus = false)
        inject(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), down, 5, listOf(a, b), stylus = false)
        inject(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), down, 40, listOf(a, b), stylus = false)
        inject(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), down, 60, listOf(a, c), stylus = false)
        inject(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 2), down, 65, listOf(a, c, d), stylus = false)
        inject(pointerAction(MotionEvent.ACTION_POINTER_UP, 0), down, 90, listOf(a, c, d), stylus = false)
        inject(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), down, 100, listOf(c, d), stylus = false)
        inject(MotionEvent.ACTION_UP, down, 100, listOf(c), stylus = false)
        settle()
        assertFalse("first quick tap did not undo", hasInk(x, rows[4]))
        assertFalse("second quick tap did not undo", hasInk(x, rows[3]))
        assertTrue("quick taps undid too much", hasInk(x, rows[2]))

        // Holding two fingers still keeps undoing.
        val hold = SystemClock.uptimeMillis()
        press(listOf(a, b), hold)
        SystemClock.sleep(900)
        release(listOf(a, b), hold)
        settle()
        assertFalse("holding did not keep undoing", hasInk(x, rows[1]))
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
        repeat(20) { if (hasInk(x, y)) SystemClock.sleep(500) }
        assertFalse("undo didn't remove the fill", hasInk(x, y))
        tap(3)
        repeat(20) { if (!hasInk(x, y)) SystemClock.sleep(500) }
        assertTrue("redo didn't bring the fill back", hasInk(x, y))
    }

    @Test
    fun aFingerTapFillsToo() {
        val x = width * 0.3f
        val y = height * 0.2f
        onStudio {
            it.setColor(Color.BLACK)
            it.selectTool(BrushKind.FILL)
        }
        tap(1)
        repeat(20) { if (!hasInk(x, y)) SystemClock.sleep(500) }
        assertTrue("a finger tap didn't fill", hasInk(x, y))
    }

    @Test
    fun aFingerHoldClosesThePickRingEvenWhenLiftedStraightAway() {
        repeat(6) {
            val down = SystemClock.uptimeMillis()
            val finger = listOf(Pointer(0, width / 2f, height / 2f))
            inject(MotionEvent.ACTION_DOWN, down, 0, finger, stylus = false)
            SystemClock.sleep(510)
            inject(MotionEvent.ACTION_UP, down, 510, finger, stylus = false)
        }
        settle()
        var ringShowing = true
        onStudio { ringShowing = it.pickRingShowing }
        assertFalse("the pick ring stayed on screen", ringShowing)
    }

    @Test
    fun pickingFromAReferenceShowsTheRingUntilItEnds() {
        var ringShowing = false
        onStudio {
            it.onReferencePick(Color.RED, 300f, 300f)
            ringShowing = it.pickRingShowing
        }
        assertTrue("the ring should show while picking", ringShowing)
        onStudio {
            it.onReferencePickEnd()
            ringShowing = it.pickRingShowing
        }
        assertFalse("the ring should close when the pick ends", ringShowing)
        var color = 0
        scenario.onActivity { color = it.currentColor }
        assertEquals("the reference color should be used", Color.RED, color)
    }

    @Test
    fun newBlendModesLeaveTheCanvasAloneWhereTheLayerIsClear() {
        val y = height / 2f
        onStudio { it.addLayer() }
        listOf(4, 5, 6, 7, 8).forEach { blend ->
            onStudio { it.setBlend(it.activeLayer()!!, blend) }
            settle()
            assertFalse("blend $blend changed the canvas through clear pixels", hasInk(width / 2f, y))
        }
        onStudio { it.setBlend(it.activeLayer()!!, 4) }
        stroke(width * 0.3f, y, width * 0.7f, y)
        settle()
        assertTrue("darken should show a dark stroke", hasInk(width / 2f, y))
    }

    @Test
    fun editingTextReplacesItAndKeepsItOnTheCanvas() {
        onStudio {
            it.setColor(Color.BLACK)
            it.addText("Hero", 0.2f)
        }
        settle()
        onStudio { it.finishTransform(true) }
        settle()
        var first = 0
        onStudio {
            first = it.activeLayer()!!.id
            it.replaceText(it.activeLayer()!!, "Villain", 0.2f)
        }
        var text: String? = null
        repeat(20) {
            onStudio { text = it.activeLayer()?.text }
            if (text != "Villain") SystemClock.sleep(300)
        }
        assertEquals("Villain", text)
        var second = 0
        onStudio { second = it.activeLayer()!!.id }
        assertTrue("the text should sit on a new layer so undo can bring it back", first != second)
    }

    @Test
    fun anImportedImageBecomesANewLayerThatCanBeUndone() {
        var before = 0
        onStudio {
            before = it.layerCount
            val image = android.graphics.Bitmap.createBitmap(1200, 1200, android.graphics.Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.BLACK)
            it.addImage(image, "Photo")
        }
        settle()
        onStudio { it.finishTransform(true) }
        settle()
        var name: String? = null
        var after = 0
        onStudio {
            name = it.activeLayer()?.name
            after = it.layerCount
        }
        assertEquals("Photo", name)
        assertEquals(before + 1, after)
        repeat(20) { if (!hasInk(width / 2f, height / 2f)) SystemClock.sleep(300) }
        assertTrue("the image should show on the canvas", hasInk(width / 2f, height / 2f))
    }

    @Test
    fun aStickerIsANewTopLayerHoldingTheDrawing() {
        val y = height / 2f
        stroke(width * 0.3f, y, width * 0.7f, y)
        settle()
        var before = 0
        onStudio {
            before = it.layerCount
            it.openStickerPanel()
        }
        var ready = false
        repeat(40) {
            onStudio { ready = it.stickerReady }
            if (!ready) SystemClock.sleep(200)
        }
        assertTrue("the sticker panel should read the drawing", ready)
        onStudio { it.makeSticker(export = false) }
        var made = false
        repeat(60) {
            onStudio { made = it.activeLayer()?.sticker == true }
            if (!made) SystemClock.sleep(250)
        }
        assertTrue("the sticker should be the active layer", made)
        var count = 0
        var topIsSticker = false
        onStudio {
            count = it.layerCount
            topIsSticker = it.topLayer()?.sticker == true
        }
        assertEquals(before + 1, count)
        assertTrue("the sticker should sit on top", topIsSticker)
        onStudio { it.deleteLayerBelowForTest() }
        settle()
        repeat(20) { if (!hasInk(width / 2f, y)) SystemClock.sleep(300) }
        assertTrue("the sticker layer should hold the drawing itself", hasInk(width / 2f, y))
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

    private fun darkestRed(x: Float, y: Float): Int {
        val shot = screenshot()
        return (-8..8).minOf { dy -> Color.red(shot.getPixel(x.toInt(), y.toInt() + dy)) }
    }

    @Test
    fun aSoftEdgeFadesPaintAcrossTheSelectionBorder() {
        var border = 0f
        onStudio {
            it.setColor(Color.BLACK)
            it.setBrushSize(120f)
            it.finishLasso(leftHalf)
            it.featherSelection(160)
            border = it.canvasToScreen(DEFAULT_CANVAS_WIDTH / 2f, 0f)[0]
        }
        val y = height / 2f
        stroke(width * 0.15f, y, width * 0.85f, y)
        settle()
        assertTrue("paint well inside should be solid", darkestRed(border - width * 0.2f, y) < 60)
        val edge = darkestRed(border, y)
        assertTrue("the border should be half painted, not $edge", edge in 60..230)
        // The area outside a selection is tinted, so it's compared with an unpainted spot beside it.
        val outside = darkestRed(border + width * 0.2f, y)
        val unpainted = darkestRed(border + width * 0.2f, y - height * 0.15f)
        assertTrue("paint well outside should stay clear, not $outside against $unpainted", outside >= unpainted - 6)
    }

    @Test
    fun distortBendsOneSideOfALayer() {
        val y = height * 0.45f
        onStudio {
            it.setColor(Color.BLACK)
            it.setBrushSize(40f)
        }
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio { it.startTransform() }
        settle()
        onStudio {
            it.bendTransform(1, 0f, 160f)
            it.bendTransform(2, 0f, 160f)
        }
        onStudio { it.finishTransform(keep = true) }
        assertTrue("the left end should stay put", hasInk(width * 0.22f, y))
        assertFalse("the right end should have moved", hasInk(width * 0.78f, y))
        assertTrue("the right end should sit lower", hasInk(width * 0.78f, y + 150f))
    }

    @Test
    fun smudgeFollowsSymmetry() {
        val y = height * 0.45f
        var centerX = 0f
        onStudio {
            it.setColor(Color.BLACK)
            it.setSymmetry(1)
            centerX = it.symmetryScreenX()
        }
        stroke(centerX - 260f, y, centerX - 60f, y)
        settle()
        val spot = y + 30f
        assertFalse("the mirrored spot should start clean", isTinted(centerX + 160f, spot))
        onStudio { it.selectTool(BrushKind.SMUDGE) }
        stroke(centerX - 160f, y - 40f, centerX - 160f, y + 80f)
        settle()
        assertTrue("smudge should pull color on the mirrored side too", isTinted(centerX + 160f, spot))
    }

    @Test
    fun animationShowsOneFrameWithItsNeighborFaint() {
        var first = 0f
        var second = 0f
        onStudio {
            it.setColor(Color.BLACK)
            it.setAnimation(true)
            first = it.canvasToScreen(0f, DEFAULT_CANVAS_HEIGHT * 0.3f)[1]
            second = it.canvasToScreen(0f, DEFAULT_CANVAS_HEIGHT * 0.7f)[1]
        }
        stroke(width * 0.2f, first, width * 0.8f, first)
        settle()
        onStudio { it.addLayer() }
        stroke(width * 0.2f, second, width * 0.8f, second)
        settle()
        assertTrue("the current frame should show", hasInk(width / 2f, second))
        assertFalse("the frame before should only show faintly", hasInk(width / 2f, first))
        assertTrue("onion skin should show the frame before", isTinted(width / 2f, first))
        onStudio { it.setAnimation(false) }
        assertTrue("both layers show again when animation is off", hasInk(width / 2f, first))
    }

    @Test
    fun erasingTheTopLayerLeavesTheLayerBelowWhileThePenIsDown() {
        var x = 0f
        var y = 0f
        onStudio {
            it.setColor(Color.BLACK)
            it.setBrushSize(120f)
            x = width / 2f
            y = it.canvasToScreen(0f, DEFAULT_CANVAS_HEIGHT / 2f)[1]
        }
        stroke(x - width * 0.3f, y, x + width * 0.3f, y)
        settle()
        onStudio {
            it.addLayer()
            it.setColor(Color.RED)
        }
        stroke(x, y - 150f, x, y + 150f)
        settle()
        onStudio { it.selectTool(BrushKind.entries.first { kind -> kind.id == "erase-ink" }) }
        val down = SystemClock.uptimeMillis()
        val startX = x - 90f
        inject(MotionEvent.ACTION_DOWN, down, 0, listOf(Pointer(0, startX, y)), stylus = true)
        for (step in 1..30) {
            inject(MotionEvent.ACTION_MOVE, down, step * 8L, listOf(Pointer(0, startX + step * 6f, y)), stylus = true)
        }
        SystemClock.sleep(300)
        val during = hasInk(x, y)
        inject(MotionEvent.ACTION_UP, down, 31 * 8L, listOf(Pointer(0, startX + 180f, y)), stylus = true)
        settle()
        assertTrue("the bottom layer should show while erasing above it", during)
        assertTrue("the bottom layer should show after erasing above it", hasInk(x, y))
    }

    @Test
    fun aWarmupGivesFeedbackAndLeavesNothingSaved() {
        scenario.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ArtworkStore(context)
        val before = store.artworks().map { it.id }.toSet()
        val exercise = WARMUPS.first { it.kind == WarmupKind.STRAIGHT_LINES }
        val seed = 21
        scenario = ActivityScenario.launch(MainActivity.warmupIntent(context, exercise, seed))
        // Keeps clear of the sliders on the left edge of the screen.
        val (from, to) = WarmupLayout(seed).dots.maxBy { minOf(it.first.x, it.second.x) }
        waitForPaper()
        var start = floatArrayOf()
        var end = floatArrayOf()
        onStudio {
            it.setColor(Color.BLACK)
            start = it.canvasToScreen(WARMUP_SIZE_FOR_TEST * from.x, WARMUP_SIZE_FOR_TEST * from.y)
            end = it.canvasToScreen(WARMUP_SIZE_FOR_TEST * to.x, WARMUP_SIZE_FOR_TEST * to.y)
        }
        stroke(start[0], start[1], end[0], end[1])
        settle()
        assertEquals("the guide and a practice layer", 2, layerCount())
        onStudio { it.warmupFeedbackNow(exercise) }
        var note: String? = null
        repeat(20) {
            scenario.onActivity { note = it.lastWarmupNote }
            if (note == null) SystemClock.sleep(250)
        }
        assertTrue("the note should talk about straight lines: $note", note?.contains("straight") == true)
        scenario.close()
        SystemClock.sleep(1000)
        assertEquals("a warm-up shouldn't be saved", before, store.artworks().map { it.id }.toSet())
    }

    @Test
    fun everyBlendModeKeepsTheCanvasSolid() {
        val y = height / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio { it.addLayer() }
        for (blend in BLEND_MODES.indices) {
            onStudio { it.setBlend(it.activeLayer()!!, blend) }
            var lowest = -1
            onStudio { it.canvasOpacityForTest { value -> lowest = value } }
            repeat(10) { if (lowest < 0) SystemClock.sleep(200) }
            assertEquals("blend ${BLEND_MODES[blend]} should leave the canvas solid", 255, lowest)
        }
    }

    @Test
    fun separateStrokesStaySeparate() {
        onStudio { it.setColor(Color.BLACK) }
        val top = height * 0.35f
        val bottom = height * 0.6f
        stroke(width * 0.2f, top, width * 0.4f, top)
        settle()
        stroke(width * 0.6f, bottom, width * 0.8f, bottom)
        settle()
        assertTrue("both strokes should be there", hasInk(width * 0.3f, top) && hasInk(width * 0.7f, bottom))
        assertFalse("nothing should join the end of one stroke to the start of the next", hasInk(width * 0.5f, (top + bottom) / 2f))
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
        var ringShowing = true
        repeat(20) {
            if (ringShowing) SystemClock.sleep(100)
            onStudio { ringShowing = it.pickRingShowing }
        }
        assertFalse("the pick ring should close with the pen", ringShowing)
        assertFalse("holding should leave no mark", hasInk(x, y))
    }

    @Test
    fun maskHidesPaintAndComesBackWithTheEraser() {
        val y = height / 2f
        val x = width / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio {
            it.addMask()
            it.selectTool(BrushKind.INK)
            it.setBrushSize(300f)
        }
        stroke(x, y - 60f, x, y + 60f)
        settle()
        assertTrue("the mask should only hide where it was painted", hasInk(width * 0.3f, y))
        assertFalse("the mask did not hide the stroke", hasInk(x, y))
        onStudio { it.setMaskEnabled(it.activeLayer()!!, false) }
        assertTrue("turning the mask off should show the stroke", hasInk(x, y))
        onStudio { it.setMaskEnabled(it.activeLayer()!!, true) }
        assertFalse(hasInk(x, y))

        onStudio { it.applyMask(it.activeLayer()!!) }
        assertFalse("applying the mask should keep the result", hasInk(x, y))
        tap(2)
        settle()
        assertFalse("undoing apply should bring the mask back", hasInk(x, y))
        onStudio { it.setMaskEnabled(it.activeLayer()!!, false) }
        assertTrue("undoing apply should restore the pixels", hasInk(x, y))
    }

    @Test
    fun maskMovesWithItsLayerAndOneUndoRestoresBoth() {
        val y = height / 2f
        val x = width / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio {
            it.addMask()
            it.selectTool(BrushKind.INK)
            it.setBrushSize(300f)
        }
        stroke(x, y - 60f, x, y + 60f)
        settle()
        repeat(10) { if (hasInk(x, y)) SystemClock.sleep(500) }
        assertFalse(hasInk(x, y))
        onStudio { it.startTransform() }
        stroke(x, height * 0.3f, x, height * 0.3f + 120f)
        settle()
        onStudio { it.finishTransform(keep = true) }
        assertFalse("the mask stayed behind its layer", hasInk(x, y + 120f))
        assertTrue("the layer should have moved", hasInk(width * 0.3f, y + 120f))
        tap(2)
        settle()
        repeat(20) { if (!hasInk(width * 0.3f, y)) SystemClock.sleep(500) }
        assertTrue("undo did not move the layer back", hasInk(width * 0.3f, y))
        assertFalse("undo did not move the mask back", hasInk(x, y))
    }

    @Test
    fun liquifyPushesPaintAndCanBeUndone() {
        val y = height / 2f
        val x = width / 2f
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        onStudio {
            it.selectTool(BrushKind.PAINT_BRUSHES.first { kind -> kind.id == "soft" })
            it.setBrushSize(1000f)
            it.startLiquify()
        }
        stroke(x, y, x, y + 90f)
        settle()
        onStudio { it.finishLiquify(keep = true) }
        assertFalse("liquify did not move the line", hasInk(x, y))
        tap(2)
        settle()
        assertTrue("undo did not restore the line", hasInk(x, y))
    }

    @Test
    fun symmetryMirrorsStrokes() {
        val y = height * 0.4f
        var centerX = 0f
        onStudio {
            it.setSymmetry(1)
            centerX = it.symmetryScreenX()
        }
        stroke(centerX - 200f, y, centerX - 80f, y)
        settle()
        assertTrue("the stroke should remain", hasInk(centerX - 140f, y))
        assertTrue("the stroke was not mirrored", hasInk(centerX + 140f, y))
    }

    @Test
    fun lassoFillPaintsAShapeAndLassoEraseRemovesIt() {
        val x = width / 2f
        val y = height / 2f
        onStudio {
            it.setColor(Color.BLACK)
            it.selectTool(BrushKind.LASSO_FILL)
        }
        loop(x, y, 120f)
        settle()
        assertTrue("lasso fill didn't paint the shape", hasInk(x, y))
        onStudio { it.selectTool(BrushKind.entries.first { kind -> kind.id == "erase-lasso" }) }
        loop(x, y, 60f)
        settle()
        assertFalse("lasso erase didn't clear the shape", hasInk(x, y))
        assertTrue("lasso erase went too far", hasInk(x + 90f, y))
        tap(2)
        settle()
        assertTrue("undo didn't restore the erased area", hasInk(x, y))
    }

    @Test
    fun holdingAtTheEndOfAStrokeMakesItStraight() {
        val y = height / 2f
        val startX = width * 0.25f
        val endX = width * 0.75f
        val down = SystemClock.uptimeMillis()
        val steps = 30
        injectPen(MotionEvent.ACTION_DOWN, startX, y, down, down)
        for (step in 1..steps) {
            val share = step / steps.toFloat()
            val wobble = kotlin.math.sin(share * 30f) * 18f
            injectPen(MotionEvent.ACTION_MOVE, startX + (endX - startX) * share, y + wobble, down, down + step * 16L)
            SystemClock.sleep(16)
        }
        SystemClock.sleep(900)
        injectPen(MotionEvent.ACTION_UP, endX, y, down, SystemClock.uptimeMillis())
        settle()
        assertTrue("the snapped line should cross the middle", hasInk(width / 2f, y))
        tap(2)
        settle()
        assertFalse("one undo should remove the snapped line", hasInk(width / 2f, y))
    }

    @Test
    fun scatterBrushLeavesMarksAwayFromThePath() {
        val y = height / 2f
        onStudio {
            it.setColor(Color.BLACK)
            it.selectTool(BrushKind.SCATTER_BRUSHES.first())
            it.setBrushSize(400f)
        }
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        val shot = screenshot()
        val offPath = (0 until 200).count { step ->
            val x = (width * 0.25f + step * width * 0.0025f).toInt()
            Color.red(shot.getPixel(x, (y + 45f).toInt())) < 100
        }
        assertTrue("marks should land beside the stroke", offPath > 0)
    }

    @Test
    fun filtersChangePixelsAndCanBeUndone() {
        val y = height / 2f
        val x = width / 2f
        onStudio { it.setColor(Color.BLACK) }
        stroke(width * 0.2f, y, width * 0.8f, y)
        settle()
        val depth = FILTERS.first { it.label == "Depth fade" }
        onStudio {
            it.setColor(Color.WHITE)
            it.applyFilter(depth, 1f)
        }
        repeat(20) { if (hasInk(x, y)) SystemClock.sleep(500) }
        assertFalse("depth fade should turn the line white", hasInk(x, y))
        tap(2)
        repeat(20) { if (!hasInk(x, y)) SystemClock.sleep(500) }
        assertTrue("undo should bring the line back", hasInk(x, y))
        val blur = FILTERS.first { it.label == "Blur" }
        onStudio { it.applyFilter(blur, 120f) }
        repeat(20) { if (hasInk(x, y)) SystemClock.sleep(500) }
        assertFalse("blur should soften a thin line", hasInk(x, y))
    }

    @Test
    fun textBecomesAPlaceableLayer() {
        val x = width / 2f
        val y = height / 2f
        onStudio {
            it.setColor(Color.BLACK)
            it.addText("████", 0.09f)
        }
        assertEquals(2, layerCount())
        onStudio { it.finishTransform(keep = false) }
        assertTrue("the text should show", hasInk(x, y))
        tap(2)
        settle()
        assertEquals("undo should remove the text layer", 1, layerCount())
    }

    @Test
    fun colorVariantTurnsEveryLayerAndOneUndoRestoresThem() {
        val firstY = height * 0.45f
        val secondY = height * 0.55f
        val x = width / 2f
        fun redAt(y: Float) = (-8..8).any { dy ->
            val pixel = screenshot().getPixel(x.toInt(), y.toInt() + dy)
            Color.red(pixel) > 180 && Color.green(pixel) < 90
        }
        onStudio {
            it.setColor(Color.RED)
            it.selectTool(BrushKind.INK)
            it.setBrushSize(120f)
        }
        stroke(width * 0.2f, firstY, width * 0.8f, firstY)
        settle()
        onStudio { it.addLayer() }
        stroke(width * 0.2f, secondY, width * 0.8f, secondY)
        settle()
        assertTrue(redAt(firstY) && redAt(secondY))
        onStudio { it.applyColorVariant(0.5f) }
        repeat(20) { if (redAt(firstY)) SystemClock.sleep(500) }
        assertFalse("the first layer kept its color", redAt(firstY))
        assertFalse("the second layer kept its color", redAt(secondY))
        tap(2)
        repeat(20) { if (!redAt(firstY)) SystemClock.sleep(500) }
        assertTrue("one undo should restore the first layer", redAt(firstY))
        assertTrue("one undo should restore the second layer", redAt(secondY))
    }

    @Test
    fun variationCopiesTheArtworkAndLinksIt() {
        var original = ""
        onStudio {
            it.setColor(Color.BLACK)
            original = it.artworkId
        }
        stroke(width * 0.2f, height / 2f, width * 0.8f, height / 2f)
        settle()
        val store = ArtworkStore(InstrumentationRegistry.getInstrumentation().targetContext)
        onStudio { it.makeVariation() }
        repeat(20) { if (store.variations(original).size < 2) SystemClock.sleep(500) }
        val family = store.variations(original)
        assertEquals(2, family.size)
        assertTrue(family.any { it.id != original && it.variationOf == original && it.name.endsWith(" B") })
    }

    @Test
    fun designPanelsAndExportsOpenWithoutTrouble() {
        stroke(width * 0.2f, height / 2f, width * 0.8f, height / 2f)
        settle()
        onStudio { it.openActionsAndVariants() }
        settle()
        onStudio { it.exportDesignSheet() }
        settle()
        onStudio { it.exportThumbnailFrames() }
        settle()
        assertTrue("the studio should still be open", hasInk(width / 2f, height / 2f))
    }

    @Test
    fun aCrashKeepsStrokesFinishedAfterTheLastSave() {
        var id = ""
        var target = 0
        onStudio {
            it.setColor(Color.BLACK)
            id = it.artworkId
            target = it.activeLayer()!!.id
        }
        stroke(width * 0.2f, height * 0.3f, width * 0.8f, height * 0.3f)
        SystemClock.sleep(3500)
        scenario.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ArtworkStore(context)
        val saved = store.load(id)!!
        val samples = FloatArray(200 * 5) { index ->
            val point = index / 5
            when (index % 5) {
                0 -> 1000f + point * 10f
                1 -> saved.height / 2f
                2 -> 1f
                else -> 0f
            }
        }
        store.journal(id).append(
            saved.journalSequence + 1,
            JournalEntry.Stroke(target, "ink", 80f, Color.BLACK, 1f, false, 0.75f, samples),
        )
        val intent = android.content.Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ARTWORK, id)
        scenario = ActivityScenario.launch(intent)
        repeat(20) { if (!hasInk(width / 2f, height / 2f)) SystemClock.sleep(500) }
        assertTrue("the journal should bring the stroke back", hasInk(width / 2f, height / 2f))
    }

    @Test
    fun tilesBringBackASavedArtworkAndSmallChangesStaySmall() {
        var id = ""
        onStudio {
            it.setColor(Color.BLACK)
            id = it.artworkId
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.io.File(context.filesDir, "artworks/$id")
        fun packBytes() = dir.listFiles().orEmpty().filter { it.name.startsWith("pack-") }.sumOf { it.length() }
        stroke(width * 0.2f, height * 0.4f, width * 0.8f, height * 0.4f)
        SystemClock.sleep(3500)
        repeat(30) { if (packBytes() == 0L) SystemClock.sleep(500) }
        val before = packBytes()
        assertTrue("a stroke should be saved as tiles", before > 0)
        val packsBefore = dir.listFiles().orEmpty().filter { it.name.startsWith("pack-") }.map { it.name }.toSet()
        stroke(width * 0.45f, height * 0.6f, width * 0.6f, height * 0.6f)
        settle()
        assertTrue("the short stroke should be drawn", hasInk(width * 0.51f, height * 0.6f))
        scenario.close()
        val newest = dir.listFiles().orEmpty().filter { it.name.startsWith("pack-") && it.name !in packsBefore }
        assertTrue("the short stroke should rewrite less than the long one", newest.sumOf { it.length() } < before)
        val intent = android.content.Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ARTWORK, id)
        scenario = ActivityScenario.launch(intent)
        repeat(20) { if (!hasInk(width / 2f, height * 0.4f)) SystemClock.sleep(500) }
        assertTrue("the long stroke should come back", hasInk(width / 2f, height * 0.4f))
        repeat(20) { if (!hasInk(width * 0.51f, height * 0.6f)) SystemClock.sleep(500) }
        assertTrue("the short stroke should come back", hasInk(width * 0.51f, height * 0.6f))
    }

    @Test
    fun olderArtworksOpenAndMoveToTiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "older-artwork-test"
        val dir = java.io.File(context.filesDir, "artworks/$id").apply {
            deleteRecursively()
            mkdirs()
        }
        val side = 800
        val pixels = ByteArray(side * side * 4)
        for (y in 380 until 420) {
            for (x in 0 until side) {
                pixels[(y * side + x) * 4 + 3] = -1
            }
        }
        java.util.zip.DeflaterOutputStream(java.io.File(dir, "layer-1-1.bin").outputStream()).use { it.write(pixels) }
        val layer = org.json.JSONObject().put("id", 1).put("name", "Old").put("opacity", 1.0)
            .put("visible", true).put("blend", 0).put("file", "layer-1-1.bin")
        val manifest = org.json.JSONObject().put("version", 1).put("name", "Old").put("project", "")
            .put("width", side).put("height", side).put("activeLayer", 1).put("nextLayerId", 2)
            .put("layers", org.json.JSONArray().put(layer))
        java.io.File(dir, "manifest.json").writeText(manifest.toString())
        val intent = android.content.Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ARTWORK, id)
        scenario.close()
        scenario = ActivityScenario.launch(intent)
        repeat(20) { if (!hasInk(width / 2f, height / 2f)) SystemClock.sleep(500) }
        assertTrue("the older artwork should open", hasInk(width / 2f, height / 2f))
        repeat(30) { if (dir.listFiles().orEmpty().none { it.name.startsWith("pack-") }) SystemClock.sleep(500) }
        scenario.close()
        val saved = org.json.JSONObject(java.io.File(dir, "manifest.json").readText())
        assertTrue("it should have moved to tiles", saved.getJSONArray("layers").getJSONObject(0).getJSONArray("tiles").length() > 0)
        scenario = ActivityScenario.launch(intent)
        repeat(20) { if (!hasInk(width / 2f, height / 2f)) SystemClock.sleep(500) }
        assertTrue("it should still open from tiles", hasInk(width / 2f, height / 2f))
        scenario.close()
        dir.deleteRecursively()
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

    private fun loop(centerX: Float, centerY: Float, radius: Float) {
        val down = SystemClock.uptimeMillis()
        val steps = 40
        fun spot(step: Int): Pointer {
            val turn = step * 2.0 * Math.PI / steps
            return Pointer(0, centerX + radius * kotlin.math.cos(turn).toFloat(), centerY + radius * kotlin.math.sin(turn).toFloat())
        }
        inject(MotionEvent.ACTION_DOWN, down, 0, listOf(spot(0)), stylus = true)
        for (step in 1..steps) inject(MotionEvent.ACTION_MOVE, down, step * 8L, listOf(spot(step)), stylus = true)
        inject(MotionEvent.ACTION_UP, down, (steps + 1) * 8L, listOf(spot(steps)), stylus = true)
    }

    private fun injectPen(action: Int, x: Float, y: Float, down: Long, time: Long) {
        inject(action, down, time - down, listOf(Pointer(0, x, y)), stylus = true)
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
