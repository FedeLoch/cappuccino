package art.cappuccino.paint

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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
private const val CREATE_BACKUP = 43
private const val OPEN_BACKUP = 44
private const val OPEN_PSD = 45
private const val CONTINUE_WIDTH = 380
private const val CONTINUE_HEIGHT = 250

/** Shows projects, the artworks inside them, and each project's reference library. */
class GalleryActivity : Activity() {
    private lateinit var store: ArtworkStore
    private lateinit var root: LinearLayout
    private val background = Executors.newSingleThreadExecutor()
    private var selected = ALL
    private var showingReferences = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ArtworkStore(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(ESPRESSO)
                isFillViewport = true
                addView(root)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun quietChip(content: String, selected: Boolean = false, onClick: () -> Unit) =
        chip(content, selected, onClick).apply {
            if (!selected) background = rounded(ROAST, 20)
            setTextColor(FOAM)
        }

    private fun wordmark(content: String, size: Float) = label(content, size, FOAM).apply {
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
                addView(quietChip("Backup") { backupOptions() })
                addView(
                    quietChip("Import PSD") { pickPsd() },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                )
                addView(
                    chip("New artwork", true) { chooseCanvas() },
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
        shelf += sketchbook("Unsorted", artworks.count { it.projectId.isEmpty() }, null) { select("") }
        shelf += newSketchbook()
        root.addView(
            LinearLayout(this).apply {
                artworks.firstOrNull()?.let { latest ->
                    addView(
                        continueCard(latest),
                        LinearLayout.LayoutParams(dp(CONTINUE_WIDTH), dp(CONTINUE_HEIGHT)).apply { marginEnd = dp(20) },
                    )
                }
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label("Your sketchbooks", 14f, LATTE))
                        addView(row(shelf), spaced())
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            },
            spaced(),
        )
        if (artworks.size > 1) {
            root.addView(label("Recent", 14f, LATTE), spaced())
            val cell = dp(220)
            val layout = grid(cell)
            artworks.drop(1).forEach { layout.addView(card(it, cell)) }
            root.addView(layout, spaced())
        }
    }

    private fun continueCard(art: ArtworkInfo) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(FOAM, 14)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.WHITE, 8)
                clipToOutline = true
                contentDescription = art.name
                setImageBitmap(BitmapFactory.decodeFile(store.thumbnail(art.id).path))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        addView(
            label("Continue ${art.name}", 15f, ESPRESSO),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) },
        )
        setOnClickListener { openStudio(art.id) }
        setOnLongClickListener {
            artworkOptions(art)
            true
        }
    }

    private fun greeting() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val preferences = getSharedPreferences("studio", MODE_PRIVATE)
        addView(
            CompanionView(context).apply {
                mascot = Mascot.valueOf(preferences.getString("mascot", null) ?: Mascot.COFFEE_DROP.name)
                contentDescription = "Your companion"
            },
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning."
            hour < 19 -> "Good afternoon."
            else -> "Good evening."
        }
        addView(
            label("$greeting Ready for another layer?", 14f, LATTE),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
    }

    private fun sketchbook(name: String, count: Int, color: Int?, onClick: () -> Unit) = FrameLayout(this).apply {
        val (cover, spine) = color?.let { SKETCHBOOK_COLORS[it % SKETCHBOOK_COLORS.size] } ?: (ROAST to SURFACE_RAISED)
        background = rounded(cover, 12)
        addView(View(context).apply { background = rounded(spine, 6) }, FrameLayout.LayoutParams(dp(10), -1))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(14), dp(14), dp(14))
                addView(label(name, 16f, Color.WHITE))
                addView(label(if (count == 1) "1 artwork" else "$count artworks", 12f, Color.WHITE))
            },
        )
        layoutParams = LinearLayout.LayoutParams(dp(170), dp(104))
        minimumWidth = dp(170)
        minimumHeight = dp(104)
        contentDescription = "Open $name"
        setOnClickListener { onClick() }
    }

    private fun newSketchbook() = label("New sketchbook", 14f, LATTE).apply {
        gravity = Gravity.CENTER
        minWidth = dp(170)
        minHeight = dp(104)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(2), LATTE, dp(8).toFloat(), dp(6).toFloat())
        }
        setOnClickListener {
            ask("New sketchbook", "") {
                selected = store.createProject(it).id
                render()
            }
        }
    }

    private fun renderSketchbook(projects: List<Project>) {
        val project = projects.firstOrNull { it.id == selected }
        root.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(quietChip("Back") { select(ALL) })
                addView(
                    wordmark(project?.name ?: "Unsorted", 24f),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) },
                )
            },
            spaced(),
        )
        root.addView(
            row(
                listOf(
                    quietChip("Artworks", !showingReferences) { showReferences(false) },
                    quietChip("References", showingReferences) { showReferences(true) },
                ),
            ),
            spaced(),
        )
        if (showingReferences) renderReferences() else renderArtworks()
    }

    private fun chooseCanvas() {
        val sizes = listOf(
            Triple("Default, 4096 x 2732", 4096, 2732),
            Triple("Square, 3000 x 3000", 3000, 3000),
            Triple("A4 at 300 dpi, 2480 x 3508", 2480, 3508),
            Triple("A3 at 300 dpi, 3508 x 4961", 3508, 4961),
            Triple("Phone story, 1080 x 1920", 1080, 1920),
            Triple("Wide video, 3840 x 2160", 3840, 2160),
        )
        AlertDialog.Builder(this)
            .setTitle("New artwork")
            .setItems((sizes.map { it.first } + "Custom size").toTypedArray()) { _, choice ->
                val size = sizes.getOrNull(choice)
                if (size != null) openNew(size.second, size.third) else askCustomSize()
            }
            .show()
    }

    private fun askCustomSize() {
        val width = EditText(this).apply {
            hint = "Width in pixels"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val height = EditText(this).apply {
            hint = "Height in pixels"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Custom size")
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(8), dp(24), 0)
                    addView(width)
                    addView(height)
                },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val w = width.text.toString().toIntOrNull() ?: 0
                val h = height.text.toString().toIntOrNull() ?: 0
                if (w in 64..MAX_CANVAS_SIDE && h in 64..MAX_CANVAS_SIDE) {
                    openNew(w, h)
                } else {
                    Toast.makeText(this, "Use sizes from 64 to $MAX_CANVAS_SIDE pixels", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun openNew(width: Int, height: Int) {
        val project = if (selected == ALL) "" else selected
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PROJECT, project)
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
        Toast.makeText(this, "Importing PSD", Toast.LENGTH_SHORT).show()
        background.execute {
            val failure = runCatching {
                val image = PsdReader.read(contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                check(image.width <= MAX_CANVAS_SIDE && image.height <= MAX_CANVAS_SIDE) { "too large" }
                store.importPsd(image, "Imported PSD", project)
            }.exceptionOrNull()
            runOnUiThread {
                if (failure != null) {
                    Toast.makeText(this, "Couldn't import this PSD. Use an 8-bit RGB file.", Toast.LENGTH_LONG).show()
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
            root.addView(label("Start your first artwork here.", 16f, LATTE), spaced())
            return
        }
        val cell = dp(240)
        val layout = grid(cell)
        artworks.forEach { layout.addView(card(it, cell)) }
        root.addView(layout, spaced())
    }

    private fun card(art: ArtworkInfo, width: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = GridLayout.LayoutParams().apply {
            this.width = width
            setMargins(0, 0, dp(12), dp(16))
        }
        addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.WHITE, 8)
                clipToOutline = true
                contentDescription = art.name
                setImageBitmap(BitmapFactory.decodeFile(store.thumbnail(art.id).path))
            },
            LinearLayout.LayoutParams(-1, width * 2 / 3),
        )
        addView(label(art.name, 14f, FOAM), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(label(DateUtils.getRelativeTimeSpanString(art.updated).toString(), 12f, LATTE))
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

    private fun artworkOptions(art: ArtworkInfo) {
        val projects = store.projects()
        AlertDialog.Builder(this)
            .setTitle(art.name)
            .setItems(arrayOf("Rename", "Move to project", "Duplicate", "Delete")) { _, choice ->
                when (choice) {
                    0 -> ask("Rename artwork", art.name) {
                        store.renameArtwork(art.id, it)
                        render()
                    }
                    1 -> {
                        val names = listOf("Unsorted") + projects.map { it.name }
                        AlertDialog.Builder(this)
                            .setTitle("Move to")
                            .setItems(names.toTypedArray()) { _, index ->
                                store.moveArtwork(art.id, projects.getOrNull(index - 1)?.id.orEmpty())
                                render()
                            }
                            .show()
                    }
                    2 -> {
                        store.duplicateArtwork(art.id)
                        render()
                    }
                    3 -> confirm("Delete ${art.name}?", "You can't undo this.") {
                        store.deleteArtwork(art.id)
                        render()
                    }
                }
            }
            .show()
    }

    private fun projectOptions(project: Project) {
        AlertDialog.Builder(this)
            .setTitle(project.name)
            .setItems(arrayOf("Rename", "Change cover color", "Delete")) { _, choice ->
                if (choice == 0) {
                    ask("Rename sketchbook", project.name) {
                        store.renameProject(project.id, it)
                        render()
                    }
                } else if (choice == 1) {
                    store.recolorProject(project.id)
                    render()
                } else {
                    confirm(
                        "Delete ${project.name}?",
                        "Its artworks move to Unsorted. Its references are deleted.",
                    ) {
                        store.deleteProject(project.id)
                        selected = ALL
                        render()
                    }
                }
            }
            .show()
    }

    private fun renderReferences() {
        root.addView(
            chip("Add references") {
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
        val files = store.references(selected)
        if (files.isEmpty()) {
            root.addView(label("Collect images for this project here.", 16f, MUTED_TEXT), spaced())
            return
        }
        val cell = dp(180)
        val layout = grid(cell)
        files.forEach { file -> layout.addView(referenceCell(file, cell)) }
        root.addView(layout, spaced())
    }

    private fun referenceCell(file: File, size: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Reference"
        layoutParams = GridLayout.LayoutParams().apply {
            width = size
            height = size
            setMargins(0, 0, dp(12), dp(12))
        }
        setImageBitmap(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }))
        setOnLongClickListener {
            confirm("Delete this reference?", "You can't undo this.") {
                file.delete()
                render()
            }
            true
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
        AlertDialog.Builder(this)
            .setTitle("Backup")
            .setItems(arrayOf("Save a backup file", "Restore from a backup file")) { _, choice ->
                if (choice == 0) {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_TITLE, "Cappuccino backup.zip"),
                        CREATE_BACKUP,
                    )
                } else {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip"),
                        OPEN_BACKUP,
                    )
                }
            }
            .show()
    }

    private fun runBackup(uri: android.net.Uri, restoring: Boolean) {
        Toast.makeText(this, if (restoring) "Restoring your work" else "Saving your backup", Toast.LENGTH_SHORT).show()
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
                    done && restoring -> "Backup restored"
                    done -> "Backup saved"
                    restoring -> "Couldn't restore this backup"
                    else -> "Couldn't save your backup"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun ask(title: String, current: String, done: (String) -> Unit) {
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

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ -> action() }
            .show()
    }

    override fun onDestroy() {
        background.shutdown()
        super.onDestroy()
    }
}
