#include "model3d.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <sstream>

namespace cappuccino {
namespace {

constexpr float kPi = 3.14159265f;
// A standing figure seven and a half heads tall, feet at zero.
constexpr float kStandard[kFigureJoints * 3] = {
    0.0f,   7.5f,  0.0f,    // head top
    0.0f,   6.5f,  0.05f,   // chin
    0.0f,   6.2f,  0.0f,    // neck
    0.9f,   6.0f,  0.0f,    // left shoulder
    1.0f,   4.6f,  -0.05f,  // left elbow
    1.05f,  3.45f, 0.05f,   // left wrist
    -0.9f,  6.0f,  0.0f,    // right shoulder
    -1.0f,  4.6f,  -0.05f,  // right elbow
    -1.05f, 3.45f, 0.05f,   // right wrist
    0.0f,   3.75f, 0.0f,    // pelvis
    0.5f,   3.6f,  0.0f,    // left hip
    0.52f,  1.8f,  0.05f,   // left knee
    0.5f,   0.25f, -0.05f,  // left ankle
    -0.5f,  3.6f,  0.0f,    // right hip
    -0.52f, 1.8f,  0.05f,   // right knee
    -0.5f,  0.25f, -0.05f,  // right ankle
};
// The figure's height in head units with its feet, so a figure keeps its size as it's posed.
constexpr float kFigureHeight = 7.6f;
constexpr int kRings = 14;
constexpr int kSegments = 22;
constexpr size_t kMaxTriangles = 400000;
// Pixels worked on at once, so memory stays small however big the canvas is.
constexpr int kStripPixels = 1 << 20;

// Paper-white in the light, a cool gray in shadow, the app's accent for construction lines.
constexpr float kLit[3] = {0.94f, 0.925f, 0.894f};
constexpr float kShadow[3] = {0.49f, 0.51f, 0.57f};
constexpr float kLine[3] = {0.2f, 0.17f, 0.157f};
constexpr float kGuide[3] = {0.847f, 0.353f, 0.188f};

Vec3 operator+(Vec3 a, Vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
Vec3 operator-(Vec3 a, Vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
Vec3 operator*(Vec3 a, float k) { return {a.x * k, a.y * k, a.z * k}; }
float dot(Vec3 a, Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
Vec3 cross(Vec3 a, Vec3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
float length(Vec3 a) { return std::sqrt(dot(a, a)); }
Vec3 normalize(Vec3 a) {
    const float size = length(a);
    return size > 1e-8f ? a * (1.0f / size) : Vec3{0.0f, 0.0f, 1.0f};
}

int parseIndex(const std::string& token, size_t count) {
    const int value = std::atoi(token.c_str());
    if (value > 0) return value - 1;
    if (value < 0) return static_cast<int>(count) + value;
    return -1;
}

}  // namespace

const float* standardPose() { return kStandard; }

ModelScene::ModelScene() {
    std::copy(kStandard, kStandard + kFigureJoints * 3, joints_);
    rebuild();
}

void ModelScene::setShape(ModelShape shape) {
    if (shape == ModelShape::kImported && importedIndices_.empty()) return;
    shape_ = shape;
    rebuild();
}

void ModelScene::setPose(const float* joints) {
    std::copy(joints, joints + kFigureJoints * 3, joints_);
    if (shape_ == ModelShape::kFigure) rebuild();
}

bool ModelScene::loadObj(const std::string& text) {
    std::vector<Vec3> positions;
    std::vector<uint32_t> indices;
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line) && indices.size() / 3 < kMaxTriangles) {
        std::istringstream words(line);
        std::string kind;
        words >> kind;
        if (kind == "v") {
            Vec3 point;
            words >> point.x >> point.y >> point.z;
            positions.push_back(point);
        } else if (kind == "f") {
            std::vector<int> face;
            std::string token;
            while (words >> token) {
                const int index = parseIndex(token.substr(0, token.find('/')), positions.size());
                if (index >= 0 && index < static_cast<int>(positions.size())) face.push_back(index);
            }
            // Faces with more corners become a fan of triangles.
            for (size_t i = 2; i < face.size(); ++i) {
                indices.insert(indices.end(), {static_cast<uint32_t>(face[0]), static_cast<uint32_t>(face[i - 1]),
                                               static_cast<uint32_t>(face[i])});
            }
        }
    }
    if (indices.empty()) return false;
    importedPositions_ = std::move(positions);
    importedIndices_ = std::move(indices);
    shape_ = ModelShape::kImported;
    rebuild();
    return true;
}

int ModelScene::newPart(float radius, bool guides, float level, bool creases) {
    partRadius_.push_back(std::max(radius, 1e-4f));
    partGuides_.push_back(guides ? 1 : 0);
    partLevel_.push_back(level);
    partCreases_.push_back(creases ? 1 : 0);
    partGroup_.push_back(joinGroup_ >= 0 ? joinGroup_ : static_cast<int>(partGroup_.size()));
    return static_cast<int>(partRadius_.size()) - 1;
}

void ModelScene::addEllipsoid(Vec3 center, Vec3 axisX, Vec3 axisY, Vec3 axisZ, bool guides, float level, int detail) {
    const int part = newPart((length(axisX) + length(axisY) + length(axisZ)) / 3.0f, guides, level, false);
    const int rings = kRings * detail;
    const int segments = kSegments * detail;
    const uint32_t base = static_cast<uint32_t>(positions_.size());
    // The normal of a stretched sphere leans by the inverse of each axis's stretch.
    const auto lean = [](Vec3 axis) { return axis * (1.0f / std::max(dot(axis, axis), 1e-8f)); };
    const Vec3 leanX = lean(axisX), leanY = lean(axisY), leanZ = lean(axisZ);
    for (int ring = 0; ring <= rings; ++ring) {
        const float theta = kPi * ring / rings;
        for (int segment = 0; segment <= segments; ++segment) {
            const float phi = 2.0f * kPi * segment / segments;
            const Vec3 unit{std::sin(theta) * std::sin(phi), std::cos(theta), std::sin(theta) * std::cos(phi)};
            positions_.push_back(center + axisX * unit.x + axisY * unit.y + axisZ * unit.z);
            normals_.push_back(normalize(leanX * unit.x + leanY * unit.y + leanZ * unit.z));
            local_.push_back(unit);
            part_.push_back(part);
        }
    }
    for (int ring = 0; ring < rings; ++ring) {
        for (int segment = 0; segment < segments; ++segment) {
            const uint32_t a = base + ring * (segments + 1) + segment;
            const uint32_t b = a + segments + 1;
            indices_.insert(indices_.end(), {a, b, a + 1, a + 1, b, b + 1});
        }
    }
}

void ModelScene::addSegment(Vec3 from, Vec3 to, float radius) {
    const Vec3 along = to - from;
    const float span = length(along);
    const Vec3 middle = (from + to) * 0.5f;
    if (span < 1e-4f) {
        addEllipsoid(middle, {radius, 0, 0}, {0, radius, 0}, {0, 0, radius});
        return;
    }
    const Vec3 direction = along * (1.0f / span);
    const Vec3 helper = std::fabs(direction.z) < 0.9f ? Vec3{0, 0, 1} : Vec3{1, 0, 0};
    const Vec3 side = normalize(cross(helper, direction));
    const Vec3 front = normalize(cross(direction, side));
    // A little longer than the bone, so neighbors overlap at the joints.
    addEllipsoid(middle, side * radius, direction * (span * 0.5f + radius * 0.35f), front * radius);
}

void ModelScene::addBox(Vec3 half) {
    const int part = newPart((half.x + half.y + half.z) / 3.0f, false, 0.0f, true);
    const Vec3 faces[6][3] = {
        {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},  {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}}, {{0, 1, 0}, {0, 0, 1}, {1, 0, 0}},
        {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}}, {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}},  {{0, 0, -1}, {0, 1, 0}, {1, 0, 0}},
    };
    for (const auto& face : faces) {
        const uint32_t base = static_cast<uint32_t>(positions_.size());
        const Vec3 normal = face[0], u = face[1], v = face[2];
        for (const auto& [a, b] : {std::pair{-1.0f, -1.0f}, {1.0f, -1.0f}, {1.0f, 1.0f}, {-1.0f, 1.0f}}) {
            const Vec3 unit = normal + u * a + v * b;
            positions_.push_back({unit.x * half.x, unit.y * half.y, unit.z * half.z});
            normals_.push_back(normal);
            local_.push_back(unit);
            part_.push_back(part);
        }
        indices_.insert(indices_.end(), {base, base + 1, base + 2, base, base + 2, base + 3});
    }
}

