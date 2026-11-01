package art.cappuccino.paint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Mood { IDLE, TIP, THINKING, NAP }

enum class CompanionLook(val label: String) {
    PAINT_DROP("Paint drop"),
    INK_BLOT("Ink blot"),
    FOAM_CUP("Foam cup"),
    COFFEE_DROP("Coffee drop"),
    FLAT_WHITE("Flat white"),
}

private const val BLINK_EVERY_MS = 4200L
private const val BLINK_MS = 140L
private const val CREAM = 0xFFF1EFE8.toInt()
private const val INK = 0xFF2C2C2A.toInt()
private const val COFFEE = 0xFF6B4226.toInt()
private const val COFFEE_LINE = 0xFF4A2C1A.toInt()
private const val MILKY_COFFEE = 0xFFC69A6B.toInt()
private const val CREMA = 0xFFA87448.toInt()
private const val STEAM = 0xFFD2C0AC.toInt()
private const val SPARKLE = 0xFFFAC775.toInt()
private const val MOTION_MS = 70L
private const val STEAM_SPEED = 1.1f
private const val STEAM_CYCLE = 2.8f
private const val STEAM_SOFT = 0.22f
private const val STEAM_BOTTOM = -29f
private const val STEAM_HEIGHT = 22f
private const val STEAM_SWAY = 2.2f
private const val STEAM_LINE = 2.6f
private const val STEAM_STEPS = 12
private const val SPARKLE_EVERY = 3.5f
private const val SPARKLE_SHARE = 0.32f
private const val SPARKLE_SIZE = 6f
private const val ROSETTA_SQUASH = 0.27f
private const val ROSETTA_SCALE = 0.72f
private val ROSETTA_LINES = listOf(26f to 36f, 7f to 34f, -12f to 24f)
private const val PEEK_FACE_SHIFT = 12f
private const val HAND_TILT = 20f

/** Draws the companion in a 120 by 120 box, with its center at the origin. */
class CompanionView(context: Context) : View(context) {
    var look = CompanionLook.PAINT_DROP
        set(value) {
            field = value
            invalidate()
            removeCallbacks(motionLoop)
            if (isAttachedToWindow) post(motionLoop)
        }
    var mood = Mood.IDLE
        set(value) { field = value; invalidate() }
    var bodyColor = ACCENT
        set(value) { field = value; invalidate() }

    var outline = CREAM
        set(value) { field = value; invalidate() }

    /** How far the flat white's animation has run, in seconds. */
    var motionTime = 0f
        set(value) { field = value; invalidate() }

    /** Rests the animation, as while you draw. */
    var still = false

    /** From 0 to 1, moves the face toward the open side as the body hides behind the toolbar. */
    var peekShare = 0f
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private var blinking = false

    private val drop = Path().apply {
        moveTo(0f, -50f)
        cubicTo(0f, -50f, -34f, -8f, -34f, 14f)
        cubicTo(-34f, 36f, -18f, 46f, 0f, 46f)
        cubicTo(18f, 46f, 34f, 36f, 34f, 14f)
        cubicTo(34f, -8f, 0f, -50f, 0f, -50f)
        close()
    }
    private val blot = Path().apply {
        moveTo(0f, -42f)
        cubicTo(24f, -48f, 36f, -28f, 40f, -10f)
        cubicTo(50f, 8f, 42f, 30f, 22f, 36f)
        cubicTo(8f, 48f, -18f, 46f, -30f, 34f)
        cubicTo(-50f, 30f, -54f, 0f, -40f, -14f)
        cubicTo(-38f, -34f, -18f, -44f, 0f, -42f)
        close()
    }

    private val cup = Path().apply {
        moveTo(-34f, -14f)
        lineTo(34f, -14f)
        lineTo(28f, 26f)
        cubicTo(26f, 38f, 16f, 42f, 8f, 42f)
        lineTo(-8f, 42f)
        cubicTo(-16f, 42f, -26f, 38f, -28f, 26f)
        close()
    }
    private val foamHeart = Path().apply {
        moveTo(0f, -8f)
        cubicTo(-16f, -14f, -14f, -24f, -6f, -23f)
        cubicTo(-3f, -23f, -1f, -21f, 0f, -19f)
        cubicTo(1f, -21f, 3f, -23f, 6f, -23f)
        cubicTo(14f, -24f, 16f, -14f, 0f, -8f)
        close()
    }

    private val foamCap = Path().apply {
        moveTo(-40f, -60f)
        lineTo(40f, -60f)
        lineTo(40f, -15f)
        lineTo(24f, -15f)
        quadTo(17f, -2f, 9f, -12f)
        quadTo(2f, 3f, -7f, -11f)
        quadTo(-15f, -3f, -24f, -15f)
        lineTo(-40f, -15f)
        close()
    }

    private val bowl = Path().apply {
        moveTo(-40f, -12f)
        lineTo(40f, -12f)
        cubicTo(40f, 14f, 26f, 34f, 10f, 36f)
        lineTo(-10f, 36f)
        cubicTo(-26f, 34f, -40f, 14f, -40f, -12f)
        close()
    }

