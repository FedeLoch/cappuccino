package art.cappuccino.paint

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal const val FORM_GRAY = 0xFFC6C5C3.toInt()
private const val SUN_CORE = 0xFFF2B544.toInt()
private const val SUN_GLOW = 0x00F2B544

/** One plane of a form, such as a cube's top, with the way it faces and where it sits. */
internal class Plane(val name: String, val normalX: Float, val normalY: Float, val bounds: FloatArray, val contains: (Float, Float) -> Boolean)

/** A shape to light in an exercise, the sun it's lit by, and the planes the companion compares. */
internal class LightForm(val name: String, val sun: SunSpot, val center: Spot, val radius: Float, val planes: List<Plane>, val outline: (Canvas, Float, Float, Paint, Paint) -> Unit)

private fun inside(points: FloatArray, x: Float, y: Float): Boolean {
    var result = false
    var previous = points.size - 2
    for (index in points.indices step 2) {
        val xi = points[index]
        val yi = points[index + 1]
        val xj = points[previous]
        val yj = points[previous + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
        previous = index
    }
    return result
}

private fun boundsOf(points: FloatArray) = floatArrayOf(
    (points.indices step 2).minOf { points[it] }, (points.indices step 2).minOf { points[it + 1] },
    (points.indices step 2).maxOf { points[it] }, (points.indices step 2).maxOf { points[it + 1] },
)

/** A sphere, split into the half toward its sun and the half away from it. */
private fun sphere(name: String, sun: SunSpot, cx: Float, cy: Float, r: Float): LightForm {
    fun side(lit: Boolean): (Float, Float) -> Boolean = { x, y ->
        val dx = (x - cx) / r
        val dy = (y - cy) / r
        val toward = dx * sun.x + dy * sun.y
        dx * dx + dy * dy < 0.85f && if (lit) toward > 0.3f else toward < -0.3f
    }
    val bounds = floatArrayOf(cx - r, cy - r, cx + r, cy + r)
    return LightForm(
        name, sun, Spot(cx, cy), r,
        listOf(Plane("lit side", sun.x, sun.y, bounds, side(true)), Plane("shadow side", -sun.x, -sun.y, bounds, side(false))),
    ) { canvas, width, height, fill, _ -> canvas.drawCircle(cx * width, cy * height, r * min(width, height), fill) }
}

/** A cube seen from slightly above, with its top, left, and right faces. */
private fun cube(sun: SunSpot, cx: Float, cy: Float, size: Float): LightForm {
    val rise = size * 0.55f
    val side = size * 1.36f
    val top = cy - (rise * 2 + side) / 2f
    val faces = listOf(
        floatArrayOf(cx, top, cx + size, top + rise, cx, top + rise * 2, cx - size, top + rise),
        floatArrayOf(cx - size, top + rise, cx, top + rise * 2, cx, top + rise * 2 + side, cx - size, top + rise + side),
        floatArrayOf(cx, top + rise * 2, cx + size, top + rise, cx + size, top + rise + side, cx, top + rise * 2 + side),
    )
    val names = listOf("top face", "left face", "right face")
    val normals = listOf(0f to -1f, -0.89f to 0.45f, 0.89f to 0.45f)
    return LightForm(
        "cube", sun, Spot(cx, cy), size,
        faces.indices.map { index -> Plane(names[index], normals[index].first, normals[index].second, boundsOf(faces[index])) { x, y -> inside(faces[index], x, y) } },
    ) { canvas, width, height, fill, line ->
        faces.forEach { face ->
            val path = Path().apply {
                moveTo(face[0] * width, face[1] * height)
                for (index in 2 until face.size step 2) lineTo(face[index] * width, face[index + 1] * height)
                close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }
    }
}

/** A standing cylinder: its top, and the halves of its side toward the left and the right. */
private fun cylinder(sun: SunSpot, cx: Float, cy: Float, radius: Float, tall: Float): LightForm {
    val tilt = radius * 0.32f
    val top = cy - tall / 2f
    val bottom = cy + tall / 2f
    fun inTop(x: Float, y: Float) = ((x - cx) / radius).let { it * it } + ((y - top) / tilt).let { it * it } < 0.8f
    fun inBody(x: Float, y: Float) = abs(x - cx) < radius * 0.92f && y > top + tilt * 0.5f && y < bottom + tilt * sqrtSafe(1f - ((x - cx) / radius).let { it * it }) - 0.01f
    return LightForm(
        "cylinder", sun, Spot(cx, cy), radius,
        listOf(
            Plane("top", 0f, -1f, floatArrayOf(cx - radius, top - tilt, cx + radius, top + tilt)) { x, y -> inTop(x, y) },
            Plane("left side", -1f, 0f, floatArrayOf(cx - radius, top, cx, bottom + tilt)) { x, y -> x < cx - radius * 0.25f && inBody(x, y) },
            Plane("right side", 1f, 0f, floatArrayOf(cx, top, cx + radius, bottom + tilt)) { x, y -> x > cx + radius * 0.25f && inBody(x, y) },
        ),
    ) { canvas, width, height, fill, line ->
        val left = (cx - radius) * width
        val right = (cx + radius) * width
        val body = Path().apply {
            moveTo(left, top * height)
            lineTo(left, bottom * height)
            arcTo(left, (bottom - tilt) * height, right, (bottom + tilt) * height, 180f, -180f, false)
            lineTo(right, top * height)
            close()
        }
        canvas.drawPath(body, fill)
        canvas.drawPath(body, line)
        canvas.drawOval(left, (top - tilt) * height, right, (top + tilt) * height, fill)
        canvas.drawOval(left, (top - tilt) * height, right, (top + tilt) * height, line)
    }
}

private fun sqrtSafe(value: Float) = kotlin.math.sqrt(max(0f, value))

internal val SPHERE_NAMES = listOf("left sphere", "middle sphere", "right sphere")

/** Names of forms and planes that notes put in their sentences, so they can be translated. */
internal val FORM_WORDS = SPHERE_NAMES + listOf(
    "sphere", "cube", "cylinder", "lit side", "shadow side", "top face", "left face", "right face",
    "top", "left side", "right side", "lower lip", "upper lip",
)

/** The forms an exercise lights, each with its own sun, placed and sized for this session. */
internal fun lightForms(kind: WarmupKind, layout: WarmupLayout): List<LightForm> = when (kind) {
    WarmupKind.SPHERE_LIGHT -> layout.spheres.mapIndexed { index, (cx, cy, r) ->
        val name = when {
            layout.spheres.size == 1 -> "sphere"
            index == 0 -> "left sphere"
            index == layout.spheres.lastIndex -> "right sphere"
            else -> "middle sphere"
        }
        sphere(name, layout.sphereSuns[index], cx, cy, r)
    }
    WarmupKind.CUBE_SHADOW -> {
        val (cubeX, cylinderX) = if (layout.cubeFirst) 0.3f to 0.72f else 0.72f to 0.3f
        listOf(
            cube(layout.suns[0], cubeX, 0.5f, layout.cubeSize),
            cylinder(layout.suns[1], cylinderX, 0.5f, layout.cylinderRadius, layout.cylinderTall),
        )
    }
    WarmupKind.WARM_COOL, WarmupKind.HUE_SHIFT -> layout.colorSphere.let { (cx, cy, r) -> listOf(sphere("sphere", layout.suns[0], cx, cy, r)) }
    else -> emptyList()
}

/** The sun lighting a face feature. */
internal fun featureSun(kind: WarmupKind, layout: WarmupLayout) = if (kind == WarmupKind.FACE_LIPS) layout.lipSun else layout.faceSun

/** The one sun lighting the whole exercise, when there is only one. */
fun singleSun(kind: WarmupKind, layout: WarmupLayout): SunSpot? = when (kind) {
    WarmupKind.WARM_COOL, WarmupKind.HUE_SHIFT -> layout.suns[0]
    WarmupKind.FACE_EYE, WarmupKind.FACE_NOSE, WarmupKind.FACE_LIPS -> featureSun(kind, layout)
    else -> null
}

/** A soft yellow sun: a warm core that fades into the paper. */
internal fun drawSun(canvas: Canvas, x: Float, y: Float, radius: Float) {
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(x, y, radius * 2.4f, intArrayOf(SUN_CORE, 0x66F2B544, SUN_GLOW), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(x, y, radius * 2.4f, glow)
    canvas.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SUN_CORE })
}

/** Draws each form, a ground under it, and its sun on the side the light comes from. */
internal fun drawLightForms(canvas: Canvas, forms: List<LightForm>, width: Float, height: Float, line: Paint) {
    val side = min(width, height)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FORM_GRAY }
    val ground = Paint(line).apply { strokeWidth = side * 0.003f }
    forms.forEach { form ->
        val floor = form.planes.maxOf { it.bounds[3] }
        canvas.drawLine((form.center.x - form.radius * 1.8f) * width, floor * height, (form.center.x + form.radius * 1.8f) * width, floor * height, ground)
        form.outline(canvas, width, height, fill, line)
        val reach = form.radius * 1.45f + 0.035f
        val sunX = (form.center.x + form.sun.x * reach).coerceIn(0.05f, 0.95f)
        val sunY = (form.center.y + form.sun.y * reach * width / height).coerceIn(0.05f, 0.95f)
        if (forms.size > 1) {
            // A faint ray ties each sun to its own form.
            val ray = Paint(line).apply {
                strokeWidth = side * 0.002f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(side * 0.008f, side * 0.01f), 0f)
                alpha = 150
            }
            val edge = form.radius * 1.08f
            canvas.drawLine(sunX * width, sunY * height, (form.center.x + form.sun.x * edge) * width, (form.center.y + form.sun.y * edge * width / height) * height, ray)
        }
        drawSun(canvas, sunX * width, sunY * height, side * (if (forms.size > 1) 0.018f else 0.026f))
    }
}

