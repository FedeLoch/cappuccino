// The web counterpart of app/src/main/cpp/jni.cpp: a small C API over the shared engine.
//
// JavaScript writes pen samples straight into fixed buffers on the wasm heap, so a stroke
// frame copies nothing and allocates nothing on either side of the boundary.

#include <emscripten/emscripten.h>
#include <emscripten/html5.h>

#include <memory>

#include "renderer.h"

using cappuccino::BrushParams;
using cappuccino::Renderer;
using cappuccino::Sample;

namespace {

constexpr int kMaxSamples = 1024;
constexpr int kSampleFloats = sizeof(Sample) / sizeof(float);
constexpr int kLayer = 1;

EMSCRIPTEN_WEBGL_CONTEXT_HANDLE context = 0;
std::unique_ptr<Renderer> renderer;
Sample samples[kMaxSamples];
Sample predicted[kMaxSamples];
// A 3x3 row-major canvas-to-screen transform, as android.graphics.Matrix stores it.
float view[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
// Screen pixels with y down to clip space, column-major.
float projection[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
int bufferWidth = 1;
int bufferHeight = 1;

}  // namespace

extern "C" {

// The page creates the WebGL 2 context first with the attributes it wants (desynchronized among
// them, which Emscripten can't ask for), so this call picks up that same context.
EMSCRIPTEN_KEEPALIVE int cap_init(int canvasWidth, int canvasHeight) {
    EmscriptenWebGLContextAttributes attributes;
    emscripten_webgl_init_context_attributes(&attributes);
    attributes.majorVersion = 2;
    attributes.minorVersion = 0;
    attributes.alpha = false;
    attributes.depth = false;
    attributes.stencil = false;
    attributes.antialias = false;
    attributes.preserveDrawingBuffer = true;
    attributes.powerPreference = EM_WEBGL_POWER_PREFERENCE_HIGH_PERFORMANCE;
    context = emscripten_webgl_create_context("#canvas", &attributes);
    if (context <= 0) return 0;
    emscripten_webgl_make_context_current(context);
    renderer = std::make_unique<Renderer>(canvasWidth, canvasHeight);
    renderer->createLayer(kLayer);
    renderer->setActiveLayer(kLayer);
    return 1;
}

EMSCRIPTEN_KEEPALIVE float* cap_samples() { return &samples[0].x; }
EMSCRIPTEN_KEEPALIVE float* cap_predicted() { return &predicted[0].x; }
EMSCRIPTEN_KEEPALIVE float* cap_view() { return view; }
EMSCRIPTEN_KEEPALIVE int cap_max_samples() { return kMaxSamples; }
EMSCRIPTEN_KEEPALIVE int cap_sample_floats() { return kSampleFloats; }

EMSCRIPTEN_KEEPALIVE void cap_resize(int width, int height) {
    bufferWidth = width;
    bufferHeight = height;
    projection[0] = 2.0f / width;
    projection[5] = -2.0f / height;
    projection[12] = -1.0f;
    projection[13] = 1.0f;
}

EMSCRIPTEN_KEEPALIVE void cap_begin_stroke(float diameter, float red, float green, float blue,
                                           float flow, float minScale, float spacingRatio,
                                           float pressureFlow, float hardness, float grain,
                                           int eraser, float pressureGamma) {
    BrushParams brush;
    brush.diameter = diameter;
    brush.color[0] = red;
    brush.color[1] = green;
    brush.color[2] = blue;
    brush.shape.flow = flow;
    brush.shape.minScale = minScale;
    brush.shape.spacingRatio = spacingRatio;
    brush.shape.pressureFlow = pressureFlow;
    brush.shape.pressureGamma = pressureGamma;
    brush.hardness = hardness;
    brush.grain = grain;
    brush.eraser = eraser != 0;
    renderer->beginStroke(brush);
}

// Adds the first count samples to the stroke and shows the predicted ones on top, drawing only
// the part of the screen that changed.
EMSCRIPTEN_KEEPALIVE void cap_draw(int count, int predictedCount, int end) {
    if (count > kMaxSamples) count = kMaxSamples;
    if (predictedCount > kMaxSamples) predictedCount = kMaxSamples;
    renderer->drawFront(projection, view, bufferWidth, bufferHeight, samples, count, predicted,
                        predictedCount, end != 0);
}

EMSCRIPTEN_KEEPALIVE void cap_cancel_stroke() { renderer->cancelStroke(); }

EMSCRIPTEN_KEEPALIVE void cap_compose() {
    renderer->compose(projection, view, bufferWidth, bufferHeight);
}

EMSCRIPTEN_KEEPALIVE int cap_undo() { return renderer->undo() ? 1 : 0; }
EMSCRIPTEN_KEEPALIVE int cap_redo() { return renderer->redo() ? 1 : 0; }

}  // extern "C"
