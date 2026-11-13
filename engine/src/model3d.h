#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace cappuccino {

// The 3D drawing aid: simple shapes, a posable mannequin, and imported models, drawn on the CPU
// so it works the same on Android and the web. Output is premultiplied RGBA, rows from the top.

enum class ModelShape { kFigure = 0, kHead = 1, kBox = 2, kSphere = 3, kCylinder = 4, kImported = 5 };

// Figure joints, in this order: head top, chin, neck, left shoulder, elbow, wrist, right
// shoulder, elbow, wrist, pelvis, left hip, knee, ankle, right hip, knee, ankle. "Left" is the
// figure's own left, which faces you on the right of the picture.
constexpr int kFigureJoints = 16;

struct ModelView {
    float yaw = 0.0f;    // Turn around the vertical axis, in radians.
    float pitch = 0.0f;  // Tip toward or away from you, in radians.
    float lens = 0.3f;   // 0 is flat, 1 is a wide angle with strong foreshortening.
    float centerX = 0.0f;  // Where the model's middle lands, in output pixels.
    float centerY = 0.0f;
    float size = 100.0f;   // The model's height in output pixels.
    float lightAzimuth = 2.4f;    // Where the light comes from on screen: 0 right, pi/2 top.
    float lightElevation = 0.6f;  // 0 grazes the surface, pi/2 comes straight from you.
    int style = 0;                // 0 shaded with lines, 1 lines only, for tracing.
};

struct Vec3 {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
};

class ModelScene {
public:
    ModelScene();

    void setShape(ModelShape shape);
    // kFigureJoints points in head units: x to the picture's right, y up, z toward you.
    void setPose(const float* joints);
    // Reads the vertices and faces of a Wavefront OBJ file. False when there's nothing to draw.
    bool loadObj(const std::string& text);
    ModelShape shape() const { return shape_; }
    size_t triangleCount() const { return indices_.size() / 3; }

    // The output pixels the model covers: left, top, right, bottom.
    void bounds(const ModelView& view, int width, int height, int* box) const;
    // Draws over clear premultiplied RGBA, width * height * 4 bytes, rows from the top. Only
    // pixels inside the model's bounds are written.
    void render(const ModelView& view, int width, int height, uint8_t* rgba) const;

private:
    struct Projected {
        float x, y, depth;
        Vec3 normal;
        Vec3 local;
    };

    void rebuild();
    void addEllipsoid(Vec3 center, Vec3 axisX, Vec3 axisY, Vec3 axisZ, bool guides = false, float level = 0.0f,
                      int detail = 1);
    void addSegment(Vec3 from, Vec3 to, float radius);
    void addBox(Vec3 half);
    void addCylinder(float radius, float halfHeight);
    int newPart(float radius, bool guides, float level, bool creases);
    void fit();
    void project(const ModelView& view, float scale, float offsetX, float offsetY, std::vector<Projected>& out) const;
    void renderStrip(const ModelView& view, int scale, uint8_t* rgba, int width, int left, int top, int right,
                     int bottom) const;

    ModelShape shape_ = ModelShape::kFigure;
    float joints_[kFigureJoints * 3] = {};
    std::vector<Vec3> importedPositions_;
    std::vector<uint32_t> importedIndices_;

    std::vector<Vec3> positions_;
    std::vector<Vec3> normals_;
    std::vector<Vec3> local_;          // Each vertex on its unit shape, for construction lines.
    std::vector<int> part_;            // Which part each vertex belongs to.
    std::vector<float> partRadius_;    // Each part's size, to keep construction lines even.
    std::vector<uint8_t> partGuides_;  // Whether a part shows construction lines.
    std::vector<float> partLevel_;     // The height of a part's crosswise construction line.
    std::vector<uint8_t> partCreases_; // Whether a part has hard edges to outline.
    std::vector<int> partGroup_;       // Parts in one group meet without a line, as one surface.
    int joinGroup_ = -1;               // The group new parts join, or -1 for a group of their own.
    std::vector<uint32_t> indices_;
};

// The standing pose every figure starts from, in head units, as kFigureJoints * 3 floats.
const float* standardPose();

}  // namespace cappuccino
