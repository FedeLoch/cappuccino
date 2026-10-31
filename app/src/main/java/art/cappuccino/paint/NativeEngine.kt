package art.cappuccino.paint

import java.nio.ByteBuffer

/** Bridge to the C++ engine. Every call except loading must run on the GL thread. */
object NativeEngine {
    /** Floats per sample: x, y, pressure, tilt, and azimuth. */
    const val SAMPLE_STRIDE = 5

    init {
        System.loadLibrary("cappuccino")
    }

    external fun create(width: Int, height: Int): Long
    external fun destroy(handle: Long)
    external fun createLayer(handle: Long, id: Int)
    external fun deleteLayer(handle: Long, id: Int)
    external fun setActiveLayer(handle: Long, id: Int)
    external fun setLayerProperties(
        handle: Long,
        id: Int,
        opacity: Float,
        visible: Boolean,
        blend: Int,
    )
    external fun moveLayer(handle: Long, id: Int, index: Int)
    external fun takeLayerDirty(handle: Long, id: Int): Boolean
    external fun readLayer(handle: Long, id: Int, pixels: ByteBuffer)
    external fun writeLayer(handle: Long, id: Int, pixels: ByteBuffer)
    external fun readComposite(handle: Long, width: Int, height: Int, pixels: ByteBuffer)
    external fun pickColor(handle: Long, x: Int, y: Int): Int
    external fun beginStroke(
        handle: Long,
        diameter: Float,
        red: Float,
        green: Float,
        blue: Float,
        flow: Float,
        minScale: Float,
        spacingRatio: Float,
        pressureFlow: Float,
        hardness: Float,
        grain: Float,
        eraser: Boolean,
    )
    external fun drawFront(
        handle: Long,
        projection: FloatArray,
        view: FloatArray,
        bufferWidth: Int,
        bufferHeight: Int,
        samples: FloatArray,
        predicted: FloatArray,
        end: Boolean,
    )
    external fun cancelStroke(handle: Long)
    external fun compose(
        handle: Long,
        projection: FloatArray,
        view: FloatArray,
        bufferWidth: Int,
        bufferHeight: Int,
    )
    external fun undo(handle: Long)
    external fun redo(handle: Long)
}
