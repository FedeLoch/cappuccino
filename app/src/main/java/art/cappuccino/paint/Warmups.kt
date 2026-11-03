package art.cappuccino.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val CLEARER_SINCE_START = 0.08f
private const val MORE_COLOR_SINCE_START = 0.15f

/** The kind of practice an exercise is, which decides its guide and how the companion reads it. */
enum class WarmupKind {
    STRAIGHT_LINES, HATCHING, ELLIPSES, TRACING, SILHOUETTES, SPHERE_LIGHT, CUBE_SHADOW,
    THREE_VALUES, VALUE_SCALE, ONE_HUE, WARM_COOL, HUE_SHIFT,
}

/**
 * A short exercise for warming up. [topic] matches the companion's note topics, so the one your
 * notes keep raising comes first.
 */
data class Warmup(val kind: WarmupKind, val group: String, val topic: String, val title: String, val steps: String)

internal val WARMUPS = listOf(
    Warmup(WarmupKind.STRAIGHT_LINES, "Lines and precision", "Next step", "Dot to dot", "Join each pair of dots with one confident straight line. Move from your shoulder, not your wrist."),
    Warmup(WarmupKind.HATCHING, "Lines and precision", "Next step", "Even hatching", "Fill each box with parallel lines, all at the same angle and evenly spaced."),
    Warmup(WarmupKind.ELLIPSES, "Lines and precision", "Next step", "Ellipses", "Draw an ellipse in each box, in one stroke, touching all four sides."),
    Warmup(WarmupKind.TRACING, "Lines and precision", "Next step", "Steady tracing", "Trace each gray path as closely as you can, slowly and in one stroke."),
    Warmup(WarmupKind.SILHOUETTES, "Shapes", "Silhouette", "Silhouettes", "Turn each stick figure into a solid black silhouette. Keep the pose easy to read."),
    Warmup(WarmupKind.SPHERE_LIGHT, "Light and shadow", "Light", "Light a sphere", "Paint the side facing the sun, a core shadow, and a cast shadow on the ground."),
    Warmup(WarmupKind.CUBE_SHADOW, "Light and shadow", "Light", "Shade a cube", "Make the face toward the sun the lightest, and the one facing away the darkest."),
    Warmup(WarmupKind.THREE_VALUES, "Values", "Values", "Three values", "Fill this little landscape with only three values: one dark, one middle, and one light."),
    Warmup(WarmupKind.VALUE_SCALE, "Values", "Values", "Value scale", "Fill the five boxes from lightest to darkest, in even steps."),
    Warmup(WarmupKind.ONE_HUE, "Color", "Palette", "One main hue", "Color this little landscape so one hue leads, with two quiet ones supporting it."),
    Warmup(WarmupKind.WARM_COOL, "Color", "Palette", "Warm light, cool shadow", "Paint the sphere with warm light from the sun and cool shadows."),
    Warmup(WarmupKind.HUE_SHIFT, "Color", "Palette", "Shift the hue", "Shade the sphere without black or gray. Let the hue move as it gets darker."),
)

val WARMUP_GROUPS = WARMUPS.map { it.group }.distinct()

/** Where the sun sits in a light exercise, as a name and a direction from the object toward it. */
data class SunSpot(val name: String, val x: Float, val y: Float)

val SUN_SPOTS = listOf(
    SunSpot("top left", -0.7071f, -0.7071f),
    SunSpot("top", 0f, -1f),
    SunSpot("top right", 0.7071f, -0.7071f),
    SunSpot("left", -1f, 0f),
    SunSpot("right", 1f, 0f),
)

/** Whether the exercise lights an object from a sun on the canvas. */
val WarmupKind.usesSun get() = this == WarmupKind.SPHERE_LIGHT || this == WarmupKind.CUBE_SHADOW ||
    this == WarmupKind.WARM_COOL || this == WarmupKind.HUE_SHIFT

/** What the companion suggests first, from the topic your notes raised most recently. */
fun warmupFor(topic: String?, day: Int): Warmup {
    val matching = WARMUPS.filter { it.topic == topic }.ifEmpty { WARMUPS }
    return matching[Math.floorMod(day, matching.size)]
}

/** Topics a warm-up can help with, in the order a note's topic is checked. */
val WARMUP_TOPICS = WARMUPS.map { it.topic }.distinct()

/** A point in the canvas as a share of its width and height. */
data class Spot(val x: Float, val y: Float)

private val DOT_PAIRS = listOf(
    Spot(0.10f, 0.14f) to Spot(0.62f, 0.10f),
    Spot(0.74f, 0.16f) to Spot(0.90f, 0.62f),
    Spot(0.12f, 0.30f) to Spot(0.56f, 0.52f),
    Spot(0.16f, 0.88f) to Spot(0.84f, 0.80f),
    Spot(0.26f, 0.60f) to Spot(0.30f, 0.86f) ,
    Spot(0.44f, 0.70f) to Spot(0.70f, 0.40f),
)

private val HATCH_BOXES = listOf(floatArrayOf(0.08f, 0.22f, 0.32f, 0.78f), floatArrayOf(0.38f, 0.22f, 0.62f, 0.78f), floatArrayOf(0.68f, 0.22f, 0.92f, 0.78f))

private val VALUE_BOXES = List(5) { index -> floatArrayOf(0.06f + index * 0.18f, 0.38f, 0.22f + index * 0.18f, 0.62f) }

private val SPHERE = floatArrayOf(0.5f, 0.46f, 0.24f)

/** Boxes to draw ellipses in, as left, top, right, and bottom. */
internal val ELLIPSE_BOXES = listOf(
    floatArrayOf(0.06f, 0.10f, 0.30f, 0.36f),
    floatArrayOf(0.36f, 0.14f, 0.66f, 0.32f),
    floatArrayOf(0.72f, 0.08f, 0.94f, 0.40f),
    floatArrayOf(0.08f, 0.50f, 0.24f, 0.90f),
    floatArrayOf(0.32f, 0.56f, 0.64f, 0.84f),
    floatArrayOf(0.70f, 0.52f, 0.92f, 0.86f),
)

