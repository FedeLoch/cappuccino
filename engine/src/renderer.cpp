#include "renderer.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace cappuccino {
namespace {

constexpr size_t kHistoryBudgetBytes = 256 * 1024 * 1024;
constexpr float kPaper[3] = {1.0f, 1.0f, 1.0f};
constexpr float kBackground[3] = {0.16f, 0.15f, 0.15f};

constexpr char kStampVertex[] = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aLocal;
layout(location = 2) in float aAlpha;
uniform mat4 uMatrix;
out vec2 vLocal;
out float vAlpha;
void main() {
    vLocal = aLocal;
    vAlpha = aAlpha;
    gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0);
})";

constexpr char kStampFragment[] = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in float vAlpha;
uniform vec3 uColor;
uniform float uHardness;
uniform float uGrain;
out vec4 fragColor;
float noise(vec2 cell) {
    return fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
}
void main() {
    float distance = length(vLocal);
    float edge = max(fwidth(distance), 0.001);
    float coverage = 1.0 - smoothstep(min(uHardness, 1.0 - edge), 1.0, distance);
    float grain = mix(1.0, smoothstep(0.35, 0.75, noise(floor(gl_FragCoord.xy / 2.0))), uGrain);
    float alpha = vAlpha * coverage * grain;
    fragColor = vec4(uColor * alpha, alpha);
})";

constexpr char kCanvasVertex[] = R"(#version 300 es
uniform mat4 uMatrix;
uniform vec2 uSize;
out vec2 vUv;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vUv = corner;
    gl_Position = uMatrix * vec4(corner * uSize, 0.0, 1.0);
})";

constexpr char kCanvasFragment[] = R"(#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTexture;
uniform vec4 uSolid;
uniform float uOpacity;
out vec4 fragColor;
void main() {
    fragColor = uSolid.a > 0.0 ? uSolid : texture(uTexture, vUv) * uOpacity;
})";

constexpr char kFilterVertex[] = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vUv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
})";

constexpr char kFilterFragment[] = R"(#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTexture;
uniform int uMode;
out vec4 fragColor;
void main() {
    vec3 color = texture(uTexture, vUv).rgb;
    float value = dot(color, vec3(0.2126, 0.7152, 0.0722));
    if (uMode == 2) value = step(0.5, value);
    if (uMode == 3) value = floor(min(value, 0.999) * 3.0) / 2.0;
    fragColor = vec4(vec3(value), 1.0);
})";

GLuint compile(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    return shader;
}

GLuint link(const char* vertexSource, const char* fragmentSource) {
    const GLuint program = glCreateProgram();
    const GLuint vertex = compile(GL_VERTEX_SHADER, vertexSource);
    const GLuint fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    return program;
}

void createTarget(int width, int height, GLuint& texture, GLuint& fbo) {
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
}

void multiply(const float* a, const float* b, float* out) {
    for (int column = 0; column < 4; ++column) {
        for (int row = 0; row < 4; ++row) {
            float value = 0.0f;
            for (int k = 0; k < 4; ++k) value += a[k * 4 + row] * b[column * 4 + k];
            out[column * 4 + row] = value;
        }
    }
}

// Expands a row-major 3x3 view matrix into a column-major 4x4 one.
void viewToMatrix(const float* view, float* out) {
    const float matrix[16] = {view[0], view[3], 0, view[6], view[1], view[4], 0, view[7],
                              0,       0,       1, 0,       view[2], view[5], 0, view[8]};
    std::memcpy(out, matrix, sizeof(matrix));
}

Rect unite(const Rect& a, const Rect& b) {
    if (a.empty()) return b;
    if (b.empty()) return a;
    return {std::min(a.left, b.left), std::min(a.top, b.top), std::max(a.right, b.right),
            std::max(a.bottom, b.bottom)};
}