void ModelScene::addCylinder(float radius, float halfHeight) {
    const int part = newPart(radius, false, 0.0f, true);
    constexpr int kSides = 40;
    const auto ring = [&](float y, bool side, Vec3 capNormal) {
        const uint32_t base = static_cast<uint32_t>(positions_.size());
        for (int i = 0; i <= kSides; ++i) {
            const float angle = 2.0f * kPi * i / kSides;
            const Vec3 out{std::sin(angle), 0, std::cos(angle)};
            positions_.push_back({out.x * radius, y, out.z * radius});
            normals_.push_back(side ? out : capNormal);
            local_.push_back({out.x, y / halfHeight, out.z});
            part_.push_back(part);
        }
        return base;
    };
    const uint32_t top = ring(halfHeight, true, {});
    const uint32_t bottom = ring(-halfHeight, true, {});
    for (uint32_t i = 0; i < kSides; ++i) {
        indices_.insert(indices_.end(), {top + i, bottom + i, top + i + 1, top + i + 1, bottom + i, bottom + i + 1});
    }
    for (const float sign : {1.0f, -1.0f}) {
        const Vec3 normal{0, sign, 0};
        const uint32_t edge = ring(sign * halfHeight, false, normal);
        const uint32_t center = static_cast<uint32_t>(positions_.size());
        positions_.push_back({0, sign * halfHeight, 0});
        normals_.push_back(normal);
        local_.push_back({0, sign, 0});
        part_.push_back(part);
        for (uint32_t i = 0; i < kSides; ++i) indices_.insert(indices_.end(), {center, edge + i, edge + i + 1});
    }
}

