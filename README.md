# Cappuccino

A free, ad-free, minimalist illustration app for Android. See [DESIGN.md](DESIGN.md).

This is an early version. It has brushes, color, layers, autosave, projects, references,
PNG export, and an on-device companion.

| Input | Action |
|---|---|
| Stylus | Draw |
| Stylus eraser end | Erase |
| Two fingers, drag | Move, zoom, and rotate |
| Two-finger tap | Undo |
| Three-finger tap | Redo |
| Four-finger tap | Hide or show the interface |
| Finger long press | Pick a color from the artwork |

## Build

Requires Android Studio with its SDK, plus JDK 21. Gradle installs the NDK and CMake.

```bash
./gradlew :app:installDebug
```

## Test

Stroke logic, on the computer:

```bash
c++ -std=c++20 -Iengine/src engine/src/stroke.cpp engine/test/stroke_test.cpp -o /tmp/stroke_test && /tmp/stroke_test
```

Stylus and gesture behavior, on a running emulator. This uninstalls the app afterward, which
deletes its artwork, so don't run it on a device with drawings you want to keep:

```bash
./gradlew :app:connectedDebugAndroidTest
```
