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
import android.graphics.drawable.GradientDrawable
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
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

private const val SHEET_MAX_WIDTH = 720
private const val SHEET_MARGIN = 32
private const val PRESET_COLUMNS = 4
private const val TWO_COLUMN_WIDTH = 620
private const val PREVIEW_MAX_HEIGHT = 420
private const val BOOK_WIDTH = 168
private const val BOOK_HEIGHT = 224
private const val SWATCH_SIZE = 34
private const val SMALL_SHEET_WIDTH = 440
private const val SWATCH_COLUMNS = 6
private const val DISABLED_ALPHA = 0.45f

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

private const val NOTE_SHORT_MS = 2200L
private const val NOTE_LONG_MS = 3800L
private const val NOTE_FADE_MS = 160L
private var openNote: PopupWindow? = null

/** A small message from your companion, in place of a plain system one. */
fun Activity.say(message: String, long: Boolean = false) {
    val root = window.decorView
    root.post {
        if (isDestroyed || isFinishing || root.windowToken == null) return@post
        runCatching { openNote?.dismiss() }
        val note = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ROAST, 22)
            setPadding(dp(8), dp(6), dp(18), dp(6))
            alpha = 0f
            addView(
                CompanionView(context).apply {
                    val studio = this@say as? MainActivity
                    look = studio?.companionLook ?: CompanionLook.valueOf(
                        getSharedPreferences("studio", Context.MODE_PRIVATE).getString("mascot", null) ?: CompanionLook.COFFEE_DROP.name,
                    )
                    studio?.let { bodyColor = it.currentColor }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(34), dp(34)),
            )
            addView(label(message, 14f, Color.WHITE), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val popup = PopupWindow(note, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isTouchable = false
            showAtLocation(root, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(14))
        }
        openNote = popup
        note.animate().alpha(1f).setDuration(NOTE_FADE_MS).start()
        root.postDelayed({
            note.animate().alpha(0f).setDuration(NOTE_FADE_MS).withEndAction {
                runCatching { popup.dismiss() }
                if (openNote === popup) openNote = null
            }.start()
        }, if (long) NOTE_LONG_MS else NOTE_SHORT_MS)
    }
}

class SheetChoice(val label: String, val icon: Int? = null, val danger: Boolean = false, val run: () -> Unit)

private fun Context.sheetRow(icon: Int?, content: String, tint: Int, onClick: () -> Unit) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(42)
    setOnClickListener { onClick() }
    if (icon != null) {
        addView(
            ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(if (tint == ESPRESSO) COCOA else tint)
            },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) },
        )
    }
    addView(label(content, 14f, tint))
}

private fun Context.smallSheetBody(title: String, close: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(24), dp(20), dp(24), dp(18))
    addView(
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(serif(title, 22f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(creamChip(tr("Cancel")) { close() })
        },
    )
}

private fun Context.sheetButton(content: String, tint: Int, onClick: () -> Unit) = label(content, 15f, Color.WHITE).apply {
    gravity = Gravity.CENTER
    minHeight = dp(44)
    minWidth = dp(112)
    setPadding(dp(20), 0, dp(20), 0)
    background = rounded(tint, 12)
    setOnClickListener { onClick() }
}

