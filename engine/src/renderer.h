#pragma once

#include <GLES3/gl3.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "stroke.h"

namespace cappuccino {

struct Rect {
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;

    bool empty() const { return right <= left || bottom <= top; }
    int width() const { return right - left; }
    int height() const { return bottom - top; }
};

enum class BlendMode { kNormal = 0, kMultiply = 1, kScreen = 2, kAdd = 3 };

struct BrushParams {
    BrushShape shape;
    float diameter = 24.0f;
    float color[3] = {0.0f, 0.0f, 0.0f};
    float hardness = 1.0f;
    float grain = 0.0f;
    bool eraser = false;
    bool alphaLock = false;
    bool smudge = false;
    std::vector<uint8_t> tip;  // Optional square grayscale tip, kTipSize on each side.
};

constexpr int kTipSize = 128;

// Owns the artwork on the GPU. All methods must run on the thread that holds the GL
// context, and each one restores the framebuffer that was bound when it was called.
class Renderer {
public:
    Renderer(int canvasWidth, int canvasHeight);
    ~Renderer();

    void createLayer(int id);
    void deleteLayer(int id);
    void setActiveLayer(int id);
    void setLayerProperties(int id, float opacity, bool visible, BlendMode blend, bool clip);
    void moveLayer(int id, int index);
    // Orders the listed layers and hides the rest, which stay available for undo.
    void setLayerOrder(const int* ids, int count);
    void markLayerDirty(int id);
    void duplicateLayer(int id, int newId);
    void mergeLayerDown(int id, int belowId);
    void readLayerThumbnail(int id, int width, int height, void* pixels);
    void setViewOptions(int mode, int guide);
    bool fillAt(int x, int y, const float* color);
    // Previews a layer under a 3x3 row-major transform, or ends the preview when id is 0.
    void setLayerTransform(int id, const float* transform);
    void commitLayerTransform();

    void setSelectionPolygon(const float* points, int count);
    void selectColorAt(int x, int y, int tolerance);
    void clearSelection();
    void invertSelection();
    bool hasSelection() const { return selectionActive_; }
    void copySelectionToLayer(int newId, bool cut);

    void setGradientPreview(bool active, const float* points, const float* color);
    void commitGradient();
    // Previews hue, saturation, brightness, shadows, midtones, and highlights on the active layer.
    void setAdjustment(bool active, const float* values);
    void commitAdjustment();
    void setPerspective(const float* points, int count);
    void setFigureGuide(float top, float bottom, float divisions);
    bool takeLayerDirty(int id);
    void readLayer(int id, void* pixels);
    void writeLayer(int id, const void* pixels);

    void beginStroke(const BrushParams& brush);
    void drawFront(const float* projection, const float* view, int bufferWidth, int bufferHeight,
                   const Sample* samples, int sampleCount, const Sample* predicted,
                   int predictedCount, bool end);
    void cancelStroke();
    void compose(const float* projection, const float* view, int bufferWidth, int bufferHeight);
    bool undo();
    bool redo();

    // Flattens the artwork on white paper. Rows run from the top of the canvas.
    void readComposite(int width, int height, void* pixels);
    uint32_t pickColor(int x, int y);

private:
    struct Layer {
        int id = 0;
        GLuint texture = 0;
        GLuint fbo = 0;
        float opacity = 1.0f;
        bool visible = true;
        BlendMode blend = BlendMode::kNormal;
        bool dirty = false;
        bool clip = false;
    };

    struct HistoryEntry {
        int layer = 0;
        Rect rect;
        std::vector<uint8_t> pixels;
    };

