#pragma once

#include <cstdint>
#include <vector>

namespace cappuccino {

enum class LiquifyMode { kPush = 0, kGrow = 1, kShrink = 2, kRestore = 3 };

// A grid over the canvas whose points follow the brush. Each vertex keeps where it now sits and
// the place in the image it shows, so dragging the grid drags the picture with it.
class LiquifyMesh {
public:
    LiquifyMesh(int width, int height, int cell);

    // Moves the grid along a brush path. An optional mask, one byte per canvas pixel, holds
    // back points in its dark areas so a selection protects the rest of the image.
    void dab(float fromX, float fromY, float toX, float toY, float radius, float strength,
             LiquifyMode mode, const uint8_t* mask);

    const std::vector<float>& vertices() const { return vertices_; }  // x, y, u, v per point.
    const std::vector<uint32_t>& indices() const { return indices_; }
    float x(int column, int row) const { return vertices_[index(column, row)]; }
    float y(int column, int row) const { return vertices_[index(column, row) + 1]; }
    int columns() const { return columns_; }
    int rows() const { return rows_; }

private:
    size_t index(int column, int row) const {
        return (static_cast<size_t>(row) * columns_ + column) * 4;
    }

    int width_;
    int height_;
    int columns_;
    int rows_;
    std::vector<float> vertices_;
    std::vector<uint32_t> indices_;
};

}  // namespace cappuccino
