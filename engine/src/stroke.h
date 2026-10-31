#pragma once

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
};

struct BrushShape {
    float minScale = 0.12f;      // Stamp size at zero pressure, as a share of the diameter.
    float spacingRatio = 0.08f;  // Distance between stamps, as a share of the stamp size.
    float flow = 1.0f;           // Stamp alpha at full pressure.
    float pressureFlow = 0.0f;   // How much light pressure fades the stamp, from 0 to 1.
};

float pressureToScale(float pressure, float minScale = BrushShape{}.minScale);

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

    float diameter_;
    BrushShape shape_;
    int count_ = 0;
    Sample previous_{};
    Sample lastMid_{};
    Sample lastPoint_{};
    float untilNextStamp_ = 0.0f;
};

}  // namespace cappuccino
