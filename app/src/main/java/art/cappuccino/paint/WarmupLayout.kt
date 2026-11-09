package art.cappuccino.paint

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything a warm-up places on the canvas, made fresh from one [seed] each session, so the same
 * exercise never repeats exactly: where the dots and boxes go, which shapes you trace, how the
 * mannequins pose, how big the forms are, and where each sun sits.
 */
class WarmupLayout(val seed: Int) {
    private val random = Random(seed.toLong())

    private fun between(low: Float, high: Float) = low + random.nextFloat() * (high - low)

    private fun <T> pick(items: List<T>) = items[random.nextInt(items.size)]

    /** Boxes that don't overlap, each at least [minWide] by [minTall], filling the page loosely. */
    private fun boxes(count: Int, minWide: Float, maxWide: Float, minTall: Float, maxTall: Float, margin: Float = 0.06f): List<FloatArray> {
        val placed = ArrayList<FloatArray>()
        var tries = 0
        while (placed.size < count && tries < 400) {
            tries++
            val wide = between(minWide, maxWide)
            val tall = between(minTall, maxTall)
            val left = between(margin, 1f - margin - wide)
            val top = between(margin, 1f - margin - tall)
            val box = floatArrayOf(left, top, left + wide, top + tall)
            val gap = 0.03f
            if (placed.none { box[0] < it[2] + gap && it[0] < box[2] + gap && box[1] < it[3] + gap && it[1] < box[3] + gap }) placed += box
        }
        return placed
    }

    /** Pairs of dots to join, at varied lengths and angles, each end kept on the page. */
    val dots: List<Pair<Spot, Spot>> = run {
        val pairs = ArrayList<Pair<Spot, Spot>>()
        var tries = 0
        while (pairs.size < 6 && tries < 600) {
            tries++
            val start = Spot(between(0.14f, 0.92f), between(0.08f, 0.9f))
            val angle = between(0f, 2f * PI.toFloat())
            val length = between(0.22f, 0.58f)
            val end = Spot(start.x + cos(angle) * length, start.y + sin(angle) * length)
            if (end.x !in 0.14f..0.92f || end.y !in 0.08f..0.9f) continue
            val ends = pairs.flatMap { listOf(it.first, it.second) }
            if (ends.any { hypot(it.x - start.x, it.y - start.y) < 0.08f || hypot(it.x - end.x, it.y - end.y) < 0.08f }) continue
            pairs += start to end
        }
        pairs
    }

    val hatchBoxes: List<FloatArray> = boxes(between(3f, 4.99f).toInt(), 0.14f, 0.3f, 0.18f, 0.42f)

    val ellipseBoxes: List<FloatArray> = boxes(between(5f, 7.99f).toInt(), 0.1f, 0.3f, 0.1f, 0.32f)

    /** Five boxes for a value scale, in a row, a column, or a gentle step. */
    val valueBoxes: List<FloatArray> = run {
        val size = between(0.11f, 0.16f)
        val gap = between(0.01f, 0.03f)
        val run = size * 5 + gap * 4
        when (random.nextInt(3)) {
            0 -> {
                val left = between(0.05f, 0.95f - run)
                val top = between(0.15f, 0.85f - size)
                List(5) { floatArrayOf(left + it * (size + gap), top, left + it * (size + gap) + size, top + size) }
            }
            1 -> {
                val left = between(0.15f, 0.85f - size)
                val top = between(0.05f, 0.95f - run)
                List(5) { floatArrayOf(left, top + it * (size + gap), left + size, top + it * (size + gap) + size) }
            }
            else -> {
                val left = between(0.05f, 0.95f - run)
                val rise = between(0.04f, 0.08f)
                val top = between(0.1f + rise * 4, 0.9f - size)
                List(5) { floatArrayOf(left + it * (size + gap), top - it * rise, left + it * (size + gap) + size, top - it * rise + size) }
            }
        }
    }