private fun meanOver(work: WarmupWork, bounds: FloatArray, inside: (Float, Float) -> Boolean, value: (Int, Int) -> Float): Float? {
    var total = 0f
    var count = 0
    val left = (bounds[0] * work.width).toInt().coerceIn(0, work.width - 1)
    val right = (bounds[2] * work.width).toInt().coerceIn(0, work.width - 1)
    val top = (bounds[1] * work.height).toInt().coerceIn(0, work.height - 1)
    val bottom = (bounds[3] * work.height).toInt().coerceIn(0, work.height - 1)
    for (y in top..bottom) {
        for (x in left..right) {
            if (!inside(x / work.width.toFloat(), y / work.height.toFloat())) continue
            total += value(x, y)
            count++
        }
    }
    return if (count == 0) null else total / count
}

private fun untouched(value: Float) = abs(value - FORM_LUMINANCE) < 0.02f

internal val FORM_LUMINANCE = 0.772f

/** What's wrong with one form: a plane that should be lighter than another, or too little contrast. */
private class FormCheck(val form: LightForm, val planes: List<Float>, val brighter: Plane?, val dimmer: Plane?, val spread: Float, val painted: Boolean)

private fun check(work: WarmupWork, form: LightForm): FormCheck {
    val values = form.planes.map { plane -> meanOver(work, plane.bounds, plane.contains, work::luminance) ?: 1f }
    val facing = form.planes.map { it.normalX * form.sun.x + it.normalY * form.sun.y }
    val wrong = form.planes.indices.flatMap { lit -> form.planes.indices.map { lit to it } }
        .firstOrNull { (lit, dim) -> facing[lit] > facing[dim] + 0.2f && values[lit] < values[dim] - 0.02f }
    val brightest = form.planes.indices.maxBy { facing[it] }
    val darkest = form.planes.indices.minBy { facing[it] }
    return FormCheck(
        form, values, wrong?.let { form.planes[it.first] }, wrong?.let { form.planes[it.second] },
        values[brightest] - values[darkest], values.any { !untouched(it) },
    )
}

