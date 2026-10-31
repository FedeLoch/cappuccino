package art.cappuccino.paint

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

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

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "${kind.label} preview"
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
            canvas.drawCircle(x, y, maxOf(1.5f, maxRadius * scale), paint)
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
