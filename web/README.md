# Cappuccino on the web

Step 1 of the web version: the C++ engine from `engine/src/` compiled to WebAssembly on
WebGL 2, behind a one-page pen test. It's meant to show whether drawing feels right with
Windows drawing tablets (Wacom, XP-Pen, Huion) in Chrome.

The engine sources are shared with the Android app, not copied, and compile **unchanged**: no
`#ifdef __EMSCRIPTEN__` was needed. Everything web-only lives here:

| File | What it is |
| --- | --- |
| `src/web_api.cpp` | A small C API over `Renderer`, the web counterpart of `app/src/main/cpp/jni.cpp` |
| `index.html`, `style.css`, `app.js` | The test page: canvas, toolbar, pointer input, stats |
| `build.sh` | Builds everything into `docs/app/` |

## Build

Needs Emscripten (`brew install emscripten`, tested with 6.0.13).

```bash
web/build.sh
```

`web/build.sh debug` builds with assertions and GL error checks. The output is about 110 KB in
total (56 KB of wasm), written to `docs/app/` so the existing GitHub Pages site can serve it at
`/app/`. Nothing links to it from `docs/index.html` yet.

## Run locally

Any static server works. From the repository root:

```bash
python3 -m http.server 4173 --directory docs
```

Then open <http://localhost:4173/app/>. Add `?stats` to open the stats panel right away, or use
the pulse button at the end of the toolbar.

## What works

- **Pen input** through Pointer Events, with `getCoalescedEvents()` for every sample the tablet
  reports between frames and `getPredictedEvents()` for the predicted ink ahead of the pen. This
  is the same prediction path as Android: the engine draws predicted stamps on screen only and
  replaces them on the next event.
- **Pressure** goes straight to the engine's pressure curve. **Tilt** comes from
  `altitudeAngle`/`azimuthAngle`, or from `tiltX`/`tiltY` where those are missing, and the
  engine leans the stamp the same way it does on Android.
- **Brushes**: Ink, Pencil (grain included), and the Ink eraser, with the same settings as
  `BrushKind` in `Model.kt`. The pen's eraser end erases too. There's a size slider (`[` and `]`)
  and six colors.
- **Undo and redo** with the engine's own history: Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y, or the buttons.
- **Pan and zoom**: one finger pans and two fingers pinch. Touches are ignored while the pen
  draws, so a palm on the screen doesn't count. With a mouse or trackpad, the wheel zooms at the
  cursor, a trackpad pinch zooms, two-finger scrolling pans, and middle-drag or Space+drag pans.
  `0` fits the page to the window.
- **Low latency**: the WebGL 2 context is created with `desynchronized: true`, and strokes draw
  inside the pointer handler, not on the next animation frame. Like Android's front buffer, the
  engine redraws only the part of the screen the stroke changed, so the context keeps
  `preserveDrawingBuffer: true`. A full redraw happens only for pan, zoom, undo, and resizing.
- **No per-frame allocations** in our code: samples are written straight into fixed buffers on
  the wasm heap, and nothing is copied across the boundary. The only per-event arrays are the
  ones the browser itself returns from `getCoalescedEvents()` and `getPredictedEvents()`.

## Not yet

- Layers, saving to browser storage, importing Android backups (step 2).
- The companion and the warm-ups (step 3), and the rest of the app's UI (step 4).
- Other brushes, smudge, fill, selection, symmetry, liquify, the stabilizer, and canvas
  rotation. The engine already has all of them; this page just doesn't expose them.
- The canvas is a fixed 2048 × 1536 white page, and there's no way to save it.
- No real pen has been tried yet. The checks below used a mouse and scripted pen events.

## Measured

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
and the display. That pane's Chromium doesn't report `desynchronized` as on, so it composites
like a normal canvas. Chrome supports low-latency canvases on Windows and ChromeOS, which is where
the friends with tablets should see the difference.

These are not measurements of light reaching the screen. JavaScript can't see when a frame
actually lights up, and the numbers above stop at the draw call and the next frame callback.

## Testing with a tablet on Windows

1. Open the page in Chrome (or Edge) and click the pulse button for the stats panel.
2. Check that `pointer` says `pen`, not `mouse`, and that `pressure` changes as you press. If it
   says `mouse` or pressure stays at 0.500, turn on **Use Windows Ink** in the tablet driver's
   settings (Wacom Tablet Properties → Mapping, or the XP-Pen/Huion app). Chrome gets pressure
   only through Windows Ink.
3. `samples/s` shows the pen's report rate (usually 133–240 for Wacom, up to 300 or more for some
   XP-Pen and Huion models). `desync` should say `on`.
4. Draw fast circles and slow lines with Ink and Pencil, then report how it feels and the
   `event→drawn` and `event→frame` numbers.