    /** Shapes to trace: a few motifs, each placed, sized, and turned at random. */
    val tracePaths: List<List<Spot>> = run {
        val cells = listOf(Spot(0.27f, 0.27f), Spot(0.73f, 0.27f), Spot(0.27f, 0.73f), Spot(0.73f, 0.73f)).shuffled(random)
        val motifs = TRACE_MOTIFS.shuffled(random).take(4)
        motifs.indices.flatMap { index ->
            val cell = cells[index]
            val scale = between(0.75f, 1.05f)
            val angle = between(-0.5f, 0.5f)
            val center = Spot(cell.x + between(-0.03f, 0.03f), cell.y + between(-0.03f, 0.03f))
            motifs[index]().map { path -> path.map { place(it, center, scale * 0.42f, angle) } }
        }
    }

    /** Mannequins to fill as silhouettes, in varied poses, sizes, and spacing. */
    internal val figures: List<Figure> = run {
        val count = 4 + random.nextInt(3)
        val ground = between(0.72f, 0.86f)
        val slot = 0.84f / count
        List(count) { index ->
            val scale = between(0.7f, 1.15f)
            val x = 0.08f + slot * (index + 0.5f) + between(-slot * 0.15f, slot * 0.15f)
            Figure.posed(pick(POSES), x, ground - between(0f, 0.08f), scale, random.nextBoolean(), random)
        }
    }

    /** Spheres resting on one ground, each sized on its own. */
    val spheres: List<FloatArray> = run {
        val count = 2 + random.nextInt(3)
        val ground = between(0.6f, 0.72f)
        val slot = 0.86f / count
        List(count) { index ->
            val radius = min(slot * 0.36f, between(0.07f, 0.14f))
            floatArrayOf(0.07f + slot * (index + 0.5f), ground - radius, radius)
        }
    }

    /** The cube and cylinder: which comes first, and how big each is. */
    val cubeFirst = random.nextBoolean()
    val cubeSize = between(0.11f, 0.16f)
    val cylinderRadius = between(0.08f, 0.13f)
    val cylinderTall = between(0.22f, 0.34f)

    /** The single sphere for color studies. */
    val colorSphere = floatArrayOf(between(0.4f, 0.6f), between(0.42f, 0.55f), between(0.18f, 0.25f))

    /** A sun for each form, never the same spot twice in a row. */
    val suns: List<SunSpot> = run {
        val spots = SUN_SPOTS.shuffled(random)
        List(6) { spots[it % spots.size] }
    }

    /**
     * A sun for each sphere. The ones in the middle are lit from above, so no sun sits between two
     * spheres, and the outer ones may also be lit from their open side.
     */
    val sphereSuns: List<SunSpot> = spheres.indices.map { index ->
        val options = SUN_SPOTS.filter { sun ->
            sun.y < -0.5f || (index == 0 && sun.x < -0.9f) || (index == spheres.lastIndex && sun.x > 0.9f)
        }
        pick(options)
    }

    /** A sun for faces, which look best lit from a side. */
    val faceSun: SunSpot = pick(SUN_SPOTS.filter { it.x != 0f })

    /** A sun for lips, which are lit from above. */
    val lipSun: SunSpot = pick(SUN_SPOTS.filter { it.y < -0.5f })

    /** How the face features vary: size, tilt, where the eye looks, and how full the lips are. */
    val featureScale = between(0.82f, 1.08f)
    val featureTilt = between(-0.12f, 0.12f)
    val featureShift = Spot(between(-0.05f, 0.05f), between(-0.04f, 0.04f))
    val eyeLook = between(-0.05f, 0.05f)
    val eyeOpen = between(0.75f, 1.15f)
    val noseWidth = between(0.85f, 1.2f)
    val lipFullness = between(0.75f, 1.25f)

