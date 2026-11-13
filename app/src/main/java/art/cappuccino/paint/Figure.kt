package art.cappuccino.paint

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The figure guide: points you drag onto a drawn body or face, and plain notes on how its
 * proportions read, from the classic rules for a figure about seven and a half heads tall.
 * Points are canvas pixels, x then y for each. Joints run in the order of model3d.h.
 */

const val HEAD_TOP = 0
const val CHIN = 1
const val NECK = 2
const val PELVIS = 9
const val BODY_JOINTS = 16

/** Each joint's parent, so moving a joint carries everything that hangs from it. */
val BODY_PARENTS = intArrayOf(1, 2, 9, 2, 3, 4, 2, 6, 7, -1, 9, 10, 11, 9, 13, 14)

/** The standing pose in head units, x to the picture's right, y up, z toward you, feet at zero. */
val STANDARD_POSE = floatArrayOf(
    0f, 7.5f, 0f, 0f, 6.5f, 0.05f, 0f, 6.2f, 0f,
    0.9f, 6f, 0f, 1f, 4.6f, -0.05f, 1.05f, 3.45f, 0.05f,
    -0.9f, 6f, 0f, -1f, 4.6f, -0.05f, -1.05f, 3.45f, 0.05f,
    0f, 3.75f, 0f,
    0.5f, 3.6f, 0f, 0.52f, 1.8f, 0.05f, 0.5f, 0.25f, -0.05f,
    -0.5f, 3.6f, 0f, -0.52f, 1.8f, 0.05f, -0.5f, 0.25f, -0.05f,
)

/** The guide's bones, from parent to child. */
val BODY_BONES = (0 until BODY_JOINTS).filter { BODY_PARENTS[it] >= 0 }.map { BODY_PARENTS[it] to it }

/** A limb as named in notes, and the bone it is. Sides are named as they appear in the picture. */
private class Limb(val name: String, val from: Int, val to: Int)

private fun limbs(rightArm: Int, leftArm: Int, rightLeg: Int, leftLeg: Int) = listOf(
    Limb("upper arm on the right", rightArm, rightArm + 1), Limb("forearm on the right", rightArm + 1, rightArm + 2),
    Limb("upper arm on the left", leftArm, leftArm + 1), Limb("forearm on the left", leftArm + 1, leftArm + 2),
    Limb("thigh on the right", rightLeg, rightLeg + 1), Limb("shin on the right", rightLeg + 1, rightLeg + 2),
    Limb("thigh on the left", leftLeg, leftLeg + 1), Limb("shin on the left", leftLeg + 1, leftLeg + 2),
)

/** One note on the figure. [text] is English with %1 marks for [values]; [joints] are the points it's about. */
class FigureNote(val text: String, val values: List<String> = emptyList(), val joints: Set<Int> = emptySet(), val warning: Boolean = true)

