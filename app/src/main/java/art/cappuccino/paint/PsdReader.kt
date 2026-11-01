package art.cappuccino.paint

import java.nio.ByteBuffer

class PsdImage(
    val width: Int,
    val height: Int,
    val layers: List<PsdImport>,
    val groups: List<String> = emptyList(),
)

class PsdImport(
    val name: String,
    val opacity: Float,
    val visible: Boolean,
    val blend: Int,
    val clipped: Boolean,
    /** Premultiplied RGBA for the whole canvas. */
    val rgba: ByteArray,
    /** One-based index into the image's groups, or 0. */
    var group: Int = 0,
)

private class ChannelRecord(val id: Int, val length: Int)

private class LayerRecord(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val channels: List<ChannelRecord>,
    val blend: String,
    val opacity: Int,
    val clipped: Boolean,
    val visible: Boolean,
    val name: String,
    val isDivider: Boolean,
    val section: Int,
)

/** Reads 8-bit RGB PSD files. One level of group folders is kept, and nested ones join it. */
object PsdReader {
    private val BLENDS = mapOf(
        "norm" to 0, "mul " to 1, "scrn" to 2, "lddg" to 3, "dark" to 4, "lite" to 5, "sLit" to 6, "fsub" to 7, "smud" to 8,
    )

    fun read(data: ByteArray): PsdImage {
        val input = ByteBuffer.wrap(data)
        require(ascii(input, 4) == "8BPS" && input.short.toInt() == 1) { "Not a PSD file" }
        input.position(input.position() + 6)
        val colorChannels = input.short.toInt()
        val height = input.int
        val width = input.int
        require(input.short.toInt() == 8 && input.short.toInt() == 3) { "Only 8-bit RGB is supported" }
        skipSection(input)
        skipSection(input)
        val sectionLength = input.int
        val sectionEnd = input.position() + sectionLength
        val groups = ArrayList<String>()
        val layers = if (sectionLength > 4) readLayers(input, width, height, groups) else emptyList()
        input.position(sectionEnd)
        if (layers.isNotEmpty()) return PsdImage(width, height, layers, groups)
        return PsdImage(width, height, listOf(readMerged(input, width, height, colorChannels)))
    }