/** Stick figures to fill as silhouettes: the center, a lean, and the hands and feet. */
private class Figure(val x: Float, val lean: Float, val hands: List<Spot>, val feet: List<Spot>) {
    val head = Spot(x, 0.30f)
    val neck = Spot(x, 0.34f)
    val hip = Spot(x + lean, 0.52f)
    val box = floatArrayOf(x - 0.09f, 0.25f, x + 0.09f, 0.76f)
}

private val FIGURES = listOf(
    Figure(0.12f, 0f, listOf(Spot(0.06f, 0.47f), Spot(0.18f, 0.47f)), listOf(Spot(0.08f, 0.73f), Spot(0.16f, 0.73f))),
    Figure(0.31f, 0.02f, listOf(Spot(0.24f, 0.26f), Spot(0.39f, 0.30f)), listOf(Spot(0.28f, 0.73f), Spot(0.38f, 0.66f))),
    Figure(0.50f, -0.03f, listOf(Spot(0.42f, 0.40f), Spot(0.58f, 0.46f)), listOf(Spot(0.42f, 0.73f), Spot(0.56f, 0.73f))),
    Figure(0.69f, 0.04f, listOf(Spot(0.62f, 0.50f), Spot(0.77f, 0.36f)), listOf(Spot(0.64f, 0.73f), Spot(0.78f, 0.70f))),
    Figure(0.88f, -0.02f, listOf(Spot(0.80f, 0.30f), Spot(0.96f, 0.30f)), listOf(Spot(0.82f, 0.73f), Spot(0.94f, 0.73f))),
)

/** The little landscape for value and color studies: mountains, then the path to the lake. */
private val MOUNTAINS = floatArrayOf(0.04f, 0.56f, 0.22f, 0.32f, 0.36f, 0.48f, 0.52f, 0.26f, 0.70f, 0.50f, 0.82f, 0.38f, 0.96f, 0.56f)
private val PATH = floatArrayOf(0.46f, 0.96f, 0.58f, 0.96f, 0.53f, 0.57f, 0.51f, 0.57f)

/** The three faces of the cube, as x and y pairs: top, left, and right. */
private val CUBE_FACES = run {
    val c = 0.5f
    val top = 0.24f
    val half = 0.22f
    val rise = 0.12f
    val side = 0.3f
    listOf(
        floatArrayOf(c, top, c + half, top + rise, c, top + rise * 2, c - half, top + rise),
        floatArrayOf(c - half, top + rise, c, top + rise * 2, c, top + rise * 2 + side, c - half, top + rise + side),
        floatArrayOf(c, top + rise * 2, c + half, top + rise, c + half, top + rise + side, c, top + rise * 2 + side),
    )
}

/** The gray paths to trace, each sampled densely. */
internal val TRACE_PATHS: List<List<Spot>> = listOf(
    List(80) { index ->
        val t = index / 79f
        Spot(0.1f + 0.8f * t, 0.2f + 0.07f * sin(t * 4f * PI.toFloat()))
    },
    List(120) { index ->
        val t = index / 119f
        val angle = t * 5f * PI.toFloat()
        val radius = 0.02f + 0.16f * t
        Spot(0.3f + radius * cos(angle), 0.64f + radius * sin(angle))
    },
    List(60) { index ->
        val t = index / 59f
        val step = t * 6f
        val up = if (step.toInt() % 2 == 0) step % 1f else 1f - step % 1f
        Spot(0.56f + 0.34f * t, 0.84f - 0.32f * up)
    },
)

private const val GUIDE_GRAY = 0xFFB9B2A8.toInt()
private const val GUIDE_DOT = 0xFF8C7F72.toInt()
private const val SPHERE_GRAY = 0xFFC6C5C3.toInt()

/** How light the untouched sphere and cube are, to tell paint from the guide. */
private const val GUIDE_LUMINANCE = 0.772f
private const val SUN = 0xFFE7A93C.toInt()

/** Paints the exercise's starting point, such as dots to join or a sphere to light. */
fun drawWarmupGuide(kind: WarmupKind, width: Int, height: Int, sun: SunSpot = SUN_SPOTS[0]): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val side = min(width, height).toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = side * 0.004f
        color = GUIDE_GRAY
    }
    fun x(value: Float) = value * width
    fun y(value: Float) = value * height
    fun sun() {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUN }
        val cx = x((0.5f + sun.x * 0.4f).coerceIn(0.08f, 0.92f))
        val cy = y((0.46f + sun.y * 0.36f).coerceIn(0.08f, 0.92f))
        canvas.drawCircle(cx, cy, side * 0.028f, fill)
        val rays = Paint(paint).apply { color = SUN; strokeWidth = side * 0.006f }
        repeat(8) { index ->
            val angle = index * PI.toFloat() / 4f
            canvas.drawLine(
                cx + cos(angle) * side * 0.042f, cy + sin(angle) * side * 0.042f,
                cx + cos(angle) * side * 0.058f, cy + sin(angle) * side * 0.058f, rays,
            )
        }
    }
    fun path(points: FloatArray) = Path().apply {
        moveTo(x(points[0]), y(points[1]))
        for (index in 2 until points.size step 2) lineTo(x(points[index]), y(points[index + 1]))
        close()
    }
    when (kind) {
        WarmupKind.STRAIGHT_LINES -> {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GUIDE_DOT }
            DOT_PAIRS.forEach { (from, to) ->
                canvas.drawCircle(x(from.x), y(from.y), side * 0.01f, dot)
                canvas.drawCircle(x(to.x), y(to.y), side * 0.01f, dot)
            }
        }
        WarmupKind.HATCHING -> HATCH_BOXES.forEach { canvas.drawRect(x(it[0]), y(it[1]), x(it[2]), y(it[3]), paint) }
        WarmupKind.VALUE_SCALE -> VALUE_BOXES.forEach { canvas.drawRect(x(it[0]), y(it[1]), x(it[2]), y(it[3]), paint) }
        WarmupKind.TRACING -> {
            val trace = Paint(paint).apply { strokeWidth = side * 0.006f; color = 0xFFD3CDC4.toInt() }
            TRACE_PATHS.forEach { points ->
                val line = Path()
                points.forEachIndexed { index, spot -> if (index == 0) line.moveTo(x(spot.x), y(spot.y)) else line.lineTo(x(spot.x), y(spot.y)) }
                canvas.drawPath(line, trace)
            }
        }
        WarmupKind.SPHERE_LIGHT, WarmupKind.WARM_COOL, WarmupKind.HUE_SHIFT -> {
            sun()
            val radius = SPHERE[2] * side
            val ground = Paint(paint).apply { strokeWidth = side * 0.003f }
            canvas.drawLine(x(0.08f), y(SPHERE[1]) + radius, x(0.92f), y(SPHERE[1]) + radius, ground)
            canvas.drawCircle(x(SPHERE[0]), y(SPHERE[1]), radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SPHERE_GRAY })
        }
        WarmupKind.CUBE_SHADOW -> {
            sun()
            val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SPHERE_GRAY }
            CUBE_FACES.forEach { points ->
                canvas.drawPath(path(points), face)
                canvas.drawPath(path(points), paint)
            }
        }
        WarmupKind.ELLIPSES -> ELLIPSE_BOXES.forEach { canvas.drawRect(x(it[0]), y(it[1]), x(it[2]), y(it[3]), paint) }
        WarmupKind.SILHOUETTES -> {
            val limb = Paint(paint).apply { strokeWidth = side * 0.006f }
            FIGURES.forEach { figure ->
                fun line(from: Spot, to: Spot) = canvas.drawLine(x(from.x), y(from.y), x(to.x), y(to.y), limb)
                canvas.drawCircle(x(figure.head.x), y(figure.head.y), side * 0.032f, limb)
                line(figure.neck, figure.hip)
                val shoulder = Spot(figure.x + figure.lean * 0.2f, 0.37f)
                figure.hands.forEach { line(shoulder, it) }
                figure.feet.forEach { line(figure.hip, it) }
            }
        }
        WarmupKind.THREE_VALUES, WarmupKind.ONE_HUE -> {
            val outline = Path().apply {
                moveTo(x(MOUNTAINS[0]), y(MOUNTAINS[1]))
                for (index in 2 until MOUNTAINS.size step 2) lineTo(x(MOUNTAINS[index]), y(MOUNTAINS[index + 1]))
            }
            canvas.drawPath(outline, paint)
            canvas.drawLine(x(0f), y(0.56f), x(1f), y(0.56f), paint)
            canvas.drawCircle(x(0.80f), y(0.16f), side * 0.05f, paint)
            canvas.drawRect(x(0.235f), y(0.64f), x(0.265f), y(0.82f), paint)
            canvas.drawCircle(x(0.25f), y(0.6f), side * 0.07f, paint)
            canvas.drawPath(path(PATH), paint)
            canvas.drawOval(x(0.62f), y(0.68f), x(0.9f), y(0.78f), paint)
        }
    }
    return bitmap
}