    /** The white leaf of a poured rosetta, wide near the drinker and pointed at the far side. */
    private val rosetta = Path().apply {
        moveTo(0f, -38f)
        cubicTo(14f, -26f, 34f, 0f, 27f, 22f)
        cubicTo(22f, 36f, 8f, 41f, 0f, 41f)
        cubicTo(-8f, 41f, -22f, 36f, -27f, 22f)
        cubicTo(-34f, 0f, -14f, -26f, 0f, -38f)
        close()
    }

    /** The fine lines of coffee a barista's wiggle leaves in the milk. */
    private val rosettaLines = Path().apply {
        ROSETTA_LINES.forEach { (y, reach) ->
            moveTo(-reach, y - 13f)
            quadTo(-reach * 0.45f, y - 1f, 0f, y)
            quadTo(reach * 0.45f, y - 1f, reach, y - 13f)
        }
        moveTo(0f, 40f)
        lineTo(0f, -30f)
    }

    /** Keeps the flat white's steam moving. It rests whenever nothing should compete with your stroke. */
    private val motionLoop = object : Runnable {
        override fun run() {
            if (look != CompanionLook.FLAT_WHITE) return
            if (!still) {
                motionTime += MOTION_MS / 1000f
                invalidate()
            }
            postDelayed(this, MOTION_MS)
        }
    }

    private val blinkLoop = object : Runnable {
        override fun run() {
            blinking = !blinking
            invalidate()
            postDelayed(this, if (blinking) BLINK_MS else BLINK_EVERY_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blinkLoop, BLINK_EVERY_MS)
        post(motionLoop)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkLoop)
        removeCallbacks(motionLoop)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(width / 120f, width / 120f)
        when (look) {
            CompanionLook.PAINT_DROP -> {
                drawBody(canvas, drop, bodyColor)
                drawFace(canvas, 10f, if (Color.luminance(bodyColor) > 0.35f) INK else CREAM)
            }
            CompanionLook.INK_BLOT -> {
                drawBody(canvas, blot, INK)
                fill.color = INK
                canvas.drawCircle(46f, -34f, 5f, fill)
                canvas.drawCircle(-48f, 38f, 4f, fill)
                drawFace(canvas, 2f, CREAM)
            }
            CompanionLook.FOAM_CUP -> drawFoamCup(canvas)
            CompanionLook.FLAT_WHITE -> drawFlatWhite(canvas)
            CompanionLook.COFFEE_DROP -> {
                drawBody(canvas, drop, COFFEE)
                fill.color = CREAM
                canvas.save()
                canvas.clipPath(drop)
                canvas.drawPath(foamCap, fill)
                canvas.restore()
                line.color = outline
                canvas.drawPath(drop, line)
                drawFace(canvas, 14f, CREAM)
            }
        }
    }

    private fun drawBody(canvas: Canvas, shape: Path, color: Int) {
        fill.color = color
        canvas.drawPath(shape, fill)
        line.color = outline
        canvas.drawPath(shape, line)
    }

    private fun drawFoamCup(canvas: Canvas) {
        line.color = COFFEE_LINE
        canvas.drawArc(26f, -6f, 52f, 24f, -90f, 180f, false, line)
        fill.color = CREAM
        canvas.drawPath(cup, fill)
        canvas.drawPath(cup, line)
        fill.color = COFFEE
        canvas.drawOval(-36f, -30f, 36f, -2f, fill)
        canvas.drawOval(-36f, -30f, 36f, -2f, line)
        fill.color = bodyColor
        canvas.drawPath(foamHeart, fill)
        drawFace(canvas, 14f, INK)
    }

    private fun drawFlatWhite(canvas: Canvas) {
        drawSteam(canvas, -10f, 0f, 1f)
        drawSteam(canvas, 11f, 0.5f, 0.74f)
        if (mood == Mood.IDLE) drawSparkle(canvas)
        line.color = COFFEE_LINE
        canvas.drawOval(22f, -6f, 54f, 20f, line)
        fill.color = CREAM
        canvas.drawPath(bowl, fill)
        canvas.drawPath(bowl, line)
        fill.color = CREMA
        canvas.drawOval(-42f, -26f, 42f, 2f, fill)
        fill.color = MILKY_COFFEE
        canvas.drawOval(-36f, -24f, 36f, 0f, fill)
        canvas.drawOval(-42f, -26f, 42f, 2f, line)
        fill.color = CREAM
        canvas.save()
        canvas.translate(0f, -12f)
        canvas.scale(ROSETTA_SCALE, ROSETTA_SQUASH)
        canvas.drawPath(rosetta, fill)
        line.color = MILKY_COFFEE
        line.strokeWidth = 4f
        canvas.drawPath(rosettaLines, line)
        line.strokeWidth = 3.5f
        canvas.restore()
        drawFace(canvas, 11.5f, INK)
    }

