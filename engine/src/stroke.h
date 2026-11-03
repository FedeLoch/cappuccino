#pragma once

#include <cstdint>
#include <vector>

namespace cappuccino {

struct Sample {
    float x;
    float y;
    float pressure;  // 0 to 1
    float tilt;      // radians, 0 when the pen is upright or tilt is unavailable
    float azimuth;   // radians, direction the pen leans in canvas space
};

struct Stamp {
    float x;
    float y;
    float radiusX;
    float radiusY;
    float angle;
    float alpha;
    float shade = 1.0f;  // Below 1 darkens the brush color, above 1 lightens it.
    float hue = 0.0f;    // Turns the brush color around the color wheel, in whole turns.
};

constexpr float kMaxHueTurn = 0.08f;

struct BrushShape {
    float minScale = 0.12f;      // Stamp size at zero pressure, as a share of the diameter.
    float spacingRatio = 0.08f;  // Distance between stamps, as a share of the stamp size.
    float flow = 1.0f;           // Stamp alpha at full pressure.
    float pressureFlow = 0.0f;   // How much light pressure fades the stamp, from 0 to 1.
    float pressureGamma = 0.75f; // Curve from pen pressure to size. Lower feels softer.
    float scatter = 0.0f;        // Random offset from the path, as a share of the diameter.
    float sizeJitter = 0.0f;     // Random size change, from 0 to 1.
    float angleJitter = 0.0f;    // Random turn, where 1 covers the full circle.
    float shadeJitter = 0.0f;    // Random lightness change, from 0 to 1.
    float hueJitter = 0.0f;      // Random hue change, where 1 turns up to kMaxHueTurn either way.
};

// Where symmetry copies a mark: x' = a x + b y + c and y' = d x + e y + f.
struct Mirror {
    float a, b, c, d, e, f;
};

// Lists the copies for a mode: 0 off, 1 left-right, 2 top-bottom, 3 four-way, 4 radial.
std::vector<Mirror> symmetryMirrors(int mode, float centerX, float centerY);
void mirrorStamps(std::vector<Stamp>& stamps, const std::vector<Mirror>& mirrors);

float pressureToScale(float pressure, float minScale = BrushShape{}.minScale,
                      float gamma = BrushShape{}.pressureGamma);

// Turns stylus samples into evenly spaced brush stamps along a smoothed path.
// The object is copyable, so predicted samples can run on a throwaway copy.
class StrokeBuilder {
public:
    explicit StrokeBuilder(float diameter, const BrushShape& shape = {});

    void add(const Sample& sample, std::vector<Stamp>& out);
    void finish(std::vector<Stamp>& out);

private:
    void walkQuadratic(const Sample& from, const Sample& control, const Sample& to,
                       std::vector<Stamp>& out);
    void place(const Sample& sample, std::vector<Stamp>& out);
    float spacingFor(float pressure) const;
    float nextRandom();  // From -1 to 1.

    float diameter_;
    BrushShape shape_;
    int count_ = 0;
    Sample previous_{};
    Sample lastMid_{};
    Sample lastPoint_{};
    float untilNextStamp_ = 0.0f;
    uint32_t random_ = 2463534242u;
};

}  // namespace cappuccino
