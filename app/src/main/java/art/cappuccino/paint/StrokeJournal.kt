package art.cappuccino.paint

import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val STROKE = 1
private const val UNDO = 2
private const val REDO = 3
private const val KEPT_SLOW_BYTES = 64 * 1024L

sealed interface JournalEntry {
    class Stroke(
        val target: Int,
        val brushId: String,
        val diameter: Float,
        val color: Int,
        val opacity: Float,
        val alphaLock: Boolean,
        val pressureGamma: Float,
        val samples: FloatArray,
    ) : JournalEntry

    data object Undo : JournalEntry
    data object Redo : JournalEntry
}

/**
 * Keeps finished strokes on disk between saves, so a crash loses none of them. Each save starts
 * a new numbered file, and its manifest names the last number it already contains.
 */
class StrokeJournal(private val dir: File) {
    private fun file(sequence: Int) = File(dir, "journal-$sequence.bin")

    private fun sequences() = dir.listFiles().orEmpty()
        .mapNotNull { Regex("journal-(\\d+)\\.bin").matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
        .sorted()

    fun latest() = sequences().lastOrNull() ?: 0

    fun append(sequence: Int, entry: JournalEntry) {
        dir.mkdirs()
        FileOutputStream(file(sequence), true).use { stream ->
            val out = DataOutputStream(stream.buffered())
            when (entry) {
                is JournalEntry.Stroke -> {
                    out.writeByte(STROKE)
                    out.writeInt(entry.target)
                    out.writeUTF(entry.brushId)
                    out.writeFloat(entry.diameter)
                    out.writeInt(entry.color)
                    out.writeFloat(entry.opacity)
                    out.writeBoolean(entry.alphaLock)
                    out.writeFloat(entry.pressureGamma)
                    out.writeInt(entry.samples.size)
                    entry.samples.forEach(out::writeFloat)
                }
                JournalEntry.Undo -> out.writeByte(UNDO)
                JournalEntry.Redo -> out.writeByte(REDO)
            }
            out.flush()
            stream.fd.sync()
        }
    }

    /** Reads everything newer than [sequence]. A record cut short by a crash ends its file. */
    fun read(sequence: Int): List<JournalEntry> = sequences().filter { it > sequence }.flatMap { number ->
        val entries = ArrayList<JournalEntry>()
        DataInputStream(file(number).inputStream().buffered()).use { input ->
            try {
                while (true) {
                    when (input.readByte().toInt()) {
                        STROKE -> entries += JournalEntry.Stroke(
                            input.readInt(), input.readUTF(), input.readFloat(), input.readInt(), input.readFloat(),
                            input.readBoolean(), input.readFloat(),
                            FloatArray(input.readInt()).also { samples ->
                                for (index in samples.indices) samples[index] = input.readFloat()
                            },
                        )
                        UNDO -> entries += JournalEntry.Undo
                        REDO -> entries += JournalEntry.Redo
                        else -> break
                    }
                }
            } catch (_: EOFException) {
                // The last record never finished writing, so it wasn't a finished stroke.
            }
        }
        entries
    }

    fun clear(upTo: Int) = sequences().filter { it <= upTo }.forEach { file(it).delete() }
}

/**
 * Counts saves still in progress, so reopening an artwork right after leaving it waits for the
 * save and loads the newest version.
 */
object PendingSaves {
    private const val WAIT_MS = 4000L
    private val lock = Object()
    private var running = 0

    fun started() = synchronized(lock) { running++ }

    fun finished() = synchronized(lock) {
        running--
        lock.notifyAll()
    }

    fun await() = synchronized(lock) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (running > 0) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            lock.wait(left)
        }
    }
}

/** Keeps a short file of slow moments, so a freeze can still be studied long after it happens. */
object SlowLog {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var file: File? = null

    fun add(name: String, milliseconds: Long) {
        val target = file ?: return
        val time = SystemClock.elapsedRealtime()
        worker.execute {
            if (target.length() > KEPT_SLOW_BYTES) target.delete()
            target.appendText("$time $name $milliseconds ms\n")
        }
    }
}