    /** A wisp that lifts off the coffee, sways as it climbs, and fades away before the next one. */
    private fun drawSteam(canvas: Canvas, x: Float, offset: Float, height: Float) {
        val time = motionTime / STEAM_CYCLE + offset
        val life = time - floor(time)
        val head = min(1f, life * 1.7f)
        val tail = max(0f, (life - 0.4f) / 0.6f)
        val sway = motionTime * STEAM_SPEED + offset * 6.3f
        line.color = STEAM
        line.strokeWidth = STEAM_LINE
        var lastX = x
        var lastY = STEAM_BOTTOM
        for (step in 0..STEAM_STEPS) {
            val share = step / STEAM_STEPS.toFloat()
            val nextX = x + sin(share * 6.3f - sway) * (1f + share * STEAM_SWAY)
            val nextY = STEAM_BOTTOM - share * STEAM_HEIGHT * height
            val glow = min((share - tail) / STEAM_SOFT, (head - share) / STEAM_SOFT).coerceIn(0f, 1f)
            if (step > 0 && glow > 0f) {
                line.alpha = (glow * (1f - share * share * 0.5f) * 255f).toInt()
                canvas.drawLine(lastX, lastY, nextX, nextY, line)
            }
            lastX = nextX
            lastY = nextY
        }
        line.alpha = 255
        line.strokeWidth = 3.5f
    }

    /** A soft gold dot that comes and goes beside the cup, on one side and then the other. */
    private fun drawSparkle(canvas: Canvas) {
        val round = motionTime / SPARKLE_EVERY
        val share = (round - floor(round)) / SPARKLE_SHARE
        if (share >= 1f) return
        val pulse = sin(share * PI.toFloat())
        // While it peeks, the left side is behind the toolbar.
        val right = round.toInt() % 2 == 0 || peekShare > 0.5f
        fill.color = SPARKLE
        fill.alpha = (pulse * 255f).toInt()
        canvas.drawCircle(if (right) 42f else -46f, if (right) -40f else -32f, pulse * SPARKLE_SIZE, fill)
        fill.alpha = 255
    }

    private fun drawFace(canvas: Canvas, y: Float, color: Int) {
        canvas.save()
        canvas.translate(PEEK_FACE_SHIFT * peekShare, 0f)
        drawExpression(canvas, y, color)
        canvas.restore()
    }

    private fun drawExpression(canvas: Canvas, y: Float, color: Int) {
        fill.color = color
        line.color = color
        when (mood) {
            Mood.NAP -> {
                canvas.drawArc(-19f, y - 6f, -7f, y + 6f, 20f, 140f, false, line)
                canvas.drawArc(7f, y - 6f, 19f, y + 6f, 20f, 140f, false, line)
            }
            Mood.TIP -> {
                canvas.drawCircle(-13f, y, 6.5f, fill)
                canvas.drawCircle(13f, y, 6.5f, fill)
                canvas.drawOval(-5f, y + 12f, 5f, y + 20f, fill)
                fill.color = 0xFFFAC775.toInt()
                canvas.drawCircle(42f - PEEK_FACE_SHIFT * peekShare, -42f, 8f, fill)
                return
            }
            Mood.THINKING -> {
                canvas.drawCircle(-10f, y - 4f, 4.5f, fill)
                canvas.drawCircle(16f, y - 4f, 4.5f, fill)
                canvas.drawLine(-5f, y + 15f, 7f, y + 13f, line)
                return
            }
            Mood.IDLE -> {
                if (blinking) {
                    canvas.drawLine(-18f, y, -8f, y, line)
                    canvas.drawLine(8f, y, 18f, y, line)
                } else {
                    canvas.drawCircle(-13f, y, 4.5f, fill)
                    canvas.drawCircle(13f, y, 4.5f, fill)
                }
            }
        }
        canvas.drawArc(-8f, y + 4f, 8f, y + 18f, 30f, 120f, false, line)
    }
}

/** Two tiny hands holding the toolbar's corner, drawn above it. */
class CompanionHands(context: Context) : View(context) {
    var look = CompanionLook.PAINT_DROP
        set(value) { field = value; invalidate() }
    var bodyColor = ACCENT
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val halfWidth = context.dp(6).toFloat()
        val halfHeight = context.dp(4).toFloat()
        drawHand(canvas, context.dp(9).toFloat(), halfHeight + context.dp(1), -HAND_TILT, halfWidth, halfHeight)
        drawHand(canvas, context.dp(14).toFloat(), height - halfHeight - context.dp(1), HAND_TILT, halfWidth, halfHeight)
    }

    private fun drawHand(canvas: Canvas, x: Float, y: Float, tilt: Float, halfWidth: Float, halfHeight: Float) {
        canvas.save()
        canvas.rotate(tilt, x, y)
        box.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        paint.style = Paint.Style.FILL
        paint.color = when (look) {
            CompanionLook.PAINT_DROP -> bodyColor
            CompanionLook.INK_BLOT -> INK
            CompanionLook.FOAM_CUP, CompanionLook.FLAT_WHITE -> CREAM
            CompanionLook.COFFEE_DROP -> COFFEE
        }
        canvas.drawOval(box, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1).toFloat()
        paint.color = if (look == CompanionLook.FOAM_CUP || look == CompanionLook.FLAT_WHITE) COFFEE_LINE else CREAM
        canvas.drawOval(box, paint)
        canvas.restore()
    }
}
