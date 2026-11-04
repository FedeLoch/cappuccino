# Cappuccino on the web

The Android app's drawing screen in the browser: the C++ engine from `engine/src/` compiled to
WebAssembly on WebGL 2, with the same toolbars, sliders, panels, icons, and companion as
`MainActivity.showChrome()`. It's meant for friends who draw on Windows with Wacom, XP-Pen, or
Huion tablets.

The engine sources are shared with the Android app, not copied. The only engine change is two
`glBindTexture(GL_TEXTURE_2D, 0)` calls behind `#ifdef __EMSCRIPTEN__` in `renderer.cpp`: WebGL
refuses to draw into a framebuffer while its texture is bound to any texture unit, which broke the
Gray, values, and Print views. The icons are generated from the Android vector drawables at build
time. Everything web-only lives here:

| File | What it is |
| --- | --- |
| `src/web_api.cpp` | A small C API over `Renderer`, the web counterpart of `app/src/main/cpp/jni.cpp` |
| `index.html`, `style.css`, `app.js` | The drawing screen: canvas, toolbars, panels, pointer input |
| `brushes.js` | The built-in brushes and erasers from `BrushKind` in `Model.kt`, tips included |
| `companion.js` | The paint drop companion from `CompanionView.kt` |
| `tools/icons.py` | Turns `app/src/main/res/drawable/ic_*.xml` into `icons.js` |
| `build.sh` | Builds everything into `docs/app/` |

## Build

Needs Emscripten (`brew install emscripten`, tested with 6.0.13) and Python 3.

```bash
web/build.sh
```

`web/build.sh debug` builds with assertions and GL error checks. The output is about 180 KB in
total, written to `docs/app/` so GitHub Pages serves it at `/app/`.

## Run locally

Any static server works. From the repository root:

```bash
python3 -m http.server 4173 --directory docs
```

Then open <http://localhost:4173/app/>. Add `?stats` for the pen stats panel, or press `` ` ``.

## What works

The screen is laid out as on Android, in the same sizes (one dp is one CSS pixel) and colors.

- **Top left**: Projects, Actions, References, Art direction, and Tools. Art direction has the
  Color, Gray, 2 values, 3 values, and Print views, the composition guides, symmetry, and Flip
  canvas. Tools has Smudge, Fill, Gradient, and Lasso fill.
- **Top right**: Brushes, Eraser, Layers, and the color swatch. The Brushes panel has the Sketch,
  Paint, and Scatter families with the same previews, ★ favorites (hold a brush), and Brush feel
  with the stabilizer and the pressure curve. The Erasers panel has every eraser shape. The color
  panel has the picker, values of the hue, recent colors, harmonies, and the starter palettes.
- **Left edge**: the size and opacity sliders, which each brush remembers, and the four most
  recent colors below them.
- **Bottom left**: the companion, which takes the brush color, blinks, and can be dragged.
- **Gestures and keys**: a two-finger tap undoes, three fingers redo, and four hide the interface,
  as does Tab. Ctrl+Z, Ctrl+Shift+Z, and Ctrl+Y undo and redo. B and E pick the last brush and
  eraser, and `[` and `]` resize. The pen's eraser end erases.
- **Web only**: the mouse wheel zooms at the cursor, a trackpad pans and pinches, middle-drag or
  Space+drag pans, and `0` fits the page to the window.
- **Pen input** through Pointer Events with `getCoalescedEvents()`, `getPredictedEvents()`,
  pressure, and tilt, drawn straight from the pointer handler on a `desynchronized: true` WebGL 2
  context. Like Android's front buffer, the engine redraws only the part of the screen that
  changed, so the context keeps `preserveDrawingBuffer: true`.

## Not yet

- Projects, Actions, References, and Layers open a "coming soon" panel. Layers, saving to browser
  storage, and importing Android backups are step 2.
- The companion's notes and warm-ups (step 3). Tapping it shows a short note for now.
- Lasso and Magic wand selection, the perspective, light, and figure guides, Snap shapes, custom
  brushes, the CMYK picker, palettes from artwork or references, and two-finger rotation.
- The companion doesn't peek from behind the toolbar when hidden, and its bubble has no tail.
- No real pen has been tried yet. The checks used a mouse and scripted pen events.

## Measured (step 1)

On a Mac (Apple Silicon) in the Claude desktop app's Chromium 152 pane, with a 976 × 1650
drawing buffer. GPU time was included by reading back one pixel after each batch.

| What | Time |
| --- | --- |
| One pen event (4 coalesced + 3 predicted samples), Ink 24 px | 0.07 ms (0.02 ms on the CPU) |
| The same with Ink 200 px | 0.04 ms |
| The same with Pencil 60 px | 0.05 ms |
| Full redraw (pan, zoom) | 0.5 ms |
| Undo / redo of a long stroke | 4 ms |
| Display frame interval while drawing | 8.4 ms (120 Hz display, no dropped frames) |
| Pointer event → stroke drawn (`event.timeStamp` to after the draw call) | 0.2 ms |
| Pointer event → next animation frame | 6.3 ms |

The engine's cost per event is a small fraction of a frame, so latency comes down to the browser
and the display. That pane's Chromium doesn't report `desynchronized` as on. Chrome supports
low-latency canvases on Windows and ChromeOS, which is where the friends with tablets should see
the difference. These are not measurements of light reaching the screen: JavaScript can't see
when a frame lights up.

## Testing with a tablet on Windows

1. Open the page in Chrome (or Edge) and press `` ` `` for the stats panel.
2. Check that `pointer` says `pen`, not `mouse`, and that `pressure` changes as you press. If it
   says `mouse` or pressure stays at 0.500, turn on **Use Windows Ink** in the tablet driver's
   settings (Wacom Tablet Properties → Mapping, or the XP-Pen/Huion app). Chrome gets pressure
   only through Windows Ink.
3. `samples/s` shows the pen's report rate (usually 133–240 for Wacom, up to 300 or more for some
   XP-Pen and Huion models). `desync` should say `on`.
4. Draw fast circles and slow lines with a few brushes, then report how it feels and the
   `event→drawn` and `event→frame` numbers.
