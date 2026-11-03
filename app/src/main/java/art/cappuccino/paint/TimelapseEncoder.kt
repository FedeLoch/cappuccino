package art.cappuccino.paint

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor

private const val FRAME_RATE = 15
private const val BIT_RATE = 3_000_000
private const val HOLD_LAST_FRAMES = 45
private const val TIMEOUT_US = 10_000L
private const val OUTRO_DELAY_FRAMES = 5
private const val OUTRO_POP_FRAMES = 8
private const val OUTRO_HEIGHT = 0.11f
private const val OUTRO_MARGIN = 0.035f

/** Picks evenly spread frames for a video of [seconds], or keeps them all, then holds the last one. */
fun timelapseTimeline(frameCount: Int, seconds: Int?): List<Int> {
    if (frameCount == 0) return emptyList()
    val wanted = seconds?.let { minOf(it * FRAME_RATE, frameCount) } ?: frameCount
    val picked = List(wanted) { if (wanted == 1) frameCount - 1 else it * (frameCount - 1) / (wanted - 1) }
    return picked + List(HOLD_LAST_FRAMES) { frameCount - 1 }
}

/** Turns saved progress frames into an H.264 video. */
object TimelapseEncoder {
    /**
     * Encodes the video. A [companion] pops into the corner while the finished piece holds at the end,
     * with [signature] beside it.
     */
    fun encode(
        frames: List<File>,
        width: Int,
        height: Int,
        seconds: Int?,
        output: FileDescriptor,
        companion: CompanionView? = null,
        signature: String = "",
    ) {
        val timeline = timelapseTimeline(frames.size, seconds)
        val outroStart = timeline.size - HOLD_LAST_FRAMES + OUTRO_DELAY_FRAMES
        encodeFrames(frames, width, height, timeline, FRAME_RATE, output, companion, signature, outroStart)
    }

    /** Plays [frames] in the order of [timeline], at [frameRate] frames a second. */
    fun encodeFrames(
        frames: List<File>,
        width: Int,
        height: Int,
        timeline: List<Int>,
        frameRate: Int,
        output: FileDescriptor,
        companion: CompanionView? = null,
        signature: String = "",
        outroStart: Int = Int.MAX_VALUE,
    ) {
        val evenWidth = width and 1.inv()
        val evenHeight = height and 1.inv()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, evenWidth, evenHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var written = 0L

        fun drain(endOfStream: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val data = codec.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            // A surface stamps frames with the time they were drawn, which runs faster
                            // than the video should. Each frame gets its own slot instead.
                            info.presentationTimeUs = written++ * 1_000_000L / frameRate
                            muxer.writeSampleData(track, data, info)
                        }
                        val finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (finished) return
                    }
                }
            }
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((position, index) in timeline.withIndex()) {
            val bitmap = BitmapFactory.decodeFile(frames[index].path) ?: continue
            val canvas = surface.lockHardwareCanvas()
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Matrix().apply { setScale(evenWidth / bitmap.width.toFloat(), evenHeight / bitmap.height.toFloat()) },
                paint,
            )
            if (companion != null && position >= outroStart) {
                drawOutro(canvas, companion, signature, evenWidth, evenHeight, position - outroStart)
            }
            surface.unlockCanvasAndPost(canvas)
            bitmap.recycle()
            drain(false)
        }
        codec.signalEndOfInputStream()
        drain(true)
        codec.stop()
        codec.release()
        surface.release()
        if (track >= 0) muxer.stop()
        muxer.release()
    }

    /** A small dark note in the corner, like the app's own, that grows in with a little bounce. */
    private fun drawOutro(canvas: Canvas, companion: CompanionView, signature: String, width: Int, height: Int, frame: Int) {
        val t = (frame + 1f) / OUTRO_POP_FRAMES
        val pop = if (t >= 1f) 1f else 1f + 2.7f * (t - 1f).let { it * it * it } + 1.7f * (t - 1f).let { it * it }
        val tall = minOf(width, height) * OUTRO_HEIGHT
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = tall * 0.32f
        }
        val wide = tall * 1.05f + text.measureText(signature) + tall * 0.4f
        val margin = minOf(width, height) * OUTRO_MARGIN
        val box = RectF(width - margin - wide, height - margin - tall, width - margin, height - margin)
        canvas.save()
        canvas.scale(pop, pop, box.right, box.bottom)
        canvas.drawRoundRect(box, tall / 2f, tall / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROAST })
        val size = (tall * 0.9f).toInt()
        companion.motionTime = frame / FRAME_RATE.toFloat()
        companion.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        companion.layout(0, 0, size, size)
        canvas.save()
        canvas.translate(box.left + tall * 0.12f, box.top + (tall - size) / 2f)
        companion.draw(canvas)
        canvas.restore()
        canvas.drawText(signature, box.left + tall * 1.05f, box.centerY() - (text.ascent() + text.descent()) / 2f, text)
        canvas.restore()
    }
}