void ModelScene::rebuild() {
    positions_.clear();
    normals_.clear();
    local_.clear();
    part_.clear();
    partRadius_.clear();
    partGuides_.clear();
    partLevel_.clear();
    partCreases_.clear();
    partGroup_.clear();
    joinGroup_ = -1;
    indices_.clear();
    const auto joint = [this](int index) { return Vec3{joints_[index * 3], joints_[index * 3 + 1], joints_[index * 3 + 2]}; };
    switch (shape_) {
        case ModelShape::kFigure: {
            const Vec3 top = joint(0), chin = joint(1), neck = joint(2), pelvis = joint(9);
            const Vec3 up = normalize(top - chin);
            const Vec3 spine = normalize(neck - pelvis);
            const Vec3 across = normalize(joint(3) - joint(6));
            const Vec3 facing = normalize(cross(across, spine));
            const float head = length(top - chin);
            const Vec3 headSide = normalize(cross(up, facing));
            // The head as an egg with a center line and an eye line, and the neck under it.
            addEllipsoid((top + chin) * 0.5f, headSide * (head * 0.38f), up * (head * 0.52f),
                         normalize(cross(headSide, up)) * (head * 0.44f), true, -0.05f);
            addSegment(chin - up * (head * 0.05f) - facing * (head * 0.12f), neck, head * 0.16f);
            // The rib cage, the waist, and the pelvis.
            const Vec3 chest = neck + (pelvis - neck) * 0.33f;
            addEllipsoid(chest, across * (head * 0.8f), spine * (head * 0.85f), facing * (head * 0.47f));
            addSegment(chest, pelvis + spine * (head * 0.2f), head * 0.36f);
            addEllipsoid(pelvis, across * (head * 0.68f), spine * (head * 0.45f), facing * (head * 0.42f));
            for (const int side : {0, 1}) {
                const int shoulder = side == 0 ? 3 : 6;
                const int hip = side == 0 ? 10 : 13;
                const Vec3 elbow = joint(shoulder + 1), wrist = joint(shoulder + 2);
                const Vec3 knee = joint(hip + 1), ankle = joint(hip + 2);
                addEllipsoid(joint(shoulder), {head * 0.23f, 0, 0}, {0, head * 0.23f, 0}, {0, 0, head * 0.23f});
                addSegment(joint(shoulder), elbow, head * 0.18f);
                addEllipsoid(elbow, {head * 0.15f, 0, 0}, {0, head * 0.15f, 0}, {0, 0, head * 0.15f});
                addSegment(elbow, wrist, head * 0.14f);
                const Vec3 hand = normalize(wrist - elbow);
                addSegment(wrist + hand * (head * 0.08f), wrist + hand * (head * 0.62f), head * 0.11f);
                addEllipsoid(joint(hip), {head * 0.25f, 0, 0}, {0, head * 0.25f, 0}, {0, 0, head * 0.25f});
                addSegment(joint(hip), knee, head * 0.26f);
                addEllipsoid(knee, {head * 0.19f, 0, 0}, {0, head * 0.19f, 0}, {0, 0, head * 0.19f});
                addSegment(knee, ankle, head * 0.19f);
                addSegment(ankle - spine * (head * 0.1f) - facing * (head * 0.08f),
                           ankle - spine * (head * 0.18f) + facing * (head * 0.55f), head * 0.12f);
            }
            break;
        }
        case ModelShape::kHead:
            // A Loomis head: the cranium as a ball, the face as an egg in front with its center
            // line and eye line, halfway between the crown and the chin, then the ears and neck.
            joinGroup_ = 0;
            addEllipsoid({0, 0, 0}, {0.47f, 0, 0}, {0, 0.5f, 0}, {0, 0, 0.5f}, true, 9.0f, 2);
            addEllipsoid({0, -0.35f, 0.12f}, {0.36f, 0, 0}, {0, 0.55f, 0}, {0, 0, 0.38f}, true, 0.27f, 2);
            joinGroup_ = -1;
            addEllipsoid({0.46f, -0.28f, -0.04f}, {0.05f, 0, 0}, {0, 0.15f, 0}, {0, 0, 0.09f});
            addEllipsoid({-0.46f, -0.28f, -0.04f}, {0.05f, 0, 0}, {0, 0.15f, 0}, {0, 0, 0.09f});
            addSegment({0, -0.6f, -0.2f}, {0, -1.05f, -0.22f}, 0.19f);
            break;
        case ModelShape::kBox:
            addBox({0.5f, 0.5f, 0.5f});
            break;
        case ModelShape::kSphere:
            addEllipsoid({0, 0, 0}, {0.5f, 0, 0}, {0, 0.5f, 0}, {0, 0, 0.5f}, true, 0.0f, 2);
            break;
        case ModelShape::kCylinder:
            addCylinder(0.35f, 0.5f);
            break;
        case ModelShape::kImported: {
            const int part = newPart(1.0f, false, 0.0f, true);
            // Each corner is smoothed only with the faces around it that meet at a gentle angle,
            // so a low-poly model keeps its hard edges and a dense one stays round.
            const size_t triangles = importedIndices_.size() / 3;
            std::vector<Vec3> faces(triangles);
            std::vector<std::vector<uint32_t>> around(importedPositions_.size());
            for (size_t t = 0; t < triangles; ++t) {
                const Vec3 a = importedPositions_[importedIndices_[t * 3]];
                const Vec3 b = importedPositions_[importedIndices_[t * 3 + 1]];
                const Vec3 c = importedPositions_[importedIndices_[t * 3 + 2]];
                faces[t] = normalize(cross(b - a, c - a));
                for (int k = 0; k < 3; ++k) around[importedIndices_[t * 3 + k]].push_back(static_cast<uint32_t>(t));
            }
            constexpr float kSmoothCosine = 0.75f;
            for (size_t t = 0; t < triangles; ++t) {
                for (int k = 0; k < 3; ++k) {
                    const uint32_t vertex = importedIndices_[t * 3 + k];
                    Vec3 sum;
                    for (const uint32_t other : around[vertex]) {
                        if (dot(faces[other], faces[t]) > kSmoothCosine) sum = sum + faces[other];
                    }
                    indices_.push_back(static_cast<uint32_t>(positions_.size()));
                    positions_.push_back(importedPositions_[vertex]);
                    normals_.push_back(normalize(sum));
                    local_.push_back({});
                    part_.push_back(part);
                }
            }
            break;
        }
    }
    fit();
}

