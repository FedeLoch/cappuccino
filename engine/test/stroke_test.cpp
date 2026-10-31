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

static void testSymmetry() {
    std::vector<Stamp> stamps{{10.0f, 20.0f, 4.0f, 4.0f, 0.0f, 1.0f}};
    mirrorStamps(stamps, symmetryMirrors(1, 50.0f, 50.0f));
    assert(stamps.size() == 2 && std::fabs(stamps[1].x - 90.0f) < 0.01f && stamps[1].y == 20.0f);

    std::vector<Stamp> four{{10.0f, 20.0f, 4.0f, 4.0f, 0.0f, 1.0f}};
    mirrorStamps(four, symmetryMirrors(3, 50.0f, 50.0f));
    assert(four.size() == 4 && std::fabs(four[3].x - 90.0f) < 0.01f &&
           std::fabs(four[3].y - 80.0f) < 0.01f);

    std::vector<Stamp> radial{{60.0f, 50.0f, 4.0f, 4.0f, 0.0f, 1.0f}};
    mirrorStamps(radial, symmetryMirrors(4, 50.0f, 50.0f));
    assert(radial.size() == 8);
    for (const Stamp& stamp : radial) {
        assert(std::fabs(std::hypot(stamp.x - 50.0f, stamp.y - 50.0f) - 10.0f) < 0.01f);
    }
    assert(symmetryMirrors(0, 0.0f, 0.0f).empty());
}

static void testScatter() {
    BrushShape shape;
    shape.spacingRatio = 0.5f;
    shape.scatter = 0.5f;
    shape.sizeJitter = 0.5f;
    shape.angleJitter = 1.0f;
    shape.shadeJitter = 0.3f;
    StrokeBuilder builder(40.0f, shape);
    std::vector<Stamp> stamps;
    for (int i = 0; i < 50; ++i) builder.add({i * 10.0f, 100.0f, 1.0f, 0.0f, 0.0f}, stamps);
    builder.finish(stamps);
    bool offPath = false;
    bool differentSizes = false;
    bool differentShades = false;
    for (const Stamp& stamp : stamps) {
        assert(std::fabs(stamp.y - 100.0f) <= 20.5f);
        assert(stamp.shade >= 0.69f && stamp.shade <= 1.31f);
        offPath = offPath || std::fabs(stamp.y - 100.0f) > 4.0f;
        differentSizes = differentSizes || std::fabs(stamp.radiusY - stamps[0].radiusY) > 1.0f;
        differentShades = differentShades || std::fabs(stamp.shade - stamps[0].shade) > 0.05f;
    }
    assert(offPath && differentSizes && differentShades);

    StrokeBuilder plain(40.0f);
    std::vector<Stamp> calm;
    for (int i = 0; i < 5; ++i) plain.add({i * 10.0f, 100.0f, 1.0f, 0.0f, 0.0f}, calm);
    for (const Stamp& stamp : calm) assert(stamp.y == 100.0f && stamp.shade == 1.0f);
}

int main() {
    testSymmetry();
    testScatter();
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
