#include "renderer.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "fill.h"

namespace cappuccino {
namespace {

constexpr size_t kHistoryBudgetBytes = 512 * 1024 * 1024;
constexpr int kGpuHistoryPixels = 1024 * 1024;
constexpr int kCarrySize = 128;
constexpr float kTint[4] = {0.17f, 0.07f, 0.04f, 0.3f};
constexpr float kPaper[3] = {1.0f, 1.0f, 1.0f};
constexpr float kBackground[3] = {0.16f, 0.15f, 0.15f};
constexpr int kStampFloats = 6;  // x, y, local x, local y, alpha, and shade per vertex.
constexpr int kLiquifyCells = 256;  // Grid cells along the canvas's longer side.
constexpr int kIsometricGuide = 4;
constexpr int kViewsGuide = 5;
constexpr int kFramesGuide = 6;
constexpr int kHideUnit = 4;
constexpr int kClipHideUnit = 5;

constexpr char kStampVertex[] = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aLocal;
layout(location = 2) in float aAlpha;
layout(location = 3) in float aShade;
uniform mat4 uMatrix;
out vec2 vLocal;
out float vAlpha;
out float vShade;
void main() {
    vLocal = aLocal;
    vAlpha = aAlpha;
    vShade = aShade;
    gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0);
})";

constexpr char kStampFragment[] = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in float vAlpha;
in float vShade;
uniform vec3 uColor;
uniform float uHardness;
uniform float uGrain;
uniform sampler2D uMask;
uniform float uUseMask;
uniform sampler2D uTip;
uniform float uUseTip;
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
    if (uUseTip > 0.5) {
        alpha = vAlpha * grain * texture(uTip, vLocal * 0.42 + 0.5).r * step(distance, 1.2);
    }
    if (uUseMask > 0.5) alpha *= texelFetch(uMask, ivec2(gl_FragCoord.xy), 0).r;
    vec3 color = vShade < 1.0 ? uColor * vShade : mix(uColor, vec3(1.0), vShade - 1.0);
    fragColor = vec4(color * alpha, alpha);
})";

constexpr char kSmudgeFragment[] = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in float vAlpha;
uniform sampler2D uCarry;
uniform float uErase;
uniform sampler2D uMask;
uniform float uUseMask;
out vec4 fragColor;
void main() {
    float coverage = 1.0 - smoothstep(0.35, 1.0, length(vLocal));
    float amount = vAlpha * coverage;
    if (uUseMask > 0.5) amount *= texelFetch(uMask, ivec2(gl_FragCoord.xy), 0).r;
    if (uErase > 0.5) {
        fragColor = vec4(0.0, 0.0, 0.0, amount);
    } else {
        fragColor = texture(uCarry, vLocal * 0.5 + 0.5) * amount;
    }
})";

constexpr char kGradientVertex[] = R"(#version 300 es
uniform mat4 uMatrix;
uniform vec2 uSize;
out vec2 vPosition;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vPosition = corner * uSize;
    gl_Position = uMatrix * vec4(vPosition, 0.0, 1.0);
})";

constexpr char kGradientFragment[] = R"(#version 300 es
precision highp float;
in vec2 vPosition;
uniform vec4 uPoints;
uniform vec3 uColor;
uniform sampler2D uMask;
uniform float uUseMask;
out vec4 fragColor;
void main() {
    vec2 axis = uPoints.zw - uPoints.xy;
    float t = clamp(dot(vPosition - uPoints.xy, axis) / max(dot(axis, axis), 1.0), 0.0, 1.0);
    float alpha = 1.0 - t;
    if (uUseMask > 0.5) alpha *= texelFetch(uMask, ivec2(vPosition), 0).r;
    fragColor = vec4(uColor * alpha, alpha);
})";

constexpr char kAdjustFragment[] = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTexture;
uniform sampler2D uMask;
uniform float uUseMask;
uniform sampler2D uHide;
uniform float uUseHide;
uniform vec3 uValues;
uniform vec3 uCurve;
out vec4 fragColor;
vec3 toHsv(vec3 c) {
    vec4 k = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, k.wz), vec4(c.gb, k.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 0.0001)), d / (q.x + 0.0001), q.x);
}
vec3 toRgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
float curve(float v) {
    v += uCurve.x * (1.0 - v) * (1.0 - v);
    v += uCurve.y * 4.0 * v * (1.0 - v) * 0.5;
    v += uCurve.z * v * v;
    return clamp(v, 0.0, 1.0);
}
void main() {
    vec4 source = texture(uTexture, vUv);
    if (source.a <= 0.0) {
        fragColor = source;
        return;
    }
    vec3 hsv = toHsv(source.rgb / source.a);
    hsv.x = fract(hsv.x + uValues.x);
    hsv.y = clamp(hsv.y * uValues.y, 0.0, 1.0);
    hsv.z = clamp(hsv.z + uValues.z, 0.0, 1.0);
    vec3 rgb = toRgb(hsv);
    rgb = vec3(curve(rgb.r), curve(rgb.g), curve(rgb.b));
    vec4 result = vec4(rgb * source.a, source.a);
    float amount = 1.0;
    if (uUseMask > 0.5) amount = texelFetch(uMask, ivec2(gl_FragCoord.xy), 0).r;
    fragColor = mix(source, result, amount) * (1.0 - uUseHide * texture(uHide, vUv).a);
})";

// Kinds: 1 blur, 2 motion blur, 3 sharpen, 4 noise, 5 gradient map, 6 levels, 7 depth fade.
constexpr char kEffectFragment[] = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTexture;
uniform sampler2D uMask;
uniform float uUseMask;
uniform sampler2D uHide;
uniform float uUseHide;
uniform vec2 uSize;
uniform int uKind;
uniform vec4 uValues;
uniform vec3 uColor;
out vec4 fragColor;
const int kSamples = 48;
vec4 blur(float radius) {
    vec2 texel = radius / uSize;
    vec4 total = texture(uTexture, vUv);
    float weight = 1.0;
    for (int i = 1; i < kSamples; ++i) {
        float share = sqrt(float(i) / float(kSamples));
        float turn = float(i) * 2.39996;
        float amount = exp(-3.0 * share * share);
        total += texture(uTexture, vUv + vec2(cos(turn), sin(turn)) * share * texel) * amount;
        weight += amount;
    }
    return total / weight;
}
vec4 motion(float reach, float angle) {
    vec2 stride = vec2(cos(angle), sin(angle)) * reach / uSize / float(kSamples);
    vec4 total = vec4(0.0);
    for (int i = 0; i < kSamples; ++i) {
        total += texture(uTexture, vUv + stride * (float(i) - float(kSamples) / 2.0));
    }
    return total / float(kSamples);
}
float noise(vec2 cell) {
    return fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
}
vec4 effect(vec4 source) {
    if (uKind == 1) return blur(uValues.x);
    if (uKind == 2) return motion(uValues.x, uValues.y);
    if (uKind == 3) {
        vec4 soft = blur(3.0);
        vec4 sharp = source + (source - soft) * uValues.x;
        return vec4(clamp(sharp.rgb, vec3(0.0), vec3(sharp.a)), sharp.a);
    }
    if (source.a <= 0.0) return source;
    vec3 color = source.rgb / source.a;
    float value = dot(color, vec3(0.2126, 0.7152, 0.0722));
    if (uKind == 4) {
        color += (noise(gl_FragCoord.xy) - 0.5) * uValues.x;
    } else if (uKind == 5) {
        vec3 mapped = value < 0.5 ? mix(vec3(0.0), uColor, value * 2.0)
                                  : mix(uColor, vec3(1.0), value * 2.0 - 1.0);
        color = mix(color, mapped, uValues.x);
    } else if (uKind == 6) {
        vec3 level = clamp((color - uValues.x) / max(uValues.z - uValues.x, 0.01), 0.0, 1.0);
        color = pow(level, vec3(1.0 / uValues.y));
    } else if (uKind == 7) {
        color = mix(color, uColor, uValues.x);
    }
    return vec4(clamp(color, 0.0, 1.0) * source.a, source.a);
}
void main() {
    vec4 source = texture(uTexture, vUv);
    float amount = 1.0;
    if (uUseMask > 0.5) amount = texelFetch(uMask, ivec2(gl_FragCoord.xy), 0).r;
    fragColor = mix(source, effect(source), amount) * (1.0 - uUseHide * texture(uHide, vUv).a);
})";

