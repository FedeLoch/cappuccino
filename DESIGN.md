# Cappuccino: design v0.1

Cappuccino is the name of the app. The proposed Google Play title is Cappuccino Paint, since another app there is already called Cappuccino.

## Goal

A free, ad-free, hyper minimalist illustration app for Android, dedicated to artists.
The first target is one device: the XPPen Magic Drawing Pad. Beating HiPaint on
reliability and drawing feel comes before matching its feature count.

## Principles

- The drawing feel comes first. A feature that adds input lag does not ship.
- Never lose artwork. Saving is automatic and crash recovery is always on.
- The canvas fills the screen. The interface appears only when needed.
- No ads, accounts, tracking, or AI training. Artwork stays on the device unless
  the artist explicitly sends one image to an assistant provider they chose.
- The assistant only advises. It never draws, edits, or generates artwork.
- Files stay open. Artists can always export their work in standard formats.
- The app is open source, so it can outlive any one maintainer.

## Target device

Measured on the artist's unit on 2026-10-31:

| Item | Value | Design consequence |
|---|---|---|
| Screen | 2160 x 1440, 60 Hz | Tune the layout for landscape first, and support portrait |
| Memory | 8 GB RAM | Limit layers by available memory, not by a fixed number |
| Android | 12 (API 31) | Minimum SDK stays at API 31 |
| Chipset | MT8771 with Mali-G57 MC2 | OpenGL ES 3.2 and Vulkan 1.1 are available |
| Stylus | 16,384 pressure levels, side button, eraser end | Provide a pressure curve and map the eraser end |
| Tilt | Not reported by this unit | Tilt stays optional, and must work well without it |

To confirm through use: hover events, pen button events, and input lag.

## Interface

The main screen has only these controls:

- Top left: gallery, actions, adjustments, selection, transform
- Top right: brush, smudge, eraser, layers, current color
- Left edge: brush size and opacity sliders

Rules:

- There are no fixed bars or side panels.
- Panels float, and close when drawing resumes.
- Controls fade while the stylus touches the canvas and return on lift.
- Touches near the stylus are rejected, so a resting palm never draws.
- The interface uses dark neutral colors, so it doesn't affect color judgment.
- Icons and the visual identity are original.
- A new main-screen button needs a strong reason, and should replace another one.

Gestures:

| Gesture | Action |
|---|---|
| Two-finger tap | Undo |
| Three-finger tap | Redo |
| Pinch and twist | Zoom and rotate |
| Long press | Eyedropper |
| Four-finger tap | Hide all interface |

## Assistant

A small, original companion lives on the canvas screen. It is the app's signature and
the one allowed exception to the main-screen control rule.

The app has four companions, and the artist picks one in the companion panel. Light direction,
figure height, and sketch-stage notes join the color, value, composition, and silhouette tips. Only one
appears at a time, and both share the same behavior and advice.

- Paint drop: a warm drop that takes on the current brush color.
- Ink blot: a dark blot with a light outline, so it stays visible on dark canvases.
- Foam cup: a small cappuccino whose foam heart takes on the current brush color.
- Coffee drop: a coffee-colored drop with a foam cap.

Each companion starts with four expressions: idle, tip ready, thinking, and napping.

Behavior:

- It stays tiny, can be dragged to any edge, and fades while the stylus is down.
- It never opens a popup or speaks first. A soft change in its expression means it
  has a tip, and the artist taps it to see the tip.
- It reacts in small ways, such as looking at the stroke or napping during breaks.
- It hides with the rest of the interface, and can be turned off in one tap.

Layer 1 runs on the device and is free. Analysis runs at low priority only after a
stroke finishes and the artist pauses, never during a stroke.

| Topic | Local analysis | Confidence |
|---|---|---|
| Palette | Dominant colors, harmony, saturation balance | High |
| Values | Grayscale preview and value grouping | High |
| Silhouette | Filled outline preview of the subject | Medium |
| Composition | Visual weight, thirds, focal contrast | Medium |
| Perspective | Line detection and likely vanishing points | Medium on architecture |
| Proportions | Pose landmarks on figures | Low, needs testing on illustrations |

Layer 2 is optional, for free-form questions. The artist adds their own provider key,
then each question shows exactly what will be sent before anything leaves the device.
Nothing is sent automatically, and Layer 1 works without it.

## Architecture

- App and interface: Kotlin with Android views for now. Compose is still an option if the
  interface grows, since popups already separate controls from the canvas.
- Drawing engine: C++ called through JNI
- Graphics: OpenGL ES 3.x for the first version, since it pairs directly with
  AndroidX low-latency rendering. Vulkan can be reconsidered after profiling.
- Stylus input: batched `MotionEvent` history, plus `MotionEventPredictor`
- Low-latency strokes: `GLFrontBufferedRenderer`, with a normal-rendering fallback
- Canvas storage: sparse 256 x 256 tiles, with empty tiles using no memory
- Color: 8 bits per channel with premultiplied alpha, sRGB in the first version

### Stroke pipeline

1. Read every raw and historical stylus sample.
2. Smooth the path and apply the pressure curve.
3. Place brush stamps at even spacing along the path.
4. Draw the live stroke to the front buffer, including predicted points.
5. On commit, discard predicted points and merge the stroke into its layer tiles.

### Memory

A full RGBA layer at 4096 x 2732 is about 45 MB uncompressed. Sparse and compressed
tiles reduce this. The app shows the layer limit for the current canvas before it
is reached, and blocks a new layer rather than crashing.

### Undo

Undo stores only the tiles changed by each action. History lives in memory first,
then spills to disk, and is limited by a storage budget rather than a step count.

### Saving and files

- The native format is a documented zip package holding tiles, layer data, and a
  manifest, so it can be read outside the app.
