package art.cappuccino.paint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** How a text layer is set: its font, weight, slant, spacing, and alignment. */
data class TextStyle(
    /** A system family key from [SYSTEM_FONTS], or "file:" and the name of an imported font. */
    val font: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Extra space between letters, in ems. */
    val tracking: Float = 0f,
    /** Line height as a multiple of the text size. */
    val leading: Float = 1f,
    /** 0 left, 1 center, 2 right. */
    val align: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("font", font)
        .put("bold", bold)
        .put("italic", italic)
        .put("tracking", tracking.toDouble())
        .put("leading", leading.toDouble())
        .put("align", align)

    companion object {
        fun fromJson(item: JSONObject?): TextStyle = if (item == null) {
            TextStyle()
        } else {
            TextStyle(
                item.optString("font"),
                item.optBoolean("bold"),
                item.optBoolean("italic"),
                item.optDouble("tracking", 0.0).toFloat(),
                item.optDouble("leading", 1.0).toFloat(),
                item.optInt("align", 1),
            )
        }
    }
}

/** System font families that ship with Android, by key and the name shown for them. */
val SYSTEM_FONTS = listOf(
    "" to "Sans",
    "serif" to "Serif",
    "condensed" to "Condensed",
    "light" to "Thin",
    "mono" to "Mono",
    "script" to "Script",
    "casual" to "Casual",
)

private const val FILE_FONT = "file:"

/** The widest a line of text may run, as a share of the canvas width. */
private const val FIT_SHARE = 0.92f

fun fontsDir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

/** Fonts you imported, by key and name. */
fun importedFonts(context: Context): List<Pair<String, String>> =
    fontsDir(context).listFiles().orEmpty()
        .filter { it.extension.lowercase() in setOf("ttf", "otf") }
        .sortedBy { it.name.lowercase() }
        .map { "$FILE_FONT${it.name}" to it.nameWithoutExtension }

/** Copies a font file into the app, so text layers can keep using it. Returns its key, or null. */
fun importFont(context: Context, uri: Uri): String? {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: return null
    val safe = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_")
    if (safe.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return null
    val target = File(fontsDir(context), safe)
    val copied = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
        Typeface.createFromFile(target)
    }.isSuccess
    if (!copied) {
        target.delete()
        return null
    }
    return "$FILE_FONT$safe"
}

fun typefaceFor(context: Context, style: TextStyle): Typeface {
    val base = when {
        style.font.startsWith(FILE_FONT) ->
            runCatching { Typeface.createFromFile(File(fontsDir(context), style.font.removePrefix(FILE_FONT))) }.getOrNull()
        style.font == "serif" -> Typeface.SERIF
        style.font == "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        style.font == "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        style.font == "mono" -> Typeface.MONOSPACE
        style.font == "script" -> Typeface.create("cursive", Typeface.NORMAL)
        style.font == "casual" -> Typeface.create("casual", Typeface.NORMAL)
        else -> null
    } ?: Typeface.DEFAULT
    val weight = when {
        style.bold && style.italic -> Typeface.BOLD_ITALIC
        style.bold -> Typeface.BOLD
        style.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    return Typeface.create(base, weight)
}

/** Lays out [text] as it will be painted, at [size] pixels. */
fun textLayout(context: Context, text: String, style: TextStyle, size: Float, color: Int): StaticLayout {
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        textSize = size
        typeface = typefaceFor(context, style)
        letterSpacing = style.tracking
    }
    val width = text.split("\n").maxOf { ceil(paint.measureText(it)).toInt() } + ceil(size * 0.2f).toInt()
    val alignment = when (style.align) {
        0 -> Layout.Alignment.ALIGN_NORMAL
        2 -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_CENTER
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
        .setAlignment(alignment)
        .setLineSpacing(0f, style.leading)
        .setIncludePad(true)
        .build()
}

/** Paints the text centered on ([centerX], [centerY]), shrunk if needed so no line runs off the canvas. */
fun drawLettering(context: Context, canvas: Canvas, text: String, style: TextStyle, size: Float, color: Int, centerX: Float, centerY: Float) {
    val room = canvas.width * FIT_SHARE
    var layout = textLayout(context, text, style, size, color)
    if (layout.width > room) layout = textLayout(context, text, style, size * room / layout.width, color)
    canvas.save()
    canvas.translate(centerX - layout.width / 2f, centerY - layout.height / 2f)
    layout.draw(canvas)
    canvas.restore()
}