/** What you drew for an exercise: strokes as x and y pairs in canvas shares, and a small image. */
class WarmupWork(
    val strokes: List<FloatArray>,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val sun: SunSpot = SUN_SPOTS[0],
) {
    fun luminance(x: Int, y: Int): Float {
        val color = pixels[y * width + x]
        return (0.2126f * ((color shr 16) and 0xFF) + 0.7152f * ((color shr 8) and 0xFF) + 0.0722f * (color and 0xFF)) / 255f
    }

    fun red(x: Int, y: Int) = ((pixels[y * width + x] shr 16) and 0xFF) / 255f

    fun blue(x: Int, y: Int) = (pixels[y * width + x] and 0xFF) / 255f
}

/** The companion's note on an exercise, based only on what the exercise practices. */
fun warmupFeedback(kind: WarmupKind, work: WarmupWork): String = when (kind) {
    WarmupKind.STRAIGHT_LINES -> lineFeedback(work.strokes)
    WarmupKind.HATCHING -> hatchingFeedback(work.strokes)
    WarmupKind.ELLIPSES -> ellipseFeedback(work.strokes)
    WarmupKind.TRACING -> tracingFeedback(work.strokes)
    WarmupKind.SILHOUETTES -> silhouetteFeedback(work)
    WarmupKind.SPHERE_LIGHT -> sphereFeedback(work)
    WarmupKind.CUBE_SHADOW -> cubeFeedback(work)
    WarmupKind.THREE_VALUES -> threeValueFeedback(work)
    WarmupKind.VALUE_SCALE -> valueScaleFeedback(work)
    WarmupKind.ONE_HUE -> oneHueFeedback(work)
    WarmupKind.WARM_COOL -> warmCoolFeedback(work)
    WarmupKind.HUE_SHIFT -> hueShiftFeedback(work)
}

private fun FloatArray.length(): Float {
    var total = 0f
    for (index in 2 until size step 2) total += hypot(this[index] - this[index - 2], this[index + 1] - this[index - 1])
    return total
}

/** How far a stroke strays from the straight line between its ends, as a share of that line. */
internal fun wobble(stroke: FloatArray): Float {
    val ax = stroke[0]
    val ay = stroke[1]
    val bx = stroke[stroke.size - 2]
    val by = stroke[stroke.size - 1]
    val chord = hypot(bx - ax, by - ay)
    if (chord < 1e-4f) return 1f
    var worst = 0f
    for (index in stroke.indices step 2) {
        val distance = abs((bx - ax) * (ay - stroke[index + 1]) - (ax - stroke[index]) * (by - ay)) / chord
        worst = max(worst, distance)
    }
    return worst / chord
}

private fun longStrokes(strokes: List<FloatArray>, shortest: Float = 0.03f) = strokes.filter { it.size >= 4 && it.length() >= shortest }

private fun lineFeedback(strokes: List<FloatArray>): String {
    val lines = longStrokes(strokes)
    if (lines.isEmpty()) return tr("Join a few dots first, then ask me again.")
    val wobble = lines.map(::wobble).average()
    val dots = DOT_PAIRS.flatMap { listOf(it.first, it.second) }
    fun miss(x: Float, y: Float) = dots.minOf { hypot(it.x - x, it.y - y) }
    val landing = lines.map { (miss(it[0], it[1]) + miss(it[it.size - 2], it[it.size - 1])) / 2f }.average() * 100
    val straight = when {
        wobble < 0.012 -> tr("Your lines are really straight.")
        wobble < 0.03 -> tr("Your lines are mostly straight, with a little wobble.")
        else -> tr("Your lines wobble. Try a faster, more confident stroke.")
    }
    val aim = when {
        landing < 1.2 -> tr("You land right on the dots.")
        landing < 3 -> tr("You land close to the dots. Look at the end dot, not the pen, as you go.")
        else -> tr("Your ends miss the dots. Hover over the path once, then commit.")
    }
    return "$straight $aim"
}

