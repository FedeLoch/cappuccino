package art.cappuccino.paint

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArtworkDocument(
    val id: String,
    var name: String,
    var projectId: String,
    var activeLayer: Int,
    var nextLayerId: Int,
    val layers: MutableList<LayerInfo>,
    var perspective: List<Float> = emptyList(),
    var references: List<ReferenceState> = emptyList(),
)

class ArtworkSnapshot(
    val layers: List<LayerInfo>,
    val activeLayer: Int,
    val nextLayerId: Int,
    val pixels: Map<Int, ByteBuffer>,
    val thumbnail: ByteBuffer,
)

/**
 * Keeps artwork on app storage. Saves write new files first and swap the manifest last, and
 * the previous manifest is kept, so a crash during a save leaves the last good version.
 */
class ArtworkStore(context: Context) {
    private val root = context.filesDir
    private val resolver = context.contentResolver
    private val artworks = File(root, "artworks").apply { mkdirs() }
    private val references = File(root, "references").apply { mkdirs() }
    private val projectsFile = File(root, "projects.json")
    private val palettesFile = File(root, "palettes.json")

    private fun artworkDir(id: String) = File(artworks, id)

    fun thumbnail(id: String) = File(artworkDir(id), THUMBNAIL)

    fun palettes(): List<Palette> = readPalettes(palettesFile)

