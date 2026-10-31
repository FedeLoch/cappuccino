package art.cappuccino.paint

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

const val SURFACE = 0xFF2C2C2A.toInt()
const val SURFACE_RAISED = 0xFF444441.toInt()
const val TEXT_COLOR = 0xFFF1EFE8.toInt()
const val MUTED_TEXT = 0xFFB4B2A9.toInt()
const val ACCENT = 0xFFD85A30.toInt()

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

fun SeekBar.onChange(change: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
            if (fromUser) change(value)
        }
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    })
}
