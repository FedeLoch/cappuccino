#include <jni.h>

#include <vector>

#include <cstring>
#include <mutex>
#include <string>

#include "model3d.h"
#include "renderer.h"

using cappuccino::Renderer;
using cappuccino::Sample;

namespace {

Renderer* renderer(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

// The 3D drawing aid. There's one, shared by every artwork, since only one studio is open.
cappuccino::ModelScene& modelScene() {
    static cappuccino::ModelScene scene;
    return scene;
}

// The preview draws on a background thread while the studio places models on the GL thread.
std::mutex& modelLock() {
    static std::mutex lock;
    return lock;
}

cappuccino::ModelView modelView(const float* values) {
    cappuccino::ModelView view;
    view.yaw = values[0];
    view.pitch = values[1];
    view.lens = values[2];
    view.centerX = values[3];
    view.centerY = values[4];
    view.size = values[5];
    view.lightAzimuth = values[6];
    view.lightElevation = values[7];
    view.style = static_cast<int>(values[8]);
    return view;
}

class FloatArray {
public:
    FloatArray(JNIEnv* env, jfloatArray array)
        : env_(env), array_(array), data_(env->GetFloatArrayElements(array, nullptr)) {}
    ~FloatArray() { env_->ReleaseFloatArrayElements(array_, data_, JNI_ABORT); }
    const float* get() const { return data_; }
    int size() const { return env_->GetArrayLength(array_); }

private:
    JNIEnv* env_;
    jfloatArray array_;
    jfloat* data_;
};

std::vector<Sample> toSamples(const FloatArray& values) {
    constexpr int kStride = sizeof(Sample) / sizeof(float);
    std::vector<Sample> samples;
    samples.reserve(values.size() / kStride);
    for (int i = 0; i + kStride <= values.size(); i += kStride) {
        const float* value = values.get() + i;
        samples.push_back({value[0], value[1], value[2], value[3], value[4]});
    }
    return samples;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_art_cappuccino_paint_NativeEngine_create(JNIEnv*, jobject, jint width,
                                                                      jint height) {
    return reinterpret_cast<jlong>(new Renderer(width, height));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_destroy(JNIEnv*, jobject,
                                                                      jlong handle) {
    delete renderer(handle);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_createLayer(JNIEnv*, jobject,
                                                                          jlong handle, jint id) {
    renderer(handle)->createLayer(id);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_createMask(JNIEnv*, jobject,
                                                                         jlong handle, jint id,
                                                                         jint maskId) {
    renderer(handle)->createMask(id, maskId);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setLayerMask(
    JNIEnv*, jobject, jlong handle, jint id, jint maskId, jboolean enabled) {
    renderer(handle)->setLayerMask(id, maskId, enabled);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_applyMask(JNIEnv*, jobject,
                                                                        jlong handle, jint id) {
    renderer(handle)->applyMask(id);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setSymmetry(
    JNIEnv*, jobject, jlong handle, jint mode, jfloat x, jfloat y) {
    renderer(handle)->setSymmetry(mode, x, y);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_beginLiquify(JNIEnv*, jobject,
                                                                           jlong handle, jint id) {
    renderer(handle)->beginLiquify(id);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_liquifyDab(
    JNIEnv*, jobject, jlong handle, jfloat fromX, jfloat fromY, jfloat toX, jfloat toY,
    jfloat radius, jfloat strength, jint mode) {
    renderer(handle)->liquifyDab(fromX, fromY, toX, toY, radius, strength,
                                 static_cast<cappuccino::LiquifyMode>(mode));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_commitLiquify(JNIEnv*, jobject,
                                                                            jlong handle) {
    renderer(handle)->commitLiquify();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_cancelLiquify(JNIEnv*, jobject,
                                                                            jlong handle) {
    renderer(handle)->cancelLiquify();
}

JNIEXPORT jboolean JNICALL Java_art_cappuccino_paint_NativeEngine_fillPolygon(
    JNIEnv* env, jobject, jlong handle, jfloatArray points, jfloat red, jfloat green, jfloat blue,
    jfloat opacity, jboolean erase, jboolean alphaLock) {
    const FloatArray values(env, points);
    const float color[3] = {red, green, blue};
    return renderer(handle)->fillPolygon(values.get(), values.size() / 2, color, opacity, erase,
                                         alphaLock);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_deleteLayer(JNIEnv*, jobject,
                                                                          jlong handle, jint id) {
    renderer(handle)->deleteLayer(id);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setActiveLayer(JNIEnv*, jobject,
                                                                             jlong handle,
                                                                             jint id) {
    renderer(handle)->setActiveLayer(id);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setLayerProperties(
    JNIEnv*, jobject, jlong handle, jint id, jfloat opacity, jboolean visible, jint blend,
    jboolean clip) {
    renderer(handle)->setLayerProperties(id, opacity, visible,
                                         static_cast<cappuccino::BlendMode>(blend), clip);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setLayerOrder(JNIEnv* env, jobject,
                                                                            jlong handle,
                                                                            jintArray ids) {
    jint* values = env->GetIntArrayElements(ids, nullptr);
    renderer(handle)->setLayerOrder(values, env->GetArrayLength(ids));
    env->ReleaseIntArrayElements(ids, values, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_markLayerDirty(JNIEnv*, jobject,
                                                                             jlong handle,
                                                                             jint id) {
    renderer(handle)->markLayerDirty(id);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_duplicateLayer(JNIEnv*, jobject,
                                                                             jlong handle, jint id,
                                                                             jint newId) {
    renderer(handle)->duplicateLayer(id, newId);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_mergeLayerDown(JNIEnv*, jobject,
                                                                             jlong handle, jint id,
                                                                             jint belowId) {
    renderer(handle)->mergeLayerDown(id, belowId);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_readLayerThumbnail(
    JNIEnv* env, jobject, jlong handle, jint id, jint width, jint height, jobject buffer) {
    renderer(handle)->readLayerThumbnail(id, width, height, env->GetDirectBufferAddress(buffer));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setViewOptions(JNIEnv*, jobject,
                                                                             jlong handle,
                                                                             jint mode,
                                                                             jint guide) {
    renderer(handle)->setViewOptions(mode, guide);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setPerspective(JNIEnv* env, jobject,
                                                                             jlong handle,
                                                                             jfloatArray points) {
    const FloatArray values(env, points);
    renderer(handle)->setPerspective(values.get(), values.size() / 2);
}

JNIEXPORT jboolean JNICALL Java_art_cappuccino_paint_NativeEngine_fillAt(
    JNIEnv*, jobject, jlong handle, jint x, jint y, jfloat red, jfloat green, jfloat blue) {
    const float color[3] = {red, green, blue};
    return renderer(handle)->fillAt(x, y, color);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setLayerTransform(
    JNIEnv* env, jobject, jlong handle, jint id, jfloatArray transform) {
    const FloatArray values(env, transform);
    renderer(handle)->setLayerTransform(id, values.get());
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_commitLayerTransform(JNIEnv*, jobject,
                                                                                   jlong handle) {
    renderer(handle)->commitLayerTransform();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setSelectionPolygon(
    JNIEnv* env, jobject, jlong handle, jfloatArray points, jint mode) {
    const FloatArray values(env, points);
    renderer(handle)->setSelectionPolygon(values.get(), values.size() / 2, mode);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_selectColorAt(
    JNIEnv*, jobject, jlong handle, jint x, jint y, jint tolerance, jint mode) {
    renderer(handle)->selectColorAt(x, y, tolerance, mode);
}

JNIEXPORT jintArray JNICALL Java_art_cappuccino_paint_NativeEngine_selectionBounds(
    JNIEnv* env, jobject, jlong handle) {
    jint bounds[4] = {};
    if (!renderer(handle)->selectionBounds(bounds)) return nullptr;
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, bounds);
    return result;
}

JNIEXPORT jboolean JNICALL Java_art_cappuccino_paint_NativeEngine_selectLayer(
    JNIEnv*, jobject, jlong handle, jint id, jint mode) {
    return renderer(handle)->selectLayer(id, mode) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_clearSelection(JNIEnv*, jobject,
                                                                             jlong handle) {
    renderer(handle)->clearSelection();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_invertSelection(JNIEnv*, jobject,
                                                                              jlong handle) {
    renderer(handle)->invertSelection();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setSelectionFeather(
    JNIEnv*, jobject, jlong handle, jint radius) {
    renderer(handle)->setSelectionFeather(radius);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_copySelectionToLayer(
    JNIEnv*, jobject, jlong handle, jint newId, jboolean cut) {
    renderer(handle)->copySelectionToLayer(newId, cut);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setGradientPreview(
    JNIEnv* env, jobject, jlong handle, jboolean active, jfloatArray points, jfloatArray color) {
    const FloatArray pointValues(env, points);
    const FloatArray colorValues(env, color);
    renderer(handle)->setGradientPreview(active, pointValues.get(), colorValues.get());
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_commitGradient(JNIEnv*, jobject,
                                                                             jlong handle) {
    renderer(handle)->commitGradient();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setAdjustment(
    JNIEnv* env, jobject, jlong handle, jboolean active, jfloatArray values) {
    const FloatArray adjustment(env, values);
    renderer(handle)->setAdjustment(active, adjustment.get());
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_commitAdjustment(JNIEnv*, jobject,
                                                                               jlong handle,
                                                                               jboolean chained) {
    renderer(handle)->commitAdjustment(chained);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setFrames(JNIEnv* env, jobject,
                                                                        jlong handle,
                                                                        jfloatArray values) {
    const FloatArray frames(env, values);
    renderer(handle)->setFrames(frames.get(), frames.size());
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setFilter(
    JNIEnv* env, jobject, jlong handle, jboolean active, jint kind, jfloatArray values,
    jfloatArray color) {
    const FloatArray filterValues(env, values);
    const FloatArray filterColor(env, color);
    renderer(handle)->setFilter(active, kind, filterValues.get(), filterColor.get());
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_commitFilter(JNIEnv*, jobject,
                                                                           jlong handle) {
    renderer(handle)->commitFilter();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_setFigureGuide(
    JNIEnv*, jobject, jlong handle, jfloat top, jfloat bottom, jfloat divisions) {
    renderer(handle)->setFigureGuide(top, bottom, divisions);
}

JNIEXPORT jintArray JNICALL Java_art_cappuccino_paint_NativeEngine_takeLayerDirty(JNIEnv* env,
                                                                                  jobject,
                                                                                  jlong handle,
                                                                                  jint id) {
    const cappuccino::Rect area = renderer(handle)->takeLayerDirty(id);
    if (area.empty()) return nullptr;
    const jint values[4] = {area.left, area.top, area.right, area.bottom};
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_readLayerRegion(
    JNIEnv* env, jobject, jlong handle, jint id, jint left, jint top, jint right, jint bottom,
    jobject buffer) {
    renderer(handle)->readLayerRegion(id, {left, top, right, bottom},
                                      env->GetDirectBufferAddress(buffer));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_readLayer(JNIEnv* env, jobject,
                                                                        jlong handle, jint id,
                                                                        jobject buffer) {
    renderer(handle)->readLayer(id, env->GetDirectBufferAddress(buffer));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_writeLayer(JNIEnv* env, jobject,
                                                                         jlong handle, jint id,
                                                                         jobject buffer) {
    renderer(handle)->writeLayer(id, env->GetDirectBufferAddress(buffer));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_readComposite(
    JNIEnv* env, jobject, jlong handle, jint width, jint height, jobject buffer) {
    renderer(handle)->readComposite(width, height, env->GetDirectBufferAddress(buffer));
}

JNIEXPORT jint JNICALL Java_art_cappuccino_paint_NativeEngine_pickLayerColor(JNIEnv*, jobject,
                                                                             jlong handle, jint x,
                                                                             jint y) {
    return static_cast<jint>(renderer(handle)->pickLayerColor(x, y));
}

JNIEXPORT jint JNICALL Java_art_cappuccino_paint_NativeEngine_pickColor(JNIEnv*, jobject,
                                                                        jlong handle, jint x,
                                                                        jint y) {
    return static_cast<jint>(renderer(handle)->pickColor(x, y));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_beginStroke(
    JNIEnv* env, jobject, jlong handle, jfloat diameter, jfloat red, jfloat green, jfloat blue,
    jfloat flow, jfloat minScale, jfloat spacingRatio, jfloat pressureFlow, jfloat hardness,
    jfloat grain, jboolean eraser, jboolean alphaLock, jboolean smudge, jfloat pressureGamma,
    jfloat scatter, jfloat sizeJitter, jfloat angleJitter, jfloat shadeJitter, jfloat hueJitter, jbyteArray tip) {
    cappuccino::BrushParams brush;
    brush.diameter = diameter;
    brush.color[0] = red;
    brush.color[1] = green;
    brush.color[2] = blue;
    brush.shape.flow = flow;
    brush.shape.minScale = minScale;
    brush.shape.spacingRatio = spacingRatio;
    brush.shape.pressureFlow = pressureFlow;
    brush.hardness = hardness;
    brush.grain = grain;
    brush.eraser = eraser;
    brush.alphaLock = alphaLock;
    brush.smudge = smudge;
    brush.shape.pressureGamma = pressureGamma;
    brush.shape.scatter = scatter;
    brush.shape.sizeJitter = sizeJitter;
    brush.shape.angleJitter = angleJitter;
    brush.shape.shadeJitter = shadeJitter;
    brush.shape.hueJitter = hueJitter;
    if (tip != nullptr) {
        brush.tip.resize(env->GetArrayLength(tip));
        env->GetByteArrayRegion(tip, 0, static_cast<jsize>(brush.tip.size()),
                                reinterpret_cast<jbyte*>(brush.tip.data()));
    }
    renderer(handle)->beginStroke(brush);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_drawFront(
    JNIEnv* env, jobject, jlong handle, jfloatArray projection, jfloatArray view, jint bufferWidth,
    jint bufferHeight, jfloatArray samples, jfloatArray predicted, jboolean end) {
    const FloatArray projectionValues(env, projection);
    const FloatArray viewValues(env, view);
    const FloatArray sampleValues(env, samples);
    const FloatArray predictedValues(env, predicted);
    const std::vector<Sample> stroke = toSamples(sampleValues);
    const std::vector<Sample> prediction = toSamples(predictedValues);
    renderer(handle)->drawFront(projectionValues.get(), viewValues.get(), bufferWidth, bufferHeight,
                                stroke.data(), static_cast<int>(stroke.size()), prediction.data(),
                                static_cast<int>(prediction.size()), end);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_cancelStroke(JNIEnv*, jobject,
                                                                           jlong handle) {
    renderer(handle)->cancelStroke();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_compose(
    JNIEnv* env, jobject, jlong handle, jfloatArray projection, jfloatArray view, jint bufferWidth,
    jint bufferHeight) {
    const FloatArray projectionValues(env, projection);
    const FloatArray viewValues(env, view);
    renderer(handle)->compose(projectionValues.get(), viewValues.get(), bufferWidth, bufferHeight);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_undo(JNIEnv*, jobject, jlong handle) {
    renderer(handle)->undo();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_redo(JNIEnv*, jobject, jlong handle) {
    renderer(handle)->redo();
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_modelShape(JNIEnv*, jobject, jint shape) {
    const std::lock_guard<std::mutex> guard(modelLock());
    modelScene().setShape(static_cast<cappuccino::ModelShape>(shape));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_modelPose(JNIEnv* env, jobject,
                                                                        jfloatArray joints) {
    const FloatArray values(env, joints);
    const std::lock_guard<std::mutex> guard(modelLock());
    if (values.size() >= cappuccino::kFigureJoints * 3) modelScene().setPose(values.get());
}

JNIEXPORT jboolean JNICALL Java_art_cappuccino_paint_NativeEngine_modelLoadObj(JNIEnv* env, jobject,
                                                                               jstring text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    const std::lock_guard<std::mutex> guard(modelLock());
    const bool loaded = modelScene().loadObj(chars);
    env->ReleaseStringUTFChars(text, chars);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_modelRender(
    JNIEnv* env, jobject, jfloatArray view, jint width, jint height, jobject buffer) {
    const FloatArray values(env, view);
    auto* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    std::memset(pixels, 0, static_cast<size_t>(width) * height * 4);
    const std::lock_guard<std::mutex> guard(modelLock());
    modelScene().render(modelView(values.get()), width, height, pixels);
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_clearLayerPixels(JNIEnv*, jobject,
                                                                              jlong handle, jint id) {
    renderer(handle)->clearLayerPixels(id);
}

// Live, the layer is cleared and redrawn without an undo step, as the model turns.
JNIEXPORT jboolean JNICALL Java_art_cappuccino_paint_NativeEngine_modelPlace(
    JNIEnv* env, jobject, jlong handle, jint layerId, jfloatArray view, jint width, jint height,
    jboolean live) {
    const FloatArray values(env, view);
    if (live) renderer(handle)->clearLayerPixels(layerId);
    const cappuccino::ModelView placed = modelView(values.get());
    const std::lock_guard<std::mutex> guard(modelLock());
    int box[4];
    modelScene().bounds(placed, width, height, box);
    const cappuccino::Rect rect{box[0], box[1], box[2], box[3]};
    if (rect.empty()) return JNI_FALSE;
    // Drawn into the model's own rectangle, then moved onto the layer there.
    std::vector<uint8_t> pixels(static_cast<size_t>(rect.width()) * rect.height() * 4, 0);
    cappuccino::ModelView local = placed;
    local.centerX -= rect.left;
    local.centerY -= rect.top;
    modelScene().render(local, rect.width(), rect.height(), pixels.data());
    renderer(handle)->placePixels(layerId, rect, pixels.data(), !live);
    return JNI_TRUE;
}

}  // extern "C"