    /** The landscape for value and color studies. */
    val farHills: FloatArray = FloatArray(14) { index -> if (index % 2 == 0) index / 12f else between(0.26f, 0.46f) }
    val nearHills: FloatArray = FloatArray(12) { index -> if (index % 2 == 0) index / 10f else between(0.52f, 0.62f) }
    val houseX = if (random.nextBoolean()) between(0.55f, 0.72f) else between(0.12f, 0.3f)
    val houseWide = between(0.14f, 0.2f)
    val houseTop = between(0.6f, 0.66f)
    val trees: List<Spot> = List(2 + random.nextInt(3)) {
        var x: Float
        do {
            x = between(0.08f, 0.9f)
        } while (abs(x - (houseX + houseWide / 2f)) < houseWide * 0.9f)
        Spot(x, between(0.72f, 0.84f))
    }
    val moon = Spot(if (houseX > 0.5f) between(0.1f, 0.3f) else between(0.7f, 0.9f), between(0.1f, 0.18f))

    /** Two-point perspective: the horizon, its two vanishing points, and the front corners of the boxes. */
    val horizon = between(0.3f, 0.48f)
    val vanishLeft = Spot(between(0.03f, 0.15f), horizon)
    val vanishRight = Spot(between(0.85f, 0.97f), horizon)
    val corners: List<FloatArray> = run {
        val count = 2 + random.nextInt(2)
        val slot = 0.56f / count
        List(count) { index ->
            val x = 0.22f + slot * (index + 0.5f) + between(-slot * 0.2f, slot * 0.2f)
            val above = random.nextInt(3) != 0
            val tall = between(0.12f, 0.22f)
            val top = if (above) horizon + between(0.08f, 0.2f) else horizon - tall - between(0.06f, 0.14f)
            floatArrayOf(x, top, x, top + tall)
        }
    }

    /** A long box to fill with a smooth blend, and which end starts dark. */
    val blendBox: FloatArray = run {
        val long = between(0.6f, 0.8f)
        val short = between(0.12f, 0.2f)
        if (random.nextBoolean()) {
            val left = between(0.08f, 0.92f - long)
            val top = between(0.2f, 0.8f - short)
            floatArrayOf(left, top, left + long, top + short)
        } else {
            val left = between(0.2f, 0.8f - short)
            val top = between(0.08f, 0.92f - long)
            floatArrayOf(left, top, left + short, top + long)
        }
    }
    val blendDarkFirst = random.nextBoolean()

    /** Half a vase-like outline to mirror across the middle line. */
    val mirrorHalf: List<Spot> = run {
        val knots = List(6) { index -> Spot(0.5f - between(0.06f, 0.28f), 0.16f + index * 0.136f) }
        List(101) { index ->
            val t = index / 100f * (knots.size - 1)
            val i = t.toInt().coerceAtMost(knots.size - 2)
            val f = t - i
            val ease = (1f - cos(f * PI.toFloat())) / 2f
            Spot(knots[i].x + (knots[i + 1].x - knots[i].x) * ease, knots[i].y + (knots[i + 1].y - knots[i].y) * f)
        }
    }

    /** Writing lines for lettering, one row per word: left, cap line, x-height, baseline, and right. */
    val letterRows: List<FloatArray> = run {
        val tall = between(0.12f, 0.16f)
        val small = tall * between(0.5f, 0.62f)
        val gap = (0.78f - LETTER_ROWS * tall) / (LETTER_ROWS - 1)
        List(LETTER_ROWS) { index ->
            val cap = 0.12f + index * (tall + gap)
            floatArrayOf(0.1f, cap, cap + tall - small, cap + tall, 0.9f)
        }
    }

    /** A word to letter on each row. */
    val letterWords: List<String> = LETTER_WORDS.shuffled(random).take(LETTER_ROWS)

    private fun abs(value: Float) = kotlin.math.abs(value)
}

private const val LETTER_ROWS = 3

/** Words with both tall and small letters, so each row practices both heights. */
private val LETTER_WORDS = listOf(
    "harbor", "quiet", "ember", "lantern", "willow", "drift", "marble", "hollow",
    "copper", "meadow", "velvet", "orchard", "thistle", "biscuit", "kindle", "hearth",
)

/** Turns a point from a motif's own space, centered on zero, into a place on the page. */
private fun place(point: Spot, center: Spot, scale: Float, angle: Float) = Spot(
    center.x + (point.x * cos(angle) - point.y * sin(angle)) * scale,
    center.y + (point.x * sin(angle) + point.y * cos(angle)) * scale,
)