constexpr char kCanvasVertex[] = R"(#version 300 es
uniform mat4 uMatrix;
uniform vec2 uSize;
out vec2 vUv;
out vec2 vPlace;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vUv = corner;
    vPlace = corner;
    gl_Position = uMatrix * vec4(corner * uSize, 0.0, 1.0);
})";

constexpr char kFillFragment[] = R"(#version 300 es
precision highp float;
uniform sampler2D uCoverage;
uniform vec3 uColor;
out vec4 fragColor;
void main() {
    if (texelFetch(uCoverage, ivec2(gl_FragCoord.xy), 0).r < 0.5) discard;
    fragColor = vec4(uColor, 1.0);
})";

constexpr char kMeshVertex[] = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aSource;
uniform mat4 uMatrix;
uniform vec2 uSize;
out vec2 vUv;
out vec2 vPlace;
void main() {
    vUv = aSource;
    vPlace = aPosition / uSize;
    gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0);
})";

constexpr char kCanvasFragment[] = R"(#version 300 es
precision mediump float;
in vec2 vUv;
in vec2 vPlace;
uniform sampler2D uTexture;
uniform vec4 uSolid;
uniform sampler2D uClip;
uniform float uUseClip;
uniform sampler2D uHide;
uniform float uUseHide;
uniform sampler2D uClipHide;
uniform float uUseClipHide;
uniform float uOpacity;
uniform float uMatte;
uniform sampler2D uTintMask;
uniform vec4 uTint;
out vec4 fragColor;
void main() {
    if (uTint.a > 0.0) {
        fragColor = uTint * (1.0 - texture(uTintMask, vUv).r);
        return;
    }
    float clip = mix(1.0, texture(uClip, vPlace).a, uUseClip);
    clip *= 1.0 - uUseClipHide * texture(uClipHide, vPlace).a;
    float shown = 1.0 - uUseHide * texture(uHide, vUv).a;
    fragColor = uSolid.a > 0.0 ? uSolid : texture(uTexture, vUv) * uOpacity * clip * shown;
    // Some blends need clear pixels to read as a color that leaves the canvas unchanged.
    fragColor.rgb += (1.0 - fragColor.a) * uMatte;
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

void blitToHistory(GLuint layerFbo, GLuint historyFbo, const Rect& rect) {
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, layerFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, historyFbo);
    glBlitFramebuffer(rect.left, rect.top, rect.right, rect.bottom, 0, 0, rect.width(),
                      rect.height(), GL_COLOR_BUFFER_BIT, GL_NEAREST);
}

void blitFromHistory(GLuint historyFbo, GLuint layerFbo, const Rect& rect) {
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, historyFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, layerFbo);
    glBlitFramebuffer(0, 0, rect.width(), rect.height(), rect.left, rect.top, rect.right,
                      rect.bottom, GL_COLOR_BUFFER_BIT, GL_NEAREST);
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
    canvasMatte_ = glGetUniformLocation(canvasProgram_, "uMatte");
    canvasClip_ = glGetUniformLocation(canvasProgram_, "uClip");
    canvasUseClip_ = glGetUniformLocation(canvasProgram_, "uUseClip");
    canvasUseHide_ = glGetUniformLocation(canvasProgram_, "uUseHide");
    canvasUseClipHide_ = glGetUniformLocation(canvasProgram_, "uUseClipHide");
    glUseProgram(canvasProgram_);
    glUniform1i(canvasClip_, 1);
    glUniform1f(canvasUseClip_, 0.0f);
    glUniform1i(glGetUniformLocation(canvasProgram_, "uHide"), kHideUnit);
    glUniform1i(glGetUniformLocation(canvasProgram_, "uClipHide"), kClipHideUnit);
    glUniform1f(canvasUseHide_, 0.0f);
    glUniform1f(canvasUseClipHide_, 0.0f);
    glUniform1i(glGetUniformLocation(canvasProgram_, "uTintMask"), 2);
    canvasTint_ = glGetUniformLocation(canvasProgram_, "uTint");
    glUniform4f(canvasTint_, 0.0f, 0.0f, 0.0f, 0.0f);

    stampUseMask_ = glGetUniformLocation(stampProgram_, "uUseMask");
    stampUseTip_ = glGetUniformLocation(stampProgram_, "uUseTip");
    glUseProgram(stampProgram_);
    glUniform1i(glGetUniformLocation(stampProgram_, "uMask"), 2);
    glUniform1i(glGetUniformLocation(stampProgram_, "uTip"), 3);

    smudgeProgram_ = link(kStampVertex, kSmudgeFragment);
    smudgeMatrix_ = glGetUniformLocation(smudgeProgram_, "uMatrix");
    glUseProgram(smudgeProgram_);
    glUniform1i(glGetUniformLocation(smudgeProgram_, "uCarry"), 0);
    glUniform1i(glGetUniformLocation(smudgeProgram_, "uMask"), 2);

    gradientProgram_ = link(kGradientVertex, kGradientFragment);
    gradientMatrix_ = glGetUniformLocation(gradientProgram_, "uMatrix");
    gradientSize_ = glGetUniformLocation(gradientProgram_, "uSize");
    gradientPoints_ = glGetUniformLocation(gradientProgram_, "uPoints");
    gradientColor_ = glGetUniformLocation(gradientProgram_, "uColor");
    gradientUseMask_ = glGetUniformLocation(gradientProgram_, "uUseMask");
    glUseProgram(gradientProgram_);
    glUniform1i(glGetUniformLocation(gradientProgram_, "uMask"), 2);

    adjustProgram_ = link(kCanvasVertex, kAdjustFragment);
    adjustMatrix_ = glGetUniformLocation(adjustProgram_, "uMatrix");
    adjustSize_ = glGetUniformLocation(adjustProgram_, "uSize");
    adjustValues_ = glGetUniformLocation(adjustProgram_, "uValues");
    adjustCurve_ = glGetUniformLocation(adjustProgram_, "uCurve");
    adjustUseMask_ = glGetUniformLocation(adjustProgram_, "uUseMask");
    adjustUseHide_ = glGetUniformLocation(adjustProgram_, "uUseHide");
    glUseProgram(adjustProgram_);
    glUniform1i(glGetUniformLocation(adjustProgram_, "uMask"), 2);
    glUniform1i(glGetUniformLocation(adjustProgram_, "uHide"), kHideUnit);
    glUniform1f(adjustUseHide_, 0.0f);

    effectProgram_ = link(kCanvasVertex, kEffectFragment);
    fillProgram_ = link(kCanvasVertex, kFillFragment);
    fillMatrix_ = glGetUniformLocation(fillProgram_, "uMatrix");
    fillSize_ = glGetUniformLocation(fillProgram_, "uSize");
    fillColor_ = glGetUniformLocation(fillProgram_, "uColor");
    effectMatrix_ = glGetUniformLocation(effectProgram_, "uMatrix");
    effectSize_ = glGetUniformLocation(effectProgram_, "uSize");
    effectKind_ = glGetUniformLocation(effectProgram_, "uKind");
    effectValues_ = glGetUniformLocation(effectProgram_, "uValues");
    effectColor_ = glGetUniformLocation(effectProgram_, "uColor");
    effectUseMask_ = glGetUniformLocation(effectProgram_, "uUseMask");
    effectUseHide_ = glGetUniformLocation(effectProgram_, "uUseHide");
    glUseProgram(effectProgram_);
    glUniform1i(glGetUniformLocation(effectProgram_, "uMask"), 2);
    glUniform1i(glGetUniformLocation(effectProgram_, "uHide"), kHideUnit);

    liquifyProgram_ = link(kMeshVertex, kCanvasFragment);
    liquifyMatrix_ = glGetUniformLocation(liquifyProgram_, "uMatrix");
    liquifyOpacity_ = glGetUniformLocation(liquifyProgram_, "uOpacity");
    liquifyMatte_ = glGetUniformLocation(liquifyProgram_, "uMatte");
    liquifyUseClip_ = glGetUniformLocation(liquifyProgram_, "uUseClip");
    liquifyUseHide_ = glGetUniformLocation(liquifyProgram_, "uUseHide");
    liquifyUseClipHide_ = glGetUniformLocation(liquifyProgram_, "uUseClipHide");
    glUseProgram(liquifyProgram_);
    glUniform2f(glGetUniformLocation(liquifyProgram_, "uSize"), static_cast<float>(width_),
                static_cast<float>(height_));
    glUniform4f(glGetUniformLocation(liquifyProgram_, "uSolid"), 0.0f, 0.0f, 0.0f, 0.0f);
    glUniform4f(glGetUniformLocation(liquifyProgram_, "uTint"), 0.0f, 0.0f, 0.0f, 0.0f);
    glUniform1i(glGetUniformLocation(liquifyProgram_, "uClip"), 1);
    glUniform1i(glGetUniformLocation(liquifyProgram_, "uHide"), kHideUnit);
    glUniform1i(glGetUniformLocation(liquifyProgram_, "uClipHide"), kClipHideUnit);
    glUniform1i(glGetUniformLocation(liquifyProgram_, "uTintMask"), 2);

    glGenTextures(1, &stampMaskTexture_);
    glBindTexture(GL_TEXTURE_2D, stampMaskTexture_);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, width_, height_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glGenTextures(1, &tipTexture_);
    glBindTexture(GL_TEXTURE_2D, tipTexture_);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, kTipSize, kTipSize);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    createTarget(kCarrySize, kCarrySize, carryTexture_, carryFbo_);

    filterProgram_ = link(kFilterVertex, kFilterFragment);
    filterMode_ = glGetUniformLocation(filterProgram_, "uMode");

    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glEnableVertexAttribArray(2);
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, kStampFloats * sizeof(float), nullptr);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, kStampFloats * sizeof(float),
                          reinterpret_cast<const void*>(2 * sizeof(float)));
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, kStampFloats * sizeof(float),
                          reinterpret_cast<const void*>(4 * sizeof(float)));
    glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, kStampFloats * sizeof(float),
                          reinterpret_cast<const void*>(5 * sizeof(float)));
    glGenVertexArrays(1, &canvasVao_);
    glGenVertexArrays(1, &liquifyVao_);
    glGenBuffers(1, &liquifyVertices_);
    glGenBuffers(1, &liquifyIndices_);
    glBindVertexArray(liquifyVao_);
    glBindBuffer(GL_ARRAY_BUFFER, liquifyVertices_);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, liquifyIndices_);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          reinterpret_cast<const void*>(2 * sizeof(float)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, previous);
}