private fun Activity.smallSheet(content: View): Dialog = sheet(content).apply {
    window?.setLayout(
        min(dp(SMALL_SHEET_WIDTH), resources.displayMetrics.widthPixels - dp(SHEET_MARGIN)),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

/** A short list of choices on the same cream card as the rest of the home screen. */
fun Activity.choiceSheet(title: String, choices: List<SheetChoice>) {
    lateinit var dialog: Dialog
    val content = smallSheetBody(title) { dialog.dismiss() }.apply {
        addView(View(context), LinearLayout.LayoutParams(-1, dp(8)))
        choices.forEach { choice ->
            addView(
                sheetRow(choice.icon, choice.label, if (choice.danger) ACCENT else ESPRESSO) {
                    dialog.dismiss()
                    choice.run()
                },
            )
        }
    }
    dialog = smallSheet(content)
    dialog.show()
}

/** Asks for one short piece of text. */
fun Activity.askSheet(title: String, current: String, done: (String) -> Unit) {
    lateinit var dialog: Dialog
    val field = EditText(this).apply {
        setText(current)
        setSelectAllOnFocus(true)
    }
    fieldStyle(field, "")
    val content = smallSheetBody(title) { dialog.dismiss() }.apply {
        addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        addView(
            sheetButton(tr("Save"), ACCENT) {
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) {
                    dialog.dismiss()
                    done(text)
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END
                topMargin = dp(16)
            },
        )
    }
    dialog = smallSheet(content)
    dialog.show()
}

/** Double checks before something that can't be taken back. */
fun Activity.confirmSheet(title: String, message: String?, action: () -> Unit) {
    lateinit var dialog: Dialog
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(22), dp(24), dp(18))
        addView(serif(title, 22f))
        if (message != null) {
            addView(label(message, 14f, COCOA), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(creamChip(tr("Keep")) { dialog.dismiss() })
                addView(
                    sheetButton(tr("Delete"), ACCENT) {
                        dialog.dismiss()
                        action()
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) },
        )
    }
    dialog = smallSheet(content)
    dialog.show()
}

/** Four small pages from the companion the first time the app opens. */
fun Activity.tourSheet(look: CompanionLook, finished: () -> Unit) {
    val pages = listOf(
        tr("Hi, I'm your companion.") to tr("I look at your art while you draw and leave small notes. Tap me to read them, or drag me out of the way."),
        tr("Draw with your pen") to tr("Your fingers move the canvas. Two fingers tap to undo, three to redo, four to hide everything."),
        tr("Pick any color") to tr("Hold the pen still, or hold a finger on the canvas, and the color wheel appears. It works on references too."),
        tr("Need some quiet?") to tr("Ask me to hide and I'll peek from the toolbar's corner. Tap me when you want me back."),
    )
    var page = 0
    lateinit var dialog: Dialog
    val face = CompanionView(this).apply {
        this.look = look
        contentDescription = tr("Your companion")
    }
    val heading = serif("", 24f)
    val text = label("", 15f, COCOA)
    val next = sheetButton("", ACCENT) {}
    fun show() {
        heading.text = pages[page].first
        text.text = pages[page].second
        next.text = if (page == pages.lastIndex) tr("Start drawing") else tr("Next")
    }
    next.setOnClickListener {
        if (page == pages.lastIndex) dialog.dismiss() else page++
        show()
    }
    show()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(28), dp(24), dp(28), dp(22))
        addView(face, LinearLayout.LayoutParams(dp(72), dp(72)))
        addView(heading, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        addView(text.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(next, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
    }
    dialog = smallSheet(content)
    dialog.setOnDismissListener { finished() }
    dialog.show()
}

private const val WARMUP_CARD_DP = 128
private const val PREVIEW_PIXELS = 256

/** A small picture of an exercise's guide, on white paper like the canvas. */
private fun Context.warmupPreview(warmup: Warmup, size: Int) = ImageView(this).apply {
    setImageBitmap(drawWarmupGuide(warmup.kind, PREVIEW_PIXELS, PREVIEW_PIXELS))
    background = rounded(Color.WHITE, 10)
    clipToOutline = true
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(size, size)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/**
 * Lets you pick a warm-up exercise, with the one your notes point to suggested first. Warm-ups are
 * only practice, so nothing from them is saved.
 */
fun Activity.warmupPicker(look: CompanionLook, suggested: Warmup, current: Warmup?, pick: (Warmup) -> Unit) {
    lateinit var dialog: Dialog
    fun start(warmup: Warmup) {
        dialog.dismiss()
        pick(warmup)
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(26), dp(22), dp(26), dp(20))
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    CompanionView(context).apply {
                        this.look = look
                        contentDescription = tr("Your companion")
                    },
                    LinearLayout.LayoutParams(dp(52), dp(52)),
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(serif(tr("Warm up"), 24f))
                        addView(label(tr("Pick an exercise. Warm-ups are just practice, so nothing is saved."), 14f, COCOA))
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) },
                )
            },
        )
        if (suggested != current) {
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(OAT, 18)
                    setPadding(dp(12), dp(12), dp(18), dp(12))
                    setOnClickListener { start(suggested) }
                    addView(warmupPreview(suggested, dp(104)))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(tr("Suggested for you"), 12f, ACCENT))
                            addView(serif(tr(suggested.title), 20f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                            addView(label(tr(suggested.steps), 14f, COCOA), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                        },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
                    )
                    addView(sheetButton(tr("Start"), ACCENT) { start(suggested) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) },
            )
        }
        WARMUP_GROUPS.forEach { group ->
            addView(label(tr(group), 13f, COCOA), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
            addView(
                FlowRow(context, dp(10)).apply {
                    WARMUPS.filter { it.group == group }.forEach { warmup ->
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(16).toFloat()
                                    setColor(OAT)
                                    if (warmup == current) setStroke(dp(2), ACCENT)
                                }
                                setPadding(dp(8), dp(8), dp(8), dp(10))
                                contentDescription = tr(warmup.title)
                                setOnClickListener { start(warmup) }
                                addView(warmupPreview(warmup, dp(WARMUP_CARD_DP - 16)))
                                addView(
                                    label(tr(warmup.title), 13f, ESPRESSO).apply { maxLines = 2 },
                                    LinearLayout.LayoutParams(dp(WARMUP_CARD_DP - 16), -2).apply { topMargin = dp(8) },
                                )
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )
        }
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                addView(creamChip(tr("Not now")) { dialog.dismiss() })
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) },
        )
    }
    dialog = sheet(content)
    dialog.show()
}

