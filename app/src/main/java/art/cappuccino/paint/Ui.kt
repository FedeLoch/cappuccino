package art.cappuccino.paint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

const val SURFACE = 0xFF2C2C2A.toInt()
const val SURFACE_RAISED = 0xFF444441.toInt()
const val TEXT_COLOR = 0xFFF1EFE8.toInt()
const val MUTED_TEXT = 0xFFB4B2A9.toInt()
const val ACCENT = 0xFFD85A30.toInt()
const val ESPRESSO = 0xFF2A1D16.toInt()
const val ROAST = 0xFF3A291F.toInt()
const val FOAM = 0xFFF4E9D8.toInt()
const val LATTE = 0xFFD8C4AC.toInt()

val SKETCHBOOK_COLORS = listOf(
    0xFFD85A30.toInt() to 0xFF993C1D.toInt(),
    0xFF1D9E75.toInt() to 0xFF0F6E56.toInt(),
    0xFF534AB7.toInt() to 0xFF3C3489.toInt(),
    0xFFBA7517.toInt() to 0xFF854F0B.toInt(),
    0xFFD4537E.toInt() to 0xFF993556.toInt(),
    0xFF378ADD.toInt() to 0xFF185FA5.toInt(),
)

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

fun Context.label(content: String, size: Float = 14f, color: Int = TEXT_COLOR) =
    TextView(this).apply {
        text = content
        textSize = size
        setTextColor(color)
    }

fun Context.chip(content: String, selected: Boolean = false, onClick: () -> Unit) =
    label(content).apply {
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(if (selected) ACCENT else SURFACE_RAISED, 20)
        setOnClickListener { onClick() }
    }

/** A small button for compact surfaces, such as the companion bubble. */
fun Context.smallChip(content: String, selected: Boolean = false, onClick: () -> Unit) =
    label(content, 12f).apply {
        gravity = Gravity.CENTER
        minHeight = dp(28)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = rounded(if (selected) ACCENT else SURFACE_RAISED, 14)
        setOnClickListener { onClick() }
    }

/** One choice from a short list, in a single rounded track. */
fun Context.segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) =
    LinearLayout(this).apply {
        background = rounded(SURFACE_RAISED, 10)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        options.forEachIndexed { index, option ->
            addView(
                label(option, 11f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    minHeight = dp(30)
                    if (index == selected) background = rounded(ACCENT, 8)
                    setOnClickListener { onPick(index) }
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }
    }

/** A compact icon and caption for a grid of actions. */
fun Context.iconTile(icon: Int, title: String, selected: Boolean = false, onClick: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(7), dp(2), dp(5))
        background = rounded(if (selected) ACCENT else SURFACE_RAISED, 10)
        contentDescription = title
        setOnClickListener { onClick() }
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(label(title, 10f).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
    }

fun Context.tileGrid(tiles: List<View>, columns: Int = 4) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    tiles.chunked(columns).forEachIndexed { rowIndex, row ->
        addView(
            LinearLayout(context).apply {
                row.forEachIndexed { index, tile ->
                    addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(4) })
                }
                repeat(columns - row.size) {
                    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(4) })
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = dp(4) },
        )
    }
}

/** A list row with an icon, a title, and an optional line of detail. */
fun Context.actionRow(icon: Int, title: String, detail: String? = null, onClick: () -> Unit) =
    LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(SURFACE_RAISED, 10)
        setOnClickListener { onClick() }
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 14f))
                detail?.let { addView(label(it, 12f, MUTED_TEXT)) }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) },
        )
    }

/** A square card with an icon, a title, and a short explanation of the tool. */
fun Context.toolCard(icon: Int, title: String, detail: String, selected: Boolean, onClick: () -> Unit) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(if (selected) ACCENT else SURFACE_RAISED, 12)
        setOnClickListener { onClick() }
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(label(title, 13f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        addView(label(detail, 11f, if (selected) TEXT_COLOR else MUTED_TEXT))
    }

fun Context.iconButton(icon: Int, description: String, onClick: (View) -> Unit) =
    ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener(onClick)
    }

fun Context.pill(vertical: Boolean = false) = LinearLayout(this).apply {
    orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    background = rounded(SURFACE, 24)
    setPadding(dp(6), dp(2), dp(6), dp(2))
}

/** Paints a sample stroke with a rough match of the brush, without using the GL engine. */
class BrushPreview(
    context: Context,
    private val kind: BrushKind,
    private val color: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(kind.id.hashCode().toLong())
    private val tipBitmap = kind.tip?.let { tip ->
        val side = sqrt(tip.size.toFloat()).toInt()
        Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(tip))
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = tr("%1 preview", tr(kind.label))
    }

    override fun onDraw(canvas: Canvas) {
        random.setSeed(kind.id.hashCode().toLong())
        val maxRadius = height * 0.22f
        val softness = (1f - kind.hardness) * maxRadius
        paint.maskFilter = if (softness > 0.5f) BlurMaskFilter(softness, BlurMaskFilter.Blur.NORMAL) else null
        val steps = 90
        for (step in 0..steps) {
            val t = step / steps.toFloat()
            val pressure = sin(t * PI.toFloat())
            val scale = kind.minScale + (1f - kind.minScale) * pressure
            val x = width * (0.1f + 0.8f * t)
            val y = height * (0.5f + 0.26f * sin(t * 2f * PI.toFloat()))
            val grainHole = kind.grain * 0.55f > random.nextFloat()
            if (grainHole) continue
            val flow = kind.flow * (1f - kind.pressureFlow * (1f - pressure))
            paint.color = color
            paint.alpha = (255 * minOf(1f, flow * 1.6f + 0.05f)).toInt()
            val radius = maxOf(1.5f, maxRadius * scale)
            val stamp = tipBitmap
            if (stamp != null) {
                canvas.drawBitmap(stamp, null, RectF(x - radius, y - radius, x + radius, y + radius), paint)
            } else {
                canvas.drawCircle(x, y, radius, paint)
            }
        }
    }
}

fun SeekBar.onChange(change: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
            if (fromUser) change(value)
        }
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    })
}
