package art.cappuccino.paint

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import java.io.File
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MIN_BRUSH = 4
private const val MAX_BRUSH = 300
private const val KEY_SIZE_STEP = 1.2f
private const val FINISH_NOTE_DELAY_MS = 3500L
private const val WARMUP_NOTE_DELAY_MS = 900L
private const val WARMUP_SIZE = 2048
private const val WARMUP_READ_WIDTH = 512
private const val WARMUP_BAR_DP = 620
private const val ANIMATION_SIDE = 1280
private const val ANIMATION_SECONDS = 6
private const val AUTOSAVE_DELAY_MS = 2500L
private const val STROKE_SAVE_DELAY_MS = 8000L
private const val PEN_NEAR_LIMIT_MS = 30000L
private const val NAP_DELAY_MS = 90_000L
private const val PICK_REFERENCES = 41
private const val REFERENCE_SIZE = 2048
private const val PREFERENCES = "studio"
private const val PANEL_WIDTH = 340
private const val BUBBLE_WIDTH = 308
private const val HIDE_BUTTON_DP = 28
private const val BUBBLE_TAIL_DP = 10

// At full strength, a selection's edge fades over a twentieth of the canvas's longer side.
private const val FEATHER_SHARE = 4000
private const val THUMBNAIL_WIDTH = 72
private const val THUMBNAIL_HEIGHT = 48
private const val LAYER_ROW = 56
private const val LAYER_GAP = 4
private const val RETIRED_LAYERS = 6
private val LEFT_PANELS = setOf("actions", "references", "view", "tools", "sticker")
private const val PICK_TIP = 45
private const val PICK_LAYER_IMAGE = 46
private const val PICK_FLASH_MS = 700L
private const val QUICK_COLORS = 4
private const val BLEND_LIST_WIDTH = 180
private const val PRINT_VIEW = 4
private const val STICKER_SIDE = 1024
private const val STICKER_PREVIEW = 300
private const val STICKER_PREVIEW_STEP = 4
private const val MAX_BORDER = 0.05f
private const val SHADOW_ALPHA = 90
private const val NEW_SITTING_MS = 6L * 60 * 60 * 1000
private const val PEEK_SIZE = 60
private const val PEEK_OVERLAP = 24
private const val PEEK_TILT = 10f
private const val HANDS_WIDTH = 24
private const val HANDS_OVERLAP = 19
private const val HANDS_OVERHANG = 1
private const val HIDE_MS = 480L
private const val HIDE_HOP_DP = 26
private const val REFERENCE_PROFILE_SIZE = 240
private val TEXT_SIZES = listOf(0.03f, 0.05f, 0.09f)
private const val TEXT_FIND_WIDTH = 512
private const val BRUSH_TIP_SIZE = 128

class MainActivity : Activity(), ReferenceHost {
    private lateinit var canvas: CanvasView
    private lateinit var store: ArtworkStore
    private lateinit var document: ArtworkDocument
    private lateinit var companion: CompanionView

    private val handler = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor()
    private val analyzer = Executors.newSingleThreadExecutor { work ->
        Thread { Thread.currentThread().priority = Thread.MIN_PRIORITY; work.run() }
    }
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
    private var progress: String? = null
    private var earlier: ArtSnapshot? = null
    private var warmup: Warmup? = null
    private val warmupStrokes = ArrayList<FloatArray>()
    private val undoneWarmupStrokes = ArrayList<FloatArray>()
    private var checkingWarmup = false
    private var warmupBar: PopupWindow? = null
    private var warmupBarContent: FrameLayout? = null
    private var warmupLayout = WarmupLayout(0)