Renderer::~Renderer() {
    glDeleteVertexArrays(1, &vao_);
    glDeleteVertexArrays(1, &canvasVao_);
    glDeleteVertexArrays(1, &liquifyVao_);
    glDeleteBuffers(1, &vbo_);
    glDeleteBuffers(1, &liquifyVertices_);
    glDeleteBuffers(1, &liquifyIndices_);
    glDeleteProgram(liquifyProgram_);
    glDeleteProgram(effectProgram_);
    glDeleteProgram(stampProgram_);
    glDeleteProgram(canvasProgram_);
    glDeleteProgram(filterProgram_);
    glDeleteProgram(smudgeProgram_);
    glDeleteProgram(gradientProgram_);
    glDeleteProgram(adjustProgram_);
    glDeleteTextures(1, &stampMaskTexture_);
    glDeleteTextures(1, &tipTexture_);
    glDeleteFramebuffers(1, &carryFbo_);
    glDeleteTextures(1, &carryTexture_);
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
    for (auto* history : {&undo_, &redo_}) {
        for (HistoryEntry& entry : *history) dropHistory(entry);
    }
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

void Renderer::createMask(int layerId, int maskId) {
    if (!findLayer(layerId)) return;
    const GLint target = currentFramebuffer();
    createLayer(maskId);
    setLayerMask(layerId, maskId, true);
    if (selectionActive_) {
        std::vector<uint8_t> pixels(static_cast<size_t>(width_) * height_ * 4, 0);
        for (size_t index = 0; index < selection_.size(); ++index) {
            pixels[index * 4 + 3] = 255 - selection_[index];
        }
        writeLayer(maskId, pixels.data());
        findLayer(maskId)->dirtyArea = {0, 0, width_, height_};
    }
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::setLayerMask(int id, int maskId, bool enabled) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    layer->maskId = maskId;
    layer->maskEnabled = enabled;
    if (Layer* mask = findLayer(maskId)) {
        mask->isMask = true;
        mask->visible = false;
    }
}

void Renderer::useHide(const Layer* layer, int unit, GLint useLocation) {
    Layer* mask = layer && layer->maskEnabled ? findLayer(layer->maskId) : nullptr;
    glUniform1f(useLocation, mask ? 1.0f : 0.0f);
    if (!mask) return;
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, mask->texture);
    glActiveTexture(GL_TEXTURE0);
}

