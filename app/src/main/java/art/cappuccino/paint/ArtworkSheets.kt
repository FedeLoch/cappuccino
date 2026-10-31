package art.cappuccino.paint

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

private const val SHEET_MAX_WIDTH = 720
private const val SHEET_MARGIN = 32
private const val PRESET_COLUMNS = 4
private const val TWO_COLUMN_WIDTH = 620

class CanvasPreset(val label: String, val width: Int, val height: Int, val tall: Boolean = false)

val CANVAS_PRESETS = listOf(
    CanvasPreset("Default", 4096, 2732),
    CanvasPreset("Square", 3000, 3000),
    CanvasPreset("A4", 3508, 2480, tall = true),
    CanvasPreset("A3", 4961, 3508, tall = true),
    CanvasPreset("Wide video", 3840, 2160),
    CanvasPreset("Phone story", 1920, 1080, tall = true),
)

/** Returns a size turned to the orientation asked for. */
fun orientedSize(width: Int, height: Int, portrait: Boolean) =
    if (portrait) min(width, height) to max(width, height) else max(width, height) to min(width, height)

fun Context.fieldStyle(field: EditText, hintText: String) = field.apply {
    hint = hintText
    setTextColor(ESPRESSO)
    setHintTextColor(COCOA)
    background = rounded(OAT, 10)
    setPadding(dp(12), dp(10), dp(12), dp(10))
    textSize = 15f
    setSingleLine()
}

/** A cream card floating over the page, used by the home screen's larger choices. */
fun Activity.sheet(content: View): Dialog {
    val dialog = Dialog(this)
    dialog.setContentView(
        ScrollView(this).apply {
            background = rounded(PAGE_CREAM, 20)
            clipToOutline = true
            addView(content)
        },
    )
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val available = resources.displayMetrics.widthPixels - dp(SHEET_MARGIN)
        setLayout(min(dp(SHEET_MAX_WIDTH), available), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    return dialog
}

private fun Context.serif(content: String, size: Float) = label(content, size, ESPRESSO).apply {
    typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
}

/** A small sheet of paper in a canvas's proportions. */
private class PaperShape(context: Context, private val ratio: Float, private val ink: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val longest = min(width, height) * 0.86f
        val paperWidth = if (ratio >= 1f) longest else longest * ratio
        val paperHeight = if (ratio >= 1f) longest / ratio else longest
        box.set(
            (width - paperWidth) / 2f, (height - paperHeight) / 2f,
            (width + paperWidth) / 2f, (height + paperHeight) / 2f,
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(box, 6f, 6f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(2).toFloat()
        paint.color = ink
        canvas.drawRoundRect(box, 6f, 6f, paint)
    }
}

/** Chooses a canvas, a name, and a sketchbook, all in one place. */
class NewArtworkSheet(
    private val activity: Activity,
    private val projects: List<Project>,
    private var project: String,
    private val create: (Int, Int, String, String) -> Unit,
) {
    private var preset: CanvasPreset? = CANVAS_PRESETS.first()
    private var portrait = false
    private var name = ""
    private var customWidth = ""
    private var customHeight = ""
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val memory = ActivityManager.MemoryInfo().also {
        activity.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem
    private var summary: TextView? = null
    private var createButton: View? = null
    private lateinit var dialog: Dialog

    fun show() {
        render()
        dialog = activity.sheet(body)
        dialog.show()
    }

    private fun size(): Pair<Int, Int>? {
        preset?.let { return orientedSize(it.width, it.height, portrait) }
        val width = customWidth.toIntOrNull() ?: return null
        val height = customHeight.toIntOrNull() ?: return null
        return if (width in 64..MAX_CANVAS_SIDE && height in 64..MAX_CANVAS_SIDE) width to height else null
    }

    private fun refreshSummary() {
        val chosen = size()
        createButton?.alpha = if (chosen == null) 0.4f else 1f
        summary?.text = if (chosen == null) {
            tr("Use sizes from 64 to %1 pixels", MAX_CANVAS_SIDE)
        } else {
            tr("%1 x %2 pixels · up to %3 layers", chosen.first, chosen.second, layerLimit(chosen.first, chosen.second, memory))
        }
    }

    private fun render(): Unit = with(activity) {
        body.removeAllViews()
        body.setPadding(dp(22), dp(20), dp(22), dp(20))
        body.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(serif(tr("New artwork"), 26f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(creamChip(tr("Cancel")) { dialog.dismiss() })
            },
        )
        body.addView(presetGrid(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if (preset == null) {
            body.addView(
                LinearLayout(this).apply {
                    fun field(text: String, hintText: String, change: (String) -> Unit) = fieldStyle(EditText(this@with), hintText).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setText(text)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                change(s.toString())
                                refreshSummary()
                            }
                        })
                    }
                    addView(field(customWidth, tr("Width in pixels")) { customWidth = it }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(
                        field(customHeight, tr("Height in pixels")) { customHeight = it },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
                    )
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
            )
        } else if (preset?.width != preset?.height) {
            body.addView(
                LinearLayout(this).apply {
                    addView(creamChip(tr("Landscape"), !portrait) { turn(false) })
                    addView(
                        creamChip(tr("Portrait"), portrait) { turn(true) },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
                    )
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
            )
        }
        body.addView(heading(tr("Name")))
        body.addView(
            fieldStyle(EditText(this), tr("Untitled")).apply {
                setText(name)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        name = s.toString()
                    }
                })
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        body.addView(heading(tr("Sketchbook")))
        body.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    LinearLayout(context).apply {
                        (projects.map { it.id to it.name } + ("" to tr("Unsorted"))).forEach { (id, title) ->
                            addView(
                                creamChip(title, project == id) {
                                    project = id
                                    render()
                                },
                                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
                            )
                        }
                    },
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        body.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                val note = label("", 13f, COCOA)
                summary = note
                addView(note, LinearLayout.LayoutParams(0, -2, 1f))
                createButton = chip(tr("Create"), true) {
                    size()?.let { (width, height) ->
                        dialog.dismiss()
                        create(width, height, name.trim(), project)
                    }
                }
                addView(createButton)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) },
        )
        refreshSummary()
    }

    private fun Context.heading(content: String) = label(content, 13f, COCOA).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun turn(toPortrait: Boolean) {
        portrait = toPortrait
        render()
    }

    private fun presetGrid(): GridLayout = with(activity) {
        GridLayout(this).apply {
            columnCount = PRESET_COLUMNS
            val options = CANVAS_PRESETS + null
            val inner = min(dp(SHEET_MAX_WIDTH), resources.displayMetrics.widthPixels - dp(SHEET_MARGIN)) - dp(44)
            val cell = inner / PRESET_COLUMNS - dp(6)
            options.forEach { option ->
                val chosen = option === preset
                val tall = if (chosen) portrait else option?.tall == true
                val (shownWidth, shownHeight) = option?.let { orientedSize(it.width, it.height, tall) } ?: (0 to 0)
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        background = rounded(if (chosen) ACCENT else OAT, 12)
                        setPadding(dp(6), dp(8), dp(6), dp(8))
                        val ink = if (chosen) ESPRESSO else COCOA
                        addView(
                            if (option != null) {
                                PaperShape(context, shownWidth / shownHeight.toFloat(), ink)
                            } else {
                                label("+", 28f, if (chosen) Color.WHITE else COCOA).apply { gravity = Gravity.CENTER }
                            },
                            LinearLayout.LayoutParams(-1, dp(52)),
                        )
                        val text = if (chosen) Color.WHITE else ESPRESSO
                        addView(label(option?.let { tr(it.label) } ?: tr("Custom size"), 13f, text).apply { gravity = Gravity.CENTER })
                        addView(
                            label(if (option != null) "$shownWidth x $shownHeight" else tr("Your own"), 11f, if (chosen) Color.WHITE else COCOA)
                                .apply { gravity = Gravity.CENTER },
                        )
                        setOnClickListener {
                            preset = option
                            portrait = option?.tall == true
                            render()
                        }
                    },
                    GridLayout.LayoutParams().apply {
                        width = cell
                        setMargins(0, 0, dp(6), dp(6))
                    },
                )
            }
        }
    }
}

