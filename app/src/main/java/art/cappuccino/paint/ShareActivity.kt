package art.cappuccino.paint

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.util.concurrent.Executors

/** Receives images shared from other apps and files them as project references. */
class ShareActivity : Activity() {
    private val background = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val images = sharedImages()
        if (images.isEmpty()) {
            finish()
            return
        }
        val store = ArtworkStore(this)
        val projects = store.projects()
        val names = listOf("Unsorted") + projects.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Add to references")
            .setItems(names.toTypedArray()) { _, index ->
                val project = projects.getOrNull(index - 1)?.id.orEmpty()
                background.execute {
                    val added = images.count { uri ->
                        runCatching { store.addReference(project, uri) }.getOrNull() != null
                    }
                    runOnUiThread {
                        val message = if (added == images.size) "References added" else "Couldn't add every image"
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun sharedImages(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    override fun onDestroy() {
        background.shutdown()
        super.onDestroy()
    }
}
