package art.cappuccino.paint

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import java.io.File
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val MIN_BRUSH = 4
private const val MAX_BRUSH = 300
private const val AUTOSAVE_DELAY_MS = 2500L
private const val NAP_DELAY_MS = 90_000L
private const val PICK_REFERENCES = 41
private const val PREFERENCES = "studio"
private const val PANEL_WIDTH = 340
private const val THUMBNAIL_WIDTH = 72
private const val THUMBNAIL_HEIGHT = 48
private const val LAYER_ROW = 56
private const val LAYER_GAP = 4

class MainActivity : Activity() {
    private lateinit var canvas: CanvasView
    private lateinit var store: ArtworkStore
    private lateinit var document: ArtworkDocument
    private lateinit var companion: CompanionView

    private val handler = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor()
    private val savedFiles = HashMap<Int, String>()
    private val recentColors = ArrayList<Int>()
    private val chrome = ArrayList<PopupWindow>()
    private val references = HashMap<String, PopupWindow>()
    private var companionWindow: PopupWindow? = null
    private var panel: PopupWindow? = null
    private var panelKey = ""
    private var interfaceHidden = false
    private var unsaved = false
    private var penDown = false
    private var report: AnalysisReport? = null
    private var unreadTips = false
    private var swatch: View? = null

    private val tools = BrushKind.entries.associateWith { ToolSettings(it.defaultSize) }
    private var lastBrush = BrushKind.INK
    private var lastEraser = BrushKind.ERASER_HARD
    private var sizeBar: SeekBar? = null
    private var opacityBar: SeekBar? = null
    private var brushButton: View? = null
    private var eraserButton: View? = null
    private var colorBeforePanel = 0
    private var colorTab = 0
    private var viewMode = 0
    private var guide = 0
    private val layerThumbnails = HashMap<Int, ImageView>()

    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val saveLater = Runnable { save() }
    private val nap = Runnable { if (!unreadTips) companion.mood = Mood.NAP }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ArtworkStore(this)
        canvas = CanvasView(this)
        setContentView(canvas)
        companion = CompanionView(this).apply {
            mascot = Mascot.valueOf(
                preferences.getString("mascot", null) ?: Mascot.PAINT_DROP.name,
            )
            bodyColor = canvas.brushColor
        }
        canvas.onStrokeStart = {
            penDown = true
            closePanel()
            fadeChrome(0.15f)
            handler.removeCallbacks(saveLater)
            handler.removeCallbacks(nap)
        }
        canvas.onStrokeEnd = {
            penDown = false
            fadeChrome(1f)
            if (companion.mood == Mood.NAP) companion.mood = Mood.IDLE
            handler.postDelayed(nap, NAP_DELAY_MS)
            markUnsaved()
        }
        canvas.onGestureStart = { closePanel() }
        canvas.onColorPicked = { setColor(it) }
        canvas.onToggleInterface = {
            interfaceHidden = !interfaceHidden
            fadeChrome(if (interfaceHidden) 0f else 1f)
            closePanel()
        }
        openDocument(intent.getStringExtra(EXTRA_ARTWORK))
        canvas.post { showChrome() }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun openDocument(id: String?) {
        if (id == null) {
            val project = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
            val first = LayerInfo(1, "Layer 1")
            document = ArtworkDocument(
                UUID.randomUUID().toString(), "Untitled", project, 1, 2, mutableListOf(first),
            )
            canvas.act {
                NativeEngine.createLayer(it, 1)
                NativeEngine.setActiveLayer(it, 1)
            }
            canvas.acceptsInput = true
            return
        }
        document = ArtworkDocument(id, "Untitled", "", 0, 1, mutableListOf())
        saver.execute {
            val loaded = store.load(id)
            if (loaded == null) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open this artwork", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@execute
            }
            for (layer in loaded.layers) {
                val pixels = layer.file?.let { store.readLayer(id, it) }
                layer.file?.let { savedFiles[layer.id] = it }
                canvas.act { engine ->
                    NativeEngine.createLayer(engine, layer.id)
                    pixels?.let { NativeEngine.writeLayer(engine, layer.id, it) }
                    applyLayerProperties(engine, layer)
                }
            }
            canvas.act { NativeEngine.setActiveLayer(it, loaded.activeLayer) }
            runOnUiThread {
                document = loaded
                canvas.alphaLock = loaded.layers.firstOrNull { it.id == loaded.activeLayer }?.alphaLock == true
                canvas.acceptsInput = true
            }
        }
    }

    private fun applyLayerProperties(engine: Long, layer: LayerInfo) {
        NativeEngine.setLayerProperties(engine, layer.id, layer.opacity, layer.visible, layer.blend)
    }

    private fun markUnsaved() {
        unsaved = true
        handler.removeCallbacks(saveLater)
        handler.postDelayed(saveLater, AUTOSAVE_DELAY_MS)
    }

    private fun save() {
        handler.removeCallbacks(saveLater)
        if (!unsaved) return
        if (penDown) {
            handler.postDelayed(saveLater, AUTOSAVE_DELAY_MS)
            return
        }
        unsaved = false
        val target = document
        val layers = target.layers.map { it.copy() }
        val active = target.activeLayer
        val nextId = target.nextLayerId
        if (!interfaceHidden) companion.mood = Mood.THINKING
        canvas.snapshot { engine ->
            val pixels = layers.filter { NativeEngine.takeLayerDirty(engine, it.id) }
                .associate { layer ->
                    layer.id to ArtworkStore.layerBuffer().also {
                        NativeEngine.readLayer(engine, layer.id, it)
                    }
                }
            val thumbnail = ArtworkStore.pixelBuffer(
                ArtworkStore.THUMBNAIL_WIDTH, ArtworkStore.THUMBNAIL_HEIGHT,
            )
            NativeEngine.readComposite(
                engine, ArtworkStore.THUMBNAIL_WIDTH, ArtworkStore.THUMBNAIL_HEIGHT, thumbnail,
            )
            saver.execute {
                layers.forEach { it.file = savedFiles[it.id] }
                runCatching {
                    store.save(target, ArtworkSnapshot(layers, active, nextId, pixels, thumbnail))
                    layers.forEach { layer -> layer.file?.let { savedFiles[layer.id] = it } }
                }.onFailure {
                    runOnUiThread {
                        unsaved = true
                        Toast.makeText(this, "Couldn't save your artwork", Toast.LENGTH_LONG).show()
                    }
                }
                analyze(thumbnail)
            }
        }
    }

    private fun analyze(thumbnail: java.nio.ByteBuffer) {
        thumbnail.rewind()
        val raw = thumbnail.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val pixels = IntArray(raw.remaining())
        raw.get(pixels)
        for (index in pixels.indices) {
            val value = pixels[index]
            pixels[index] = (0xFF shl 24) or
                ((value and 0xFF) shl 16) or (value and 0xFF00) or ((value shr 16) and 0xFF)
        }
        val result = analyzeArtwork(pixels, ArtworkStore.THUMBNAIL_WIDTH, ArtworkStore.THUMBNAIL_HEIGHT)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val changed = result.tips != report?.tips
            report = result
            if (changed && result.tips.isNotEmpty()) unreadTips = true
            companion.mood = if (unreadTips) Mood.TIP else Mood.IDLE
        }
    }

    private fun setColor(color: Int) {
        canvas.brushColor = color
        companion.bodyColor = color
        swatch?.background = swatchBackground(color)
        if (canvas.brushKind.isEraser) selectTool(lastBrush)
    }

    private fun selectTool(kind: BrushKind) {
        val settings = tools.getValue(kind)
        canvas.brushKind = kind
        canvas.brushDiameter = settings.diameter
        canvas.brushOpacity = settings.opacity
        if (kind.isEraser) {
            lastEraser = kind
            canvas.eraserKind = kind
        } else {
            lastBrush = kind
        }
        sizeBar?.progress = sizeProgress()
        opacityBar?.progress = (settings.opacity * 1000f).roundToInt()
        brushButton?.background = if (kind.isEraser) null else rounded(SURFACE_RAISED, 18)
        eraserButton?.background = if (kind.isEraser) rounded(SURFACE_RAISED, 18) else null
    }

    private fun swatchBackground(color: Int) = rounded(color, 12).apply {
        setStroke(dp(2), TEXT_COLOR)
    }

    private fun rememberColor() {
        recentColors.remove(canvas.brushColor)
        recentColors.add(0, canvas.brushColor)
        if (recentColors.size > 8) recentColors.removeAt(recentColors.size - 1)
    }

    private fun place(content: View, gravity: Int, x: Int, y: Int) {
        val window = PopupWindow(
            content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { isClippingEnabled = false }
        window.showAtLocation(canvas, gravity, x, y)
        chrome.add(window)
    }

    private fun showChrome() {
        if (isDestroyed) return
        val left = pill().apply {
            addView(
                iconButton(R.drawable.ic_gallery, "Projects") {
                    save()
                    startActivity(
                        Intent(this@MainActivity, GalleryActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                },
            )
            addView(iconButton(R.drawable.ic_actions, "Actions") { togglePanel("actions", ::actionsPanel) })
            addView(iconButton(R.drawable.ic_references, "References") { togglePanel("references", ::referencesPanel) })
            addView(iconButton(R.drawable.ic_view, "Art direction") { togglePanel("view", ::viewPanel) })
        }
        place(left, Gravity.TOP or Gravity.START, dp(12), dp(12))

        val colorButton = View(this).apply {
            background = swatchBackground(canvas.brushColor)
            contentDescription = "Color"
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                setMargins(dp(10), dp(10), dp(10), dp(10))
            }
            setOnClickListener {
                colorBeforePanel = canvas.brushColor
                togglePanel("color", ::colorPanel)
            }
        }
        swatch = colorButton
        val right = pill().apply {
            brushButton = iconButton(R.drawable.ic_brush, "Brushes") {
                if (canvas.brushKind.isEraser) selectTool(lastBrush)
                togglePanel("brushes", ::brushPanel)
            }
            eraserButton = iconButton(R.drawable.ic_eraser, "Eraser") {
                if (canvas.brushKind.isEraser) {
                    togglePanel("erasers", ::eraserPanel)
                } else {
                    selectTool(lastEraser)
                    closePanel()
                }
            }
            addView(brushButton)
            addView(eraserButton)
            addView(iconButton(R.drawable.ic_layers, "Layers") { togglePanel("layers", ::layersPanel) })
            addView(colorButton)
        }
        place(right, Gravity.TOP or Gravity.END, dp(12), dp(12))

        val sliders = pill(vertical = true).apply {
            addView(verticalSlider("Brush size", sizeProgress(), { sizeBar = it }) {
                val share = it / 1000f
                canvas.brushDiameter = MIN_BRUSH + (MAX_BRUSH - MIN_BRUSH) * share * share
                tools.getValue(canvas.brushKind).diameter = canvas.brushDiameter
            })
            addView(verticalSlider("Brush opacity", (canvas.brushOpacity * 1000f).roundToInt(), { opacityBar = it }) {
                canvas.brushOpacity = max(it, 10) / 1000f
                tools.getValue(canvas.brushKind).opacity = canvas.brushOpacity
            })
        }
        place(sliders, Gravity.START or Gravity.CENTER_VERTICAL, dp(6), 0)
        selectTool(canvas.brushKind)
        if (preferences.getBoolean("companion", true)) showCompanion()
    }

    private fun sizeProgress(): Int {
        val share = (canvas.brushDiameter - MIN_BRUSH) / (MAX_BRUSH - MIN_BRUSH)
        return (kotlin.math.sqrt(share) * 1000f).roundToInt()
    }

    private fun verticalSlider(
        name: String,
        value: Int,
        created: (SeekBar) -> Unit,
        change: (Int) -> Unit,
    ): View {
        val length = dp(150)
        val bar = SeekBar(this).apply {
            max = 1000
            progress = value
            rotation = -90f
            contentDescription = name
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.GRAY)
            onChange(change)
        }
        created(bar)
        return FrameLayout(this).apply {
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(dp(36), length + dp(8))
            addView(bar, FrameLayout.LayoutParams(length, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCompanion() {
        val size = dp(56)
        val window = PopupWindow(companion, size, size).apply { isClippingEnabled = false }
        var x = preferences.getInt("companionX", dp(12))
        var y = preferences.getInt("companionY", canvas.height - size - dp(12))
            .coerceIn(0, max(0, canvas.height - size))
        x = x.coerceIn(0, max(0, canvas.width - size))
        var startX = 0f
        var startY = 0f
        var originX = x
        var originY = y
        var dragged = false
        companion.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originX = x
                    originY = y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) + abs(dy) > dp(8)) dragged = true
                    if (dragged) {
                        x = (originX + dx).toInt().coerceIn(0, canvas.width - size)
                        y = (originY + dy).toInt().coerceIn(0, canvas.height - size)
                        window.update(x, y, -1, -1)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        preferences.edit().putInt("companionX", x).putInt("companionY", y).apply()
                    } else {
                        unreadTips = false
                        companion.mood = Mood.IDLE
                        togglePanel("companion", ::companionPanel)
                    }
                }
            }
            true
        }
        window.showAtLocation(canvas, Gravity.NO_GRAVITY, x, y)
        companionWindow = window
        handler.postDelayed(nap, NAP_DELAY_MS)
    }

    private fun setCompanionVisible(visible: Boolean) {
        preferences.edit().putBoolean("companion", visible).apply()
        companionWindow?.dismiss()
        companionWindow = null
        closePanel()
        if (visible) showCompanion()
    }

    private fun fadeChrome(alpha: Float) {
        if (interfaceHidden && alpha > 0f) return
        (chrome + listOfNotNull(companionWindow)).forEach {
            it.contentView.animate().alpha(alpha).setDuration(120).start()
        }
    }

    private fun togglePanel(key: String, content: () -> View) {
        val reopen = panelKey != key || panel?.isShowing != true
        closePanel()
        if (reopen) showPanel(key, content())
    }

    private fun showPanel(key: String, content: View) {
        val body = ScrollView(this).apply {
            background = rounded(SURFACE, 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(content)
        }
        val window = PopupWindow(body, dp(PANEL_WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isClippingEnabled = true
        }
        val leftSide = key == "actions" || key == "references" || key == "view"
        val side = if (leftSide) Gravity.START else Gravity.END
        window.height = minOf(dp(520), canvas.height - dp(84)).let {
            body.measure(
                View.MeasureSpec.makeMeasureSpec(dp(PANEL_WIDTH), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED,
            )
            minOf(it, body.measuredHeight)
        }
        if (key == "companion") {
            window.showAtLocation(canvas, Gravity.BOTTOM or Gravity.START, dp(12), dp(76))
        } else {
            window.showAtLocation(canvas, Gravity.TOP or side, dp(12), dp(68))
        }
        panel = window
        panelKey = key
    }

    private fun closePanel() {
        if (panelKey == "color") rememberColor()
        panel?.dismiss()
        panel = null
        panelKey = ""
    }

    private fun companionPanel() = column().apply {
        addView(label("Companion", 16f))
        val current = report
        if (current == null || current.tips.isEmpty()) {
            gapped(label("Draw a little more, then pause. Tips appear here.", 13f, MUTED_TEXT))
        } else {
            current.tips.forEach { tip ->
                gapped(label(tip.topic, 13f, MUTED_TEXT))
                addView(label(tip.text, 14f))
            }
            gapped(label("Values and silhouette", 13f, MUTED_TEXT))
            addView(
                LinearLayout(context).apply {
                    listOf(current.values, current.silhouette).forEach { pixels ->
                        val bitmap = Bitmap.createBitmap(
                            pixels, current.width, current.height, Bitmap.Config.ARGB_8888,
                        )
                        addView(
                            ImageView(context).apply {
                                setImageBitmap(bitmap)
                                contentDescription = "Artwork preview"
                            },
                            LinearLayout.LayoutParams(0, dp(90), 1f).apply { marginEnd = dp(4) },
                        )
                    }
                },
            )
        }
        gapped(
            wrapRow(
                Mascot.entries.map { mascot ->
                    chip(mascot.label, companion.mascot == mascot) { chooseMascot(mascot) }
                },
            ),
        )
        gapped(chip("Hide companion") { setCompanionVisible(false) })
    }

    private fun chooseMascot(mascot: Mascot) {
        companion.mascot = mascot
        preferences.edit().putString("mascot", mascot.name).apply()
        closePanel()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun LinearLayout.gapped(view: View) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun wrapRow(views: List<View>) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(
            LinearLayout(context).apply {
                views.forEach {
                    addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                }
            },
        )
    }

    private fun toolGrid(kinds: List<BrushKind>, previewColor: Int) = GridLayout(this).apply {
        columnCount = 2
        kinds.forEach { kind ->
            val selected = canvas.brushKind == kind
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(if (selected) ACCENT else SURFACE_RAISED, 12)
                    setPadding(dp(8), dp(4), dp(8), dp(8))
                    setOnClickListener {
                        selectTool(kind)
                        closePanel()
                    }
                    addView(BrushPreview(context, kind, previewColor), LinearLayout.LayoutParams(-1, dp(48)))
                    addView(label(kind.label, 13f))
                },
                GridLayout.LayoutParams().apply {
                    width = dp(152)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                },
            )
        }
    }

    private fun brushPanel() = column().apply {
        addView(label("Brushes", 16f))
        val color = if (Color.luminance(canvas.brushColor) < 0.08f) TEXT_COLOR else canvas.brushColor
        gapped(toolGrid(BrushKind.entries.filterNot { it.isEraser }, color))
    }

    private fun eraserPanel() = column().apply {
        addView(label("Erasers", 16f))
        gapped(toolGrid(BrushKind.entries.filter { it.isEraser }, TEXT_COLOR))
        gapped(label("Each brush and eraser remembers its own size and opacity.", 13f, MUTED_TEXT))
    }

    private fun swatchRow(colors: List<Int>, size: Int = 34, onLongPress: (() -> Unit)? = null) =
        LinearLayout(this).apply {
            colors.forEach { color ->
                addView(
                    View(context).apply {
                        background = rounded(color, 8)
                        contentDescription = "Color swatch"
                        setOnClickListener {
                            setColor(color)
                            rerender("color", ::colorPanel)
                        }
                        setOnLongClickListener {
                            onLongPress?.invoke()
                            onLongPress != null
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(size), 1f).apply { marginEnd = dp(3) },
                )
            }
        }

    private fun rerender(key: String, content: () -> View) {
        if (panelKey != key) return
        panel?.dismiss()
        showPanel(key, content())
    }

    private fun colorPanel(): View = column().apply {
        addView(
            wrapRow(
                listOf("Picker", "Harmony", "Palettes").mapIndexed { index, name ->
                    chip(name, colorTab == index) {
                        colorTab = index
                        rerender("color", ::colorPanel)
                    }
                },
            ),
        )
        when (colorTab) {
            0 -> {
                gapped(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            View(context).apply {
                                background = swatchBackground(colorBeforePanel)
                                contentDescription = "Previous color"
                                setOnClickListener {
                                    setColor(colorBeforePanel)
                                    rerender("color", ::colorPanel)
                                }
                            },
                            LinearLayout.LayoutParams(dp(44), dp(30)),
                        )
                        addView(
                            View(context).apply {
                                background = swatchBackground(canvas.brushColor)
                                tag = "currentColor"
                            },
                            LinearLayout.LayoutParams(dp(44), dp(30)).apply { marginStart = dp(4) },
                        )
                        addView(
                            label(hex(canvas.brushColor), 13f, MUTED_TEXT).apply { tag = "hex" },
                            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) },
                        )
                    },
                )
                val header = this
                gapped(
                    ColorPicker(context, canvas.brushColor) {
                        setColor(it)
                        header.findViewWithTag<View>("currentColor")?.background = swatchBackground(it)
                        header.findViewWithTag<android.widget.TextView>("hex")?.text = hex(it)
                    }.apply { minimumHeight = dp(230) },
                )
                gapped(label("Values of this hue", 13f, MUTED_TEXT))
                addView(swatchRow(valueScale(canvas.brushColor)))
                if (recentColors.isNotEmpty()) {
                    gapped(label("Recent", 13f, MUTED_TEXT))
                    addView(swatchRow(recentColors, 28))
                }
            }
            1 -> harmoniesFor(canvas.brushColor).forEach { harmony ->
                gapped(label(harmony.name, 13f, MUTED_TEXT))
                addView(swatchRow(harmony.colors))
            }
            else -> {
                gapped(
                    wrapRow(
                        listOf(
                            chip("From artwork") { paletteFromArtwork() },
                            chip("From reference") { paletteFromReference() },
                            chip("Save recent") {
                                if (recentColors.size < 2) {
                                    Toast.makeText(context, "Use at least two colors first", Toast.LENGTH_SHORT).show()
                                } else {
                                    store.addPalette("Recent colors", recentColors.toList())
                                    rerender("color", ::colorPanel)
                                }
                            },
                        ),
                    ),
                )
                store.palettes().forEach { palette ->
                    gapped(label(palette.name, 13f, MUTED_TEXT))
                    addView(swatchRow(palette.colors, onLongPress = { confirmDeletePalette(palette) }))
                }
                STARTER_PALETTES.forEach { palette ->
                    gapped(label(palette.name, 13f, MUTED_TEXT))
                    addView(swatchRow(palette.colors))
                }
            }
        }
    }

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    private fun confirmDeletePalette(palette: Palette) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${palette.name}?")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                store.deletePalette(palette.id)
                rerender("color", ::colorPanel)
            }
            .show()
    }

    private fun savePalette(name: String, colors: List<Int>) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (colors.isEmpty()) {
                Toast.makeText(this, "There aren't any colors to collect yet", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            store.addPalette(name, colors)
            rerender("color", ::colorPanel)
        }
    }

    private fun paletteFromArtwork() {
        val width = ArtworkStore.THUMBNAIL_WIDTH
        val height = ArtworkStore.THUMBNAIL_HEIGHT
        canvas.snapshot { engine ->
            val buffer = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, buffer)
            saver.execute {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                bitmap.recycle()
                savePalette("${document.name} colors", extractPalette(pixels))
            }
        }
    }

    private fun paletteFromReference() {
        val file = references.keys.lastOrNull()
        if (file == null) {
            Toast.makeText(this, "Open a reference first", Toast.LENGTH_SHORT).show()
            return
        }
        saver.execute {
            val bitmap = decodeReference(File(file), 240) ?: return@execute
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            savePalette("Reference colors", extractPalette(pixels))
        }
    }

    private fun viewPanel(): View = column().apply {
        addView(label("Art direction", 16f))
        gapped(label("Check your image", 13f, MUTED_TEXT))
        gapped(
            wrapRow(
                VIEW_MODES.mapIndexed { index, name ->
                    chip(name, viewMode == index) {
                        viewMode = index
                        applyViewOptions()
                    }
                },
            ),
        )
        gapped(label("Composition guides", 13f, MUTED_TEXT))
        gapped(
            wrapRow(
                GUIDES.mapIndexed { index, name ->
                    chip(name, guide == index) {
                        guide = index
                        applyViewOptions()
                    }
                },
            ),
        )
        gapped(chip("Flip canvas") { canvas.flip() })
        gapped(label("These only change your view, not your artwork or exports.", 13f, MUTED_TEXT))
    }

    private fun applyViewOptions() {
        val mode = viewMode
        val lines = guide
        canvas.act { NativeEngine.setViewOptions(it, mode, lines) }
        rerender("view", ::viewPanel)
    }

    private fun refreshLayers() = rerender("layers", ::layersPanel)

    private fun updateLayer(layer: LayerInfo) {
        val copy = layer.copy()
        canvas.act { applyLayerProperties(it, copy) }
        markUnsaved()
    }

    private fun activeLayer() = document.layers.firstOrNull { it.id == document.activeLayer }

    private fun selectLayer(layer: LayerInfo) {
        document.activeLayer = layer.id
        canvas.alphaLock = layer.alphaLock
        canvas.act { NativeEngine.setActiveLayer(it, layer.id) }
        markUnsaved()
    }

    private fun layersPanel(): View = column().apply {
        layerThumbnails.clear()
        val active = activeLayer()
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Layers", 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(chip("Add") { addLayer() })
            },
        )
        val rows = ArrayList<View>()
        val list = column()
        document.layers.asReversed().forEach { layer ->
            val row = layerRow(layer, rows)
            rows.add(row)
            list.addView(row, LinearLayout.LayoutParams(-1, dp(LAYER_ROW)).apply { topMargin = dp(LAYER_GAP) })
        }
        gapped(list)
        requestLayerThumbnails()
        if (active == null) return@apply

        gapped(label("Opacity", 13f, MUTED_TEXT))
        addView(
            SeekBar(context).apply {
                max = 100
                progress = (active.opacity * 100).roundToInt()
                progressTintList = ColorStateList.valueOf(ACCENT)
                thumbTintList = ColorStateList.valueOf(ACCENT)
                contentDescription = "Layer opacity"
                onChange {
                    active.opacity = it / 100f
                    updateLayer(active)
                }
            },
        )
        gapped(
            wrapRow(
                listOf(
                    chip(BLEND_MODES[active.blend]) { }.apply {
                        setOnClickListener { chooseBlend(this, active) }
                    },
                    chip("Duplicate") { duplicateLayer(active) },
                    chip("Merge down") { confirmMergeDown(active) },
                    chip("Delete") { confirmDeleteLayer(active) },
                ),
            ),
        )
        gapped(label("Long-press a layer to rename it. Drag the lines to reorder.", 13f, MUTED_TEXT))
    }

    private fun chooseBlend(anchor: View, layer: LayerInfo) {
        PopupMenu(this, anchor).apply {
            BLEND_MODES.forEachIndexed { index, name -> menu.add(0, index, index, name) }
            setOnMenuItemClickListener {
                layer.blend = it.itemId
                updateLayer(layer)
                refreshLayers()
                true
            }
            show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun layerRow(layer: LayerInfo, rows: List<View>): View {
        val selected = layer.id == document.activeLayer
        val thumbnail = ImageView(this).apply {
            background = rounded(Color.WHITE, 4)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "${layer.name} preview"
        }
        layerThumbnails[layer.id] = thumbnail
        val handle = ImageView(this).apply {
            setImageResource(R.drawable.ic_handle)
            contentDescription = "Reorder ${layer.name}"
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (selected) ACCENT else SURFACE_RAISED, 10)
            setPadding(dp(6), 0, dp(2), 0)
            setOnClickListener {
                selectLayer(layer)
                refreshLayers()
            }
            setOnLongClickListener {
                renameLayer(layer)
                true
            }
            addView(handle, LinearLayout.LayoutParams(dp(32), -1))
            addView(thumbnail, LinearLayout.LayoutParams(dp(THUMBNAIL_WIDTH), dp(THUMBNAIL_HEIGHT)))
            addView(
                label(layer.name).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
            )
            addView(
                iconButton(if (layer.alphaLock) R.drawable.ic_lock else R.drawable.ic_unlock, "Lock transparency") {
                    layer.alphaLock = !layer.alphaLock
                    if (layer.id == document.activeLayer) canvas.alphaLock = layer.alphaLock
                    markUnsaved()
                    refreshLayers()
                },
            )
            addView(
                iconButton(if (layer.visible) R.drawable.ic_visible else R.drawable.ic_hidden, "Show or hide ${layer.name}") {
                    layer.visible = !layer.visible
                    updateLayer(layer)
                    refreshLayers()
                },
            )
        }
        var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    row.parent.requestDisallowInterceptTouchEvent(true)
                    row.alpha = 0.8f
                    row.elevation = dp(4).toFloat()
                }
                MotionEvent.ACTION_MOVE -> row.translationY = event.rawY - startY
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val step = dp(LAYER_ROW + LAYER_GAP).toFloat()
                    val moved = ((event.rawY - startY) / step).roundToInt()
                    row.translationY = 0f
                    row.alpha = 1f
                    row.elevation = 0f
                    if (moved != 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                        moveLayerTo(layer, document.layers.indexOf(layer) - moved)
                    }
                }
            }
            true
        }
        return row
    }

    private fun requestLayerThumbnails() {
        val width = THUMBNAIL_WIDTH * 2
        val height = THUMBNAIL_HEIGHT * 2
        document.layers.map { it.id }.forEach { id ->
            canvas.snapshot { engine ->
                val pixels = ArtworkStore.pixelBuffer(width, height)
                NativeEngine.readLayerThumbnail(engine, id, width, height, pixels)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pixels.rewind()
                bitmap.copyPixelsFromBuffer(pixels)
                runOnUiThread { layerThumbnails[id]?.setImageBitmap(bitmap) }
            }
        }
    }

    private fun addLayer() {
        if (document.layers.size >= MAX_LAYERS) {
            Toast.makeText(this, "This canvas can hold $MAX_LAYERS layers", Toast.LENGTH_LONG).show()
            return
        }
        val id = document.nextLayerId++
        val index = document.layers.indexOfFirst { it.id == document.activeLayer } + 1
        document.layers.add(index, LayerInfo(id, "Layer $id"))
        document.activeLayer = id
        canvas.alphaLock = false
        canvas.act {
            NativeEngine.createLayer(it, id)
            NativeEngine.moveLayer(it, id, index)
            NativeEngine.setActiveLayer(it, id)
        }
        markUnsaved()
        refreshLayers()
    }

    private fun duplicateLayer(layer: LayerInfo) {
        if (document.layers.size >= MAX_LAYERS) {
            Toast.makeText(this, "This canvas can hold $MAX_LAYERS layers", Toast.LENGTH_LONG).show()
            return
        }
        val id = document.nextLayerId++
        val copy = layer.copy(id = id, name = "${layer.name} copy", file = null)
        document.layers.add(document.layers.indexOf(layer) + 1, copy)
        document.activeLayer = id
        canvas.alphaLock = copy.alphaLock
        canvas.act {
            NativeEngine.duplicateLayer(it, layer.id, id)
            NativeEngine.setActiveLayer(it, id)
        }
        markUnsaved()
        refreshLayers()
    }

    private fun confirmMergeDown(layer: LayerInfo) {
        val index = document.layers.indexOf(layer)
        if (index == 0) {
            Toast.makeText(this, "There isn't a layer below this one", Toast.LENGTH_SHORT).show()
            return
        }
        val below = document.layers[index - 1]
        AlertDialog.Builder(this)
            .setTitle("Merge into ${below.name}?")
            .setMessage("This can't be undone, and the blend mode of ${layer.name} is flattened to Normal.")
            .setNegativeButton("Keep separate", null)
            .setPositiveButton("Merge") { _, _ ->
                document.layers.remove(layer)
                selectLayer(below)
                canvas.act {
                    NativeEngine.mergeLayerDown(it, layer.id, below.id)
                    NativeEngine.setActiveLayer(it, below.id)
                }
                markUnsaved()
                refreshLayers()
            }
            .show()
    }

    private fun moveLayerTo(layer: LayerInfo, target: Int) {
        val layers = document.layers
        val from = layers.indexOf(layer)
        val to = target.coerceIn(0, layers.size - 1)
        if (to == from) return
        layers.removeAt(from)
        layers.add(to, layer)
        canvas.act { NativeEngine.moveLayer(it, layer.id, to) }
        markUnsaved()
        refreshLayers()
    }

    private fun confirmDeleteLayer(layer: LayerInfo) {
        if (document.layers.size == 1) {
            Toast.makeText(this, "An artwork needs at least one layer", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${layer.name}?")
            .setMessage("You can't undo this.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                val index = document.layers.indexOf(layer)
                document.layers.remove(layer)
                val next = document.layers[max(0, index - 1)]
                canvas.act { NativeEngine.deleteLayer(it, layer.id) }
                selectLayer(next)
                markUnsaved()
                refreshLayers()
            }
            .show()
    }

    private fun askForName(title: String, current: String, done: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(current)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done)
            }
            .show()
    }

    private fun renameLayer(layer: LayerInfo) = askForName("Rename layer", layer.name) {
        layer.name = it
        markUnsaved()
        refreshLayers()
    }

    private fun actionsPanel() = column().apply {
        addView(label(document.name, 16f))
        gapped(
            chip("Rename artwork") {
                closePanel()
                askForName("Rename artwork", document.name) {
                    document.name = it
                    markUnsaved()
                }
            },
        )
        gapped(
            chip("Export PNG") {
                closePanel()
                exportPng()
            },
        )
        val visible = preferences.getBoolean("companion", true)
        gapped(chip(if (visible) "Hide companion" else "Show companion") { setCompanionVisible(!visible) })
    }

    private fun exportPng() {
        val name = document.name
        canvas.snapshot { engine ->
            val pixels = ArtworkStore.layerBuffer()
            NativeEngine.readComposite(engine, CANVAS_WIDTH, CANVAS_HEIGHT, pixels)
            saver.execute {
                val saved = runCatching { store.exportPng(name, pixels) }.getOrDefault(false)
                runOnUiThread {
                    val message = if (saved) "Saved to Pictures/Cappuccino" else "Couldn't export this PNG"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun referencesPanel() = column().apply {
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("References", 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    chip("Add") {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("image/*")
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                            PICK_REFERENCES,
                        )
                    },
                )
            },
        )
        val files = store.references(document.projectId)
        if (files.isEmpty()) {
            gapped(label("Add images here, then tap one to float it over your canvas.", 13f, MUTED_TEXT))
            return@apply
        }
        gapped(
            GridLayout(context).apply {
                columnCount = 3
                files.forEach { file ->
                    addView(
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(decodeReference(file, dp(84)))
                            contentDescription = "Reference"
                            setOnClickListener {
                                toggleReference(file)
                                closePanel()
                            }
                        },
                        GridLayout.LayoutParams().apply {
                            width = dp(84)
                            height = dp(84)
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        },
                    )
                }
            },
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_REFERENCES || resultCode != RESULT_OK || data == null) return
        val single = listOfNotNull(data.data)
        val multiple = data.clipData?.let { clip ->
            (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        }.orEmpty()
        val project = document.projectId
        saver.execute {
            (multiple.ifEmpty { single }).forEach { uri -> runCatching { store.addReference(project, uri) } }
            runOnUiThread {
                closePanel()
                showPanel("references", referencesPanel())
            }
        }
    }

    private fun decodeReference(file: File, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetSize) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun toggleReference(file: File) {
        references.remove(file.path)?.let {
            it.dismiss()
            return
        }
        val bitmap = decodeReference(file, 1400) ?: return
        val startWidth = dp(320)
        val ratio = bitmap.height / bitmap.width.toFloat()
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "Floating reference"
        }
        val close = label("Close", 12f).apply {
            background = rounded(SURFACE, 10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val content = FrameLayout(this).apply {
            addView(image)
            addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }
        val window = PopupWindow(content, startWidth, (startWidth * ratio).toInt()).apply {
            isClippingEnabled = false
        }
        close.setOnClickListener { toggleReference(file) }
        attachReferenceGestures(image, window, bitmap, ratio)
        window.showAtLocation(canvas, Gravity.NO_GRAVITY, dp(90), dp(90))
        references[file.path] = window
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachReferenceGestures(image: ImageView, window: PopupWindow, bitmap: Bitmap, ratio: Float) {
        var x = dp(90)
        var y = dp(90)
        var width = dp(320).toFloat()
        var lastX = 0f
        var lastY = 0f
        val scale = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    width = (width * detector.scaleFactor).coerceIn(dp(120).toFloat(), canvas.width.toFloat())
                    window.update(x, y, width.toInt(), (width * ratio).toInt())
                    return true
                }
            },
        )
        val press = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(event: MotionEvent) {
                    val pixelX = (event.x / image.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val pixelY = (event.y / image.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    setColor(bitmap.getPixel(pixelX, pixelY) or 0xFF000000.toInt())
                    rememberColor()
                }
            },
        )
        image.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            press.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scale.isInProgress) {
                    x += (event.rawX - lastX).toInt()
                    y += (event.rawY - lastY).toInt()
                    lastX = event.rawX
                    lastY = event.rawY
                    window.update(x, y, -1, -1)
                }
            }
            true
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        canvas.post {
            closePanel()
            chrome.forEach { it.dismiss() }
            chrome.clear()
            companionWindow?.dismiss()
            companionWindow = null
            showChrome()
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closePanel()
        references.values.forEach { it.dismiss() }
        chrome.forEach { it.dismiss() }
        companionWindow?.dismiss()
        canvas.release()
        saver.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ARTWORK = "artwork"
        const val EXTRA_PROJECT = "project"
    }
}
