package art.cappuccino.paint

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
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
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_BRUSH = 4
private const val MAX_BRUSH = 300
private const val AUTOSAVE_DELAY_MS = 2500L
private const val NAP_DELAY_MS = 90_000L
private const val PICK_REFERENCES = 41
private const val REFERENCE_SIZE = 2048
private const val PREFERENCES = "studio"
private const val PANEL_WIDTH = 340
private const val BUBBLE_WIDTH = 308
private const val THUMBNAIL_WIDTH = 72
private const val THUMBNAIL_HEIGHT = 48
private const val LAYER_ROW = 56
private const val LAYER_GAP = 4
private const val RETIRED_LAYERS = 6
private val LEFT_PANELS = setOf("actions", "references", "view", "selection")
private const val PICK_TIP = 45
private const val PEEK_SIZE = 44
private const val PEEK_OVERLAP = 16
private const val PEEK_TOP = 1
private const val PEEK_TILT = 24f
private const val REFERENCE_PROFILE_SIZE = 240
private const val BRUSH_TIP_SIZE = 128

class MainActivity : Activity(), ReferenceHost {
    private lateinit var canvas: CanvasView
    private lateinit var store: ArtworkStore
    private lateinit var document: ArtworkDocument
    private lateinit var companion: CompanionView

    private val handler = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor()
    private val savedFiles = HashMap<Int, String>()
    private val recentColors = ArrayList<Int>()
    private val chrome = ArrayList<PopupWindow>()
    private val references = LinkedHashMap<String, ReferenceWindow>()
    private var companionWindow: PopupWindow? = null
    private var panel: PopupWindow? = null
    private var panelKey = ""
    private var interfaceHidden = false
    private var unsaved = false
    private var penDown = false
    private var report: AnalysisReport? = null
    private var unreadTips = false
    private var swatch: View? = null