void ModelScene::fit() {
    if (positions_.empty()) return;
    Vec3 low = positions_[0], high = positions_[0];
    for (const Vec3& point : positions_) {
        low = {std::min(low.x, point.x), std::min(low.y, point.y), std::min(low.z, point.z)};
        high = {std::max(high.x, point.x), std::max(high.y, point.y), std::max(high.z, point.z)};
    }
    const Vec3 middle = (low + high) * 0.5f;
    const Vec3 extent = high - low;
    // The figure keeps one scale as it's posed; anything else fills its size.
    const float scale = shape_ == ModelShape::kFigure ? 1.0f / kFigureHeight
                                                       : 1.0f / std::max({extent.x, extent.y, extent.z, 1e-6f});
    for (Vec3& point : positions_) point = (point - middle) * scale;
    for (float& radius : partRadius_) radius *= scale;
}

void ModelScene::project(const ModelView& view, float scale, float offsetX, float offsetY,
                         std::vector<Projected>& out) const {
    const float cosYaw = std::cos(view.yaw), sinYaw = std::sin(view.yaw);
    const float cosPitch = std::cos(view.pitch), sinPitch = std::sin(view.pitch);
    const float lens = std::clamp(view.lens, 0.0f, 1.0f);
    const float distance = 1.4f + (1.0f - lens) * (1.0f - lens) * 30.0f;
    const auto turn = [&](Vec3 p) {
        const float x = p.x * cosYaw + p.z * sinYaw;
        const float z = -p.x * sinYaw + p.z * cosYaw;
        return Vec3{x, p.y * cosPitch - z * sinPitch, p.y * sinPitch + z * cosPitch};
    };
    out.resize(positions_.size());
    for (size_t i = 0; i < positions_.size(); ++i) {
        const Vec3 p = turn(positions_[i]);
        const float near = distance / std::max(distance - p.z, 0.1f);
        out[i] = {(view.centerX + view.size * p.x * near) * scale - offsetX,
                  (view.centerY - view.size * p.y * near) * scale - offsetY, p.z, turn(normals_[i]), local_[i]};
    }
}