private fun hatchingFeedback(strokes: List<FloatArray>): String {
    val lines = longStrokes(strokes, 0.02f)
    if (lines.size < 4) return tr("Draw at least four hatching lines, then ask me again.")
    val angles = lines.map { atan2(it[it.size - 1] - it[1], it[it.size - 2] - it[0]).let { angle -> ((angle + PI) % PI).toFloat() } }
    val mean = atan2(angles.sumOf { sin(2.0 * it) }, angles.sumOf { cos(2.0 * it) }) / 2.0
    val spread = sqrt(angles.map { angle -> (((angle - mean) % PI + 1.5 * PI) % PI - PI / 2).let { it * it } }.average()) * 180 / PI
    val normalX = -sin(mean).toFloat()
    val normalY = cos(mean).toFloat()
    val offsets = lines.map { (it[0] + it[it.size - 2]) / 2f * normalX + (it[1] + it[it.size - 1]) / 2f * normalY }.sorted()
    val gaps = offsets.zipWithNext { a, b -> b - a }.filter { it > 0.002f }
    val gapMean = gaps.average()
    val gapSpread = if (gaps.size < 2 || gapMean <= 0) 0.0 else sqrt(gaps.map { (it - gapMean) * (it - gapMean) }.average()) / gapMean
    val angle = when {
        spread < 4 -> tr("Your lines keep the same angle beautifully.")
        spread < 9 -> tr("The angle drifts a little between lines.")
        else -> tr("The angle changes a lot. Turn the canvas so the lines go in your most comfortable direction.")
    }
    val spacing = when {
        gapSpread < 0.35 -> tr("The spacing is nice and even.")
        gapSpread < 0.7 -> tr("The spacing is a bit uneven. Use the last line as your ruler for the next.")
        else -> tr("The spacing jumps around. Slow down and measure each gap by eye.")
    }
    return "$angle $spacing"
}

/** How evenly a loop follows its own best-fitting ellipse, where 0 is perfect. */
internal fun ellipseUnevenness(stroke: FloatArray): Float {
    val count = stroke.size / 2
    var cx = 0f
    var cy = 0f
    for (index in 0 until count) {
        cx += stroke[index * 2]
        cy += stroke[index * 2 + 1]
    }
    cx /= count
    cy /= count
    var xx = 0f
    var xy = 0f
    var yy = 0f
    for (index in 0 until count) {
        val dx = stroke[index * 2] - cx
        val dy = stroke[index * 2 + 1] - cy
        xx += dx * dx
        xy += dx * dy
        yy += dy * dy
    }
    val angle = 0.5f * atan2(2f * xy, xx - yy)
    val cosine = cos(angle)
    val sine = sin(angle)
    var major = 0f
    var minor = 0f
    for (index in 0 until count) {
        val dx = stroke[index * 2] - cx
        val dy = stroke[index * 2 + 1] - cy
        val u = dx * cosine + dy * sine
        val v = -dx * sine + dy * cosine
        major += u * u
        minor += v * v
    }
    major = sqrt(major / count)
    minor = sqrt(minor / count)
    if (major < 1e-5f || minor < 1e-5f) return 1f
    val radii = (0 until count).map { index ->
        val dx = stroke[index * 2] - cx
        val dy = stroke[index * 2 + 1] - cy
        hypot((dx * cosine + dy * sine) / major, (-dx * sine + dy * cosine) / minor)
    }
    val mean = radii.average().toFloat()
    return sqrt(radii.map { (it - mean) * (it - mean) }.average()).toFloat() / mean
}

/** The loop drawn in each box, if any, chosen by where its middle falls. */
private fun loopsInBoxes(strokes: List<FloatArray>): List<FloatArray?> {
    val loops = longStrokes(strokes, 0.05f)
    return ELLIPSE_BOXES.map { box ->
        loops.lastOrNull { loop ->
            val cx = (loop.indices step 2).map { loop[it] }.average().toFloat()
            val cy = (loop.indices step 2).map { loop[it + 1] }.average().toFloat()
            cx in box[0]..box[2] && cy in box[1]..box[3]
        }
    }
}

/** How far a loop's outer edges sit from its box's sides, as a share of the box. */
internal fun boxMiss(loop: FloatArray, box: FloatArray): Float {
    val xs = (loop.indices step 2).map { loop[it] }
    val ys = (loop.indices step 2).map { loop[it + 1] }
    val width = box[2] - box[0]
    val height = box[3] - box[1]
    return listOf(abs(xs.min() - box[0]) / width, abs(xs.max() - box[2]) / width, abs(ys.min() - box[1]) / height, abs(ys.max() - box[3]) / height).average().toFloat()
}

private fun ellipseFeedback(strokes: List<FloatArray>): String {
    val placed = loopsInBoxes(strokes)
    val loops = placed.filterNotNull()
    if (loops.isEmpty()) return tr("Draw an ellipse in one of the boxes first, then ask me again.")
    val gap = loops.map { hypot(it[0] - it[it.size - 2], it[1] - it[it.size - 1]) / it.length() }.average()
    val uneven = loops.map(::ellipseUnevenness).average()
    val miss = loops.indices.map { boxMiss(loops[it], ELLIPSE_BOXES[placed.indexOf(loops[it])]) }.average()
    val shape = when {
        uneven < 0.06 -> tr("Your ellipses are smooth and even.")
        uneven < 0.13 -> tr("Your ellipses are fairly smooth, with a few flat spots.")
        else -> tr("Your ellipses get lumpy. Draw a couple of ghost loops in the air before touching down.")
    }
    val fit = when {
        miss < 0.06 -> tr("They touch the sides of their boxes nicely.")
        miss < 0.14 -> tr("Some fall short of the box sides. Aim for all four edges.")
        else -> tr("They float inside the boxes. Let each one touch all four sides.")
    }
    val close = if (gap >= 0.05) " " + tr("Some stay open. Overlap the end with the start.") else ""
    val left = placed.count { it == null }
    val rest = if (left > 0) " " + tr("%1 boxes to go.", left) else ""
    return "$shape $fit$close$rest"
}

