#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

#include "stroke.h"

using namespace cappuccino;

static std::vector<Stamp> line(float diameter, float length, float pressure) {
    StrokeBuilder builder(diameter);
    std::vector<Stamp> stamps;
    for (float x = 0.0f; x <= length; x += 7.0f) {
        builder.add({x, 0.0f, pressure, 0.0f, 0.0f}, stamps);
    }
    builder.finish(stamps);
    return stamps;
}

static void singleTapLeavesOneDot() {
    StrokeBuilder builder(20.0f);
    std::vector<Stamp> stamps;
    builder.add({5.0f, 6.0f, 1.0f, 0.0f, 0.0f}, stamps);
    builder.finish(stamps);
    assert(stamps.size() == 1);
    assert(stamps[0].x == 5.0f && stamps[0].y == 6.0f);
}

static void stampsAreEvenlySpaced() {
    const auto stamps = line(20.0f, 700.0f, 1.0f);
    assert(stamps.size() > 100);
    for (size_t i = 2; i < stamps.size(); ++i) {
        const float gap = std::hypot(stamps[i].x - stamps[i - 1].x, stamps[i].y - stamps[i - 1].y);
        assert(std::fabs(gap - 1.6f) < 0.05f);
    }
}

static void strokeReachesTheLastPoint() {
    const auto stamps = line(20.0f, 700.0f, 1.0f);
    assert(std::fabs(stamps.back().x - 700.0f) < 2.0f);
}

static void pressureGrowsRadius() {
    assert(pressureToScale(0.0f) < pressureToScale(0.5f));
    assert(pressureToScale(0.5f) < pressureToScale(1.0f));
    assert(pressureToScale(2.0f) == 1.0f);
}

static void tiltWidensTheStamp() {
    StrokeBuilder builder(20.0f);
    std::vector<Stamp> stamps;
    builder.add({0.0f, 0.0f, 1.0f, 1.05f, 0.4f}, stamps);
    assert(stamps[0].radiusX > stamps[0].radiusY * 2.5f);
    assert(stamps[0].angle == 0.4f);
}

static void predictedCopyLeavesTheOriginalUntouched() {
    StrokeBuilder builder(20.0f);
    std::vector<Stamp> committed;
    builder.add({0.0f, 0.0f, 1.0f, 0.0f, 0.0f}, committed);
    builder.add({50.0f, 0.0f, 1.0f, 0.0f, 0.0f}, committed);

    StrokeBuilder prediction = builder;
    std::vector<Stamp> predicted;
    prediction.add({100.0f, 0.0f, 1.0f, 0.0f, 0.0f}, predicted);
    prediction.finish(predicted);
    assert(!predicted.empty());

    std::vector<Stamp> realStamps;
    builder.add({100.0f, 50.0f, 1.0f, 0.0f, 0.0f}, realStamps);
    builder.finish(realStamps);
    assert(std::fabs(realStamps.back().y - 50.0f) < 2.0f);
}

static void lightPressureFadesFlow() {
    BrushShape shape;
    shape.flow = 0.5f;
    shape.pressureFlow = 1.0f;
    StrokeBuilder builder(20.0f, shape);
    std::vector<Stamp> stamps;
    builder.add({0.0f, 0.0f, 0.5f, 0.0f, 0.0f}, stamps);
    assert(std::fabs(stamps[0].alpha - 0.25f) < 0.001f);
}

int main() {
    singleTapLeavesOneDot();
    stampsAreEvenlySpaced();
    strokeReachesTheLastPoint();
    pressureGrowsRadius();
    tiltWidensTheStamp();
    predictedCopyLeavesTheOriginalUntouched();
    lightPressureFadesFlow();
    std::puts("stroke tests passed");
    return 0;
}