static float lineWidth(const ModelView& view) { return std::max(1.3f, view.size * 0.0032f); }

void ModelScene::bounds(const ModelView& view, int width, int height, int* box) const {
    std::vector<Projected> points;
    project(view, 1.0f, 0.0f, 0.0f, points);
    float left = 1e9f, top = 1e9f, right = -1e9f, bottom = -1e9f;
    for (const Projected& point : points) {
        left = std::min(left, point.x);
        top = std::min(top, point.y);
        right = std::max(right, point.x);
        bottom = std::max(bottom, point.y);
    }
    const float pad = lineWidth(view) + 2.0f;
    box[0] = std::clamp(static_cast<int>(std::floor(left - pad)), 0, width);
    box[1] = std::clamp(static_cast<int>(std::floor(top - pad)), 0, height);
    box[2] = std::clamp(static_cast<int>(std::ceil(right + pad)), 0, width);
    box[3] = std::clamp(static_cast<int>(std::ceil(bottom + pad)), 0, height);
    if (points.empty()) box[0] = box[1] = box[2] = box[3] = 0;
}

void ModelScene::render(const ModelView& view, int width, int height, uint8_t* rgba) const {
    int box[4];
    bounds(view, width, height, box);
    const int boxWidth = box[2] - box[0];
    const int boxHeight = box[3] - box[1];
    if (boxWidth <= 0 || boxHeight <= 0) return;
    // Drawn at twice the size and averaged down for smooth edges, unless that's very large.
    const int scale = static_cast<int64_t>(boxWidth) * boxHeight <= 6'000'000 ? 2 : 1;
    const int rows = std::max(8, kStripPixels / (boxWidth * scale * scale));
    for (int top = box[1]; top < box[3]; top += rows) {
        renderStrip(view, scale, rgba, width, box[0], top, box[2], std::min(top + rows, box[3]));
    }
}

