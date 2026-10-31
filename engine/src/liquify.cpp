#include "liquify.h"

#include <algorithm>
#include <cmath>

namespace cappuccino {
namespace {

constexpr float kGrowRate = 0.08f;
constexpr float kRestoreRate = 0.25f;

}  // namespace

LiquifyMesh::LiquifyMesh(int width, int height, int cell)
    : width_(width),
      height_(height),
      columns_((width + cell - 1) / cell + 1),
      rows_((height + cell - 1) / cell + 1) {
    vertices_.reserve(static_cast<size_t>(columns_) * rows_ * 4);
    for (int row = 0; row < rows_; ++row) {
        const float y = static_cast<float>(row) / (rows_ - 1);
        for (int column = 0; column < columns_; ++column) {
            const float x = static_cast<float>(column) / (columns_ - 1);
            vertices_.insert(vertices_.end(), {x * width_, y * height_, x, y});
        }
    }
    indices_.reserve(static_cast<size_t>(columns_ - 1) * (rows_ - 1) * 6);
    for (int row = 0; row + 1 < rows_; ++row) {
        for (int column = 0; column + 1 < columns_; ++column) {
            const uint32_t corner = row * columns_ + column;
            indices_.insert(indices_.end(), {corner, corner + 1, corner + columns_, corner + 1,
                                             corner + columns_ + 1, corner + columns_});
        }
    }
}

void LiquifyMesh::dab(float fromX, float fromY, float toX, float toY, float radius, float strength,
                      LiquifyMode mode, const uint8_t* mask) {
    if (radius <= 0.0f) return;
    const float moveX = toX - fromX;
    const float moveY = toY - fromY;
    for (size_t offset = 0; offset < vertices_.size(); offset += 4) {
        float& x = vertices_[offset];
        float& y = vertices_[offset + 1];
        const float awayX = x - fromX;
        const float awayY = y - fromY;
        const float reach = (awayX * awayX + awayY * awayY) / (radius * radius);
        if (reach >= 1.0f) continue;
        const float fade = (1.0f - reach) * (1.0f - reach);
        const float restX = vertices_[offset + 2] * width_;
        const float restY = vertices_[offset + 3] * height_;
        float amount = fade * strength;
        if (mask != nullptr) {
            const int maskX = std::clamp(static_cast<int>(restX), 0, width_ - 1);
            const int maskY = std::clamp(static_cast<int>(restY), 0, height_ - 1);
            amount *= mask[static_cast<size_t>(maskY) * width_ + maskX] / 255.0f;
        }
        switch (mode) {
            case LiquifyMode::kPush:
                x += moveX * amount;
                y += moveY * amount;
                break;
            case LiquifyMode::kGrow:
                x += awayX * kGrowRate * amount;
                y += awayY * kGrowRate * amount;
                break;
            case LiquifyMode::kShrink:
                x -= awayX * kGrowRate * amount;
                y -= awayY * kGrowRate * amount;
                break;
            case LiquifyMode::kRestore:
                x += (restX - x) * kRestoreRate * amount;
                y += (restY - y) * kRestoreRate * amount;
                break;
        }
    }
}

}  // namespace cappuccino