    private fun readPalettes(file: File): List<Palette> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val colors = item.getJSONArray("colors")
            Palette(
                item.getString("id"),
                item.getString("name"),
                (0 until colors.length()).map { colors.getInt(it) },
            )
        }
    }

    private fun writePalettes(palettes: List<Palette>) {
        val array = JSONArray()
        palettes.forEach { palette ->
            array.put(
                JSONObject()
                    .put("id", palette.id)
                    .put("name", palette.name)
                    .put("colors", JSONArray(palette.colors)),
            )
        }
        writeAtomically(palettesFile, array.toString().toByteArray())
    }

    fun addPalette(name: String, colors: List<Int>) {
        writePalettes(listOf(Palette(UUID.randomUUID().toString(), name, colors)) + palettes())
    }

    fun deletePalette(id: String) = writePalettes(palettes().filter { it.id != id })

    fun projects(): List<Project> = readProjects(projectsFile)

    private fun readProjects(file: File): List<Project> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            Project(item.getString("id"), item.getString("name"))
        }
    }

    private fun writeProjects(projects: List<Project>) {
        val array = JSONArray()
        projects.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        writeAtomically(projectsFile, array.toString().toByteArray())
    }

    fun createProject(name: String): Project {
        val project = Project(UUID.randomUUID().toString(), name)
        writeProjects(projects() + project)
        return project
    }

    fun renameProject(id: String, name: String) {
        writeProjects(projects().map { if (it.id == id) Project(id, name) else it })
    }

    /** Removes the project and its references. Its artworks become unsorted. */
    fun deleteProject(id: String) {
        artworks().filter { it.projectId == id }.forEach { moveArtwork(it.id, "") }
        writeProjects(projects().filter { it.id != id })
        referenceDir(id).deleteRecursively()
    }

    fun artworks(): List<ArtworkInfo> =
        artworks.listFiles().orEmpty().mapNotNull { dir ->
            readManifest(dir.name)?.let {
                ArtworkInfo(
                    dir.name,
                    it.optString("name", "Untitled"),
                    it.optString("project", ""),
                    it.optLong("updated"),
                )
            }
        }.sortedByDescending { it.updated }

    private fun readManifest(id: String): JSONObject? {
        val dir = artworkDir(id)
        for (name in listOf(MANIFEST, PREVIOUS_MANIFEST)) {
            val parsed = runCatching { JSONObject(File(dir, name).readText()) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun updateManifest(id: String, change: (JSONObject) -> Unit) {
        val manifest = readManifest(id) ?: return
        change(manifest)
        writeAtomically(File(artworkDir(id), MANIFEST), manifest.toString().toByteArray())
    }

    fun renameArtwork(id: String, name: String) = updateManifest(id) { it.put("name", name) }

    fun moveArtwork(id: String, projectId: String) =
        updateManifest(id) { it.put("project", projectId) }

    fun deleteArtwork(id: String) {
        artworkDir(id).deleteRecursively()
    }

    fun duplicateArtwork(id: String) {
        val copy = UUID.randomUUID().toString()
        artworkDir(id).copyRecursively(artworkDir(copy))
        updateManifest(copy) {
            it.put("name", it.optString("name", "Untitled") + " copy")
            it.put("updated", System.currentTimeMillis())
        }
    }

    fun load(id: String): ArtworkDocument? {
        val manifest = readManifest(id) ?: return null
        val array = manifest.getJSONArray("layers")
        val layers = (0 until array.length()).map {
            val item = array.getJSONObject(it)
            LayerInfo(
                item.getInt("id"),
                item.getString("name"),
                item.getDouble("opacity").toFloat(),
                item.getBoolean("visible"),
                item.getInt("blend"),
                item.optBoolean("alphaLock"),
                item.optString("file").ifEmpty { null },
                item.optBoolean("clip"),
            )
        }.toMutableList()
        return ArtworkDocument(
            id,
            manifest.optString("name", "Untitled"),
            manifest.optString("project", ""),
            manifest.getInt("activeLayer"),
            manifest.getInt("nextLayerId"),
            layers,
            manifest.optJSONArray("perspective")?.let { points ->
                (0 until points.length()).map { points.getDouble(it).toFloat() }
            }.orEmpty(),
            manifest.optJSONArray("references")?.let { items ->
                (0 until items.length()).map { parseReference(items.getJSONObject(it)) }
            }.orEmpty(),
        )
    }

    private fun parseReference(item: JSONObject) = ReferenceState(
        item.getString("file"),
        item.getInt("x"),
        item.getInt("y"),
        item.getInt("width"),
        item.getDouble("zoom").toFloat(),
        item.getDouble("panX").toFloat(),
        item.getDouble("panY").toFloat(),
        item.getBoolean("grayscale"),
        item.getBoolean("flipped"),
        item.getDouble("opacity").toFloat(),
        item.getBoolean("pinned"),
    )

    private fun referenceJson(state: ReferenceState) = JSONObject()
        .put("file", state.file)
        .put("x", state.x)
        .put("y", state.y)
        .put("width", state.width)
        .put("zoom", state.zoom.toDouble())
        .put("panX", state.panX.toDouble())
        .put("panY", state.panY.toDouble())
        .put("grayscale", state.grayscale)
        .put("flipped", state.flipped)
        .put("opacity", state.opacity.toDouble())
        .put("pinned", state.pinned)

    fun readLayer(artworkId: String, file: String): ByteBuffer {
        val buffer = layerBuffer()
        InflaterInputStream(File(artworkDir(artworkId), file).inputStream()).use { input ->
            val chunk = ByteArray(1 shl 16)
            while (buffer.hasRemaining()) {
                val count = input.read(chunk, 0, minOf(chunk.size, buffer.remaining()))
                if (count < 0) break
                buffer.put(chunk, 0, count)
            }
        }
        buffer.rewind()
        return buffer
    }

    fun save(document: ArtworkDocument, snapshot: ArtworkSnapshot) {
        val dir = artworkDir(document.id).apply { mkdirs() }
        val generation = System.currentTimeMillis()
        val layers = JSONArray()
        for (layer in snapshot.layers) {
            snapshot.pixels[layer.id]?.let { pixels ->
                layer.file = "layer-${layer.id}-$generation.bin"
                writeLayer(File(dir, layer.file!!), pixels)
            }
            layers.put(
                JSONObject()
                    .put("id", layer.id)
                    .put("name", layer.name)
                    .put("opacity", layer.opacity.toDouble())
                    .put("visible", layer.visible)
                    .put("blend", layer.blend)
                    .put("alphaLock", layer.alphaLock)
                    .put("clip", layer.clip)
                    .put("file", layer.file ?: ""),
            )
        }
        writeThumbnail(thumbnail(document.id), snapshot.thumbnail)

        val manifest = JSONObject()
            .put("version", 1)
            .put("name", document.name)
            .put("project", document.projectId)
            .put("width", CANVAS_WIDTH)
            .put("height", CANVAS_HEIGHT)
            .put("activeLayer", snapshot.activeLayer)
            .put("nextLayerId", snapshot.nextLayerId)
            .put("updated", generation)
            .put("layers", layers)
            .put("perspective", JSONArray(document.perspective.map { it.toDouble() }))
            .put("references", JSONArray(document.references.map(::referenceJson)))
        val current = File(dir, MANIFEST)
        if (current.exists()) current.renameTo(File(dir, PREVIOUS_MANIFEST))
        writeAtomically(current, manifest.toString().toByteArray())
        removeUnusedFiles(dir)
    }

    private fun removeUnusedFiles(dir: File) {
        val used = listOf(MANIFEST, PREVIOUS_MANIFEST).flatMap { name ->
            val manifest = runCatching { JSONObject(File(dir, name).readText()) }.getOrNull()
                ?: return@flatMap emptyList()
            val layers = manifest.getJSONArray("layers")
            (0 until layers.length()).map { layers.getJSONObject(it).optString("file") }
        }.toSet()
        dir.listFiles().orEmpty()
            .filter { it.name.startsWith("layer-") && it.name !in used }
            .forEach { it.delete() }
    }

    private fun writeLayer(file: File, pixels: ByteBuffer) {
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { output ->
            val deflater = Deflater(Deflater.BEST_SPEED)
            val stream = DeflaterOutputStream(output, deflater, 1 shl 16)
            val chunk = ByteArray(1 shl 16)
            pixels.rewind()
            while (pixels.hasRemaining()) {
                val count = minOf(chunk.size, pixels.remaining())
                pixels.get(chunk, 0, count)
                stream.write(chunk, 0, count)
            }
            stream.finish()
            deflater.end()
            output.fd.sync()
        }
        temporary.renameTo(file)
    }

    private fun writeThumbnail(file: File, pixels: ByteBuffer) {
        val bitmap = Bitmap.createBitmap(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        temporary.renameTo(file)
        bitmap.recycle()
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }
        temporary.renameTo(file)
    }

    private fun referenceDir(projectId: String) =
        File(references, projectId.ifEmpty { "unsorted" }).apply { mkdirs() }

    fun references(projectId: String): List<File> =
        referenceDir(projectId).listFiles().orEmpty().sortedByDescending { it.lastModified() }

    fun addReference(projectId: String, uri: Uri): File? {
        val target = File(referenceDir(projectId), UUID.randomUUID().toString())
        val input = resolver.openInputStream(uri) ?: return null
        input.use { source -> target.outputStream().use(source::copyTo) }
        return target
    }

    fun exportPsd(name: String, layers: List<PsdLayer>, merged: ByteArray): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.psd")
            put(MediaStore.Downloads.MIME_TYPE, "image/vnd.adobe.photoshop")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Cappuccino")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val output = resolver.openOutputStream(uri) ?: return false
        output.use { PsdWriter(CANVAS_WIDTH, CANVAS_HEIGHT).write(it, layers, merged) }
        return true
    }

    /** Packs all artworks, references, projects, and palettes into one zip file. */
    fun backup(output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            listOf(artworks, references, projectsFile, palettesFile).forEach { item ->
                item.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).path))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Adds a backup's contents. Existing files are kept, and projects are combined. */
    fun restore(input: InputStream) {
        val incoming = File(root, "restore.tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(incoming, entry.name)
                check(target.canonicalPath.startsWith(incoming.canonicalPath + File.separator)) {
                    "Invalid backup"
                }
                target.parentFile?.mkdirs()
                if (!entry.isDirectory) target.outputStream().use(zip::copyTo)
                zip.closeEntry()
            }
        }
        val existing = projects()
        val added = readProjects(File(incoming, projectsFile.name))
            .filter { project -> existing.none { it.id == project.id } }
        val savedPalettes = palettes()
        val addedPalettes = readPalettes(File(incoming, palettesFile.name))
            .filter { palette -> savedPalettes.none { it.id == palette.id } }
        listOf("artworks", "references").forEach { name ->
            File(incoming, name).takeIf { it.exists() }?.copyRecursively(File(root, name), overwrite = false) { _, error ->
                if (error is FileAlreadyExistsException) OnErrorAction.SKIP else throw error
            }
        }
        writeProjects(existing + added)
        writePalettes(savedPalettes + addedPalettes)
        incoming.deleteRecursively()
    }

    fun exportPng(name: String, pixels: ByteBuffer): Boolean {
        val bitmap = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cappuccino")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val written = uri?.let { target ->
            resolver.openOutputStream(target)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } ?: false
        bitmap.recycle()
        return written
    }

    companion object {
        const val THUMBNAIL_WIDTH = 480
        const val THUMBNAIL_HEIGHT = 320
        private const val MANIFEST = "manifest.json"
        private const val PREVIOUS_MANIFEST = "manifest.prev.json"
        private const val THUMBNAIL = "thumbnail.png"

        fun layerBuffer(): ByteBuffer = pixelBuffer(CANVAS_WIDTH, CANVAS_HEIGHT)

        fun pixelBuffer(width: Int, height: Int): ByteBuffer =
            ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }
}
