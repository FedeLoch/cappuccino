package art.cappuccino.paint

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

class PsdLayer(
    val name: String,
    val opacity: Float,
    val visible: Boolean,
    val blend: Int,
    val clipped: Boolean,
    val rgba: ByteArray,
)

private val BLEND_KEYS = listOf("norm", "mul ", "scrn", "lddg")

/** Writes layered PSD files with PackBits compression. Pixels arrive as premultiplied RGBA. */
class PsdWriter(private val width: Int, private val height: Int) {
    private class Channels(val data: Array<ByteArray>)

    private fun DataOutputStream.writeKey(key: String) = write(key.toByteArray(Charsets.US_ASCII))

    private fun packRow(row: ByteArray, out: ByteArrayOutputStream) {
        var index = 0
        while (index < row.size) {
            var run = 1
            while (index + run < row.size && run < 128 && row[index + run] == row[index]) run++
            if (run >= 2) {
                out.write(1 - run)
                out.write(row[index].toInt())
                index += run
                continue
            }
            val start = index
            index++
            while (index < row.size && index - start < 128) {
                if (index + 1 < row.size && row[index] == row[index + 1]) break
                index++
            }
            out.write(index - start - 1)
            out.write(row, start, index - start)
        }
    }

    /** Separates one image into channels, each with its row lengths and packed rows. */
    private fun encodeChannel(rgba: ByteArray, channel: Int, opaque: Boolean, withKind: Boolean): ByteArray {
        val lengths = ByteArrayOutputStream(height * 2)
        val body = ByteArrayOutputStream(width)
        val row = ByteArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val alpha = rgba[offset + 3].toInt() and 255
                val value = rgba[offset + channel].toInt() and 255
                row[x] = when {
                    channel == 3 -> value
                    opaque || alpha == 255 -> value
                    alpha == 0 -> 255
                    else -> minOf(255, value * 255 / alpha)
                }.toByte()
            }
            val before = body.size()
            packRow(row, body)
            val length = body.size() - before
            lengths.write(length shr 8)
            lengths.write(length)
        }
        val result = ByteArrayOutputStream(lengths.size() + body.size() + 2)
        if (withKind) {
            result.write(0)
            result.write(1)
        }
        lengths.writeTo(result)
        body.writeTo(result)
        return result.toByteArray()
    }

    private fun layerExtras(name: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(0)
        out.writeInt(0)
        val ascii = name.filter { it.code in 32..126 }.take(255).toByteArray(Charsets.US_ASCII)
        val padded = (ascii.size + 4) / 4 * 4
        out.writeByte(ascii.size)
        out.write(ascii)
        repeat(padded - 1 - ascii.size) { out.writeByte(0) }
        val unicode = ByteArrayOutputStream()
        val unicodeOut = DataOutputStream(unicode)
        unicodeOut.writeInt(name.length)
        name.forEach { unicodeOut.writeShort(it.code) }
        if (unicode.size() % 2 != 0) unicodeOut.writeByte(0)
        out.writeKey("8BIM")
        out.writeKey("luni")
        out.writeInt(unicode.size())
        unicode.writeTo(out)
        return bytes.toByteArray()
    }

    private fun layerInfo(layers: List<PsdLayer>): ByteArray {
        val records = ByteArrayOutputStream()
        val out = DataOutputStream(records)
        out.writeShort(layers.size)
        val channels = layers.map { layer ->
            Channels(
                arrayOf(
                    encodeChannel(layer.rgba, 3, false, true),
                    encodeChannel(layer.rgba, 0, false, true),
                    encodeChannel(layer.rgba, 1, false, true),
                    encodeChannel(layer.rgba, 2, false, true),
                ),
            )
        }
        layers.forEachIndexed { index, layer ->
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(height)
            out.writeInt(width)
            out.writeShort(4)
            listOf(-1, 0, 1, 2).forEachIndexed { position, id ->
                out.writeShort(id)
                out.writeInt(channels[index].data[position].size)
            }
            out.writeKey("8BIM")
            out.writeKey(BLEND_KEYS[layer.blend])
            out.writeByte((layer.opacity * 255f).toInt())
            out.writeByte(if (layer.clipped) 1 else 0)
            out.writeByte(if (layer.visible) 0 else 2)
            out.writeByte(0)
            val extras = layerExtras(layer.name)
            out.writeInt(extras.size)
            out.write(extras)
        }
        channels.forEach { layer -> layer.data.forEach(out::write) }
        if (records.size() % 2 != 0) out.writeByte(0)
        return records.toByteArray()
    }

    /** [layers] are listed bottom first, and [merged] is the flattened artwork. */
    fun write(target: OutputStream, layers: List<PsdLayer>, merged: ByteArray) {
        val out = DataOutputStream(target.buffered(1 shl 16))
        out.writeKey("8BPS")
        out.writeShort(1)
        repeat(6) { out.writeByte(0) }
        out.writeShort(3)
        out.writeInt(height)
        out.writeInt(width)
        out.writeShort(8)
        out.writeShort(3)
        out.writeInt(0)
        out.writeInt(0)

        val info = layerInfo(layers)
        // Layer and mask section: its length, the layer info, then an empty global mask.
        out.writeInt(info.size + 8)
        out.writeInt(info.size)
        out.write(info)
        out.writeInt(0)

        out.writeShort(1)
        val encoded = (0..2).map { encodeRowsOnly(merged, it) }
        encoded.forEach { out.write(it.first) }
        encoded.forEach { out.write(it.second) }
        out.flush()
    }

    private fun encodeRowsOnly(rgba: ByteArray, channel: Int): Pair<ByteArray, ByteArray> {
        val full = encodeChannel(rgba, channel, true, false)
        val split = height * 2
        return full.copyOfRange(0, split) to full.copyOfRange(split, full.size)
    }
}
