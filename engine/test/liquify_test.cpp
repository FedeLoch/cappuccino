#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

#include "liquify.h"

using namespace cappuccino;

int main() {
    LiquifyMesh mesh(100, 100, 10);
    assert(mesh.columns() == 11 && mesh.rows() == 11);
    assert(mesh.indices().size() == 10 * 10 * 6);

    mesh.dab(50, 50, 60, 50, 20, 1.0f, LiquifyMode::kPush, nullptr);
    assert(std::fabs(mesh.x(5, 5) - 60.0f) < 0.01f);
    assert(mesh.y(5, 5) == 50.0f);
    assert(mesh.x(9, 5) == 90.0f);

    for (int i = 0; i < 80; ++i) mesh.dab(55, 50, 55, 50, 30, 1.0f, LiquifyMode::kRestore, nullptr);
    assert(std::fabs(mesh.x(5, 5) - 50.0f) < 0.5f);

    LiquifyMesh grow(100, 100, 10);
    grow.dab(50, 50, 50, 50, 40, 1.0f, LiquifyMode::kGrow, nullptr);
    assert(grow.x(6, 5) > 60.0f && grow.x(4, 5) < 40.0f);
    LiquifyMesh shrink(100, 100, 10);
    shrink.dab(50, 50, 50, 50, 40, 1.0f, LiquifyMode::kShrink, nullptr);
    assert(shrink.x(6, 5) < 60.0f);

    std::vector<uint8_t> mask(100 * 100, 0);
    LiquifyMesh guarded(100, 100, 10);
    guarded.dab(50, 50, 60, 50, 20, 1.0f, LiquifyMode::kPush, mask.data());
    assert(guarded.x(5, 5) == 50.0f);

    std::puts("liquify tests passed");
    return 0;
}