- Each finished stroke is added to a recovery journal on a background thread.
- Saves write to a temporary file, then atomically replace the old file.
- The gallery keeps the previous good version of each artwork.
- The first version exports PNG and JPEG. PSD import and export come later.

## Art direction tools

- Brushes and erasers are chosen from cards with stroke previews. Every brush shape can
  erase, and each tool remembers its own size and opacity.
- The color panel has a picker with value steps, harmony suggestions, and palettes. Palettes
  can be collected from the artwork or an open reference.
- Layers show thumbnails and support transparency lock, drag reordering, duplicate, and
  merge down.
- Grayscale, two-value, and three-value views, composition guides, and canvas flip change
  only the view, never the artwork or exports.

## Perspective guide

- The art direction panel offers 1, 2, or 3 vanishing points, saved with each artwork. The
  points are dragged on the canvas while the panel is open.
- Guide lines are part of the view only, and never reach the artwork or exports.
- After each long, straight stroke, the companion compares it with the nearest allowed
  direction. It tolerates 4 degrees, and ignores short and curved strokes.
- It reports drift as an observation, since breaking perspective can be deliberate.

## Undo

Strokes and layer changes share one timeline. Adding, deleting, reordering, duplicating,
merging, renaming, and changing layer visibility, opacity, blend mode, or transparency lock
can all be undone and redone. Removed layers stay in memory for undo, up to a limit, and
history ends when the artwork closes. Selecting a layer is not an undo step.

## Professional tools

- A stabilizer per brush smooths slow lines.
- Fill floods the connected area on the active layer, and undo covers it.
- A layer can clip to the layer below, and exports keep that setting in PSD files.
- Transform moves, scales, and rotates the active layer until Done is tapped.
- PSD export keeps layers, names, opacity, visibility, clipping, and the four blend modes.
- Backup packs artworks, references, projects, and palettes into one zip file. Restore adds
  to the current library and keeps existing files.

- Selection uses a lasso or magic wand. Painting, fill, smudge, and gradients stay inside it,
  and it can be copied, cut, or transformed as a new layer.
- Fill reads the visible artwork, so it stops at line art on other layers.
- Layer groups can collapse, hide together, and share visibility. They flatten in PSD export.
- Adjustments cover hue, saturation, brightness, shadows, midtones, and highlights.
- Custom brushes save shape settings and an optional imported grayscale tip.
- Canvases can be any size from 64 to 8192 pixels per side.
- PSD import reads 8-bit RGB files and flattens group folders into their layers.
- A time-lapse frame is saved with each autosave at low resolution.

Layer limits now depend on canvas size and device memory. Layers are still full textures, so
tile storage remains future work, along with a stroke recovery journal.

## Reference match

When hidden, the companion rests behind the top-left toolbar and wakes with a tap.

The companion compares the artwork with open references, or with the project's whole library
when none are open. It runs on the device after each autosave and scores palette, values,
composition, and mood, keeping the closest reference for each area. It names the weakest area
and offers colors to try. Every tip has a Why view with its measurements, and reference tips
also show the artwork beside the closest reference. It measures art direction, and does not recognize subjects or style.

## Projects and references

- The home screen is the projects view. Artworks can be grouped into projects, renamed,
  moved, duplicated, and deleted.
- Each project has its own reference library, and unsorted artworks share one.
- A reference floats over the canvas in its own window. It can be moved, resized, zoomed,
  flipped, shown in grayscale, made transparent, and pinned for tracing.
- Each artwork remembers its open references, their positions, and their settings.
- A long press picks a color. A reference can also become a palette or a new layer.
- Images can be shared into a project from other apps.

## Current storage limits

Layers are full textures, limited by canvas size and device memory, until tiles replace them.
Autosave runs after each pause of 2.5 seconds and when the app leaves the screen, so a crash
can lose strokes since the last save. The recovery journal is still planned.

## Roadmap

Each milestone must pass its gate on the real device before the next one starts.

| Milestone | Scope | Gate |
|---|---|---|
| 1. Drawing prototype | One brush, pressure, tilt if present, zoom and rotate, undo | Artist approves the feel in a side-by-side test with HiPaint |
| 2. Daily sketching | Layers, blend modes, color picker, eraser, smudge, gallery, autosave, PNG export | A forced-crash test loses no finished stroke |
| 3. Serious illustration | About 10 refined brushes, custom brushes, selections, transforms, clipping masks, PSD | A complete illustration is finished in the app |
| 4. Public release | Device testing, onboarding, Google Play, and F-Droid | Beta runs without data-loss reports |
| 5. Assistant | Companion, local analysis, optional questions through the artist's own key | Measured: no added input lag during drawing |
| 6. Later | Symmetry, perspective guides, time-lapse, animation, brush sharing | Decided from artist feedback |

## First version non-goals

Cloud sync, accounts, social features, generative AI tools, phone-first layouts,
CMYK, and animation.

## Measures

- Input lag: target under 20 ms from stylus event to visible ink, using a high-speed
  camera on the real device. This target must be validated.
- Pan and zoom: 60 fps with a typical multi-layer canvas.
- Data loss: zero finished strokes lost in forced-crash tests.

## Open decisions

- Final companion artwork, companion names, and visual identity.
- Trademark check and domain for Cappuccino. On 2026-10-31, cappuccino.app and
  cappuccinoapp.com were registered. cappuccino.art, cappuccinopaint.com,
  cappuccinodraw.com, and getcappuccino.app appeared unregistered.
- Open-source license. GPL-3.0 is the current recommendation, because it keeps
  derivative apps open. Its compatibility with F-Droid and Google Play is fine, but
  any third-party libraries must be checked against it.
- The remaining UI constraints from the artist