/** The cast shadow's spot: on the ground beside the form, away from its sun. */
private fun castArea(form: LightForm): FloatArray {
    val floor = form.planes.maxOf { it.bounds[3] }
    val away = -form.sun.x
    val from = min(form.center.x, form.center.x + away * form.radius * 1.8f) - if (away == 0f) form.radius else 0f
    val to = max(form.center.x, form.center.x + away * form.radius * 1.8f) + if (away == 0f) form.radius else 0f
    return floatArrayOf(from, floor - 0.015f, to, floor + 0.05f)
}

private fun mark(label: String, area: FloatArray) = NoteMark(label, MarkShape.BOX, area[0], area[1], area[2], area[3])

/** Reads each lit form against its own sun, and points at the one that needs the most help. */
internal fun lightFormsReview(work: WarmupWork, kind: WarmupKind): WarmupReview {
    val forms = lightForms(kind, work.layout)
    val checks = forms.map { check(work, it) }
    if (checks.none { it.painted }) return WarmupReview(tr("Shade the forms first, then ask me again."), emptyList())
    val worst = checks.filter { it.painted }.let { painted -> painted.firstOrNull { it.brighter != null } ?: painted.minBy { it.spread } }
    val spheres = kind == WarmupKind.SPHERE_LIGHT
    val missingCast = if (spheres) {
        checks.firstOrNull { it.painted && (meanOver(work, castArea(it.form), { _, _ -> true }, work::luminance) ?: 1f) > 0.8f }
    } else {
        null
    }
    val marks = ArrayList<NoteMark>()
    val text = when {
        worst.brighter != null -> {
            marks += mark(tr("Lighter"), worst.brighter.bounds)
            worst.dimmer?.let { marks += mark(tr("Darker"), it.bounds) }
            tr("On the %1, the %2 should be lighter, since it faces its sun.", tr(worst.form.name), tr(worst.brighter.name))
        }
        worst.spread < 0.12f -> {
            marks += mark(tr("Darker here"), worst.form.planes.minBy { it.normalX * worst.form.sun.x + it.normalY * worst.form.sun.y }.bounds)
            tr("The %1 still looks flat. Darken the side away from its sun.", tr(worst.form.name))
        }
        worst.spread < 0.28f -> tr("Good form overall. The %1 could use more contrast between light and shadow.", tr(worst.form.name))
        checks.all { it.painted } -> tr("Every form turns clearly from its sun to its shadow. Lovely.")
        else -> tr("That one reads well. Now light the others from their own suns.")
    }
    val cast = missingCast?.let {
        marks += mark(tr("Cast shadow here"), castArea(it.form))
        " " + tr("Add a cast shadow beside the %1, away from its sun.", tr(it.form.name))
    }.orEmpty()
    return WarmupReview(text + cast, marks.take(2))
}