/** Names a new sketchbook beside a cover that follows along as you choose. */
fun Activity.newSketchbookSheet(firstColor: Int, create: (String, Int) -> Unit) {
    lateinit var dialog: Dialog
    var color = firstColor
    val title = serif(tr("Untitled"), 20f).apply {
        setTextColor(Color.WHITE)
        maxLines = 4
    }
    val spine = View(this)
    val book = FrameLayout(this).apply {
        addView(spine, FrameLayout.LayoutParams(dp(14), -1))
        addView(
            title,
            FrameLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(28), dp(18), dp(14), dp(14))
            },
        )
    }
    val swatches = ArrayList<View>()
    fun paint() {
        val (cover, edge) = SKETCHBOOK_COLORS[color]
        book.background = rounded(cover, 14)
        spine.background = rounded(edge, 7)
        swatches.forEachIndexed { index, swatch ->
            val (fill) = SKETCHBOOK_COLORS[index]
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(if (index == color) 3 else 1), if (index == color) ESPRESSO else OAT)
            }
        }
    }
    val createButton = label(tr("Create"), 15f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minWidth = dp(112)
        setPadding(dp(20), 0, dp(20), 0)
        background = rounded(ACCENT, 12)
        alpha = DISABLED_ALPHA
    }
    val field = EditText(this)
    fieldStyle(field, tr("Untitled"))
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(text: Editable?) {
            val name = text.toString().trim()
            title.text = name.ifEmpty { tr("Untitled") }
            createButton.alpha = if (name.isEmpty()) DISABLED_ALPHA else 1f
        }
    })
    createButton.setOnClickListener {
        val name = field.text.toString().trim()
        if (name.isEmpty()) return@setOnClickListener
        dialog.dismiss()
        create(name, color)
    }
    val wide = resources.displayMetrics.widthPixels / resources.displayMetrics.density >= TWO_COLUMN_WIDTH
    val choices = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(tr("Name"), 13f, COCOA))
        addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(label(tr("Cover"), 13f, COCOA), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        addView(
            GridLayout(context).apply {
                columnCount = SWATCH_COLUMNS
                SKETCHBOOK_COLORS.indices.forEach { index ->
                    val swatch = View(context).apply {
                        contentDescription = tr("Cover color %1", index + 1)
                        setOnClickListener {
                            color = index
                            paint()
                        }
                    }
                    swatches += swatch
                    addView(
                        swatch,
                        GridLayout.LayoutParams().apply {
                            width = dp(SWATCH_SIZE)
                            height = dp(SWATCH_SIZE)
                            setMargins(0, 0, dp(10), dp(10))
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) },
        )
    }
    paint()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(22), dp(24), dp(22))
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(serif(tr("New sketchbook"), 26f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(creamChip(tr("Cancel")) { dialog.dismiss() })
            },
        )
        addView(
            LinearLayout(context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                addView(
                    book,
                    LinearLayout.LayoutParams(dp(BOOK_WIDTH), dp(BOOK_HEIGHT)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
                addView(
                    choices,
                    LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f).apply {
                        if (wide) marginStart = dp(24) else topMargin = dp(18)
                    },
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) },
        )
        addView(
            createButton,
            LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END
                topMargin = dp(8)
            },
        )
    }
    dialog = sheet(content)
    dialog.show()
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
    fun action(icon: Int, content: String, tint: Int = ESPRESSO, run: () -> Unit) = sheetRow(icon, content, tint) {
        dialog.dismiss()
        run()
    }
    val screenWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density
    val wide = screenWidth >= TWO_COLUMN_WIDTH
    val previewWidth = dp(if (wide) 340 else 300)
    val preview = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        maxHeight = dp(PREVIEW_MAX_HEIGHT)
        background = rounded(OAT, 12)
        clipToOutline = true
        contentDescription = art.name
        thumbnails.show(store.previewFile(art.id), previewWidth, this)
    }
    val facts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(serif(art.name, 26f))
        addView(
            label(tr("Edited %1", DateUtils.getRelativeTimeSpanString(art.updated).toString().lowercase()), 13f, COCOA),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) },
        )
        val size = if (art.layers == 1) {
            tr("%1 x %2 pixels · 1 layer", art.width, art.height)
        } else {
            tr("%1 x %2 pixels · %3 layers", art.width, art.height, art.layers)
        }
        addView(label(size, 13f, COCOA))
        addView(label(sketchbook, 13f, COCOA))
        addView(
            label(tr("Continue drawing"), 15f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = rounded(ACCENT, 12)
                minHeight = dp(46)
                setOnClickListener {
                    dialog.dismiss()
                    open(art.id)
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
        )
        addView(View(context), LinearLayout.LayoutParams(-1, dp(6)))
        addView(action(R.drawable.ic_rename, tr("Rename"), run = rename))
        addView(action(R.drawable.ic_gallery, tr("Move to sketchbook"), run = move))
        addView(action(R.drawable.ic_duplicate, tr("Duplicate"), run = duplicate))
        addView(action(R.drawable.ic_palette, tr("Make a variation"), run = variation))
        addView(action(R.drawable.ic_trash, tr("Delete"), ACCENT, delete))
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