    private val tools = HashMap<String, ToolSettings>()
    private val customBrushes = ArrayList<BrushKind>()
    private var lastBrush = BrushKind.INK
    private var lastEraser = BrushKind.ERASE_INK
    private var maxLayers = 12
    private lateinit var lassoOverlay: LassoOverlay
    private var lassoWindow: PopupWindow? = null
    private var pickRing: PopupWindow? = null
    private var pickView: PickRing? = null
    private var hasSelection = false
    private var tipIndex = 0
    private var ideaIndex = 0
    private var matchedFiles: List<File> = emptyList()
    private var peek: CompanionView? = null
    private var peekFrame: View? = null
    private var peekWindow: PopupWindow? = null
    private var companionX = 0
    private var companionY = 0
    private var referenceMatch: ReferenceMatch? = null
    private val referenceCache = java.util.concurrent.ConcurrentHashMap<String, VisualProfile>()
    private var companionSection = 0
    private var pendingBrushName = ""
    private var pendingBrushShape: FloatArray? = null
    private var adjustment = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f)
    private var editingBrush: BrushKind? = null
    private var draftTip: ByteArray? = null
    private val figureHandles = ArrayList<PopupWindow>()
    private var selectButton: View? = null
    private var sizeBar: SeekBar? = null
    private var opacityBar: SeekBar? = null
    private var brushButton: View? = null
    private var eraserButton: View? = null
    private var colorBeforePanel = 0
    private var colorTab = 0
    private var transformControls: PopupWindow? = null
    private var viewMode = 0
    private var guide = 0
    private val layerThumbnails = HashMap<Int, ImageView>()
    private val perspectiveHandles = ArrayList<PopupWindow>()
    private val perspectiveLog = PerspectiveLog()
    private val history = EditHistory()
    private val engineLayers = HashSet<Int>()

    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val saveLater = Runnable { save() }
    private val nap = Runnable { if (!unreadTips) companion.mood = Mood.NAP }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ArtworkStore(this)
        val artworkId = intent.getStringExtra(EXTRA_ARTWORK)
        val loaded = artworkId?.let(store::load)
        if (artworkId != null && loaded == null) {
            Toast.makeText(this, tr("Couldn't open this artwork"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val canvasWidth = loaded?.width ?: intent.getIntExtra(EXTRA_WIDTH, DEFAULT_CANVAS_WIDTH)
        val canvasHeight = loaded?.height ?: intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_CANVAS_HEIGHT)
        canvas = CanvasView(this, canvasWidth, canvasHeight)
        maxLayers = layerLimit(
            canvasWidth, canvasHeight,
            ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem,
        )
        canvas.pressureGamma = preferences.getFloat("pressureGamma", 0.75f)
        customBrushes.addAll(store.customBrushes())
        setContentView(canvas)
        companion = CompanionView(this).apply {
            look = CompanionLook.valueOf(
                preferences.getString("mascot", null) ?: CompanionLook.PAINT_DROP.name,
            )
            bodyColor = canvas.brushColor
        }
        canvas.onStrokeStart = {
            penDown = true
            closePanel()
            fadeChrome(0.15f)
            references.values.forEach { it.setDrawing(true) }
            handler.removeCallbacks(saveLater)
            handler.removeCallbacks(nap)
        }
        canvas.onStrokeEnd = {
            penDown = false
            fadeChrome(1f)
            references.values.forEach { it.setDrawing(false) }
            if (companion.mood == Mood.NAP) companion.mood = Mood.IDLE
            handler.postDelayed(nap, NAP_DELAY_MS)
            markUnsaved()
        }
        canvas.onGestureStart = { closePanel() }
        canvas.onViewChanged = { positionHandles() }
        canvas.onFillTap = { x, y -> fillAt(x, y) }
        canvas.onStrokeCommitted = { history.record(Edit.Stroke) }
        canvas.onUndoGesture = { undoEdit() }
        canvas.onRedoGesture = { redoEdit() }
        canvas.onStrokePath = { checkPerspective(it) }
                canvas.onToggleInterface = {
            interfaceHidden = !interfaceHidden
            fadeChrome(if (interfaceHidden) 0f else 1f)
            closePanel()
        }
        canvas.onPickMove = { color, x, y -> showPickRing(color, x, y) }
        canvas.onPickEnd = { hidePickRing() }
        canvas.onLassoMove = { x, y ->
            showLassoOverlay()
            lassoOverlay.add(x, y)
        }
        canvas.onLassoDone = { finishLasso(it) }
        canvas.onWandTap = { x, y -> finishWand(x, y) }
        canvas.onGradientMove = { previewGradient(it) }
        canvas.onGradientDone = { finishGradient() }
        lassoOverlay = LassoOverlay(this)
        openDocument(artworkId, loaded, canvasWidth, canvasHeight)
        canvas.post { showChrome() }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun openDocument(id: String?, loaded: ArtworkDocument?, width: Int, height: Int) {
        if (id == null || loaded == null) {
            val project = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
            val first = LayerInfo(1, tr("Layer %1", 1))
            document = ArtworkDocument(
                UUID.randomUUID().toString(), tr("Untitled"), project, 1, 2, mutableListOf(first),
                width = width, height = height,
            )
            engineLayers.add(1)
            canvas.act {
                NativeEngine.createLayer(it, 1)
                NativeEngine.setActiveLayer(it, 1)
            }
            canvas.acceptsInput = true
            return
        }
        document = loaded
        engineLayers.addAll(loaded.layers.map { it.id })
        pushPerspective()
        pushFigure()
        saver.execute {
            for (layer in loaded.layers) {
                val pixels = layer.file?.let { store.readLayer(id, it, width, height) }
                layer.file?.let { savedFiles[layer.id] = it }
                canvas.act { engine ->
                    NativeEngine.createLayer(engine, layer.id)
                    pixels?.let { NativeEngine.writeLayer(engine, layer.id, it) }
                }
            }
            canvas.act { engine ->
                loaded.layers.forEach { applyLayerProperties(engine, it) }
                NativeEngine.setActiveLayer(engine, loaded.activeLayer)
            }
            runOnUiThread {
                syncAlphaLock()
                canvas.acceptsInput = true
                canvas.post { restoreReferences() }
            }
        }
    }

    private fun applyLayerProperties(engine: Long, layer: LayerInfo) {
        val group = document.groups.firstOrNull { it.id == layer.groupId }
        NativeEngine.setLayerProperties(
            engine, layer.id,
            layer.opacity * (group?.opacity ?: 1f),
            layer.visible && group?.visible != false,
            layer.blend, layer.clip,
        )
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
        val groups = target.groups.map { it.copy() }
        val nextGroup = target.nextGroupId
        val referenceSet = referenceFiles()
        if (!interfaceHidden) companion.mood = Mood.THINKING
        canvas.snapshot { engine ->
            val pixels = layers.filter { NativeEngine.takeLayerDirty(engine, it.id) }
                .associate { layer ->
                    layer.id to ArtworkStore.pixelBuffer(target.width, target.height).also {
                        NativeEngine.readLayer(engine, layer.id, it)
                    }
                }
            val thumbnail = ArtworkStore.pixelBuffer(ArtworkStore.THUMBNAIL_WIDTH, target.thumbnailHeight)
            NativeEngine.readComposite(engine, ArtworkStore.THUMBNAIL_WIDTH, target.thumbnailHeight, thumbnail)
            saver.execute {
                layers.forEach { it.file = savedFiles[it.id] }
                runCatching {
                    store.save(target, ArtworkSnapshot(layers, active, nextId, pixels, thumbnail, groups, nextGroup))
                    store.addTimelapseFrame(target.id, thumbnail, target.thumbnailHeight)
                    layers.forEach { layer -> layer.file?.let { savedFiles[layer.id] = it } }
                }.onFailure {
                    runOnUiThread {
                        unsaved = true
                        Toast.makeText(this, tr("Couldn't save your artwork"), Toast.LENGTH_LONG).show()
                    }
                }
                analyze(thumbnail, referenceSet)
            }
        }
    }

    /** Uses open references, or the whole project's library when none are open. */
    private fun referenceFiles(): List<File> {
        val all = store.references(document.projectId)
        val open = all.filter { references.containsKey(it.name) }
        return open.ifEmpty { all }
    }

    private fun referenceProfiles(files: List<File>): List<VisualProfile> = files.mapNotNull { file ->
        referenceCache.getOrPut(file.name) {
            val bitmap = decodeReference(file, REFERENCE_PROFILE_SIZE) ?: return@mapNotNull null
            val small = Bitmap.createScaledBitmap(
                bitmap, REFERENCE_PROFILE_SIZE, max(1, REFERENCE_PROFILE_SIZE * bitmap.height / bitmap.width), true,
            )
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            profileImage(pixels, small.width, small.height)
        }
    }

    private fun analyze(thumbnail: java.nio.ByteBuffer, files: List<File>) {
        thumbnail.rewind()
        val raw = thumbnail.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val pixels = IntArray(raw.remaining())
        raw.get(pixels)
        for (index in pixels.indices) {
            val value = pixels[index]
            pixels[index] = (0xFF shl 24) or
                ((value and 0xFF) shl 16) or (value and 0xFF00) or ((value shr 16) and 0xFF)
        }
        val result = analyzeArtwork(
            pixels, ArtworkStore.THUMBNAIL_WIDTH, document.thumbnailHeight, document.light.takeIf { it != Light.OFF }?.degrees,
        )
        val artProfile = profileImage(pixels, ArtworkStore.THUMBNAIL_WIDTH, document.thumbnailHeight)
        val matched = compareWithReferences(artProfile, referenceProfiles(files))
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val changed = result.tips.map { it.text } != report?.tips?.map { it.text } ||
                matched?.summary != referenceMatch?.summary
            report = result
            referenceMatch = matched
            matchedFiles = files
            if (changed && result.tips.isNotEmpty()) unreadTips = true
            if (unreadTips) peek?.mood = Mood.TIP
            companion.mood = if (unreadTips) Mood.TIP else Mood.IDLE
        }
    }

    internal fun setColor(color: Int) {
        canvas.brushColor = color
        companion.bodyColor = color
        peek?.bodyColor = color
        swatch?.background = swatchBackground(color)
        if (canvas.brushKind.isEraser) selectTool(lastBrush)
    }

    private fun toolFor(kind: BrushKind) = tools.getOrPut(kind.id) { ToolSettings(kind.defaultSize) }

    internal fun selectTool(kind: BrushKind) {
        val settings = toolFor(kind)
        canvas.brushKind = kind
        canvas.brushDiameter = settings.diameter
        canvas.brushOpacity = settings.opacity
        canvas.stabilizerStrength = settings.stabilizer
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
                iconButton(R.drawable.ic_gallery, tr("Projects")) {
                    save()
                    startActivity(
                        Intent(this@MainActivity, GalleryActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                },
            )
            addView(iconButton(R.drawable.ic_actions, tr("Actions")) { togglePanel("actions", ::actionsPanel) })
            addView(iconButton(R.drawable.ic_references, tr("References")) { togglePanel("references", ::referencesPanel) })
            addView(iconButton(R.drawable.ic_view, tr("Art direction")) { togglePanel("view", ::viewPanel) })
            selectButton = iconButton(R.drawable.ic_select, tr("Selection")) { togglePanel("selection", ::selectionPanel) }
            addView(selectButton)
        }
        left.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        showPeek(dp(12) + left.measuredWidth - dp(PEEK_OVERLAP), dp(PEEK_TOP))
        place(left, Gravity.TOP or Gravity.START, dp(12), dp(12))

        val colorButton = View(this).apply {
            background = swatchBackground(canvas.brushColor)
            contentDescription = tr("Color")
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
            brushButton = iconButton(R.drawable.ic_brush, tr("Brushes")) {
                if (canvas.brushKind.isEraser) selectTool(lastBrush)
                togglePanel("brushes", ::brushPanel)
            }
            eraserButton = iconButton(R.drawable.ic_eraser, tr("Eraser")) {
                if (!canvas.brushKind.isEraser) selectTool(lastEraser)
                togglePanel("erasers", ::eraserPanel)
            }
            addView(brushButton)
            addView(eraserButton)
            addView(iconButton(R.drawable.ic_layers, tr("Layers")) { togglePanel("layers", ::layersPanel) })
            addView(colorButton)
        }
        place(right, Gravity.TOP or Gravity.END, dp(12), dp(12))

        val sliders = pill(vertical = true).apply {
            addView(verticalSlider(tr("Brush size"), sizeProgress(), { sizeBar = it }) {
                val share = it / 1000f
                canvas.brushDiameter = MIN_BRUSH + (MAX_BRUSH - MIN_BRUSH) * share * share
                toolFor(canvas.brushKind).diameter = canvas.brushDiameter
            })
            addView(verticalSlider(tr("Brush opacity"), (canvas.brushOpacity * 1000f).roundToInt(), { opacityBar = it }) {
                canvas.brushOpacity = max(it, 10) / 1000f
                toolFor(canvas.brushKind).opacity = canvas.brushOpacity
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
        var x = preferences.getInt("companionX", dp(12)).coerceIn(0, max(0, canvas.width - size))
        var y = preferences.getInt("companionY", canvas.height - size - dp(12))
            .coerceIn(0, max(0, canvas.height - size))
        companionX = x
        companionY = y
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
                        companionX = x
                        companionY = y
                        window.update(x, y, -1, -1)
                        if (panelKey == "companion") closePanel()
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

    /**
     * A resting companion peeking from behind the toolbar's top right corner. Its window opens
     * first, so the toolbar stays in front of it. Tap it to wake the companion.
     */
    private fun showPeek(x: Int, y: Int) {
        val resting = CompanionView(this).apply {
            look = companion.look
            bodyColor = canvas.brushColor
            mood = if (unreadTips) Mood.TIP else Mood.NAP
            rotation = PEEK_TILT
        }
        peek = resting
        val hidden = !preferences.getBoolean("companion", true)
        val frame = FrameLayout(this).apply {
            contentDescription = tr("Wake your companion")
            visibility = if (hidden) View.VISIBLE else View.INVISIBLE
            addView(resting, FrameLayout.LayoutParams(dp(PEEK_SIZE), dp(PEEK_SIZE), Gravity.CENTER))
            setOnClickListener { setCompanionVisible(true) }
        }
        peekFrame = frame
        peekWindow = PopupWindow(frame, dp(PEEK_SIZE), dp(PEEK_SIZE)).apply {
            isClippingEnabled = false
            isTouchable = hidden
            showAtLocation(canvas, Gravity.NO_GRAVITY, x, y)
        }
        peekWindow?.let(chrome::add)
    }

    private fun setCompanionVisible(visible: Boolean) {
        preferences.edit().putBoolean("companion", visible).apply()
        peek?.look = companion.look
        peek?.bodyColor = canvas.brushColor
        peek?.mood = if (unreadTips) Mood.TIP else Mood.NAP
        peekFrame?.visibility = if (visible) View.INVISIBLE else View.VISIBLE
        peekWindow?.let {
            it.isTouchable = !visible
            it.update()
        }
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
        val panelWidth = dp(if (key == "companion") BUBBLE_WIDTH else PANEL_WIDTH)
        val padding = dp(if (key == "companion") 10 else 14)
        val body = ScrollView(this).apply {
            background = rounded(SURFACE, 16)
            setPadding(padding, padding, padding, padding)
            addView(content)
        }
        val window = PopupWindow(body, panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isClippingEnabled = true
        }
        val leftSide = key in LEFT_PANELS
        val side = if (leftSide) Gravity.START else Gravity.END
        window.height = minOf(dp(520), canvas.height - dp(84)).let {
            body.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED,
            )
            minOf(it, body.measuredHeight)
        }
        if (key == "companion") {
            val spot = bubbleSpot(panelWidth, window.height)
            window.showAtLocation(canvas, Gravity.NO_GRAVITY, spot[0], spot[1])
        } else {
            window.showAtLocation(canvas, Gravity.TOP or side, dp(12), dp(68))
        }
        panel = window
        panelKey = key
        if (key == "view") {
            showHandles()
            showFigureHandles()
        }
    }

    /** Places the bubble beside the companion, on whichever side has room. */
    private fun bubbleSpot(width: Int, height: Int): IntArray {
        val size = dp(56)
        val gap = dp(8)
        val right = companionX + size + gap
        val x = if (right + width <= canvas.width) right else max(0, companionX - gap - width)
        val y = (companionY + size / 2 - height / 2).coerceIn(0, max(0, canvas.height - height))
        return intArrayOf(x, y)
    }

    private fun closePanel() {
        hideHandles()
        if (panelKey == "adjust") canvas.act { NativeEngine.setAdjustment(it, false, FloatArray(6)) }
        if (panelKey == "color") rememberColor()
        panel?.dismiss()
        panel = null
        panelKey = ""
    }

    private fun companionTips(): List<Tip> = listOfNotNull(
        referenceMatch?.let { Tip("Your references", "${it.summary} ${it.advice}", it.why) },
        perspectiveLog.summary()?.let {
            Tip("Perspective", it, tr("A straight line counts as drifted when it's over 4° from the guide."))
        },
    ) + report?.tips.orEmpty()

    private fun companionPanel(): View = column().apply {
        val tips = companionTips()
        val tip = tips.getOrNull(tipIndex.mod(max(tips.size, 1)))
        if (tip == null) {
            addView(label(tr("Draw a little more, then pause. I'll have a note for you."), 14f))
        } else {
            addView(label(tr(tip.topic), 13f, MUTED_TEXT))
            addView(label(tip.text, 14f))
        }
        val actions = ArrayList<View>()
        if (tips.size > 1) {
            actions += smallChip(tr("Next")) {
                tipIndex++
                rerender("companion", ::companionPanel)
            }
        }
        if (tip?.why?.isNotEmpty() == true) {
            actions += smallChip(tr("Why"), companionSection == 1) {
                companionSection = if (companionSection == 1) 0 else 1
                rerender("companion", ::companionPanel)
            }
        }
        if (referenceMatch != null) {
            actions += smallChip(tr("Match"), companionSection == 3) {
                companionSection = if (companionSection == 3) 0 else 3
                rerender("companion", ::companionPanel)
            }
        }
        if (report?.ideas?.isNotEmpty() == true) {
            actions += smallChip(tr("Idea"), companionSection == 5) {
                if (companionSection == 5) ideaIndex++
                companionSection = 5
                rerender("companion", ::companionPanel)
            }
        }
        actions += smallChip(tr("Companion"), companionSection == 2) {
            companionSection = if (companionSection == 2) 0 else 2
            rerender("companion", ::companionPanel)
        }
        gapped(wrapRow(actions))
        report?.ideas?.takeIf { companionSection == 5 && it.isNotEmpty() }?.let { ideas ->
            val idea = ideas[ideaIndex.mod(ideas.size)]
            gapped(label(tr("Idea"), 12f, MUTED_TEXT))
            addView(label(idea.text, 14f))
            val brush = (BrushKind.entries + customBrushes).firstOrNull { it.id == idea.brushId }
            if (brush != null || idea.accentHue != null) {
                gapped(
                    wrapRow(
                        listOf(
                            smallChip(tr("Try it"), true) {
                                brush?.let(::selectTool)
                                idea.accentHue?.let { hue ->
                                    setColor(Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.85f)))
                                    rememberColor()
                                }
                                closePanel()
                            },
                        ),
                    ),
                )
            }
        }
        val match = referenceMatch
        if (companionSection == 3 && match != null) {
            match.areas.forEach { area ->
                gapped(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label(tr(area.name), 13f, MUTED_TEXT), LinearLayout.LayoutParams(dp(96), -2))
                        addView(
                            android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                                max = 100
                                progress = (area.score * 100).roundToInt()
                                progressTintList = ColorStateList.valueOf(ACCENT)
                                contentDescription = tr("%1 match", tr(area.name))
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                    },
                )
            }
            if (match.colors.isNotEmpty()) {
                gapped(label(tr("Colors to try"), 13f, MUTED_TEXT))
                addView(
                    LinearLayout(context).apply {
                        match.colors.forEach { color ->
                            addView(
                                View(context).apply {
                                    background = rounded(color, 8)
                                    contentDescription = tr("Use this color")
                                    setOnClickListener {
                                        setColor(color)
                                        rememberColor()
                                    }
                                },
                                LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(3) },
                            )
                        }
                    },
                )
            }
        }
        if (companionSection == 1 && tip != null) {
            gapped(label(tip.why, 13f, MUTED_TEXT))
            val current = report
            val images = ArrayList<Pair<String, Bitmap?>>()
            if (tip.topic == "Your references" && match != null) {
                images += tr("Yours") to BitmapFactory.decodeFile(store.thumbnail(document.id).path)
                images += tr("Closest reference") to matchedFiles.getOrNull(match.closest)?.let { decodeReference(it, dp(150)) }
            } else if (current != null && (tip.topic == "Values" || tip.topic == "Silhouette")) {
                val pixels = if (tip.topic == "Values") current.values else current.silhouette
                images += tr("Preview") to Bitmap.createBitmap(pixels, current.width, current.height, Bitmap.Config.ARGB_8888)
            }
            if (images.isNotEmpty()) {
                gapped(
                    LinearLayout(context).apply {
                        images.forEach { (name, bitmap) ->
                            addView(
                                column().apply {
                                    addView(label(name, 12f, MUTED_TEXT))
                                    addView(
                                        ImageView(context).apply {
                                            setImageBitmap(bitmap)
                                            scaleType = ImageView.ScaleType.FIT_START
                                            contentDescription = name
                                        },
                                        LinearLayout.LayoutParams(-1, dp(96)),
                                    )
                                },
                                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) },
                            )
                        }
                    },
                )
            }
        }
        if (companionSection == 2) {
            gapped(
                wrapRow(
                    listOf(smallChip(tr("Hide")) { setCompanionVisible(false) }) +
                        CompanionLook.entries.map { look ->
                            smallChip(tr(look.label), companion.look == look) { chooseCompanion(look) }
                        },
                ),
            )
        }
    }

    private fun chooseCompanion(look: CompanionLook) {
        companion.look = look
        preferences.edit().putString("mascot", look.name).apply()
        peek?.look = look
        rerender("companion", ::companionPanel)
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

    private fun toolGrid(
        kinds: List<BrushKind>,
        previewColor: Int,
        favorites: Set<String> = emptySet(),
    ) = GridLayout(this).apply {
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
                    if (!kind.isEraser) {
                        setOnLongClickListener {
                            toggleFavorite(kind)
                            true
                        }
                    }
                    addView(BrushPreview(context, kind, previewColor), LinearLayout.LayoutParams(-1, dp(48)))
                    addView(label(if (kind.id in favorites) "★ ${tr(kind.label)}" else tr(kind.label), 13f))
                },
                GridLayout.LayoutParams().apply {
                    width = dp(144)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                },
            )
        }
    }

    private fun favoriteIds(): Set<String> = preferences.getStringSet("favorites", emptySet()).orEmpty()

    private fun toggleFavorite(kind: BrushKind) {
        val favorites = favoriteIds().toMutableSet()
        if (!favorites.remove(kind.id)) favorites.add(kind.id)
        preferences.edit().putStringSet("favorites", favorites).apply()
        rerender("brushes", ::brushPanel)
    }

    private fun brushPanel(): View = column().apply {
        addView(label(tr("Brushes"), 16f))
        val color = if (Color.luminance(canvas.brushColor) < 0.08f) TEXT_COLOR else canvas.brushColor
        val favorites = favoriteIds()
        val brushes = (BrushKind.PAINT_BRUSHES + BrushKind.TEXTURE_BRUSHES + customBrushes).sortedBy { it.id !in favorites }
        gapped(toolGrid(brushes, color, favorites))
        gapped(label(tr("Tools"), 13f, MUTED_TEXT))
        gapped(
            LinearLayout(context).apply {
                listOf(
                    Triple(BrushKind.SMUDGE, R.drawable.ic_smudge, tr("Blend colors")),
                    Triple(BrushKind.FILL, R.drawable.ic_fill, tr("Fill an area")),
                    Triple(BrushKind.GRADIENT, R.drawable.ic_gradient, tr("Fade a color")),
                ).forEachIndexed { index, (kind, icon, detail) ->
                    addView(
                        toolCard(icon, tr(kind.label), detail, canvas.brushKind === kind) {
                            selectTool(kind)
                            closePanel()
                        },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) },
                    )
                }
            },
        )
        addView(toolSlider(tr("Stabilizer"), (toolFor(canvas.brushKind).stabilizer * 100).roundToInt()) {
            toolFor(canvas.brushKind).stabilizer = it / 100f
            canvas.stabilizerStrength = it / 100f
        })
        val gamma = ((canvas.pressureGamma - 0.4f) / 1.2f * 100).roundToInt()
        addView(toolSlider(tr("Pressure: soft to firm"), gamma) {
            canvas.pressureGamma = 0.4f + it / 100f * 1.2f
            preferences.edit().putFloat("pressureGamma", canvas.pressureGamma).apply()
        })
        val active = canvas.brushKind
        val actions = arrayListOf<View>(smallChip(tr("New brush")) { openBrushEditor(null) })
        if (active.custom) {
            actions += smallChip(tr("Edit")) { openBrushEditor(active) }
            actions += smallChip(tr("Delete")) { deleteBrush(active) }
        }
        gapped(wrapRow(actions))
        gapped(label(tr("Hold a brush to keep it at the top."), 12f, MUTED_TEXT))
    }

    private fun toolSlider(name: String, value: Int, change: (Int) -> Unit): View = column().apply {
        gapped(label(name, 12f, MUTED_TEXT))
        addView(
            SeekBar(context).apply {
                max = 100
                progress = value
                contentDescription = name
                progressTintList = ColorStateList.valueOf(ACCENT)
                thumbTintList = ColorStateList.valueOf(ACCENT)
                onChange(change)
            },
        )
    }

    private fun deleteBrush(brush: BrushKind) {
        store.deleteBrush(brush.id)
        customBrushes.removeAll { it.id == brush.id }
        selectTool(BrushKind.INK)
        rerender("brushes", ::brushPanel)
    }

    private fun openBrushEditor(base: BrushKind?) {
        editingBrush = base
        draftTip = base?.tip
        closePanel()
        showPanel("brushEditor", brushEditor(base ?: BrushKind.INK, base != null))
    }

    private fun draftBrush(source: BrushKind, name: String, shape: FloatArray) = BrushKind(
        editingBrush?.id ?: UUID.randomUUID().toString(), name.ifBlank { tr("My brush") },
        shape[0], shape[1], shape[2], shape[3], shape[4], shape[5], source.defaultSize,
        tip = draftTip, custom = true,
    )

    private fun brushEditor(source: BrushKind, editing: Boolean): View = column().apply {
        addView(label(if (editing) tr("Edit brush") else tr("New brush"), 16f))
        val shape = floatArrayOf(
            source.minScale, source.spacing, source.flow, source.pressureFlow, source.hardness, source.grain,
        )
        val name = EditText(context).apply {
            setText(if (editing) source.label else "")
            hint = tr("Brush name")
            setTextColor(TEXT_COLOR)
            setHintTextColor(MUTED_TEXT)
            isSingleLine = true
        }
        gapped(name)
        val preview = FrameLayout(context)
        fun refresh() {
            preview.removeAllViews()
            preview.addView(BrushPreview(context, draftBrush(source, name.text.toString(), shape), TEXT_COLOR))
        }
        refresh()
        gapped(preview.apply { minimumHeight = dp(64) })
        preview.layoutParams.height = dp(64)
        listOf(
            Triple(tr("Size taper"), 0, 1f),
            Triple(tr("Spacing"), 1, 0.5f),
            Triple(tr("Flow"), 2, 1f),
            Triple(tr("Pressure fade"), 3, 1f),
            Triple(tr("Hardness"), 4, 1f),
            Triple(tr("Grain"), 5, 1f),
        ).forEach { (title, index, range) ->
            addView(toolSlider(title, (shape[index] / range * 100).roundToInt()) {
                shape[index] = maxOf(0.02f, it / 100f * range)
                refresh()
            })
        }
        gapped(
            wrapRow(
                listOf(
                    smallChip(if (draftTip == null) tr("Import tip") else tr("Replace tip")) {
                        pendingBrushName = name.text.toString()
                        pendingBrushShape = shape
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                            PICK_TIP,
                        )
                    },
                    smallChip(tr("Save"), true) {
                        val brush = draftBrush(source, name.text.toString(), shape)
                        store.saveBrush(brush)
                        customBrushes.removeAll { it.id == brush.id }
                        customBrushes.add(brush)
                        closePanel()
                        selectTool(brush)
                    },
                ),
            ),
        )
    }

    private fun tipFrom(bitmap: Bitmap): ByteArray {
        val size = BRUSH_TIP_SIZE
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        return ByteArray(size * size) { index ->
            val pixel = pixels[index]
            val darkness = 1f - Color.luminance(pixel or Color.BLACK)
            (darkness * Color.alpha(pixel)).toInt().toByte()
        }
    }

    private fun showPickRing(color: Int, x: Float, y: Float) {
        val size = dp(112)
        val ring = pickView ?: PickRing(this, canvas.brushColor).also { view ->
            pickView = view
            pickRing = PopupWindow(view, size, size).apply {
                isClippingEnabled = false
                isTouchable = false
                showAtLocation(canvas, Gravity.NO_GRAVITY, x.toInt() - size / 2, y.toInt() - size - dp(24))
            }
        }
        ring.picked = color
        pickRing?.update(x.toInt() - size / 2, y.toInt() - size - dp(24), -1, -1)
    }

    private fun hidePickRing() {
        pickView?.let {
            setColor(it.picked)
            rememberColor()
        }
        pickRing?.dismiss()
        pickRing = null
        pickView = null
    }

    private fun selectionPanel(): View = column().apply {
        addView(label(tr("Selection"), 16f))
        gapped(
            LinearLayout(context).apply {
                addView(
                    toolCard(R.drawable.ic_select, tr("Lasso"), tr("Draw around an area"), false) {
                        startSelection(SelectionTool.LASSO, tr("Draw around an area with your pen"))
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                addView(
                    toolCard(R.drawable.ic_wand, tr("Magic wand"), tr("Pick a color area"), false) {
                        startSelection(SelectionTool.WAND, tr("Tap a color area with your pen"))
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
                )
            },
        )
        if (!hasSelection) {
            gapped(label(tr("Make a selection to keep painting inside it, or to move part of a layer."), 13f, MUTED_TEXT))
            return@apply
        }
        gapped(actionRow(R.drawable.ic_transform, tr("Transform"), tr("Move, scale, or rotate the selection")) { selectionToLayer(cut = true, transform = true) })
        gapped(actionRow(R.drawable.ic_copy, tr("Copy to layer")) { selectionToLayer(cut = false, transform = false) })
        gapped(actionRow(R.drawable.ic_cut, tr("Cut to layer")) { selectionToLayer(cut = true, transform = false) })
        gapped(
            actionRow(R.drawable.ic_invert, tr("Invert")) {
                canvas.act { NativeEngine.invertSelection(it) }
                closePanel()
            },
        )
        gapped(actionRow(R.drawable.ic_close, tr("Clear selection")) { clearSelection() })
    }

    private fun startSelection(tool: SelectionTool, hint: String) {
        canvas.selectionTool = tool
        closePanel()
        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
    }

    private fun markSelection(active: Boolean) {
        hasSelection = active
        selectButton?.background = if (active) rounded(SURFACE_RAISED, 18) else null
    }

    internal fun clearSelection() {
        canvas.act { NativeEngine.clearSelection(it) }
        markSelection(false)
        closePanel()
    }

    internal fun finishLasso(path: FloatArray) {
        canvas.selectionTool = SelectionTool.NONE
        lassoWindow?.dismiss()
        lassoWindow = null
        lassoOverlay.clear()
        canvas.act { NativeEngine.setSelectionPolygon(it, path) }
        markSelection(path.size >= 6)
    }

    private fun finishWand(x: Int, y: Int) {
        canvas.selectionTool = SelectionTool.NONE
        canvas.act { NativeEngine.selectColorAt(it, x, y, 40) }
        markSelection(true)
    }

    private fun showLassoOverlay() {
        if (lassoWindow != null) return
        lassoWindow = PopupWindow(lassoOverlay, canvas.width, canvas.height).apply {
            isTouchable = false
            isClippingEnabled = false
            showAtLocation(canvas, Gravity.NO_GRAVITY, 0, 0)
        }
    }

    internal fun selectionToLayer(cut: Boolean, transform: Boolean) {
        if (document.layers.size >= maxLayers) {
            Toast.makeText(this, tr("This canvas can hold %1 layers", maxLayers), Toast.LENGTH_LONG).show()
            return
        }
        val id = document.nextLayerId++
        engineLayers.add(id)
        canvas.act { NativeEngine.copySelectionToLayer(it, id, cut) }
        editLayers(pixels = cut) {
            val index = document.layers.indexOfFirst { it.id == document.activeLayer } + 1
            document.layers.add(index, LayerInfo(id, tr("Selection %1", id)))
            document.activeLayer = id
        }
        clearSelection()
        if (transform) startTransform()
    }

    private fun previewGradient(points: FloatArray) {
        val color = canvas.brushColor
        val rgb = floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        canvas.act { NativeEngine.setGradientPreview(it, true, points, rgb) }
    }

    private fun finishGradient() {
        canvas.act { NativeEngine.commitGradient(it) }
        history.record(Edit.Stroke)
        markUnsaved()
    }

    private fun adjustPanel(): View = column().apply {
        addView(label(tr("Adjust layer"), 16f))
        adjustment = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f)
        pushAdjustment()
        fun slider(title: String, index: Int, low: Float, high: Float, start: Int) {
            addView(toolSlider(title, start) {
                adjustment[index] = low + (high - low) * it / 100f
                pushAdjustment()
            })
        }
        slider(tr("Hue"), 0, -0.5f, 0.5f, 50)
        slider(tr("Saturation"), 1, 0f, 2f, 50)
        slider(tr("Brightness"), 2, -0.5f, 0.5f, 50)
        slider(tr("Shadows"), 3, -0.5f, 0.5f, 50)
        slider(tr("Midtones"), 4, -0.5f, 0.5f, 50)
        slider(tr("Highlights"), 5, -0.5f, 0.5f, 50)
        gapped(
            wrapRow(
                listOf(
                    smallChip(tr("Cancel")) { closePanel() },
                    smallChip(tr("Apply"), true) {
                        panelKey = ""
                        panel?.dismiss()
                        applyAdjustment()
                    },
                ),
            ),
        )
    }

    internal fun applyAdjustment(values: FloatArray = adjustment) {
        adjustment = values
        pushAdjustment()
        canvas.act { NativeEngine.commitAdjustment(it) }
        history.record(Edit.Stroke)
        markUnsaved()
    }

    internal fun hideActiveGroup() {
        val id = activeLayer()?.groupId ?: return
        editLayers(structural = false) { document.groups.firstOrNull { it.id == id }?.visible = false }
    }

    private fun pushAdjustment() {
        val values = adjustment.copyOf()
        canvas.act { NativeEngine.setAdjustment(it, true, values) }
    }

    private fun pushFigure() {
        val figure = document.figure
        canvas.act {
            if (figure.size == 3) {
                NativeEngine.setFigureGuide(it, figure[0], figure[1], figure[2])
            } else {
                NativeEngine.setFigureGuide(it, 0f, 0f, 0f)
            }
        }
    }

    private fun setFigure(heads: Float) {
        val height = document.height.toFloat()
        document.figure = if (heads <= 0f) {
            emptyList()
        } else {
            listOf(document.figure.getOrElse(0) { height * 0.08f }, document.figure.getOrElse(1) { height * 0.92f }, heads)
        }
        pushFigure()
        markUnsaved()
        rerender("view", ::viewPanel)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFigureHandles() {
        if (document.figure.size != 3) return
        val size = dp(36)
        for (index in 0..1) {
            val handle = View(this).apply {
                background = rounded(0xFF1D9E75.toInt(), 18).apply { setStroke(dp(3), TEXT_COLOR) }
                contentDescription = if (index == 0) tr("Top of head") else tr("Bottom of feet")
            }
            val window = PopupWindow(handle, size, size).apply { isClippingEnabled = false }
            handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val y = canvas.screenToCanvas(event.rawX, event.rawY)[1]
                    document.figure = document.figure.toMutableList().also { it[index] = y }
                    pushFigure()
                    val spot = figureSpot(index)
                    window.update(spot[0], spot[1], -1, -1)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    markUnsaved()
                }
                true
            }
            val spot = figureSpot(index)
            window.showAtLocation(canvas, Gravity.NO_GRAVITY, spot[0], spot[1])
            figureHandles.add(window)
        }
    }

    private fun figureSpot(index: Int): IntArray {
        val point = canvas.canvasToScreen(document.width * 0.95f, document.figure[index])
        return intArrayOf(point[0].toInt() - dp(18), point[1].toInt() - dp(18))
    }

    private fun exportTimelapse() {
        val frames = store.timelapseFrames(document.id)
        if (frames.size < 2) {
            Toast.makeText(this, tr("Draw a little more to build a time-lapse"), Toast.LENGTH_SHORT).show()
            return
        }
        val name = document.name
        val width = ArtworkStore.THUMBNAIL_WIDTH * 2
        val height = document.thumbnailHeight * 2
        Toast.makeText(this, tr("Exporting time-lapse"), Toast.LENGTH_SHORT).show()
        saver.execute {
            val saved = runCatching {
                val target = store.exportTimelapseTarget(name)!!
                contentResolver.openFileDescriptor(target, "w")!!.use {
                    TimelapseEncoder.encode(frames, width, height, it.fileDescriptor)
                }
            }.isSuccess
            runOnUiThread {
                val message = if (saved) tr("Saved to Movies/Cappuccino") else tr("Couldn't export this time-lapse")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fillAt(x: Int, y: Int) {
        val color = canvas.brushColor
        canvas.act { engine ->
            val changed = NativeEngine.fillAt(
                engine, x, y, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f,
            )
            if (changed) {
                runOnUiThread {
                    history.record(Edit.Stroke)
                    markUnsaved()
                }
            }
        }
    }

    internal fun finishTransform(keep: Boolean) {
        canvas.endTransform(keep)
        if (keep) {
            history.record(Edit.Stroke)
            markUnsaved()
        }
        transformControls?.dismiss()
    }

    internal fun startTransform() {
        val layer = activeLayer() ?: return
        closePanel()
        canvas.beginTransform(layer.id)
        val controls = pill().apply {
            addView(chip(tr("Cancel")) { finishTransform(keep = false) })
            addView(
                chip(tr("Done"), true) { finishTransform(keep = true) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
            )
        }
        transformControls = PopupWindow(controls, -2, -2).apply {
            showAtLocation(canvas, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(24))
        }
        Toast.makeText(this, tr("Drag to move. Pinch to scale and rotate."), Toast.LENGTH_SHORT).show()
    }

    private fun eraserPanel() = column().apply {
        addView(label(tr("Erasers"), 16f))
        gapped(toolGrid(BrushKind.entries.filter { it.isEraser }, TEXT_COLOR))
        gapped(label(tr("Every brush shape can erase, and each one remembers its own size and opacity."), 13f, MUTED_TEXT))
    }

    private fun swatchRow(colors: List<Int>, size: Int = 34, onLongPress: (() -> Unit)? = null) =
        LinearLayout(this).apply {
            colors.forEach { color ->
                addView(
                    View(context).apply {
                        background = rounded(color, 8)
                        contentDescription = tr("Color swatch")
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
        hideHandles()
        panel?.dismiss()
        showPanel(key, content())
    }

    private fun colorPanel(): View = column().apply {
        addView(
            segmented(listOf(tr("Picker"), tr("Harmony"), tr("Palettes")), colorTab) {
                colorTab = it
                rerender("color", ::colorPanel)
            },
        )
        when (colorTab) {
            0 -> {
                gapped(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            View(context).apply {
                                background = swatchBackground(colorBeforePanel)
                                contentDescription = tr("Previous color")
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
                gapped(label(tr("Values of this hue"), 13f, MUTED_TEXT))
                addView(swatchRow(valueScale(canvas.brushColor)))
                if (recentColors.isNotEmpty()) {
                    gapped(label(tr("Recent"), 13f, MUTED_TEXT))
                    addView(swatchRow(recentColors, 28))
                }
            }
            1 -> harmoniesFor(canvas.brushColor).forEach { harmony ->
                gapped(label(tr(harmony.name), 13f, MUTED_TEXT))
                addView(swatchRow(harmony.colors))
            }
            else -> {
                gapped(
                    wrapRow(
                        listOf(
                            smallChip(tr("From artwork")) { paletteFromArtwork() },
                            chip(tr("From reference")) {
                        val reference = references.values.lastOrNull()
                        if (reference == null) {
                            Toast.makeText(context, tr("Open a reference first"), Toast.LENGTH_SHORT).show()
                        } else {
                            onReferencePalette(reference)
                        }
                    },
                            smallChip(tr("Save recent")) {
                                if (recentColors.size < 2) {
                                    Toast.makeText(context, tr("Use at least two colors first"), Toast.LENGTH_SHORT).show()
                                } else {
                                    store.addPalette(tr("Recent colors"), recentColors.toList())
                                    rerender("color", ::colorPanel)
                                }
                            },
                        ),
                    ),
                )
                store.palettes().forEach { palette ->
                    gapped(label(tr(palette.name), 13f, MUTED_TEXT))
                    addView(swatchRow(palette.colors, onLongPress = { confirmDeletePalette(palette) }))
                }
                STARTER_PALETTES.forEach { palette ->
                    gapped(label(tr(palette.name), 13f, MUTED_TEXT))
                    addView(swatchRow(palette.colors))
                }
            }
        }
    }

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    private fun confirmDeletePalette(palette: Palette) {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete %1?", tr(palette.name)))
            .setNegativeButton(tr("Keep"), null)
            .setPositiveButton(tr("Delete")) { _, _ ->
                store.deletePalette(palette.id)
                rerender("color", ::colorPanel)
            }
            .show()
    }

    private fun savePalette(name: String, colors: List<Int>) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (colors.isEmpty()) {
                Toast.makeText(this, tr("There aren't any colors to collect yet"), Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            store.addPalette(name, colors)
            rerender("color", ::colorPanel)
        }
    }

    private fun paletteFromArtwork() {
        val width = ArtworkStore.THUMBNAIL_WIDTH
        val height = document.thumbnailHeight
        canvas.snapshot { engine ->
            val buffer = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, buffer)
            saver.execute {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                bitmap.recycle()
                savePalette(tr("%1 colors", document.name), extractPalette(pixels))
            }
        }
    }

    private fun viewPanel(): View = column().apply {
        addView(label(tr("Art direction"), 16f))
        gapped(label(tr("Check your image"), 12f, MUTED_TEXT))
        addView(segmented(listOf(tr("Color"), tr("Gray"), tr("2 values"), tr("3 values")), viewMode) {
            viewMode = it
            applyViewOptions()
        })
        gapped(label(tr("Composition"), 12f, MUTED_TEXT))
        addView(segmented(GUIDES.map { tr(if (it == "None") tr("Off") else it) }, guide) {
            guide = it
            applyViewOptions()
        })
        gapped(label(tr("Perspective"), 12f, MUTED_TEXT))
        addView(segmented(listOf(tr("Off"), tr("1 point"), tr("2 points"), tr("3 points")), document.perspective.size / 2) {
            setPerspectiveCount(it)
        })
        gapped(label(tr("Light direction"), 12f, MUTED_TEXT))
        addView(segmented(Light.entries.map { tr(it.label) }, document.light.ordinal) {
            document.light = Light.entries[it]
            markUnsaved()
            rerender("view", ::viewPanel)
        })
        gapped(label(tr("Figure height"), 12f, MUTED_TEXT))
        val figures = listOf(0f, 6f, 7.5f, 8f)
        addView(
            segmented(
                listOf(tr("Off"), tr("6 heads"), tr("7.5 heads"), tr("8 heads")),
                figures.indexOf(document.figure.getOrElse(2) { 0f }).coerceAtLeast(0),
            ) { setFigure(figures[it]) },
        )
        if (document.perspective.isNotEmpty() || document.figure.isNotEmpty()) {
            gapped(label(tr("Drag the circles to place your guides."), 12f, MUTED_TEXT))
        }
        gapped(actionRow(R.drawable.ic_flip, tr("Flip canvas"), tr("A fresh look at your drawing")) { canvas.flip() })
        gapped(label(tr("These change your view only, never your artwork."), 12f, MUTED_TEXT))
    }

    private fun setPerspectiveCount(count: Int) {
        val kept = document.perspective.take(count * 2)
        document.perspective = kept + defaultVanishingPoints(count, document.width, document.height).drop(kept.size)
        perspectiveLog.reset()
        pushPerspective()
        markUnsaved()
        rerender("view", ::viewPanel)
    }

    private fun pushPerspective() {
        val points = document.perspective.toFloatArray()
        canvas.act { NativeEngine.setPerspective(it, points) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showHandles() {
        val size = dp(36)
        val colors = listOf(ACCENT, 0xFF1D9E75.toInt(), 0xFF534AB7.toInt())
        for (index in 0 until document.perspective.size / 2) {
            val handle = View(this).apply {
                background = rounded(colors[index], 18).apply { setStroke(dp(3), TEXT_COLOR) }
                contentDescription = tr("Vanishing point %1", index + 1)
            }
            val window = PopupWindow(handle, size, size).apply { isClippingEnabled = false }
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val point = canvas.screenToCanvas(event.rawX, event.rawY)
                        document.perspective = document.perspective.toMutableList().also {
                            it[index * 2] = point[0]
                            it[index * 2 + 1] = point[1]
                        }
                        pushPerspective()
                        positionHandles()
                    }
                    MotionEvent.ACTION_UP -> {
                        perspectiveLog.reset()
                        markUnsaved()
                    }
                }
                true
            }
            val spot = handleSpot(index)
            window.showAtLocation(canvas, Gravity.NO_GRAVITY, spot[0], spot[1])
            perspectiveHandles.add(window)
        }
    }

    private fun handleSpot(index: Int): IntArray {
        val center = canvas.canvasToScreen(document.perspective[index * 2], document.perspective[index * 2 + 1])
        return intArrayOf(center[0].toInt() - dp(18), center[1].toInt() - dp(18))
    }

    private fun positionHandles() {
        perspectiveHandles.forEachIndexed { index, window ->
            val spot = handleSpot(index)
            window.update(spot[0], spot[1], -1, -1)
        }
    }

    private fun hideHandles() {
        perspectiveHandles.forEach { it.dismiss() }
        perspectiveHandles.clear()
        figureHandles.forEach { it.dismiss() }
        figureHandles.clear()
    }

    private fun checkPerspective(path: FloatArray) {
        val drift = strokeDrift(path, document.perspective) ?: return
        perspectiveLog.add(drift)
        if (drift > PERSPECTIVE_TOLERANCE) {
            unreadTips = true
            companion.mood = Mood.TIP
        }
    }

    private fun applyViewOptions() {
        val mode = viewMode
        val lines = guide
        canvas.act { NativeEngine.setViewOptions(it, mode, lines) }
        rerender("view", ::viewPanel)
    }

    private fun refreshLayers() = rerender("layers", ::layersPanel)

    internal fun setClip(layer: LayerInfo, clip: Boolean) {
        editLayers(structural = false) { layer.clip = clip }
    }

    private fun layerById(id: Int) = document.layers.firstOrNull { it.id == id }

    private fun captureLayers() = LayerState(document.layers, document.activeLayer, document.groups)

    private fun syncAlphaLock() {
        canvas.alphaLock = activeLayer()?.alphaLock == true
    }

    /** Sends the document's layers to the engine. Light edits skip the costly active layer copy. */
    private fun syncLayers(structural: Boolean) {
        val layers = document.layers.map { it.copy() }
        val active = document.activeLayer
        canvas.act { engine ->
            if (structural) NativeEngine.setLayerOrder(engine, layers.map { it.id }.toIntArray())
            layers.forEach { applyLayerProperties(engine, it) }
            if (structural) NativeEngine.setActiveLayer(engine, active)
        }
        syncAlphaLock()
    }

    private fun editLayers(
        mergeKey: String? = null,
        structural: Boolean = true,
        pixels: Boolean = false,
        change: () -> Unit,
    ) {
        val before = captureLayers()
        change()
        history.record(Edit.Layers(before, captureLayers(), pixels, mergeKey))
        syncLayers(structural)
        markUnsaved()
        releaseRetiredLayers()
    }

    /** Layers removed from the document stay in the engine for undo, within a memory limit. */
    private fun releaseRetiredLayers() {
        if (engineLayers.size <= maxLayers + RETIRED_LAYERS) return
        val current = document.layers.map { it.id }.toSet()
        val retired = engineLayers - current
        history.clear()
        engineLayers.removeAll(retired)
        canvas.act { engine -> retired.forEach { NativeEngine.deleteLayer(engine, it) } }
        Toast.makeText(this, tr("Older undo steps were cleared to free memory"), Toast.LENGTH_LONG).show()
    }

    private fun restoreLayers(state: LayerState) {
        val previous = document.layers.map { it.id }.toSet()
        document.layers.clear()
        document.layers.addAll(state.layers.map { it.copy() })
        document.activeLayer = state.activeLayer
        document.groups.clear()
        document.groups.addAll(state.groups.map { it.copy() })
        // Only layers coming back need saving again. Their old files may already be gone.
        val returning = document.layers.map { it.id }.filter { it !in previous }
        canvas.act { engine -> returning.forEach { NativeEngine.markLayerDirty(engine, it) } }
        syncLayers(structural = true)
        refreshLayers()
    }

    private fun undoEdit() {
        when (val edit = history.undo()) {
            null -> return
            Edit.Stroke -> canvas.undo()
            is Edit.Layers -> {
                if (edit.pixels) canvas.undo()
                restoreLayers(edit.before)
            }
        }
        markUnsaved()
    }

    private fun redoEdit() {
        when (val edit = history.redo()) {
            null -> return
            Edit.Stroke -> canvas.redo()
            is Edit.Layers -> {
                if (edit.pixels) canvas.redo()
                restoreLayers(edit.after)
            }
        }
        markUnsaved()
    }

    internal val layerCount get() = document.layers.size
    internal val currentColor get() = canvas.brushColor

    internal fun activeLayer() = document.layers.firstOrNull { it.id == document.activeLayer }

    private fun selectLayer(layer: LayerInfo) {
        document.activeLayer = layer.id
        syncAlphaLock()
        canvas.act { NativeEngine.setActiveLayer(it, layer.id) }
        markUnsaved()
    }

    private fun layersPanel(): View = column().apply {
        layerThumbnails.clear()
        val active = activeLayer()
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(tr("Layers"), 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_add, tr("Add layer")) { addLayer() })
            },
        )
        val rows = ArrayList<View>()
        val list = column()
        var shownGroup = 0
        document.layers.asReversed().forEach { layer ->
            val group = document.groups.firstOrNull { it.id == layer.groupId }
            if (group != null && group.id != shownGroup) {
                list.addView(groupRow(group), LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(LAYER_GAP) })
            }
            shownGroup = group?.id ?: 0
            if (group?.collapsed == true) return@forEach
            val row = layerRow(layer, rows)
            rows.add(row)
            list.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(LAYER_ROW)).apply {
                    topMargin = dp(LAYER_GAP)
                    marginStart = dp((if (group != null) 12 else 0) + (if (layer.clip) 20 else 0))
                },
            )
        }
        gapped(list)
        requestLayerThumbnails()
        if (active == null) return@apply

        gapped(label(tr("Opacity"), 12f, MUTED_TEXT))
        addView(
            SeekBar(context).apply {
                max = 100
                progress = (active.opacity * 100).roundToInt()
                progressTintList = ColorStateList.valueOf(ACCENT)
                thumbTintList = ColorStateList.valueOf(ACCENT)
                contentDescription = tr("Layer opacity")
                onChange {
                    val value = it / 100f
                    editLayers(mergeKey = "opacity${active.id}", structural = false) {
                        layerById(active.id)?.opacity = value
                    }
                }
            },
        )
        gapped(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(tr("Blend"), 12f, MUTED_TEXT), LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    smallChip("${tr(BLEND_MODES[active.blend])} ▾") { }.apply {
                        setOnClickListener { chooseBlend(this, active) }
                    },
                )
            },
        )
        gapped(
            tileGrid(
                listOf(
                    iconTile(R.drawable.ic_transform, tr("Transform")) { startTransform() },
                    iconTile(R.drawable.ic_adjust, tr("Adjust")) {
                        closePanel()
                        showPanel("adjust", adjustPanel())
                    },
                    iconTile(R.drawable.ic_clip, tr("Clip"), active.clip) {
                        setClip(active, !active.clip)
                        refreshLayers()
                    },
                    iconTile(R.drawable.ic_group, if (active.groupId != 0) tr("Ungroup") else tr("Group")) { toggleGroup(active) },
                    iconTile(R.drawable.ic_duplicate, tr("Duplicate")) { duplicateLayer(active) },
                    iconTile(R.drawable.ic_merge, tr("Merge")) { mergeDown(active) },
                    iconTile(R.drawable.ic_trash, tr("Delete")) { deleteLayer(active) },
                ),
            ),
        )
        gapped(label(tr("Hold a layer to rename it. Drag its lines to reorder."), 12f, MUTED_TEXT))
    }

    private fun groupRow(group: LayerGroup): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(ROAST, 10)
        setPadding(dp(10), 0, dp(2), 0)
        setOnClickListener {
            group.collapsed = !group.collapsed
            markUnsaved()
            refreshLayers()
        }
        setOnLongClickListener {
            askForName(tr("Rename group"), group.name) { name ->
                editLayers(structural = false) { group.name = name }
                refreshLayers()
            }
            true
        }
        addView(
            label("${if (group.collapsed) "▸" else "▾"} ${group.name}"),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            iconButton(if (group.visible) R.drawable.ic_visible else R.drawable.ic_hidden, tr("Show or hide %1", group.name)) {
                editLayers(structural = false) { group.visible = !group.visible }
                refreshLayers()
            },
        )
    }

    internal fun toggleGroup(layer: LayerInfo) {
        if (layer.groupId != 0) {
            val id = layer.groupId
            editLayers {
                document.layers.filter { it.groupId == id }.forEach { it.groupId = 0 }
                document.groups.removeAll { it.id == id }
            }
        } else {
            val index = document.layers.indexOf(layer)
            val below = document.layers.getOrNull(index - 1)
            val id = below?.groupId?.takeIf { it != 0 } ?: document.nextGroupId++
            editLayers {
                if (document.groups.none { it.id == id }) document.groups.add(LayerGroup(id, tr("Group %1", id)))
                layer.groupId = id
                below?.groupId = id
            }
        }
        refreshLayers()
    }

    private fun chooseBlend(anchor: View, layer: LayerInfo) {
        PopupMenu(this, anchor).apply {
            BLEND_MODES.forEachIndexed { index, name -> menu.add(0, index, index, tr(name)) }
            setOnMenuItemClickListener {
                val blend = it.itemId
                editLayers(structural = false) { layerById(layer.id)?.blend = blend }
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
            contentDescription = tr("%1 preview", layer.name)
        }
        layerThumbnails[layer.id] = thumbnail
        val handle = ImageView(this).apply {
            setImageResource(R.drawable.ic_handle)
            contentDescription = tr("Reorder %1", layer.name)
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
                iconButton(if (layer.alphaLock) R.drawable.ic_lock else R.drawable.ic_unlock, tr("Lock transparency")) {
                    editLayers(structural = false) { layer.alphaLock = !layer.alphaLock }
                    syncAlphaLock()
                    refreshLayers()
                },
            )
            addView(
                iconButton(if (layer.visible) R.drawable.ic_visible else R.drawable.ic_hidden, tr("Show or hide %1", layer.name)) {
                    editLayers(structural = false) { layer.visible = !layer.visible }
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
                        moveLayerTo(layer.id, document.layers.indexOf(layer) - moved)
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

    internal fun addLayer() {
        if (document.layers.size >= maxLayers) {
            Toast.makeText(this, tr("This canvas can hold %1 layers", maxLayers), Toast.LENGTH_LONG).show()
            return
        }
        val id = document.nextLayerId++
        engineLayers.add(id)
        canvas.act { NativeEngine.createLayer(it, id) }
        editLayers {
            val index = document.layers.indexOfFirst { it.id == document.activeLayer } + 1
            document.layers.add(index, LayerInfo(id, tr("Layer %1", id)))
            document.activeLayer = id
        }
        refreshLayers()
    }

    private fun duplicateLayer(layer: LayerInfo) {
        if (document.layers.size >= maxLayers) {
            Toast.makeText(this, tr("This canvas can hold %1 layers", maxLayers), Toast.LENGTH_LONG).show()
            return
        }
        val id = document.nextLayerId++
        engineLayers.add(id)
        canvas.act { NativeEngine.duplicateLayer(it, layer.id, id) }
        editLayers {
            val copy = layer.copy(id = id, name = tr("%1 copy", layer.name), file = null)
            document.layers.add(document.layers.indexOf(layer) + 1, copy)
            document.activeLayer = id
        }
        refreshLayers()
    }

    internal fun mergeDown(layer: LayerInfo) {
        val index = document.layers.indexOf(layer)
        if (index == 0) {
            Toast.makeText(this, tr("There isn't a layer below this one"), Toast.LENGTH_SHORT).show()
            return
        }
        val below = document.layers[index - 1]
        canvas.act { NativeEngine.mergeLayerDown(it, layer.id, below.id) }
        editLayers(pixels = true) {
            document.layers.remove(layer)
            document.activeLayer = below.id
        }
        refreshLayers()
    }

    private fun moveLayerTo(id: Int, target: Int) {
        val layer = layerById(id) ?: return
        val to = target.coerceIn(0, document.layers.size - 1)
        if (to == document.layers.indexOf(layer)) return
        editLayers {
            document.layers.remove(layer)
            document.layers.add(to, layer)
        }
        refreshLayers()
    }

    internal fun deleteLayer(layer: LayerInfo) {
        if (document.layers.size == 1) {
            Toast.makeText(this, tr("An artwork needs at least one layer"), Toast.LENGTH_SHORT).show()
            return
        }
        editLayers {
            val index = document.layers.indexOf(layer)
            document.layers.remove(layer)
            document.activeLayer = document.layers[max(0, index - 1)].id
        }
        refreshLayers()
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
            .setNegativeButton(tr("Cancel"), null)
            .setPositiveButton(tr("Save")) { _, _ ->
                field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done)
            }
            .show()
    }

    private fun renameLayer(layer: LayerInfo) = askForName(tr("Rename layer"), layer.name) { name ->
        editLayers(structural = false) { layer.name = name }
        refreshLayers()
    }

    private fun actionsPanel() = column().apply {
        addView(label(document.name, 16f))
        addView(label(tr("%1 x %2 pixels · %3 of %4 layers", document.width, document.height, document.layers.size, maxLayers), 12f, MUTED_TEXT))
        gapped(
            actionRow(R.drawable.ic_rename, tr("Rename artwork")) {
                closePanel()
                askForName(tr("Rename artwork"), document.name) {
                    document.name = it
                    markUnsaved()
                }
            },
        )
        gapped(label(tr("Export"), 13f, MUTED_TEXT))
        gapped(
            actionRow(R.drawable.ic_export, tr("PNG image"), tr("Flattened, saved to Pictures")) {
                closePanel()
                exportPng()
            },
        )
        gapped(
            actionRow(R.drawable.ic_layersfile, tr("PSD file"), tr("Keeps your layers, saved to Download")) {
                closePanel()
                exportPsd()
            },
        )
        gapped(
            actionRow(R.drawable.ic_video, tr("Time-lapse"), tr("A video of your progress")) {
                closePanel()
                exportTimelapse()
            },
        )
        gapped(label(tr("Language"), 13f, MUTED_TEXT))
        gapped(
            segmented(Language.entries.map { it.label }, I18n.language.ordinal) { index ->
                setLanguage(Language.entries[index])
            },
        )
        gapped(label(tr("Companion"), 13f, MUTED_TEXT))
        val visible = preferences.getBoolean("companion", true)
        gapped(
            actionRow(R.drawable.ic_companion, if (visible) tr("Hide companion") else tr("Wake companion")) {
                setCompanionVisible(!visible)
            },
        )
    }

    private fun setLanguage(language: Language) {
        if (language == I18n.language) return
        (application as CappuccinoApp).saveLanguage(language)
        save()
        startActivity(
            Intent(this, GalleryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    /** Sends one layer at a time through the GL thread, so a tablet isn't short of memory. */
    private fun exportPsd() {
        val layers = document.layers.map { it.copy() }
        val name = document.name
        val width = document.width
        val height = document.height
        Toast.makeText(this, tr("Exporting PSD"), Toast.LENGTH_SHORT).show()
        canvas.snapshot { engine ->
            val parts = layers.map { layer ->
                val pixels = ArtworkStore.pixelBuffer(width, height)
                NativeEngine.readLayer(engine, layer.id, pixels)
                layer to pixels
            }
            val flat = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, flat)
            saver.execute {
                val saved = runCatching {
                    val psdLayers = parts.map { (layer, pixels) ->
                        val bytes = ByteArray(pixels.capacity())
                        pixels.rewind()
                        pixels.get(bytes)
                        PsdLayer(layer.name, layer.opacity, layer.visible, layer.blend, layer.clip, bytes)
                    }
                    val merged = ByteArray(flat.capacity())
                    flat.rewind()
                    flat.get(merged)
                    store.exportPsd(name, width, height, psdLayers, merged)
                }.getOrDefault(false)
                runOnUiThread {
                    val message = if (saved) tr("Saved to Download/Cappuccino") else tr("Couldn't export this PSD")
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exportPng() {
        val name = document.name
        val width = document.width
        val height = document.height
        canvas.snapshot { engine ->
            val pixels = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, pixels)
            saver.execute {
                val saved = runCatching { store.exportPng(name, width, height, pixels) }.getOrDefault(false)
                runOnUiThread {
                    val message = if (saved) tr("Saved to Pictures/Cappuccino") else tr("Couldn't export this PNG")
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun referencesPanel(): View = column().apply {
        addView(label(tr("References"), 16f))
        val files = store.references(document.projectId)
        val cell = (dp(PANEL_WIDTH) - dp(28) - dp(8)) / 3
        gapped(
            GridLayout(context).apply {
                columnCount = 3
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = rounded(SURFACE_RAISED, 10)
                        contentDescription = tr("Add references")
                        addView(ImageView(context).apply { setImageResource(R.drawable.ic_add) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                        addView(label(tr("Add"), 12f), LinearLayout.LayoutParams(-2, -2))
                        setOnClickListener {
                            startActivityForResult(
                                Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("image/*")
                                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                                PICK_REFERENCES,
                            )
                        }
                    },
                    GridLayout.LayoutParams().apply {
                        width = cell
                        height = cell
                        setMargins(0, 0, dp(4), dp(4))
                    },
                )
                files.forEach { file ->
                    val open = references.containsKey(file.name)
                    addView(
                        FrameLayout(context).apply {
                            background = rounded(if (open) ACCENT else SURFACE, 10)
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            addView(
                                ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    background = rounded(SURFACE_RAISED, 8)
                                    clipToOutline = true
                                    setImageBitmap(decodeReference(file, cell))
                                    contentDescription = if (open) tr("Close reference") else tr("Open reference")
                                    setOnClickListener {
                                        toggleReference(file)
                                        rerender("references", ::referencesPanel)
                                    }
                                    setOnLongClickListener {
                                        confirmDeleteReference(file)
                                        true
                                    }
                                },
                            )
                        },
                        GridLayout.LayoutParams().apply {
                            width = cell
                            height = cell
                            setMargins(0, 0, dp(4), dp(4))
                        },
                    )
                }
            },
        )
        val hint = if (files.isEmpty()) {
            tr("Add images, or share them here from another app.")
        } else {
            tr("Tap to open. Hold to delete. On a reference, tap for tools.")
        }
        gapped(label(hint, 12f, MUTED_TEXT))
    }

    private fun confirmDeleteReference(file: File) {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete this reference?"))
            .setMessage(tr("It's removed from this project and every artwork using it."))
            .setNegativeButton(tr("Keep"), null)
            .setPositiveButton(tr("Delete")) { _, _ ->
                references.remove(file.name)?.dismiss()
                file.delete()
                rememberReferences()
                rerender("references", ::referencesPanel)
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_TIP && resultCode == RESULT_OK && data?.data != null) {
            val bitmap = contentResolver.openInputStream(data.data!!)?.use(BitmapFactory::decodeStream)
            if (bitmap == null) {
                Toast.makeText(this, tr("Couldn't read this image"), Toast.LENGTH_SHORT).show()
                return
            }
            draftTip = tipFrom(bitmap)
            val shape = pendingBrushShape ?: return
            val source = BrushKind(
                editingBrush?.id ?: "draft", pendingBrushName, shape[0], shape[1], shape[2], shape[3],
                shape[4], shape[5], canvas.brushDiameter,
            )
            closePanel()
            showPanel("brushEditor", brushEditor(source, editingBrush != null))
            return
        }
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
        references[file.name]?.let {
            onReferenceClosed(it)
            return
        }
        val bitmap = decodeReference(file, REFERENCE_SIZE)
        if (bitmap == null) {
            Toast.makeText(this, tr("Couldn't open this image"), Toast.LENGTH_SHORT).show()
            return
        }
        val width = fitReferenceWidth(bitmap.width, bitmap.height, canvas.width, canvas.height)
        val offset = dp(32) * references.size
        openReference(ReferenceState(file.name, dp(90) + offset, dp(90) + offset, width), bitmap)
        rememberReferences()
    }

    private fun openReference(state: ReferenceState, bitmap: Bitmap) {
        val window = ReferenceWindow(this, canvas, state, bitmap, this)
        window.show()
        references[state.file] = window
    }

    private fun restoreReferences() {
        if (isDestroyed) return
        val folder = store.references(document.projectId).associateBy { it.name }
        document.references.forEach { state ->
            val bitmap = folder[state.file]?.let { decodeReference(it, REFERENCE_SIZE) }
            if (bitmap != null) openReference(state.copy(), bitmap)
        }
    }

    private fun rememberReferences() {
        document.references = references.values.map { it.state.copy() }
        markUnsaved()
    }

    override fun onReferenceChanged() = rememberReferences()

    override fun onReferenceClosed(reference: ReferenceWindow) {
        references.remove(reference.state.file)
        reference.dismiss()
        rememberReferences()
        rerender("references", ::referencesPanel)
    }

    override fun onReferenceColor(color: Int) {
        setColor(color)
        rememberColor()
    }

    override fun onReferencePalette(reference: ReferenceWindow) {
        val bitmap = reference.bitmap
        saver.execute {
            val small = Bitmap.createScaledBitmap(bitmap, 240, max(1, 240 * bitmap.height / bitmap.width), true)
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val colors = extractPalette(pixels)
            savePalette(tr("Reference colors"), colors)
            runOnUiThread {
                if (colors.isNotEmpty()) Toast.makeText(this, tr("Palette saved"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onReferenceToLayer(reference: ReferenceWindow) {
        if (document.layers.size >= maxLayers) {
            Toast.makeText(this, tr("This canvas can hold %1 layers", maxLayers), Toast.LENGTH_LONG).show()
            return
        }
        val bitmap = reference.bitmap
        val id = document.nextLayerId++
        engineLayers.add(id)
        saver.execute {
            val pageWidth = document.width
            val pageHeight = document.height
            val page = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            val scale = min(pageWidth / bitmap.width.toFloat(), pageHeight / bitmap.height.toFloat())
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = (pageWidth - width) / 2f
            val top = (pageHeight - height) / 2f
            android.graphics.Canvas(page).drawBitmap(
                bitmap, null, RectF(left, top, left + width, top + height),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = ArtworkStore.pixelBuffer(pageWidth, pageHeight)
            page.copyPixelsToBuffer(pixels)
            page.recycle()
            pixels.rewind()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                canvas.act { engine ->
                    NativeEngine.createLayer(engine, id)
                    NativeEngine.writeLayer(engine, id, pixels)
                    NativeEngine.markLayerDirty(engine, id)
                }
                editLayers {
                    val index = document.layers.indexOfFirst { it.id == document.activeLayer } + 1
                    document.layers.add(index, LayerInfo(id, tr("Reference %1", id)))
                    document.activeLayer = id
                }
                refreshLayers()
            }
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
            references.values.forEach { it.refit() }
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closePanel()
        hideHandles()
        transformControls?.dismiss()
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
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
    }
}