private fun tracingFeedback(strokes: List<FloatArray>): String {
    val traced = longStrokes(strokes, 0.02f)
    if (traced.isEmpty()) return tr("Trace one of the gray paths first, then ask me again.")
    val guide = TRACE_PATHS.flatten()
    val distances = traced.flatMap { stroke ->
        (stroke.indices step 2).map { index -> guide.minOf { hypot(it.x - stroke[index], it.y - stroke[index + 1]) } }
    }
    val close = distances.count { it < 0.008f } / distances.size.toFloat() * 100
    return when {
        close >= 85 -> tr("Very precise: %1% of your line sits right on the path.", close.toInt())
        close >= 55 -> tr("%1% of your line sits on the path. Slow down on the tight turns.", close.toInt())
        else -> tr("Only %1% of your line sits on the path. Go slower, and lift the pen to reset when you drift.", close.toInt())
    }
}

/** Dark shapes in the image, each as the cells it covers on a coarse grid. */
internal fun darkShapes(work: WarmupWork, grid: Int = 64): List<List<Int>> {
    val dark = BooleanArray(grid * grid) { cell ->
        val x = (cell % grid) * work.width / grid
        val y = (cell / grid) * work.height / grid
        work.luminance(x, y) < 0.35f
    }
    val seen = BooleanArray(dark.size)
    val shapes = ArrayList<List<Int>>()
    for (start in dark.indices) {
        if (!dark[start] || seen[start]) continue
        val cells = ArrayList<Int>()
        val queue = ArrayDeque(listOf(start))
        seen[start] = true
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            cells += cell
            val x = cell % grid
            val y = cell / grid
            for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until grid || ny !in 0 until grid) continue
                val next = ny * grid + nx
                if (dark[next] && !seen[next]) {
                    seen[next] = true
                    queue += next
                }
            }
        }
        if (cells.size >= 6) shapes += cells
    }
    return shapes
}

/** How much of each stick figure's area is filled dark. */
private fun figureFill(work: WarmupWork): List<Float> = FIGURES.map { figure ->
    meanIn(work, { x, y -> x in figure.box[0]..figure.box[2] && y in figure.box[1]..figure.box[3] }) { x, y ->
        if (work.luminance(x, y) < 0.35f) 1f else 0f
    } ?: 0f
}

private fun silhouetteFeedback(work: WarmupWork): String {
    val fill = figureFill(work)
    val done = fill.count { it > 0.22f }
    if (fill.all { it < 0.06f }) return tr("Fill one of the stick figures in solid black first, then ask me again.")
    val count = if (done < FIGURES.size) tr("%1 of 5 figures are filled.", done) else tr("All five figures are filled.")
    val shapes = darkShapes(work)
    val solid = shapes.map { cells ->
        val xs = cells.map { it % 64 }
        val ys = cells.map { it / 64 }
        cells.size / ((xs.max() - xs.min() + 1) * (ys.max() - ys.min() + 1)).toFloat()
    }.average()
    val read = when {
        shapes.isEmpty() -> ""
        solid > 0.8 -> tr("They're quite boxy. Keep gaps between the limbs and the body, so each pose reads at a glance.")
        solid > 0.35 -> tr("They have clear, readable outlines.")
        else -> tr("They're thin or broken up. Fill them in fully so the outline does the talking.")
    }
    return "$count $read".trim()
}

private fun meanIn(work: WarmupWork, inside: (Float, Float) -> Boolean, value: (Int, Int) -> Float): Float? {
    var total = 0f
    var count = 0
    for (y in 0 until work.height step 2) {
        for (x in 0 until work.width step 2) {
            if (!inside(x / work.width.toFloat(), y / work.height.toFloat())) continue
            total += value(x, y)
            count++
        }
    }
    return if (count == 0) null else total / count
}

/** True inside the sphere on the side toward the light, or away from it when [lit] is false. */
private fun sphereSide(work: WarmupWork, lit: Boolean): (Float, Float) -> Boolean {
    val radiusX = SPHERE[2] * min(work.width, work.height) / work.width
    val radiusY = SPHERE[2] * min(work.width, work.height) / work.height
    return { x, y ->
        val dx = (x - SPHERE[0]) / radiusX
        val dy = (y - SPHERE[1]) / radiusY
        val toward = dx * work.sun.x + dy * work.sun.y
        dx * dx + dy * dy < 0.85f && if (lit) toward > 0.3f else toward < -0.3f
    }
}

private fun sphereFeedback(work: WarmupWork): String {
    val lit = meanIn(work, sphereSide(work, true), work::luminance) ?: return tr("Paint on the sphere first, then ask me again.")
    val shadow = meanIn(work, sphereSide(work, false), work::luminance) ?: return tr("Paint on the sphere first, then ask me again.")
    if (abs(lit - GUIDE_LUMINANCE) < 0.02f && abs(shadow - GUIDE_LUMINANCE) < 0.02f) return tr("Paint on the sphere first, then ask me again.")
    val bottom = SPHERE[1] + SPHERE[2] * min(work.width, work.height) / work.height
    // The cast shadow falls on the ground on the side away from the sun.
    val away = -work.sun.x
    val castFrom = min(SPHERE[0], SPHERE[0] + away * 0.4f) - if (away == 0f) 0.15f else 0f
    val castTo = max(SPHERE[0], SPHERE[0] + away * 0.4f) + if (away == 0f) 0.15f else 0f
    val cast = meanIn(work, { x, y -> y in bottom - 0.02f..bottom + 0.1f && x in castFrom..castTo }, work::luminance) ?: 1f
    val form = when {
        lit < shadow - 0.03f -> tr("The side facing the sun came out darker. Turn it around: light toward the sun, shadow away.")
        lit - shadow < 0.12f -> tr("The sphere still looks flat. Push the side away from the light darker.")
        lit - shadow < 0.3f -> tr("The sphere has form. A bit more contrast would make it pop.")
        else -> tr("Great form: the sphere really turns from light to shadow.")
    }
    val ground = if (cast < 0.75f) tr("And the cast shadow grounds it.") else tr("Add a cast shadow on the ground, on the side away from the sun.")
    return "$form $ground"
}