    /** A line naming where the sun is, when one sun lights the whole exercise. */
    private fun sunLine(exercise: Warmup) = singleSun(exercise.kind, warmupLayout)?.let { tr("The sun is at the %1.", tr(it.name)) }
    private var onionSkin = true
    private var playingFrame = -1
    private var animationBar: PopupWindow? = null
    private var animationContent: FrameLayout? = null
    private val nextFrame = object : Runnable {
        override fun run() {
            if (playingFrame < 0 || isDestroyed) return
            playingFrame = (playingFrame + 1) % max(1, document.layers.size)
            syncLayers(structural = false)
            handler.postDelayed(this, 1000L / document.fps)
        }
    }
    @Volatile private var latestSnapshot: ArtSnapshot? = null
    private var earlierLoaded = false
    private var comparison: String? = null
    private val shapeHandles = ArrayList<PopupWindow>()
    private var recentStrip: LinearLayout? = null
    private var selectionMode = 0
    private var selectionFeather = 0
    private val featherRadius = AtomicInteger()
    private val transformHandles = ArrayList<PopupWindow>()
    private var distorting = false
    private var transformCorners: FloatArray? = null
    private var pickerInks = false
    private var inks: FloatArray? = null
    private var stickerArt: Bitmap? = null
    private var stickerAlpha: ByteArray? = null
    @Volatile private var stickerSmallAlpha: ByteArray? = null
    @Volatile private var stickerRequest = 0
    private val stickerWorker = Executors.newSingleThreadExecutor()
    private var stickerWidth = 0
    private var stickerHeight = 0
    private var stickerBorder = 0.35f
    private var stickerColor = Color.WHITE
    private var stickerShadow = true
    private var stickerImage: ImageView? = null
    private var pickingReferences = false
    private val pickedReferences = LinkedHashSet<String>()
    private var pickingLayers = false
    private val pickedLayers = LinkedHashSet<Int>()
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
    private val endPickFlash = Runnable { hidePickRing() }
    private var hasSelection = false
    private var tipIndex = 0
    private var ideaIndex = 0
    private var matchedFiles: List<File> = emptyList()
    private var matchedScope = ReferenceScope.NEWEST
    private var matchedBoards: Map<BoardPurpose, List<String>> = emptyMap()
    private var referenceFilter = ""
    private var editingMask = false
    private var previewStale = false
    private var everSaved = false
    private var journalSequence = 1
    private var saveWaitStart = 0L
    private val journalWriter = Executors.newSingleThreadExecutor()
    private var filter = FILTERS.first()
    private var filterValues = FloatArray(4)
    private var layersButton: View? = null
    private var symmetryHandle: PopupWindow? = null
    private var liquifyControls: PopupWindow? = null
    private var liquifyRingWindow: PopupWindow? = null
    private var liquifyRing: BrushRing? = null
    private val thumbnails = ReferenceThumbnails()
    private var peek: CompanionView? = null
    private var peekFrame: View? = null
    private var peekWindow: PopupWindow? = null
    private var peekHands: CompanionHands? = null
    private var noteOverlay: NoteOverlay? = null
    private var noteWindow: PopupWindow? = null
    private var peekX = 0
    private var peekY = 0
    private var hideMotion: ValueAnimator? = null
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
    private var toolsButton: ImageButton? = null
    private var brushTab: String? = null
    private var brushFeelOpen = false
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
    private val layerPreviews = HashMap<Int, Bitmap>()
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
        warmup = intent.getStringExtra(EXTRA_WARMUP)?.let { name -> WARMUPS.firstOrNull { it.kind.name == name } }
        warmupLayout = WarmupLayout(intent.getIntExtra(EXTRA_SEED, 0))
        val artworkId = intent.getStringExtra(EXTRA_ARTWORK)
        PendingSaves.await()
        val loaded = artworkId?.let(store::load)
        if (artworkId != null && loaded == null) {
            say(tr("Couldn't open this artwork"), long = true)
            finish()
            return
        }
        val canvasWidth = loaded?.width ?: intent.getIntExtra(EXTRA_WIDTH, DEFAULT_CANVAS_WIDTH)
        val canvasHeight = loaded?.height ?: intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_CANVAS_HEIGHT)
        SlowLog.file = File(filesDir, "slow.log")
        canvas = CanvasView(this, canvasWidth, canvasHeight)
        maxLayers = layerLimit(
            canvasWidth, canvasHeight,
            ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem,
        )
        canvas.pressureGamma = preferences.getFloat("pressureGamma", 0.75f)
        canvas.snapShapes = preferences.getBoolean("snapShapes", true)
        customBrushes.addAll(store.customBrushes())
        setContentView(canvas)
        companion = CompanionView(this).apply {
            look = CompanionLook.valueOf(
                preferences.getString("mascot", null) ?: CompanionLook.PAINT_DROP.name,
            )
            bodyColor = canvas.brushColor
        }
        canvas.onStrokeStart = {
            hideShapeHandles()
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
            markUnsaved(strokeOnly = true)
        }
        canvas.onGestureStart = {
            closePanel()
            hideShapeHandles()
        }
        canvas.onShapeSnapped = ::showShapeHandles
        canvas.onTransformMoved = ::moveTransformHandles
        canvas.onViewChanged = {
            positionHandles()
            noteOverlay?.invalidate()
        }
        canvas.onFillTap = { x, y -> fillAt(x, y) }
        canvas.onStrokeCommitted = { history.record(Edit.Stroke) }
        canvas.onStrokeRecord = { brush, samples -> journalStroke(brush, samples) }
        canvas.onPenNear = { holdSaveForPen() }
        canvas.onUndoGesture = { undoEdit() }
        canvas.onRedoGesture = { redoEdit() }
        canvas.penButtonAction = preferences.getInt("penButton", 0)
        warmup?.let { exercise ->
            canvas.post { if (!isDestroyed) showWarmupBar(exercise) }
            val steps = listOfNotNull(sunLine(exercise), tr(exercise.steps)).joinToString(" ")
            handler.postDelayed({ if (!isDestroyed) say(steps, long = true) }, WARMUP_NOTE_DELAY_MS)
        }
        canvas.onStrokePath = {
            checkPerspective(it)
            if (warmup != null) {
                warmupStrokes += it
                undoneWarmupStrokes.clear()
            }
        }
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
        canvas.onLassoFill = { path, erase -> finishLassoFill(path, erase) }
        canvas.onLiquifyRing = { x, y, radius -> showLiquifyRing(x, y, radius) }
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
                UUID.randomUUID().toString(), intent.getStringExtra(EXTRA_NAME)?.ifEmpty { null } ?: tr("Untitled"),
                project, 1, 2, mutableListOf(first),
                width = width, height = height,
            )
            engineLayers.add(1)
            canvas.act {
                NativeEngine.createLayer(it, 1)
                NativeEngine.setActiveLayer(it, 1)
            }
            warmup?.let { setUpWarmupLayers(it.kind) }
            canvas.acceptsInput = true
            return
        }
        document = loaded
        everSaved = true
        if (loaded.animation) canvas.post { if (!isDestroyed) showAnimationBar() }
        engineLayers.addAll(loaded.layers.map { it.id })
        engineLayers.addAll(loaded.layers.map { it.maskId }.filter { it != 0 })
        pushPerspective()
        pushFigure()
        pushSymmetry()
        saver.execute {
            for (layer in loaded.layers) {
                val pixels = store.readLayerPixels(loaded, layer.id, layer.file)
                val mask = store.readLayerPixels(loaded, layer.maskId, layer.maskFile)
                // Older artworks move to tiles on their next save, all at once.
                val older = listOf(layer.id, layer.maskId).filter { it != 0 && it !in loaded.tiles }
                layer.file?.let { savedFiles[layer.id] = it }
                layer.maskFile?.let { savedFiles[layer.maskId] = it }
                canvas.act { engine ->
                    NativeEngine.createLayer(engine, layer.id)
                    pixels?.let { NativeEngine.writeLayer(engine, layer.id, it) }
                    if (layer.maskId != 0) {
                        NativeEngine.createLayer(engine, layer.maskId)
                        NativeEngine.setLayerMask(engine, layer.id, layer.maskId, layer.maskEnabled)
                        mask?.let { NativeEngine.writeLayer(engine, layer.maskId, it) }
                    }
                    older.forEach { NativeEngine.markLayerDirty(engine, it) }
                }
            }
            if (loaded.layers.any { it.id !in loaded.tiles }) runOnUiThread { markUnsaved() }
            val shares = animationShares()
            canvas.act { engine ->
                loaded.layers.forEach { applyLayerProperties(engine, it, shares[it.id] ?: 1f) }
                NativeEngine.setActiveLayer(engine, loaded.activeLayer)
            }
            val journal = store.journal(id)
            journalSequence = maxOf(loaded.journalSequence, journal.latest()) + 1
            val recovered = runCatching { journal.read(loaded.journalSequence) }.getOrDefault(emptyList())
            if (recovered.isNotEmpty()) recoverStrokes(recovered)
            runOnUiThread {
                syncAlphaLock()
                canvas.acceptsInput = true
                canvas.post { restoreReferences() }
            }
        }
    }

    private fun journalStroke(brush: BrushState, samples: FloatArray) {
        val entry = JournalEntry.Stroke(
            paintTarget(), brush.kind.id, brush.diameter, brush.color, brush.opacity, brush.alphaLock,
            brush.pressureGamma, samples,
        )
        writeJournal(entry)
    }

    private fun writeJournal(entry: JournalEntry) {
        if (warmup != null) return
        val journal = store.journal(document.id)
        val sequence = journalSequence
        journalWriter.execute { runCatching { journal.append(sequence, entry) } }
    }

    /** Redraws strokes that finished after the last save, as they were before a crash. */
    private fun recoverStrokes(entries: List<JournalEntry>) {
        val brushes = BrushKind.entries + customBrushes
        val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        val view = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val active = document.activeLayer
        val known = engineLayers.toSet()
        canvas.act { engine ->
            entries.forEach { entry ->
                when (entry) {
                    is JournalEntry.Stroke -> if (entry.target in known) {
                        val kind = brushes.firstOrNull { it.id == entry.brushId } ?: BrushKind.INK
                        NativeEngine.setActiveLayer(engine, entry.target)
                        beginEngineStroke(
                            engine,
                            BrushState(kind, entry.diameter, entry.color, entry.opacity, entry.alphaLock, entry.pressureGamma),
                        )
                        NativeEngine.drawFront(engine, identity, view, 16, 16, entry.samples, FloatArray(0), true)
                    }
                    is JournalEntry.Fill -> if (entry.target in known) {
                        NativeEngine.setActiveLayer(engine, entry.target)
                        NativeEngine.fillAt(
                            engine, entry.x, entry.y,
                            Color.red(entry.color) / 255f, Color.green(entry.color) / 255f, Color.blue(entry.color) / 255f,
                        )
                    }
                    is JournalEntry.ShapeFill -> if (entry.target in known) {
                        NativeEngine.setActiveLayer(engine, entry.target)
                        NativeEngine.fillPolygon(
                            engine, entry.path,
                            Color.red(entry.color) / 255f, Color.green(entry.color) / 255f, Color.blue(entry.color) / 255f,
                            entry.opacity, entry.erase, entry.alphaLock,
                        )
                    }
                    JournalEntry.Undo -> NativeEngine.undo(engine)
                    JournalEntry.Redo -> NativeEngine.redo(engine)
                }
            }
            NativeEngine.setActiveLayer(engine, active)
        }
        fun target(entry: JournalEntry) = when (entry) {
            is JournalEntry.Stroke -> entry.target
            is JournalEntry.Fill -> entry.target
            is JournalEntry.ShapeFill -> entry.target
            else -> null
        }
        val strokes = entries.filter { target(it) in known }
        runOnUiThread {
            entries.forEach { entry ->
                when (entry) {
                    JournalEntry.Undo -> history.undo()
                    JournalEntry.Redo -> history.redo()
                    else -> if (target(entry) in known) history.record(Edit.Stroke)
                }
            }
            markUnsaved()
            say(tr("Recovered %1 strokes from your last session", strokes.size), long = true)
        }
    }

    /** Waits while the pen is near the screen, so a save doesn't land as the next stroke starts. */
    private fun holdSaveForPen() {
        if (!unsaved || !handler.hasCallbacks(saveLater)) return
        val now = SystemClock.elapsedRealtime()
        if (saveWaitStart == 0L) saveWaitStart = now
        if (now - saveWaitStart > PEN_NEAR_LIMIT_MS) return
        handler.removeCallbacks(saveLater)
        handler.postDelayed(saveLater, AUTOSAVE_DELAY_MS)
    }

    /** [share] fades a layer that's an animation frame other than the current one. */
    private fun applyLayerProperties(engine: Long, layer: LayerInfo, share: Float = 1f) {
        val group = document.groups.firstOrNull { it.id == layer.groupId }
        NativeEngine.setLayerProperties(
            engine, layer.id,
            layer.opacity * (group?.opacity ?: 1f) * share,
            layer.visible && group?.visible != false && share > 0f,
            layer.blend, layer.clip,
        )
        NativeEngine.setLayerMask(engine, layer.id, layer.maskId, layer.maskEnabled)
    }

    /** Strokes wait longer, since the journal already protects them. */
    private fun markUnsaved(strokeOnly: Boolean = false) {
        unsaved = true
        previewStale = true
        handler.removeCallbacks(saveLater)
        handler.postDelayed(saveLater, if (strokeOnly && everSaved) STROKE_SAVE_DELAY_MS else AUTOSAVE_DELAY_MS)
    }

    /** Saves changes. [withPreview] adds the sharp image used on sheets, and [then] runs afterward. */
    private fun save(withPreview: Boolean = false, then: (() -> Unit)? = null) {
        handler.removeCallbacks(saveLater)
        // A warm-up is only practice, so it never reaches the library.
        if (warmup != null) {
            then?.let { saver.execute(it) }
            return
        }
        if (!unsaved && !withPreview) {
            then?.let { saver.execute(it) }
            return
        }
        if (penDown && then == null) {
            handler.postDelayed(saveLater, AUTOSAVE_DELAY_MS)
            return
        }
        if (withPreview) previewStale = false
        saveWaitStart = 0L
        val included = journalSequence++
        PendingSaves.started()
        unsaved = false
        val target = document
        val layers = target.layers.map { it.copy() }
        val active = target.activeLayer
        val nextId = target.nextLayerId
        val groups = target.groups.map { it.copy() }
        val nextGroup = target.nextGroupId
        val referenceSet = referenceChoice()
        if (!interfaceHidden) companion.mood = Mood.THINKING
        canvas.snapshot { engine ->
            val pixels = HashMap<Int, LayerRegion>()
            for (id in layers.flatMap { listOf(it.id, it.maskId) }) {
                if (id == 0) continue
                val changed = NativeEngine.takeLayerDirty(engine, id) ?: continue
                val area = alignToTiles(changed, target.width, target.height)
                val buffer = ArtworkStore.pixelBuffer(area[2] - area[0], area[3] - area[1])
                NativeEngine.readLayerRegion(engine, id, area[0], area[1], area[2], area[3], buffer)
                pixels[id] = LayerRegion(area[0], area[1], area[2] - area[0], area[3] - area[1], buffer)
            }
            val thumbnail = ArtworkStore.pixelBuffer(ArtworkStore.THUMBNAIL_WIDTH, target.thumbnailHeight)
            NativeEngine.readComposite(engine, ArtworkStore.THUMBNAIL_WIDTH, target.thumbnailHeight, thumbnail)
            val preview = if (withPreview) {
                val height = ArtworkStore.previewHeight(target.width, target.height)
                ArtworkStore.pixelBuffer(ArtworkStore.PREVIEW_WIDTH, height).also {
                    NativeEngine.readComposite(engine, ArtworkStore.PREVIEW_WIDTH, height, it)
                }
            } else {
                null
            }
            saver.execute {
                layers.forEach {
                    it.file = savedFiles[it.id]
                    it.maskFile = savedFiles[it.maskId]
                }
                runCatching {
                    store.save(target, ArtworkSnapshot(layers, active, nextId, pixels, thumbnail, groups, nextGroup, preview, included))
                    everSaved = true
                    val journal = store.journal(target.id)
                    journalWriter.execute { journal.clear(included) }
                    store.addTimelapseFrame(target.id, thumbnail, target.thumbnailHeight)
                    layers.forEach { layer ->
                        layer.file?.let { savedFiles[layer.id] = it }
                        layer.maskFile?.let { savedFiles[layer.maskId] = it }
                    }
                }.onFailure {
                    // Those changes never reached disk, so the next save has to include them.
                    canvas.snapshot { engine -> pixels.keys.forEach { NativeEngine.markLayerDirty(engine, it) } }
                    runOnUiThread {
                        unsaved = true
                        say(tr("Couldn't save your artwork"), long = true)
                    }
                }
                PendingSaves.finished()
                then?.invoke()
                val snapshot = java.nio.ByteBuffer.allocateDirect(thumbnail.capacity()).put(thumbnail.duplicate().apply { rewind() })
                analyzer.execute { analyze(snapshot, referenceSet) }
            }
        }
    }

    /** The images the companion compares with, and why those were chosen. */
    private fun referenceChoice(): Pair<List<File>, AspectChoice> {
        val all = store.references(document.projectId)
        val project = document.projectId
        val choice = aspectReferences(
            all.map { it.name }, references.keys, store.referenceNotes(project), store.boardPurposes(project), document.followedBoards.toSet(),
        )
        val byName = all.associateBy { it.name }
        return choice.names.map(byName::getValue) to choice
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

    private fun analyze(thumbnail: java.nio.ByteBuffer, referenceSet: Pair<List<File>, AspectChoice>) {
        val (files, choice) = referenceSet
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
        val now = ArtSnapshot(System.currentTimeMillis(), result.contrast, result.coverage, result.colored)
        latestSnapshot = now
        if (!earlierLoaded && warmup == null) {
            earlierLoaded = true
            store.saveFirstSnapshot(document.id, now)
            val saved = store.snapshot(document.id)
            if (saved == null || now.time - saved.time >= NEW_SITTING_MS) {
                store.saveSnapshot(document.id, now)
                earlier = saved
            }
        }
        val note = earlier?.let { progressNote(it, now) }
        val artProfile = profileImage(pixels, ArtworkStore.THUMBNAIL_WIDTH, document.thumbnailHeight)
        val profiles = referenceProfiles(files)
        val matched = if (profiles.size == files.size) compareWithReferences(artProfile, profiles, choice.aspects) else compareWithReferences(artProfile, profiles)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val changed = result.tips.map { it.text } != report?.tips?.map { it.text } ||
                matched?.summary != referenceMatch?.summary
            report = result
            progress = note
            referenceMatch = matched
            matchedFiles = files
            matchedScope = choice.scope
            matchedBoards = choice.boards
            if (changed && result.tips.isNotEmpty()) unreadTips = true
            result.tips.firstOrNull { it.topic in WARMUP_TOPICS }?.takeIf { warmup == null }?.let {
                preferences.edit().putString("warmupTopic", it.topic).apply()
            }
            if (unreadTips) peek?.mood = Mood.TIP
            companion.mood = if (unreadTips) Mood.TIP else Mood.IDLE
        }
    }

    internal fun setColor(color: Int) {
        canvas.brushColor = color
        companion.bodyColor = color
        peek?.bodyColor = color
        peekHands?.bodyColor = color
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
        canvas.pressureGamma = preferences.getFloat("pressure-${kind.id}", preferences.getFloat("pressureGamma", 0.75f))
        if (kind.isEraser) {
            lastEraser = kind
            canvas.eraserKind = kind
        } else if (!kind.isTool) {
            lastBrush = kind
        }
        sizeBar?.progress = sizeProgress()
        opacityBar?.progress = (settings.opacity * 1000f).roundToInt()
        brushButton?.background = if (kind.isEraser || kind.isTool) null else rounded(SURFACE_RAISED, 18)
        eraserButton?.background = if (kind.isEraser) rounded(SURFACE_RAISED, 18) else null
        markTools()
    }

    private fun markTools() {
        toolsButton?.background = if (hasSelection || canvas.brushKind.isTool) rounded(SURFACE_RAISED, 18) else null
        toolsButton?.setImageResource(
            when {
                canvas.selectionTool == SelectionTool.WAND -> R.drawable.ic_wand
                canvas.selectionTool == SelectionTool.LASSO -> R.drawable.ic_select
                canvas.brushKind === BrushKind.SMUDGE -> R.drawable.ic_smudge
                canvas.brushKind === BrushKind.FILL -> R.drawable.ic_fill
                canvas.brushKind === BrushKind.GRADIENT -> R.drawable.ic_gradient
                canvas.brushKind === BrushKind.LASSO_FILL -> R.drawable.ic_lasso_fill
                else -> R.drawable.ic_select
            },
        )
    }

    private fun swatchBackground(color: Int) = rounded(color, 12).apply {
        setStroke(dp(2), TEXT_COLOR)
    }

    private fun rememberColor() {
        recentColors.remove(canvas.brushColor)
        recentColors.add(0, canvas.brushColor)
        if (recentColors.size > 8) recentColors.removeAt(recentColors.size - 1)
        showRecentColors()
    }

    private fun showRecentColors() {
        val strip = recentStrip ?: return
        strip.visibility = if (recentColors.isEmpty()) View.INVISIBLE else View.VISIBLE
        for (index in 0 until strip.childCount) {
            val dot = strip.getChildAt(index)
            val color = recentColors.getOrNull(index)
            dot.visibility = if (color == null) View.INVISIBLE else View.VISIBLE
            if (color == null) continue
            dot.background = rounded(color, 11).apply { setStroke(dp(1), TEXT_COLOR) }
            dot.contentDescription = tr("Use this color")
            dot.setOnClickListener { setColor(color) }
        }
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
        hideMotion?.removeAllListeners()
        hideMotion?.cancel()
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
            toolsButton = iconButton(R.drawable.ic_select, tr("Tools")) { togglePanel("tools", ::toolsPanel) }
            addView(toolsButton)
        }
        left.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        showPeek(
            dp(12) + left.measuredWidth - dp(PEEK_OVERLAP),
            dp(12) + left.measuredHeight / 2 - dp(PEEK_SIZE) / 2,
        )
        place(left, Gravity.TOP or Gravity.START, dp(12), dp(12))
        showPeekHands(dp(12) + left.measuredWidth - dp(HANDS_OVERLAP), dp(12) - dp(HANDS_OVERHANG), left.measuredHeight + dp(HANDS_OVERHANG) * 2)

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
                if (canvas.brushKind.isEraser || canvas.brushKind.isTool) {
                    closePanel()
                    selectTool(lastBrush)
                } else {
                    togglePanel("brushes", ::brushPanel)
                }
            }
            eraserButton = iconButton(R.drawable.ic_eraser, tr("Eraser")) {
                if (canvas.brushKind.isEraser) {
                    togglePanel("erasers", ::eraserPanel)
                } else {
                    closePanel()
                    selectTool(lastEraser)
                }
            }
            addView(brushButton)
            addView(eraserButton)
            layersButton = iconButton(R.drawable.ic_layers, tr("Layers")) { togglePanel("layers", ::layersPanel) }
            addView(layersButton)
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
        sliders.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val strip = pill(vertical = true).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            repeat(QUICK_COLORS) { addView(View(context), LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(0, dp(2), 0, dp(2)) }) }
        }
        recentStrip = strip
        strip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        place(strip, Gravity.START or Gravity.CENTER_VERTICAL, dp(9), (sliders.measuredHeight + strip.measuredHeight) / 2 + dp(8))
        showRecentColors()
        selectTool(canvas.brushKind)
        markMaskEditing()
        if (preferences.getBoolean("companion", true)) showCompanion()
    }

    /** Shortcuts for a keyboard paired with the tablet. Text fields keep their keys. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentFocus is EditText || canvas.transformMode) return super.onKeyDown(keyCode, event)
        val command = event.isCtrlPressed || event.isMetaPressed
        when {
            command && keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed -> redoEdit()
            command && keyCode == KeyEvent.KEYCODE_Z -> undoEdit()
            command && keyCode == KeyEvent.KEYCODE_Y -> redoEdit()
            command -> return super.onKeyDown(keyCode, event)
            keyCode == KeyEvent.KEYCODE_B -> {
                closePanel()
                selectTool(lastBrush)
            }
            keyCode == KeyEvent.KEYCODE_E -> {
                closePanel()
                selectTool(lastEraser)
            }
            keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> resizeBrush(1f / KEY_SIZE_STEP)
            keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> resizeBrush(KEY_SIZE_STEP)
            keyCode == KeyEvent.KEYCODE_TAB -> canvas.onToggleInterface()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun resizeBrush(factor: Float) {
        canvas.brushDiameter = (canvas.brushDiameter * factor).coerceIn(MIN_BRUSH.toFloat(), MAX_BRUSH.toFloat())
        toolFor(canvas.brushKind).diameter = canvas.brushDiameter
        sizeBar?.progress = sizeProgress()
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
            mood = if (unreadTips) Mood.TIP else Mood.IDLE
            peekShare = 1f
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
        peekX = x
        peekY = y
        peekWindow = PopupWindow(frame, dp(PEEK_SIZE), dp(PEEK_SIZE)).apply {
            isClippingEnabled = false
            isTouchable = hidden
            showAtLocation(canvas, Gravity.NO_GRAVITY, x, y)
        }
        peekWindow?.let(chrome::add)
    }

    /** Holds the toolbar from above it, so this window opens after the toolbar's. */
    private fun showPeekHands(x: Int, y: Int, height: Int) {
        val hands = CompanionHands(this).apply {
            look = companion.look
            bodyColor = canvas.brushColor
            visibility = if (preferences.getBoolean("companion", true)) View.INVISIBLE else View.VISIBLE
        }
        peekHands = hands
        PopupWindow(hands, dp(HANDS_WIDTH), height).apply {
            isTouchable = false
            isClippingEnabled = false
            showAtLocation(canvas, Gravity.NO_GRAVITY, x, y)
            chrome.add(this)
        }
    }

    private fun setCompanionVisible(visible: Boolean) {
        preferences.edit().putBoolean("companion", visible).apply()
        peek?.look = companion.look
        peek?.bodyColor = canvas.brushColor
        peek?.mood = if (unreadTips) Mood.TIP else Mood.IDLE
        peekHands?.look = companion.look
        peekHands?.bodyColor = canvas.brushColor
        peekHands?.visibility = View.INVISIBLE
        peekFrame?.visibility = View.VISIBLE
        peekWindow?.let {
            it.isTouchable = false
            it.update()
        }
        companionWindow?.dismiss()
        companionWindow = null
        closePanel()
        moveCompanion(hiding = !visible) {
            if (visible) {
                peekFrame?.visibility = View.INVISIBLE
                peekWindow?.update(peekX, peekY, -1, -1)
                showCompanion()
            } else {
                peekHands?.visibility = View.VISIBLE
                peekWindow?.let {
                    it.isTouchable = true
                    it.update()
                }
            }
        }
    }

    /**
     * Hops between the companion's spot and the toolbar in the window below the toolbar, so it
     * really goes behind it. Its hands appear only once it has arrived.
     */
    private fun moveCompanion(hiding: Boolean, arrived: () -> Unit) {
        val size = dp(56)
        val restX = preferences.getInt("companionX", dp(12)).coerceIn(0, max(0, canvas.width - size)) - (dp(PEEK_SIZE) - size) / 2
        val restY = preferences.getInt("companionY", canvas.height - size - dp(12))
            .coerceIn(0, max(0, canvas.height - size)) - (dp(PEEK_SIZE) - size) / 2
        hideMotion?.removeAllListeners()
        hideMotion?.cancel()
        hideMotion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HIDE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val share = if (hiding) it.animatedFraction else 1f - it.animatedFraction
                val hop = sin(it.animatedFraction * PI.toFloat()) * dp(HIDE_HOP_DP)
                peekWindow?.update(
                    (restX + (peekX - restX) * share).toInt(),
                    (restY + (peekY - restY) * share - hop).toInt(),
                    -1, -1,
                )
                peek?.peekShare = share
                peek?.rotation = PEEK_TILT * share
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = arrived()
            })
            start()
        }
    }

    /** A small dot inside a larger touch area, so it stays quiet but easy to grab. */
    private fun quietHandle() = android.graphics.drawable.InsetDrawable(
        rounded(Color.WHITE, 7).apply { setStroke(dp(2), ROAST) },
        dp(11),
    )

    private fun hideShapeHandles() {
        shapeHandles.forEach { it.dismiss() }
        shapeHandles.clear()
    }

    /** Two handles that stay on a snapped shape until the next stroke, so it can still be adjusted. */
    private fun showShapeHandles(kind: BrushKind, shape: FloatArray, pressure: Float) {
        hideShapeHandles()
        val size = dp(36)
        var current = shape
        var anchors = shapeAnchors(shape)
        fun place(window: PopupWindow, end: Int) {
            val spot = canvas.canvasToScreen(anchors[end], anchors[end + 1])
            window.update(spot[0].toInt() - size / 2, spot[1].toInt() - size / 2, -1, -1)
        }
        listOf(0, 2).forEach { end ->
            val handle = View(this).apply {
                background = quietHandle()
                contentDescription = tr("Adjust shape")
            }
            val window = PopupWindow(handle, size, size).apply { isClippingEnabled = false }
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val point = canvas.screenToCanvas(event.rawX, event.rawY)
                        anchors = anchors.copyOf().also {
                            it[end] = point[0]
                            it[end + 1] = point[1]
                        }
                        place(window, end)
                    }
                    MotionEvent.ACTION_UP -> {
                        current = reshapeShape(current, shapeAnchors(current), anchors)
                        undoEdit()
                        canvas.redrawShape(kind, current, pressure)
                    }
                }
                true
            }
            val spot = canvas.canvasToScreen(anchors[end], anchors[end + 1])
            window.showAtLocation(canvas, Gravity.NO_GRAVITY, spot[0].toInt() - size / 2, spot[1].toInt() - size / 2)
            shapeHandles.add(window)
        }
    }

    private fun fadeChrome(alpha: Float) {
        if (interfaceHidden && alpha > 0f) return
        companion.still = alpha == 0f
        peek?.still = alpha == 0f
        (chrome + listOfNotNull(companionWindow)).forEach {
            it.contentView.animate().alpha(alpha).setDuration(120).start()
        }
    }

    /** The overlay exists only while a note is open, so drawing never pays for it. */
    private fun showNoteMarks(marks: List<NoteMark>) {
        if (marks.isEmpty()) {
            noteWindow?.dismiss()
            noteWindow = null
            noteOverlay = null
            return
        }
        val overlay = noteOverlay ?: NoteOverlay(this, document.width, document.height, canvas::canvasToScreen).also {
            noteOverlay = it
            noteWindow = PopupWindow(it, canvas.width, canvas.height).apply {
                isTouchable = false
                isClippingEnabled = false
                showAtLocation(canvas, Gravity.NO_GRAVITY, 0, 0)
            }
        }
        overlay.show(marks)
    }

    private fun currentTip(): Tip? {
        val tips = companionTips()
        return tips.getOrNull(tipIndex.mod(max(tips.size, 1)))
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
        showNoteMarks(
            when {
                key != "companion" -> emptyList()
                warmup != null && warmupBubble != null -> lastWarmupMarks
                else -> currentTip()?.marks.orEmpty()
            },
        )
        val window = PopupWindow(body, panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isClippingEnabled = true
        }
        val leftSide = key in LEFT_PANELS
        val side = if (leftSide) Gravity.START else Gravity.END
        window.height = panelHeight(body, panelWidth)
        if (key == "companion") {
            // The bubble grows a tail toward the companion, so the note visibly comes from it.
            val tail = dp(BUBBLE_TAIL_DP)
            val spot = bubbleSpot(panelWidth + tail, window.height)
            val tailLeft = spot[0] > companionX
            val tailY = companionY + dp(28) - spot[1].toFloat()
            body.background = SpeechBubble(SURFACE, dp(16).toFloat(), tail.toFloat(), tailLeft, tailY)
            body.setPadding(padding + if (tailLeft) tail else 0, padding, padding + if (tailLeft) 0 else tail, padding)
            window.width = panelWidth + tail
            window.showAtLocation(canvas, Gravity.NO_GRAVITY, spot[0], spot[1])
        } else {
            window.showAtLocation(canvas, Gravity.TOP or side, dp(12), dp(68))
        }
        panel = window
        panelKey = key
        if (key == "view") showViewHandles()
    }

    private fun panelHeight(body: View, width: Int): Int {
        body.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        return minOf(dp(520), canvas.height - dp(84), body.measuredHeight)
    }

    private fun showViewHandles() {
        showHandles()
        showFigureHandles()
        showSymmetryHandle()
    }

    /** Places the bubble beside the companion, on whichever side has room. */
    private fun bubbleSpot(width: Int, height: Int): IntArray {
        val size = dp(56)
        val gap = dp(2)
        val right = companionX + size + gap
        val x = if (right + width <= canvas.width) right else max(0, companionX - gap - width)
        val y = (companionY + size / 2 - height / 2).coerceIn(0, max(0, canvas.height - height))
        return intArrayOf(x, y)
    }

    private fun closePanel() {
        hideHandles()
        if (panelKey == "adjust") canvas.act { NativeEngine.setAdjustment(it, false, FloatArray(6)) }
        if (panelKey == "filter") canvas.act { NativeEngine.setFilter(it, false, 0, FloatArray(4), FloatArray(3)) }
        if (panelKey == "color") rememberColor()
        panel?.dismiss()
        panel = null
        panelKey = ""
        showNoteMarks(emptyList())
    }

    private fun mutedNotes() = preferences.getStringSet("mutedNotes", emptySet()).orEmpty()

    private fun mutedTopics(): Map<String, Int> =
        preferences.getString("mutedTopics", "").orEmpty().split('|').filter { it.contains('=') }
            .associate { it.substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: 0) }

    private fun muteNote(tip: Tip) {
        val topics = mutedTopics().toMutableMap()
        topics[tip.topic] = (topics[tip.topic] ?: 0) + 1
        preferences.edit()
            .putStringSet("mutedNotes", mutedNotes() + tip.text)
            .putString("mutedTopics", topics.entries.joinToString("|") { "${it.key}=${it.value}" })
            .apply()
        companionSection = 0
        rerender("companion", ::companionPanel)
    }

    private fun compareVariations(family: List<ArtworkInfo>) {
        saver.execute {
            val readable = family.mapNotNull { art ->
                val bitmap = BitmapFactory.decodeFile(store.thumbnail(art.id).path) ?: return@mapNotNull null
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                art to analyzeArtwork(pixels, bitmap.width, bitmap.height)
            }
            val best = readable.getOrNull(clearestAtASmallSize(readable.map { it.second }))?.first
            runOnUiThread {
                comparison = best?.let { tr("%1 reads clearest at a small size. Its lights and darks separate the most.", it.name) }
                companionSection = 6
                rerender("companion", ::companionPanel)
            }
        }
    }

    private fun companionTips(): List<Tip> = withoutMuted(allTips(), mutedNotes(), mutedTopics())

    private fun allTips(): List<Tip> = listOfNotNull(
        progress?.let { Tip("Progress", it) },
        referenceMatch?.let {
            Tip(
                "Your references",
                listOfNotNull(
                    it.summary, it.advice,
                    matchedBoards[it.weakest]?.firstOrNull()?.let { board -> tr("From your %1 references.", tr(board)) },
                ).joinToString(" "),
                it.why,
                if (it.marksCanvas) {
                    listOf(NoteMark(tr("Furthest from your reference"), MarkShape.RING, it.farthest.first, it.farthest.second, 0.07f))
                } else {
                    emptyList()
                },
                NoteAction.OPEN_REFERENCE,
            )
        },
        perspectiveLog.summary()?.let {
            Tip(
                "Perspective", it, tr("A straight line counts as drifted when it's over 4° from the guide."),
                perspectiveLog.driftedLine?.let { line ->
                    listOf(
                        NoteMark(
                            tr("This line drifts"), MarkShape.LINE,
                            line[0] / document.width, line[1] / document.height,
                            line[2] / document.width, line[3] / document.height,
                        ),
                    )
                }.orEmpty(),
                NoteAction.OPEN_GUIDES,
            )
        },
    ) + report?.tips.orEmpty()

    private fun companionPanel(): View = FrameLayout(this).apply {
        addView(companionNote())
        addView(
            iconButton(R.drawable.ic_hidden, tr("Hide companion")) { setCompanionVisible(false) }.apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                tooltipText = contentDescription
            },
            FrameLayout.LayoutParams(dp(HIDE_BUTTON_DP), dp(HIDE_BUTTON_DP), Gravity.TOP or Gravity.END),
        )
    }

    private fun companionNote(): View = warmupFeedbackNote() ?: column().apply {
        // Keeps the first lines clear of the hide button in the corner.
        fun beside(view: View) = view.apply { setPaddingRelative(0, 0, dp(HIDE_BUTTON_DP), 0) }
        val tips = companionTips()
        val tip = tips.getOrNull(tipIndex.mod(max(tips.size, 1)))
        if (tip == null) {
            addView(beside(label(tr("Draw a little more, then pause. I'll have a note for you."), 14f)))
        } else {
            addView(beside(label(tr(tip.topic), 13f, MUTED_TEXT)))
            addView(beside(label(tip.text, 14f)))
            noteChip(tip)?.let { gapped(wrapRow(listOf(it))) }
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
                                    flashPickRing(
                                        Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.85f)),
                                        companionX + dp(28).toFloat(),
                                        companionY.toFloat(),
                                    )
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
            gapped(
                label(
                    when (matchedScope) {
                        ReferenceScope.OPEN -> tr("Compared with your open references")
                        ReferenceScope.BOARDS -> tr("Compared with: %1", matchedBoards.values.flatten().distinct().joinToString(", ") { tr(it) })
                        ReferenceScope.STARRED -> tr("Compared with your starred references")
                        ReferenceScope.NEWEST -> tr("Compared with your %1 newest references", matchedFiles.size)
                    },
                    12f, MUTED_TEXT,
                ),
            )
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
                                    setOnClickListener { spot ->
                                        val place = IntArray(2).also(spot::getLocationOnScreen)
                                        flashPickRing(color, place[0] + spot.width / 2f, place[1].toFloat())
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
        if (companionSection == 6) comparison?.let { gapped(label(it, 14f)) }
        if (companionSection == 2) {
            val family = store.variations(document.id)
            gapped(
                wrapRow(
                    listOfNotNull(
                        tip?.let { note -> smallChip(tr("Not useful")) { muteNote(note) } },
                        if (family.size > 1) smallChip(tr("Compare")) { compareVariations(family) } else null,
                    ) +
                        (
                            if (mutedNotes().isEmpty()) {
                                emptyList()
                            } else {
                                listOf(
                                    smallChip(tr("Bring notes back")) {
                                        preferences.edit().remove("mutedNotes").remove("mutedTopics").apply()
                                        rerender("companion", ::companionPanel)
                                    },
                                )
                            }
                        ),
                ),
            )
            gapped(
                LinearLayout(context).apply {
                    CompanionLook.entries.forEach { look ->
                        addView(
                            FrameLayout(context).apply {
                                if (companion.look == look) background = rounded(SURFACE_RAISED, 12)
                                contentDescription = tr(look.label)
                                setOnClickListener { chooseCompanion(look) }
                                val preview = CompanionView(context).also {
                                    it.look = look
                                    it.still = true
                                    it.bodyColor = canvas.brushColor
                                    it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                }
                                addView(preview, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
                            },
                            LinearLayout.LayoutParams(0, dp(48), 1f),
                        )
                    }
                },
            )
        }
    }

    /** One tap from a note to the tool that helps with it. */
    private fun noteChip(tip: Tip): View? {
        fun toggle(active: Boolean, on: String, off: String, change: () -> Unit) = smallChip(if (active) off else on, true) {
            change()
            canvas.post { applyViewOptions() }
            rerender("companion", ::companionPanel)
        }
        fun open(name: String, run: () -> Unit) = smallChip(name, true) {
            closePanel()
            run()
        }
        return when (tip.action) {
            NoteAction.SEE_VALUES -> toggle(viewMode == 1, tr("See in gray"), tr("Back to color")) {
                viewMode = if (viewMode == 1) 0 else 1
            }
            NoteAction.CHECK_SHAPES -> toggle(viewMode == 2, tr("See big shapes"), tr("Back to color")) {
                viewMode = if (viewMode == 2) 0 else 2
            }
            NoteAction.SHOW_THIRDS -> toggle(guide == 1, tr("Show thirds"), tr("Hide thirds")) {
                guide = if (guide == 1) 0 else 1
            }
            NoteAction.OPEN_COLORS -> open(tr("Open colors")) {
                colorBeforePanel = canvas.brushColor
                showPanel("color", colorPanel())
            }
            NoteAction.OPEN_GUIDES -> open(tr("Open guides")) { showPanel("view", viewPanel()) }
            NoteAction.BLOCK_IN -> open(tr("Use Chalk")) {
                BrushKind.entries.firstOrNull { it.id == "chalk" }?.let(::selectTool)
            }
            NoteAction.SHADOW_LAYER -> open(tr("Add a shadow layer")) {
                addLayer()
                activeLayer()?.let { layer -> editLayers(structural = false) { layer.blend = 1 } }
            }
            NoteAction.OPEN_REFERENCE -> {
                val file = referenceMatch?.let { matchedFiles.getOrNull(it.closest) }
                if (file == null || references.containsKey(file.name)) null else open(tr("Open this reference")) { toggleReference(file) }
            }
            null -> null
        }
    }

    private fun chooseCompanion(look: CompanionLook) {
        companion.look = look
        preferences.edit().putString("mascot", look.name).apply()
        peek?.look = look
        peekHands?.look = look
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
                    background = rounded(SURFACE_RAISED, 12).apply { if (selected) setStroke(dp(2), ACCENT) }
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
                    width = dp(150)
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

    private fun brushFamilies(): List<Pair<String, List<BrushKind>>> {
        val favorites = favoriteIds()
        val all = BrushKind.BRUSH_FAMILIES.flatMap { it.second } + customBrushes
        return buildList {
            if (favorites.isNotEmpty()) add("★" to all.filter { it.id in favorites })
            BrushKind.BRUSH_FAMILIES.forEach { (name, kinds) -> add(tr(name) to kinds) }
            if (customBrushes.isNotEmpty()) add(tr("Mine") to customBrushes.toList())
        }
    }

    private fun brushPanel(): View = column().apply {
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(tr("Brushes"), 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_add, tr("New brush")) { openBrushEditor(null) })
            },
        )
        val active = canvas.brushKind
        val families = brushFamilies()
        val family = families.firstOrNull { it.first == brushTab }
            ?: families.firstOrNull { active in it.second }
            ?: families.first()
        addView(
            segmented(families.map { it.first }, families.indexOf(family)) {
                brushTab = families[it].first
                rerender("brushes", ::brushPanel)
            },
        )
        val color = if (Color.luminance(canvas.brushColor) < 0.08f) TEXT_COLOR else canvas.brushColor
        gapped(toolGrid(family.second, color, favoriteIds()))
        gapped(
            label("${if (brushFeelOpen) "▾" else "▸"} ${tr("Brush feel")}", 13f, MUTED_TEXT).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(32)
                setOnClickListener {
                    brushFeelOpen = !brushFeelOpen
                    rerender("brushes", ::brushPanel)
                }
            },
        )
        if (brushFeelOpen) {
            addView(toolSlider(tr("Stabilizer"), (toolFor(active).stabilizer * 100).roundToInt()) {
                toolFor(active).stabilizer = it / 100f
                canvas.stabilizerStrength = it / 100f
            })
            val gamma = ((canvas.pressureGamma - 0.4f) / 1.2f * 100).roundToInt()
            addView(toolSlider(tr("Pressure: soft to firm"), gamma) {
                canvas.pressureGamma = 0.4f + it / 100f * 1.2f
                preferences.edit().putFloat("pressure-${active.id}", canvas.pressureGamma).apply()
            })
            gapped(label(tr("Snap shapes"), 12f, MUTED_TEXT))
            addView(
                segmented(listOf(tr("Off"), tr("On")), if (canvas.snapShapes) 1 else 0) {
                    canvas.snapShapes = it == 1
                    preferences.edit().putBoolean("snapShapes", canvas.snapShapes).apply()
                    rerender("brushes", ::brushPanel)
                },
            )
            gapped(label(tr("Hold at the end of a stroke for a clean line, ellipse, or box."), 12f, MUTED_TEXT))
            gapped(label(tr("Hold a brush to add it to ★."), 12f, MUTED_TEXT))
            if (active.custom) {
                gapped(
                    wrapRow(
                        listOf(
                            smallChip(tr("Edit")) { openBrushEditor(active) },
                            smallChip(tr("Delete")) { deleteBrush(active) },
                        ),
                    ),
                )
            }
        }
    }

    private fun LinearLayout.addTool(kind: BrushKind, icon: Int, detail: String, index: Int) {
        addView(
            toolCard(icon, tr(kind.label), detail, canvas.brushKind === kind) {
                canvas.selectionTool = SelectionTool.NONE
                selectTool(kind)
                closePanel()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) },
        )
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
        scatter = shape.getOrElse(6) { 0f }, sizeJitter = shape.getOrElse(7) { 0f },
        angleJitter = shape.getOrElse(8) { 0f }, shadeJitter = shape.getOrElse(9) { 0f },
        hueJitter = shape.getOrElse(10) { 0f }, strokeJitter = shape.getOrElse(11) { 0f },
    )

    private fun brushEditor(source: BrushKind, editing: Boolean): View = column().apply {
        addView(label(if (editing) tr("Edit brush") else tr("New brush"), 16f))
        val shape = floatArrayOf(
            source.minScale, source.spacing, source.flow, source.pressureFlow, source.hardness, source.grain,
            source.scatter, source.sizeJitter, source.angleJitter, source.shadeJitter,
            source.hueJitter, source.strokeJitter,
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
            Triple(tr("Scatter"), 6, 2f),
            Triple(tr("Size variation"), 7, 1f),
            Triple(tr("Angle variation"), 8, 1f),
            Triple(tr("Shade variation"), 9, 1f),
            Triple(tr("Hue variation"), 10, 1f),
            Triple(tr("Color per stroke"), 11, 1f),
        ).forEach { (title, index, range) ->
            addView(toolSlider(title, (shape[index] / range * 100).roundToInt()) {
                shape[index] = maxOf(if (index >= 6) 0f else 0.02f, it / 100f * range)
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
                showAtLocation(canvas, Gravity.NO_GRAVITY, x.toInt() - size / 2, max(0, y.toInt() - size - dp(24)))
            }
        }
        ring.picked = color
        pickRing?.update(x.toInt() - size / 2, max(0, y.toInt() - size - dp(24)), -1, -1)
    }

    /** Shows the ring for a moment when a color comes from a tap, not a held pick. */
    private fun flashPickRing(color: Int, x: Float, y: Float) {
        showPickRing(color, x, y)
        setColor(color)
        handler.removeCallbacks(endPickFlash)
        handler.postDelayed(endPickFlash, PICK_FLASH_MS)
    }

    internal val pickRingShowing get() = pickRing != null
    internal val stickerReady get() = stickerAlpha != null
    internal fun topLayer() = document.layers.lastOrNull()
    internal fun deleteLayerBelowForTest() {
        document.layers.firstOrNull { !it.sticker }?.let {
            editLayers(structural = false) { it.visible = false }
        }
    }
    internal val companionLook get() = companion.look

    private fun hidePickRing() {
        handler.removeCallbacks(endPickFlash)
        pickView?.let {
            setColor(it.picked)
            rememberColor()
        }
        pickRing?.dismiss()
        pickRing = null
        pickView = null
    }

    private fun toolsPanel(): View = column().apply {
        addView(label(tr("Tools"), 16f))
        gapped(
            LinearLayout(context).apply {
                listOf(
                    Triple(BrushKind.SMUDGE, R.drawable.ic_smudge, tr("Blend colors")),
                    Triple(BrushKind.FILL, R.drawable.ic_fill, tr("Fill an area")),
                ).forEachIndexed { index, (kind, icon, detail) -> addTool(kind, icon, detail, index) }
            },
        )
        gapped(
            LinearLayout(context).apply {
                listOf(
                    Triple(BrushKind.GRADIENT, R.drawable.ic_gradient, tr("Fade a color")),
                    Triple(BrushKind.LASSO_FILL, R.drawable.ic_lasso_fill, tr("Fill a shape you draw")),
                ).forEachIndexed { index, (kind, icon, detail) -> addTool(kind, icon, detail, index) }
            },
        )
        gapped(label(tr("Selection"), 13f, MUTED_TEXT))
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
        if (!hasSelection) return@apply
        gapped(
            segmented(listOf(tr("New"), tr("Add"), tr("Remove")), selectionMode) {
                selectionMode = it
                rerender("tools", ::toolsPanel)
            },
        )
        gapped(
            toolSlider(tr("Soft edge"), selectionFeather) { value ->
                selectionFeather = value
                featherSelection(value * max(document.width, document.height) / FEATHER_SHARE)
            },
        )
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

    /** Fades the selection's edge over about twice [radius] canvas pixels. */
    internal fun featherSelection(radius: Int) {
        featherRadius.set(radius)
        canvas.act { NativeEngine.setSelectionFeather(it, featherRadius.get()) }
    }

    private fun startSelection(tool: SelectionTool, hint: String) {
        canvas.selectionTool = tool
        markTools()
        closePanel()
        say(hint)
    }

    private fun markSelection(active: Boolean) {
        hasSelection = active
        if (!active) selectionMode = 0
        markTools()
    }

    internal fun clearSelection() {
        canvas.act { NativeEngine.clearSelection(it) }
        markSelection(false)
        closePanel()
    }

    private fun hideLasso() {
        lassoWindow?.dismiss()
        lassoWindow = null
        lassoOverlay.clear()
    }

    private fun finishLassoFill(path: FloatArray, erase: Boolean) {
        hideLasso()
        val color = canvas.brushColor
        val opacity = canvas.brushOpacity
        val lock = canvas.alphaLock
        val target = paintTarget()
        canvas.act { engine ->
            val changed = NativeEngine.fillPolygon(
                engine, path, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f,
                opacity, erase, lock,
            )
            if (changed) {
                runOnUiThread {
                    history.record(Edit.Stroke)
                    writeJournal(JournalEntry.ShapeFill(target, color, opacity, erase, lock, path))
                    markUnsaved()
                }
            }
        }
    }

    internal fun finishLasso(path: FloatArray) {
        canvas.selectionTool = SelectionTool.NONE
        hideLasso()
        val mode = selectionMode
        canvas.act { NativeEngine.setSelectionPolygon(it, path, mode) }
        markSelection(if (mode == 0) path.size >= 6 else hasSelection)
    }

    private fun finishWand(x: Int, y: Int) {
        canvas.selectionTool = SelectionTool.NONE
        val mode = selectionMode
        canvas.act { NativeEngine.selectColorAt(it, x, y, 40, mode) }
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
        if (layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
            return
        }
        setEditingMask(false)
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

    private fun filterPanel(): View = column().apply {
        addView(label(tr("Filters"), 16f))
        gapped(wrapRow(FILTERS.map { kind -> smallChip(tr(kind.label), kind === filter) { chooseFilter(kind) } }))
        filter.sliders.forEachIndexed { index, slider ->
            val span = slider.high - slider.low
            addView(toolSlider(tr(slider.label), ((filterValues[index] - slider.low) / span * 100).roundToInt()) {
                filterValues[index] = slider.low + span * it / 100f
                pushFilter()
            })
        }
        if (filter.usesColor) gapped(label(tr("Uses your current color."), 12f, MUTED_TEXT))
        gapped(
            wrapRow(
                listOf(
                    smallChip(tr("Cancel")) { closePanel() },
                    smallChip(tr("Apply"), true) {
                        panelKey = ""
                        panel?.dismiss()
                        commitFilter()
                    },
                ),
            ),
        )
    }

    private fun chooseFilter(kind: FilterKind) {
        filter = kind
        filterValues = FloatArray(4).also { values -> kind.sliders.forEachIndexed { i, s -> values[i] = s.start } }
        pushFilter()
        rerender("filter", ::filterPanel)
    }

    private fun openFilters() {
        setEditingMask(false)
        closePanel()
        chooseFilter(filter)
        showPanel("filter", filterPanel())
    }

    private fun pushFilter() {
        val id = filter.id
        val values = filterValues.copyOf()
        val color = canvas.brushColor
        val rgb = floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        canvas.act { NativeEngine.setFilter(it, true, id, values, rgb) }
    }

    private fun commitFilter() {
        pushFilter()
        canvas.act { NativeEngine.commitFilter(it) }
        history.record(Edit.Stroke)
        markUnsaved()
    }

    internal fun applyFilter(kind: FilterKind, vararg values: Float) {
        filter = kind
        filterValues = FloatArray(4).also { values.copyInto(it) }
        commitFilter()
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

    private fun askTimelapseLength() = choiceSheet(
        tr("Time-lapse length"),
        listOf(15, 30, 60).map { seconds -> SheetChoice(tr("%1 seconds", seconds)) { exportTimelapse(seconds) } } +
            SheetChoice(tr("Every saved moment")) { exportTimelapse(null) },
    )

    private fun exportTimelapse(seconds: Int?) {
        val frames = store.timelapseFrames(document.id)
        if (frames.size < 2) {
            say(tr("Draw a little more to build a time-lapse"))
            return
        }
        val name = document.name
        val width = ArtworkStore.THUMBNAIL_WIDTH * 2
        val height = document.thumbnailHeight * 2
        say(tr("Exporting time-lapse"))
        val outro = CompanionView(this).apply {
            look = companion.look
            bodyColor = canvas.brushColor
            still = true
        }
        val signature = tr("Made with Cappuccino")
        saver.execute {
            val saved = runCatching {
                val target = store.exportTimelapseTarget(name)!!
                contentResolver.openFileDescriptor(target, "w")!!.use {
                    TimelapseEncoder.encode(frames, width, height, seconds, it.fileDescriptor, outro, signature)
                }
            }.isSuccess
            runOnUiThread {
                val message = if (saved) tr("Saved to Movies/Cappuccino") else tr("Couldn't export this time-lapse")
                say(message, long = true)
            }
        }
    }

    private fun fillAt(x: Int, y: Int) {
        val color = canvas.brushColor
        val target = paintTarget()
        canvas.act { engine ->
            val changed = NativeEngine.fillAt(
                engine, x, y, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f,
            )
            if (changed) {
                runOnUiThread {
                    history.record(Edit.Stroke)
                    writeJournal(JournalEntry.Fill(target, x, y, color))
                    markUnsaved()
                }
            }
        }
    }

    internal fun finishTransform(keep: Boolean) {
        hideTransformHandles()
        canvas.endTransform(keep)
        if (keep) {
            history.record(Edit.Stroke)
            markUnsaved()
        }
        transformControls?.dismiss()
    }

    internal fun startLiquify() {
        val layer = activeLayer() ?: return
        closePanel()
        setEditingMask(false)
        canvas.liquifyTool = 0
        canvas.act { NativeEngine.beginLiquify(it, layer.id) }
        val ring = BrushRing(this)
        liquifyRing = ring
        liquifyRingWindow = PopupWindow(ring, canvas.width, canvas.height).apply {
            isTouchable = false
            isClippingEnabled = false
            showAtLocation(canvas, Gravity.NO_GRAVITY, 0, 0)
        }
        val holder = FrameLayout(this)
        fun build() {
            holder.removeAllViews()
            holder.addView(
                pill().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    addView(
                        segmented(LIQUIFY_TOOLS.map { tr(it) }, canvas.liquifyTool) {
                            canvas.liquifyTool = it
                            build()
                        },
                        LinearLayout.LayoutParams(dp(280), -2),
                    )
                    addView(
                        SeekBar(context).apply {
                            max = 100
                            progress = (canvas.liquifyStrength * 100).roundToInt()
                            progressTintList = ColorStateList.valueOf(ACCENT)
                            thumbTintList = ColorStateList.valueOf(ACCENT)
                            contentDescription = tr("Strength")
                            onChange { canvas.liquifyStrength = max(it, 5) / 100f }
                        },
                        LinearLayout.LayoutParams(dp(120), -2),
                    )
                    addView(chip(tr("Cancel")) { finishLiquify(keep = false) })
                    addView(
                        chip(tr("Done"), true) { finishLiquify(keep = true) },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                    )
                },
            )
        }
        build()
        liquifyControls = PopupWindow(holder, -2, -2).apply {
            showAtLocation(canvas, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(24))
        }
        say(tr("Push the image with your pen. Your size slider sets the brush."), long = true)
    }

    private fun showLiquifyRing(x: Float, y: Float, radius: Float) {
        liquifyRing?.move(x, y, radius)
    }

    internal fun finishLiquify(keep: Boolean) {
        canvas.liquifyTool = -1
        canvas.act { if (keep) NativeEngine.commitLiquify(it) else NativeEngine.cancelLiquify(it) }
        if (keep) {
            history.record(Edit.Stroke)
            markUnsaved()
        }
        liquifyControls?.dismiss()
        liquifyControls = null
        liquifyRingWindow?.dismiss()
        liquifyRingWindow = null
        liquifyRing = null
    }

    internal fun startTransform() {
        val layer = activeLayer() ?: return
        closePanel()
        setEditingMask(false)
        canvas.beginTransform(layer.id)
        distorting = false
        val controls = pill().apply {
            lateinit var distort: View
            distort = chip(tr("Distort")) {
                distorting = !distorting
                distort.isSelected = distorting
                distort.background = rounded(if (distorting) ACCENT else SURFACE_RAISED, 20)
                say(if (distorting) tr("Drag a corner to bend the layer into perspective.") else tr("Drag a corner to resize."))
            }
            addView(distort)
            addView(chip(tr("Cancel")) { finishTransform(keep = false) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(
                chip(tr("Done"), true) { finishTransform(keep = true) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
            )
        }
        transformControls = PopupWindow(controls, -2, -2).apply {
            showAtLocation(canvas, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(24))
        }
        say(tr("Drag to move. Drag a corner to resize. Pinch to rotate."))
        showTransformHandles(layer.id)
    }

    /** Finds what's drawn on the layer, so its corners can carry handles that work with a pen. */
    private fun showTransformHandles(layer: Int) {
        val width = TEXT_FIND_WIDTH
        val height = max(1, width * document.height / document.width)
        canvas.snapshot { engine ->
            val small = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readLayerThumbnail(engine, layer, width, height, small)
            small.rewind()
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (small.get((y * width + x) * 4 + 3).toInt() and 0xFF <= 8) continue
                    left = min(left, x)
                    right = max(right, x + 1)
                    top = min(top, y)
                    bottom = max(bottom, y + 1)
                }
            }
            if (right < 0) return@snapshot
            val scale = document.width / width.toFloat()
            val corners = floatArrayOf(
                left * scale, top * scale, right * scale, top * scale,
                right * scale, bottom * scale, left * scale, bottom * scale,
            )
            runOnUiThread { if (!isDestroyed && canvas.transformMode) placeTransformHandles(corners) }
        }
    }

    private fun placeTransformHandles(corners: FloatArray) {
        hideTransformHandles()
        transformCorners = corners
        val size = dp(36)
        for (corner in 0 until 4) {
            val handle = View(this).apply {
                background = quietHandle()
                contentDescription = tr("Resize")
            }
            val window = PopupWindow(handle, size, size).apply { isClippingEnabled = false }
            var lastDistance = 0f
            var startX = 0f
            var startY = 0f
            var startSpot = floatArrayOf(0f, 0f)
            handle.setOnTouchListener { _, event ->
                if (distorting) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                            startSpot = canvas.transformedToScreen(corners[corner * 2], corners[corner * 2 + 1])
                        }
                        MotionEvent.ACTION_MOVE -> canvas.moveTransformCorner(
                            corners, corner, startSpot[0] + event.rawX - startX, startSpot[1] + event.rawY - startY,
                        )
                    }
                    return@setOnTouchListener true
                }
                val across = (corner + 2) % 4
                val anchor = canvas.transformedToScreen(corners[across * 2], corners[across * 2 + 1])
                val distance = kotlin.math.hypot(event.rawX - anchor[0], event.rawY - anchor[1])
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lastDistance = distance
                    MotionEvent.ACTION_MOVE -> if (lastDistance > 1f && distance > 1f) {
                        canvas.scaleTransform(distance / lastDistance, anchor[0], anchor[1])
                        lastDistance = distance
                    }
                }
                true
            }
            val spot = canvas.transformedToScreen(corners[corner * 2], corners[corner * 2 + 1])
            window.showAtLocation(canvas, Gravity.NO_GRAVITY, spot[0].toInt() - size / 2, spot[1].toInt() - size / 2)
            transformHandles.add(window)
        }
    }

    /** Moves one corner of the transform by a distance on screen, as dragging it in Distort does. */
    internal fun bendTransform(corner: Int, dx: Float, dy: Float) {
        val corners = transformCorners ?: return
        val spot = canvas.transformedToScreen(corners[corner * 2], corners[corner * 2 + 1])
        canvas.moveTransformCorner(corners, corner, spot[0] + dx, spot[1] + dy)
    }

    private fun moveTransformHandles() {
        val corners = transformCorners ?: return
        val size = dp(36)
        transformHandles.forEachIndexed { corner, window ->
            val spot = canvas.transformedToScreen(corners[corner * 2], corners[corner * 2 + 1])
            window.update(spot[0].toInt() - size / 2, spot[1].toInt() - size / 2, -1, -1)
        }
    }

    private fun hideTransformHandles() {
        transformHandles.forEach { it.dismiss() }
        transformHandles.clear()
        transformCorners = null
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
        val window = panel
        val body = window?.contentView as? ScrollView
        // The note's marks sit under its bubble, so that window still opens again above them.
        if (window == null || body == null || !window.isShowing || key == "companion") {
            window?.dismiss()
            showPanel(key, content())
            return
        }
        // Swaps the content inside the open window, so the panel doesn't blink or lose its scroll.
        val scroll = body.scrollY
        val next = content()
        body.removeAllViews()
        body.addView(next)
        val height = panelHeight(body, window.width)
        next.layout(body.paddingLeft, body.paddingTop, body.paddingLeft + next.measuredWidth, body.paddingTop + next.measuredHeight)
        body.scrollTo(0, scroll)
        window.update(window.width, height)
        if (key == "view") showViewHandles()
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
                    segmented(listOf("RGB", "CMYK"), if (pickerInks) 1 else 0) {
                        pickerInks = it == 1
                        rerender("color", ::colorPanel)
                    },
                )
                fun show(color: Int) {
                    header.findViewWithTag<View>("currentColor")?.background = swatchBackground(color)
                    header.findViewWithTag<android.widget.TextView>("hex")?.text = hex(color)
                    header.findViewWithTag<View>("printNote")?.visibility = if (printsDuller(color)) View.VISIBLE else View.INVISIBLE
                    header.findViewWithTag<View>("printed")?.background = swatchBackground(printedColor(color))
                }
                if (pickerInks) {
                    val amounts = inks?.takeIf { inkColor(it) == canvas.brushColor } ?: toCmyk(canvas.brushColor)
                    inks = amounts
                    listOf("Cyan", "Magenta", "Yellow", "Black").forEachIndexed { index, name ->
                        addView(toolSlider(tr(name), (amounts[index] * 100).roundToInt()) {
                            amounts[index] = it / 100f
                            val color = inkColor(amounts)
                            setColor(color)
                            show(color)
                        })
                    }
                } else {
                    gapped(
                        ColorPicker(context, canvas.brushColor) {
                            setColor(it)
                            show(it)
                        }.apply { minimumHeight = dp(230) },
                    )
                    gapped(
                        LinearLayout(context).apply {
                            tag = "printNote"
                            gravity = Gravity.CENTER_VERTICAL
                            addView(View(context).apply { tag = "printed" }, LinearLayout.LayoutParams(dp(28), dp(22)))
                            addView(
                                label(tr("Prints duller"), 12f, MUTED_TEXT),
                                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
                            )
                            addView(
                                smallChip(tr("Use this")) {
                                    setColor(printedColor(canvas.brushColor))
                                    rerender("color", ::colorPanel)
                                },
                            )
                        },
                    )
                    show(canvas.brushColor)
                }
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
                            say(tr("Open a reference first"))
                        } else {
                            onReferencePalette(reference)
                        }
                    },
                            smallChip(tr("Save recent")) {
                                if (recentColors.size < 2) {
                                    say(tr("Use at least two colors first"))
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

    private fun confirmDeletePalette(palette: Palette) = confirmSheet(tr("Delete %1?", tr(palette.name)), null) {
        store.deletePalette(palette.id)
        rerender("color", ::colorPanel)
    }

    private fun savePalette(name: String, colors: List<Int>) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (colors.isEmpty()) {
                say(tr("There aren't any colors to collect yet"))
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
        addView(segmented(listOf(tr("Color"), tr("Gray"), tr("2 values"), tr("3 values"), tr("Print")), viewMode) {
            viewMode = it
            applyViewOptions()
            rerender("view", ::viewPanel)
        })
        if (viewMode == PRINT_VIEW) gapped(label(tr("A close guide to CMYK print. Real results depend on the printer and paper."), 12f, MUTED_TEXT))
        gapped(label(tr("Composition"), 12f, MUTED_TEXT))
        gapped(
            wrapRow(
                GUIDES.mapIndexed { index, name ->
                    smallChip(if (name == "None") tr("Off") else tr(name), guide == index) {
                        guide = index
                        applyViewOptions()
                    }
                },
            ),
        )
        gapped(label(tr("Perspective"), 12f, MUTED_TEXT))
        addView(segmented(listOf(tr("Off"), tr("1 point"), tr("2 points"), tr("3 points")), document.perspective.size / 2) {
            setPerspectiveCount(it)
        })
        gapped(label(tr("Symmetry"), 12f, MUTED_TEXT))
        addView(segmented(listOf(tr("Off"), "↔", "↕", "✛", "✺"), document.symmetry) { setSymmetry(it) })
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
        if (document.perspective.isNotEmpty() || document.figure.isNotEmpty() || document.symmetry != 0) {
            gapped(label(tr("Drag the circles to place your guides."), 12f, MUTED_TEXT))
        }
        gapped(actionRow(R.drawable.ic_flip, tr("Flip canvas"), tr("A fresh look at your drawing")) { canvas.flip() })
        gapped(label(tr("These change your view only, never your artwork."), 12f, MUTED_TEXT))
    }

    internal fun setSymmetry(mode: Int) {
        document.symmetry = mode
        if (document.symmetryCenter.size != 2) {
            document.symmetryCenter = listOf(document.width / 2f, document.height / 2f)
        }
        pushSymmetry()
        markUnsaved()
        if (panelKey == "view") {
            closePanel()
            showPanel("view", viewPanel())
        }
    }

    private fun pushSymmetry() {
        val mode = document.symmetry
        val center = document.symmetryCenter.takeIf { it.size == 2 } ?: listOf(0f, 0f)
        canvas.act { NativeEngine.setSymmetry(it, mode, center[0], center[1]) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSymmetryHandle() {
        if (document.symmetry == 0) return
        val handle = View(this).apply {
            background = rounded(ACCENT, 18).apply { setStroke(dp(3), Color.WHITE) }
            contentDescription = tr("Symmetry center")
        }
        val window = PopupWindow(handle, dp(36), dp(36)).apply { isClippingEnabled = false }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val point = canvas.screenToCanvas(event.rawX, event.rawY)
                    document.symmetryCenter = listOf(point[0], point[1])
                    pushSymmetry()
                    positionHandles()
                }
                MotionEvent.ACTION_UP -> markUnsaved()
            }
            true
        }
        val spot = symmetrySpot()
        window.showAtLocation(canvas, Gravity.NO_GRAVITY, spot[0], spot[1])
        symmetryHandle = window
    }

    private fun symmetrySpot(): IntArray {
        val center = canvas.canvasToScreen(document.symmetryCenter[0], document.symmetryCenter[1])
        return intArrayOf(center[0].toInt() - dp(18), center[1].toInt() - dp(18))
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
        canvas.snapGuides = document.perspective
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
        symmetryHandle?.let { window ->
            val spot = symmetrySpot()
            window.update(spot[0], spot[1], -1, -1)
        }
    }

    private fun hideHandles() {
        perspectiveHandles.forEach { it.dismiss() }
        perspectiveHandles.clear()
        figureHandles.forEach { it.dismiss() }
        figureHandles.clear()
        symmetryHandle?.dismiss()
        symmetryHandle = null
    }

    private fun checkPerspective(path: FloatArray) {
        val drift = strokeDrift(path, document.perspective) ?: return
        perspectiveLog.add(drift, floatArrayOf(path[0], path[1], path[path.size - 2], path[path.size - 1]))
        if (drift > PERSPECTIVE_TOLERANCE) {
            unreadTips = true
            companion.mood = Mood.TIP
        }
    }

    private fun applyViewOptions() {
        val mode = viewMode
        val lines = guide
        val frames = thumbnailFrames(document.width, document.height).flatMap { it.toList() }.toFloatArray()
        canvas.act {
            NativeEngine.setFrames(it, frames)
            NativeEngine.setViewOptions(it, mode, lines)
        }
        rerender("view", ::viewPanel)
    }

    private fun refreshLayers() = rerender("layers", ::layersPanel)

    internal fun setClip(layer: LayerInfo, clip: Boolean) {
        editLayers(structural = false) { layer.clip = clip }
    }

    private fun layerById(id: Int) = document.layers.firstOrNull { it.id == id }

    private fun captureLayers() = LayerState(document.layers, document.activeLayer, document.groups)

    private fun syncAlphaLock() {
        canvas.alphaLock = activeLayer()?.alphaLock == true && !editingMask
    }

    private val layerSlots get() = document.layers.size + document.layers.count { it.maskId != 0 }

    /** The engine layer that strokes land on: the layer itself, or its mask. */
    private fun paintTarget() = activeLayer()?.maskId?.takeIf { it != 0 && editingMask } ?: document.activeLayer

    private fun markMaskEditing() {
        layersButton?.background = if (editingMask) rounded(ACCENT, 18) else null
    }

    internal fun setEditingMask(editing: Boolean) {
        if (editingMask == editing) return
        editingMask = editing
        val target = paintTarget()
        canvas.act { NativeEngine.setActiveLayer(it, target) }
        syncAlphaLock()
        markMaskEditing()
    }

    /** Adds a mask to the active layer. Any selection stays visible and the rest is hidden. */
    internal fun addMask() {
        val layer = activeLayer() ?: return
        if (layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
            return
        }
        val id = document.nextLayerId++
        val owner = layer.id
        engineLayers.add(id)
        canvas.act {
            NativeEngine.createMask(it, owner, id)
            NativeEngine.clearSelection(it)
        }
        markSelection(false)
        editLayers(structural = false) {
            layer.maskId = id
            layer.maskEnabled = true
        }
        setEditingMask(true)
        say(tr("Paint to hide. Erase to bring it back."))
    }

    internal fun applyMask(layer: LayerInfo) {
        setEditingMask(false)
        val id = layer.id
        canvas.act { NativeEngine.applyMask(it, id) }
        editLayers(structural = false, pixels = true) { layer.maskId = 0 }
    }

    private fun deleteMask(layer: LayerInfo) {
        setEditingMask(false)
        editLayers(structural = false) { layer.maskId = 0 }
    }

    internal fun setMaskEnabled(layer: LayerInfo, enabled: Boolean) {
        if (!enabled) setEditingMask(false)
        editLayers(structural = false) { layer.maskEnabled = enabled }
    }

    /** Sends the document's layers to the engine. Light edits skip the costly active layer copy. */
    private fun syncLayers(structural: Boolean) {
        val layers = document.layers.map { it.copy() }
        val active = paintTarget()
        val shares = animationShares()
        if (document.animation) refreshAnimationBar()
        canvas.act { engine ->
            if (structural) NativeEngine.setLayerOrder(engine, layers.map { it.id }.toIntArray())
            layers.forEach { applyLayerProperties(engine, it, shares[it.id] ?: 1f) }
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
        val current = document.layers.flatMap { listOf(it.id, it.maskId) }.toSet()
        val retired = engineLayers - current
        history.clear()
        engineLayers.removeAll(retired)
        canvas.act { engine -> retired.forEach { NativeEngine.deleteLayer(engine, it) } }
        say(tr("Older undo steps were cleared to free memory"), long = true)
    }

    private fun restoreLayers(state: LayerState) {
        val previous = document.layers.flatMap { listOf(it.id, it.maskId) }.toSet()
        document.layers.clear()
        document.layers.addAll(state.layers.map { it.copy() })
        document.activeLayer = state.activeLayer
        document.groups.clear()
        document.groups.addAll(state.groups.map { it.copy() })
        // Only layers coming back need saving again. Their old files may already be gone.
        val returning = document.layers.flatMap { listOf(it.id, it.maskId) }.filter { it != 0 && it !in previous }
        if (activeLayer()?.maskId == 0) setEditingMask(false)
        canvas.act { engine -> returning.forEach { NativeEngine.markLayerDirty(engine, it) } }
        syncLayers(structural = true)
        refreshLayers()
    }

    private fun undoEdit() {
        if (canvas.liquifyTool >= 0) return
        when (val edit = history.undo()) {
            null -> return
            Edit.Stroke -> {
                canvas.undo()
                writeJournal(JournalEntry.Undo)
                warmupStrokes.removeLastOrNull()?.let(undoneWarmupStrokes::add)
            }
            is Edit.Layers -> {
                if (edit.pixels) canvas.undo()
                restoreLayers(edit.before)
            }
        }
        markUnsaved()
    }

    private fun redoEdit() {
        if (canvas.liquifyTool >= 0) return
        when (val edit = history.redo()) {
            null -> return
            Edit.Stroke -> {
                canvas.redo()
                writeJournal(JournalEntry.Redo)
                undoneWarmupStrokes.removeLastOrNull()?.let(warmupStrokes::add)
            }
            is Edit.Layers -> {
                if (edit.pixels) canvas.redo()
                restoreLayers(edit.after)
            }
        }
        markUnsaved()
    }

    internal val layerCount get() = document.layers.size
    internal val artworkId get() = document.id

    internal fun openActionsAndVariants() {
        showPanel("actions", actionsPanel())
        closePanel()
        showPanel("variants", variantsPanel())
    }

    internal fun exportDesignSheet() = exportSheet()

    internal fun exportThumbnailFrames() = exportFrames()

    internal fun setBrushSize(diameter: Float) {
        canvas.brushDiameter = diameter
    }
    internal fun canvasToScreen(x: Float, y: Float) = canvas.canvasToScreen(x, y)

    /** The most see-through spot in a small copy of the canvas, from 0 to 255. */
    internal fun canvasOpacityForTest(done: (Int) -> Unit) {
        canvas.snapshot { engine ->
            val pixels = ArtworkStore.pixelBuffer(64, 64)
            NativeEngine.readComposite(engine, 64, 64, pixels)
            var lowest = 255
            for (index in 0 until 64 * 64) lowest = min(lowest, pixels.get(index * 4 + 3).toInt() and 0xFF)
            done(lowest)
        }
    }
    internal fun symmetryScreenX() = symmetrySpot()[0] + dp(18).toFloat()
    internal val currentColor get() = canvas.brushColor

    internal fun activeLayer() = document.layers.firstOrNull { it.id == document.activeLayer }

    private fun selectLayer(layer: LayerInfo) {
        editingMask = false
        markMaskEditing()
        document.activeLayer = layer.id
        syncAlphaLock()
        canvas.act { NativeEngine.setActiveLayer(it, layer.id) }
        if (document.animation) syncLayers(structural = false)
        markUnsaved()
    }

    /** Puts the exercise's guide on the bottom layer and a clear practice layer above it. */
    private fun setUpWarmupLayers(kind: WarmupKind) {
        val guide = drawWarmupGuide(kind, document.width, document.height, warmupLayout)
        val pixels = ArtworkStore.pixelBuffer(document.width, document.height)
        guide.copyPixelsToBuffer(pixels)
        guide.recycle()
        pixels.rewind()
        document.layers[0].name = tr("Guide")
        val id = document.nextLayerId++
        engineLayers.add(id)
        document.layers.add(LayerInfo(id, tr("Practice")))
        document.activeLayer = id
        canvas.act { engine ->
            NativeEngine.writeLayer(engine, 1, pixels)
            NativeEngine.markLayerDirty(engine, 1)
            NativeEngine.createLayer(engine, id)
            NativeEngine.setLayerOrder(engine, intArrayOf(1, id))
            NativeEngine.setActiveLayer(engine, id)
        }
    }

    /** In a warm-up, the companion's bubble tells you how the exercise went. */
    private fun warmupFeedbackNote(): View? {
        val exercise = warmup ?: return null
        val note = warmupBubble ?: return null
        return column().apply {
            addView(label(if (note == tr(exercise.purpose)) tr("Why %1", tr(exercise.title)) else tr(exercise.title), 13f, MUTED_TEXT).apply { setPaddingRelative(0, 0, dp(HIDE_BUTTON_DP), 0) })
            addView(label(note, 14f).apply { setPaddingRelative(0, 0, dp(HIDE_BUTTON_DP), 0) })
        }
    }

    /** A slim bar under the canvas with the exercise, its feedback, and where to go next. */
    private fun showWarmupBar(exercise: Warmup) {
        val holder = warmupBarContent ?: FrameLayout(this).also { content ->
            warmupBarContent = content
            warmupBar = PopupWindow(content, -2, -2).apply {
                // The companion and its bubble live at the bottom left, so the bar keeps to the right.
                showAtLocation(canvas, Gravity.BOTTOM or Gravity.END, dp(20), dp(20))
                chrome.add(this)
            }
        }
        holder.removeAllViews()
        val card = column().apply {
            background = rounded(SURFACE, 20)
            setPadding(dp(16), dp(10), dp(10), dp(10))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        column().apply {
                            addView(label(tr(exercise.title), 14f).apply { isSingleLine = true })
                            sunLine(exercise)?.let { addView(label(it, 12f, MUTED_TEXT).apply { isSingleLine = true }) }
                        },
                    )
                    fun gap() = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
                    addView(
                        smallChip(if (lastWarmupNote == null) tr("How did I do?") else tr("Look again"), true) { warmupFeedbackNow(exercise) },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) },
                    )
                    addView(smallChip(tr("Why?")) { explainWarmup(exercise) }, gap())
                    addView(
                        smallChip(tr("Again")) {
                            startActivity(warmupIntent(this@MainActivity, exercise))
                            finish()
                        },
                        gap(),
                    )
                    addView(
                        smallChip(tr("Another")) {
                            warmupPicker(companion.look, WARMUPS[(WARMUPS.indexOf(exercise) + 1) % WARMUPS.size], exercise) { next ->
                                startActivity(warmupIntent(this@MainActivity, next))
                                finish()
                            }
                        },
                        gap(),
                    )
                    addView(smallChip(tr("Finish")) { finish() }, gap())
                    for (index in 0 until childCount) (getChildAt(index) as? android.widget.TextView)?.isSingleLine = true
                },
            )
        }
        holder.addView(card)
        holder.measure(View.MeasureSpec.makeMeasureSpec(min(canvas.width - dp(32), dp(WARMUP_BAR_DP)), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        warmupBar?.update(holder.measuredWidth, holder.measuredHeight)
    }

    /** Reads what you drew and lets the companion comment on just what the exercise practices. */
    internal fun warmupFeedbackNow(exercise: Warmup) {
        checkingWarmup = true
        closePanel()
        companion.mood = Mood.THINKING
        val strokes = warmupStrokes.map { path ->
            FloatArray(path.size) { index -> if (index % 2 == 0) path[index] / document.width else path[index] / document.height }
        }
        val width = WARMUP_READ_WIDTH
        val height = max(1, width * document.height / document.width)
        val layout = warmupLayout
        canvas.snapshot { engine ->
            val pixels = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, pixels)
            val colors = rgbaToColors(pixels, width * height)
            saver.execute {
                val review = warmupReview(exercise.kind, WarmupWork(strokes, colors, width, height, layout))
                lastWarmupNote = review.text
                warmupBubble = review.text
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    checkingWarmup = false
                    lastWarmupMarks = review.marks
                    companion.mood = Mood.IDLE
                    showWarmupBar(exercise)
                    closePanel()
                    showPanel("companion", companionPanel())
                }
            }
        }
    }

    @Volatile internal var lastWarmupNote: String? = null
    @Volatile private var warmupBubble: String? = null

    /** The companion reminds you what the exercise is for. */
    private fun explainWarmup(exercise: Warmup) {
        warmupBubble = tr(exercise.purpose)
        lastWarmupMarks = emptyList()
        closePanel()
        showPanel("companion", companionPanel())
    }
    private var lastWarmupMarks: List<NoteMark> = emptyList()

    /** How much each layer shows while animating, by id. Empty when the artwork isn't animated. */
    private fun animationShares(): Map<Int, Float> {
        if (!document.animation) return emptyMap()
        val current = if (playingFrame >= 0) playingFrame else document.layers.indexOfFirst { it.id == document.activeLayer }
        val shares = frameShares(document.layers.size, current, onionSkin && playingFrame < 0)
        return document.layers.withIndex().associate { (index, layer) -> layer.id to shares[index] }
    }

    internal fun setAnimation(on: Boolean) {
        stopPlaying()
        document.animation = on
        markUnsaved()
        syncLayers(structural = false)
        if (on) {
            showAnimationBar()
            say(tr("Each layer is a frame now. Add frames with +, and the frames beside this one show faintly."), long = true)
        } else {
            hideAnimationBar()
        }
    }

    private fun hideAnimationBar() {
        animationBar?.let {
            chrome.remove(it)
            it.dismiss()
        }
        animationBar = null
        animationContent = null
    }

    private fun showAnimationBar() {
        hideAnimationBar()
        val holder = FrameLayout(this)
        animationContent = holder
        animationBar = PopupWindow(holder, -2, -2).apply {
            showAtLocation(canvas, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(24))
            chrome.add(this)
        }
        refreshAnimationBar()
    }

    private fun refreshAnimationBar() {
        val holder = animationContent ?: return
        val window = animationBar ?: return
        val current = if (playingFrame >= 0) playingFrame else document.layers.indexOfFirst { it.id == document.activeLayer }
        holder.removeAllViews()
        val bar = pill().apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(chip(if (playingFrame >= 0) tr("Stop") else tr("Play"), playingFrame >= 0) { togglePlaying() })
                val frames = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                document.layers.forEachIndexed { index, layer ->
                    frames.addView(
                        smallChip("${index + 1}", index == current) {
                            stopPlaying()
                            selectLayer(layer)
                            refreshLayers()
                        },
                        LinearLayout.LayoutParams(dp(32), -2).apply { marginStart = dp(4) },
                    )
                }
                frames.addView(smallChip("+") { addLayer() }, LinearLayout.LayoutParams(dp(32), -2).apply { marginStart = dp(4) })
                addView(frames, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
                fun gap() = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
                addView(
                    chip(tr("Onion skin"), onionSkin) {
                        onionSkin = !onionSkin
                        syncLayers(structural = false)
                    },
                    gap(),
                )
                addView(
                    chip(tr("%1 fps", document.fps)) {
                        document.fps = ANIMATION_SPEEDS[(ANIMATION_SPEEDS.indexOf(document.fps) + 1).mod(ANIMATION_SPEEDS.size)]
                        markUnsaved()
                        refreshAnimationBar()
                    },
                    gap(),
                )
                addView(chip(tr("Export")) { exportAnimation() }, gap())
            }
        // On a narrow screen the bar scrolls sideways instead of squeezing its buttons.
        holder.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(bar)
            },
        )
        holder.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        window.update(min(holder.measuredWidth, canvas.width - dp(32)), holder.measuredHeight)
    }

    private fun togglePlaying() {
        if (playingFrame >= 0) {
            stopPlaying()
            syncLayers(structural = false)
            return
        }
        if (document.layers.size < 2) {
            say(tr("Add a second frame to make an animation"))
            return
        }
        playingFrame = document.layers.indexOfFirst { it.id == document.activeLayer }.coerceAtLeast(0)
        handler.post(nextFrame)
    }

    private fun stopPlaying() {
        playingFrame = -1
        handler.removeCallbacks(nextFrame)
    }

    /** Renders each frame alone and plays them a few times in a video. */
    private fun exportAnimation() {
        stopPlaying()
        val layers = document.layers.map { it.copy() }
        if (layers.size < 2) {
            say(tr("Add a second frame to make an animation"))
            return
        }
        val scale = min(1f, ANIMATION_SIDE / max(document.width, document.height).toFloat())
        val width = (document.width * scale).toInt() and 1.inv()
        val height = (document.height * scale).toInt() and 1.inv()
        val fps = document.fps
        val name = document.name
        val restore = animationShares()
        val dir = File(cacheDir, "animation").apply {
            deleteRecursively()
            mkdirs()
        }
        say(tr("Exporting animation"))
        canvas.snapshot { engine ->
            val pixels = ArtworkStore.pixelBuffer(width, height)
            val files = layers.indices.map { frame ->
                val shares = frameShares(layers.size, frame, onion = false)
                layers.forEachIndexed { index, layer -> applyLayerProperties(engine, layer, shares[index]) }
                pixels.rewind()
                NativeEngine.readComposite(engine, width, height, pixels)
                pixels.rewind()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(pixels)
                File(dir, "frame-%04d.png".format(frame)).also { file ->
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            layers.forEach { applyLayerProperties(engine, it, restore[it.id] ?: 1f) }
            saver.execute {
                val loops = max(1, (ANIMATION_SECONDS * fps + files.size - 1) / files.size)
                val saved = runCatching {
                    val target = store.exportVideoTarget(tr("%1 animation", name))!!
                    contentResolver.openFileDescriptor(target, "w")!!.use {
                        TimelapseEncoder.encodeFrames(files, width, height, animationTimeline(files.size, loops), fps, it.fileDescriptor)
                    }
                }.isSuccess
                dir.deleteRecursively()
                runOnUiThread {
                    syncLayers(structural = false)
                    say(if (saved) tr("Saved to Movies/Cappuccino") else tr("Couldn't export this animation"), long = true)
                }
            }
        }
    }

    private fun layersPanel(): View = column().apply {
        layerThumbnails.clear()
        val active = activeLayer()
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(tr("Layers"), 16f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    iconButton(R.drawable.ic_group, tr("Select layers")) {
                        pickingLayers = !pickingLayers
                        pickedLayers.clear()
                        refreshLayers()
                    }.apply { if (pickingLayers) background = rounded(SURFACE_RAISED, 18) },
                )
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
        if (pickingLayers) {
            gapped(wrapRow(layerPickActions()))
            return@apply
        }
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
                    iconTile(R.drawable.ic_liquify, tr("Liquify")) { startLiquify() },
                    iconTile(R.drawable.ic_filter, tr("Filters")) { openFilters() },
                    iconTile(R.drawable.ic_mask, tr("Mask"), editingMask) {
                        if (active.maskId == 0) addMask() else setEditingMask(!editingMask)
                        refreshLayers()
                    },
                    iconTile(R.drawable.ic_adjust, tr("Adjust")) {
                        setEditingMask(false)
                        closePanel()
                        showPanel("adjust", adjustPanel())
                    },
                    iconTile(R.drawable.ic_clip, tr("Clip"), active.clip) {
                        setClip(active, !active.clip)
                        refreshLayers()
                    },
                    iconTile(R.drawable.ic_references, tr("Import")) {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                            PICK_LAYER_IMAGE,
                        )
                    },
                    iconTile(R.drawable.ic_group, if (active.groupId != 0) tr("Ungroup") else tr("Group")) { toggleGroup(active) },
                    iconTile(R.drawable.ic_duplicate, tr("Duplicate")) { duplicateLayer(active) },
                    iconTile(R.drawable.ic_merge, tr("Merge")) { mergeDown(active) },
                    iconTile(R.drawable.ic_trash, tr("Delete")) { deleteLayer(active) },
                ),
            ),
        )
        if (active.maskId != 0) {
            gapped(
                wrapRow(
                    listOf(
                        smallChip(if (active.maskEnabled) tr("Turn mask off") else tr("Turn mask on")) {
                            setMaskEnabled(active, !active.maskEnabled)
                            refreshLayers()
                        },
                        smallChip(tr("Apply mask")) {
                            applyMask(active)
                            refreshLayers()
                        },
                        smallChip(tr("Delete mask")) {
                            deleteMask(active)
                            refreshLayers()
                        },
                    ),
                ),
            )
        }
        if (active.text != null) {
            gapped(wrapRow(listOf(smallChip(tr("Edit text")) { askForText(active) })))
        }
        if (editingMask) gapped(label(tr("Painting on the mask. Tap Mask to return to the layer."), 12f, ACCENT))
    }

    private fun layerPickActions(): List<View> {
        val picked = document.layers.filter { it.id in pickedLayers }
        fun finish() {
            pickingLayers = false
            pickedLayers.clear()
            refreshLayers()
        }
        val actions = arrayListOf<View>(smallChip(tr("Done")) { finish() })
        if (picked.isEmpty()) return actions
        actions += smallChip(tr("Group")) {
            val id = document.nextGroupId++
            editLayers {
                document.groups.add(LayerGroup(id, tr("Group %1", id)))
                picked.forEach { it.groupId = id }
            }
            finish()
        }
        actions += smallChip(tr("Show or hide")) {
            val visible = picked.none { it.visible }
            editLayers(structural = false) { picked.forEach { it.visible = visible } }
            refreshLayers()
        }
        actions += smallChip(tr("Merge")) {
            val places = picked.map(document.layers::indexOf).sorted()
            if (places.last() - places.first() != places.size - 1) {
                say(tr("Pick layers next to each other"))
            } else {
                picked.drop(1).reversed().forEach(::mergeDown)
                finish()
            }
        }
        actions += smallChip(tr("Delete")) {
            if (picked.size >= document.layers.size) {
                say(tr("An artwork needs at least one layer"))
            } else {
                setEditingMask(false)
                editLayers {
                    val first = document.layers.indexOf(picked.first())
                    document.layers.removeAll(picked.toSet())
                    document.activeLayer = document.layers[max(0, first - 1)].id
                }
                finish()
            }
        }
        return actions
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

    internal fun setBlend(layer: LayerInfo, blend: Int) {
        editLayers(structural = false) { layerById(layer.id)?.blend = blend }
        refreshLayers()
    }

    /** Stays in the app's own window, so opening it never takes focus and flashes the screen. */
    private fun chooseBlend(anchor: View, layer: LayerInfo) {
        lateinit var list: PopupWindow
        val rows = column().apply {
            BLEND_MODES.forEachIndexed { index, name ->
                addView(
                    label(tr(name), 14f).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minHeight = dp(40)
                        setPadding(dp(14), 0, dp(14), 0)
                        if (index == layer.blend) background = rounded(SURFACE_RAISED, 10)
                        setOnClickListener {
                            list.dismiss()
                            setBlend(layer, index)
                        }
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
            }
        }
        val body = ScrollView(this).apply {
            background = rounded(SURFACE, 12)
            elevation = dp(8).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(rows)
        }
        list = PopupWindow(body, dp(BLEND_LIST_WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            showAsDropDown(anchor, 0, dp(4), Gravity.END)
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
        layerPreviews[layer.id]?.let(thumbnail::setImageBitmap)
        layerThumbnails[layer.id] = thumbnail
        val handle = ImageView(this).apply {
            setImageResource(R.drawable.ic_handle)
            contentDescription = tr("Reorder %1", layer.name)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val highlighted = if (pickingLayers) layer.id in pickedLayers else selected
            background = rounded(if (highlighted) ACCENT else SURFACE_RAISED, 10)
            setPadding(dp(6), 0, dp(2), 0)
            setOnClickListener {
                if (pickingLayers) {
                    if (!pickedLayers.remove(layer.id)) pickedLayers.add(layer.id)
                } else {
                    selectLayer(layer)
                }
                refreshLayers()
            }
            setOnLongClickListener {
                renameLayer(layer)
                true
            }
            addView(handle, LinearLayout.LayoutParams(dp(32), -1))
            addView(thumbnail, LinearLayout.LayoutParams(dp(THUMBNAIL_WIDTH), dp(THUMBNAIL_HEIGHT)))
            addView(
                label(if (layer.maskId != 0) "◐ ${layer.name}" else layer.name).apply {
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
        layerPreviews.keys.retainAll(document.layers.map { it.id }.toSet())
        document.layers.map { it.id }.forEach { id ->
            canvas.snapshot { engine ->
                val pixels = ArtworkStore.pixelBuffer(width, height)
                NativeEngine.readLayerThumbnail(engine, id, width, height, pixels)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                pixels.rewind()
                bitmap.copyPixelsFromBuffer(pixels)
                runOnUiThread {
                    layerPreviews[id] = bitmap
                    layerThumbnails[id]?.setImageBitmap(bitmap)
                }
            }
        }
    }

    internal fun addLayer() {
        if (layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
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
        if (layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
            return
        }
        setEditingMask(false)
        val id = document.nextLayerId++
        engineLayers.add(id)
        val mask = if (layer.maskId != 0) document.nextLayerId++ else 0
        if (mask != 0) engineLayers.add(mask)
        val sourceMask = layer.maskId
        canvas.act {
            NativeEngine.duplicateLayer(it, layer.id, id)
            if (mask != 0) NativeEngine.duplicateLayer(it, sourceMask, mask)
        }
        editLayers {
            val copy = layer.copy(id = id, name = tr("%1 copy", layer.name), file = null, maskId = mask, maskFile = null)
            document.layers.add(document.layers.indexOf(layer) + 1, copy)
            document.activeLayer = id
        }
        refreshLayers()
    }

    internal fun mergeDown(layer: LayerInfo) {
        val index = document.layers.indexOf(layer)
        if (index == 0) {
            say(tr("There isn't a layer below this one"))
            return
        }
        val below = document.layers[index - 1]
        setEditingMask(false)
        canvas.act { NativeEngine.mergeLayerDown(it, layer.id, below.id) }
        editLayers(pixels = true) {
            document.layers.remove(layer)
            if (below.maskEnabled) below.maskId = 0
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
            say(tr("An artwork needs at least one layer"))
            return
        }
        editingMask = false
        markMaskEditing()
        editLayers {
            val index = document.layers.indexOf(layer)
            document.layers.remove(layer)
            document.activeLayer = document.layers[max(0, index - 1)].id
        }
        refreshLayers()
    }

    private fun askForName(title: String, current: String, done: (String) -> Unit) = askSheet(title, current, done)

    private fun renameLayer(layer: LayerInfo) = askForName(tr("Rename layer"), layer.name) { name ->
        editLayers(structural = false) { layer.name = name }
        refreshLayers()
    }

    private fun actionsPanel(): View = column().apply {
        addView(label(document.name, 16f))
        addView(label(tr("%1 x %2 pixels · %3 of %4 layers", document.width, document.height, layerSlots, maxLayers), 12f, MUTED_TEXT))
        gapped(
            actionRow(R.drawable.ic_rename, tr("Rename artwork")) {
                closePanel()
                askForName(tr("Rename artwork"), document.name) {
                    document.name = it
                    markUnsaved()
                }
            },
        )
        gapped(
            actionRow(R.drawable.ic_text, tr("Add text"), tr("Label a design sheet")) {
                closePanel()
                askForText()
            },
        )
        gapped(
            actionRow(R.drawable.ic_copy, tr("Make a sticker"), tr("Add a die-cut border around your drawing")) { openStickerPanel() },
        )
        gapped(
            actionRow(R.drawable.ic_palette, tr("Color variants"), tr("Try this design in new colors")) {
                closePanel()
                showPanel("variants", variantsPanel())
            },
        )
        gapped(label(tr("Variations"), 13f, MUTED_TEXT))
        gapped(actionRow(R.drawable.ic_duplicate, tr("Make a variation"), tr("Try another version, keeping this one")) { makeVariation() })
        val family = store.variations(document.id)
        if (family.size > 1) {
            val cell = (dp(PANEL_WIDTH) - dp(36)) / 2
            gapped(
                GridLayout(context).apply {
                    columnCount = 2
                    family.forEach { art ->
                        addView(
                            column().apply {
                                setOnClickListener { if (art.id != document.id) openVariation(art.id) }
                                addView(
                                    ImageView(context).apply {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        background = rounded(if (art.id == document.id) ACCENT else SURFACE_RAISED, 8)
                                        setPadding(dp(2), dp(2), dp(2), dp(2))
                                        contentDescription = tr("%1 preview", art.name)
                                        thumbnails.show(store.thumbnail(art.id), cell, this)
                                    },
                                    LinearLayout.LayoutParams(cell, cell * 2 / 3),
                                )
                                addView(label(art.name, 12f, MUTED_TEXT).apply { maxLines = 1 })
                            },
                            GridLayout.LayoutParams().apply {
                                width = cell
                                setMargins(0, 0, dp(4), dp(4))
                            },
                        )
                    }
                },
            )
        }
        gapped(
            actionRow(R.drawable.ic_video, if (document.animation) tr("Stop animating") else tr("Animate"), tr("Each layer becomes a frame")) {
                closePanel()
                setAnimation(!document.animation)
            },
        )
        gapped(label(tr("Export"), 13f, MUTED_TEXT))
        gapped(
            actionRow(R.drawable.ic_layers, tr("Design sheet"), tr("All variations on one page")) {
                closePanel()
                exportSheet()
            },
        )
        gapped(
            actionRow(R.drawable.ic_export, tr("Frames as PNGs"), tr("One image per thumbnail frame")) {
                closePanel()
                exportFrames()
            },
        )
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
                askTimelapseLength()
            },
        )
        gapped(label(tr("Language"), 13f, MUTED_TEXT))
        gapped(
            segmented(Language.entries.map { it.label }, I18n.language.ordinal) { index ->
                setLanguage(Language.entries[index])
            },
        )
        gapped(label(tr("Pen button"), 13f, MUTED_TEXT))
        gapped(
            segmented(listOf(tr("Pick color"), tr("Eraser"), tr("Undo")), canvas.penButtonAction) { action ->
                canvas.penButtonAction = action
                preferences.edit().putInt("penButton", action).apply()
                rerender("actions", ::actionsPanel)
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

    private fun askForText(editing: LayerInfo? = null) {
        val input = EditText(this).apply {
            hint = tr("Your label")
            setText(editing?.text.orEmpty())
        }
        var size = TEXT_SIZES.indexOfFirst { it >= (editing?.textSize ?: 0f) - 0.001f }.coerceAtLeast(0).let { if (editing == null) 1 else it }
        val sizes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun showSizes() {
            sizes.removeAllViews()
            sizes.addView(
                segmented(listOf(tr("Small"), tr("Medium"), tr("Large")), size) {
                    size = it
                    showSizes()
                },
            )
        }
        showSizes()
        val form = column().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            gapped(sizes)
        }
        AlertDialog.Builder(this)
            .setTitle(if (editing == null) tr("Add text") else tr("Edit text"))
            .setView(form)
            .setNegativeButton(tr("Cancel"), null)
            .setPositiveButton(if (editing == null) tr("Add") else tr("Save")) { _, _ ->
                input.text.toString().takeIf { it.isNotBlank() }?.let {
                    if (editing == null) addText(it, TEXT_SIZES[size]) else replaceText(editing, it, TEXT_SIZES[size])
                }
            }
            .show()
    }

    private fun drawTextPage(text: String, size: Float, color: Int, centerX: Float, centerY: Float): java.nio.ByteBuffer {
        val page = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val lines = text.split("\n")
        val top = centerY - lines.size * paint.textSize / 2f
        lines.forEachIndexed { index, line ->
            android.graphics.Canvas(page).drawText(line, centerX, top + (index + 1) * paint.textSize, paint)
        }
        val pixels = ArtworkStore.pixelBuffer(document.width, document.height)
        page.copyPixelsToBuffer(pixels)
        page.recycle()
        pixels.rewind()
        return pixels
    }

    /** Swaps in freshly set text where the old text now sits, so editing it keeps your placement. */
    internal fun replaceText(layer: LayerInfo, text: String, heightShare: Float) {
        val width = TEXT_FIND_WIDTH
        val height = max(1, width * document.height / document.width)
        val small = ArtworkStore.pixelBuffer(width, height)
        val color = layer.textColor
        val id = document.nextLayerId++
        engineLayers.add(id)
        canvas.act { engine ->
            NativeEngine.readLayerThumbnail(engine, layer.id, width, height, small)
            small.rewind()
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (small.get((y * width + x) * 4 + 3).toInt() and 0xFF <= 8) continue
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                }
            }
            val found = right >= 0
            val centerX = if (found) (left + right + 1) / 2f / width * document.width else document.width / 2f
            val centerY = if (found) (top + bottom + 1) / 2f / height * document.height else document.height / 2f
            val pixels = drawTextPage(text, document.height * heightShare, color, centerX, centerY)
            NativeEngine.createLayer(engine, id)
            NativeEngine.writeLayer(engine, id, pixels)
            NativeEngine.markLayerDirty(engine, id)
            runOnUiThread {
                editLayers {
                    val index = document.layers.indexOf(layer)
                    document.layers[index] = layer.copy(
                        id = id, name = text.lineSequence().first().take(24), text = text, textSize = heightShare,
                        file = null, maskId = 0, maskFile = null,
                    )
                    document.activeLayer = id
                }
                refreshLayers()
            }
        }
    }

    private fun importLayerImage(uri: android.net.Uri) {
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
        }?.take(24) ?: tr("Image")
        val width = document.width
        val height = document.height
        saver.execute {
            val image = runCatching {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val shrink = min(1f, min(width / info.size.width.toFloat(), height / info.size.height.toFloat()))
                    decoder.setTargetSize(max(1, (info.size.width * shrink).toInt()), max(1, (info.size.height * shrink).toInt()))
                }
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (image == null) {
                    say(tr("Couldn't read this image"))
                } else {
                    addImage(image, name)
                }
            }
        }
    }

    /** Places an image in the middle of a new layer and starts a transform so it can be moved. */
    internal fun addImage(image: Bitmap, name: String) {
        if (layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
            return
        }
        setEditingMask(false)
        val page = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(page).drawBitmap(
            image, (document.width - image.width) / 2f, (document.height - image.height) / 2f,
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        val pixels = ArtworkStore.pixelBuffer(document.width, document.height)
        page.copyPixelsToBuffer(pixels)
        page.recycle()
        image.recycle()
        pixels.rewind()
        val id = document.nextLayerId++
        engineLayers.add(id)
        canvas.act { engine ->
            NativeEngine.createLayer(engine, id)
            NativeEngine.writeLayer(engine, id, pixels)
            NativeEngine.markLayerDirty(engine, id)
        }
        editLayers {
            val index = document.layers.indexOfFirst { it.id == document.activeLayer } + 1
            document.layers.add(index, LayerInfo(id, name))
            document.activeLayer = id
        }
        startTransform()
    }

    /** Draws text on a new layer and starts a transform so it can be placed. */
    internal fun addText(text: String, heightShare: Float) {
        if (layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
            return
        }
        setEditingMask(false)
        val color = canvas.brushColor
        val pixels = drawTextPage(text, document.height * heightShare, color, document.width / 2f, document.height / 2f)
        val id = document.nextLayerId++
        engineLayers.add(id)
        canvas.act { engine ->
            NativeEngine.createLayer(engine, id)
            NativeEngine.writeLayer(engine, id, pixels)
            NativeEngine.markLayerDirty(engine, id)
        }
        editLayers {
            val index = document.layers.indexOfFirst { it.id == document.activeLayer } + 1
            document.layers.add(
                index,
                LayerInfo(id, text.lineSequence().first().take(24), text = text, textSize = heightShare, textColor = color),
            )
            document.activeLayer = id
        }
        startTransform()
    }

    private fun stickerLayers() = document.layers.filter { it.visible && !it.sticker && document.groups.none { group -> group.id == it.groupId && !group.visible } }

    /** Reads a small copy of the drawing once, so the border slider can answer right away. */
    internal fun openStickerPanel() {
        val longest = max(document.width, document.height)
        val width = max(1, document.width * STICKER_SIDE / longest)
        val height = max(1, document.height * STICKER_SIDE / longest)
        val layers = stickerLayers().map { it.id to it.opacity }
        canvas.snapshot { engine ->
            val art = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val painter = android.graphics.Canvas(art)
            val alpha = ByteArray(width * height)
            layers.forEach { (id, opacity) ->
                val pixels = ArtworkStore.pixelBuffer(width, height)
                NativeEngine.readLayerThumbnail(engine, id, width, height, pixels)
                pixels.rewind()
                for (index in alpha.indices) {
                    val value = (pixels.get(index * 4 + 3).toInt() and 0xFF) * opacity
                    if (value > (alpha[index].toInt() and 0xFF)) alpha[index] = value.toInt().toByte()
                }
                pixels.rewind()
                val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                layer.copyPixelsFromBuffer(pixels)
                painter.drawBitmap(layer, 0f, 0f, android.graphics.Paint().apply { this.alpha = (opacity * 255).toInt() })
                layer.recycle()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                stickerArt = art
                stickerAlpha = alpha
                stickerSmallAlpha = null
                stickerWidth = width
                stickerHeight = height
                closePanel()
                showPanel("sticker", stickerPanel())
            }
        }
    }

    private fun stickerRadius() = (stickerBorder * MAX_BORDER * STICKER_SIDE).coerceAtLeast(1f)

    /** The border, with its shadow behind it, ready to draw at any size. */
    private fun paintSticker(
        target: android.graphics.Canvas,
        shape: ByteArray,
        shapeWidth: Int,
        shapeHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        val pixels = IntArray(shape.size) { ((shape[it].toInt() and 0xFF) shl 24) or 0xFFFFFF }
        val small = Bitmap.createBitmap(pixels, shapeWidth, shapeHeight, Bitmap.Config.ARGB_8888)
        val scale = outputWidth / shapeWidth.toFloat()
        val box = android.graphics.RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat())
        if (stickerShadow) {
            val blur = 6f * scale
            val outline = small.extractAlpha()
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = SHADOW_ALPHA
                maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            target.save()
            target.translate(0f, blur * 0.6f)
            target.drawBitmap(outline, null, box, paint)
            target.restore()
            outline.recycle()
        }
        val red = Color.red(stickerColor).toFloat()
        val green = Color.green(stickerColor).toFloat()
        val blue = Color.blue(stickerColor).toFloat()
        // Sharpens an enlarged edge, so a small shape still cuts a clean line.
        val enlarged = scale > 1f
        val edge = android.graphics.ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, red, 0f, 0f, 0f, 0f, green, 0f, 0f, 0f, 0f, blue,
                0f, 0f, 0f, if (enlarged) 5f else 1f, if (enlarged) -510f else 0f,
            ),
        )
        target.drawBitmap(
            small, null, box,
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(edge)
            },
        )
        small.recycle()
    }

    private fun refreshStickerPreview() {
        val alpha = stickerAlpha ?: return
        val art = stickerArt ?: return
        val image = stickerImage ?: return
        val radius = stickerRadius() / STICKER_PREVIEW_STEP
        val shapeWidth = stickerWidth / STICKER_PREVIEW_STEP
        val shapeHeight = stickerHeight / STICKER_PREVIEW_STEP
        val request = ++stickerRequest
        stickerWorker.execute {
            // A slider sends many values, and only the newest one is worth drawing.
            if (request != stickerRequest) return@execute
            val small = stickerSmallAlpha ?: shrinkAlpha(alpha, stickerWidth, stickerHeight, STICKER_PREVIEW_STEP).also { stickerSmallAlpha = it }
            val shape = stickerShape(small, shapeWidth, shapeHeight, radius) ?: return@execute
            val height = STICKER_PREVIEW * stickerHeight / stickerWidth
            val preview = Bitmap.createBitmap(STICKER_PREVIEW, height, Bitmap.Config.ARGB_8888)
            val painter = android.graphics.Canvas(preview)
            painter.drawColor(ROAST)
            paintSticker(painter, shape, shapeWidth, shapeHeight, STICKER_PREVIEW, height)
            painter.drawBitmap(art, null, android.graphics.RectF(0f, 0f, STICKER_PREVIEW.toFloat(), height.toFloat()), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            runOnUiThread { if (request == stickerRequest) image.setImageBitmap(preview) }
        }
    }

    private fun stickerPanel(): View = column().apply {
        addView(label(tr("Make a sticker"), 16f))
        val alpha = stickerAlpha
        val filled = alpha?.count { (it.toInt() and 0xFF) > 24 }?.toFloat()?.div(alpha.size) ?: 0f
        if (filled == 0f) {
            gapped(label(tr("Draw something first."), 13f, MUTED_TEXT))
            return@apply
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(ROAST, 10)
            clipToOutline = true
            contentDescription = tr("Sticker preview")
        }
        stickerImage = image
        gapped(image)
        image.layoutParams.height = dp(190)
        if (filled >= FULL_CANVAS_SHARE) {
            gapped(label(tr("Your drawing fills the canvas. Keep the background on its own hidden layer for a shaped sticker."), 12f, MUTED_TEXT))
        }
        addView(toolSlider(tr("Border size"), (stickerBorder * 100).roundToInt()) {
            stickerBorder = it / 100f
            refreshStickerPreview()
        })
        gapped(
            wrapRow(
                listOf(
                    tr("White") to Color.WHITE,
                    tr("Black") to Color.BLACK,
                    tr("Your color") to canvas.brushColor,
                ).map { (name, color) ->
                    smallChip(name, stickerColor == color) {
                        stickerColor = color
                        rerender("sticker", ::stickerPanel)
                    }
                } + smallChip(tr("Shadow"), stickerShadow) {
                    stickerShadow = !stickerShadow
                    rerender("sticker", ::stickerPanel)
                },
            ),
        )
        gapped(
            wrapRow(
                listOf(
                    smallChip(tr("Add sticker layer"), true) { makeSticker(export = false) },
                    smallChip(tr("Save as PNG")) { makeSticker(export = true) },
                ),
            ),
        )
        refreshStickerPreview()
    }

    /** Puts the drawing and its border together on one new top layer, replacing an older sticker. */
    internal fun makeSticker(export: Boolean) {
        val alpha = stickerAlpha ?: return
        val old = document.layers.filter { it.sticker }
        if (old.isEmpty() && layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
            return
        }
        val width = document.width
        val height = document.height
        val name = tr("%1 sticker", document.name)
        val radius = stickerRadius()
        val layers = stickerLayers().map { it.id to it.opacity }
        closePanel()
        say(tr("Making your sticker"))
        saver.execute {
            val shape = stickerShape(alpha, stickerWidth, stickerHeight, radius) ?: return@execute
            val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val painter = android.graphics.Canvas(page)
            paintSticker(painter, shape, stickerWidth, stickerHeight, width, height)
            canvas.snapshot { engine ->
                layers.forEach { (id, opacity) ->
                    val pixels = ArtworkStore.pixelBuffer(width, height)
                    NativeEngine.readLayerThumbnail(engine, id, width, height, pixels)
                    pixels.rewind()
                    val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    layer.copyPixelsFromBuffer(pixels)
                    painter.drawBitmap(layer, 0f, 0f, android.graphics.Paint().apply { this.alpha = (opacity * 255).toInt() })
                    layer.recycle()
                }
                val pixels = ArtworkStore.pixelBuffer(width, height)
                page.copyPixelsToBuffer(pixels)
                page.recycle()
                pixels.rewind()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setEditingMask(false)
                    val id = document.nextLayerId++
                    engineLayers.add(id)
                    canvas.act { engine ->
                        NativeEngine.createLayer(engine, id)
                        NativeEngine.writeLayer(engine, id, pixels)
                        NativeEngine.markLayerDirty(engine, id)
                    }
                    editLayers {
                        document.layers.removeAll(old.toSet())
                        document.layers.add(LayerInfo(id, tr("Sticker"), sticker = true))
                        document.activeLayer = id
                    }
                    if (export) {
                        saver.execute {
                            val saved = runCatching { store.exportPng(name, width, height, pixels) }.getOrDefault(false)
                            runOnUiThread { say(if (saved) tr("Saved to Pictures/Cappuccino") else tr("Couldn't export this PNG"), long = true) }
                        }
                    }
                }
            }
        }
    }

    private fun variantsPanel(): View = column().apply {
        addView(label(tr("Color variants"), 16f))
        val cell = (dp(PANEL_WIDTH) - dp(36)) / 2
        val width = ArtworkStore.THUMBNAIL_WIDTH / 2
        val height = max(1, width * document.height / document.width)
        val tiles = COLOR_VARIANTS.map { turn ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(SURFACE_RAISED, 8)
                clipToOutline = true
                contentDescription = tr("Color variants")
                setOnClickListener { applyColorVariant(turn) }
            }
        }
        gapped(
            GridLayout(context).apply {
                columnCount = 2
                tiles.forEach { tile ->
                    addView(
                        tile,
                        GridLayout.LayoutParams().apply {
                            this.width = cell
                            this.height = cell * 2 / 3
                            setMargins(0, 0, dp(4), dp(4))
                        },
                    )
                }
            },
        )
        gapped(label(tr("Tap one to recolor every layer. Make a variation first to keep this version."), 12f, MUTED_TEXT))
        canvas.snapshot { engine ->
            val buffer = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, buffer)
            saver.execute {
                val raw = buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                val pixels = IntArray(raw.remaining()).also(raw::get)
                for (index in pixels.indices) {
                    val value = pixels[index]
                    pixels[index] = (0xFF shl 24) or ((value and 0xFF) shl 16) or (value and 0xFF00) or ((value shr 16) and 0xFF)
                }
                COLOR_VARIANTS.forEachIndexed { index, turn ->
                    val bitmap = Bitmap.createBitmap(shiftHue(pixels, turn), width, height, Bitmap.Config.ARGB_8888)
                    runOnUiThread { tiles[index].setImageBitmap(bitmap) }
                }
            }
        }
    }

    /** Turns the hue of every layer. One undo restores them all. */
    internal fun applyColorVariant(turn: Float) {
        closePanel()
        val ids = document.layers.map { it.id }
        val target = paintTarget()
        val values = floatArrayOf(turn, 1f, 0f, 0f, 0f, 0f)
        canvas.act { engine ->
            ids.forEachIndexed { index, id ->
                NativeEngine.setActiveLayer(engine, id)
                NativeEngine.setAdjustment(engine, true, values)
                NativeEngine.commitAdjustment(engine, index > 0)
            }
            NativeEngine.setActiveLayer(engine, target)
        }
        history.record(Edit.Stroke)
        markUnsaved()
    }

    internal fun makeVariation() {
        closePanel()
        val id = document.id
        save(withPreview = true) {
            val copy = store.makeVariation(id)
            runOnUiThread { openVariation(copy) }
        }
    }

    private fun openVariation(id: String) {
        closePanel()
        save {
            runOnUiThread {
                startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_ARTWORK, id))
                finish()
            }
        }
    }

    private fun exportSheet() {
        val name = document.name
        val id = document.id
        say(tr("Making your design sheet"))
        save(withPreview = true) {
            val saved = runCatching {
                val family = store.variations(id)
                val images = family.mapNotNull { art ->
                    BitmapFactory.decodeFile(store.previewFile(art.id).path)?.let { art.name to it }
                }
                val sheet = contactSheet(name, images)
                images.forEach { it.second.recycle() }
                val pixels = ArtworkStore.pixelBuffer(sheet.width, sheet.height)
                sheet.copyPixelsToBuffer(pixels)
                store.exportPng(tr("%1 design sheet", name), sheet.width, sheet.height, pixels).also { sheet.recycle() }
            }.getOrDefault(false)
            runOnUiThread {
                val message = if (saved) tr("Saved to Pictures/Cappuccino") else tr("Couldn't export this PNG")
                say(message, long = true)
            }
        }
    }

    private fun exportFrames() {
        val name = document.name
        val width = document.width
        val height = document.height
        canvas.snapshot { engine ->
            val pixels = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, pixels)
            saver.execute {
                val saved = runCatching {
                    val whole = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    pixels.rewind()
                    whole.copyPixelsFromBuffer(pixels)
                    val results = thumbnailFrames(width, height).mapIndexed { index, frame ->
                        val left = frame[0].toInt()
                        val top = frame[1].toInt()
                        val crop = Bitmap.createBitmap(whole, left, top, frame[2].toInt() - left, frame[3].toInt() - top)
                        val part = ArtworkStore.pixelBuffer(crop.width, crop.height)
                        crop.copyPixelsToBuffer(part)
                        store.exportPng("$name ${index + 1}", crop.width, crop.height, part).also { crop.recycle() }
                    }
                    whole.recycle()
                    results.all { it }
                }.getOrDefault(false)
                runOnUiThread {
                    val message = if (saved) tr("Saved to Pictures/Cappuccino") else tr("Couldn't export this PNG")
                    say(message, long = true)
                }
            }
        }
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
        val groups = document.groups.map { it.copy() }
        val name = document.name
        val width = document.width
        val height = document.height
        say(tr("Exporting PSD"))
        canvas.snapshot { engine ->
            val parts = layers.map { layer ->
                val pixels = ArtworkStore.pixelBuffer(width, height)
                NativeEngine.readLayer(engine, layer.id, pixels)
                if (layer.maskId != 0 && layer.maskEnabled) {
                    val mask = ArtworkStore.pixelBuffer(width, height)
                    NativeEngine.readLayer(engine, layer.maskId, mask)
                    applyMaskPixels(pixels, mask)
                }
                layer to pixels
            }
            val flat = ArtworkStore.pixelBuffer(width, height)
            NativeEngine.readComposite(engine, width, height, flat)
            saver.execute {
                val saved = runCatching {
                    val flatLayers = parts.map { (layer, pixels) ->
                        val bytes = ByteArray(pixels.capacity())
                        pixels.rewind()
                        pixels.get(bytes)
                        PsdLayer(layer.name, layer.opacity, layer.visible, layer.blend, layer.clip, bytes)
                    }
                    val psdLayers = withPsdGroups(
                        flatLayers,
                        { index -> groups.firstOrNull { it.id == parts[index].first.groupId } },
                        { group -> psdFolder(group.name, group.opacity, group.visible) },
                    )
                    val merged = ByteArray(flat.capacity())
                    flat.rewind()
                    flat.get(merged)
                    store.exportPsd(name, width, height, psdLayers, merged)
                }.getOrDefault(false)
                runOnUiThread {
                    val message = if (saved) tr("Saved to Download/Cappuccino") else tr("Couldn't export this PSD")
                    say(message, long = true)
                }
            }
        }
    }

    /** PSD layers carry their masks baked in, so they look the same in other apps. */
    private fun applyMaskPixels(pixels: java.nio.ByteBuffer, mask: java.nio.ByteBuffer) {
        for (index in 0 until pixels.capacity() step 4) {
            val hidden = mask.get(index + 3).toInt() and 0xFF
            if (hidden == 0) continue
            for (channel in 0 until 4) {
                val value = pixels.get(index + channel).toInt() and 0xFF
                pixels.put(index + channel, (value * (255 - hidden) / 255).toByte())
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
                    say(message, long = true)
                    if (saved) celebrateFinish()
                }
            }
        }
    }

    /** After an export, the companion looks back at how far the piece came. */
    private fun celebrateFinish() {
        val now = latestSnapshot ?: return
        saver.execute {
            val first = store.firstSnapshot(document.id) ?: return@execute
            val note = finishNote(first, now)
            handler.postDelayed({ if (!isDestroyed) say(note, long = true) }, FINISH_NOTE_DELAY_MS)
        }
    }

    private fun referencesPanel(): View = column().apply {
        val notes = store.referenceNotes(document.projectId)
        val blocks = referenceBlocks(notes)
        if (referenceFilter.isNotEmpty() && referenceFilter != STARRED_FILTER && referenceFilter !in blocks) referenceFilter = ""
        val all = store.references(document.projectId)
        val inside = referenceFilter.isNotEmpty()
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (inside) {
                    addView(
                        smallChip("‹") {
                            referenceFilter = ""
                            pickingReferences = false
                            pickedReferences.clear()
                            rerender("references", ::referencesPanel)
                        },
                        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
                    )
                }
                val title = if (referenceFilter == STARRED_FILTER) "★" else if (inside) tr(referenceFilter) else tr("References")
                addView(label(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                if (all.isNotEmpty()) {
                    addView(
                        smallChip(tr("Select"), pickingReferences) {
                            pickingReferences = !pickingReferences
                            pickedReferences.clear()
                            rerender("references", ::referencesPanel)
                        },
                    )
                }
                addView(
                    iconButton(R.drawable.ic_add, tr("Add references")) {
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
        if (all.isEmpty()) {
            gapped(label(tr("Add images, or share them here from another app."), 12f, MUTED_TEXT))
            return@apply
        }
        val names = if (inside) {
            filterReferences(all.map { it.name }, notes, referenceFilter).toSet()
        } else {
            all.filter { notes[it.name]?.board.isNullOrEmpty() }.map { it.name }.toSet()
        }
        val files = all.filter { it.name in names }
        if (!inside) {
            val full = blocks.filter { block -> all.any { notes[it.name]?.board == block } }
            if (full.isNotEmpty()) gapped(blockGrid(full, all, notes))
            val quiet = buildList {
                if (all.any { notes[it.name]?.starred == true }) add(STARRED_FILTER)
                addAll(blocks - full.toSet())
            }
            gapped(
                wrapRow(
                    quiet.map { block ->
                        smallChip(if (block == STARRED_FILTER) "★" else tr(block)) {
                            referenceFilter = block
                            rerender("references", ::referencesPanel)
                        }
                    },
                ),
            )
            if (files.isNotEmpty()) gapped(label(tr("Not sorted"), 12f, MUTED_TEXT))
        }
        if (pickingReferences) gapped(wrapRow(referencePickActions(files)))
        if (referenceFilter in blocks) boardControls(referenceFilter)
        if (files.isNotEmpty()) gapped(referenceGrid(files, notes))
    }

    /** Each block with a peek at its first images. */
    private fun blockGrid(blocks: List<String>, all: List<File>, notes: Map<String, ReferenceNote>) = GridLayout(this).apply {
        columnCount = 2
        val width = (dp(PANEL_WIDTH) - dp(28) - dp(8)) / 2
        val peek = (width - dp(16) - dp(4)) / 3
        blocks.forEach { block ->
            val inside = all.filter { notes[it.name]?.board == block }
            addView(
                column().apply {
                    background = rounded(SURFACE_RAISED, 12)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = tr(block)
                    setOnClickListener {
                        referenceFilter = block
                        rerender("references", ::referencesPanel)
                    }
                    addView(
                        LinearLayout(context).apply {
                            inside.take(3).forEach { file ->
                                addView(
                                    ImageView(context).apply {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        background = rounded(SURFACE, 6)
                                        clipToOutline = true
                                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                        thumbnails.show(file, peek, this)
                                    },
                                    LinearLayout.LayoutParams(peek, peek).apply { marginEnd = dp(2) },
                                )
                            }
                        },
                        LinearLayout.LayoutParams(-1, peek),
                    )
                    addView(
                        label("${tr(block)} · ${inside.size}", 12f).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
                    )
                },
                GridLayout.LayoutParams().apply {
                    this.width = width
                    setMargins(0, 0, dp(4), dp(4))
                },
            )
        }
    }

    private fun referenceGrid(files: List<File>, notes: Map<String, ReferenceNote>) = GridLayout(this).apply {
        columnCount = 3
        val cell = (dp(PANEL_WIDTH) - dp(28) - dp(8)) / 3
        files.forEach { file ->
            val open = references.containsKey(file.name)
            addView(
                FrameLayout(context).apply {
                    val marked = if (pickingReferences) file.name in pickedReferences else open
                    background = rounded(if (marked) ACCENT else SURFACE, 10)
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    addView(
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = rounded(SURFACE_RAISED, 8)
                            clipToOutline = true
                            thumbnails.show(file, cell, this)
                            contentDescription = if (open) tr("Close reference") else tr("Open reference")
                            setOnClickListener {
                                if (pickingReferences) {
                                    if (!pickedReferences.remove(file.name)) pickedReferences.add(file.name)
                                } else {
                                    toggleReference(file)
                                }
                                rerender("references", ::referencesPanel)
                            }
                            setOnLongClickListener {
                                manageReference(file)
                                true
                            }
                        },
                    )
                    val block = notes[file.name]?.board.orEmpty()
                    if (block.isNotEmpty()) {
                        addView(
                            label(tr(block), 9f).apply {
                                background = rounded(SURFACE, 6)
                                setPadding(dp(5), dp(1), dp(5), dp(1))
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            },
                            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                                setMargins(dp(3), 0, dp(3), dp(3))
                            },
                        )
                    }
                    if (notes[file.name]?.starred == true) {
                        addView(
                            label("★", 13f).apply { setShadowLayer(4f, 0f, 1f, Color.BLACK) },
                            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                                setMargins(0, dp(2), dp(5), 0)
                            },
                        )
                    }
                },
                GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                    setMargins(0, 0, dp(4), dp(4))
                },
            )
        }
    }

    /** What the companion uses this board for, and whether this artwork follows it. */
    private fun LinearLayout.boardControls(board: String) {
        val project = document.projectId
        val uses = store.boardPurposes(project)[board].orEmpty()
        gapped(label(tr("Your companion reads this block for"), 12f, MUTED_TEXT))
        gapped(
            wrapRow(
                BoardPurpose.entries.map { purpose ->
                    smallChip(tr(purpose.label), purpose in uses) {
                        val next = when {
                            purpose in uses -> uses - purpose
                            purpose == BoardPurpose.INSPIRATION -> setOf(purpose)
                            else -> uses - BoardPurpose.INSPIRATION + purpose
                        }
                        store.setBoardPurposes(project, board, next)
                        markUnsaved()
                        rerender("references", ::referencesPanel)
                    }
                },
            ),
        )
        val following = board in document.followedBoards
        gapped(
            wrapRow(
                listOf(
                    smallChip(tr("Only for this artwork"), following) {
                        document.followedBoards = if (following) document.followedBoards - board else document.followedBoards + board
                        markUnsaved()
                        rerender("references", ::referencesPanel)
                    },
                ),
            ),
        )
    }

    private fun referencePickActions(all: List<File>): List<View> {
        val picked = all.filter { it.name in pickedReferences }
        val project = document.projectId
        fun finish() {
            pickingReferences = false
            pickedReferences.clear()
            rerender("references", ::referencesPanel)
        }
        val actions = arrayListOf<View>(
            smallChip(tr("All")) {
                pickedReferences.addAll(all.map { it.name })
                rerender("references", ::referencesPanel)
            },
        )
        if (picked.isEmpty()) return actions
        actions += smallChip("★") {
            picked.forEach { file -> store.updateReferenceNote(project, file.name) { it.copy(starred = true) } }
            finish()
        }
        actions += smallChip(tr("Block")) { chooseBoard(store, project, picked) { finish() } }
        actions += smallChip(tr("Move")) {
            chooseSketchbook(store, project, picked) {
                picked.forEach { references.remove(it.name)?.dismiss() }
                rememberReferences()
                finish()
            }
        }
        actions += smallChip(tr("Delete")) {
            confirmSheet(tr("Delete %1 references?", picked.size), tr("They're removed from this project and every artwork using them.")) {
                picked.forEach { file ->
                    references.remove(file.name)?.dismiss()
                    store.deleteReference(project, file)
                }
                rememberReferences()
                finish()
            }
        }
        return actions
    }

    private fun manageReference(file: File) {
        val refresh = { rerender("references", ::referencesPanel) }
        referenceOptions(
            store, document.projectId, file,
            changed = refresh,
            moved = {
                references.remove(file.name)?.dismiss()
                rememberReferences()
                refresh()
            },
            delete = { confirmDeleteReference(file) },
        )
    }

    private fun confirmDeleteReference(file: File) = confirmSheet(
        tr("Delete this reference?"),
        tr("It's removed from this project and every artwork using it."),
    ) {
        references.remove(file.name)?.dismiss()
        store.deleteReference(document.projectId, file)
        rememberReferences()
        rerender("references", ::referencesPanel)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_LAYER_IMAGE) {
            if (resultCode == RESULT_OK) data?.data?.let(::importLayerImage)
            return
        }
        if (requestCode == PICK_TIP && resultCode == RESULT_OK && data?.data != null) {
            val bitmap = contentResolver.openInputStream(data.data!!)?.use(BitmapFactory::decodeStream)
            if (bitmap == null) {
                say(tr("Couldn't read this image"))
                return
            }
            draftTip = tipFrom(bitmap)
            val shape = pendingBrushShape ?: return
            val source = BrushKind(
                editingBrush?.id ?: "draft", pendingBrushName, shape[0], shape[1], shape[2], shape[3],
                shape[4], shape[5], canvas.brushDiameter,
                scatter = shape.getOrElse(6) { 0f }, sizeJitter = shape.getOrElse(7) { 0f },
                angleJitter = shape.getOrElse(8) { 0f }, shadeJitter = shape.getOrElse(9) { 0f },
                hueJitter = shape.getOrElse(10) { 0f }, strokeJitter = shape.getOrElse(11) { 0f },
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
        val block = referenceFilter.takeIf { it.isNotEmpty() && it != STARRED_FILTER }
        saver.execute {
            (multiple.ifEmpty { single }).forEach { uri ->
                runCatching {
                    val added = store.addReference(project, uri)
                    if (added != null && block != null) store.updateReferenceNote(project, added.name) { it.copy(board = block) }
                }
            }
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
            say(tr("Couldn't open this image"))
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

    override fun onReferencePick(color: Int, screenX: Float, screenY: Float) = showPickRing(color, screenX, screenY)

    override fun onReferencePickEnd() = hidePickRing()

    override fun onReferencePalette(reference: ReferenceWindow) {
        val bitmap = reference.bitmap
        saver.execute {
            val small = Bitmap.createScaledBitmap(bitmap, 240, max(1, 240 * bitmap.height / bitmap.width), true)
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val colors = extractPalette(pixels)
            savePalette(tr("Reference colors"), colors)
            runOnUiThread {
                if (colors.isNotEmpty()) say(tr("Palette saved"))
            }
        }
    }

    override fun onReferenceToLayer(reference: ReferenceWindow) {
        if (layerSlots >= maxLayers) {
            say(tr("This canvas can hold %1 layers", maxLayers), long = true)
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
        hidePickRing()
        save(withPreview = previewStale)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideMotion?.removeAllListeners()
        hideMotion?.cancel()
        closePanel()
        hideHandles()
        transformControls?.dismiss()
        noteWindow?.dismiss()
        liquifyControls?.dismiss()
        liquifyRingWindow?.dismiss()
        references.values.forEach { it.dismiss() }
        chrome.forEach { it.dismiss() }
        companionWindow?.dismiss()
        canvas.release()
        thumbnails.close()
        journalWriter.shutdown()
        // A save still running hands its picture to the analyzer, so that stops after the saves.
        saver.execute { analyzer.shutdown() }
        stickerWorker.shutdown()
        saver.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ARTWORK = "artwork"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_NAME = "name"
        const val EXTRA_WARMUP = "warmup"
        const val EXTRA_SEED = "seed"

        /** Opens a fresh canvas for one warm-up exercise. Nothing drawn there is saved. */
        fun warmupIntent(context: android.content.Context, warmup: Warmup, seed: Int = kotlin.random.Random.nextInt()): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_NAME, tr("Warm-up"))
                .putExtra(EXTRA_WIDTH, WARMUP_SIZE)
                .putExtra(EXTRA_HEIGHT, WARMUP_SIZE)
                .putExtra(EXTRA_WARMUP, warmup.kind.name)
                .putExtra(EXTRA_SEED, seed)
    }
}
