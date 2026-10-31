#include "stroke.h"

#include <algorithm>
#include <cmath>

namespace cappuccino {
namespace {

constexpr float kMinSpacing = 0.5f;
constexpr float kTiltStart = 0.5f;
constexpr float kMaxTiltWiden = 3.0f;
constexpr int kCurveSteps = 16;

Sample lerp(const Sample& a, const Sample& b, float t) {
    return {
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.pressure + (b.pressure - a.pressure) * t,
        a.tilt + (b.tilt - a.tilt) * t,
        // Azimuth wraps around, so it follows the newer sample instead of blending.
        b.azimuth,
    };
}

Sample quadratic(const Sample& a, const Sample& b, const Sample& c, float t) {
    return lerp(lerp(a, b, t), lerp(b, c, t), t);
}

}  // namespace

float pressureToScale(float pressure, float minScale, float gamma) {
    const float clamped = std::clamp(pressure, 0.0f, 1.0f);
    return minScale + (1.0f - minScale) * std::pow(clamped, gamma);
}

StrokeBuilder::StrokeBuilder(float diameter, const BrushShape& shape)
    : diameter_(diameter), shape_(shape) {}

float StrokeBuilder::spacingFor(float pressure) const {
    return std::max(kMinSpacing,
                    diameter_ * pressureToScale(pressure, shape_.minScale, shape_.pressureGamma) *
                        shape_.spacingRatio);
}

void StrokeBuilder::place(const Sample& sample, std::vector<Stamp>& out) {
    const float radius =
        diameter_ * pressureToScale(sample.pressure, shape_.minScale, shape_.pressureGamma) * 0.5f;
    const float pressure = std::clamp(sample.pressure, 0.0f, 1.0f);
    const float alpha = shape_.flow * (1.0f - shape_.pressureFlow * (1.0f - pressure));
    const float lean = std::clamp((sample.tilt - kTiltStart) / (1.05f - kTiltStart), 0.0f, 1.0f);
    out.push_back({
        sample.x,
        sample.y,
        radius * (1.0f + (kMaxTiltWiden - 1.0f) * lean),
        radius,
        sample.azimuth,
        alpha,
    });
}

void StrokeBuilder::walkQuadratic(const Sample& from, const Sample& control, const Sample& to,
                                  std::vector<Stamp>& out) {
    Sample segmentStart = from;
    for (int step = 1; step <= kCurveSteps; ++step) {
        const Sample segmentEnd =
            quadratic(from, control, to, static_cast<float>(step) / kCurveSteps);
        const float length =
            std::hypot(segmentEnd.x - segmentStart.x, segmentEnd.y - segmentStart.y);
        float walked = 0.0f;
        while (length > 0.0f && length - walked >= untilNextStamp_) {
            walked += untilNextStamp_;
            const Sample sample = lerp(segmentStart, segmentEnd, walked / length);
            place(sample, out);
            untilNextStamp_ = spacingFor(sample.pressure);
        }
        untilNextStamp_ -= length - walked;
        segmentStart = segmentEnd;
    }
}

void StrokeBuilder::add(const Sample& sample, std::vector<Stamp>& out) {
    if (count_ == 0) {
        place(sample, out);
        untilNextStamp_ = spacingFor(sample.pressure);
        lastMid_ = sample;
    } else {
        const Sample mid = lerp(previous_, sample, 0.5f);
        walkQuadratic(lastMid_, previous_, mid, out);
        lastMid_ = mid;
    }
    previous_ = sample;
    lastPoint_ = sample;
    ++count_;
}

void StrokeBuilder::finish(std::vector<Stamp>& out) {
    if (count_ > 1) {
        walkQuadratic(lastMid_, lerp(lastMid_, lastPoint_, 0.5f), lastPoint_, out);
    }
}

}  // namespace cappuccino