private fun inPolygon(points: FloatArray, x: Float, y: Float): Boolean {
    var inside = false
    var previous = points.size - 2
    for (index in points.indices step 2) {
        val xi = points[index]
        val yi = points[index + 1]
        val xj = points[previous]
        val yj = points[previous + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        previous = index
    }
    return inside
}

/** Which way each cube face looks: top, left, and right. */
private val CUBE_NORMALS = listOf(0f to -1f, -0.89f to 0.45f, 0.89f to 0.45f)
internal val CUBE_FACE_NAMES = listOf("top face", "left face", "right face")

private fun cubeFeedback(work: WarmupWork): String {
    val faces = CUBE_FACES.map { face -> meanIn(work, { x, y -> inPolygon(face, x, y) }, work::luminance) ?: 1f }
    if (faces.all { abs(it - GUIDE_LUMINANCE) < 0.02f }) return tr("Shade the cube's faces first, then ask me again.")
    val facing = CUBE_NORMALS.map { (nx, ny) -> nx * work.sun.x + ny * work.sun.y }
    val brightest = faces.indices.maxBy { facing[it] }
    val darkest = faces.indices.minBy { facing[it] }
    // Faces that look at the sun about equally can share a value, so only clear differences count.
    val wrong = faces.indices.flatMap { lit -> faces.indices.map { lit to it } }
        .filter { (lit, dim) -> facing[lit] > facing[dim] + 0.2f && faces[lit] < faces[dim] - 0.02f }
    return when {
        wrong.isEmpty() && faces[brightest] - faces[darkest] > 0.25f ->
            tr("Lovely: the face toward the sun is lightest, the one facing away darkest, and the planes read clearly.")
        wrong.isEmpty() -> tr("The order is right. Spread the values further apart so each plane separates.")
        wrong.any { it.first == brightest } -> tr("The %1 should be the lightest, since it faces the sun most.", tr(CUBE_FACE_NAMES[brightest]))
        else -> tr("The %1 looks away from the sun, so it should be the darkest.", tr(CUBE_FACE_NAMES[wrong.first().second]))
    }
}

/** How many distinct value bands cover a real part of the image. */
internal fun valueBands(work: WarmupWork): Int {
    val bins = IntArray(8)
    var total = 0
    for (y in 0 until work.height step 2) {
        for (x in 0 until work.width step 2) {
            bins[min(7, (work.luminance(x, y) * 8).toInt())]++
            total++
        }
    }
    return bins.count { it > total * 0.04f }
}

private fun threeValueFeedback(work: WarmupWork): String = when (valueBands(work)) {
    0, 1 -> tr("Paint your scene first, then ask me again.")
    2 -> tr("I see two values. Add a middle one to connect the light and the dark.")
    3 -> tr("Exactly three values. Squint: the big shapes should read clearly.")
    else -> tr("I see %1 values. Merge the close ones, so only three remain.", valueBands(work))
}

private fun valueScaleFeedback(work: WarmupWork): String {
    val boxes = VALUE_BOXES.map { box -> meanIn(work, { x, y -> x in box[0] + 0.02f..box[2] - 0.02f && y in box[1] + 0.02f..box[3] - 0.02f }, work::luminance) ?: 1f }
    if (boxes.all { it > 0.95f }) return tr("Fill the boxes first, then ask me again.")
    val steps = boxes.zipWithNext { a, b -> a - b }
    if (steps.any { it < 0.02f }) return tr("Some neighbors are too close or out of order. Each box should be a clear step darker.")
    val mean = steps.average()
    val spread = sqrt(steps.map { (it - mean) * (it - mean) }.average()) / mean
    return when {
        spread < 0.3 -> tr("Smooth, even steps from light to dark. That's a great scale.")
        spread < 0.6 -> tr("The scale goes the right way, but the steps vary. Compare each box with both neighbors.")
        else -> tr("The order is right, but some jumps are much bigger. Even them out.")
    }
}

private fun hueOf(color: Int): Float? {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    if (high < 0.15f || high - low < 0.15f * high || high - low < 0.06f) return null
    val delta = high - low
    val hue = when (high) {
        r -> ((g - b) / delta).mod(6f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return hue * 60f
}

private fun oneHueFeedback(work: WarmupWork): String {
    val bins = IntArray(12)
    var colored = 0
    work.pixels.forEachIndexed { index, color ->
        if (index % 3 != 0) return@forEachIndexed
        val hue = hueOf(color) ?: return@forEachIndexed
        bins[(hue / 30f).toInt() % 12]++
        colored++
    }
    if (colored < work.pixels.size / 60) return tr("Paint with some color first, then ask me again.")
    val lead = bins.max() / colored.toFloat()
    val strong = bins.count { it > colored * 0.15f }
    return when {
        lead > 0.55f && strong <= 3 -> tr("One hue clearly leads with %1% of the color. That's a calm, unified palette.", (lead * 100).toInt())
        lead > 0.4f -> tr("One hue leads, but the others compete a little. Mute or shrink the supporting ones.")
        else -> tr("Several hues share the stage equally. Pick one to lead and let it cover more of the study.")
    }
}

private fun warmth(work: WarmupWork): (Int, Int) -> Float = { x, y -> work.red(x, y) - work.blue(x, y) }

private fun warmCoolFeedback(work: WarmupWork): String {
    val lit = meanIn(work, sphereSide(work, true), warmth(work)) ?: return tr("Paint on the sphere first, then ask me again.")
    val shadow = meanIn(work, sphereSide(work, false), warmth(work)) ?: return tr("Paint on the sphere first, then ask me again.")
    val litValue = meanIn(work, sphereSide(work, true), work::luminance) ?: 1f
    if (abs(lit - shadow) < 0.01f && abs(litValue - GUIDE_LUMINANCE) < 0.02f) return tr("Paint on the sphere first, then ask me again.")
    return when {
        lit > 0.04f && shadow < lit - 0.08f -> tr("Warm light and cooler shadows, nicely done. The sphere feels lit by the sun.")
        lit <= 0.04f -> tr("The light side isn't warm yet. Push it toward yellow or orange.")
        else -> tr("The shadows are as warm as the light. Cool them toward blue or violet.")
    }
}

/** How far the hue turns from the lit side to the shadow side, and how much of the shadow is gray. */
private class HueShift(val shift: Double?, val grayShare: Float, val painted: Boolean)

private fun measureHueShift(work: WarmupWork): HueShift {
    val litHues = ArrayList<Float>()
    val shadowHues = ArrayList<Float>()
    var shadowGray = 0
    var shadowTotal = 0
    val lit = sphereSide(work, true)
    val shadow = sphereSide(work, false)
    for (y in 0 until work.height step 2) {
        for (x in 0 until work.width step 2) {
            val sx = x / work.width.toFloat()
            val sy = y / work.height.toFloat()
            val hue = hueOf(work.pixels[y * work.width + x])
            if (lit(sx, sy)) hue?.let(litHues::add)
            if (shadow(sx, sy)) {
                shadowTotal++
                if (hue == null) shadowGray++ else shadowHues += hue
            }
        }
    }
    val grayShare = if (shadowTotal == 0) 0f else shadowGray / shadowTotal.toFloat()
    val untouched = (meanIn(work, lit, work::luminance) ?: GUIDE_LUMINANCE).let { abs(it - GUIDE_LUMINANCE) < 0.02f } && litHues.isEmpty()
    if (litHues.isEmpty() || shadowHues.isEmpty()) return HueShift(null, grayShare, !untouched)
    fun average(hues: List<Float>) = atan2(hues.sumOf { sin(it * PI / 180) }, hues.sumOf { cos(it * PI / 180) }) * 180 / PI
    return HueShift(abs(((average(shadowHues) - average(litHues)) % 360 + 540) % 360 - 180), grayShare, true)
}

private fun hueShiftFeedback(work: WarmupWork): String {
    val measured = measureHueShift(work)
    val shift = measured.shift
    return when {
        shift == null && measured.painted && measured.grayShare > 0.6f -> tr("The shadows went gray or black. Shade with a darker, shifted hue instead.")
        shift == null -> tr("Paint the sphere in color first, then ask me again.")
        measured.grayShare > 0.5f -> tr("Much of the shadow went gray. Keep color in it, just darker and shifted.")
        shift < 8 -> tr("The hue barely moves. Try nudging the shadows toward a neighbor on the color wheel.")
        shift < 60 -> tr("The hue shifts %1 degrees into the shadow. That's what makes it glow.", shift.toInt())
        else -> tr("The shadow hue jumps %1 degrees, which can look like two objects. A smaller shift reads more natural.", shift.toInt())
    }
}

/** What the companion says about an exercise, and the spots on the canvas it means. */
class WarmupReview(val text: String, val marks: List<NoteMark>)

fun warmupReview(kind: WarmupKind, work: WarmupWork) = WarmupReview(warmupFeedback(kind, work), warmupMarks(kind, work))

private fun ring(label: String, x: Float, y: Float, radius: Float) = NoteMark(label, MarkShape.RING, x, y, radius)

private fun box(label: String, area: FloatArray) = NoteMark(label, MarkShape.BOX, area[0], area[1], area[2], area[3])

private fun line(label: String, stroke: FloatArray) = NoteMark(label, MarkShape.LINE, stroke[0], stroke[1], stroke[stroke.size - 2], stroke[stroke.size - 1])

private fun polygonBox(points: FloatArray) = floatArrayOf(
    (points.indices step 2).minOf { points[it] }, (points.indices step 2).minOf { points[it + 1] },
    (points.indices step 2).maxOf { points[it] }, (points.indices step 2).maxOf { points[it + 1] },
)

/** The spot on the sphere, toward the sun or away from it, where the companion points. */
private fun sphereSpot(work: WarmupWork, lit: Boolean): Spot {
    val sign = if (lit) 1f else -1f
    val reach = SPHERE[2] * 0.5f * sign
    return Spot(SPHERE[0] + work.sun.x * reach * min(work.width, work.height) / work.width, SPHERE[1] + work.sun.y * reach * min(work.width, work.height) / work.height)
}

/** Where the companion points to show what to improve, at most two places. */
fun warmupMarks(kind: WarmupKind, work: WarmupWork): List<NoteMark> {
    val marks = ArrayList<NoteMark>()
    when (kind) {
        WarmupKind.STRAIGHT_LINES -> {
            val lines = longStrokes(work.strokes)
            lines.maxByOrNull(::wobble)?.takeIf { wobble(it) > 0.012f }?.let { marks += line(tr("Wobbliest line"), it) }
            val dots = DOT_PAIRS.flatMap { listOf(it.first, it.second) }
            val ends = lines.flatMap { listOf(Spot(it[0], it[1]), Spot(it[it.size - 2], it[it.size - 1])) }
            ends.map { end -> end to dots.minBy { hypot(it.x - end.x, it.y - end.y) } }
                .maxByOrNull { (end, dot) -> hypot(dot.x - end.x, dot.y - end.y) }
                ?.takeIf { (end, dot) -> hypot(dot.x - end.x, dot.y - end.y) > 0.012f }
                ?.let { (_, dot) -> marks += ring(tr("Aim for this dot"), dot.x, dot.y, 0.035f) }
        }
        WarmupKind.HATCHING -> {
            val lines = longStrokes(work.strokes, 0.02f)
            if (lines.size >= 4) {
                val angles = lines.map { atan2(it[it.size - 1] - it[1], it[it.size - 2] - it[0]).let { angle -> ((angle + PI) % PI) } }
                val mean = atan2(angles.sumOf { sin(2.0 * it) }, angles.sumOf { cos(2.0 * it) }) / 2.0
                val off = angles.indices.maxBy { abs(((angles[it] - mean) % PI + 1.5 * PI) % PI - PI / 2) }
                if (abs(((angles[off] - mean) % PI + 1.5 * PI) % PI - PI / 2) > 6 * PI / 180) marks += line(tr("Off angle"), lines[off])
            }
        }
        WarmupKind.ELLIPSES -> {
            val placed = loopsInBoxes(work.strokes)
            placed.indices.filter { placed[it] != null }.maxByOrNull { boxMiss(placed[it]!!, ELLIPSE_BOXES[it]) }
                ?.takeIf { boxMiss(placed[it]!!, ELLIPSE_BOXES[it]) > 0.08f }
                ?.let { marks += box(tr("Touch all four sides"), ELLIPSE_BOXES[it]) }
            placed.filterNotNull().maxByOrNull(::ellipseUnevenness)?.takeIf { ellipseUnevenness(it) > 0.08f }?.let { loop ->
                val xs = (loop.indices step 2).map { loop[it] }
                val ys = (loop.indices step 2).map { loop[it + 1] }
                marks += ring(tr("Smooth this one"), xs.average().toFloat(), ys.average().toFloat(), (xs.max() - xs.min()) / 2f + 0.02f)
            }
        }
        WarmupKind.TRACING -> {
            val guide = TRACE_PATHS.flatten()
            val points = longStrokes(work.strokes, 0.02f).flatMap { stroke -> (stroke.indices step 2).map { Spot(stroke[it], stroke[it + 1]) } }
            points.maxByOrNull { point -> guide.minOf { hypot(it.x - point.x, it.y - point.y) } }
                ?.takeIf { point -> guide.minOf { hypot(it.x - point.x, it.y - point.y) } > 0.012f }
                ?.let { marks += ring(tr("Drifted here"), it.x, it.y, 0.04f) }
        }
        WarmupKind.SILHOUETTES -> {
            val fill = figureFill(work)
            fill.indices.filter { fill[it] < 0.22f }.minByOrNull { fill[it] }?.takeIf { fill.any { it > 0.06f } }
                ?.let { marks += box(tr("Fill this one"), FIGURES[it].box) }
        }
        WarmupKind.SPHERE_LIGHT -> {
            val lit = meanIn(work, sphereSide(work, true), work::luminance) ?: return marks
            val shadow = meanIn(work, sphereSide(work, false), work::luminance) ?: return marks
            if (abs(lit - GUIDE_LUMINANCE) < 0.02f && abs(shadow - GUIDE_LUMINANCE) < 0.02f) return marks
            if (lit - shadow < 0.3f) sphereSpot(work, false).let { marks += ring(tr("Darker here"), it.x, it.y, 0.08f) }
            val bottom = SPHERE[1] + SPHERE[2] * min(work.width, work.height) / work.height
            val away = -work.sun.x
            val from = min(SPHERE[0], SPHERE[0] + away * 0.4f) - if (away == 0f) 0.15f else 0f
            val to = max(SPHERE[0], SPHERE[0] + away * 0.4f) + if (away == 0f) 0.15f else 0f
            val cast = meanIn(work, { x, y -> y in bottom - 0.02f..bottom + 0.1f && x in from..to }, work::luminance) ?: 1f
            if (cast >= 0.75f) marks += box(tr("Cast shadow here"), floatArrayOf(from, bottom - 0.02f, to, bottom + 0.08f))
        }
        WarmupKind.CUBE_SHADOW -> {
            val faces = CUBE_FACES.map { face -> meanIn(work, { x, y -> inPolygon(face, x, y) }, work::luminance) ?: 1f }
            if (faces.all { abs(it - GUIDE_LUMINANCE) < 0.02f }) return marks
            val facing = CUBE_NORMALS.map { (nx, ny) -> nx * work.sun.x + ny * work.sun.y }
            faces.indices.flatMap { lit -> faces.indices.map { lit to it } }
                .firstOrNull { (lit, dim) -> facing[lit] > facing[dim] + 0.2f && faces[lit] < faces[dim] - 0.02f }
                ?.let { (lit, dim) ->
                    marks += box(tr("Lighter"), polygonBox(CUBE_FACES[lit]))
                    marks += box(tr("Darker"), polygonBox(CUBE_FACES[dim]))
                }
        }
        WarmupKind.VALUE_SCALE -> {
            val boxes = VALUE_BOXES.map { area -> meanIn(work, { x, y -> x in area[0] + 0.02f..area[2] - 0.02f && y in area[1] + 0.02f..area[3] - 0.02f }, work::luminance) ?: 1f }
            if (boxes.all { it > 0.95f }) return marks
            boxes.zipWithNext { a, b -> a - b }.indexOfFirst { it < 0.02f }.takeIf { it >= 0 }
                ?.let { marks += box(tr("Darker than its neighbor"), VALUE_BOXES[it + 1]) }
        }
        WarmupKind.WARM_COOL -> {
            val lit = meanIn(work, sphereSide(work, true), warmth(work)) ?: return marks
            val shadow = meanIn(work, sphereSide(work, false), warmth(work)) ?: return marks
            val value = meanIn(work, sphereSide(work, true), work::luminance) ?: 1f
            if (abs(lit - shadow) < 0.01f && abs(value - GUIDE_LUMINANCE) < 0.02f) return marks
            if (lit <= 0.04f) sphereSpot(work, true).let { marks += ring(tr("Warmer here"), it.x, it.y, 0.08f) }
            else if (shadow >= lit - 0.08f) sphereSpot(work, false).let { marks += ring(tr("Cooler here"), it.x, it.y, 0.08f) }
        }
        WarmupKind.HUE_SHIFT -> {
            val measured = measureHueShift(work)
            val shift = measured.shift
            val needsWork = if (shift == null) measured.painted && measured.grayShare > 0.6f else measured.grayShare > 0.5f || shift < 8 || shift >= 60
            if (needsWork) sphereSpot(work, false).let { marks += ring(tr("Shift the hue here"), it.x, it.y, 0.08f) }
        }
        WarmupKind.THREE_VALUES, WarmupKind.ONE_HUE -> Unit
    }
    return marks.take(2)
}

/** A short look back when you export a finished piece, comparing it with where it started. */
fun finishNote(first: ArtSnapshot, now: ArtSnapshot): String = when {
    now.contrast - first.contrast >= CLEARER_SINCE_START ->
        tr("Finished! Your values are much clearer than when you started this one.")
    now.colored - first.colored >= MORE_COLOR_SINCE_START ->
        tr("Finished! This began almost without color, and now it really glows.")
    now.coverage > first.coverage ->
        tr("Finished! You took this from a few marks to a whole piece.")
    else -> tr("Finished! Nice work. I'll remember this one.")
}

/** Turns engine pixels, red first, into colors for [WarmupWork]. */
fun rgbaToColors(bytes: java.nio.ByteBuffer, count: Int): IntArray {
    bytes.rewind()
    return IntArray(count) {
        val r = bytes.get().toInt() and 0xFF
        val g = bytes.get().toInt() and 0xFF
        val b = bytes.get().toInt() and 0xFF
        bytes.get()
        Color.rgb(r, g, b)
    }
}
