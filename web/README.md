# Cappuccino on the web

The Android app in the browser: the home screen from `GalleryActivity` and the drawing screen from
`MainActivity`, over the C++ engine from `engine/src/` compiled to WebAssembly on WebGL 2. It's
meant for friends who draw on Windows with Wacom, XP-Pen, or Huion tablets. The site's
"Use it online" buttons open it at `/app/`.

The engine sources are shared with the Android app, not copied. The only engine change is two
`glBindTexture(GL_TEXTURE_2D, 0)` calls behind `#ifdef __EMSCRIPTEN__` in `renderer.cpp`: WebGL
refuses to draw into a framebuffer while its texture is bound to any texture unit, which broke
the paper in the Gray, values, and Print views and in saved thumbnails. The icons and the Spanish
translations are generated from the Android sources at build time. Everything web-only lives here:

| File | What it is |
| --- | --- |
| `src/web_api.cpp` | A small C API over `Renderer`, the web counterpart of `app/src/main/cpp/jni.cpp` |
| `index.html`, `style.css`, `app.js` | The page, and the drawing screen: canvas, toolbars, panels, pointer input, saving |
| `home.js` | The home screen and its sheets, from `GalleryActivity.kt` and `ArtworkSheets.kt` |
| `storage.js` | Reads and writes artworks, references, palettes, and brushes in the Android format |
| `zip.js` | Writes and reads backup files, the same zip `ArtworkStore.backup()` makes |
| `ui.js` | The DOM builder, the interface scale, the companion's notes, and the cream sheets |
| `i18n.js` | Languages, from `Translations.kt` |
| `brushes.js` | The built-in brushes and erasers from `BrushKind` in `Model.kt`, tips included |
| `companion.js` | The companion and its looks, from `CompanionView.kt` |
| `analysis.js` | What the companion notices, from `Analysis.kt` and `CompanionMemory.kt` |
| `references.js` | The reference library and floating windows, from `ReferenceLibrary.kt` and `ReferenceWindow.kt` |
| `match.js` | Comparing an artwork with its references, from `ReferenceMatch.kt` |
| `shapes.js` | Snap shapes, from `ShapeSnap.kt` |
| `warmups.js`, `practice.js` | Warm-ups, from `Warmups.kt`, `WarmupLayout.kt`, `WarmupForms.kt`, and `WarmupExtras.kt` |
| `psd.js` | PSD import and export, from `PsdReader.kt` and `PsdWriter.kt` |
| `tools/icons.py` | Turns `app/src/main/res/drawable/ic_*.xml` into `icons.js` |
| `tools/translations.py` | Copies the Spanish table from `Translations.kt` into `translations.js` |
| `build.sh` | Builds everything into `docs/app/` |

## Build

Needs Emscripten (`brew install emscripten`, tested with 6.0.13) and Python 3.

```bash
web/build.sh
```

`web/build.sh debug` builds with assertions and GL error checks. The output is about 700 KB in
total, written to `docs/app/` so GitHub Pages serves it at `/app/`.

## Run locally

Any static server works. From the repository root:

```bash
python3 -m http.server 4173 --directory docs
```

Then open <http://localhost:4173/app/>. Add `?stats` for the pen stats panel, or press `` ` ``.

## Where artworks are saved

Choose a folder on your computer and every artwork is saved there as you draw. The first New
artwork asks, and **Backup** on the home screen lets you change it later. This uses the File
System Access API, so it needs Chrome or Edge; other browsers keep artworks in the browser's own
storage. After a reload the browser asks once for permission to open the folder again.

The folder has the same layout and formats as the Android app's storage and its backup zip:

```
projects.json, palettes.json, brushes.json, brush-<id>.tip
artworks/<id>/manifest.json, manifest.prev.json, thumbnail.png, preview.jpg, pack-<n>.bin,
              snapshot.json, first-snapshot.json, timelapse/<n>.jpg
references/<sketchbook or "unsorted">/<image>, library.json, boards.json
```

Layers are 256 px RGBA tiles compressed with zlib, packed into `pack-<n>.bin` files. A save writes
only the tiles that changed, 2.5 seconds after the last edit as on Android, and again with a
preview when you go back home. **Backup** also saves and restores a backup file, the same zip the
Android app makes. Restoring keeps everything already there and adds the rest, as on Android.
Backup files haven't been tried with a real Android backup yet.

## What works

Everything from the Android app, laid out in the same sizes and colors. The interface is scaled
to fill the window the way the app fills a tablet (about 1000 by 680 dp), from one to two times.

- **Home**: Backup (the folder, backup files, and Language), Import PSD, New artwork, the
  companion's greeting and Warm up, Continue, sketchbooks with their covers, Recent with search,
  and each sketchbook's Artworks and References. Right-click does what a long press does.
- **Actions**: rename, text layers, stickers, color variants, variations, animation, design
  sheet, frames, PNG, PSD, and time-lapse export, Language, the pen button, and the companion.
- **References**: each sketchbook's library with blocks, stars, and Select, floating windows
  that move, resize, zoom (wheel or pinch), flip, go gray, pin for tracing, pick a color (hold),
  save their palette, or become a layer. Images dropped on the studio join the library.
- **Art direction**: the check views, composition guides, perspective with 1 to 3 vanishing
  points, symmetry, light direction, and figure height, each with handles to drag, and Flip canvas.
- **Tools**: Smudge, Fill, Gradient, Lasso fill, Transform, Liquify, Adjust, Filters, and Lasso and
  Magic wand selection.
- **Brushes**: every family, ★ favorites, Brush feel with stabilizer, pressure, and Snap shapes
  (hold at the end of a stroke), and your own brushes with an imported tip.
- **Color**: the picker with RGB or CMYK and the print check, harmonies, and palettes from the
  artwork, a reference, or recent colors.
- **Layers**: masks, groups, blend modes, clipping, alpha lock, merge, duplicate, and reorder.
- **Companion**: its looks and moods, notes after each save with marks on the canvas, Why,
  Match against your references, Ideas, warm-ups with feedback, and drifting perspective lines.
- **Gestures and keys**: two fingers pan, pinch, and turn the canvas. Two-, three-, and
  four-finger taps undo, redo, and hide the interface. Ctrl+Z, Ctrl+Shift+Z, and Ctrl+Y undo and
  redo. B and E pick the last brush and eraser, and `[` and `]` resize. The pen's eraser end erases.
- **Web only**: the mouse wheel zooms at the cursor, a trackpad pans and pinches, middle-drag or
  Space+drag pans, Alt-click picks a color, and `0` fits the page to the window.
- **Pen input** through Pointer Events with `getCoalescedEvents()`, `getPredictedEvents()`,
  pressure, and tilt, drawn straight from the pointer handler on a `desynchronized: true` WebGL 2
  context. Like Android's front buffer, the engine redraws only the part of the screen that
  changed, so the context keeps `preserveDrawingBuffer: true`.

## Not yet

- No real pen has been tried yet. The checks used a mouse and scripted pen and touch events.
- Spanish covers everything Android translates, plus the web-only lines.

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
