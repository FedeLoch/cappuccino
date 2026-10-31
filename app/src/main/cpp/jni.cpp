#include <jni.h>

#include <vector>

#include "renderer.h"

using cappuccino::Renderer;
using cappuccino::Sample;

namespace {

Renderer* renderer(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

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
    JNIEnv*, jobject, jlong handle, jint id, jfloat opacity, jboolean visible, jint blend) {
    renderer(handle)->setLayerProperties(id, opacity, visible,
                                         static_cast<cappuccino::BlendMode>(blend));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_moveLayer(JNIEnv*, jobject,
                                                                        jlong handle, jint id,
                                                                        jint index) {
    renderer(handle)->moveLayer(id, index);
}

JNIEXPORT jboolean JNICALL Java_art_cappuccino_paint_NativeEngine_takeLayerDirty(JNIEnv*, jobject,
                                                                                 jlong handle,
                                                                                 jint id) {
    return renderer(handle)->takeLayerDirty(id);
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

JNIEXPORT jint JNICALL Java_art_cappuccino_paint_NativeEngine_pickColor(JNIEnv*, jobject,
                                                                        jlong handle, jint x,
                                                                        jint y) {
    return static_cast<jint>(renderer(handle)->pickColor(x, y));
}

JNIEXPORT void JNICALL Java_art_cappuccino_paint_NativeEngine_beginStroke(
    JNIEnv*, jobject, jlong handle, jfloat diameter, jfloat red, jfloat green, jfloat blue,
    jfloat flow, jfloat minScale, jfloat spacingRatio, jfloat pressureFlow, jfloat hardness,
    jfloat grain, jboolean eraser) {
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

}  // extern "C"
