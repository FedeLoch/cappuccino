#include <cassert>
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

int main() {
    auto image = boxedImage();
    FillBounds bounds;
    assert(floodFill(image.data(), kSize, kSize, 10, 10, kRed, 16, &bounds));
    assert(at(image, 10, 10)[0] == 255);
    assert(at(image, 2, 2)[3] == 0);
    assert(bounds.left == 5 && bounds.right == 16);
    assert(!floodFill(image.data(), kSize, kSize, 10, 10, kRed, 16, nullptr));
    assert(!floodFill(image.data(), kSize, kSize, -1, 0, kRed, 16, nullptr));
    std::puts("fill tests passed");
    return 0;
}
