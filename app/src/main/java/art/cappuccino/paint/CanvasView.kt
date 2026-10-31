package art.cappuccino.paint

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.Matrix as GlMatrix
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import androidx.input.motionprediction.MotionEventPredictor
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MIN_ZOOM = 0.1f
private const val MAX_ZOOM = 16f
private const val TAP_TIMEOUT_MS = 300L
private const val TAP_TRAVEL_DP = 16f
private const val FIRST_FRAME_RETRY_MS = 100L
private const val LONG_PRESS_MS = 500L

private sealed interface RenderOp {
    class Stroke(
        val begin: Boolean,
        val end: Boolean,
        val brush: BrushState,
        val view: FloatArray,
        val samples: FloatArray,
        val predicted: FloatArray,
    ) : RenderOp {
        var drawn = false
    }
    data object Refresh : RenderOp
}

class BrushState(
    val kind: BrushKind,
    val diameter: Float,
    val color: Int,
    val opacity: Float,
    val alphaLock: Boolean,
    val pressureGamma: Float,
)

private const val SLOW_MS = 32L

/** Logs work on the drawing thread that's long enough to miss a frame or more. */
private inline fun <T> timed(name: String, work: () -> T): T {
    val start = SystemClock.elapsedRealtime()
    val result = work()
    val elapsed = SystemClock.elapsedRealtime() - start
    if (elapsed >= SLOW_MS) Log.d("CappuccinoPerf", "$name took $elapsed ms")
    return result
}

enum class SelectionTool { NONE, LASSO, WAND }