private fun standardLength(from: Int, to: Int): Float {
    val dx = STANDARD_POSE[to * 3] - STANDARD_POSE[from * 3]
    val dy = STANDARD_POSE[to * 3 + 1] - STANDARD_POSE[from * 3 + 1]
    val dz = STANDARD_POSE[to * 3 + 2] - STANDARD_POSE[from * 3 + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun heads(value: Float) = (value * 10).roundToInt().let { "${it / 10}.${it % 10}" }

/** A standing figure filling most of the canvas height, centered. */
fun defaultBody(width: Int, height: Int): FloatArray {
    val unit = height * 0.85f / 7.6f
    val left = width / 2f
    val bottom = height * 0.925f
    return FloatArray(BODY_JOINTS * 2) { index ->
        val joint = index / 2
        if (index % 2 == 0) left + STANDARD_POSE[joint * 3] * unit else bottom - STANDARD_POSE[joint * 3 + 1] * unit
    }
}

/** Moves a joint and everything that hangs from it. */
fun moveJoint(points: FloatArray, joint: Int, dx: Float, dy: Float) {
    for (index in 0 until BODY_JOINTS) {
        var walk = index
        while (walk >= 0 && walk != joint) walk = BODY_PARENTS[walk]
        if (walk == joint) {
            points[index * 2] += dx
            points[index * 2 + 1] += dy
        }
    }
}

/** How the figure's proportions read, warnings first. */
fun analyzeBody(points: FloatArray): List<FigureNote> {
    fun x(joint: Int) = points[joint * 2]
    fun y(joint: Int) = points[joint * 2 + 1]
    fun span(a: Int, b: Int) = hypot(x(b) - x(a), y(b) - y(a))
    val head = span(HEAD_TOP, CHIN)
    if (head < 1f) return emptyList()
    val notes = ArrayList<FigureNote>()
    val rightArm = if (x(3) >= x(6)) 3 else 6
    val rightLeg = if (x(10) >= x(13)) 10 else 13
    val limbs = limbs(rightArm, 9 - rightArm, rightLeg, 23 - rightLeg)
    val lowest = max(y(12), y(15))
    val standing = lowest - y(PELVIS) > head * 2.5f && y(PELVIS) > y(NECK)

    if (standing) {
        val tall = (lowest - y(HEAD_TOP)) / head + 0.15f
        val reading = when {
            tall < 5.5f -> "That reads young or stylized."
            tall < 7f -> "That's a sturdy, natural build."
            tall <= 8.2f -> "That's close to the classic seven and a half."
            else -> "That's long and heroic, as in fashion or comics."
        }
        notes += FigureNote("The figure is %1 heads tall.", listOf(heads(tall)), warning = false)
        notes += FigureNote(reading, warning = false)
    }

    val torso = span(NECK, PELVIS) / head
    if (torso < 1.9f) notes += FigureNote("The head reads big for the body, like a child or a cartoon.", joints = setOf(HEAD_TOP, CHIN))
    if (torso > 3.2f) notes += FigureNote("The head reads small for the body.", joints = setOf(HEAD_TOP, CHIN))

    val shoulders = span(3, 6) / head
    if (shoulders < 1.3f) {
        notes += FigureNote("The shoulders are %1 heads across. About two is typical, unless the body turns.", listOf(heads(shoulders)), setOf(3, 6))
    } else if (shoulders > 2.6f) {
        notes += FigureNote("The shoulders are %1 heads across, which reads very broad. About two is typical.", listOf(heads(shoulders)), setOf(3, 6))
    }

    val ratios = limbs.associateWith { span(it.from, it.to) / head / standardLength(it.from, it.to) }
    val flagged = HashSet<Limb>()
    for (limb in limbs) {
        val ratio = ratios.getValue(limb)
        val typical = standardLength(limb.from, limb.to)
        if (ratio > 1.2f) {
            flagged += limb
            notes += FigureNote(
                "The %1 is %2 heads long; it's usually about %3, so it reads long.",
                listOf(limb.name, heads(ratio * typical), heads(typical)), setOf(limb.from, limb.to),
            )
        } else if (ratio < 0.7f) {
            flagged += limb
            notes += FigureNote(
                "The %1 reads short. That's right if it points toward or away from us.",
                listOf(limb.name), setOf(limb.from, limb.to), warning = false,
            )
        }
    }
    // Matching limbs on each side, where neither already has a note.
    for (pair in limbs.chunked(2).let { listOf(it[0][0] to it[1][0], it[0][1] to it[1][1], it[2][0] to it[3][0], it[2][1] to it[3][1]) }) {
        if (pair.first in flagged || pair.second in flagged) continue
        val a = ratios.getValue(pair.first)
        val b = ratios.getValue(pair.second)
        if (max(a, b) / max(min(a, b), 0.01f) > 1.25f) {
            notes += FigureNote(
                "The %1 and the %2 differ in length. Unless one turns toward us, match them.",
                listOf(pair.first.name, pair.second.name), setOf(pair.first.from, pair.first.to, pair.second.from, pair.second.to),
            )
        }
    }

    // Where hanging arms land: elbows at the waist, wrists at the crotch.
    if (standing) {
        for ((shoulder, side) in listOf(rightArm to "right", 9 - rightArm to "left")) {
            val elbow = shoulder + 1
            val wrist = shoulder + 2
            val hanging = y(wrist) > y(elbow) && y(elbow) > y(shoulder) &&
                abs(x(elbow) - x(shoulder)) < (y(elbow) - y(shoulder)) * 0.5f &&
                abs(x(wrist) - x(elbow)) < (y(wrist) - y(elbow)) * 0.6f
            if (!hanging) continue
            val waist = y(PELVIS) - head * 0.85f
            val crotch = y(PELVIS) + head * 0.25f
            if (abs(y(elbow) - waist) > head * 0.5f) {
                notes += FigureNote(
                    if (y(elbow) < waist) "The elbow on the %1 sits high. A hanging arm bends at the waist." else "The elbow on the %1 sits low. A hanging arm bends at the waist.",
                    listOf(side), setOf(elbow),
                )
            } else if (abs(y(wrist) - crotch) > head * 0.55f) {
                notes += FigureNote(
                    if (y(wrist) < crotch) "The wrist on the %1 sits high. A hanging arm reaches the crotch at the wrist." else "The wrist on the %1 sits low. A hanging arm reaches the crotch at the wrist.",
                    listOf(side), setOf(wrist),
                )
            }
        }
        // Balance: standing, the pit of the neck sits above the feet.
        val feetLeft = min(x(12), x(15)) - head * 0.35f
        val feetRight = max(x(12), x(15)) + head * 0.35f
        if (x(NECK) < feetLeft || x(NECK) > feetRight) {
            notes += FigureNote(
                "The neck isn't above the feet, so the figure looks like it's tipping. Move the feet under it, or give it something to lean on.",
                joints = setOf(NECK, 12, 15),
            )
        }
    }
    if (notes.none { it.warning }) notes += FigureNote("The proportions read well.", warning = false)
    return notes.sortedByDescending { it.warning }
}

/** Heights down the figure where landmarks fall, in heads from the top of the head, with their names. */
val BODY_LANDMARKS = listOf(1f to "chin", 2f to "chest", 3f to "elbows", 4f to "wrists", 5.75f to "knees", 7.5f to "feet")

// The face: crown, chin, eye on the left, eye on the right, nose base, mouth.
const val FACE_POINTS = 6

/** Where along the face, from crown to chin, the eyes, nose base, and mouth usually sit. */
val FACE_LEVELS = listOf(0.5f to "eyes", 0.69f to "nose", 0.79f to "mouth")

fun defaultFace(width: Int, height: Int): FloatArray {
    val size = min(width, height) * 0.5f
    val cx = width / 2f
    val top = height / 2f - size / 2f
    return floatArrayOf(
        cx, top, cx, top + size,
        cx - size * 0.15f, top + size * 0.5f, cx + size * 0.15f, top + size * 0.5f,
        cx, top + size * 0.69f, cx, top + size * 0.79f,
    )
}

/** Where a face point sits: how far down from crown to chin, and how far across, both in face lengths. */
fun facePlace(points: FloatArray, index: Int): Pair<Float, Float> {
    val ax = points[2] - points[0]
    val ay = points[3] - points[1]
    val length2 = ax * ax + ay * ay
    if (length2 < 1f) return 0f to 0f
    val px = points[index * 2] - points[0]
    val py = points[index * 2 + 1] - points[1]
    val down = (px * ax + py * ay) / length2
    val across = (px * -ay + py * ax) / length2
    return down to across
}

fun analyzeFace(points: FloatArray): List<FigureNote> {
    val length = hypot(points[2] - points[0], points[3] - points[1])
    if (length < 1f) return emptyList()
    val notes = ArrayList<FigureNote>()
    val (leftDown, leftAcross) = facePlace(points, 2)
    val (rightDown, rightAcross) = facePlace(points, 3)
    val (noseDown, noseAcross) = facePlace(points, 4)
    val (mouthDown, mouthAcross) = facePlace(points, 5)
    val eyes = (leftDown + rightDown) / 2f
    if (abs(eyes - 0.5f) > 0.06f) {
        notes += FigureNote(
            if (eyes < 0.5f) "The eyes sit high. They're usually halfway between the top of the head and the chin." else "The eyes sit low. They're usually halfway between the top of the head and the chin.",
            joints = setOf(2, 3),
        )
    }
    if (abs(leftDown - rightDown) > 0.04f) notes += FigureNote("The eyes aren't level with each other. Tilt the whole head, or level them.", joints = setOf(2, 3))
    val apart = hypot(points[4] - points[6], points[5] - points[7]) / length
    if (apart < 0.22f) notes += FigureNote("The eyes are close together. About one eye's width between them is typical.", joints = setOf(2, 3))
    if (apart > 0.38f) notes += FigureNote("The eyes are far apart. About one eye's width between them is typical.", joints = setOf(2, 3))
    if (abs(noseDown - 0.69f) > 0.07f) {
        notes += FigureNote(
            if (noseDown < 0.69f) "The nose ends high. Its base usually sits a little above two thirds of the way down." else "The nose ends low. Its base usually sits a little above two thirds of the way down.",
            joints = setOf(4),
        )
    }
    if (abs(mouthDown - 0.79f) > 0.06f) {
        notes += FigureNote(
            if (mouthDown < 0.79f) "The mouth sits high. It's usually a third of the way from the nose to the chin." else "The mouth sits low. It's usually a third of the way from the nose to the chin.",
            joints = setOf(5),
        )
    }
    val middle = (leftAcross + rightAcross) / 2f
    if (abs(noseAcross - middle) > 0.08f || abs(mouthAcross - middle) > 0.08f) {
        notes += FigureNote("The nose and mouth aren't between the eyes. If the head turns, move the eyes the same way.", joints = setOf(4, 5))
    }
    if (notes.isEmpty()) notes += FigureNote("The face reads in proportion.", warning = false)
    return notes
}

/** Which way the face guide turns, as yaw and pitch in radians, read from where the features sit. */
fun faceTurn(points: FloatArray): Pair<Float, Float> {
    val (leftDown, leftAcross) = facePlace(points, 2)
    val (rightDown, rightAcross) = facePlace(points, 3)
    val (_, noseAcross) = facePlace(points, 4)
    // Across runs to the face's own right, which is the picture's left when it faces you.
    val sideways = (leftAcross + rightAcross) / 2f - noseAcross
    val down = (leftDown + rightDown) / 2f - 0.5f
    return asin((sideways / 0.3f).coerceIn(-1f, 1f)) to asin((down / 0.25f).coerceIn(-1f, 1f))
}

/**
 * Lifts the drawn figure into 3D for the mannequin: each bone keeps its usual length, so a bone
 * drawn shorter must point toward or away from you. Arms and thighs come forward, shins go back.
 * Returns joints in head units, as STANDARD_POSE.
 */
fun liftBody(points: FloatArray): FloatArray {
    val head = max(hypot(points[2] - points[0], points[3] - points[1]), 1f)
    val px = points[PELVIS * 2]
    val py = points[PELVIS * 2 + 1]
    val result = FloatArray(BODY_JOINTS * 3)
    for (joint in 0 until BODY_JOINTS) {
        result[joint * 3] = (points[joint * 2] - px) / head
        result[joint * 3 + 1] = -(points[joint * 2 + 1] - py) / head
    }
    val forward = setOf(4, 5, 7, 8, 11, 14)
    val backward = setOf(12, 15)
    // Parents come before children when walking out from the pelvis.
    val order = listOf(PELVIS, NECK, CHIN, HEAD_TOP, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15)
    for (joint in order) {
        val parent = BODY_PARENTS[joint]
        if (parent < 0) continue
        val drawn = hypot(result[joint * 3] - result[parent * 3], result[joint * 3 + 1] - result[parent * 3 + 1])
        val usual = standardLength(parent, joint)
        val depth = sqrt(max(0f, usual * usual - drawn * drawn))
        val sign = when (joint) {
            in forward -> 1f
            in backward -> -1f
            else -> 0f
        }
        result[joint * 3 + 2] = result[parent * 3 + 2] + depth * sign
    }
    return result
}

private fun posed(vararg changes: Pair<Int, Triple<Float, Float, Float>>): FloatArray = STANDARD_POSE.copyOf().also { pose ->
    for ((joint, place) in changes) {
        pose[joint * 3] = place.first
        pose[joint * 3 + 1] = place.second
        pose[joint * 3 + 2] = place.third
    }
}

/** Ready poses for the 3D mannequin, in head units, as STANDARD_POSE. */
val MANNEQUIN_POSES = listOf(
    "Stand" to STANDARD_POSE,
    "Walk" to posed(
        4 to Triple(1f, 4.65f, -0.4f), 5 to Triple(1.05f, 3.55f, -0.65f),
        7 to Triple(-1f, 4.6f, 0.35f), 8 to Triple(-1.05f, 3.5f, 0.6f),
        11 to Triple(0.52f, 1.85f, 0.45f), 12 to Triple(0.5f, 0.3f, 0.6f),
        14 to Triple(-0.52f, 1.85f, -0.35f), 15 to Triple(-0.5f, 0.5f, -0.85f),
    ),
    "Sit" to posed(
        4 to Triple(1f, 4.75f, 0.6f), 5 to Triple(0.85f, 4f, 1.5f),
        7 to Triple(-1f, 4.75f, 0.6f), 8 to Triple(-0.85f, 4f, 1.5f),
        11 to Triple(0.55f, 3.5f, 1.8f), 12 to Triple(0.55f, 1.95f, 1.9f),
        14 to Triple(-0.55f, 3.5f, 1.8f), 15 to Triple(-0.55f, 1.95f, 1.9f),
    ),
    "Reach" to posed(4 to Triple(1.1f, 7.35f, 0.1f), 5 to Triple(1.2f, 8.5f, 0.2f)),
)
