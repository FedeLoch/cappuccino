#pragma once

#include <cstdint>

namespace cappuccino {

struct FillBounds {
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
};

// Fills the connected area around (x, y) in an RGBA image, growing one pixel past its edge so
// anti-aliased lines don't leave a pale halo. Returns false when nothing changed.
bool floodFill(uint8_t* pixels, int width, int height, int x, int y, const uint8_t color[4],
               int tolerance, FillBounds* bounds);

}  // namespace cappuccino