void Renderer::bakeMask(Layer& layer) {
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
    glViewport(0, 0, width_, height_);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(canvasProgram_);
    glUniform2f(canvasSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform4f(canvasSolid_, 0.0f, 0.0f, 0.0f, 0.0f);
    glUniform1f(canvasOpacity_, 1.0f);
    useHide(&layer, kHideUnit, canvasUseHide_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, layer.texture);
    glBindVertexArray(canvasVao_);
    drawQuad(toCanvas);
    glBindVertexArray(0);
    glUniform1f(canvasUseHide_, 0.0f);
    replaceLayerFromShadow(layer);
    layer.maskId = 0;
}

void Renderer::applyMask(int id) {
    Layer* layer = findLayer(id);
    if (!layer || !findLayer(layer->maskId)) return;
    const GLint target = currentFramebuffer();
    resetState();
    pushFullHistory(*layer);
    layer->maskEnabled = true;
    bakeMask(*layer);
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
        std::erase_if(*history, [&](HistoryEntry& entry) {
            if (entry.layer != id) return false;
            historyBytes_ -= entry.bytes();
            dropHistory(entry);
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
    copy->clip = settings.clip;
    copy->isMask = settings.isMask;
    copy->dirtyArea = {0, 0, width_, height_};
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

    pushFullHistory(*lower);
    if (lower->maskEnabled && findLayer(lower->maskId)) bakeMask(*lower);

    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    if (upper->clip) copyRegion(lower->fbo, shadowFbo_, {0, 0, width_, height_});
    glBindFramebuffer(GL_FRAMEBUFFER, lower->fbo);
    glViewport(0, 0, width_, height_);
    glUseProgram(canvasProgram_);
    glUniform2f(canvasSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform4f(canvasSolid_, 0.0f, 0.0f, 0.0f, 0.0f);
    glUniform1f(canvasOpacity_, upper->opacity);
    glUniform1f(canvasUseClip_, upper->clip ? 1.0f : 0.0f);
    useHide(upper, kHideUnit, canvasUseHide_);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, shadowTexture_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, upper->texture);
    glBindVertexArray(canvasVao_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    drawQuad(toCanvas);
    glDisable(GL_BLEND);
    glUniform1f(canvasUseClip_, 0.0f);
    glUniform1f(canvasUseHide_, 0.0f);
    glBindVertexArray(0);
    lower->dirtyArea = {0, 0, width_, height_};
    glBindFramebuffer(GL_FRAMEBUFFER, target);
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

void Renderer::pushHistory(HistoryEntry entry) {
    historyBytes_ += entry.bytes();
    undo_.push_back(std::move(entry));
    for (HistoryEntry& stale : redo_) {
        historyBytes_ -= stale.bytes();
        dropHistory(stale);
    }
    redo_.clear();
    while (historyBytes_ > kHistoryBudgetBytes && undo_.size() > 1) {
        historyBytes_ -= undo_.front().bytes();
        dropHistory(undo_.front());
        undo_.erase(undo_.begin());
    }
}

void Renderer::captureHistory(HistoryEntry& entry, GLuint fromFbo) {
    const Rect& rect = entry.rect;
    if (static_cast<int64_t>(rect.width()) * rect.height() >= kGpuHistoryPixels) {
        createTarget(rect.width(), rect.height(), entry.texture, entry.fbo);
        blitToHistory(fromFbo, entry.fbo, rect);
        return;
    }
    entry.pixels.resize(static_cast<size_t>(rect.width()) * rect.height() * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, fromFbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(rect.left, rect.top, rect.width(), rect.height(), GL_RGBA, GL_UNSIGNED_BYTE,
                 entry.pixels.data());
}

void Renderer::dropHistory(HistoryEntry& entry) {
    if (entry.fbo == 0) return;
    glDeleteFramebuffers(1, &entry.fbo);
    glDeleteTextures(1, &entry.texture);
    entry.fbo = 0;
    entry.texture = 0;
}

bool Renderer::fillAt(int x, int y, const float* color) {
    Layer* layer = findLayer(activeLayer_);
    if (!layer || x < 0 || y < 0 || x >= width_ || y >= height_) return false;
    const GLint target = currentFramebuffer();
    const size_t size = static_cast<size_t>(width_) * height_;
    if (selectionActive_ && selection_[static_cast<size_t>(y) * width_ + x] == 0) return false;

    std::vector<uint8_t> reference(size * 4);
    readComposite(width_, height_, reference.data());
    std::vector<uint8_t> mask;
    FillBounds box;
    if (!floodMask(reference.data(), width_, height_, x, y, 40, mask, &box)) return false;
    reference = {};

    box.left = std::max(0, box.left - 1);
    box.top = std::max(0, box.top - 1);
    box.right = std::min(width_, box.right + 1);
    box.bottom = std::min(height_, box.bottom + 1);

    // Grows one pixel so the fill reaches under a line's soft edge.
    for (int row = box.top; row < box.bottom; ++row) {
        for (int column = box.left; column < box.right; ++column) {
            const size_t index = static_cast<size_t>(row) * width_ + column;
            if (mask[index] != 0) continue;
            if ((column > 0 && mask[index - 1] == 1) || (column + 1 < width_ && mask[index + 1] == 1) ||
                (row > 0 && mask[index - width_] == 1) || (row + 1 < height_ && mask[index + width_] == 1)) {
                mask[index] = 2;
            }
        }
    }
    for (size_t index = 0; index < size; ++index) {
        mask[index] = mask[index] != 0 && (!selectionActive_ || selection_[index] != 0) ? 255 : 0;
    }

    HistoryEntry entry;
    entry.layer = layer->id;
    entry.rect = {box.left, box.top, box.right, box.bottom};
    captureHistory(entry, layer->fbo);
    const Rect filled = entry.rect;
    pushHistory(std::move(entry));

    GLuint coverage = 0;
    glGenTextures(1, &coverage);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, coverage);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width_, height_, 0, GL_RED, GL_UNSIGNED_BYTE, mask.data());

    resetState();
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
    glViewport(0, 0, width_, height_);
    glEnable(GL_SCISSOR_TEST);
    glScissor(filled.left, filled.top, filled.width(), filled.height());
    glUseProgram(fillProgram_);
    glUniformMatrix4fv(fillMatrix_, 1, GL_FALSE, toCanvas);
    glUniform2f(fillSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform3fv(fillColor_, 1, color);
    glBindVertexArray(canvasVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glDisable(GL_SCISSOR_TEST);
    glDeleteTextures(1, &coverage);

    copyRegion(layer->fbo, shadowFbo_, filled);
    layer->dirtyArea = unite(layer->dirtyArea, filled);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    return true;
}

void Renderer::uploadSelection() {
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, stampMaskTexture_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, GL_RED, GL_UNSIGNED_BYTE,
                    selection_.data());
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, stampMaskTexture_);
    glActiveTexture(GL_TEXTURE0);
    selectionActive_ = true;
}

void Renderer::setSelectionPolygon(const float* points, int count) {
    if (count < 3) {
        clearSelection();
        return;
    }
    rasterizePolygon(points, count, width_, height_, selection_);
    uploadSelection();
}

void Renderer::selectColorAt(int x, int y, int tolerance) {
    std::vector<uint8_t> reference(static_cast<size_t>(width_) * height_ * 4);
    readComposite(width_, height_, reference.data());
    if (!floodMask(reference.data(), width_, height_, x, y, tolerance, selection_, nullptr)) return;
    for (uint8_t& value : selection_) value = value != 0 ? 255 : 0;
    uploadSelection();
}

void Renderer::clearSelection() {
    selectionActive_ = false;
    selection_.clear();
}

void Renderer::invertSelection() {
    if (!selectionActive_) return;
    for (uint8_t& value : selection_) value = 255 - value;
    uploadSelection();
}

void Renderer::pushFullHistory(const Layer& layer, bool chained) {
    HistoryEntry entry;
    entry.layer = layer.id;
    entry.chained = chained;
    entry.rect = {0, 0, width_, height_};
    captureHistory(entry, layer.fbo);
    pushHistory(std::move(entry));
}

void Renderer::replaceLayerFromShadow(Layer& layer) {
    copyRegion(shadowFbo_, layer.fbo, {0, 0, width_, height_});
    if (layer.id != activeLayer_) {
        if (Layer* active = findLayer(activeLayer_)) {
            copyRegion(active->fbo, shadowFbo_, {0, 0, width_, height_});
        }
    }
    layer.dirtyArea = {0, 0, width_, height_};
}

void Renderer::copySelectionToLayer(int newId, bool cut) {
    Layer* source = findLayer(activeLayer_);
    if (!source) return;
    const int sourceId = source->id;
    const GLint target = currentFramebuffer();
    std::vector<uint8_t> pixels(static_cast<size_t>(width_) * height_ * 4);
    readLayer(sourceId, pixels.data());
    if (cut) pushFullHistory(*source);

    std::vector<uint8_t> copy(pixels.size());
    for (size_t index = 0; index < selection_.size(); ++index) {
        if (selection_[index] == 0) continue;
        std::memcpy(copy.data() + index * 4, pixels.data() + index * 4, 4);
        if (cut) std::memset(pixels.data() + index * 4, 0, 4);
    }
    if (cut) {
        writeLayer(sourceId, pixels.data());
        findLayer(sourceId)->dirtyArea = {0, 0, width_, height_};
    }
    createLayer(newId);
    writeLayer(newId, copy.data());
    findLayer(newId)->dirtyArea = {0, 0, width_, height_};
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::setGradientPreview(bool active, const float* points, const float* color) {
    gradientActive_ = active;
    if (active) {
        std::memcpy(gradientPointValues_, points, sizeof(gradientPointValues_));
        std::memcpy(gradientColorValues_, color, sizeof(gradientColorValues_));
    }
}

void Renderer::drawGradient(const float* matrix) {
    glUseProgram(gradientProgram_);
    glUniformMatrix4fv(gradientMatrix_, 1, GL_FALSE, matrix);
    glUniform2f(gradientSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform4fv(gradientPoints_, 1, gradientPointValues_);
    glUniform3fv(gradientColor_, 1, gradientColorValues_);
    glUniform1f(gradientUseMask_, selectionActive_ ? 1.0f : 0.0f);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(canvasVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

void Renderer::commitGradient() {
    Layer* layer = findLayer(activeLayer_);
    if (!layer || !gradientActive_) return;
    const GLint target = currentFramebuffer();
    resetState();
    pushFullHistory(*layer);
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
    glViewport(0, 0, width_, height_);
    drawGradient(toCanvas);
    copyRegion(layer->fbo, shadowFbo_, {0, 0, width_, height_});
    layer->dirtyArea = {0, 0, width_, height_};
    gradientActive_ = false;
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::setAdjustment(bool active, const float* values) {
    adjustActive_ = active;
    if (active) std::memcpy(adjustment_, values, sizeof(adjustment_));
}

void Renderer::commitAdjustment(bool chained) {
    Layer* layer = findLayer(activeLayer_);
    if (!layer || !adjustActive_) return;
    const GLint target = currentFramebuffer();
    resetState();
    pushFullHistory(*layer, chained);
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
    glViewport(0, 0, width_, height_);
    glUseProgram(adjustProgram_);
    glUniformMatrix4fv(adjustMatrix_, 1, GL_FALSE, toCanvas);
    glUniform2f(adjustSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform3fv(adjustValues_, 1, adjustment_);
    glUniform3fv(adjustCurve_, 1, adjustment_ + 3);
    glUniform1f(adjustUseMask_, selectionActive_ ? 1.0f : 0.0f);
    glUniform1f(adjustUseHide_, 0.0f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, layer->texture);
    glBindVertexArray(canvasVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    copyRegion(shadowFbo_, layer->fbo, {0, 0, width_, height_});
    layer->dirtyArea = {0, 0, width_, height_};
    adjustActive_ = false;
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::setFilter(bool active, int kind, const float* values, const float* color) {
    filterActive_ = active;
    if (!active) return;
    filterKind_ = kind;
    std::memcpy(filterValues_, values, sizeof(filterValues_));
    std::memcpy(filterColor_, color, sizeof(filterColor_));
}

void Renderer::useFilter(const float* matrix, bool preview, const Layer& layer) {
    glUseProgram(effectProgram_);
    glUniformMatrix4fv(effectMatrix_, 1, GL_FALSE, matrix);
    glUniform2f(effectSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform1i(effectKind_, filterKind_);
    glUniform4fv(effectValues_, 1, filterValues_);
    glUniform3fv(effectColor_, 1, filterColor_);
    glUniform1f(effectUseMask_, selectionActive_ ? 1.0f : 0.0f);
    if (preview) {
        useHide(&layer, kHideUnit, effectUseHide_);
    } else {
        glUniform1f(effectUseHide_, 0.0f);
    }
}

void Renderer::commitFilter() {
    Layer* layer = findLayer(activeLayer_);
    if (!layer || !filterActive_) return;
    const GLint target = currentFramebuffer();
    resetState();
    pushFullHistory(*layer);
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
    glViewport(0, 0, width_, height_);
    useFilter(toCanvas, false, *layer);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, layer->texture);
    glBindVertexArray(canvasVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    copyRegion(shadowFbo_, layer->fbo, {0, 0, width_, height_});
    layer->dirtyArea = {0, 0, width_, height_};
    filterActive_ = false;
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::drawSelectionTint(const float* matrix) {
    if (!selectionActive_ || !showSelectionTint_) return;
    glUseProgram(canvasProgram_);
    glUniform2f(canvasSize_, static_cast<float>(width_), static_cast<float>(height_));
    glUniform4fv(canvasTint_, 1, kTint);
    glBindVertexArray(canvasVao_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    drawQuad(matrix);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
    glUniform4f(canvasTint_, 0.0f, 0.0f, 0.0f, 0.0f);
}

void Renderer::setLayerTransform(int id, const float* transform) {
    previewLayer_ = id;
    if (id != 0) viewToMatrix(transform, previewTransform_);
}

void Renderer::commitLayerTransform() {
    Layer* layer = findLayer(previewLayer_);
    if (!layer) return;
    const GLint target = currentFramebuffer();
    resetState();
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    float matrix[16];
    multiply(toCanvas, previewTransform_, matrix);
    // A layer's mask moves with it, and one undo brings both back.
    Layer* mask = findLayer(layer->maskId);
    for (Layer* moved : {mask, layer}) {
        if (!moved) continue;
        pushFullHistory(*moved, moved == layer && mask != nullptr);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
        glViewport(0, 0, width_, height_);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(canvasProgram_);
        glUniform2f(canvasSize_, static_cast<float>(width_), static_cast<float>(height_));
        glUniform4f(canvasSolid_, 0.0f, 0.0f, 0.0f, 0.0f);
        glUniform1f(canvasOpacity_, 1.0f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, moved->texture);
        glBindVertexArray(canvasVao_);
        drawQuad(matrix);
        glBindVertexArray(0);
        replaceLayerFromShadow(*moved);
    }
    previewLayer_ = 0;
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::beginLiquify(int id) {
    if (!findLayer(id)) return;
    liquifyLayer_ = id;
    liquify_ = std::make_unique<LiquifyMesh>(width_, height_,
                                             std::max(1, std::max(width_, height_) / kLiquifyCells));
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, liquifyIndices_);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(liquify_->indices().size() * sizeof(uint32_t)),
                 liquify_->indices().data(), GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    liquifyDirty_ = true;
}

void Renderer::liquifyDab(float fromX, float fromY, float toX, float toY, float radius,
                          float strength, LiquifyMode mode) {
    if (!liquify_) return;
    liquify_->dab(fromX, fromY, toX, toY, radius, strength, mode,
                  selectionActive_ ? selection_.data() : nullptr);
    liquifyDirty_ = true;
}

void Renderer::drawLiquify(const float* matrix, float opacity, bool clipped, const Layer& layer) {
    glUseProgram(liquifyProgram_);
    glUniform1f(liquifyMatte_, blendMatte_);
    glUniformMatrix4fv(liquifyMatrix_, 1, GL_FALSE, matrix);
    glUniform1f(liquifyOpacity_, opacity);
    glUniform1f(liquifyUseClip_, clipped ? 1.0f : 0.0f);
    useHide(&layer, kHideUnit, liquifyUseHide_);
    glUniform1f(liquifyUseClipHide_, 0.0f);
    glBindVertexArray(liquifyVao_);
    glBindBuffer(GL_ARRAY_BUFFER, liquifyVertices_);
    if (liquifyDirty_) {
        glBufferData(GL_ARRAY_BUFFER,
                     static_cast<GLsizeiptr>(liquify_->vertices().size() * sizeof(float)),
                     liquify_->vertices().data(), GL_STREAM_DRAW);
        liquifyDirty_ = false;
    }
    glDrawElements(GL_TRIANGLES, static_cast<GLsizei>(liquify_->indices().size()), GL_UNSIGNED_INT,
                   nullptr);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
}

void Renderer::warpLayer(Layer& layer, bool chained) {
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    pushFullHistory(layer, chained);
    glBindFramebuffer(GL_FRAMEBUFFER, shadowFbo_);
    glViewport(0, 0, width_, height_);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, layer.texture);
    Layer plain;
    drawLiquify(toCanvas, 1.0f, false, plain);
    replaceLayerFromShadow(layer);
}

void Renderer::commitLiquify() {
    Layer* layer = findLayer(liquifyLayer_);
    if (!layer || !liquify_) return;
    const GLint target = currentFramebuffer();
    resetState();
    Layer* mask = findLayer(layer->maskId);
    if (mask) warpLayer(*mask, false);
    warpLayer(*layer, mask != nullptr);
    cancelLiquify();
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::cancelLiquify() {
    liquify_.reset();
    liquifyLayer_ = 0;
}

bool Renderer::fillPolygon(const float* points, int count, const float* color, float opacity,
                           bool erase, bool alphaLock) {
    Layer* layer = findLayer(activeLayer_);
    if (!layer || count < 3) return false;
    const GLint target = currentFramebuffer();
    std::vector<uint8_t> coverage(static_cast<size_t>(width_) * height_, 0);
    FillBounds box{width_, height_, 0, 0};
    polygonCoverage(points, count, width_, height_, coverage, box);
    std::vector<float> copy(static_cast<size_t>(count) * 2);
    for (const Mirror& mirror : mirrors_) {
        for (int i = 0; i < count; ++i) {
            const float x = points[i * 2];
            const float y = points[i * 2 + 1];
            copy[i * 2] = mirror.a * x + mirror.b * y + mirror.c;
            copy[i * 2 + 1] = mirror.d * x + mirror.e * y + mirror.f;
        }
        polygonCoverage(copy.data(), count, width_, height_, coverage, box);
    }
    if (box.right <= box.left || box.bottom <= box.top) return false;

    const Rect rect{box.left, box.top, box.right, box.bottom};
    HistoryEntry entry;
    entry.layer = layer->id;
    entry.rect = rect;
    entry.pixels.resize(static_cast<size_t>(rect.width()) * rect.height() * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(rect.left, rect.top, rect.width(), rect.height(), GL_RGBA, GL_UNSIGNED_BYTE,
                 entry.pixels.data());
    std::vector<uint8_t> pixels = entry.pixels;
    size_t offset = 0;
    for (int y = rect.top; y < rect.bottom; ++y) {
        for (int x = rect.left; x < rect.right; ++x, offset += 4) {
            const size_t index = static_cast<size_t>(y) * width_ + x;
            float amount = coverage[index] / 255.0f * opacity;
            if (selectionActive_) amount *= selection_[index] / 255.0f;
            if (amount <= 0.0f) continue;
            uint8_t* pixel = pixels.data() + offset;
            const float keep = 1.0f - amount;
            const float alpha = pixel[3] / 255.0f;
            for (int channel = 0; channel < 3; ++channel) {
                float value = pixel[channel] * keep;
                if (!erase) value += color[channel] * 255.0f * amount * (alphaLock ? alpha : 1.0f);
                pixel[channel] = static_cast<uint8_t>(std::min(value + 0.5f, 255.0f));
            }
            if (erase) {
                pixel[3] = static_cast<uint8_t>(pixel[3] * keep + 0.5f);
            } else if (!alphaLock) {
                pixel[3] = static_cast<uint8_t>(std::min(pixel[3] * keep + 255.0f * amount + 0.5f, 255.0f));
            }
        }
    }
    pushHistory(std::move(entry));
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, layer->texture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, rect.left, rect.top, rect.width(), rect.height(), GL_RGBA,
                    GL_UNSIGNED_BYTE, pixels.data());
    copyRegion(layer->fbo, shadowFbo_, rect);
    layer->dirtyArea = unite(layer->dirtyArea, rect);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    return true;
}

void Renderer::setViewOptions(int mode, int guide) {
    viewMode_ = mode;
    guide_ = guide;
}

void Renderer::setFrames(const float* values, int count) {
    frames_.assign(values, values + count);
}

void Renderer::setSymmetry(int mode, float x, float y) {
    symmetry_ = mode;
    symmetryX_ = x;
    symmetryY_ = y;
    mirrors_ = symmetryMirrors(mode, x, y);
}

void Renderer::setPerspective(const float* points, int count) {
    perspective_.assign(points, points + count * 2);
}

void Renderer::setFigureGuide(float top, float bottom, float divisions) {
    figureTop_ = top;
    figureBottom_ = bottom;
    figureDivisions_ = divisions;
}

void Renderer::appendLine(float x1, float y1, float x2, float y2) {
    constexpr float kHalfWidth = 2.0f;
    const float length = std::max(std::hypot(x2 - x1, y2 - y1), 1.0f);
    const float nx = -(y2 - y1) / length * kHalfWidth;
    const float ny = (x2 - x1) / length * kHalfWidth;
    const float corners[6][2] = {{x1 - nx, y1 - ny}, {x2 - nx, y2 - ny}, {x2 + nx, y2 + ny},
                                 {x1 - nx, y1 - ny}, {x2 + nx, y2 + ny}, {x1 + nx, y1 + ny}};
    for (const auto& corner : corners) {
        vertices_.insert(vertices_.end(), {corner[0], corner[1], 0.0f, 0.0f, 0.55f, 1.0f});
    }
}

void Renderer::drawPerspective(const float* matrix) {
    constexpr int kRays = 36;
    constexpr float kPi = 3.14159265f;
    static constexpr float kColors[3][3] = {{0.85f, 0.35f, 0.19f},
                                            {0.11f, 0.62f, 0.46f},
                                            {0.33f, 0.29f, 0.72f}};
    const int count = static_cast<int>(perspective_.size() / 2);
    if (count == 0 && figureDivisions_ <= 0.0f && symmetry_ == 0 && guide_ != kIsometricGuide) return;
    const float reach = (width_ + height_) * 3.0f;

    glUseProgram(stampProgram_);
    glUniformMatrix4fv(stampMatrix_, 1, GL_FALSE, matrix);
    glUniform1f(stampHardness_, 1.0f);
    glUniform1f(stampGrain_, 0.0f);
    glUniform1f(stampUseMask_, 0.0f);
    glUniform1f(stampUseTip_, 0.0f);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    for (int point = 0; point < count; ++point) {
        const float x = perspective_[point * 2];
        const float y = perspective_[point * 2 + 1];
        vertices_.clear();
        for (int ray = 0; ray < kRays; ++ray) {
            const float angle = ray * 2.0f * kPi / kRays;
            appendLine(x, y, x + std::cos(angle) * reach, y + std::sin(angle) * reach);
        }
        if (point < 2) appendLine(x - reach, y, x + reach, y);
        glUniform3fv(stampColor_, 1, kColors[point % 3]);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertices_.size() * sizeof(float)),
                     vertices_.data(), GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices_.size() / kStampFloats));
    }
    if (figureDivisions_ > 0.0f) {
        vertices_.clear();
        const float step = (figureBottom_ - figureTop_) / figureDivisions_;
        for (int line = 0; line <= static_cast<int>(figureDivisions_); ++line) {
            const float y = figureTop_ + step * line;
            appendLine(0.0f, y, static_cast<float>(width_), y);
        }
        appendLine(0.0f, figureBottom_, static_cast<float>(width_), figureBottom_);
        glUniform3fv(stampColor_, 1, kColors[1]);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertices_.size() * sizeof(float)),
                     vertices_.data(), GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices_.size() / kStampFloats));
    }
    if (guide_ == kIsometricGuide) {
        vertices_.clear();
        const float w = static_cast<float>(width_);
        const float h = static_cast<float>(height_);
        const float spacing = h / 12.0f;
        const float rise = w * 0.57735f;  // How far a 30 degree line climbs across the canvas.
        for (float y = -rise; y < h + rise; y += spacing) {
            appendLine(0.0f, y, w, y + rise);
            appendLine(0.0f, y, w, y - rise);
        }
        for (float x = 0.0f; x < w; x += spacing * 0.866f) appendLine(x, 0.0f, x, h);
        glUniform3fv(stampColor_, 1, kColors[2]);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertices_.size() * sizeof(float)),
                     vertices_.data(), GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices_.size() / kStampFloats));
    }
    if (symmetry_ != 0) {
        vertices_.clear();
        const float x = symmetryX_;
        const float y = symmetryY_;
        if (symmetry_ != 2) appendLine(x, y - reach, x, y + reach);
        if (symmetry_ != 1) appendLine(x - reach, y, x + reach, y);
        if (symmetry_ == 4) {
            appendLine(x - reach, y - reach, x + reach, y + reach);
            appendLine(x - reach, y + reach, x + reach, y - reach);
        }
        glUniform3fv(stampColor_, 1, kColors[0]);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertices_.size() * sizeof(float)),
                     vertices_.data(), GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices_.size() / kStampFloats));
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

void Renderer::fillCanvasRect(const float* matrix, float x, float y, float width, float height) {
    const float place[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, 0, 1};
    float placed[16];
    multiply(matrix, place, placed);
    glUniform2f(canvasSize_, width, height);
    drawQuad(placed);
}

void Renderer::drawGuides(const float* matrix) {
    if (guide_ == 0 || guide_ == kIsometricGuide) return;
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
    } else if (guide_ == 3) {
        for (int i = 1; i < 8; ++i) columns.push_back(i / 8.0f);
        rows = columns;
    } else if (guide_ == kViewsGuide) {
        columns = {0.25f, 0.5f, 0.75f};
    }
    glUseProgram(canvasProgram_);
    glUniform4f(canvasSolid_, 0.55f, 0.23f, 0.12f, 0.65f);
    glBindVertexArray(canvasVao_);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    for (const float column : columns) fillCanvasRect(matrix, column * w, 0.0f, kLine, h);
    for (const float row : rows) fillCanvasRect(matrix, 0.0f, row * h, w, kLine);
    if (guide_ == kFramesGuide) {
        for (size_t i = 0; i + 3 < frames_.size(); i += 4) {
            const float left = frames_[i];
            const float top = frames_[i + 1];
            const float wide = frames_[i + 2] - left;
            const float tall = frames_[i + 3] - top;
            fillCanvasRect(matrix, left, top, wide, kLine);
            fillCanvasRect(matrix, left, top + tall, wide, kLine);
            fillCanvasRect(matrix, left, top, kLine, tall);
            fillCanvasRect(matrix, left + wide, top, kLine, tall);
        }
    }
    glDisable(GL_BLEND);
    glBindVertexArray(0);
}

void Renderer::drawScene(const float* matrix, int bufferWidth, int bufferHeight,
                         const std::vector<Stamp>* stamps) {
    if (viewMode_ == 0) {
        drawCanvas(matrix);
        if (stamps) drawStamps(*stamps, matrix);
        drawGuides(matrix);
        drawPerspective(matrix);
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
    drawPerspective(matrix);
}

void Renderer::setActiveLayer(int id) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    const GLint target = currentFramebuffer();
    activeLayer_ = id;
    copyRegion(layer->fbo, shadowFbo_, {0, 0, width_, height_});
    glBindFramebuffer(GL_FRAMEBUFFER, target);
}

void Renderer::setLayerProperties(int id, float opacity, bool visible, BlendMode blend,
                                  bool clip) {
    if (Layer* layer = findLayer(id)) {
        layer->opacity = opacity;
        layer->visible = visible;
        layer->blend = blend;
        layer->clip = clip;
    }
}

void Renderer::moveLayer(int id, int index) {
    Layer* found = findLayer(id);
    if (!found || index < 0 || index >= static_cast<int>(layers_.size())) return;
    const Layer layer = *found;
    layers_.erase(layers_.begin() + (found - layers_.data()));
    layers_.insert(layers_.begin() + index, layer);
}

void Renderer::setLayerOrder(const int* ids, int count) {
    std::vector<Layer> ordered;
    ordered.reserve(layers_.size());
    for (int i = 0; i < count; ++i) {
        if (Layer* layer = findLayer(ids[i])) ordered.push_back(*layer);
    }
    for (const Layer& layer : layers_) {
        if (std::find(ids, ids + count, layer.id) != ids + count) continue;
        ordered.push_back(layer);
        ordered.back().visible = false;
    }
    layers_ = std::move(ordered);
}

void Renderer::markLayerDirty(int id) {
    if (Layer* layer = findLayer(id)) layer->dirtyArea = {0, 0, width_, height_};
}

Rect Renderer::takeLayerDirty(int id) {
    Layer* layer = findLayer(id);
    if (!layer) return {};
    const Rect area = layer->dirtyArea;
    layer->dirtyArea = {};
    return area;
}

void Renderer::readLayerRegion(int id, const Rect& rect, void* pixels) {
    Layer* layer = findLayer(id);
    if (!layer) return;
    const GLint target = currentFramebuffer();
    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(rect.left, rect.top, rect.width(), rect.height(), GL_RGBA, GL_UNSIGNED_BYTE,
                 pixels);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
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
    vertices_.reserve(stamps.size() * 6 * kStampFloats);
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
            vertices_.push_back(stamp.shade);
        }
    }
    glUseProgram(stampProgram_);
    glUniformMatrix4fv(stampMatrix_, 1, GL_FALSE, matrix);
    glUniform3fv(stampColor_, 1, brush_.color);
    glUniform1f(stampHardness_, brush_.hardness);
    glUniform1f(stampGrain_, brush_.grain);
    glUniform1f(stampUseMask_, maskStamps_ ? 1.0f : 0.0f);
    glUniform1f(stampUseTip_, brush_.tip.empty() ? 0.0f : 1.0f);
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
    glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertices_.size() / kStampFloats));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

void Renderer::drawQuad(const float* matrix) {
    glUniformMatrix4fv(canvasMatrix_, 1, GL_FALSE, matrix);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void Renderer::drawSmudge(const std::vector<Stamp>& stamps, const float* matrix, GLuint layerFbo) {
    glUseProgram(smudgeProgram_);
    glUniformMatrix4fv(smudgeMatrix_, 1, GL_FALSE, matrix);
    const GLint erase = glGetUniformLocation(smudgeProgram_, "uErase");
    glUniform1f(glGetUniformLocation(smudgeProgram_, "uUseMask"), selectionActive_ ? 1.0f : 0.0f);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, carryTexture_);
    glEnable(GL_BLEND);
    for (const Stamp& stamp : stamps) {
        const float r = std::max(stamp.radiusX, 1.0f);
        if (carryReady_) {
            const float corners[6][2] = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
            float vertices[6 * kStampFloats];
            for (int i = 0; i < 6; ++i) {
                float* vertex = vertices + i * kStampFloats;
                vertex[0] = stamp.x + corners[i][0] * r;
                vertex[1] = stamp.y + corners[i][1] * r;
                vertex[2] = corners[i][0];
                vertex[3] = corners[i][1];
                vertex[4] = stamp.alpha;
                vertex[5] = 1.0f;
            }
            glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
            glUniform1f(erase, 1.0f);
            glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glUniform1f(erase, 0.0f);
            glBlendFunc(GL_ONE, GL_ONE);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, layerFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, carryFbo_);
        glBlitFramebuffer(std::max(0, static_cast<int>(stamp.x - r)),
                          std::max(0, static_cast<int>(stamp.y - r)),
                          std::min(width_, static_cast<int>(stamp.x + r)),
                          std::min(height_, static_cast<int>(stamp.y + r)), 0, 0, kCarrySize,
                          kCarrySize, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindFramebuffer(GL_FRAMEBUFFER, layerFbo);
        glEnable(GL_BLEND);
        carryReady_ = true;
    }
    glDisable(GL_BLEND);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
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
    const Layer* base = nullptr;
    for (const Layer& layer : layers_) {
        if (layer.isMask) continue;
        if (!layer.clip) base = &layer;
        const bool clipped = layer.clip && base != nullptr;
        if (!layer.visible || (clipped && !base->visible)) continue;
        glBlendEquation(GL_FUNC_ADD);
        blendMatte_ = 0.0f;
        switch (layer.blend) {
            case BlendMode::kNormal: glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); break;
            case BlendMode::kMultiply: glBlendFunc(GL_DST_COLOR, GL_ONE_MINUS_SRC_ALPHA); break;
            case BlendMode::kScreen: glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR); break;
            case BlendMode::kAdd: glBlendFunc(GL_ONE, GL_ONE); break;
            case BlendMode::kDarken:
                glBlendEquation(GL_MIN);
                blendMatte_ = 1.0f;
                break;
            case BlendMode::kLighten: glBlendEquation(GL_MAX); break;
            case BlendMode::kSoftLight:
                glBlendFunc(GL_DST_COLOR, GL_SRC_COLOR);
                blendMatte_ = 0.5f;
                break;
            case BlendMode::kSubtract:
                glBlendEquation(GL_FUNC_REVERSE_SUBTRACT);
                glBlendFunc(GL_ONE, GL_ONE);
                break;
            case BlendMode::kExclusion: glBlendFunc(GL_ONE_MINUS_DST_COLOR, GL_ONE_MINUS_SRC_COLOR); break;
        }
        glUniform1f(canvasMatte_, blendMatte_);
        glUniform1f(canvasOpacity_, layer.opacity);
        glUniform1f(canvasUseClip_, clipped ? 1.0f : 0.0f);
        useHide(&layer, kHideUnit, canvasUseHide_);
        useHide(clipped ? base : nullptr, kClipHideUnit, canvasUseClipHide_);
        if (clipped) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, base->texture);
            glActiveTexture(GL_TEXTURE0);
        }
        glBindTexture(GL_TEXTURE_2D, layer.texture);
        if (filterActive_ && layer.id == activeLayer_) {
            useFilter(matrix, true, layer);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glUseProgram(canvasProgram_);
        } else if (adjustActive_ && layer.id == activeLayer_) {
            glUseProgram(adjustProgram_);
            glUniformMatrix4fv(adjustMatrix_, 1, GL_FALSE, matrix);
            glUniform2f(adjustSize_, static_cast<float>(width_), static_cast<float>(height_));
            glUniform3fv(adjustValues_, 1, adjustment_);
            glUniform3fv(adjustCurve_, 1, adjustment_ + 3);
            glUniform1f(adjustUseMask_, 0.0f);
            useHide(&layer, kHideUnit, adjustUseHide_);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glUseProgram(canvasProgram_);
        } else if (liquify_ && layer.id == liquifyLayer_) {
            drawLiquify(matrix, layer.opacity, clipped, layer);
            glUseProgram(canvasProgram_);
            glBindVertexArray(canvasVao_);
        } else if (layer.id == previewLayer_) {
            float moved[16];
            multiply(matrix, previewTransform_, moved);
            drawQuad(moved);
        } else {
            drawQuad(matrix);
        }
        if (gradientActive_ && (layer.id == activeLayer_ || layer.maskId == activeLayer_)) {
            drawGradient(matrix);
            glUseProgram(canvasProgram_);
            glEnable(GL_BLEND);
            glBindVertexArray(canvasVao_);
        }
    }
    glBlendEquation(GL_FUNC_ADD);
    glUniform1f(canvasMatte_, 0.0f);
    glUniform1f(canvasUseClip_, 0.0f);
    glUniform1f(canvasUseHide_, 0.0f);
    glUniform1f(canvasUseClipHide_, 0.0f);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
    drawSelectionTint(matrix);
}