    void commitStroke();
    void drawStamps(const std::vector<Stamp>& stamps, const float* matrix);
    void drawCanvas(const float* matrix);
    void drawQuad(const float* matrix);
    void drawScene(const float* matrix, int bufferWidth, int bufferHeight,
                   const std::vector<Stamp>* stamps);
    void drawGuides(const float* matrix);
    void drawSelectionTint(const float* matrix);
    void drawGradient(const float* matrix);
    void uploadSelection();
    void pushFullHistory(const Layer& layer);
    void replaceLayerFromShadow(Layer& layer);
    void drawSmudge(const std::vector<Stamp>& stamps, const float* matrix, GLuint layerFbo);
    void drawPerspective(const float* matrix);
    void appendLine(float x1, float y1, float x2, float y2);
    void fillCanvasRect(const float* matrix, float x, float y, float width, float height);
    void clearLayerHistory(int id);
    void pushHistory(HistoryEntry entry);
    Layer* findLayer(int id);
    bool swapHistory(std::vector<HistoryEntry>& from, std::vector<HistoryEntry>& to);
    void copyRegion(GLuint fromFbo, GLuint toFbo, const Rect& rect);
    Rect bounds(const std::vector<Stamp>& stamps) const;

    int width_;
    int height_;
    std::vector<Layer> layers_;  // Bottom layer first.
    int activeLayer_ = 0;
    GLuint shadowTexture_ = 0;  // The artwork as it was before the current stroke.
    GLuint shadowFbo_ = 0;
    GLuint stampProgram_ = 0;
    GLuint canvasProgram_ = 0;
    GLint stampMatrix_ = -1;
    GLint stampColor_ = -1;
    GLint stampHardness_ = -1;
    GLint stampGrain_ = -1;
    GLint canvasMatrix_ = -1;
    GLint canvasSize_ = -1;
    GLint canvasSolid_ = -1;
    GLint canvasOpacity_ = -1;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint canvasVao_ = 0;
    GLuint filterProgram_ = 0;
    GLint filterMode_ = -1;
    GLuint viewTexture_ = 0;
    GLuint viewFbo_ = 0;
    int viewWidth_ = 0;
    int viewHeight_ = 0;
    int viewMode_ = 0;
    int guide_ = 0;
    int previewLayer_ = 0;
    float figureTop_ = 0.0f;
    float figureBottom_ = 0.0f;
    float figureDivisions_ = 0.0f;
    float previewTransform_[16] = {};
    GLint canvasClip_ = -1;
    GLint canvasTint_ = -1;
    GLuint stampMaskTexture_ = 0;
    GLint stampUseMask_ = -1;
    GLint stampUseTip_ = -1;
    GLuint tipTexture_ = 0;
    GLuint carryTexture_ = 0;
    GLuint carryFbo_ = 0;
    bool carryReady_ = false;
    GLuint smudgeProgram_ = 0;
    GLint smudgeMatrix_ = -1;
    GLuint gradientProgram_ = 0;
    GLint gradientMatrix_ = -1;
    GLint gradientSize_ = -1;
    GLint gradientPoints_ = -1;
    GLint gradientColor_ = -1;
    GLint gradientUseMask_ = -1;
    bool gradientActive_ = false;
    float gradientPointValues_[4] = {};
    float gradientColorValues_[3] = {};
    GLuint adjustProgram_ = 0;
    GLint adjustMatrix_ = -1;
    GLint adjustSize_ = -1;
    GLint adjustValues_ = -1;
    GLint adjustCurve_ = -1;
    GLint adjustUseMask_ = -1;
    bool adjustActive_ = false;
    float adjustment_[6] = {};
    bool maskStamps_ = false;
    bool selectionActive_ = false;
    bool showSelectionTint_ = true;
    std::vector<uint8_t> selection_;
    GLint canvasUseClip_ = -1;
    std::vector<float> perspective_;  // x and y of each vanishing point.

    std::unique_ptr<StrokeBuilder> stroke_;
    BrushParams brush_;
    Rect strokeDirty_;
    Rect predictedDirty_;
    std::vector<Stamp> stamps_;
    std::vector<Stamp> predictedStamps_;
    std::vector<float> vertices_;
    std::vector<HistoryEntry> undo_;
    std::vector<HistoryEntry> redo_;
    size_t historyBytes_ = 0;
};

}  // namespace cappuccino
