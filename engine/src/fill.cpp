#include "fill.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <utility>
#include <vector>

namespace cappuccino {
namespace {

int distance(const uint8_t* a, const uint8_t* b) {
    return std::max({std::abs(a[0] - b[0]), std::abs(a[1] - b[1]), std::abs(a[2] - b[2]),
                     std::abs(a[3] - b[3])});
}

}  // namespace

bool floodFill(uint8_t* pixels, int width, int height, int x, int y, const uint8_t color[4],
               int tolerance, FillBounds* bounds) {
    if (x < 0 || y < 0 || x >= width || y >= height) return false;
    uint8_t seed[4];
    std::memcpy(seed, pixels + (static_cast<size_t>(y) * width + x) * 4, 4);
    if (distance(seed, color) == 0) return false;

    std::vector<uint8_t> mask(static_cast<size_t>(width) * height, 0);
    const auto matches = [&](int px, int py) {
        const size_t index = static_cast<size_t>(py) * width + px;
        return mask[index] == 0 && distance(pixels + index * 4, seed) <= tolerance;
    };

    FillBounds box{x, y, x + 1, y + 1};
    std::vector<std::pair<int, int>> pending{{x, y}};
    while (!pending.empty()) {
        auto [startX, row] = pending.back();
        pending.pop_back();
        if (!matches(startX, row)) continue;
        int left = startX;
        int right = startX;
        while (left > 0 && matches(left - 1, row)) --left;
        while (right + 1 < width && matches(right + 1, row)) ++right;
        std::memset(mask.data() + static_cast<size_t>(row) * width + left, 1, right - left + 1);
        box.left = std::min(box.left, left);
        box.right = std::max(box.right, right + 1);
        box.top = std::min(box.top, row);
        box.bottom = std::max(box.bottom, row + 1);
        for (const int next : {row - 1, row + 1}) {
            if (next < 0 || next >= height) continue;
            bool inSpan = false;
            for (int column = left; column <= right; ++column) {
                const bool match = matches(column, next);
                if (match && !inSpan) pending.emplace_back(column, next);
                inSpan = match;
            }
        }
    }

    box.left = std::max(0, box.left - 1);
    box.top = std::max(0, box.top - 1);
    box.right = std::min(width, box.right + 1);
    box.bottom = std::min(height, box.bottom + 1);
    for (int row = box.top; row < box.bottom; ++row) {
        for (int column = box.left; column < box.right; ++column) {
            const size_t index = static_cast<size_t>(row) * width + column;
            bool covered = mask[index] != 0;
            if (!covered && column > 0) covered = mask[index - 1] == 1;
            if (!covered && column + 1 < width) covered = mask[index + 1] == 1;
            if (!covered && row > 0) covered = mask[index - width] == 1;
            if (!covered && row + 1 < height) covered = mask[index + width] == 1;
            if (covered) std::memcpy(pixels + index * 4, color, 4);
        }
    }
    if (bounds) *bounds = box;
    return true;
}

}  // namespace cappuccino