class CanvasView(
    context: Context,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
) : SurfaceView(context) {
    var brushKind = BrushKind.INK
    var brushDiameter = 24f
    var brushOpacity = 1f
    var brushColor = 0xFF2C2C2A.toInt()
    var eraserKind = BrushKind.ERASE_INK
    var alphaLock = false
    var stabilizerStrength = 0f
    var pressureGamma = 0.75f
    var selectionTool = SelectionTool.NONE
    var transformMode = false
        private set
    var acceptsInput = false

    var onStrokeStart: () -> Unit = {}
    var onStrokeEnd: () -> Unit = {}
    var onGestureStart: () -> Unit = {}
    var onColorPicked: (Int) -> Unit = {}
    var onToggleInterface: () -> Unit = {}
    var onViewChanged: () -> Unit = {}
    var onFillTap: (Int, Int) -> Unit = { _, _ -> }
    var onWandTap: (Int, Int) -> Unit = { _, _ -> }
    var onLassoMove: (Float, Float) -> Unit = { _, _ -> }
    var onLassoDone: (FloatArray) -> Unit = {}
    var onGradientMove: (FloatArray) -> Unit = {}
    var onGradientDone: () -> Unit = {}
    var onPickMove: (Int, Float, Float) -> Unit = { _, _, _ -> }
    var onPickEnd: () -> Unit = {}
    var onStrokeCommitted: () -> Unit = {}
    var onUndoGesture: () -> Unit = {}
    var onRedoGesture: () -> Unit = {}
    var onStrokePath: (FloatArray) -> Unit = {}

    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val predictor = MotionEventPredictor.newInstance(this)
    private val point = FloatArray(2)
    private val tapTravel = TAP_TRAVEL_DP * resources.displayMetrics.density
    private var fitted = false

    // These cross to the GL thread outside the renderer's params, because it can merge
    // multi-buffered render requests and the latest state must survive that.
    @Volatile private var latestView = FloatArray(9)
    @Volatile private var composed = false
    private val pendingActions = ConcurrentLinkedQueue<(Long) -> Unit>()

    private val publishUntilComposed = object : Runnable {
        override fun run() {
            if (composed) return
            refresh()
            postDelayed(this, FIRST_FRAME_RETRY_MS)
        }
    }

    private var penPointerId = MotionEvent.INVALID_POINTER_ID
    private var strokeKind = BrushKind.INK
    private var strokePath = FloatArray(1024)
    private var strokePathSize = 0
    private var toolGesture = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var picking = false
    private val stabilizer = LineStabilizer()
    private val layerMatrix = Matrix()
    private var transformLayer = 0

    private var maxFingers = 0
    private var gestureStart = 0L
    private var travel = 0f
    private var pressX = 0f
    private var pressY = 0f
    private var previousX = 0f
    private var previousY = 0f
    private var previousSpan = 0f
    private var previousAngle = 0f

    private val glCallback = object : GLFrontBufferedRenderer.Callback<RenderOp> {
        private var engine = 0L
        private val ortho = FloatArray(16)
        private val projection = FloatArray(16)

        fun drainActions() {
            if (engine == 0L) engine = NativeEngine.create(canvasWidth, canvasHeight)
            while (true) {
                val action = pendingActions.poll() ?: break
                timed("engine action") { action(engine) }
            }
        }

        private fun prepare(bufferInfo: BufferInfo, transform: FloatArray) {
            if (engine == 0L) engine = NativeEngine.create(canvasWidth, canvasHeight)
            GlMatrix.orthoM(
                ortho, 0,
                0f, bufferInfo.width.toFloat(),
                0f, bufferInfo.height.toFloat(),
                -1f, 1f,
            )
            GlMatrix.multiplyMM(projection, 0, ortho, 0, transform, 0)
        }

        override fun onDrawFrontBufferedLayer(
            eglManager: EGLManager,
            width: Int,
            height: Int,
            bufferInfo: BufferInfo,
            transform: FloatArray,
            param: RenderOp,
        ) {
            prepare(bufferInfo, transform)
            if (param is RenderOp.Stroke) drawStroke(param, bufferInfo)
        }

        private fun drawStroke(op: RenderOp.Stroke, bufferInfo: BufferInfo) {
            op.drawn = true
            if (op.begin) {
                val brush = op.brush
                val kind = brush.kind
                NativeEngine.beginStroke(
                    engine, brush.diameter,
                    Color.red(brush.color) / 255f,
                    Color.green(brush.color) / 255f,
                    Color.blue(brush.color) / 255f,
                    kind.stampFlow(brush.opacity), kind.minScale, kind.spacing,
                    kind.pressureFlow, kind.hardness, kind.grain, kind.isEraser, brush.alphaLock,
                    kind.mode == BrushMode.SMUDGE, brush.pressureGamma, kind.tip,
                )
            }
            timed(if (op.end) "stroke end" else "stroke frame") {
                NativeEngine.drawFront(
                    engine, projection, op.view,
                    bufferInfo.width, bufferInfo.height,
                    op.samples, op.predicted, op.end,
                )
            }
        }

        override fun onDrawMultiBufferedLayer(
            eglManager: EGLManager,
            width: Int,
            height: Int,
            bufferInfo: BufferInfo,
            transform: FloatArray,
            params: Collection<RenderOp>,
        ) {
            prepare(bufferInfo, transform)
            // Commit hands over the whole stroke, including samples the front layer
            // skipped because the GL thread fell behind.
            for (op in params) {
                if (op is RenderOp.Stroke && !op.drawn) drawStroke(op, bufferInfo)
            }
            drainActions()
            val view = latestView
            if (view[Matrix.MPERSP_2] == 0f) return
            timed("compose") {
                NativeEngine.compose(engine, projection, view, bufferInfo.width, bufferInfo.height)
            }
            composed = true
        }

        fun destroyEngine() {
            if (engine != 0L) NativeEngine.destroy(engine)
            engine = 0L
        }
    }

    private val renderer = GLFrontBufferedRenderer(this, glCallback)

    init {
        // Registered after the renderer's own callback, so the surface is ready to draw on.
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                composed = false
                removeCallbacks(publishUntilComposed)
                post(publishUntilComposed)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                removeCallbacks(publishUntilComposed)
            }
        })
    }

    /** Runs on the GL thread before the next frame, then shows the result. */
    fun act(action: (Long) -> Unit) {
        pendingActions.add(action)
        refresh()
    }

    /** Runs on the GL thread even while the surface is gone, as when the app goes to the background. */
    fun snapshot(action: (Long) -> Unit) {
        pendingActions.add(action)
        renderer.execute { glCallback.drainActions() }
    }

    fun canvasToScreen(x: Float, y: Float): FloatArray =
        floatArrayOf(x, y).also(viewMatrix::mapPoints)

    fun screenToCanvas(x: Float, y: Float): FloatArray {
        viewMatrix.invert(inverseMatrix)
        return floatArrayOf(x, y).also(inverseMatrix::mapPoints)
    }

    fun flip() {
        viewMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        publishView()
    }

    fun undo() = act { NativeEngine.undo(it) }

    fun redo() = act { NativeEngine.redo(it) }

    fun release() {
        removeCallbacks(publishUntilComposed)
        removeCallbacks(pickAtPress)
        renderer.execute { glCallback.destroyEngine() }
        renderer.release(true)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == 0 || height == 0) return
        if (!fitted) {
            val scale = min(width / canvasWidth.toFloat(), height / canvasHeight.toFloat()) * 0.95f
            viewMatrix.setScale(scale, scale)
            viewMatrix.postTranslate(
                (width - canvasWidth * scale) / 2f,
                (height - canvasHeight * scale) / 2f,
            )
            fitted = true
        }
        publishView()
    }

    private fun viewValues() = FloatArray(9).also(viewMatrix::getValues)

    private fun publishView() {
        latestView = viewValues()
        refresh()
        onViewChanged()
    }

    private fun refresh() {
        renderer.renderMultiBufferedLayer(listOf<RenderOp>(RenderOp.Refresh))
    }

    fun beginTransform(layer: Int) {
        transformLayer = layer
        layerMatrix.reset()
        transformMode = true
        publishTransform()
    }

    /** Ends the transform. A true [keep] applies it to the layer's pixels. */
    fun endTransform(keep: Boolean) {
        transformMode = false
        act {
            if (keep) {
                NativeEngine.commitLayerTransform(it)
            } else {
                NativeEngine.setLayerTransform(it, 0, FloatArray(9))
            }
        }
    }

    private fun publishTransform() {
        val values = FloatArray(9).also(layerMatrix::getValues)
        val id = transformLayer
        act { NativeEngine.setLayerTransform(it, id, values) }
    }

    private fun onTransformEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> captureGesture(event, skip = -1)
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount >= 3) {
                    captureGesture(event, skip = event.actionIndex)
                } else {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    previousX = event.getX(remaining)
                    previousY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val change = Matrix()
                if (event.pointerCount >= 2) {
                    val (first, second) = gesturePointers(event, skip = -1)
                    val x = (event.getX(first) + event.getX(second)) / 2f
                    val y = (event.getY(first) + event.getY(second)) / 2f
                    val span = hypot(event.getX(second) - event.getX(first), event.getY(second) - event.getY(first))
                    val angle = atan2(event.getY(second) - event.getY(first), event.getX(second) - event.getX(first))
                    if (span < 1f || previousSpan < 1f) return true
                    change.postTranslate(x - previousX, y - previousY)
                    change.postScale(span / previousSpan, span / previousSpan, x, y)
                    change.postRotate(Math.toDegrees((angle - previousAngle).toDouble()).toFloat(), x, y)
                    previousX = x
                    previousY = y
                    previousSpan = span
                    previousAngle = angle
                } else {
                    change.postTranslate(event.x - previousX, event.y - previousY)
                    previousX = event.x
                    previousY = event.y
                }
                val onScreen = Matrix(layerMatrix)
                onScreen.postConcat(viewMatrix)
                onScreen.postConcat(change)
                viewMatrix.invert(inverseMatrix)
                onScreen.postConcat(inverseMatrix)
                layerMatrix.set(onScreen)
                publishTransform()
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (transformMode) return onTransformEvent(event)
        if (toolGesture) return onToolGesture(event)
        val index = event.actionIndex
        val startsStroke = acceptsInput &&
            event.actionMasked == MotionEvent.ACTION_DOWN && isPen(event, index)
        return if (startsStroke || penPointerId != MotionEvent.INVALID_POINTER_ID) {
            onPenEvent(event)
        } else {
            onFingerEvent(event)
        }
    }

    private fun isPen(event: MotionEvent, index: Int) = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER,
        MotionEvent.TOOL_TYPE_MOUSE -> true
        else -> false
    }

    private fun onPenEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER
        ) {
            val spot = screenToCanvas(event.x, event.y)
            if (selectionTool == SelectionTool.WAND) {
                onWandTap(spot[0].toInt(), spot[1].toInt())
                return true
            }
            if (selectionTool == SelectionTool.LASSO || brushKind.mode == BrushMode.GRADIENT) {
                toolGesture = true
                strokePathSize = 0
                gestureStartX = spot[0]
                gestureStartY = spot[1]
                return onToolGesture(event)
            }
            if (brushKind.mode == BrushMode.FILL) {
                onFillTap(spot[0].toInt(), spot[1].toInt())
                return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                penPointerId = event.getPointerId(0)
                requestUnbufferedDispatch(event)
                viewMatrix.invert(inverseMatrix)
                val tool = event.getToolType(0)
                strokePathSize = 0
                stabilizer.reset(if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) 0f else stabilizerStrength)
                strokeKind = if (tool == MotionEvent.TOOL_TYPE_ERASER) eraserKind else brushKind
                onStrokeStart()
                predictor.record(event)
                sendStroke(event, begin = true, end = false)
            }
            MotionEvent.ACTION_MOVE -> {
                predictor.record(event)
                sendStroke(event, begin = false, end = false)
            }
            MotionEvent.ACTION_UP -> finishStroke(event)
            MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == penPointerId) finishStroke(event)
            MotionEvent.ACTION_CANCEL -> {
                penPointerId = MotionEvent.INVALID_POINTER_ID
                renderer.cancel()
                act { NativeEngine.cancelStroke(it) }
                onStrokeEnd()
            }
        }
        return true
    }

    private fun onToolGesture(event: MotionEvent): Boolean {
        val spot = screenToCanvas(event.x, event.y)
        val lasso = selectionTool == SelectionTool.LASSO
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (lasso) {
                    rememberPath(floatArrayOf(spot[0], spot[1], 0f, 0f, 0f))
                    onLassoMove(event.x, event.y)
                } else {
                    onGradientMove(floatArrayOf(gestureStartX, gestureStartY, spot[0], spot[1]))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                toolGesture = false
                if (lasso) onLassoDone(strokePath.copyOf(strokePathSize)) else onGradientDone()
            }
        }
        return true
    }

    private fun finishStroke(event: MotionEvent) {
        predictor.record(event)
        sendStroke(event, begin = false, end = true)
        renderer.commit()
        penPointerId = MotionEvent.INVALID_POINTER_ID
        onStrokeCommitted()
        onStrokeEnd()
        if (!strokeKind.isEraser) onStrokePath(strokePath.copyOf(strokePathSize))
    }

    private fun sendStroke(event: MotionEvent, begin: Boolean, end: Boolean) {
        val predicted = if (end || stabilizer.isActive) null else predictor.predict()
        val samples = readSamples(event, stabilize = true)
        rememberPath(samples)
        val op = RenderOp.Stroke(
            begin = begin,
            end = end,
            brush = BrushState(strokeKind, brushDiameter, brushColor, brushOpacity, alphaLock, pressureGamma),
            view = viewValues(),
            samples = samples,
            predicted = predicted?.let { readSamples(it, stabilize = false) } ?: FloatArray(0),
        )
        predicted?.recycle()
        renderer.renderFrontBufferedLayer(op)
    }

    private fun rememberPath(samples: FloatArray) {
        val needed = strokePathSize + samples.size / NativeEngine.SAMPLE_STRIDE * 2
        if (needed > strokePath.size) strokePath = strokePath.copyOf(needed * 2)
        for (offset in samples.indices step NativeEngine.SAMPLE_STRIDE) {
            strokePath[strokePathSize++] = samples[offset]
            strokePath[strokePathSize++] = samples[offset + 1]
        }
    }

    private fun readSamples(event: MotionEvent, stabilize: Boolean): FloatArray {
        val index = event.findPointerIndex(penPointerId)
        if (index < 0) return FloatArray(0)
        val history = event.historySize
        val values = FloatArray((history + 1) * NativeEngine.SAMPLE_STRIDE)
        val rotation = viewRotation()
        for (position in 0..history) {
            val past = position < history
            val orientation = if (past) {
                event.getHistoricalOrientation(index, position)
            } else {
                event.getOrientation(index)
            }
            val tilt = if (past) {
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, position)
            } else {
                event.getAxisValue(MotionEvent.AXIS_TILT, index)
            }
            point[0] = if (past) event.getHistoricalX(index, position) else event.getX(index)
            point[1] = if (past) event.getHistoricalY(index, position) else event.getY(index)
            inverseMatrix.mapPoints(point)
            if (stabilize) stabilizer.next(point)
            var offset = position * NativeEngine.SAMPLE_STRIDE
            values[offset++] = point[0]
            values[offset++] = point[1]
            values[offset++] = if (past) {
                event.getHistoricalPressure(index, position)
            } else {
                event.getPressure(index)
            }
            values[offset++] = tilt
            values[offset] = orientation - PI.toFloat() / 2f + rotation
        }
        return values
    }

    private fun viewRotation(): Float {
        val values = viewValues()
        return -atan2(values[Matrix.MSKEW_X], values[Matrix.MSCALE_X])
    }

    private fun onFingerEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                maxFingers = 1
                gestureStart = event.eventTime
                travel = 0f
                pressX = event.x
                pressY = event.y
                onGestureStart()
                postDelayed(pickAtPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(pickAtPress)
                maxFingers = max(maxFingers, event.pointerCount)
                captureGesture(event, skip = -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (picking) {
                    pickAt(event.x, event.y)
                } else if (event.pointerCount >= 2) {
                    applyGesture(event)
                } else if (hypot(event.x - pressX, event.y - pressY) > tapTravel) {
                    removeCallbacks(pickAtPress)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pickAtPress)
                endPick()
            }
            MotionEvent.ACTION_POINTER_UP ->
                if (event.pointerCount >= 3) captureGesture(event, skip = event.actionIndex)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(pickAtPress)
                endPick()
                val isTap = travel < tapTravel && event.eventTime - gestureStart < TAP_TIMEOUT_MS
                if (isTap && maxFingers == 2) onUndoGesture()
                if (isTap && maxFingers == 3) onRedoGesture()
                if (isTap && maxFingers == 4) onToggleInterface()
            }
        }
        return true
    }

    private fun pickAt(screenX: Float, screenY: Float) {
        val spot = screenToCanvas(screenX, screenY)
        val x = spot[0].toInt()
        val y = spot[1].toInt()
        if (x !in 0 until canvasWidth || y !in 0 until canvasHeight) return
        act { engine ->
            val color = NativeEngine.pickColor(engine, x, y) or 0xFF000000.toInt()
            post { onPickMove(color, screenX, screenY) }
        }
    }

    private val pickAtPress = Runnable {
        picking = true
        pickAt(pressX, pressY)
    }

    private fun endPick() {
        if (!picking) return
        picking = false
        onPickEnd()
    }

    private fun gesturePointers(event: MotionEvent, skip: Int): Pair<Int, Int> {
        val indices = (0 until event.pointerCount).filter { it != skip }
        return indices[0] to indices[1]
    }

    private fun captureGesture(event: MotionEvent, skip: Int) {
        val (first, second) = gesturePointers(event, skip)
        previousX = (event.getX(first) + event.getX(second)) / 2f
        previousY = (event.getY(first) + event.getY(second)) / 2f
        previousSpan = hypot(event.getX(second) - event.getX(first), event.getY(second) - event.getY(first))
        previousAngle = atan2(event.getY(second) - event.getY(first), event.getX(second) - event.getX(first))
    }

    private fun applyGesture(event: MotionEvent) {
        val (first, second) = gesturePointers(event, skip = -1)
        val x = (event.getX(first) + event.getX(second)) / 2f
        val y = (event.getY(first) + event.getY(second)) / 2f
        val span = hypot(event.getX(second) - event.getX(first), event.getY(second) - event.getY(first))
        val angle = atan2(event.getY(second) - event.getY(first), event.getX(second) - event.getX(first))
        if (span < 1f || previousSpan < 1f) return

        var turn = angle - previousAngle
        if (turn > PI) turn -= 2f * PI.toFloat()
        if (turn < -PI) turn += 2f * PI.toFloat()
        travel += hypot(x - previousX, y - previousY) + kotlin.math.abs(span - previousSpan) +
            kotlin.math.abs(turn) * span / 2f

        if (travel >= tapTravel) {
            val values = viewValues()
            val zoom = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
            val scale = (zoom * span / previousSpan).coerceIn(MIN_ZOOM, MAX_ZOOM) / zoom
            viewMatrix.postTranslate(x - previousX, y - previousY)
            viewMatrix.postScale(scale, scale, x, y)
            viewMatrix.postRotate(Math.toDegrees(turn.toDouble()).toFloat(), x, y)
            publishView()
        }
        previousX = x
        previousY = y
        previousSpan = span
        previousAngle = angle
    }
}