// Finds the buffer pixels covered by a canvas rectangle, grown by one pixel for filtering.
Rect toBufferRect(const Rect& rect, const float* matrix, int bufferWidth, int bufferHeight) {
    float minX = bufferWidth, minY = bufferHeight, maxX = 0.0f, maxY = 0.0f;
    const float xs[2] = {static_cast<float>(rect.left), static_cast<float>(rect.right)};
    const float ys[2] = {static_cast<float>(rect.top), static_cast<float>(rect.bottom)};
    for (const float x : xs) {
        for (const float y : ys) {
            const float px = matrix[0] * x + matrix[4] * y + matrix[12];
            const float py = matrix[1] * x + matrix[5] * y + matrix[13];
            const float bufferX = (px + 1.0f) * 0.5f * bufferWidth;
            const float bufferY = (py + 1.0f) * 0.5f * bufferHeight;
            minX = std::min(minX, bufferX);
            minY = std::min(minY, bufferY);
            maxX = std::max(maxX, bufferX);
            maxY = std::max(maxY, bufferY);
        }
    }
    return {std::max(0, static_cast<int>(std::floor(minX)) - 1),
            std::max(0, static_cast<int>(std::floor(minY)) - 1),
            std::min(bufferWidth, static_cast<int>(std::ceil(maxX)) + 1),
            std::min(bufferHeight, static_cast<int>(std::ceil(maxY)) + 1)};
}

GLint currentFramebuffer() {
    GLint fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
    return fbo;
}

void resetState() {
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
}

}  // namespace

Renderer::Renderer(int canvasWidth, int canvasHeight) : width_(canvasWidth), height_(canvasHeight) {
    const GLint previous = currentFramebuffer();
    resetState();
    createTarget(width_, height_, shadowTexture_, shadowFbo_);

    stampProgram_ = link(kStampVertex, kStampFragment);
    stampMatrix_ = glGetUniformLocation(stampProgram_, "uMatrix");
    stampColor_ = glGetUniformLocation(stampProgram_, "uColor");
    stampHardness_ = glGetUniformLocation(stampProgram_, "uHardness");
    stampGrain_ = glGetUniformLocation(stampProgram_, "uGrain");
    canvasProgram_ = link(kCanvasVertex, kCanvasFragment);
    canvasMatrix_ = glGetUniformLocation(canvasProgram_, "uMatrix");
    canvasSize_ = glGetUniformLocation(canvasProgram_, "uSize");
    canvasSolid_ = glGetUniformLocation(canvasProgram_, "uSolid");
    canvasOpacity_ = glGetUniformLocation(canvasProgram_, "uOpacity");

    filterProgram_ = link(kFilterVertex, kFilterFragment);
    filterMode_ = glGetUniformLocation(filterProgram_, "uMode");

    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float), nullptr);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                          reinterpret_cast<const void*>(2 * sizeof(float)));
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                          reinterpret_cast<const void*>(4 * sizeof(float)));
    glGenVertexArrays(1, &canvasVao_);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, previous);
}

Renderer::~Renderer() {
    glDeleteVertexArrays(1, &vao_);
    glDeleteVertexArrays(1, &canvasVao_);
    glDeleteBuffers(1, &vbo_);
    glDeleteProgram(stampProgram_);
    glDeleteProgram(canvasProgram_);
    glDeleteProgram(filterProgram_);
    if (viewFbo_ != 0) {
        glDeleteFramebuffers(1, &viewFbo_);
        glDeleteTextures(1, &viewTexture_);
    }
    for (Layer& layer : layers_) {
        glDeleteFramebuffers(1, &layer.fbo);
        glDeleteTextures(1, &layer.texture);
    }
    glDeleteFramebuffers(1, &shadowFbo_);
    glDeleteTextures(1, &shadowTexture_);
}

Renderer::Layer* Renderer::findLayer(int id) {
    for (Layer& layer : layers_) {
        if (layer.id == id) return &layer;
    }
    return nullptr;
}

void Renderer::createLayer(int id) {
    const GLint target = currentFramebuffer();
    resetState();
    Layer layer;
    layer.id = id;
    createTarget(width_, height_, layer.texture, layer.fbo);
    layers_.push_back(layer);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::deleteLayer(int id) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    glDeleteFramebuffers(1, &layer->fbo);
    glDeleteTextures(1, &layer->texture);
    layers_.erase(layers_.begin() + (layer - layers_.data()));
    clearLayerHistory(id);
    if (activeLayer_ == id) activeLayer_ = 0;
}

void Renderer::clearLayerHistory(int id) {
    for (auto* history : {&undo_, &redo_}) {
        std::erase_if(*history, [&](const HistoryEntry& entry) {
            if (entry.layer != id) return false;
            historyBytes_ -= entry.pixels.size();
            return true;
        });
    }
}

