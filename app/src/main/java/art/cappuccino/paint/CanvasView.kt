package art.cappuccino.paint

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.Matrix as GlMatrix
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
)

class CanvasView(context: Context) : SurfaceView(context) {
    var brushKind = BrushKind.INK
    var brushDiameter = 24f
    var brushOpacity = 1f
    var brushColor = 0xFF2C2C2A.toInt()
    var acceptsInput = false

    var onStrokeStart: () -> Unit = {}
    var onStrokeEnd: () -> Unit = {}
    var onGestureStart: () -> Unit = {}
    var onColorPicked: (Int) -> Unit = {}
    var onToggleInterface: () -> Unit = {}

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
            if (engine == 0L) engine = NativeEngine.create(CANVAS_WIDTH, CANVAS_HEIGHT)
            while (true) (pendingActions.poll() ?: break).invoke(engine)
        }

        private fun prepare(bufferInfo: BufferInfo, transform: FloatArray) {
            if (engine == 0L) engine = NativeEngine.create(CANVAS_WIDTH, CANVAS_HEIGHT)
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
                    kind.pressureFlow, kind.hardness, kind.grain, kind == BrushKind.ERASER,
                )
            }
            NativeEngine.drawFront(
                engine, projection, op.view,
                bufferInfo.width, bufferInfo.height,
                op.samples, op.predicted, op.end,
            )
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
            NativeEngine.compose(engine, projection, view, bufferInfo.width, bufferInfo.height)
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
            val scale = min(width / CANVAS_WIDTH.toFloat(), height / CANVAS_HEIGHT.toFloat()) * 0.95f
            viewMatrix.setScale(scale, scale)
            viewMatrix.postTranslate(
                (width - CANVAS_WIDTH * scale) / 2f,
                (height - CANVAS_HEIGHT * scale) / 2f,
            )
            fitted = true
        }
        publishView()
    }

    private fun viewValues() = FloatArray(9).also(viewMatrix::getValues)

    private fun publishView() {
        latestView = viewValues()
        refresh()
    }

    private fun refresh() {
        renderer.renderMultiBufferedLayer(listOf<RenderOp>(RenderOp.Refresh))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                penPointerId = event.getPointerId(0)
                requestUnbufferedDispatch(event)
                viewMatrix.invert(inverseMatrix)
                val tool = event.getToolType(0)
                strokeKind = if (tool == MotionEvent.TOOL_TYPE_ERASER) BrushKind.ERASER else brushKind
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

    private fun finishStroke(event: MotionEvent) {
        predictor.record(event)
        sendStroke(event, begin = false, end = true)
        renderer.commit()
        penPointerId = MotionEvent.INVALID_POINTER_ID
        onStrokeEnd()
    }

    private fun sendStroke(event: MotionEvent, begin: Boolean, end: Boolean) {
        val predicted = if (end) null else predictor.predict()
        val op = RenderOp.Stroke(
            begin = begin,
            end = end,
            brush = BrushState(strokeKind, brushDiameter, brushColor, brushOpacity),
            view = viewValues(),
            samples = readSamples(event),
            predicted = predicted?.let(::readSamples) ?: FloatArray(0),
        )
        predicted?.recycle()
        renderer.renderFrontBufferedLayer(op)
    }

    private fun readSamples(event: MotionEvent): FloatArray {
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
                if (event.pointerCount >= 2) {
                    applyGesture(event)
                } else if (hypot(event.x - pressX, event.y - pressY) > tapTravel) {
                    removeCallbacks(pickAtPress)
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(pickAtPress)
            MotionEvent.ACTION_POINTER_UP ->
                if (event.pointerCount >= 3) captureGesture(event, skip = event.actionIndex)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(pickAtPress)
                val isTap = travel < tapTravel && event.eventTime - gestureStart < TAP_TIMEOUT_MS
                if (isTap && maxFingers == 2) undo()
                if (isTap && maxFingers == 3) redo()
                if (isTap && maxFingers == 4) onToggleInterface()
            }
        }
        return true
    }

    private val pickAtPress = Runnable {
        point[0] = pressX
        point[1] = pressY
        viewMatrix.invert(inverseMatrix)
        inverseMatrix.mapPoints(point)
        val x = point[0].toInt()
        val y = point[1].toInt()
        if (x in 0 until CANVAS_WIDTH && y in 0 until CANVAS_HEIGHT) {
            act { engine ->
                val color = NativeEngine.pickColor(engine, x, y)
                post { onColorPicked(color or 0xFF000000.toInt()) }
            }
        }
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