/** The construction of a facial feature: lines to draw over, and the areas the shading is read in. */
internal class Feature(val contours: List<List<Spot>>, val lit: Plane, val shade: Plane)

/** Points along a smooth curve through [points], for drawing and comparing with your line. */
private fun smooth(vararg points: Spot, samples: Int = 16): List<Spot> {
    val result = ArrayList<Spot>()
    for (index in 0 until points.size - 1) {
        val p0 = points[max(0, index - 1)]
        val p1 = points[index]
        val p2 = points[index + 1]
        val p3 = points[min(points.size - 1, index + 2)]
        for (step in 0 until samples) {
            val t = step / samples.toFloat()
            val t2 = t * t
            val t3 = t2 * t
            fun blend(a: Float, b: Float, c: Float, d: Float) = 0.5f * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
            result += Spot(blend(p0.x, p1.x, p2.x, p3.x), blend(p0.y, p1.y, p2.y, p3.y))
        }
    }
    result += points.last()
    return result
}

private fun ring(cx: Float, cy: Float, rx: Float, ry: Float, samples: Int = 48) = List(samples + 1) { index ->
    val angle = index * 2f * PI.toFloat() / samples
    Spot(cx + rx * cos(angle), cy + ry * sin(angle))
}

private fun polygon(points: List<Spot>) = FloatArray(points.size * 2) { index -> if (index % 2 == 0) points[index / 2].x else points[index / 2].y }