void ModelScene::renderStrip(const ModelView& view, int scale, uint8_t* rgba, int width, int left, int top,
                             int right, int bottom) const {
    const float line = lineWidth(view) * scale;
    const int reach = std::max(1, static_cast<int>(std::lround(line * 0.5f)));
    const int margin = reach + 1;
    const int innerWidth = (right - left) * scale + margin * 2;
    const int innerHeight = (bottom - top) * scale + margin * 2;
    const size_t count = static_cast<size_t>(innerWidth) * innerHeight;
    std::vector<float> depth(count, -1e9f);
    std::vector<float> shade(count, 0.0f);
    std::vector<int16_t> part(count, -1);
    std::vector<Vec3> normal(count);
    std::vector<uint8_t> guide(count, 0);

    std::vector<Projected> points;
    project(view, static_cast<float>(scale), static_cast<float>(left * scale - margin),
            static_cast<float>(top * scale - margin), points);
    const Vec3 light = normalize({std::cos(view.lightElevation) * std::cos(view.lightAzimuth),
                                  std::cos(view.lightElevation) * std::sin(view.lightAzimuth),
                                  std::sin(view.lightElevation)});
    const bool imported = shape_ == ModelShape::kImported;

    for (size_t i = 0; i + 2 < indices_.size(); i += 3) {
        const Projected& a = points[indices_[i]];
        const Projected& b = points[indices_[i + 1]];
        const Projected& c = points[indices_[i + 2]];
        const float area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        if (std::fabs(area) < 1e-9f) continue;
        const int x0 = std::max(0, static_cast<int>(std::floor(std::min({a.x, b.x, c.x}))));
        const int x1 = std::min(innerWidth - 1, static_cast<int>(std::ceil(std::max({a.x, b.x, c.x}))));
        const int y0 = std::max(0, static_cast<int>(std::floor(std::min({a.y, b.y, c.y}))));
        const int y1 = std::min(innerHeight - 1, static_cast<int>(std::ceil(std::max({a.y, b.y, c.y}))));
        if (x0 > x1 || y0 > y1) continue;
        const int owner = part_[indices_[i]];
        const bool guides = partGuides_[owner] != 0;
        // Construction lines keep about the same width on screen however big the part is.
        const float band = line * 0.6f / std::max(view.size * scale * partRadius_[owner], 1.0f);
        const float level = partLevel_[owner];
        for (int y = y0; y <= y1; ++y) {
            const float py = y + 0.5f;
            for (int x = x0; x <= x1; ++x) {
                const float px = x + 0.5f;
                const float w0 = ((b.x - px) * (c.y - py) - (b.y - py) * (c.x - px)) / area;
                const float w1 = ((c.x - px) * (a.y - py) - (c.y - py) * (a.x - px)) / area;
                const float w2 = 1.0f - w0 - w1;
                if (w0 < 0.0f || w1 < 0.0f || w2 < 0.0f) continue;
                const float z = a.depth * w0 + b.depth * w1 + c.depth * w2;
                const size_t index = static_cast<size_t>(y) * innerWidth + x;
                if (z <= depth[index]) continue;
                depth[index] = z;
                Vec3 n = normalize(a.normal * w0 + b.normal * w1 + c.normal * w2);
                // Imported faces may wind either way, so a normal facing away is turned around.
                if (imported && n.z < 0.0f) n = n * -1.0f;
                normal[index] = n;
                const float lit = std::max(0.0f, dot(n, light));
                shade[index] = 0.2f + 0.8f * lit;
                part[index] = static_cast<int16_t>(owner);
                guide[index] = 0;
                if (guides) {
                    const Vec3 local = a.local * w0 + b.local * w1 + c.local * w2;
                    if (local.z > -0.2f && (std::fabs(local.x) < band || std::fabs(local.y - level) < band)) {
                        guide[index] = 1;
                    }
                }
            }
        }
    }

    // Lines where the model meets the background, another part, a step in depth, or a crease.
    constexpr int kAround[8][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
    const auto edge = [&](int x, int y) {
        const size_t index = static_cast<size_t>(y) * innerWidth + x;
        const bool covered = part[index] >= 0;
        for (const auto& step : kAround) {
            const int nx = x + step[0] * reach, ny = y + step[1] * reach;
            if (nx < 0 || ny < 0 || nx >= innerWidth || ny >= innerHeight) continue;
            const size_t other = static_cast<size_t>(ny) * innerWidth + nx;
            const bool otherCovered = part[other] >= 0;
            if (covered != otherCovered) return true;
            if (!covered) continue;
            if (partGroup_[part[other]] != partGroup_[part[index]] && std::fabs(depth[other] - depth[index]) > 0.004f) return true;
            if (std::fabs(depth[other] - depth[index]) > 0.03f) return true;
            // Hard edges only; round parts turn too quickly near their outline to tell.
            if (partCreases_[part[index]] && dot(normal[other], normal[index]) < 0.7f) return true;
        }
        return false;
    };

    const float area = static_cast<float>(scale * scale);
    for (int y = top; y < bottom; ++y) {
        for (int x = left; x < right; ++x) {
            float total[4] = {0, 0, 0, 0};
            for (int sy = 0; sy < scale; ++sy) {
                for (int sx = 0; sx < scale; ++sx) {
                    const int ix = (x - left) * scale + sx + margin;
                    const int iy = (y - top) * scale + sy + margin;
                    const size_t index = static_cast<size_t>(iy) * innerWidth + ix;
                    float color[3] = {0, 0, 0};  // Premultiplied.
                    float alpha = 0.0f;
                    if (part[index] >= 0 && view.style == 0) {
                        const float s = shade[index];
                        for (int k = 0; k < 3; ++k) color[k] = kShadow[k] + (kLit[k] - kShadow[k]) * s;
                        alpha = 1.0f;
                    }
                    if (part[index] >= 0 && guide[index]) {
                        for (int k = 0; k < 3; ++k) color[k] = kGuide[k];
                        alpha = 1.0f;
                    } else if (edge(ix, iy)) {
                        const float ink = view.style == 0 ? 0.85f : 1.0f;
                        for (int k = 0; k < 3; ++k) color[k] = color[k] * (1.0f - ink) + kLine[k] * ink;
                        alpha = alpha * (1.0f - ink) + ink;
                    }
                    for (int k = 0; k < 3; ++k) total[k] += color[k];
                    total[3] += alpha;
                }
            }
            uint8_t* out = rgba + (static_cast<size_t>(y) * width + x) * 4;
            for (int k = 0; k < 4; ++k) {
                out[k] = static_cast<uint8_t>(std::clamp(total[k] / area * 255.0f + 0.5f, 0.0f, 255.0f));
            }
        }
    }
}

}  // namespace cappuccino