private fun motifOval(cx: Float, cy: Float, rx: Float, ry: Float, from: Float = 0f, to: Float = 2f * PI.toFloat(), samples: Int = 60) =
    List(samples + 1) { index ->
        val angle = from + (to - from) * index / samples
        Spot(cx + rx * cos(angle), cy + ry * sin(angle))
    }

private fun root(value: Float) = sqrt(max(0f, value))

/** Shapes to trace, each drawn in its own space from about -0.5 to 0.5. */
private val TRACE_MOTIFS: List<() -> List<List<Spot>>> = listOf(
    {
        val length = 0.9f
        val width = 0.24f
        fun edge(sign: Float) = List(41) { index ->
            val t = index / 40f
            Spot((t - 0.5f) * length, sign * width * root(sin(t * PI.toFloat())) * (1f - 0.3f * t))
        }
        val rib = List(21) { index -> Spot((index / 20f - 0.5f) * length * 1.08f, 0f) }
        val veins = listOf(0.3f, 0.5f, 0.7f).flatMap { t ->
            listOf(1f, -1f).map { sign -> List(8) { index -> Spot((t - 0.5f) * length + index / 7f * length * 0.16f, sign * index / 7f * width * 0.7f) } }
        }
        listOf(edge(1f), edge(-1f), rib) + veins
    },
    {
        listOf(
            motifOval(0f, -0.12f, 0.34f, 0.08f),
            List(31) { index ->
                val angle = PI.toFloat() * index / 30f
                Spot(-0.34f * cos(angle), -0.12f + 0.44f * root(sin(angle)))
            },
            motifOval(0.36f, 0.08f, 0.1f, 0.13f, -1.4f, 1.4f),
            motifOval(0f, 0.36f, 0.5f, 0.07f),
            List(30) { index -> val t = index / 29f; Spot(-0.09f + 0.05f * sin(t * 2.2f * PI.toFloat()), -0.26f - 0.24f * t) },
            List(30) { index -> val t = index / 29f; Spot(0.09f + 0.05f * sin(t * 2.2f * PI.toFloat() + 1.3f), -0.24f - 0.22f * t) },
        )
    },
    {
        listOf(List(160) { index ->
            val t = index / 159f
            Spot(-0.48f + 0.96f * t + 0.07f * sin(t * 10f * PI.toFloat()), 0.13f * cos(t * 10f * PI.toFloat()))
        })
    },
    {
        listOf(List(140) { index ->
            val t = index / 139f
            val angle = t * 5f * PI.toFloat()
            val radius = 0.03f + 0.4f * t
            Spot(radius * cos(angle), radius * sin(angle))
        })
    },
    {
        listOf(List(121) { index ->
            val t = index / 120f
            val angle = t * 2f * PI.toFloat()
            val radius = 0.22f + 0.2f * (if (index % 24 < 12) (index % 12) / 12f else 1f - (index % 12) / 12f)
            Spot(radius * cos(angle - PI.toFloat() / 2), radius * sin(angle - PI.toFloat() / 2))
        })
    },
    {
        listOf(List(121) { index ->
            val t = index / 120f * 2f * PI.toFloat()
            Spot(0.026f * 16f * sin(t).let { it * it * it }, -0.026f * (13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t)))
        })
    },
    {
        listOf(List(160) { index ->
            val t = index / 159f
            Spot(-0.48f + 0.96f * t, 0.16f * sin(t * 4f * PI.toFloat()) * (1f - 0.5f * t))
        })
    },
    {
        val petals = 6
        listOf(List(181) { index ->
            val angle = index / 180f * 2f * PI.toFloat()
            val radius = 0.2f + 0.22f * kotlin.math.abs(sin(angle * petals / 2f))
            Spot(radius * cos(angle), radius * sin(angle))
        }, motifOval(0f, 0f, 0.09f, 0.09f))
    },
)

/** One pose: two arms, then two legs, each as its middle joint and its end, with the chest lean. */
internal class Pose(val lean: Float, val joints: FloatArray)