/** Splits a box into the halves toward and away from a sun. */
private fun halves(bounds: FloatArray, sun: SunSpot, inside: (Float, Float) -> Boolean): Pair<Plane, Plane> {
    val cx = (bounds[0] + bounds[2]) / 2f
    val cy = (bounds[1] + bounds[3]) / 2f
    fun toward(x: Float, y: Float) = (x - cx) * sun.x + (y - cy) * sun.y
    return Plane("lit side", sun.x, sun.y, bounds) { x, y -> inside(x, y) && toward(x, y) > 0.02f } to
        Plane("shadow side", -sun.x, -sun.y, bounds) { x, y -> inside(x, y) && toward(x, y) < -0.02f }
}

/** An eye: lids, iris, pupil, crease, and brow, with the eyeball read for shading. */
private fun eye(sun: SunSpot, look: Float, open: Float): Feature {
    fun lid(y: Float) = 0.52f + (y - 0.52f) * open
    val upper = smooth(Spot(0.2f, 0.53f), Spot(0.34f, lid(0.41f)), Spot(0.52f, lid(0.37f)), Spot(0.68f, lid(0.42f)), Spot(0.8f, 0.51f))
    val lower = smooth(Spot(0.2f, 0.53f), Spot(0.36f, lid(0.6f)), Spot(0.54f, lid(0.62f)), Spot(0.7f, lid(0.58f)), Spot(0.8f, 0.51f))
    val crease = smooth(Spot(0.27f, 0.42f), Spot(0.42f, 0.31f), Spot(0.6f, 0.3f), Spot(0.76f, 0.4f))
    val brow = smooth(Spot(0.16f, 0.32f), Spot(0.34f, 0.2f), Spot(0.58f, 0.18f), Spot(0.84f, 0.27f))
    val almond = polygon(upper + lower.reversed())
    val (lit, shade) = halves(boundsOf(almond), sun) { x, y -> inside(almond, x, y) }
    val iris = 0.5f + look
    return Feature(listOf(upper, lower, crease, brow, ring(iris, 0.49f, 0.1f * min(1f, open), 0.1f * min(1f, open)), ring(iris, 0.49f, 0.04f, 0.04f)), lit, shade)
}

/** A nose from the front: the bridge, the ball, the wings, and the nostrils. */
private fun nose(sun: SunSpot, width: Float): Feature {
    fun w(x: Float) = 0.5f + (x - 0.5f) * width
    val left = smooth(Spot(0.45f, 0.16f), Spot(0.44f, 0.36f), Spot(0.42f, 0.56f))
    val right = smooth(Spot(0.55f, 0.16f), Spot(0.56f, 0.36f), Spot(0.58f, 0.56f))
    val ball = ring(0.5f, 0.64f, 0.075f * width, 0.07f)
    val wingLeft = smooth(Spot(w(0.43f), 0.6f), Spot(w(0.35f), 0.64f), Spot(w(0.36f), 0.73f), Spot(w(0.44f), 0.74f))
    val wingRight = smooth(Spot(w(0.57f), 0.6f), Spot(w(0.65f), 0.64f), Spot(w(0.64f), 0.73f), Spot(w(0.56f), 0.74f))
    val nostrils = listOf(ring(w(0.455f), 0.735f, 0.022f, 0.011f), ring(w(0.545f), 0.735f, 0.022f, 0.011f))
    val outline = polygon(left + wingLeft + wingRight.reversed() + right.reversed())
    val bounds = floatArrayOf(0.33f, 0.14f, 0.67f, 0.78f)
    val (lit, shade) = halves(bounds, sun) { x, y -> x in 0.33f..0.67f && y in 0.14f..0.78f && (inside(outline, x, y) || hypot(x - 0.5f, y - 0.64f) < 0.08f) }
    return Feature(listOf(left, right, ball, wingLeft, wingRight) + nostrils, lit, shade)
}