void Renderer::beginStroke(const BrushParams& brush) {
    if (!findLayer(activeLayer_)) return;
    brush_ = brush;
    carryReady_ = false;
    if (!brush.tip.empty()) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, tipTexture_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kTipSize, kTipSize, GL_RED, GL_UNSIGNED_BYTE,
                        brush.tip.data());
        glActiveTexture(GL_TEXTURE0);
    }
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
    if (!end && predictedCount > 0 && !brush_.eraser && !brush_.smudge && !layer->isMask) {
        StrokeBuilder prediction = *stroke_;
        for (int i = 0; i < predictedCount; ++i) prediction.add(predicted[i], predictedStamps_);
        prediction.finish(predictedStamps_);
    }

    if (!brush_.smudge && !mirrors_.empty()) {
        mirrorStamps(stamps_, mirrors_);
        mirrorStamps(predictedStamps_, mirrors_);
    }

    if (!stamps_.empty()) {
        const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                    0,             0, 1, 0, -1, -1,            0, 1};
        glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);
        glViewport(0, 0, width_, height_);
        if (brush_.smudge) {
            drawSmudge(stamps_, toCanvas, layer->fbo);
        } else {
            maskStamps_ = selectionActive_;
            drawStamps(stamps_, toCanvas);
            maskStamps_ = false;
        }
    }
    const Rect stampDirty = bounds(stamps_);
    layer->dirtyArea = unite(layer->dirtyArea, stampDirty);
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
        captureHistory(entry, shadowFbo_);
        copyRegion(layer->fbo, shadowFbo_, strokeDirty_);

        pushHistory(std::move(entry));
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
    if (entry.fbo != 0) {
        GLuint texture = 0;
        GLuint fbo = 0;
        createTarget(entry.rect.width(), entry.rect.height(), texture, fbo);
        blitToHistory(layer->fbo, fbo, entry.rect);
        blitFromHistory(entry.fbo, layer->fbo, entry.rect);
        if (layer->id == activeLayer_) blitFromHistory(entry.fbo, shadowFbo_, entry.rect);
        layer->dirtyArea = unite(layer->dirtyArea, entry.rect);
        dropHistory(entry);
        entry.texture = texture;
        entry.fbo = fbo;
        to.push_back(std::move(entry));
        glBindFramebuffer(GL_FRAMEBUFFER, target);
        return true;
    }
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
    layer->dirtyArea = unite(layer->dirtyArea, entry.rect);

    entry.pixels = std::move(current);
    to.push_back(std::move(entry));
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    return true;
}

bool Renderer::undo() {
    bool changed = false;
    bool more = true;
    while (more && !undo_.empty()) {
        more = undo_.back().chained;
        changed = swapHistory(undo_, redo_) || changed;
    }
    return changed;
}

bool Renderer::redo() {
    bool changed = swapHistory(redo_, undo_);
    while (!redo_.empty() && redo_.back().chained) swapHistory(redo_, undo_);
    return changed;
}

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
    showSelectionTint_ = false;
    GLuint texture = 0;
    GLuint fbo = 0;
    createTarget(width, height, texture, fbo);
    const float toCanvas[16] = {2.0f / width_, 0, 0, 0, 0, 2.0f / height_, 0, 0,
                                0,             0, 1, 0, -1, -1,            0, 1};
    glViewport(0, 0, width, height);
    drawCanvas(toCanvas);
    showSelectionTint_ = true;
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
    showSelectionTint_ = false;
    drawCanvas(toPixel);
    showSelectionTint_ = true;
    uint8_t pixel[4] = {};
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    glBindFramebuffer(GL_FRAMEBUFFER, target);
    return (pixel[0] << 16) | (pixel[1] << 8) | pixel[2];
}

}  // namespace cappuccino