    private fun ascii(input: ByteBuffer, count: Int): String {
        val bytes = ByteArray(count).also(input::get)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun skipSection(input: ByteBuffer) {
        val length = input.int
        input.position(input.position() + length)
    }

    private fun readLayers(input: ByteBuffer, width: Int, height: Int, groups: MutableList<String>): List<PsdImport> {
        input.int
        val count = kotlin.math.abs(input.short.toInt())
        val records = List(count) { readRecord(input) }
        val layers = ArrayList<PsdImport>()
        var depth = 0
        var firstInGroup = 0
        for (record in records) {
            if (record.section == 3) {
                if (depth++ == 0) firstInGroup = layers.size
            } else if (record.isDivider && depth > 0 && --depth == 0) {
                groups += record.name
                for (index in firstInGroup until layers.size) layers[index].group = groups.size
            }
            val channels = record.channels.associate { channel ->
                val start = input.position()
                val pixels = if (record.isDivider) null else readChannel(input, record, channel)
                input.position(start + channel.length)
                channel.id to pixels
            }
            if (!record.isDivider) layers += assemble(record, channels, width, height)
        }
        return layers
    }

    private fun readRecord(input: ByteBuffer): LayerRecord {
        val top = input.int
        val left = input.int
        val bottom = input.int
        val right = input.int
        val channels = List(input.short.toInt()) { ChannelRecord(input.short.toInt(), input.int) }
        ascii(input, 4)
        val blend = ascii(input, 4)
        val opacity = input.get().toInt() and 255
        val clipped = input.get().toInt() != 0
        val flags = input.get().toInt()
        input.get()
        val extraEnd = input.int + input.position()
        skipSection(input)
        skipSection(input)
        val nameLength = input.get().toInt() and 255
        var name = ascii(input, nameLength)
        input.position(input.position() + (4 - (nameLength + 1) % 4) % 4)
        var divider = false
        var section = 0
        while (input.position() + 12 <= extraEnd) {
            ascii(input, 4)
            val key = ascii(input, 4)
            val length = input.int
            val next = input.position() + length + length % 2
            if (key == "lsct" || key == "lsdk") {
                section = input.int
                divider = section != 0
            }
            if (key == "luni") {
                val size = input.int
                name = String(CharArray(size) { input.char })
            }
            input.position(minOf(next, extraEnd))
        }
        input.position(extraEnd)
        return LayerRecord(
            top, left, bottom, right, channels, blend, opacity, clipped, flags and 2 == 0, name, divider, section,
        )
    }

    private fun readChannel(input: ByteBuffer, record: LayerRecord, channel: ChannelRecord): ByteArray {
        val width = record.right - record.left
        val height = record.bottom - record.top
        val compression = input.short.toInt()
        val pixels = ByteArray(width * height)
        if (width == 0 || height == 0 || channel.id < -1) return pixels
        when (compression) {
            0 -> input.get(pixels)
            1 -> {
                val lengths = IntArray(height) { input.short.toInt() and 0xFFFF }
                for (row in 0 until height) {
                    val next = input.position() + lengths[row]
                    unpack(input, pixels, row * width, width)
                    input.position(next)
                }
            }
            else -> error("Unsupported PSD compression")
        }
        return pixels
    }

    private fun unpack(input: ByteBuffer, target: ByteArray, start: Int, count: Int) {
        var offset = start
        val end = start + count
        while (offset < end) {
            val code = input.get().toInt()
            if (code >= 0) {
                val length = minOf(code + 1, end - offset)
                input.get(target, offset, length)
                input.position(input.position() + code + 1 - length)
                offset += length
            } else if (code != -128) {
                val length = minOf(1 - code, end - offset)
                target.fill(input.get(), offset, offset + length)
                offset += length
            }
        }
    }

    private fun assemble(
        record: LayerRecord,
        channels: Map<Int, ByteArray?>,
        width: Int,
        height: Int,
    ): PsdImport {
        val rgba = ByteArray(width * height * 4)
        val layerWidth = record.right - record.left
        for (y in maxOf(0, record.top) until minOf(height, record.bottom)) {
            for (x in maxOf(0, record.left) until minOf(width, record.right)) {
                val source = (y - record.top) * layerWidth + x - record.left
                val alpha = channels[-1]?.get(source)?.toInt()?.and(255) ?: 255
                val target = (y * width + x) * 4
                for (channel in 0..2) {
                    val value = channels[channel]?.get(source)?.toInt()?.and(255) ?: 0
                    rgba[target + channel] = (value * alpha / 255).toByte()
                }
                rgba[target + 3] = alpha.toByte()
            }
        }
        return PsdImport(
            record.name.ifEmpty { "Layer" },
            record.opacity / 255f,
            record.visible,
            BLENDS[record.blend] ?: 0,
            record.clipped,
            rgba,
        )
    }

    private fun readMerged(input: ByteBuffer, width: Int, height: Int, channels: Int): PsdImport {
        val compression = input.short.toInt()
        val planes = Array(channels) { ByteArray(width * height) }
        if (compression == 1) {
            val lengths = IntArray(channels * height) { input.short.toInt() and 0xFFFF }
            for (channel in 0 until channels) {
                for (row in 0 until height) {
                    val next = input.position() + lengths[channel * height + row]
                    unpack(input, planes[channel], row * width, width)
                    input.position(next)
                }
            }
        } else {
            planes.forEach(input::get)
        }
        val rgba = ByteArray(width * height * 4)
        for (index in 0 until width * height) {
            for (channel in 0..2) rgba[index * 4 + channel] = planes[channel][index]
            rgba[index * 4 + 3] = -1
        }
        return PsdImport("Background", 1f, true, 0, false, rgba)
    }
}
