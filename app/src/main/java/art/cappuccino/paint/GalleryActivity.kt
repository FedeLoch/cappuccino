package art.cappuccino.paint

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.max

private const val ALL = "all"
private const val PICK_REFERENCES = 42
private const val TOUR_SEEN = "tourSeen"
private const val SEARCH_FROM = 6
private const val CREATE_BACKUP = 43
private const val OPEN_BACKUP = 44
private const val OPEN_PSD = 45
private const val COVER_COLUMNS = 3
private const val COVER_ROW_WIDTH = 640
private const val COVER_SIZE = 88
private const val COVER_HEIGHT = 104
private const val CONTINUE_WIDTH = 380
private const val CONTINUE_HEIGHT = 250

/** Shows projects, the artworks inside them, and each project's reference library. */
class GalleryActivity : Activity() {
    private lateinit var store: ArtworkStore
    private lateinit var root: LinearLayout
    private val background = Executors.newSingleThreadExecutor()
    private val thumbnails = ReferenceThumbnails()
    private var referenceFilter = ""
    private var selected = ALL
    private var showingReferences = false
    private var searchText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ArtworkStore(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(PAGE_CREAM)
                isFillViewport = true
                addView(root)
            },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (PendingSaves.busy) {
            // The artwork just left is still saving, so its new thumbnail comes a moment later.
            background.execute {
                PendingSaves.await()
                runOnUiThread { if (!isDestroyed) render() }
            }
        }
        val preferences = getSharedPreferences("studio", MODE_PRIVATE)
        if (!preferences.getBoolean(TOUR_SEEN, false) && store.artworks().isEmpty()) {
            preferences.edit().putBoolean(TOUR_SEEN, true).apply()
            tourSheet(CompanionLook.COFFEE_DROP) {}
        }
    }

    private fun quietChip(content: String, selected: Boolean = false, onClick: () -> Unit) =
        creamChip(content, selected, onClick)

    private fun wordmark(content: String, size: Float) = label(content, size, ESPRESSO).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    private fun render() {
        root.removeAllViews()
        val projects = store.projects()
        if (selected != ALL && selected.isNotEmpty() && projects.none { it.id == selected }) selected = ALL
        root.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(wordmark("Cappuccino", 34f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(quietChip(tr("Backup")) { backupOptions() })
                addView(
                    quietChip(tr("Import PSD")) { pickPsd() },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                )
                addView(
                    chip(tr("New artwork"), true) { showNewArtwork() },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                )
            },
        )
        if (selected == ALL) renderHome(projects) else renderSketchbook(projects)
    }

    private fun renderHome(projects: List<Project>) {
        val artworks = store.artworks()
        root.addView(greeting(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val shelf = ArrayList<View>()
        projects.forEach { project ->
            shelf += sketchbook(project.name, artworks.count { it.projectId == project.id }, project.color) {
                select(project.id)
            }.apply {
                setOnLongClickListener {
                    projectOptions(project)
                    true
                }
            }
        }
        shelf += sketchbook(tr("Unsorted"), artworks.count { it.projectId.isEmpty() }, null) { select("") }
        shelf += newSketchbook()
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val books = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(tr("Your sketchbooks"), 14f, COCOA))
            addView(row(shelf), spaced())
        }
        if (portrait) {
            artworks.firstOrNull()?.let { latest ->
                root.addView(
                    continueCard(latest),
                    LinearLayout.LayoutParams(-1, dp(CONTINUE_HEIGHT)).apply { topMargin = dp(16) },
                )
            }
            root.addView(books, spaced())
        } else {
            root.addView(
                LinearLayout(this).apply {
                    artworks.firstOrNull()?.let { latest ->
                        addView(
                            continueCard(latest),
                            LinearLayout.LayoutParams(dp(CONTINUE_WIDTH), dp(CONTINUE_HEIGHT)).apply { marginEnd = dp(20) },
                        )
                    }
                    addView(books, LinearLayout.LayoutParams(0, -2, 1f))
                },
                spaced(),
            )
        }
        if (artworks.size > 1) {
            addSearchableGrid(artworks.drop(1), dp(220), tr("Recent"))
        }
    }

    private fun continueCard(art: ArtworkInfo) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 14).apply { setStroke(dp(1), OAT) }
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(
            FrameLayout(context).apply {
                addView(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(Color.WHITE, 8)
                        clipToOutline = true
                        contentDescription = art.name
                        setImageBitmap(BitmapFactory.decodeFile(store.thumbnail(art.id).path))
                    },
                )
                addView(moreButton(art), moreSpot())
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        addView(
            label(tr("Continue %1", art.name), 15f, ESPRESSO),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) },
        )
        setOnClickListener { openStudio(art.id) }
        setOnLongClickListener {
            artworkOptions(art)
            true
        }
    }

    /** The button looks small, but its touch area stays easy to hit. */
    private fun moreSpot() = FrameLayout.LayoutParams(dp(44), dp(36), Gravity.TOP or Gravity.END)

    private fun moreButton(art: ArtworkInfo) = label("···", 11f, ESPRESSO).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(dp(13), dp(5), dp(5), dp(13))
        background = android.graphics.drawable.InsetDrawable(rounded(OAT, 9), dp(13), dp(5), dp(5), dp(13))
        contentDescription = tr("More for %1", art.name)
        setOnClickListener { artworkOptions(art) }
    }

    private fun greeting() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val preferences = getSharedPreferences("studio", MODE_PRIVATE)
        addView(
            CompanionView(context).apply {
                look = CompanionLook.valueOf(preferences.getString("mascot", null) ?: CompanionLook.COFFEE_DROP.name)
                outline = COCOA
                contentDescription = tr("Your companion")
            },
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> tr("Good morning.")
            hour < 19 -> tr("Good afternoon.")
            else -> tr("Good evening.")
        }
        addView(
            label(tr("%1 Ready for another layer?", greeting), 14f, COCOA),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
    }

    private fun sketchbook(name: String, count: Int, color: Int?, onClick: () -> Unit) = FrameLayout(this).apply {
        val (cover, spine) = color?.let { SKETCHBOOK_COLORS[it % SKETCHBOOK_COLORS.size] } ?: (OAT to COCOA)
        background = rounded(cover, 12)
        addView(View(context).apply { background = rounded(spine, 6) }, FrameLayout.LayoutParams(dp(10), -1))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(14), dp(14), dp(14))
                val text = if (color == null) ESPRESSO else Color.WHITE
                addView(label(name, 16f, text))
                addView(label(if (count == 1) tr("1 artwork") else tr("%1 artworks", count), 12f, text))
            },
        )
        layoutParams = LinearLayout.LayoutParams(dp(170), dp(104))
        minimumWidth = dp(170)
        minimumHeight = dp(104)
        contentDescription = tr("Open %1", name)
        setOnClickListener { onClick() }
    }

    private fun newSketchbook() = label(tr("New sketchbook"), 14f, COCOA).apply {
        gravity = Gravity.CENTER
        minWidth = dp(170)
        minHeight = dp(104)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(2), COCOA, dp(8).toFloat(), dp(6).toFloat())
        }
        setOnClickListener {
            newSketchbookSheet(store.projects().size % SKETCHBOOK_COLORS.size) { name, color ->
                selected = store.createProject(name, color).id
                render()
            }
        }
    }

    private fun renderSketchbook(projects: List<Project>) {
        val project = projects.firstOrNull { it.id == selected }
        root.addView(sketchbookCover(project), LinearLayout.LayoutParams(-1, dp(COVER_HEIGHT)).apply { topMargin = dp(16) })
        root.addView(
            LinearLayout(this).apply {
                addView(quietChip(tr("Artworks"), !showingReferences) { showReferences(false) })
                addView(
                    quietChip(tr("References"), showingReferences) { showReferences(true) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
                )
            },
            spaced(),
        )
        if (showingReferences) renderReferences() else renderArtworks()
    }

    /** The open sketchbook's cover, in its own color, with its name and contents. */
    private fun sketchbookCover(project: Project?) = FrameLayout(this).apply {
        val (cover, spine) = project?.let { SKETCHBOOK_COLORS[it.color % SKETCHBOOK_COLORS.size] } ?: (OAT to COCOA)
        val text = if (project == null) ESPRESSO else Color.WHITE
        val artworks = store.artworks().count { it.projectId == selected }
        val references = store.references(selected).size
        background = rounded(cover, 16)
        addView(View(context).apply { background = rounded(spine, 8) }, FrameLayout.LayoutParams(dp(14), -1))
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(30), dp(18), dp(18), dp(18))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(wordmark(project?.name ?: tr("Unsorted"), 30f).apply { setTextColor(text) })
                        addView(
                            label(
                                listOf(
                                    if (artworks == 1) tr("1 artwork") else tr("%1 artworks", artworks),
                                    if (references == 1) tr("1 reference") else tr("%1 references", references),
                                ).joinToString(" · "),
                                14f, text,
                            ),
                        )
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                project?.let {
                    addView(quietChip(tr("Edit")) { projectOptions(it) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                }
                addView(quietChip(tr("Back")) { select(ALL) })
            },
            FrameLayout.LayoutParams(-1, -2),
        )
    }

    internal fun showNewArtwork() {
        val project = if (selected == ALL) "" else selected
        NewArtworkSheet(this, store.projects(), project, ::openNew).show()
    }

    private fun openNew(width: Int, height: Int, name: String, project: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PROJECT, project)
                .putExtra(MainActivity.EXTRA_NAME, name)
                .putExtra(MainActivity.EXTRA_WIDTH, width)
                .putExtra(MainActivity.EXTRA_HEIGHT, height),
        )
    }

    private fun pickPsd() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            OPEN_PSD,
        )
    }

    private fun importPsd(uri: android.net.Uri) {
        val project = if (selected == ALL) "" else selected
        Toast.makeText(this, tr("Importing PSD"), Toast.LENGTH_SHORT).show()
        background.execute {
            val failure = runCatching {
                val image = PsdReader.read(contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                check(image.width <= MAX_CANVAS_SIDE && image.height <= MAX_CANVAS_SIDE) { "too large" }
                store.importPsd(image, tr("Imported PSD"), project)
            }.exceptionOrNull()
            runOnUiThread {
                if (failure != null) {
                    Toast.makeText(this, tr("Couldn't import this PSD. Use an 8-bit RGB file."), Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    private fun select(id: String) {
        selected = id
        showingReferences = false
        render()
    }

    private fun showReferences(show: Boolean) {
        showingReferences = show
        render()
    }

    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }

    private fun row(views: List<View>) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(
            LinearLayout(context).apply {
                views.forEach {
                    addView(it, LinearLayout.LayoutParams(it.layoutParams?.width ?: -2, it.layoutParams?.height ?: -2).apply { marginEnd = dp(10) })
                }
            },
        )
    }

    private fun grid(cellWidth: Int) = GridLayout(this).apply {
        val available = resources.displayMetrics.widthPixels - dp(48)
        columnCount = max(1, available / (cellWidth + dp(12)))
    }

    private fun renderArtworks() {
        val artworks = store.artworks().filter { selected == ALL || it.projectId == selected }
        if (artworks.isEmpty()) {
            root.addView(label(tr("Start your first artwork here."), 16f, COCOA), spaced())
            return
        }
        addSearchableGrid(artworks, dp(240))
    }

    /** A grid with a quiet magnifier. The field only appears when asked for, and only redraws the grid. */
    private fun addSearchableGrid(artworks: List<ArtworkInfo>, cell: Int, title: String? = null) {
        val layout = grid(cell)
        fun fill() {
            layout.removeAllViews()
            findArtworks(artworks, searchText).forEach { layout.addView(card(it, cell)) }
        }
        val field = EditText(this)
        fieldStyle(field, tr("Search"))
        field.setText(searchText)
        field.visibility = if (searchText.isEmpty()) View.GONE else View.VISIBLE
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: android.text.Editable?) {
                searchText = text.toString()
                fill()
            }
        })
        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title.orEmpty(), 14f, COCOA), LinearLayout.LayoutParams(0, -2, 1f))
            if (artworks.size >= SEARCH_FROM) {
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_search)
                        setColorFilter(COCOA)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        contentDescription = tr("Search")
                        setOnClickListener {
                            val top = (parent as View).top
                            val opening = field.visibility != View.VISIBLE
                            field.visibility = if (opening) View.VISIBLE else View.GONE
                            if (opening) {
                                field.requestFocus()
                                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                    .showSoftInput(field, 0)
                                (root.parent as? ScrollView)?.let { page -> page.post { page.smoothScrollTo(0, top) } }
                            } else {
                                field.setText("")
                            }
                        }
                    },
                    LinearLayout.LayoutParams(dp(36), dp(36)),
                )
            }
        }
        if (title != null || artworks.size >= SEARCH_FROM) root.addView(heading, spaced())
        root.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fill()
        root.addView(layout, spaced())
    }

    private fun card(art: ArtworkInfo, width: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = GridLayout.LayoutParams().apply {
            this.width = width
            setMargins(0, 0, dp(12), dp(16))
        }
        addView(
            FrameLayout(context).apply {
                addView(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(Color.WHITE, 8)
                        clipToOutline = true
                        contentDescription = art.name
                        setImageBitmap(BitmapFactory.decodeFile(store.thumbnail(art.id).path))
                    },
                )
                addView(moreButton(art), moreSpot())
            },
            LinearLayout.LayoutParams(-1, width * 2 / 3),
        )
        addView(label(art.name, 14f, ESPRESSO), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(label(DateUtils.getRelativeTimeSpanString(art.updated).toString(), 12f, COCOA))
        setOnClickListener { openStudio(art.id) }
        setOnLongClickListener {
            artworkOptions(art)
            true
        }
    }

    private fun openStudio(id: String?) {
        val project = if (selected == ALL) "" else selected
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ARTWORK, id)
                .putExtra(MainActivity.EXTRA_PROJECT, project),
        )
    }

    internal fun artworkOptions(art: ArtworkInfo) {
        val projects = store.projects()
        artworkSheet(
            art,
            projects.firstOrNull { it.id == art.projectId }?.name ?: tr("Unsorted"),
            store.variations(art.id),
            thumbnails,
            store,
            open = ::openStudio,
            rename = {
                ask(tr("Rename artwork"), art.name) {
                    store.renameArtwork(art.id, it)
                    render()
                }
            },
            move = {
                val places = listOf("" to tr("Unsorted")) + projects.map { it.id to it.name }
                choiceSheet(
                    tr("Move to"),
                    places.map { (id, name) ->
                        SheetChoice(name) {
                            store.moveArtwork(art.id, id)
                            render()
                        }
                    },
                )
            },
            duplicate = {
                store.duplicateArtwork(art.id)
                render()
            },
            variation = {
                store.makeVariation(art.id)
                render()
            },
            delete = {
                confirm(tr("Delete %1?", art.name), tr("You can't undo this.")) {
                    store.deleteArtwork(art.id)
                    render()
                }
            },
        )
    }

    private fun projectOptions(project: Project) {
        choiceSheet(
            project.name,
            listOf(
                SheetChoice(tr("Rename"), R.drawable.ic_rename) {
                    ask(tr("Rename sketchbook"), project.name) {
                        store.renameProject(project.id, it)
                        render()
                    }
                },
                SheetChoice(tr("Change cover color"), R.drawable.ic_palette) { chooseCover(project) },
                SheetChoice(tr("Delete"), R.drawable.ic_trash, danger = true) {
                    confirm(
                        tr("Delete %1?", project.name),
                        tr("Its artworks move to Unsorted. Its references are deleted."),
                    ) {
                        store.deleteProject(project.id)
                        selected = ALL
                        render()
                    }
                },
            ),
        )
    }

    /** Shows every cover color as a small sketchbook, ready to tap. */
    internal fun chooseCover(project: Project) {
        lateinit var dialog: android.app.Dialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            addView(wordmark(tr("Change cover color"), 24f))
            addView(
                GridLayout(context).apply {
                    columnCount = if (resources.displayMetrics.widthPixels >= dp(COVER_ROW_WIDTH)) SKETCHBOOK_COLORS.size else COVER_COLUMNS
                    SKETCHBOOK_COLORS.forEachIndexed { index, (cover, spine) ->
                        addView(
                            FrameLayout(context).apply {
                                background = rounded(cover, 10).apply {
                                    if (index == project.color % SKETCHBOOK_COLORS.size) setStroke(dp(3), ESPRESSO)
                                }
                                contentDescription = tr("Cover color %1", index + 1)
                                addView(View(context).apply { background = rounded(spine, 5) }, FrameLayout.LayoutParams(dp(9), -1))
                                setOnClickListener {
                                    store.recolorProject(project.id, index)
                                    dialog.dismiss()
                                    render()
                                }
                            },
                            GridLayout.LayoutParams().apply {
                                width = dp(COVER_SIZE)
                                height = dp(COVER_SIZE * 4 / 5)
                                setMargins(0, 0, dp(8), dp(8))
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) },
            )
        }
        dialog = sheet(content)
        dialog.show()
    }

    private fun renderReferences() {
        root.addView(
            quietChip(tr("Add references")) {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                    PICK_REFERENCES,
                )
            },
            spaced(),
        )
        val all = store.references(selected)
        if (all.isEmpty()) {
            root.addView(label(tr("Collect the images that inspire this sketchbook."), 15f, COCOA), spaced())
            return
        }
        val notes = store.referenceNotes(selected)
        val boards = referenceBoards(notes)
        if (referenceFilter.isNotEmpty() && referenceFilter != STARRED_FILTER && referenceFilter !in boards) referenceFilter = ""
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    LinearLayout(context).apply {
                        val filters = listOf(tr("All") to "", "★" to STARRED_FILTER) + boards.map { it to it }
                        filters.forEach { (name, value) ->
                            addView(
                                quietChip(name, referenceFilter == value) {
                                    referenceFilter = value
                                    render()
                                },
                                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
                            )
                        }
                    },
                )
            },
            spaced(),
        )
        val shown = filterReferences(all.map { it.name }, notes, referenceFilter).toSet()
        val cell = dp(180)
        val layout = grid(cell)
        all.filter { it.name in shown }.forEach { file ->
            layout.addView(referenceCell(file, cell, notes[file.name]?.starred == true))
        }
        root.addView(layout, spaced())
        root.addView(label(tr("Hold a reference to star, sort, move, or delete it."), 13f, COCOA), spaced())
    }

    private fun referenceCell(file: File, size: Int, starred: Boolean) = FrameLayout(this).apply {
        layoutParams = GridLayout.LayoutParams().apply {
            width = size
            height = size
            setMargins(0, 0, dp(12), dp(12))
        }
        addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(OAT, 10)
                clipToOutline = true
                contentDescription = tr("Reference")
                thumbnails.show(file, size, this)
                setOnLongClickListener {
                    referenceOptions(
                        store, selected, file,
                        changed = { render() },
                        moved = { render() },
                        delete = {
                            confirm(tr("Delete this reference?"), tr("You can't undo this.")) {
                                store.deleteReference(selected, file)
                                render()
                            }
                        },
                    )
                    true
                }
            },
        )
        if (starred) {
            addView(
                label("★", 18f, Color.WHITE).apply { setShadowLayer(5f, 0f, 1f, Color.BLACK) },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(8), 0) },
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == OPEN_PSD) {
            data.data?.let(::importPsd)
            return
        }
        if (requestCode == CREATE_BACKUP || requestCode == OPEN_BACKUP) {
            data.data?.let { runBackup(it, requestCode == OPEN_BACKUP) }
            return
        }
        if (requestCode != PICK_REFERENCES) return
        val single = listOfNotNull(data.data)
        val multiple = data.clipData?.let { clip ->
            (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        }.orEmpty()
        val project = selected
        background.execute {
            (multiple.ifEmpty { single }).forEach { uri -> runCatching { store.addReference(project, uri) } }
            runOnUiThread { render() }
        }
    }

    private fun backupOptions() {
        choiceSheet(
            tr("Backup"),
            listOf(
                SheetChoice(tr("Save a backup file"), R.drawable.ic_export) {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_TITLE, "Cappuccino backup.zip"),
                        CREATE_BACKUP,
                    )
                },
                SheetChoice(tr("Restore from a backup file"), R.drawable.ic_copy) {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip"),
                        OPEN_BACKUP,
                    )
                },
                SheetChoice(tr("Language"), R.drawable.ic_rename) { chooseLanguage() },
            ),
        )
    }

    private fun chooseLanguage() {
        choiceSheet(
            tr("Language"),
            Language.entries.map { language ->
                SheetChoice(language.label) {
                    (application as CappuccinoApp).saveLanguage(language)
                    render()
                }
            },
        )
    }

    private fun runBackup(uri: android.net.Uri, restoring: Boolean) {
        Toast.makeText(this, if (restoring) tr("Restoring your work") else tr("Saving your backup"), Toast.LENGTH_SHORT).show()
        background.execute {
            val done = runCatching {
                if (restoring) {
                    contentResolver.openInputStream(uri)!!.use(store::restore)
                } else {
                    contentResolver.openOutputStream(uri)!!.use(store::backup)
                }
            }.isSuccess
            runOnUiThread {
                val message = when {
                    done && restoring -> tr("Backup restored")
                    done -> tr("Backup saved")
                    restoring -> tr("Couldn't restore this backup")
                    else -> tr("Couldn't save your backup")
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun ask(title: String, current: String, done: (String) -> Unit) = askSheet(title, current, done)

    private fun confirm(title: String, message: String, action: () -> Unit) = confirmSheet(title, message, action)

    override fun onDestroy() {
        background.shutdown()
        thumbnails.close()
        super.onDestroy()
    }
}
