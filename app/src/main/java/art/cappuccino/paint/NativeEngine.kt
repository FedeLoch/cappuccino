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
    external fun createMask(handle: Long, id: Int, maskId: Int)
    external fun setLayerMask(handle: Long, id: Int, maskId: Int, enabled: Boolean)
    external fun applyMask(handle: Long, id: Int)
    external fun setSymmetry(handle: Long, mode: Int, x: Float, y: Float)
    external fun beginLiquify(handle: Long, id: Int)
    external fun liquifyDab(
        handle: Long,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float,
        strength: Float,
        mode: Int,
    )
    external fun commitLiquify(handle: Long)
    external fun cancelLiquify(handle: Long)
    external fun fillPolygon(
        handle: Long,
        points: FloatArray,
        red: Float,
        green: Float,
        blue: Float,
        opacity: Float,
        erase: Boolean,
        alphaLock: Boolean,
    ): Boolean
    external fun setActiveLayer(handle: Long, id: Int)
    external fun setLayerProperties(
        handle: Long,
        id: Int,
        opacity: Float,
        visible: Boolean,
        blend: Int,
        clip: Boolean,
    )
    external fun setLayerOrder(handle: Long, ids: IntArray)
    external fun markLayerDirty(handle: Long, id: Int)
    external fun duplicateLayer(handle: Long, id: Int, newId: Int)
    external fun mergeLayerDown(handle: Long, id: Int, belowId: Int)
    external fun readLayerThumbnail(
        handle: Long,
        id: Int,
        width: Int,
        height: Int,
        pixels: ByteBuffer,
    )
    external fun setViewOptions(handle: Long, mode: Int, guide: Int)
    external fun setPerspective(handle: Long, points: FloatArray)
    external fun setFigureGuide(handle: Long, top: Float, bottom: Float, divisions: Float)
    external fun fillAt(handle: Long, x: Int, y: Int, red: Float, green: Float, blue: Float): Boolean
    external fun setLayerTransform(handle: Long, id: Int, transform: FloatArray)
    external fun commitLayerTransform(handle: Long)
    external fun setSelectionPolygon(handle: Long, points: FloatArray, mode: Int)
    external fun selectColorAt(handle: Long, x: Int, y: Int, tolerance: Int, mode: Int)
    external fun clearSelection(handle: Long)
    external fun invertSelection(handle: Long)
    external fun copySelectionToLayer(handle: Long, newId: Int, cut: Boolean)
    external fun setGradientPreview(handle: Long, active: Boolean, points: FloatArray, color: FloatArray)
    external fun commitGradient(handle: Long)
    external fun setAdjustment(handle: Long, active: Boolean, values: FloatArray)
    external fun commitAdjustment(handle: Long, chained: Boolean = false)
    external fun setFrames(handle: Long, values: FloatArray)
    external fun setFilter(handle: Long, active: Boolean, kind: Int, values: FloatArray, color: FloatArray)
    external fun commitFilter(handle: Long)
    /** Returns left, top, right, and bottom of what changed, or null when nothing did. */
    external fun takeLayerDirty(handle: Long, id: Int): IntArray?
    external fun readLayerRegion(handle: Long, id: Int, left: Int, top: Int, right: Int, bottom: Int, pixels: ByteBuffer)
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
        alphaLock: Boolean,
        smudge: Boolean,
        pressureGamma: Float,
        scatter: Float,
        sizeJitter: Float,
        angleJitter: Float,
        shadeJitter: Float,
        tip: ByteArray?,
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
