#pragma once

#include <cstdint>
#include <vector>

namespace cappuccino {

struct FillBounds {
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
};

// Marks the connected area around (x, y) of an RGBA image in mask, one byte per pixel.
bool floodMask(const uint8_t* pixels, int width, int height, int x, int y, int tolerance,
               std::vector<uint8_t>& mask, FillBounds* bounds);

// Marks the inside of a closed polygon, given as x and y pairs, using the even-odd rule.
void rasterizePolygon(const float* points, int count, int width, int height,
                      std::vector<uint8_t>& mask);

// Fills the connected area around (x, y) in an RGBA image, growing one pixel past its edge so
// anti-aliased lines don't leave a pale halo. Returns false when nothing changed.
bool floodFill(uint8_t* pixels, int width, int height, int x, int y, const uint8_t color[4],
               int tolerance, FillBounds* bounds);

}  // namespace cappuccino