/** Lips: the upper lip with its cupid's bow, the line between them, and the fuller lower lip. */
private fun lips(full: Float): Feature {
    fun f(y: Float) = 0.53f + (y - 0.53f) * full
    val upper = smooth(Spot(0.22f, 0.53f), Spot(0.36f, f(0.45f)), Spot(0.46f, f(0.42f)), Spot(0.5f, f(0.445f)), Spot(0.54f, f(0.42f)), Spot(0.64f, f(0.45f)), Spot(0.78f, 0.53f))
    val middle = smooth(Spot(0.22f, 0.53f), Spot(0.36f, 0.535f), Spot(0.5f, 0.555f), Spot(0.64f, 0.535f), Spot(0.78f, 0.53f))
    val lower = smooth(Spot(0.22f, 0.53f), Spot(0.34f, f(0.62f)), Spot(0.5f, f(0.66f)), Spot(0.66f, f(0.62f)), Spot(0.78f, 0.53f))
    val philtrum = listOf(smooth(Spot(0.478f, 0.425f), Spot(0.472f, 0.37f)), smooth(Spot(0.522f, 0.425f), Spot(0.528f, 0.37f)))
    val top = polygon(upper + middle.reversed())
    val bottom = polygon(middle + lower.reversed())
    return Feature(
        listOf(upper, middle, lower) + philtrum,
        Plane("lower lip", 0f, -1f, boundsOf(bottom)) { x, y -> inside(bottom, x, y) },
        Plane("upper lip", 0f, 1f, boundsOf(top)) { x, y -> inside(top, x, y) },
    )
}

internal fun feature(kind: WarmupKind, layout: WarmupLayout): Feature? {
    val sun = featureSun(kind, layout)
    val base = when (kind) {
        WarmupKind.FACE_EYE -> eye(sun, layout.eyeLook, layout.eyeOpen)
        WarmupKind.FACE_NOSE -> nose(sun, layout.noseWidth)
        WarmupKind.FACE_LIPS -> lips(layout.lipFullness)
        else -> return null
    }
    return base.turned(layout.featureScale, layout.featureTilt, layout.featureShift)
}

