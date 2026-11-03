#include <cassert>
#include <utility>
#include <cstdio>
#include <vector>

#include "fill.h"

using namespace cappuccino;

static constexpr int kSize = 20;
static constexpr uint8_t kRed[4] = {255, 0, 0, 255};

static uint8_t* at(std::vector<uint8_t>& image, int x, int y) {
    return image.data() + (y * kSize + x) * 4;
}

static std::vector<uint8_t> boxedImage() {
    std::vector<uint8_t> image(kSize * kSize * 4, 0);
    for (int i = 5; i <= 15; ++i) {
        for (const auto& [x, y] : {std::pair{i, 5}, {i, 15}, {5, i}, {15, i}}) {
            *reinterpret_cast<uint32_t*>(at(image, x, y)) = 0xFF000000;
        }
    }
    return image;
}

static void testPolygonCoverage() {
    const float square[8] = {2.5f, 2.0f, 6.0f, 2.0f, 6.0f, 6.0f, 2.5f, 6.0f};
    std::vector<uint8_t> coverage(kSize * kSize, 0);
    FillBounds box{kSize, kSize, 0, 0};
    polygonCoverage(square, 4, kSize, kSize, coverage, box);
    assert(coverage[3 * kSize + 4] == 255);
    assert(coverage[3 * kSize + 2] > 100 && coverage[3 * kSize + 2] < 160);
    assert(coverage[3 * kSize + 7] == 0);
    assert(box.left == 2 && box.top == 2 && box.right == 6 && box.bottom == 6);
}

int main() {
    testPolygonCoverage();
    auto image = boxedImage();
    FillBounds bounds;
    assert(floodFill(image.data(), kSize, kSize, 10, 10, kRed, 16, &bounds));
    assert(at(image, 10, 10)[0] == 255);
    assert(at(image, 2, 2)[3] == 0);
    assert(bounds.left == 5 && bounds.right == 16);
    assert(!floodFill(image.data(), kSize, kSize, 10, 10, kRed, 16, nullptr));
    assert(!floodFill(image.data(), kSize, kSize, -1, 0, kRed, 16, nullptr));
    const float triangle[6] = {2, 2, 18, 2, 2, 18};
    std::vector<uint8_t> mask;
    rasterizePolygon(triangle, 3, kSize, kSize, mask);
    assert(mask[5 * kSize + 5] == 255);
    assert(mask[17 * kSize + 17] == 0);
    std::vector<uint8_t> selection{255, 255, 0, 0};
    combineSelection(selection, {0, 0, 255, 0}, 1);
    assert((selection == std::vector<uint8_t>{255, 255, 255, 0}));
    combineSelection(selection, {255, 0, 0, 0}, 2);
    assert((selection == std::vector<uint8_t>{0, 255, 255, 0}));
    combineSelection(selection, {0, 0, 0, 255}, 0);
    assert((selection == std::vector<uint8_t>{0, 0, 0, 255}));
    std::vector<uint8_t> none;
    combineSelection(none, {255}, 2);
    assert(none.empty());
    std::vector<uint8_t> edge(kSize * kSize, 0);
    for (int y = 0; y < kSize; ++y) {
        for (int x = 0; x < kSize / 2; ++x) edge[y * kSize + x] = 255;
    }
    featherMask(edge, kSize, kSize, 4);
    assert(edge[10 * kSize + 1] == 255);
    assert(edge[10 * kSize + 18] == 0);
    assert(edge[10 * kSize + 9] > 100 && edge[10 * kSize + 9] < 220);
    assert(edge[10 * kSize + 8] > edge[10 * kSize + 10]);
    std::puts("fill tests passed");
    return 0;
}