/** Standing, walking, reaching, kneeling, jumping, waving, and sitting on the ground. */
private val POSES = listOf(
    Pose(0.0f, floatArrayOf(-0.04f, 0.45f, -0.05f, 0.53f, 0.04f, 0.45f, 0.05f, 0.53f, -0.02f, 0.62f, -0.025f, 0.73f, 0.02f, 0.62f, 0.03f, 0.73f)),
    Pose(0.015f, floatArrayOf(-0.035f, 0.44f, -0.06f, 0.5f, 0.045f, 0.45f, 0.07f, 0.49f, -0.03f, 0.62f, -0.07f, 0.72f, 0.05f, 0.62f, 0.05f, 0.73f)),
    Pose(-0.01f, floatArrayOf(-0.035f, 0.31f, -0.04f, 0.22f, 0.045f, 0.36f, 0.07f, 0.29f, -0.025f, 0.62f, -0.03f, 0.73f, 0.025f, 0.62f, 0.035f, 0.73f)),
    Pose(0.02f, floatArrayOf(-0.04f, 0.45f, -0.02f, 0.51f, 0.05f, 0.44f, 0.08f, 0.47f, -0.04f, 0.62f, -0.07f, 0.66f, 0.05f, 0.6f, 0.05f, 0.72f)),
    Pose(0.0f, floatArrayOf(-0.055f, 0.34f, -0.085f, 0.27f, 0.055f, 0.34f, 0.085f, 0.27f, -0.045f, 0.58f, -0.02f, 0.66f, 0.04f, 0.6f, 0.07f, 0.66f)),
    Pose(-0.015f, floatArrayOf(-0.04f, 0.46f, -0.045f, 0.54f, 0.06f, 0.33f, 0.09f, 0.26f, -0.02f, 0.62f, -0.03f, 0.73f, 0.03f, 0.62f, 0.04f, 0.73f)),
    Pose(0.03f, floatArrayOf(-0.03f, 0.45f, -0.06f, 0.53f, 0.06f, 0.44f, 0.09f, 0.5f, 0.07f, 0.56f, 0.1f, 0.66f, 0.08f, 0.58f, 0.13f, 0.62f)),
)

/** A posed mannequin to fill as a silhouette: its head, chest, and hips, then elbows, hands, knees, and feet. */
internal class Figure(val head: Spot, val neck: Spot, val chest: Spot, val hips: Spot, val shoulders: List<Spot>, val hipJoints: List<Spot>, val limbs: List<Pair<Spot, Spot>>, val scale: Float) {
    private val all = listOf(head, chest, hips) + limbs.flatMap { listOf(it.first, it.second) }
    val box = floatArrayOf(all.minOf { it.x } - 0.03f * scale, head.y - 0.04f * scale, all.maxOf { it.x } + 0.03f * scale, all.maxOf { it.y } + 0.02f * scale)

    companion object {
        /** Places a pose standing on [ground], at [scale], mirrored if [flip], with a little wobble in each joint. */
        fun posed(pose: Pose, x: Float, ground: Float, scale: Float, flip: Boolean, random: Random): Figure {
            val sign = if (flip) -1f else 1f
            fun at(dx: Float, y: Float) = Spot(x + sign * dx * scale, ground - (0.73f - y) * scale)
            fun jitter() = (random.nextFloat() - 0.5f) * 0.016f
            val joints = (pose.joints.indices step 2).map { at(pose.joints[it] + jitter(), pose.joints[it + 1] + jitter()) }
            val chest = at(pose.lean * 0.5f, 0.4f)
            val hips = at(pose.lean, 0.52f)
            return Figure(
                at(0f, 0.3f), at(0f, 0.33f), chest, hips,
                listOf(at(pose.lean * 0.5f - 0.028f, 0.37f), at(pose.lean * 0.5f + 0.028f, 0.37f)),
                listOf(at(pose.lean - 0.018f, 0.53f), at(pose.lean + 0.018f, 0.53f)),
                joints.chunked(2).map { it[0] to it[1] }, scale,
            )
        }
    }
}