/** The same feature, sized, tilted, and moved a little, so it looks new each session. */
private fun Feature.turned(scale: Float, tilt: Float, shift: Spot): Feature {
    fun move(spot: Spot): Spot {
        val dx = (spot.x - 0.5f) * scale
        val dy = (spot.y - 0.5f) * scale
        return Spot(0.5f + shift.x + dx * cos(tilt) - dy * sin(tilt), 0.5f + shift.y + dx * sin(tilt) + dy * cos(tilt))
    }
    // Areas are checked by moving the point back into the feature's own space.
    fun back(x: Float, y: Float): Spot {
        val dx = x - 0.5f - shift.x
        val dy = y - 0.5f - shift.y
        return Spot(0.5f + (dx * cos(tilt) + dy * sin(tilt)) / scale, 0.5f + (-dx * sin(tilt) + dy * cos(tilt)) / scale)
    }
    fun plane(plane: Plane): Plane {
        val corners = listOf(Spot(plane.bounds[0], plane.bounds[1]), Spot(plane.bounds[2], plane.bounds[1]), Spot(plane.bounds[2], plane.bounds[3]), Spot(plane.bounds[0], plane.bounds[3])).map(::move)
        val bounds = floatArrayOf(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
        return Plane(plane.name, plane.normalX, plane.normalY, bounds) { x, y -> back(x, y).let { plane.contains(it.x, it.y) } }
    }
    return Feature(contours.map { it.map(::move) }, plane(lit), plane(shade))
}

/** Draws a feature's construction lines and its sun. */
internal fun drawFeature(canvas: Canvas, feature: Feature, sun: SunSpot, width: Float, height: Float, line: Paint) {
    feature.contours.forEach { points ->
        val path = Path()
        points.forEachIndexed { index, spot -> if (index == 0) path.moveTo(spot.x * width, spot.y * height) else path.lineTo(spot.x * width, spot.y * height) }
        canvas.drawPath(path, line)
    }
    val sunX = (0.5f + sun.x * 0.4f).coerceIn(0.07f, 0.93f)
    val sunY = (0.5f + sun.y * 0.4f).coerceIn(0.07f, 0.93f)
    drawSun(canvas, sunX * width, sunY * height, min(width, height) * 0.024f)
}

/** Reads how closely you followed the construction, and whether the shading turns the right way. */
internal fun featureReview(work: WarmupWork, kind: WarmupKind): WarmupReview {
    val feature = feature(kind, work.layout) ?: return WarmupReview("", emptyList())
    val guide = feature.contours.flatten()
    val points = work.strokes.flatMap { stroke -> (stroke.indices step 2).map { Spot(stroke[it], stroke[it + 1]) } }
    val lit = meanOver(work, feature.lit.bounds, feature.lit.contains, work::luminance) ?: 1f
    val shade = meanOver(work, feature.shade.bounds, feature.shade.contains, work::luminance) ?: 1f
    val shaded = min(lit, shade) < 0.9f
    if (points.isEmpty() && !shaded) return WarmupReview(tr("Draw over the guide first, then ask me again."), emptyList())
    val marks = ArrayList<NoteMark>()
    val lines = if (points.isEmpty()) {
        ""
    } else {
        val near = 0.015f
        val covered = guide.count { spot -> points.any { hypot(it.x - spot.x, it.y - spot.y) < near } } / guide.size.toFloat()
        val onGuide = points.count { point -> guide.any { hypot(it.x - point.x, it.y - point.y) < near } } / points.size.toFloat()
        when {
            covered < 0.35f -> {
                guide.filter { spot -> points.none { hypot(it.x - spot.x, it.y - spot.y) < near } }
                    .maxByOrNull { spot -> points.minOf { hypot(it.x - spot.x, it.y - spot.y) } }
                    ?.let { marks += NoteMark(tr("Keep going here"), MarkShape.RING, it.x, it.y, 0.04f) }
                tr("You've drawn about %1% of the guide. Keep going around the whole shape.", (covered * 100).toInt())
            }
            onGuide < 0.6f -> {
                points.maxByOrNull { point -> guide.minOf { hypot(it.x - point.x, it.y - point.y) } }
                    ?.let { marks += NoteMark(tr("Drifted here"), MarkShape.RING, it.x, it.y, 0.04f) }
                tr("Your lines wander off the guide. Slow down on the curves.")
            }
            else -> tr("Your lines follow the construction well.")
        }
    }
    val shading = when {
        kind == WarmupKind.FACE_LIPS && !shaded -> tr("Now shade it: the upper lip faces down, so it's usually darker.")
        kind == WarmupKind.FACE_LIPS && shade < lit - 0.04f -> tr("Nice: the upper lip sits in shadow and the lower one catches the light.")
        kind == WarmupKind.FACE_LIPS -> {
            marks += mark(tr("Darker here"), feature.shade.bounds)
            tr("Darken the upper lip. It faces down, away from the light.")
        }
        !shaded -> tr("Now shade it: the side away from the sun is darker.")
        shade < lit - 0.05f -> tr("The shading turns away from the sun nicely.")
        else -> {
            marks += mark(tr("Darker here"), feature.shade.bounds)
            tr("Darken the side away from the sun so it turns into form.")
        }
    }
    return WarmupReview("$lines $shading".trim(), marks.take(2))
}
