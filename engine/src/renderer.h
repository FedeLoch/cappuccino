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
};

// Owns the artwork on the GPU. All methods must run on the thread that holds the GL
// context, and each one restores the framebuffer that was bound when it was called.
class Renderer {
public:
    Renderer(int canvasWidth, int canvasHeight);
    ~Renderer();

    void createLayer(int id);
    void deleteLayer(int id);
    void setActiveLayer(int id);
    void setLayerProperties(int id, float opacity, bool visible, BlendMode blend);
    void moveLayer(int id, int index);
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
