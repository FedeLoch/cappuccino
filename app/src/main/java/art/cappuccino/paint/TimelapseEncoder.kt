package art.cappuccino.paint

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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

/** Picks evenly spread frames for a video of [seconds], or keeps them all, then holds the last one. */
fun timelapseTimeline(frameCount: Int, seconds: Int?): List<Int> {
    if (frameCount == 0) return emptyList()
    val wanted = seconds?.let { minOf(it * FRAME_RATE, frameCount) } ?: frameCount
    val picked = List(wanted) { if (wanted == 1) frameCount - 1 else it * (frameCount - 1) / (wanted - 1) }
    return picked + List(HOLD_LAST_FRAMES) { frameCount - 1 }
}

/** Turns saved progress frames into an H.264 video. */
object TimelapseEncoder {
    fun encode(frames: List<File>, width: Int, height: Int, seconds: Int?, output: FileDescriptor) {
        val evenWidth = width and 1.inv()
        val evenHeight = height and 1.inv()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, evenWidth, evenHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1

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
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, data, info)
                        val finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (finished) return
                    }
                }
            }
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (index in timelapseTimeline(frames.size, seconds)) {
            val bitmap = BitmapFactory.decodeFile(frames[index].path) ?: continue
            val canvas = surface.lockHardwareCanvas()
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Matrix().apply { setScale(evenWidth / bitmap.width.toFloat(), evenHeight / bitmap.height.toFloat()) },
                paint,
            )
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
}
