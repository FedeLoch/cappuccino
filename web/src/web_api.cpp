// The web counterpart of app/src/main/cpp/jni.cpp: a small C API over the shared engine.
//
// JavaScript writes pen samples, points, and brush tips straight into fixed buffers on the wasm
// heap, so a stroke frame copies nothing and allocates nothing on either side of the boundary.

#include <emscripten/emscripten.h>
#include <emscripten/html5.h>

#include <cstdint>
#include <memory>

#include "renderer.h"

using cappuccino::BrushParams;
using cappuccino::Renderer;
using cappuccino::Sample;

namespace {

constexpr int kMaxSamples = 1024;
constexpr int kMaxPoints = 8192;
constexpr int kSampleFloats = sizeof(Sample) / sizeof(float);
constexpr int kLayer = 1;

EMSCRIPTEN_WEBGL_CONTEXT_HANDLE context = 0;
std::unique_ptr<Renderer> renderer;
Sample samples[kMaxSamples];
Sample predicted[kMaxSamples];
float points[kMaxPoints * 2];
uint8_t tip[cappuccino::kTipSize * cappuccino::kTipSize];
// A 3x3 row-major canvas-to-screen transform, as android.graphics.Matrix stores it.
float view[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
// Screen pixels with y down to clip space, column-major.
float projection[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
int bufferWidth = 1;
int bufferHeight = 1;
int canvasWidth = 1;
int canvasHeight = 1;

}  // namespace

extern "C" {

// The page creates the WebGL 2 context first with the attributes it wants (desynchronized among
// them, which Emscripten can't ask for), so this call picks up that same context.
EMSCRIPTEN_KEEPALIVE int cap_init(int width, int height) {
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
    canvasWidth = width;
    canvasHeight = height;
    renderer = std::make_unique<Renderer>(width, height);
    renderer->createLayer(kLayer);
    renderer->setActiveLayer(kLayer);
    return 1;
}

EMSCRIPTEN_KEEPALIVE float* cap_samples() { return &samples[0].x; }
EMSCRIPTEN_KEEPALIVE float* cap_predicted() { return &predicted[0].x; }
EMSCRIPTEN_KEEPALIVE float* cap_points() { return points; }
EMSCRIPTEN_KEEPALIVE uint8_t* cap_tip() { return tip; }
EMSCRIPTEN_KEEPALIVE float* cap_view() { return view; }
EMSCRIPTEN_KEEPALIVE int cap_max_samples() { return kMaxSamples; }
EMSCRIPTEN_KEEPALIVE int cap_max_points() { return kMaxPoints; }
EMSCRIPTEN_KEEPALIVE int cap_sample_floats() { return kSampleFloats; }

EMSCRIPTEN_KEEPALIVE void cap_resize(int width, int height) {
    bufferWidth = width;
    bufferHeight = height;
    projection[0] = 2.0f / width;
    projection[5] = -2.0f / height;
    projection[12] = -1.0f;
    projection[13] = 1.0f;
}

// Takes the same brush settings as NativeEngine.beginStroke on Android. The tip, when used, is
// read from cap_tip().
EMSCRIPTEN_KEEPALIVE void cap_begin_stroke(float diameter, float red, float green, float blue,
                                           float flow, float minScale, float spacingRatio,
                                           float pressureFlow, float hardness, float grain,
                                           int eraser, int smudge, float pressureGamma,
                                           float scatter, float sizeJitter, float angleJitter,
                                           float shadeJitter, float hueJitter, int useTip) {
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
    brush.shape.scatter = scatter;
    brush.shape.sizeJitter = sizeJitter;
    brush.shape.angleJitter = angleJitter;
    brush.shape.shadeJitter = shadeJitter;
    brush.shape.hueJitter = hueJitter;
    brush.hardness = hardness;
    brush.grain = grain;
    brush.eraser = eraser != 0;
    brush.smudge = smudge != 0;
    if (useTip) brush.tip.assign(tip, tip + sizeof(tip));
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

EMSCRIPTEN_KEEPALIVE int cap_fill_at(int x, int y, float red, float green, float blue) {
    const float color[3] = {red, green, blue};
    return renderer->fillAt(x, y, color) ? 1 : 0;
}

// Fills the closed shape in cap_points(), count points long.
EMSCRIPTEN_KEEPALIVE int cap_fill_polygon(int count, float red, float green, float blue,
                                          float opacity) {
    if (count > kMaxPoints) count = kMaxPoints;
    const float color[3] = {red, green, blue};
    return renderer->fillPolygon(points, count, color, opacity, false, false) ? 1 : 0;
}

// Previews a gradient from (x1, y1) to (x2, y2), or ends the preview.
EMSCRIPTEN_KEEPALIVE void cap_set_gradient(int active, float x1, float y1, float x2, float y2,
                                           float red, float green, float blue) {
    const float line[4] = {x1, y1, x2, y2};
    const float color[3] = {red, green, blue};
    renderer->setGradientPreview(active != 0, line, color);
}

EMSCRIPTEN_KEEPALIVE void cap_commit_gradient() { renderer->commitGradient(); }

EMSCRIPTEN_KEEPALIVE void cap_set_view_options(int mode, int guide) {
    renderer->setViewOptions(mode, guide);
}

EMSCRIPTEN_KEEPALIVE void cap_set_symmetry(int mode) {
    renderer->setSymmetry(mode, canvasWidth / 2.0f, canvasHeight / 2.0f);
}

}  // extern "C"
