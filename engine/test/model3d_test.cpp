#include <cassert>
#include <cstdio>
#include <vector>

#include "model3d.h"

using namespace cappuccino;

static constexpr int kSize = 200;

static std::vector<uint8_t> draw(const ModelScene& scene, ModelView view) {
    std::vector<uint8_t> image(kSize * kSize * 4, 0);
    scene.render(view, kSize, kSize, image.data());
    return image;
}

static ModelView centered() {
    ModelView view;
    view.centerX = kSize / 2.0f;
    view.centerY = kSize / 2.0f;
    view.size = 160.0f;
    return view;
}

static const uint8_t* at(const std::vector<uint8_t>& image, int x, int y) { return image.data() + (y * kSize + x) * 4; }

static int covered(const std::vector<uint8_t>& image) {
    int count = 0;
    for (int i = 3; i < kSize * kSize * 4; i += 4) count += image[i] > 128;
    return count;
}

static void testEveryShapeDraws() {
    ModelScene scene;
    for (const ModelShape shape : {ModelShape::kFigure, ModelShape::kHead, ModelShape::kBox, ModelShape::kSphere,
                                   ModelShape::kCylinder}) {
        scene.setShape(shape);
        const auto image = draw(scene, centered());
        assert(covered(image) > 1500);
        assert(at(image, 0, 0)[3] == 0);  // The background stays clear.
    }
}

static void testLightComesFromItsSide() {
    ModelScene scene;
    scene.setShape(ModelShape::kSphere);
    ModelView view = centered();
    view.lightAzimuth = 3.14159f;  // From the left.
    view.lightElevation = 0.3f;
    const auto image = draw(scene, view);
    // Premultiplied and opaque inside, so red alone shows how lit it is.
    assert(at(image, 60, 80)[0] > at(image, 140, 80)[0] + 40);
}

static void testLinesOnlyLeavesTheInsideClear() {
    ModelScene scene;
    scene.setShape(ModelShape::kBox);
    ModelView view = centered();
    view.style = 1;
    view.yaw = 0.6f;
    view.pitch = 0.4f;
    const auto image = draw(scene, view);
    assert(covered(image) > 200);
    assert(covered(image) < 6000);
}

static void testFigureTurnsAndPoses() {
    ModelScene scene;
    const auto front = draw(scene, centered());
    ModelView side = centered();
    side.yaw = 1.5708f;
    const auto turned = draw(scene, side);
    // Seen from the side, the shoulders no longer spread across the picture.
    assert(covered(turned) < covered(front));
    float raised[kFigureJoints * 3];
    std::copy(standardPose(), standardPose() + kFigureJoints * 3, raised);
    raised[5 * 3 + 1] = 8.5f;  // Lift the left wrist above the head.
    raised[4 * 3 + 1] = 7.2f;
    scene.setPose(raised);
    const auto lifted = draw(scene, centered());
    int highest = kSize;
    for (int y = 0; y < kSize && highest == kSize; ++y) {
        for (int x = 0; x < kSize; ++x) {
            if (at(lifted, x, y)[3] > 128) {
                highest = y;
                break;
            }
        }
    }
    int frontHighest = kSize;
    for (int y = 0; y < kSize && frontHighest == kSize; ++y) {
        for (int x = 0; x < kSize; ++x) {
            if (at(front, x, y)[3] > 128) {
                frontHighest = y;
                break;
            }
        }
    }
    assert(highest < frontHighest);
}

static void testObjLoads() {
    ModelScene scene;
    const char* pyramid =
        "# a pyramid\nv 0 1 0\nv -1 0 -1\nv 1 0 -1\nv 1 0 1\nv -1 0 1\n"
        "f 1 2 3\nf 1/1 3/1 4/1\nf 1 4 5\nf -5 -1 -4\nf 2 3 4 5\n";
    assert(scene.loadObj(pyramid));
    assert(scene.shape() == ModelShape::kImported);
    assert(scene.triangleCount() == 6);
    assert(covered(draw(scene, centered())) > 1000);
    assert(!scene.loadObj("nothing here"));
    assert(scene.shape() == ModelShape::kImported);
}

int main() {
    testEveryShapeDraws();
    testLightComesFromItsSide();
    testLinesOnlyLeavesTheInsideClear();
    testFigureTurnsAndPoses();
    testObjLoads();
    std::printf("model3d tests passed\n");
    return 0;
}
