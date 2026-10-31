package art.cappuccino.paint

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StudioFeatureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun timelapseProducesAPlayableVideo() {
        val folder = File(context.cacheDir, "frames").apply { mkdirs() }
        val frames = (0 until 8).map { index ->
            val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(index * 30, 80, 120))
            File(folder, "$index.jpg").also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        }
        val video = File(context.cacheDir, "test.mp4")
        video.outputStream().use { TimelapseEncoder.encode(frames, 960, 640, it.fd) }

        val reader = MediaMetadataRetriever()
        reader.setDataSource(video.path)
        assertEquals("960", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        assertTrue("video should have length", duration > 500)
        reader.release()
    }

    @Test
    fun customCanvasSizeOpens() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_WIDTH, 1080)
            .putExtra(MainActivity.EXTRA_HEIGHT, 1920)
        ActivityScenario.launch<MainActivity>(intent).use {
            SystemClock.sleep(2500)
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            assertTrue("portrait paper should show", Color.red(shot.getPixel(shot.width / 2, shot.height / 2)) > 200)
        }
    }
}
