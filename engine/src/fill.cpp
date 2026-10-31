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

bool floodMask(const uint8_t* pixels, int width, int height, int x, int y, int tolerance,
               std::vector<uint8_t>& mask, FillBounds* bounds) {
    if (x < 0 || y < 0 || x >= width || y >= height) return false;
    uint8_t seed[4];
    std::memcpy(seed, pixels + (static_cast<size_t>(y) * width + x) * 4, 4);
    mask.assign(static_cast<size_t>(width) * height, 0);
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
    if (bounds) *bounds = box;
    return true;
}

void rasterizePolygon(const float* points, int count, int width, int height,
                      std::vector<uint8_t>& mask) {
    mask.assign(static_cast<size_t>(width) * height, 0);
    std::vector<float> crossings;
    for (int row = 0; row < height; ++row) {
        const float y = row + 0.5f;
        crossings.clear();
        for (int i = 0; i < count; ++i) {
            const int next = (i + 1) % count;
            const float y1 = points[i * 2 + 1];
            const float y2 = points[next * 2 + 1];
            if ((y1 <= y) == (y2 <= y)) continue;
            const float x1 = points[i * 2];
            const float x2 = points[next * 2];
            crossings.push_back(x1 + (y - y1) / (y2 - y1) * (x2 - x1));
        }
        std::sort(crossings.begin(), crossings.end());
        for (size_t i = 0; i + 1 < crossings.size(); i += 2) {
            const int left = std::clamp(static_cast<int>(crossings[i] + 0.5f), 0, width);
            const int right = std::clamp(static_cast<int>(crossings[i + 1] + 0.5f), 0, width);
            if (right > left) {
                std::memset(mask.data() + static_cast<size_t>(row) * width + left, 255,
                            right - left);
            }
        }
    }
}

void polygonCoverage(const float* points, int count, int width, int height,
                     std::vector<uint8_t>& coverage, FillBounds& bounds) {
    constexpr int kSteps = 4;
    float top = static_cast<float>(height);
    float bottom = 0.0f;
    for (int i = 0; i < count; ++i) {
        top = std::min(top, points[i * 2 + 1]);
        bottom = std::max(bottom, points[i * 2 + 1]);
    }
    const int firstRow = std::clamp(static_cast<int>(top), 0, height);
    const int lastRow = std::clamp(static_cast<int>(bottom) + 1, 0, height);
    std::vector<float> row(width + 1);
    std::vector<float> crossings;
    for (int y = firstRow; y < lastRow; ++y) {
        std::fill(row.begin(), row.end(), 0.0f);
        for (int step = 0; step < kSteps; ++step) {
            const float line = y + (step + 0.5f) / kSteps;
            crossings.clear();
            for (int i = 0; i < count; ++i) {
                const int next = (i + 1) % count;
                const float y1 = points[i * 2 + 1];
                const float y2 = points[next * 2 + 1];
                if ((y1 <= line) == (y2 <= line)) continue;
                const float x1 = points[i * 2];
                crossings.push_back(x1 + (line - y1) / (y2 - y1) * (points[next * 2] - x1));
            }
            std::sort(crossings.begin(), crossings.end());
            for (size_t i = 0; i + 1 < crossings.size(); i += 2) {
                const float left = std::clamp(crossings[i], 0.0f, static_cast<float>(width));
                const float right = std::clamp(crossings[i + 1], 0.0f, static_cast<float>(width));
                if (right <= left) continue;
                const int start = static_cast<int>(left);
                const int end = static_cast<int>(right);
                if (start == end) {
                    row[start] += right - left;
                    continue;
                }
                row[start] += start + 1 - left;
                for (int x = start + 1; x < end; ++x) row[x] += 1.0f;
                row[end] += right - end;
            }
        }
        for (int x = 0; x < width; ++x) {
            if (row[x] <= 0.0f) continue;
            const int value = std::min(255, static_cast<int>(row[x] / kSteps * 255.0f + 0.5f));
            uint8_t& pixel = coverage[static_cast<size_t>(y) * width + x];
            pixel = std::max(pixel, static_cast<uint8_t>(value));
            bounds.left = std::min(bounds.left, x);
            bounds.top = std::min(bounds.top, y);
            bounds.right = std::max(bounds.right, x + 1);
            bounds.bottom = std::max(bounds.bottom, y + 1);
        }
    }
}

bool floodFill(uint8_t* pixels, int width, int height, int x, int y, const uint8_t color[4],
               int tolerance, FillBounds* bounds) {
    if (x < 0 || y < 0 || x >= width || y >= height) return false;
    if (distance(pixels + (static_cast<size_t>(y) * width + x) * 4, color) == 0) return false;
    std::vector<uint8_t> mask;
    FillBounds box;
    floodMask(pixels, width, height, x, y, tolerance, mask, &box);

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
