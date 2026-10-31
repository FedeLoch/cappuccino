package art.cappuccino.paint

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

private const val ALL = "all"
private const val PICK_REFERENCES = 42

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
                setBackgroundColor(0xFF1F1E1D.toInt())
                isFillViewport = true
                addView(root)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        val projects = store.projects()
        if (selected != ALL && selected.isNotEmpty() && projects.none { it.id == selected }) selected = ALL
        root.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Cappuccino", 26f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(chip("New artwork", true) { openStudio(null) })
            },
        )
        val tabs = ArrayList<View>()
        tabs += chip("All", selected == ALL) { select(ALL) }
        tabs += chip("Unsorted", selected.isEmpty()) { select("") }
        projects.forEach { project ->
            tabs += chip(project.name, selected == project.id) { select(project.id) }.apply {
                setOnLongClickListener {
                    projectOptions(project)
                    true
                }
            }
        }
        tabs += chip("New project") {
            ask("New project", "") {
                selected = store.createProject(it).id
                render()
            }
        }
        root.addView(row(tabs), spaced())

        if (selected != ALL) {
            root.addView(
                row(
                    listOf(
                        chip("Artworks", !showingReferences) { showReferences(false) },
                        chip("References", showingReferences) { showReferences(true) },
                    ),
                ),
                spaced(),
            )
        }
        if (selected != ALL && showingReferences) renderReferences() else renderArtworks()
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
                    addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
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
            root.addView(label("Start your first artwork here.", 16f, MUTED_TEXT), spaced())
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
                background = rounded(SURFACE_RAISED, 8)
                clipToOutline = true
                contentDescription = art.name
                setImageBitmap(BitmapFactory.decodeFile(store.thumbnail(art.id).path))
            },
            LinearLayout.LayoutParams(-1, width * 2 / 3),
        )
        addView(label(art.name), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(label(DateUtils.getRelativeTimeSpanString(art.updated).toString(), 12f, MUTED_TEXT))
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
            .setItems(arrayOf("Rename", "Delete")) { _, choice ->
                if (choice == 0) {
                    ask("Rename project", project.name) {
                        store.renameProject(project.id, it)
                        render()
                    }
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
        if (requestCode != PICK_REFERENCES || resultCode != RESULT_OK || data == null) return
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