/** Everything about one artwork, with the actions you can take on it. */
fun Activity.artworkSheet(
    art: ArtworkInfo,
    sketchbook: String,
    family: List<ArtworkInfo>,
    thumbnails: ReferenceThumbnails,
    store: ArtworkStore,
    open: (String) -> Unit,
    rename: () -> Unit,
    move: () -> Unit,
    duplicate: () -> Unit,
    variation: () -> Unit,
    delete: () -> Unit,
) {
    lateinit var dialog: Dialog
    fun action(content: String, selected: Boolean = false, run: () -> Unit) = creamChip(content, selected) {
        dialog.dismiss()
        run()
    }
    val screenWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density
    val wide = screenWidth >= TWO_COLUMN_WIDTH
    val previewWidth = dp(if (wide) 340 else 300)
    val preview = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        background = rounded(OAT, 12)
        clipToOutline = true
        contentDescription = art.name
        thumbnails.show(store.previewFile(art.id), previewWidth, this)
    }
    val facts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(serif(art.name, 24f))
        addView(label(tr("Edited %1", DateUtils.getRelativeTimeSpanString(art.updated).toString().lowercase()), 13f, COCOA))
        addView(label(tr("%1 x %2 pixels · %3 layers", art.width, art.height, art.layers), 13f, COCOA))
        addView(label(sketchbook, 13f, COCOA))
        addView(
            action(tr("Continue drawing"), true) { open(art.id) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) },
        )
        val more = GridLayout(context).apply {
            columnCount = 2
            listOf(
                action(tr("Rename"), run = rename),
                action(tr("Move to project"), run = move),
                action(tr("Duplicate"), run = duplicate),
                action(tr("Make a variation"), run = variation),
                action(tr("Delete"), run = delete),
            ).forEach { chipView ->
                addView(chipView, GridLayout.LayoutParams().apply { setMargins(0, 0, dp(6), dp(6)) })
            }
        }
        addView(more, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        addView(
            LinearLayout(context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                addView(preview, LinearLayout.LayoutParams(if (wide) previewWidth else -1, -2))
                addView(
                    facts,
                    LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f).apply {
                        if (wide) marginStart = dp(18) else topMargin = dp(14)
                    },
                )
            },
        )
        if (family.size > 1) {
            addView(label(tr("Variations"), 13f, COCOA), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(
                        LinearLayout(context).apply {
                            family.forEach { member ->
                                addView(
                                    FrameLayout(context).apply {
                                        background = rounded(if (member.id == art.id) ACCENT else OAT, 10)
                                        setPadding(dp(3), dp(3), dp(3), dp(3))
                                        addView(
                                            ImageView(context).apply {
                                                scaleType = ImageView.ScaleType.CENTER_CROP
                                                clipToOutline = true
                                                background = rounded(OAT, 8)
                                                contentDescription = member.name
                                                thumbnails.show(store.thumbnail(member.id), dp(120), this)
                                            },
                                        )
                                        setOnClickListener {
                                            dialog.dismiss()
                                            open(member.id)
                                        }
                                    },
                                    LinearLayout.LayoutParams(dp(120), dp(84)).apply { marginEnd = dp(8) },
                                )
                            }
                        },
                    )
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
        }
    }
    dialog = sheet(content)
    dialog.show()
}
