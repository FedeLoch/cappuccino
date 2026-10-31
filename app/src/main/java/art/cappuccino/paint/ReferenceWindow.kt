package art.cappuccino.paint

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.LayerDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

private const val MIN_WIDTH_DP = 180
private const val MAX_TOOL_DP = 36
private const val MIN_TOOL_DP = 22
private const val MAX_ZOOM = 12f
private const val VISIBLE_EDGE_DP = 56
private const val DRAWING_ALPHA = 0.35f

interface ReferenceHost {
    fun onReferenceChanged()
    fun onReferenceClosed(reference: ReferenceWindow)
    fun onReferenceColor(color: Int)
    fun onReferencePalette(reference: ReferenceWindow)
    fun onReferenceToLayer(reference: ReferenceWindow)
}

/** A floating image that can be moved, resized, zoomed, and checked while drawing. */
@SuppressLint("ClickableViewAccessibility")
class ReferenceWindow(
    private val activity: Activity,
    private val anchor: View,
    val state: ReferenceState,
    val bitmap: Bitmap,
    private val host: ReferenceHost,
) {
    private val ratio = bitmap.height / bitmap.width.toFloat()
    private val toolButtons = ArrayList<ImageButton>()
    private val image = ReferenceImage()
    private val bar: View = createBar()
    private val root = FrameLayout(activity).apply {
        background = activity.rounded(SURFACE, 12)
        clipToOutline = true
        foreground = createFrame()
        addView(image)
        addView(createResizeHandle(), FrameLayout.LayoutParams(activity.dp(36), activity.dp(36), Gravity.BOTTOM or Gravity.END))
        addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }
    private val window: PopupWindow = PopupWindow(root, state.width, windowHeight()).apply {
        isClippingEnabled = false
        isTouchable = !state.pinned
    }

    init {
        fitToolbar()
        bar.visibility = View.GONE
        root.alpha = state.opacity
    }

    /** A cream frame with a thin dark edge, so it shows on both light and dark artwork. */
    private fun createFrame(): LayerDrawable {
        val outer = activity.rounded(Color.TRANSPARENT, 12).apply { setStroke(activity.dp(1), SURFACE) }
        val inner = activity.rounded(Color.TRANSPARENT, 11).apply { setStroke(activity.dp(2), FOAM) }
        val inset = activity.dp(1)
        return LayerDrawable(arrayOf(outer, inner)).apply { setLayerInset(1, inset, inset, inset, inset) }
    }

    private fun windowHeight(): Int = (state.width * ratio).toInt()

    fun show() {
        clampToScreen()
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, state.x, state.y)
    }

    fun dismiss() = window.dismiss()

    fun setDrawing(drawing: Boolean) {
        val alpha = if (drawing && !state.pinned) minOf(DRAWING_ALPHA, state.opacity) else state.opacity
        root.animate().alpha(alpha).setDuration(120).start()
    }

    fun refit() {
        clampToScreen()
        applyBounds()
    }

    private fun clampToScreen() {
        val edge = activity.dp(VISIBLE_EDGE_DP)
        state.x = clampReferencePosition(state.x, state.width, anchor.width, edge)
        state.y = clampReferencePosition(state.y, windowHeight(), anchor.height, edge)
    }

    private fun applyBounds() {
        window.update(state.x, state.y, state.width, windowHeight(), true)
        fitToolbar()
    }

    /** Keeps all the tools in one row by shrinking them as the window gets smaller. */
    private fun fitToolbar() {
        val available = state.width - activity.dp(10)
        val size = (available / toolButtons.size)
            .coerceIn(activity.dp(MIN_TOOL_DP), activity.dp(MAX_TOOL_DP))
        val padding = size / 5
        toolButtons.forEach { button ->
            button.setPadding(padding, padding, padding, padding)
            button.layoutParams = button.layoutParams.apply {
                width = size
                height = size
            }
        }
    }

    private fun createResizeHandle(): View = TextView(activity).apply {
        text = "◢"
        textSize = 16f
        gravity = Gravity.BOTTOM or Gravity.END
        setPadding(0, 0, activity.dp(5), activity.dp(1))
        setTextColor(FOAM)
        setShadowLayer(3f, 0f, 0f, SURFACE)
        contentDescription = tr("Resize reference")
        var startX = 0f
        var startWidth = 0
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startWidth = state.width
                }
                MotionEvent.ACTION_MOVE -> {
                    state.width = (startWidth + event.rawX - startX).toInt()
                        .coerceIn(activity.dp(MIN_WIDTH_DP), anchor.width)
                    applyBounds()
                    image.limitPan()
                }
                MotionEvent.ACTION_UP -> host.onReferenceChanged()
            }
            true
        }
    }

    private fun createBar(): View {
        fun toggle(button: View, on: Boolean) {
            button.background = if (on) activity.rounded(ACCENT, 8) else null
        }
        lateinit var grayButton: View
        lateinit var flipButton: View
        val actions = listOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_invert, tr("Grayscale")) {
                state.grayscale = !state.grayscale
                toggle(grayButton, state.grayscale)
                image.invalidate()
                host.onReferenceChanged()
            },
            Triple(R.drawable.ic_flip, tr("Flip")) {
                state.flipped = !state.flipped
                toggle(flipButton, state.flipped)
                image.invalidate()
                host.onReferenceChanged()
            },
            Triple(R.drawable.ic_fit, tr("Fit")) {
                state.zoom = 1f
                state.panX = 0f
                state.panY = 0f
                image.invalidate()
                host.onReferenceChanged()
            },
            Triple(R.drawable.ic_pin, tr("Pin for tracing")) { pin() },
            Triple(R.drawable.ic_palette, tr("Save its palette")) { host.onReferencePalette(this) },
            Triple(R.drawable.ic_layersfile, tr("Add as layer")) { host.onReferenceToLayer(this) },
            Triple(R.drawable.ic_close, tr("Close")) { host.onReferenceClosed(this) },
        )
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            actions.forEach { (icon, name, action) ->
                val button = activity.iconButton(icon, name) { action() }.apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    tooltipText = name
                }
                toolButtons += button
                if (icon == R.drawable.ic_invert) grayButton = button
                if (icon == R.drawable.ic_flip) flipButton = button
                addView(button, LinearLayout.LayoutParams(activity.dp(MAX_TOOL_DP), activity.dp(MAX_TOOL_DP)))
            }
        }
        toggle(grayButton, state.grayscale)
        toggle(flipButton, state.flipped)
        val opacity = SeekBar(activity).apply {
            max = 100
            min = 15
            progress = (state.opacity * 100).roundToInt()
            contentDescription = tr("Reference opacity")
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            onChange {
                state.opacity = it / 100f
                root.alpha = state.opacity
                host.onReferenceChanged()
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(activity.dp(4), activity.dp(2), activity.dp(4), activity.dp(2))
            isClickable = true
            addView(
                HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row)
                },
            )
            addView(opacity, LinearLayout.LayoutParams(-1, activity.dp(22)))
        }
    }

    private fun pin() {
        state.pinned = true
        bar.visibility = View.GONE
        window.isTouchable = false
        applyBounds()
        host.onReferenceChanged()
        Toast.makeText(activity, tr("Pinned. You can draw over it. Tap it in References to close it."), Toast.LENGTH_LONG).show()
    }

    private inner class ReferenceImage : View(activity) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        private val drawMatrix = Matrix()
        private val inverse = Matrix()
        private var lastX = 0f
        private var lastY = 0f

        private val scale = ScaleGestureDetector(
            activity,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    state.zoom = (state.zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                    limitPan()
                    return true
                }
            },
        )
        private val press = GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    bar.visibility = if (bar.visibility == VISIBLE) GONE else VISIBLE
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    if (!scale.isInProgress) host.onReferenceColor(pickColor(event.x, event.y))
                }
            },
        )

        init {
            contentDescription = tr("Floating reference")
        }

        fun limitPan() {
            state.panX = clampReferencePan(state.panX, state.zoom, width)
            state.panY = clampReferencePan(state.panY, state.zoom, height)
            invalidate()
        }

        private fun updateMatrix() {
            val base = width / bitmap.width.toFloat() * state.zoom
            drawMatrix.setScale(base, base)
            drawMatrix.postTranslate(
                width / 2f - bitmap.width * base / 2f + state.panX,
                height / 2f - bitmap.height * base / 2f + state.panY,
            )
            if (state.flipped) drawMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        }

        private fun pickColor(x: Float, y: Float): Int {
            updateMatrix()
            drawMatrix.invert(inverse)
            val point = floatArrayOf(x, y).also(inverse::mapPoints)
            val pixelX = point[0].toInt().coerceIn(0, bitmap.width - 1)
            val pixelY = point[1].toInt().coerceIn(0, bitmap.height - 1)
            return bitmap.getPixel(pixelX, pixelY) or Color.BLACK
        }

        override fun onDraw(canvas: Canvas) {
            updateMatrix()
            paint.colorFilter = if (state.grayscale) gray else null
            canvas.drawBitmap(bitmap, drawMatrix, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scale.onTouchEvent(event)
            press.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scale.isInProgress) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    if (state.zoom > 1.01f) {
                        state.panX += if (state.flipped) -dx else dx
                        state.panY += dy
                        limitPan()
                    } else {
                        state.x += dx.toInt()
                        state.y += dy.toInt()
                        clampToScreen()
                        window.update(state.x, state.y, -1, -1)
                    }
                }
                MotionEvent.ACTION_UP -> host.onReferenceChanged()
            }
            return true
        }
    }
}