void Renderer::duplicateLayer(int id, int newId) {
    Layer* source = findLayer(id);
    if (!source) return;
    const Layer settings = *source;
    const int index = static_cast<int>(source - layers_.data());
    const GLint target = currentFramebuffer();
    createLayer(newId);
    Layer* copy = findLayer(newId);
    copy->opacity = settings.opacity;
    copy->visible = settings.visible;
    copy->blend = settings.blend;
    copy->dirty = true;
    copyRegion(settings.fbo, copy->fbo, {0, 0, width_, height_});
    moveLayer(newId, index + 1);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::mergeLayerDown(int id, int belowId) {
    Layer* upper = findLayer(id);
    Layer* lower = findLayer(belowId);
    if (!upper || !lower) return;
    const GLint target = currentFramebuffer();
    resetState();
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, lower->fbo);
    glViewport(0, 0, width_, height_);
    glUseProgram(canvasProgram_);
    glUniform2f(canvasSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform4f(canvasSolid_, 0.0f, 0.0f, 0.0f, 0.0f);
    glUniform1f(canvasOpacity_, upper->opacity);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, upper->texture);
    glBindVertexArray(canvasVao_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    drawQuad(toCanvas);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
    lower->dirty = true;
    clearLayerHistory(belowId);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    deleteLayer(id);
}

void Renderer::readLayerThumbnail(int id, int width, int height, void* pixels) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    const GLint target = currentFramebuffer();
    resetState();
    GLuint texture = 0;
    GLuint fbo = 0;
    createTarget(width, height, texture, fbo);
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glViewport(0, 0, width, height);
    glUseProgram(canvasProgram_);
    glUniform2f(canvasSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform4f(canvasSolid_, 0.0f, 0.0f, 0.0f, 0.0f);
    glUniform1f(canvasOpacity_, 1.0f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, layer->texture);
    glBindVertexArray(canvasVao_);
    drawQuad(toCanvas);
    glBindVertexArray(0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::setViewOptions(int mode, int guide) {
    viewMode_ = mode;
    guide_ = guide;
}

void Renderer::fillCanvasRect(const float* matrix, float x, float y, float width, float height) {
    const float place[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, 0, 1};
    float placed[16];
    multiply(matrix, place, placed);
    glUniform2f(canvasSize_, width, height);
    drawQuad(placed);
}

void Renderer::drawGuides(const float* matrix) {
    if (guide_ == 0) return;
    constexpr float kLine = 4.0f;
    const float w = static_cast<float>(width_);
    const float h = static_cast<float>(height_);
    std::vector<float> columns;
    std::vector<float> rows;
    if (guide_ == 1) {
        columns = {1.0f / 3.0f, 2.0f / 3.0f};
        rows = columns;
    } else if (guide_ == 2) {
        columns = {0.382f, 0.618f};
        rows = columns;
    } else {
        for (int i = 1; i < 8; ++i) columns.push_back(i / 8.0f);
        rows = columns;
    }
    glUseProgram(canvasProgram_);
    glUniform4f(canvasSolid_, 0.55f, 0.23f, 0.12f, 0.65f);
    glBindVertexArray(canvasVao_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    for (const float column : columns) fillCanvasRect(matrix, column * w, 0.0f, kLine, h);
    for (const float row : rows) fillCanvasRect(matrix, 0.0f, row * h, w, kLine);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
}

void Renderer::drawScene(const float* matrix, int bufferWidth, int bufferHeight,
                         const std::vector<Stamp>* stamps) {
    if (viewMode_ == 0) {
        drawCanvas(matrix);
        if (stamps) drawStamps(*stamps, matrix);
        drawGuides(matrix);
        return;
    }
    const GLint target = currentFramebuffer();
    if (viewWidth_ != bufferWidth || viewHeight_ != bufferHeight) {
        if (viewFbo_ != 0) {
            glDeleteFramebuffers(1, &viewFbo_);
            glDeleteTextures(1, &viewTexture_);
        }
        const GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        glDisable(GL_SCISSOR_TEST);
        createTarget(bufferWidth, bufferHeight, viewTexture_, viewFbo_);
        if (scissor) glEnable(GL_SCISSOR_TEST);
        viewWidth_ = bufferWidth;
        viewHeight_ = bufferHeight;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, viewFbo_);
    glClearColor(kBackground[0], kBackground[1], kBackground[2], 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    drawCanvas(matrix);
    if (stamps) drawStamps(*stamps, matrix);

    glBindFramebuffer(GL_FRAMEBUFFER, target);
    glUseProgram(filterProgram_);
    glUniform1i(filterMode_, viewMode_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, viewTexture_);
    glBindVertexArray(canvasVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    drawGuides(matrix);
}

void Renderer::setActiveLayer(int id) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    const GLint target = currentFramebuffer();
    activeLayer_ = id;
    copyRegion(layer->fbo, shadowFbo_, {0, 0, width_, height_});
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::setLayerProperties(int id, float opacity, bool visible, BlendMode blend) {
    if (Layer* layer = findLayer(id)) {
        layer->opacity = opacity;
        layer->visible = visible;
        layer->blend = blend;
    }
}

void Renderer::moveLayer(int id, int index) {
    Layer* found = findLayer(id);
    if (!found || index < 0 || index >= static_cast<int>(layers_.size())) return;
    const Layer layer = *found;
    layers_.erase(layers_.begin() + (found - layers_.data()));
    layers_.insert(layers_.begin() + index, layer);
}

bool Renderer::takeLayerDirty(int id) {
    Layer* layer = findLayer(id);
    if (!layer) return false;
    const bool dirty = layer->dirty;
    layer->dirty = false;
    return dirty;
}

void Renderer::readLayer(int id, void* pixels) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    const GLint target = currentFramebuffer();
    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::writeLayer(int id, const void* pixels) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, layer->texture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    if (id == activeLayer_) setActiveLayer(id);
}

Rect Renderer::bounds(const std::vector<Stamp>& stamps) const {
    if (stamps.empty()) return {};
    float left = stamps[0].x, top = stamps[0].y, right = left, bottom = top;
    for (const Stamp& stamp : stamps) {
        const float reach = std::max(stamp.radiusX, stamp.radiusY) * 1.2f + 2.0f;
        left = std::min(left, stamp.x - reach);
        top = std::min(top, stamp.y - reach);
        right = std::max(right, stamp.x + reach);
        bottom = std::max(bottom, stamp.y + reach);
    }
    return {std::clamp(static_cast<int>(std::floor(left)), 0, width_),
            std::clamp(static_cast<int>(std::floor(top)), 0, height_),
            std::clamp(static_cast<int>(std::ceil(right)), 0, width_),
            std::clamp(static_cast<int>(std::ceil(bottom)), 0, height_)};
}

void Renderer::drawStamps(const std::vector<Stamp>& stamps, const float* matrix) {
    if (stamps.empty()) return;
    static constexpr float kCorners[6][2] = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
    vertices_.clear();
    vertices_.reserve(stamps.size() * 30);
    for (const Stamp& stamp : stamps) {
        const float cosine = std::cos(stamp.angle);
        const float sine = std::sin(stamp.angle);
        for (const auto& corner : kCorners) {
            // The quad is slightly oversized so the anti-aliased edge is not clipped.
            const float localX = corner[0] * 1.2f;
            const float localY = corner[1] * 1.2f;
            const float offsetX = localX * stamp.radiusX;
            const float offsetY = localY * stamp.radiusY;
            vertices_.push_back(stamp.x + offsetX * cosine - offsetY * sine);
            vertices_.push_back(stamp.y + offsetX * sine + offsetY * cosine);
            vertices_.push_back(localX);
            vertices_.push_back(localY);
            vertices_.push_back(stamp.alpha);
        }
    }
    glUseProgram(stampProgram_);
    glUniformMatrix4fv(stampMatrix_, 1, GL_FALSE, matrix);
    glUniform3fv(stampColor_, 1, brush_.color);
    glUniform1f(stampHardness_, brush_.hardness);
    glUniform1f(stampGrain_, brush_.grain);
    glEnable(GL_BLEND);
    if (brush_.eraser) {
        glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
    } else if (brush_.alphaLock) {
        glBlendFuncSeparate(GL_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
    } else {
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertices_.size() * sizeof(float)),
                 vertices_.data(), GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices_.size() / 5));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

void Renderer::drawQuad(const float* matrix) {
    glUniformMatrix4fv(canvasMatrix_, 1, GL_FALSE, matrix);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void Renderer::drawCanvas(const float* matrix) {
    glUseProgram(canvasProgram_);
    glUniform2f(canvasSize_, static_cast<float>(width_), static_cast<float>(height_));
    glActiveTexture(GL_TEXTURE0);
    glBindVertexArray(canvasVao_);

    glUniform4f(canvasSolid_, kPaper[0], kPaper[1], kPaper[2], 1.0f);
    drawQuad(matrix);

    glUniform4f(canvasSolid_, 0.0f, 0.0f, 0.0f, 0.0f);
    glEnable(GL_BLEND);
    for (const Layer& layer : layers_) {
        if (!layer.visible) continue;
        switch (layer.blend) {
            case BlendMode::kNormal: glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); break;
            case BlendMode::kMultiply: glBlendFunc(GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA); break;
            case BlendMode::kScreen: glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR); break;
            case BlendMode::kAdd: glBlendFunc(GL_ONE, GL_ONE); break;
        }
        glUniform1f(canvasOpacity_, layer.opacity);
        glBindTexture(GL_TEXTURE_2D, layer.texture);
        drawQuad(matrix);
    }
    glDisable(GL_BLEND);
    glBindVertexArray(0);
}

void Renderer::beginStroke(const BrushParams& brush) {
    if (!findLayer(activeLayer_)) return;
    brush_ = brush;
    stroke_ = std::make_unique<StrokeBuilder>(brush.diameter, brush.shape);
    strokeDirty_ = {};
    predictedDirty_ = {};
}

void Renderer::drawFront(const float* projection, const float* view, int bufferWidth,
                         int bufferHeight, const Sample* samples, int sampleCount,
                         const Sample* predicted, int predictedCount, bool end) {
    Layer* layer = findLayer(activeLayer_);
    if (!stroke_ || !layer) return;
    const GLint target = currentFramebuffer();
    resetState();

    stamps_.clear();
    for (int i = 0; i < sampleCount; ++i) stroke_->add(samples[i], stamps_);
    if (end) stroke_->finish(stamps_);

    predictedStamps_.clear();
    if (!end && predictedCount > 0 && !brush_.eraser) {
        StrokeBuilder prediction = *stroke_;
        for (int i = 0; i < predictedCount; ++i) prediction.add(predicted[i], predictedStamps_);
        prediction.finish(predictedStamps_);
    }

    if (!stamps_.empty()) {
        const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                    0,             0, 1, 0, -1, -1,            0, 1};
        glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
        glViewport(0, 0, width_, height_);
        drawStamps(stamps_, toCanvas);
        layer->dirty = true;
    }
    const Rect stampDirty = bounds(stamps_);
    strokeDirty_ = unite(strokeDirty_, stampDirty);
    const Rect predictedBounds = bounds(predictedStamps_);
    const Rect frameDirty = unite(unite(stampDirty, predictedDirty_), predictedBounds);
    predictedDirty_ = predictedBounds;

    glBindFramebuffer(GL_FRAMEBUFFER, target);
    if (!frameDirty.empty()) {
        float viewMatrix[16];
        float matrix[16];
        viewToMatrix(view, viewMatrix);
        multiply(projection, viewMatrix, matrix);
        const Rect scissor = toBufferRect(frameDirty, matrix, bufferWidth, bufferHeight);
        if (!scissor.empty()) {
            glViewport(0, 0, bufferWidth, bufferHeight);
            glEnable(GL_SCISSOR_TEST);
            glScissor(scissor.left, scissor.top, scissor.width(), scissor.height());
            glClearColor(kBackground[0], kBackground[1], kBackground[2], 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            drawScene(matrix, bufferWidth, bufferHeight, &predictedStamps_);
            glDisable(GL_SCISSOR_TEST);
        }
    }
    if (end) commitStroke();
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::commitStroke() {
    Layer* layer = findLayer(activeLayer_);
    if (layer && !strokeDirty_.empty()) {
        HistoryEntry entry;
        entry.layer = layer->id;
        entry.rect = strokeDirty_;
        entry.pixels.resize(static_cast<size_t>(strokeDirty_.width()) * strokeDirty_.height() * 4);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(strokeDirty_.left, strokeDirty_.top, strokeDirty_.width(),
                     strokeDirty_.height(), GL_RGBA, GL_UNSIGNED_BYTE, entry.pixels.data());
        copyRegion(layer->fbo, shadowFbo_, strokeDirty_);

        historyBytes_ += entry.pixels.size();
        undo_.push_back(std::move(entry));
        for (const HistoryEntry& stale : redo_) historyBytes_ -= stale.pixels.size();
        redo_.clear();
        while (historyBytes_ > kHistoryBudgetBytes && undo_.size() > 1) {
            historyBytes_ -= undo_.front().pixels.size();
            undo_.erase(undo_.begin());
        }
    }
    stroke_.reset();
}

void Renderer::cancelStroke() {
    if (!stroke_) return;
    const GLint target = currentFramebuffer();
    Layer* layer = findLayer(activeLayer_);
    if (layer && !strokeDirty_.empty()) copyRegion(shadowFbo_, layer->fbo, strokeDirty_);
    stroke_.reset();
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::copyRegion(GLuint fromFbo, GLuint toFbo, const Rect& rect) {
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fromFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, toFbo);
    glBlitFramebuffer(rect.left, rect.top, rect.right, rect.bottom, rect.left, rect.top, rect.right,
                      rect.bottom, GL_COLOR_BUFFER_BIT, GL_NEAREST);
}

bool Renderer::swapHistory(std::vector<HistoryEntry>& from, std::vector<HistoryEntry>& to) {
    if (from.empty()) return false;
    const GLint target = currentFramebuffer();
    HistoryEntry entry = std::move(from.back());
    from.pop_back();

    Layer* layer = findLayer(entry.layer);
    std::vector<uint8_t> current(entry.pixels.size());
    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(entry.rect.left, entry.rect.top, entry.rect.width(), entry.rect.height(), GL_RGBA,
                 GL_UNSIGNED_BYTE, current.data());

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, layer->texture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, entry.rect.left, entry.rect.top, entry.rect.width(),
                    entry.rect.height(), GL_RGBA, GL_UNSIGNED_BYTE, entry.pixels.data());
    if (layer->id == activeLayer_) {
        glBindTexture(GL_TEXTURE_2D, shadowTexture_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, entry.rect.left, entry.rect.top, entry.rect.width(),
                        entry.rect.height(), GL_RGBA, GL_UNSIGNED_BYTE, entry.pixels.data());
    }
    layer->dirty = true;

    entry.pixels = std::move(current);
    to.push_back(std::move(entry));
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    return true;
}

bool Renderer::undo() { return swapHistory(undo_, redo_); }

bool Renderer::redo() { return swapHistory(redo_, undo_); }

void Renderer::compose(const float* projection, const float* view, int bufferWidth,
                       int bufferHeight) {
    resetState();
    glViewport(0, 0, bufferWidth, bufferHeight);
    glClearColor(kBackground[0], kBackground[1], kBackground[2], 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    float viewMatrix[16];
    float matrix[16];
    viewToMatrix(view, viewMatrix);
    multiply(projection, viewMatrix, matrix);
    drawScene(matrix, bufferWidth, bufferHeight, nullptr);
}

void Renderer::readComposite(int width, int height, void* pixels) {
    const GLint target = currentFramebuffer();
    resetState();
    GLuint texture = 0;
    GLuint fbo = 0;
    createTarget(width, height, texture, fbo);
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glViewport(0, 0, width, height);
    drawCanvas(toCanvas);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

uint32_t Renderer::pickColor(int x, int y) {
    const GLint target = currentFramebuffer();
    resetState();
    GLuint texture = 0;
    GLuint fbo = 0;
    createTarget(1, 1, texture, fbo);
    const float toPixel[16] = {2.0f, 0, 0, 0, 0, 2.0f, 0, 0, 0, 0, 1, 0,
                               -2.0f * x - 1.0f, -2.0f * y - 1.0f, 0, 1};
    glViewport(0, 0, 1, 1);
    drawCanvas(toPixel);
    uint8_t pixel[4] = {};
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    return (pixel[0] << 16) | (pixel[1] << 8) | pixel[2];
}

}  // namespace cappuccino
