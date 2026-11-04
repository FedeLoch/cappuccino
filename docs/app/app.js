// Cappuccino's drawing screen on the web, laid out like MainActivity.showChrome() on Android, over
// the shared C++ engine running as WebAssembly on WebGL 2.
//
// Strokes draw straight from the pointer handlers into a desynchronized canvas, the way the
// Android app draws into its front buffer. Pan, zoom, undo, and resizing redraw the whole view
// on the next animation frame. Nothing here allocates per sample or per frame except the arrays
// the browser itself returns from getCoalescedEvents() and getPredictedEvents().

import createEngine from "./engine.js";
import ICONS from "./icons.js";
import {
  BRUSH_FAMILIES, DEFAULT_BRUSH, DEFAULT_ERASER, ERASERS, JavaRandom, TIP_SIZE, TOOLS,
  isTool, javaHash, stampFlow,
} from "./brushes.js";
import { Companion } from "./companion.js";
import { h, say as sayNote, svg } from "./ui.js";
import { Home } from "./home.js";
import {
  ArtworkStore, PREVIEW_WIDTH, THUMBNAIL_WIDTH, TILE_SIZE, previewHeight, thumbnailHeight,
} from "./storage.js";

// The defaults and limits from Model.kt and MainActivity.kt.
const AUTOSAVE_DELAY_MS = 2500;
const MIN_BRUSH = 4;
const MAX_BRUSH = 300;
const KEY_SIZE_STEP = 1.2;
const QUICK_COLORS = 4;
const MAX_SMOOTHING = 0.92;
const TAP_TIMEOUT_MS = 300;
const TAP_TRAVEL = 16;
const MIN_ZOOM = 0.02;
const MAX_ZOOM = 32;
const TEXT_COLOR = "#F1EFE8";
const GUIDES = ["None", "Thirds", "Golden", "Grid", "Isometric", "Views", "Frames"];
const STARTER_PALETTES = [
  ["Warm dusk", ["#2B1B2E", "#6D2E46", "#C8553D", "#F28F3B", "#FFD5C2"]],
  ["Cool night", ["#0B132B", "#1C2541", "#3A506B", "#5BC0BE", "#D8E2DC"]],
  ["Earth tones", ["#3A2E27", "#6B4F3A", "#A47148", "#C9A66B", "#EFE3C8"]],
  ["Skin range", ["#4A2C22", "#8D5A3B", "#C68863", "#E8B896", "#F9DCC4"]],
];
const LEFT_PANELS = new Set(["actions", "references", "view", "tools"]);

const canvas = document.getElementById("canvas");
const overlay = document.getElementById("overlay");
const overlayContext = overlay.getContext("2d");
const panel = document.getElementById("panel");
const panelBody = document.getElementById("panel-body");
const studioView = document.getElementById("studio");
const homeView = document.getElementById("home");
const statsBox = document.getElementById("stats");
const message = document.getElementById("message");

for (const button of document.querySelectorAll("[data-icon]")) button.innerHTML = ICONS[button.dataset.icon];

// Created before the engine asks for it, so it gets the attributes Emscripten can't request.
const gl = canvas.getContext("webgl2", {
  alpha: false,
  depth: false,
  stencil: false,
  antialias: false,
  desynchronized: true,
  preserveDrawingBuffer: true,
  powerPreference: "high-performance",
});
if (!gl) fail("This browser doesn't support WebGL 2.");

const engine = await createEngine();
if (!engine._cap_init()) fail("Couldn't start the drawing engine.");

const SAMPLE_FLOATS = engine._cap_sample_floats();
const MAX_SAMPLES = engine._cap_max_samples();
const MAX_POINTS = engine._cap_max_points();
const samplesOffset = engine._cap_samples() >> 2;
const predictedOffset = engine._cap_predicted() >> 2;
const pointsOffset = engine._cap_points() >> 2;
const viewOffset = engine._cap_view() >> 2;
const tipPointer = engine._cap_tip();

// --- Preferences, kept per browser like Android's SharedPreferences --------------------------

function loadPreference(key, fallback) {
  try {
    const value = localStorage.getItem(`cappuccino.${key}`);
    return value === null ? fallback : JSON.parse(value);
  } catch {
    return fallback;
  }
}

function savePreference(key, value) {
  try {
    localStorage.setItem(`cappuccino.${key}`, JSON.stringify(value));
  } catch {
    // Private windows can refuse storage; the setting then lasts for this visit.
  }
}

// --- Brush state, as in MainActivity ---------------------------------------------------------

let brush = DEFAULT_BRUSH;
let lastBrush = DEFAULT_BRUSH;
let lastEraser = DEFAULT_ERASER;
let color = "#2C2C2A";
let colorBeforePanel = color;
const recentColors = [];
const toolSettings = new Map();  // Brush id -> size, opacity, and stabilizer.
let brushTab = "";
let brushFeelOpen = false;
let colorTab = 0;
let viewMode = 0;
let guide = 0;
let symmetry = 0;
let interfaceHidden = false;
let favorites = new Set(loadPreference("favorites", []));

function settingsFor(kind) {
  let settings = toolSettings.get(kind.id);
  if (!settings) {
    settings = { diameter: kind.size, opacity: 1, stabilizer: 0 };
    toolSettings.set(kind.id, settings);
  }
  return settings;
}

function pressureGamma(kind) {
  return loadPreference(`pressure-${kind.id}`, 0.75);
}

// --- View --------------------------------------------------------------------------------------

let canvasWidth = 4096;
let canvasHeight = 2732;
let opened = false;  // Whether the engine holds an artwork yet.
let dpr = 1;
let zoom = 1;
let flipped = false;
let panX = 0;
let panY = 0;
let composeQueued = false;

function writeView() {
  const heap = engine.HEAPF32;
  heap[viewOffset] = flipped ? -zoom : zoom;
  heap[viewOffset + 1] = 0;
  heap[viewOffset + 2] = panX;
  heap[viewOffset + 3] = 0;
  heap[viewOffset + 4] = zoom;
  heap[viewOffset + 5] = panY;
  heap[viewOffset + 6] = 0;
  heap[viewOffset + 7] = 0;
  heap[viewOffset + 8] = 1;
}

function toCanvasX(x) {
  return (x - panX) / (flipped ? -zoom : zoom);
}

function toCanvasY(y) {
  return (y - panY) / zoom;
}

function fitView() {
  zoom = Math.min(canvas.width / canvasWidth, canvas.height / canvasHeight) * 0.95;
  flipped = false;
  panX = (canvas.width - canvasWidth * zoom) / 2;
  panY = (canvas.height - canvasHeight * zoom) / 2;
  writeView();
  queueCompose();
}

function zoomAt(x, y, factor) {
  const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom * factor));
  panX = x - (x - panX) * (next / zoom);
  panY = y - (y - panY) * (next / zoom);
  zoom = next;
  writeView();
  queueCompose();
}

function flipCanvas() {
  flipped = !flipped;
  panX = canvas.width - panX;
  writeView();
  queueCompose();
}

function resize() {
  dpr = window.devicePixelRatio || 1;
  const width = Math.round(window.innerWidth * dpr);
  const height = Math.round(window.innerHeight * dpr);
  if (width === canvas.width && height === canvas.height) return;
  const first = canvas.width === 300 && canvas.height === 150;
  if (!first) {
    // Keeps the same part of the canvas in the middle at the same share of the screen.
    const before = Math.min(canvas.width / canvasWidth, canvas.height / canvasHeight);
    const after = Math.min(width / canvasWidth, height / canvasHeight);
    const scale = after / before;
    panX = width / 2 + (panX - canvas.width / 2) * scale;
    panY = height / 2 + (panY - canvas.height / 2) * scale;
    zoom *= scale;
  }
  canvas.width = width;
  canvas.height = height;
  overlay.width = width;
  overlay.height = height;
  engine._cap_resize(width, height);
  if (first) fitView();
  writeView();
  composeNow();
  companion.resize();
}

function queueCompose() {
  if (composeQueued) return;
  composeQueued = true;
  requestAnimationFrame(composeNow);
}

function composeNow() {
  if (!composeQueued && arguments.length > 0) return;
  composeQueued = false;
  if (!opened) return;
  const start = performance.now();
  engine._cap_compose();
  composeIndex = record(composeTimes, composeIndex, performance.now() - start);
}

// --- Stats, with ?stats or the ` key -----------------------------------------------------------

const STAT_WINDOW = 120;
const drawTimes = new Float64Array(STAT_WINDOW);
const inputLatencies = new Float64Array(STAT_WINDOW);
const frameLatencies = new Float64Array(STAT_WINDOW);
const frameIntervals = new Float64Array(STAT_WINDOW);
const composeTimes = new Float64Array(STAT_WINDOW);
let drawIndex = 0, inputIndex = 0, frameLatencyIndex = 0, frameIndex = 0, composeIndex = 0;
let pendingEventTime = -1;
let lastFrame = 0;
let sampleCount = 0;
let sampleWindowStart = 0;
let sampleRate = 0;
let lastPointerType = "-";
let lastPressure = 0;
let lastTilt = 0;
let lastCoalesced = 0;
let lastPredicted = 0;
let statsOn = new URLSearchParams(location.search).has("stats");
let statsUpdated = 0;

function record(buffer, index, value) {
  buffer[index % STAT_WINDOW] = value;
  return index + 1;
}

function statsFrame() {
  if (!statsOn) return;
  const now = performance.now();
  if (lastFrame) frameIndex = record(frameIntervals, frameIndex, now - lastFrame);
  lastFrame = now;
  if (pendingEventTime >= 0) {
    frameLatencyIndex = record(frameLatencies, frameLatencyIndex, now - pendingEventTime);
    pendingEventTime = -1;
  }
  if (now - statsUpdated > 250) {
    statsUpdated = now;
    showStats();
  }
  requestAnimationFrame(statsFrame);
}

function setStats(on) {
  statsOn = on;
  statsBox.hidden = !on;
  if (on) {
    lastFrame = 0;
    requestAnimationFrame(statsFrame);
  }
}

function average(buffer, count) {
  const n = Math.min(count, STAT_WINDOW);
  if (n === 0) return NaN;
  let sum = 0;
  for (let i = 0; i < n; i++) sum += buffer[i];
  return sum / n;
}

function maximum(buffer, count) {
  const n = Math.min(count, STAT_WINDOW);
  let max = 0;
  for (let i = 0; i < n; i++) max = Math.max(max, buffer[i]);
  return n === 0 ? NaN : max;
}

function ms(value) {
  return Number.isNaN(value) ? "   -" : value.toFixed(2).padStart(5);
}

function showStats() {
  const attributes = gl.getContextAttributes();
  const interval = average(frameIntervals, frameIndex);
  statsBox.textContent =
    `pointer      ${lastPointerType}\n` +
    `pressure     ${lastPressure.toFixed(3)}   tilt ${(lastTilt * 180 / Math.PI).toFixed(0)}°\n` +
    `samples/s    ${sampleRate.toFixed(0)}   (${lastCoalesced} coalesced, ${lastPredicted} predicted)\n` +
    `frame        ${ms(interval)} ms  (${Number.isNaN(interval) ? "-" : (1000 / interval).toFixed(0)} fps)\n` +
    `stroke draw  ${ms(average(drawTimes, drawIndex))} ms avg  ${ms(maximum(drawTimes, drawIndex))} max\n` +
    `full redraw  ${ms(average(composeTimes, composeIndex))} ms avg\n` +
    `event→drawn  ${ms(average(inputLatencies, inputIndex))} ms avg\n` +
    `event→frame  ${ms(average(frameLatencies, frameLatencyIndex))} ms avg\n` +
    `desync       ${attributes && attributes.desynchronized ? "on" : "off"}   zoom ${(zoom * 100).toFixed(0)}%`;
}

// --- Strokes -----------------------------------------------------------------------------------

let strokePointer = -1;  // The pen or mouse drawing right now.
let strokeKind = brush;
let stabilizerFollow = 1;
let stabilizedX = 0;
let stabilizedY = 0;
let stabilizerStarted = false;
let toolGesture = null;  // A gradient or lasso fill being drawn.
let strokeHadSamples = false;

function hexToRgb(hex) {
  const value = parseInt(hex.slice(1), 16);
  return [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255];
}

// BrushKind.strokeColor: moves the hue, saturation, and value a little for each stroke.
function strokeColor(kind) {
  if (kind.strokeJitter <= 0 || kind.eraser) return color;
  const hsv = colorToHsv(color);
  const spread = () => Math.random() * 2 - 1;
  hsv[0] = (hsv[0] + spread() * 24 * kind.strokeJitter + 360) % 360;
  hsv[1] = Math.min(1, Math.max(0, hsv[1] + spread() * 0.14 * kind.strokeJitter));
  hsv[2] = Math.min(1, Math.max(0, hsv[2] + spread() * 0.14 * kind.strokeJitter));
  return hsvToColor(hsv);
}

function beginStroke(kind) {
  const settings = settingsFor(kind);
  const rgb = hexToRgb(strokeColor(kind));
  if (kind.tip) engine.HEAPU8.set(kind.tip, tipPointer);
  engine._cap_begin_stroke(
    settings.diameter, rgb[0], rgb[1], rgb[2],
    stampFlow(kind, settings.opacity), kind.minScale, kind.spacing, kind.pressureFlow,
    kind.hardness, kind.grain, kind.eraser ? 1 : 0, kind.mode === "smudge" ? 1 : 0,
    pressureGamma(kind), kind.scatter, kind.sizeJitter, kind.angleJitter, kind.shadeJitter,
    kind.hueJitter, kind.tip ? 1 : 0,
  );
  strokeKind = kind;
  stabilizerFollow = kind.eraser ? 1 : 1 - MAX_SMOOTHING * settings.stabilizer;
  stabilizerStarted = false;
  strokeHadSamples = false;
}

// Writes one pointer sample, in canvas pixels, into the engine's buffer at slot.
function writeSample(offset, slot, event, stabilize) {
  const heap = engine.HEAPF32;
  let x = toCanvasX(event.clientX * dpr);
  let y = toCanvasY(event.clientY * dpr);
  if (stabilize) {
    if (!stabilizerStarted) {
      stabilizedX = x;
      stabilizedY = y;
      stabilizerStarted = true;
    } else {
      stabilizedX += (x - stabilizedX) * stabilizerFollow;
      stabilizedY += (y - stabilizedY) * stabilizerFollow;
    }
    x = stabilizedX;
    y = stabilizedY;
  }
  let tilt = 0;
  let azimuth = 0;
  if (event.pointerType === "pen") {
    if (event.altitudeAngle !== undefined) {
      tilt = Math.PI / 2 - event.altitudeAngle;
      azimuth = event.azimuthAngle;
    } else if (event.tiltX || event.tiltY) {
      const tx = Math.tan(event.tiltX * Math.PI / 180);
      const ty = Math.tan(event.tiltY * Math.PI / 180);
      tilt = Math.atan(Math.hypot(tx, ty));
      azimuth = Math.atan2(ty, tx);
    }
  }
  // A mouse draws like a relaxed pen. Browsers report 0.5 for it, but not all of them do.
  let pressure = event.pointerType === "mouse" ? 0.5 : event.pressure;
  if (pressure === 0) pressure = 0.01;
  const i = offset + slot * SAMPLE_FLOATS;
  heap[i] = x;
  heap[i + 1] = y;
  heap[i + 2] = pressure;
  heap[i + 3] = tilt;
  heap[i + 4] = azimuth;
  lastPressure = pressure;
  lastTilt = tilt;
}

function drawSamples(event, end) {
  const coalesced = event.getCoalescedEvents ? event.getCoalescedEvents() : null;
  // Like Android, a stabilized line skips prediction, which would run ahead of the smoothing.
  const predicted = !end && stabilizerFollow >= 1 && event.getPredictedEvents ? event.getPredictedEvents() : null;
  let count = 0;
  if (coalesced && coalesced.length > 0) {
    const n = Math.min(coalesced.length, MAX_SAMPLES);
    for (let i = 0; i < n; i++) writeSample(samplesOffset, count++, coalesced[i], true);
  } else {
    writeSample(samplesOffset, count++, event, true);
  }
  let predictedCount = 0;
  if (predicted) {
    const n = Math.min(predicted.length, MAX_SAMPLES);
    for (let i = 0; i < n; i++) writeSample(predictedOffset, predictedCount++, predicted[i], false);
  }

  const start = performance.now();
  engine._cap_draw(count, predictedCount, end ? 1 : 0);
  const done = performance.now();

  strokeHadSamples = true;
  sampleCount += count;
  lastCoalesced = count;
  lastPredicted = predictedCount;
  if (statsOn) {
    drawIndex = record(drawTimes, drawIndex, done - start);
    inputIndex = record(inputLatencies, inputIndex, done - event.timeStamp);
    pendingEventTime = event.timeStamp;
    if (done - sampleWindowStart > 1000) {
      sampleRate = sampleCount * 1000 / (done - sampleWindowStart);
      sampleCount = 0;
      sampleWindowStart = done;
    }
  }
}

function endStroke() {
  // The lift itself carries no pressure on most pens, so it only closes the stroke.
  engine._cap_draw(0, 0, 1);
  strokePointer = -1;
  markUnsaved();
  queueCompose();  // Clears any predicted ink left past the real end.
}

// --- Gradient and lasso fill -------------------------------------------------------------------

function beginToolGesture(kind, event) {
  const x = toCanvasX(event.clientX * dpr);
  const y = toCanvasY(event.clientY * dpr);
  toolGesture = { kind, startX: x, startY: y, count: 0 };
  if (kind.mode === "lasso") addLassoPoint(event);
}

function moveToolGesture(event) {
  if (toolGesture.kind.mode === "gradient") {
    const rgb = hexToRgb(color);
    engine._cap_set_gradient(1, toolGesture.startX, toolGesture.startY,
      toCanvasX(event.clientX * dpr), toCanvasY(event.clientY * dpr), rgb[0], rgb[1], rgb[2]);
    queueCompose();
    return;
  }
  const events = event.getCoalescedEvents ? event.getCoalescedEvents() : [event];
  for (const sample of events.length ? events : [event]) addLassoPoint(sample);
  drawLasso();
}

function addLassoPoint(event) {
  if (toolGesture.count >= MAX_POINTS) return;
  const heap = engine.HEAPF32;
  const i = pointsOffset + toolGesture.count * 2;
  heap[i] = toCanvasX(event.clientX * dpr);
  heap[i + 1] = toCanvasY(event.clientY * dpr);
  toolGesture.count++;
}

// Shows the lasso as you draw it, on a plain 2D canvas over the artwork.
function drawLasso() {
  const c = overlayContext;
  c.clearRect(0, 0, overlay.width, overlay.height);
  if (!toolGesture || toolGesture.count < 2) return;
  const heap = engine.HEAPF32;
  const sx = flipped ? -zoom : zoom;
  c.beginPath();
  for (let n = 0; n < toolGesture.count; n++) {
    const x = heap[pointsOffset + n * 2] * sx + panX;
    const y = heap[pointsOffset + n * 2 + 1] * zoom + panY;
    if (n === 0) c.moveTo(x, y); else c.lineTo(x, y);
  }
  c.closePath();
  c.fillStyle = color + "55";
  c.fill();
  c.lineWidth = 2 * dpr;
  c.strokeStyle = TEXT_COLOR;
  c.setLineDash([6 * dpr, 5 * dpr]);
  c.stroke();
}

function endToolGesture(cancelled) {
  const gesture = toolGesture;
  toolGesture = null;
  if (!cancelled) markUnsaved();
  if (gesture.kind.mode === "gradient") {
    if (cancelled) {
      engine._cap_set_gradient(0, 0, 0, 0, 0, 0, 0, 0);
    } else {
      engine._cap_commit_gradient();
    }
  } else {
    overlayContext.clearRect(0, 0, overlay.width, overlay.height);
    if (!cancelled && gesture.count >= 3) {
      const rgb = hexToRgb(color);
      engine._cap_fill_polygon(gesture.count, rgb[0], rgb[1], rgb[2], settingsFor(gesture.kind).opacity);
    }
  }
  queueCompose();
}

// --- Pointer input -----------------------------------------------------------------------------

const touches = new Map();  // pointerId -> {x, y} in buffer pixels, for pan and zoom.
let gesture = null;
let tapStart = 0;
let tapTravel = 0;
let tapFingers = 0;
let panPointer = -1;  // Middle button or space + drag.
let panLastX = 0;
let panLastY = 0;
let spaceHeld = false;

function capture(event) {
  try {
    canvas.setPointerCapture(event.pointerId);
  } catch {
    // Synthetic events from tests have no real pointer to capture.
  }
}

canvas.addEventListener("pointerdown", (event) => {
  if (!ready) return;
  lastPointerType = event.pointerType;
  closePanel();
  if (event.pointerType === "touch") {
    if (strokePointer !== -1 || toolGesture) return;  // Palm on the screen while the pen draws.
    capture(event);
    if (touches.size === 0) {
      tapStart = event.timeStamp;
      tapTravel = 0;
      tapFingers = 0;
    }
    touches.set(event.pointerId, { x: event.clientX * dpr, y: event.clientY * dpr });
    tapFingers = Math.max(tapFingers, touches.size);
    gesture = null;
    return;
  }
  if (event.button === 1 || (event.button === 0 && spaceHeld)) {
    panPointer = event.pointerId;
    panLastX = event.clientX * dpr;
    panLastY = event.clientY * dpr;
    capture(event);
    canvas.style.cursor = "grabbing";
    event.preventDefault();
    return;
  }
  // Pen barrel buttons report other buttons; only the tip and the eraser end draw.
  if (event.button !== 0 || strokePointer !== -1 || toolGesture) return;
  const eraserEnd = event.pointerType === "pen" && (event.buttons & 32) !== 0;
  const kind = eraserEnd ? lastEraser : brush;
  capture(event);
  if (kind.mode === "fill") {
    const rgb = hexToRgb(color);
    engine._cap_fill_at(Math.floor(toCanvasX(event.clientX * dpr)), Math.floor(toCanvasY(event.clientY * dpr)),
      rgb[0], rgb[1], rgb[2]);
    queueCompose();
    markUnsaved();
    return;
  }
  if (kind.mode === "gradient" || kind.mode === "lasso") {
    strokePointer = event.pointerId;
    beginToolGesture(kind, event);
    return;
  }
  strokePointer = event.pointerId;
  companion.setMood("thinking");
  beginStroke(kind);
  drawSamples(event, false);
});

canvas.addEventListener("pointermove", (event) => {
  if (event.pointerId === strokePointer) {
    if (toolGesture) moveToolGesture(event); else drawSamples(event, false);
    return;
  }
  if (event.pointerId === panPointer) {
    const x = event.clientX * dpr;
    const y = event.clientY * dpr;
    panX += x - panLastX;
    panY += y - panLastY;
    panLastX = x;
    panLastY = y;
    writeView();
    queueCompose();
    return;
  }
  const touch = touches.get(event.pointerId);
  if (!touch) return;
  const x = event.clientX * dpr;
  const y = event.clientY * dpr;
  tapTravel += Math.hypot(x - touch.x, y - touch.y) / dpr;
  touch.x = x;
  touch.y = y;
  moveGesture();
});

function finishPointer(event, cancelled) {
  if (event.pointerId === strokePointer) {
    strokePointer = -1;
    if (toolGesture) {
      endToolGesture(cancelled);
    } else if (cancelled) {
      engine._cap_cancel_stroke();
      queueCompose();
    } else {
      endStroke();
    }
    companion.setMood("idle");
    return;
  }
  if (event.pointerId === panPointer) {
    panPointer = -1;
    canvas.style.cursor = spaceHeld ? "grab" : "";
    return;
  }
  if (!touches.delete(event.pointerId)) return;
  gesture = null;
  if (touches.size > 0 || cancelled) return;
  // Taps with two, three, and four fingers undo, redo, and hide the interface, as on Android.
  const isTap = tapTravel < TAP_TRAVEL && event.timeStamp - tapStart < TAP_TIMEOUT_MS;
  if (isTap && tapFingers === 2) undo();
  if (isTap && tapFingers === 3) redo();
  if (isTap && tapFingers === 4) toggleInterface();
}
canvas.addEventListener("pointerup", (event) => finishPointer(event, false));
canvas.addEventListener("pointercancel", (event) => finishPointer(event, true));
canvas.addEventListener("lostpointercapture", (event) => {
  if (event.pointerId === strokePointer) finishPointer(event, false);
});

// One finger pans; two pan and pinch around their midpoint.
function moveGesture() {
  let x = 0, y = 0, n = 0;
  let ax = 0, ay = 0, bx = 0, by = 0;
  for (const touch of touches.values()) {
    if (n === 0) { ax = touch.x; ay = touch.y; } else if (n === 1) { bx = touch.x; by = touch.y; }
    x += touch.x;
    y += touch.y;
    n++;
    if (n === 2) break;
  }
  if (n === 0) return;
  x /= n;
  y /= n;
  const span = n === 2 ? Math.hypot(bx - ax, by - ay) : 0;
  if (gesture && gesture.count === n) {
    panX += x - gesture.x;
    panY += y - gesture.y;
    if (n === 2 && gesture.span > 0) zoomAt(x, y, span / gesture.span);
    writeView();
    queueCompose();
  } else {
    gesture = gesture || { x: 0, y: 0, span: 0, count: 0 };
  }
  gesture.x = x;
  gesture.y = y;
  gesture.span = span;
  gesture.count = n;
}

// A wheel zooms around the cursor; a trackpad's two-finger scroll pans and its pinch zooms.
canvas.addEventListener("wheel", (event) => {
  event.preventDefault();
  const x = event.clientX * dpr;
  const y = event.clientY * dpr;
  const lines = event.deltaMode === 1;
  const pixelScroll = !lines && !event.ctrlKey && event.deltaX !== 0;
  if (pixelScroll || (event.shiftKey && !event.ctrlKey)) {
    panX -= (event.shiftKey ? event.deltaY : event.deltaX) * dpr;
    panY -= (event.shiftKey ? 0 : event.deltaY) * dpr;
    writeView();
    queueCompose();
    return;
  }
  const delta = lines ? event.deltaY * 16 : event.deltaY;
  zoomAt(x, y, Math.exp(-delta * (event.ctrlKey ? 0.01 : 0.002)));
}, { passive: false });

document.addEventListener("contextmenu", (event) => event.preventDefault());

// --- History -----------------------------------------------------------------------------------

function undo() {
  if (strokePointer !== -1 || !ready) return;
  if (engine._cap_undo()) markUnsaved();
  queueCompose();
}

function redo() {
  if (strokePointer !== -1 || !ready) return;
  if (engine._cap_redo()) markUnsaved();
  queueCompose();
}

// --- Keyboard, as MainActivity.onKeyDown ------------------------------------------------------

window.addEventListener("keydown", (event) => {
  if (studioView.hidden || !ready) return;
  if (event.target instanceof HTMLInputElement) return;
  const command = event.ctrlKey || event.metaKey;
  const key = event.key.toLowerCase();
  if (command && key === "z") {
    event.preventDefault();
    if (event.shiftKey) redo(); else undo();
  } else if (command && key === "y") {
    event.preventDefault();
    redo();
  } else if (command) {
    return;
  } else if (event.key === " ") {
    event.preventDefault();
    spaceHeld = true;
    if (panPointer === -1) canvas.style.cursor = "grab";
  } else if (key === "b") {
    closePanel();
    selectTool(lastBrush);
  } else if (key === "e") {
    closePanel();
    selectTool(lastEraser);
  } else if (event.key === "[") {
    resizeBrush(1 / KEY_SIZE_STEP);
  } else if (event.key === "]") {
    resizeBrush(KEY_SIZE_STEP);
  } else if (event.key === "Tab") {
    event.preventDefault();
    toggleInterface();
  } else if (event.key === "Escape") {
    closePanel();
  } else if (event.key === "0") {
    fitView();
  } else if (event.key === "`") {
    setStats(!statsOn);
  }
});

window.addEventListener("keyup", (event) => {
  if (studioView.hidden) return;
  if (event.key === " ") {
    spaceHeld = false;
    if (panPointer === -1) canvas.style.cursor = "";
  }
});

// --- Chrome: the two toolbars, the sliders, and recent colors ----------------------------------

const brushButton = document.getElementById("brush-button");
const eraserButton = document.getElementById("eraser-button");
const toolsButton = document.getElementById("tools-button");
const colorButton = document.getElementById("color-button");
const chrome = [document.getElementById("left"), document.getElementById("right"),
  document.getElementById("sliders"), document.getElementById("recent"), document.getElementById("companion")];

brushButton.addEventListener("click", () => {
  if (brush.eraser || isTool(brush)) {
    closePanel();
    selectTool(lastBrush);
  } else {
    togglePanel("brushes", brushPanel);
  }
});

eraserButton.addEventListener("click", () => {
  if (brush.eraser) {
    togglePanel("erasers", eraserPanel);
  } else {
    closePanel();
    selectTool(lastEraser);
  }
});

colorButton.addEventListener("click", () => {
  if (panelKey !== "color") colorBeforePanel = color;
  togglePanel("color", colorPanel);
});

for (const button of document.querySelectorAll("[data-action]")) {
  const action = button.dataset.action;
  button.addEventListener("click", () => {
    if (action === "projects") leaveStudio();
    else if (action === "view") togglePanel("view", viewPanel);
    else if (action === "tools") togglePanel("tools", toolsPanel);
    else togglePanel(action, () => comingPanel(button.getAttribute("aria-label")));
  });
}

function selectTool(kind) {
  brush = kind;
  if (kind.eraser) lastEraser = kind;
  else if (!isTool(kind)) lastBrush = kind;
  sizeSlider.set(sizeProgress());
  opacitySlider.set(Math.round(settingsFor(kind).opacity * 1000));
  brushButton.classList.toggle("on", !kind.eraser && !isTool(kind));
  eraserButton.classList.toggle("on", kind.eraser);
  toolsButton.classList.toggle("on", isTool(kind));
  const icon = { smudge: "smudge", fill: "fill", gradient: "gradient", lasso: "lasso_fill" }[kind.mode];
  toolsButton.innerHTML = ICONS[isTool(kind) ? icon : "select"];
}

function setColor(next) {
  color = next.toUpperCase();
  colorButton.firstElementChild.style.background = color;
  companion.setColor(color);
  if (brush.eraser) selectTool(lastBrush);
}

function rememberColor() {
  const index = recentColors.indexOf(color);
  if (index >= 0) recentColors.splice(index, 1);
  recentColors.unshift(color);
  if (recentColors.length > 8) recentColors.pop();
  showRecentColors();
}

const recentStrip = document.getElementById("recent");
const recentDots = [...recentStrip.children];
recentDots.forEach((dot, index) => dot.addEventListener("click", () => {
  if (recentColors[index]) setColor(recentColors[index]);
}));

function showRecentColors() {
  recentStrip.style.visibility = recentColors.length === 0 ? "hidden" : "visible";
  recentDots.forEach((dot, index) => {
    const value = recentColors[index];
    dot.classList.toggle("empty", !value);
    if (value) dot.style.background = value;
  });
}

function sizeProgress() {
  const share = (settingsFor(brush).diameter - MIN_BRUSH) / (MAX_BRUSH - MIN_BRUSH);
  return Math.round(Math.sqrt(Math.max(0, share)) * 1000);
}

function resizeBrush(factor) {
  const settings = settingsFor(brush);
  settings.diameter = Math.min(MAX_BRUSH, Math.max(MIN_BRUSH, settings.diameter * factor));
  sizeSlider.set(sizeProgress());
}

// A vertical slider from 0 to 1000, drawn like Android's rotated SeekBar.
function verticalSlider(element, change) {
  const fill = element.querySelector(".fill");
  const thumb = element.querySelector(".thumb");
  let value = 0;
  const travel = () => element.clientHeight - 40;
  const set = (next) => {
    value = Math.min(1000, Math.max(0, Math.round(next)));
    const offset = 20 + travel() * value / 1000;
    fill.style.height = `${offset - 20}px`;
    thumb.style.bottom = `${offset}px`;
    element.setAttribute("aria-valuenow", String(value));
  };
  const pick = (event) => {
    const box = element.getBoundingClientRect();
    set((box.bottom - 20 - event.clientY) / travel() * 1000);
    change(value);
  };
  element.addEventListener("pointerdown", (event) => {
    element.setPointerCapture(event.pointerId);
    pick(event);
  });
  element.addEventListener("pointermove", (event) => {
    if (element.hasPointerCapture(event.pointerId)) pick(event);
  });
  element.addEventListener("keydown", (event) => {
    const step = event.key === "ArrowUp" || event.key === "ArrowRight" ? 20
      : event.key === "ArrowDown" || event.key === "ArrowLeft" ? -20 : 0;
    if (!step) return;
    event.preventDefault();
    set(value + step);
    change(value);
  });
  return { set };
}

const sizeSlider = verticalSlider(document.getElementById("size-slider"), (value) => {
  const share = value / 1000;
  settingsFor(brush).diameter = MIN_BRUSH + (MAX_BRUSH - MIN_BRUSH) * share * share;
});
const opacitySlider = verticalSlider(document.getElementById("opacity-slider"), (value) => {
  settingsFor(brush).opacity = Math.max(value, 10) / 1000;
});

function placeRecentStrip() {
  const sliders = document.getElementById("sliders");
  const top = window.innerHeight / 2 + sliders.offsetHeight / 2 + 8;
  recentStrip.style.top = `${top}px`;
}

function toggleInterface() {
  interfaceHidden = !interfaceHidden;
  closePanel();
  for (const element of chrome) element.hidden = interfaceHidden;
}

// --- Panels, as MainActivity.togglePanel and showPanel ------------------------------------------

let panelKey = "";
let panelBuilder = null;

function togglePanel(key, build) {
  const reopen = panelKey !== key;
  closePanel();
  if (reopen) showPanel(key, build);
}

function showPanel(key, build) {
  panelKey = key;
  panelBuilder = build;
  panel.className = key === "companion" ? "bubble" : LEFT_PANELS.has(key) ? "left" : "right";
  panelBody.replaceChildren(build());
  panel.hidden = false;
  if (key === "companion") placeBubble();
}

// Builds the open panel again in place, keeping its scroll.
function rerender() {
  if (!panelBuilder) return;
  const scroll = panel.scrollTop;
  panelBody.replaceChildren(panelBuilder());
  panel.scrollTop = scroll;
}

function closePanel() {
  if (!panelKey) return;
  if (panelKey === "color") rememberColor();
  panel.hidden = true;
  panelKey = "";
  panelBuilder = null;
}

const gap = (element) => {
  element.classList.add("gap");
  return element;
};

function segmented(options, selected, pick) {
  return h("div", { className: "segmented", role: "tablist" },
    options.map((option, index) => h("button", {
      className: index === selected ? "on" : "",
      role: "tab",
      ariaSelected: String(index === selected),
      onClick: () => pick(index),
    }, option)));
}

function smallChip(text, selected, click) {
  return h("button", { className: selected ? "chip on" : "chip", onClick: click }, text);
}

function toolSlider(name, value, change) {
  return h("div", { className: "column" },
    gap(h("div", { className: "small" }, name)),
    h("input", {
      className: "range", type: "range", min: "0", max: "100", value: String(value), ariaLabel: name,
      onInput: (event) => change(Number(event.target.value)),
    }));
}

function comingPanel(title) {
  return h("div", { className: "column" },
    h("div", { className: "title" }, title),
    gap(h("div", { className: "caption" }, "Coming to the web version soon.")));
}

// --- Brushes and erasers --------------------------------------------------------------------

function brushFamilies() {
  const all = BRUSH_FAMILIES.flatMap(([, kinds]) => kinds);
  const families = [];
  if (favorites.size > 0) families.push(["★", all.filter((kind) => favorites.has(kind.id))]);
  return families.concat(BRUSH_FAMILIES);
}

function brushPanel() {
  const families = brushFamilies();
  const family = families.find(([name]) => name === brushTab)
    || families.find(([, kinds]) => kinds.includes(brush))
    || families[0];
  const previewColor = luminance(color) < 0.08 ? TEXT_COLOR : color;
  const settings = settingsFor(brush);
  const body = h("div", { className: "column" },
    h("div", { className: "row header" },
      h("div", { className: "title grow" }, "Brushes"),
      h("button", {
        className: "icon", ariaLabel: "New brush", innerHTML: ICONS.add,
        onClick: () => say("Your own brushes come to the web soon"),
      })),
    segmented(families.map(([name]) => name), families.indexOf(family), (index) => {
      brushTab = families[index][0];
      rerender();
    }),
    gap(toolGrid(family[1], previewColor, true)),
    gap(h("button", {
      className: "feel",
      onClick: () => {
        brushFeelOpen = !brushFeelOpen;
        rerender();
      },
    }, `${brushFeelOpen ? "▾" : "▸"} Brush feel`)));
  if (brushFeelOpen) {
    body.append(
      toolSlider("Stabilizer", Math.round(settings.stabilizer * 100), (value) => {
        settings.stabilizer = value / 100;
      }),
      toolSlider("Pressure: soft to firm", Math.round((pressureGamma(brush) - 0.4) / 1.2 * 100), (value) => {
        savePreference(`pressure-${brush.id}`, 0.4 + value / 100 * 1.2);
      }),
    );
  }
  body.append(gap(h("div", { className: "small" }, "Hold a brush to add it to ★.")));
  return body;
}

function eraserPanel() {
  return h("div", { className: "column" },
    h("div", { className: "title" }, "Erasers"),
    gap(toolGrid(ERASERS, TEXT_COLOR, false)),
    gap(h("div", { className: "caption" },
      "Every brush shape can erase, and each one remembers its own size and opacity.")));
}

function toolGrid(kinds, previewColor, favoritable) {
  return h("div", { className: "grid" }, kinds.map((kind) => {
    const preview = h("canvas", { width: 130 * dpr, height: 48 * dpr, ariaLabel: `${kind.label} preview` });
    drawBrushPreview(preview, kind, previewColor);
    let held = 0;
    let holdTimer = 0;
    const card = h("button", {
      className: brush === kind ? "card on" : "card",
      onPointerdown: () => {
        if (!favoritable) return;
        held = 0;
        holdTimer = setTimeout(() => {
          held = 1;
          toggleFavorite(kind);
        }, 500);
      },
      onPointerup: () => clearTimeout(holdTimer),
      onPointerleave: () => clearTimeout(holdTimer),
      onClick: () => {
        if (held) return;
        selectTool(kind);
        closePanel();
      },
    }, preview, favorites.has(kind.id) && favoritable ? `★ ${kind.label}` : kind.label);
    return card;
  }));
}

function toggleFavorite(kind) {
  if (!favorites.delete(kind.id)) favorites.add(kind.id);
  savePreference("favorites", [...favorites]);
  rerender();
}

const tintedTips = new Map();

// Paints a sample stroke with a rough match of the brush, as BrushPreview in Ui.kt does.
function drawBrushPreview(element, kind, tint) {
  const c = element.getContext("2d");
  const width = element.width;
  const height = element.height;
  const random = new JavaRandom(javaHash(kind.id));
  const scattered = kind.scatter > 0;
  const maxRadius = height * (scattered ? 0.1 : 0.22);
  const softness = (1 - kind.hardness) * maxRadius;
  c.filter = softness > 0.5 ? `blur(${softness * 0.57735 + 0.5}px)` : "none";
  const steps = scattered ? 36 : 90;
  const spread = Math.min(kind.scatter, 0.5) * height;
  const stamp = kind.tip ? tintedTip(kind, tint) : null;
  c.fillStyle = tint;
  for (let step = 0; step <= steps; step++) {
    const t = step / steps;
    const pressure = Math.sin(t * Math.PI);
    const scale = kind.minScale + (1 - kind.minScale) * pressure;
    const x = width * (0.1 + 0.8 * t) + spread * (random.nextFloat() - 0.5);
    const y = height * (0.5 + 0.26 * Math.sin(t * 2 * Math.PI)) + spread * (random.nextFloat() - 0.5);
    const grainHole = kind.grain * 0.55 > random.nextFloat();
    if (grainHole) continue;
    const flow = kind.flow * (1 - kind.pressureFlow * (1 - pressure));
    c.globalAlpha = Math.floor(255 * Math.min(1, flow * 1.6 + 0.05)) / 255;
    const radius = Math.max(1.5, maxRadius * scale * (1 - kind.sizeJitter * 0.5 * random.nextFloat()));
    if (stamp) {
      c.drawImage(stamp, x - radius, y - radius, radius * 2, radius * 2);
    } else {
      c.beginPath();
      c.arc(x, y, radius, 0, Math.PI * 2);
      c.fill();
    }
  }
}

function tintedTip(kind, tint) {
  const key = `${kind.id}/${tint}`;
  let tip = tintedTips.get(key);
  if (tip) return tip;
  tip = document.createElement("canvas");
  tip.width = TIP_SIZE;
  tip.height = TIP_SIZE;
  const c = tip.getContext("2d");
  const pixels = c.createImageData(TIP_SIZE, TIP_SIZE);
  const [r, g, b] = hexToRgb(tint).map((v) => Math.round(v * 255));
  for (let i = 0; i < kind.tip.length; i++) {
    pixels.data[i * 4] = r;
    pixels.data[i * 4 + 1] = g;
    pixels.data[i * 4 + 2] = b;
    pixels.data[i * 4 + 3] = kind.tip[i];
  }
  c.putImageData(pixels, 0, 0);
  tintedTips.set(key, tip);
  return tip;
}

// --- Tools ---------------------------------------------------------------------------------

function toolCard(icon, title, detail, selected, click, available = true) {
  return h("button", {
    className: `tool-card${selected ? " on" : ""}${available ? "" : " off"}`,
    onClick: available ? click : () => say("Selections come to the web soon"),
  }, svg(icon), h("div", { className: "name" }, title), h("div", { className: "detail" }, detail));
}

function toolsPanel() {
  const pick = (kind) => () => {
    selectTool(kind);
    closePanel();
  };
  return h("div", { className: "column" },
    h("div", { className: "title" }, "Tools"),
    gap(h("div", { className: "tools" },
      toolCard("smudge", "Smudge", "Blend colors", brush === TOOLS.smudge, pick(TOOLS.smudge)),
      toolCard("fill", "Fill", "Fill an area", brush === TOOLS.fill, pick(TOOLS.fill)),
      toolCard("gradient", "Gradient", "Fade a color", brush === TOOLS.gradient, pick(TOOLS.gradient)),
      toolCard("lasso_fill", "Lasso fill", "Fill a shape you draw", brush === TOOLS.lassoFill, pick(TOOLS.lassoFill)))),
    gap(h("div", { className: "caption" }, "Selection")),
    gap(h("div", { className: "tools" },
      toolCard("select", "Lasso", "Draw around an area", false, null, false),
      toolCard("wand", "Magic wand", "Pick a color area", false, null, false))),
    gap(h("div", { className: "caption" },
      "Make a selection to keep painting inside it, or to move part of a layer.")));
}

// --- Art direction ---------------------------------------------------------------------------

function viewPanel() {
  const apply = () => {
    engine._cap_set_view_options(viewMode, guide);
    queueCompose();
  };
  return h("div", { className: "column" },
    h("div", { className: "title" }, "Art direction"),
    gap(h("div", { className: "small" }, "Check your image")),
    segmented(["Color", "Gray", "2 values", "3 values", "Print"], viewMode, (index) => {
      viewMode = index;
      apply();
      rerender();
    }),
    viewMode === 4 && gap(h("div", { className: "small" },
      "A close guide to CMYK print. Real results depend on the printer and paper.")),
    gap(h("div", { className: "small" }, "Composition")),
    gap(h("div", { className: "chips" }, GUIDES.slice(0, 5).map((name, index) =>
      smallChip(name === "None" ? "Off" : name, guide === index, () => {
        guide = index;
        apply();
        rerender();
      })))),
    gap(h("div", { className: "small" }, "Symmetry")),
    segmented(["Off", "↔", "↕", "✛", "✺"], symmetry, (index) => {
      symmetry = index;
      engine._cap_set_symmetry(index, -1, -1);
      if (doc) {
        doc.manifest.symmetry = index;
        doc.manifest.symmetryCenter = [canvasWidth / 2, canvasHeight / 2];
        markUnsaved();
      }
      queueCompose();
      rerender();
    }),
    gap(h("button", { className: "action-row", onClick: flipCanvas },
      svg("flip"),
      h("div", { className: "text" }, h("div", {}, "Flip canvas"),
        h("div", { className: "detail" }, "A fresh look at your drawing")))),
    gap(h("div", { className: "small" }, "These change your view only, never your artwork.")));
}

// --- Color, as MainActivity.colorPanel and ColorPicker.kt ----------------------------------------

function colorToHsv(hex) {
  const [r, g, b] = hexToRgb(hex);
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;
  let hue = 0;
  if (delta > 0) {
    if (max === r) hue = ((g - b) / delta) % 6;
    else if (max === g) hue = (b - r) / delta + 2;
    else hue = (r - g) / delta + 4;
    hue *= 60;
    if (hue < 0) hue += 360;
  }
  return [hue, max === 0 ? 0 : delta / max, max];
}

function hsvToColor([hue, saturation, value]) {
  const chroma = value * saturation;
  const part = chroma * (1 - Math.abs(((hue / 60) % 2) - 1));
  const sector = Math.floor(hue / 60) % 6;
  const [r, g, b] = [
    [chroma, part, 0], [part, chroma, 0], [0, chroma, part],
    [0, part, chroma], [part, 0, chroma], [chroma, 0, part],
  ][sector].map((channel) => Math.round((channel + value - chroma) * 255));
  return `#${((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1).toUpperCase()}`;
}

function luminance(hex) {
  const linear = (s) => (s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4));
  const [r, g, b] = hexToRgb(hex);
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

function rotateHue(hsv, degrees) {
  return hsvToColor([(hsv[0] + degrees + 360) % 360, hsv[1], hsv[2]]);
}

function harmoniesFor(base) {
  const hsv = colorToHsv(base);
  return [
    ["Complementary", [base, rotateHue(hsv, 180)]],
    ["Analogous", [rotateHue(hsv, -30), base, rotateHue(hsv, 30)]],
    ["Split complementary", [base, rotateHue(hsv, 150), rotateHue(hsv, 210)]],
    ["Triadic", [base, rotateHue(hsv, 120), rotateHue(hsv, 240)]],
  ];
}

function valueScale(base, steps = 7) {
  const hsv = colorToHsv(base);
  return Array.from({ length: steps }, (_, step) => {
    const t = (step + 1) / (steps + 1);
    return hsvToColor([hsv[0], hsv[1] * (1 - t * 0.6), t]);
  });
}

function swatchRow(colors, short = false) {
  return h("div", { className: short ? "swatches short" : "swatches" }, colors.map((value) =>
    h("button", {
      ariaLabel: "Color swatch",
      style: { background: value },
      onClick: () => {
        setColor(value);
        rerender();
      },
    })));
}

function colorPanel() {
  const body = h("div", { className: "column" },
    segmented(["Picker", "Harmony", "Palettes"], colorTab, (index) => {
      colorTab = index;
      rerender();
    }));
  if (colorTab === 0) {
    const current = h("span", { className: "swatch", style: { background: color } });
    const hex = h("span", { className: "hex" }, color);
    body.append(gap(h("div", { className: "compare" },
      h("button", {
        className: "swatch", ariaLabel: "Previous color", style: { background: colorBeforePanel },
        onClick: () => {
          setColor(colorBeforePanel);
          rerender();
        },
      }),
      current, hex)));
    body.append(gap(colorPicker((value) => {
      setColor(value);
      current.style.background = value;
      hex.textContent = value;
    })));
    body.append(gap(h("div", { className: "caption" }, "Values of this hue")), swatchRow(valueScale(color)));
    if (recentColors.length > 0) {
      body.append(gap(h("div", { className: "caption" }, "Recent")), swatchRow(recentColors, true));
    }
  } else if (colorTab === 1) {
    for (const [name, colors] of harmoniesFor(color)) {
      body.append(gap(h("div", { className: "caption" }, name)), swatchRow(colors));
    }
  } else {
    for (const [name, colors] of STARTER_PALETTES) {
      body.append(gap(h("div", { className: "caption" }, name)), swatchRow(colors));
    }
  }
  return body;
}

// A saturation and value square above a hue bar.
function colorPicker(pick) {
  const hsv = colorToHsv(color);
  const element = h("canvas", { ariaLabel: "Color picker" });
  const holder = h("div", { className: "picker" }, element);
  const barHeight = 28;
  const gapHeight = 12;
  let draggingHue = false;
  const draw = () => {
    const width = holder.clientWidth;
    const height = holder.clientHeight;
    if (!width) return;
    element.width = width * dpr;
    element.height = height * dpr;
    const c = element.getContext("2d");
    c.scale(dpr, dpr);
    const square = height - barHeight - gapHeight;
    let gradient = c.createLinearGradient(0, 0, width, 0);
    gradient.addColorStop(0, "#FFFFFF");
    gradient.addColorStop(1, hsvToColor([hsv[0], 1, 1]));
    c.fillStyle = gradient;
    c.fillRect(0, 0, width, square);
    gradient = c.createLinearGradient(0, 0, 0, square);
    gradient.addColorStop(0, "rgba(0,0,0,0)");
    gradient.addColorStop(1, "#000000");
    c.fillStyle = gradient;
    c.fillRect(0, 0, width, square);
    gradient = c.createLinearGradient(0, 0, width, 0);
    for (let n = 0; n <= 6; n++) gradient.addColorStop(n / 6, hsvToColor([(n * 60) % 360, 1, 1]));
    c.fillStyle = gradient;
    c.fillRect(0, height - barHeight, width, barHeight);
    c.strokeStyle = "#FFFFFF";
    c.lineWidth = 2;
    c.beginPath();
    c.arc(hsv[1] * width, (1 - hsv[2]) * square, gapHeight * 0.7, 0, Math.PI * 2);
    c.stroke();
    c.beginPath();
    c.arc(hsv[0] / 360 * width, height - barHeight / 2, gapHeight * 0.7, 0, Math.PI * 2);
    c.stroke();
  };
  const move = (event) => {
    const box = holder.getBoundingClientRect();
    const square = box.height - barHeight - gapHeight;
    const x = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    if (draggingHue) {
      hsv[0] = x * 359.9;
    } else {
      hsv[1] = x;
      hsv[2] = 1 - Math.min(1, Math.max(0, (event.clientY - box.top) / square));
    }
    draw();
    pick(hsvToColor(hsv));
  };
  holder.addEventListener("pointerdown", (event) => {
    holder.setPointerCapture(event.pointerId);
    const box = holder.getBoundingClientRect();
    draggingHue = event.clientY - box.top > box.height - barHeight - gapHeight;
    move(event);
  });
  holder.addEventListener("pointermove", (event) => {
    if (holder.hasPointerCapture(event.pointerId)) move(event);
  });
  requestAnimationFrame(draw);
  return holder;
}

// --- Companion ---------------------------------------------------------------------------------

const companionCanvas = document.getElementById("companion");
const companion = new Companion(companionCanvas);
let companionX = loadPreference("companionX", null);
let companionY = loadPreference("companionY", null);

function placeCompanion() {
  const size = 56;
  const x = companionX === null ? 12 : Math.min(Math.max(0, companionX), window.innerWidth - size);
  const y = companionY === null ? window.innerHeight - size - 12 : Math.min(Math.max(0, companionY), window.innerHeight - size);
  companionCanvas.style.left = `${x}px`;
  companionCanvas.style.top = `${y}px`;
  companionCanvas.style.bottom = "auto";
  return [x, y];
}

{
  let startX = 0, startY = 0, originX = 0, originY = 0, dragged = false;
  companionCanvas.addEventListener("pointerdown", (event) => {
    companionCanvas.setPointerCapture(event.pointerId);
    [originX, originY] = placeCompanion();
    startX = event.clientX;
    startY = event.clientY;
    dragged = false;
  });
  companionCanvas.addEventListener("pointermove", (event) => {
    if (!companionCanvas.hasPointerCapture(event.pointerId)) return;
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    if (Math.abs(dx) + Math.abs(dy) > 8) dragged = true;
    if (!dragged) return;
    companionX = originX + dx;
    companionY = originY + dy;
    placeCompanion();
    if (panelKey === "companion") closePanel();
  });
  companionCanvas.addEventListener("pointerup", () => {
    if (dragged) {
      savePreference("companionX", companionX);
      savePreference("companionY", companionY);
    } else {
      togglePanel("companion", companionPanel);
    }
  });
}

function companionPanel() {
  return h("div", { className: "column" },
    h("div", {}, "Hi! I keep you company while you draw."),
    gap(h("div", { className: "caption" },
      "On the web I can't look at your drawing yet. Notes and warm-ups come in a later version.")));
}

// Places the bubble beside the companion, on whichever side has room.
function placeBubble() {
  const [x, y] = placeCompanion();
  const size = 56;
  const width = panel.offsetWidth;
  const height = panel.offsetHeight;
  const right = x + size + 2;
  const left = right + width <= window.innerWidth ? right : Math.max(0, x - 2 - width);
  const top = Math.min(Math.max(0, y + size / 2 - height / 2), Math.max(0, window.innerHeight - height));
  panel.style.left = `${left}px`;
  panel.style.top = `${top}px`;
}

const panelObserver = new MutationObserver(() => {
  if (panel.className !== "bubble") {
    panel.style.left = "";
    panel.style.top = "";
  }
});
panelObserver.observe(panel, { attributes: true, attributeFilter: ["class"] });

// --- Messages ----------------------------------------------------------------------------------

// Notes come from the studio's companion, in the brush color, as on Android.
function say(text, long = false) {
  sayNote(text, { long, look: "paint", color });
}

function fail(text) {
  message.textContent = text;
  message.hidden = false;
  throw new Error(text);
}

// --- Artworks: opening, saving, and going home, as MainActivity -------------------------------

let store = null;
let waitingFolder = null;
let home = null;
let doc = null;  // The open artwork: {id, dir, manifest, width, height, fresh}.
let ready = false;
let unsaved = false;
let previewStale = false;
let saveTimer = 0;
let saving = Promise.resolve();
const rectPointer = engine._malloc(16);

function markUnsaved() {
  if (!doc) return;
  unsaved = true;
  previewStale = true;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(autosave, AUTOSAVE_DELAY_MS);
}

function autosave() {
  // Waits for the pen to lift, so saving never lands in the middle of a stroke.
  if (strokePointer !== -1) {
    saveTimer = setTimeout(autosave, AUTOSAVE_DELAY_MS);
    return;
  }
  save(false);
}

async function encodeComposite(width, height, type) {
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_composite(width, height, pointer);
  const pixels = new Uint8ClampedArray(engine.HEAPU8.buffer, pointer, size).slice();
  engine._free(pointer);
  const surface = new OffscreenCanvas(width, height);
  surface.getContext("2d").putImageData(new ImageData(pixels, width, height), 0, 0);
  return surface.convertToBlob(type === "image/jpeg" ? { type, quality: 0.92 } : { type });
}

// Reads every layer's changes now, while the engine holds them, then writes in the background.
function save(withPreview) {
  if (!doc) return saving;
  const target = doc;
  const regions = new Map();
  for (const layer of target.manifest.layers) {
    for (const id of [layer.id, layer.mask || 0]) {
      if (!id || !engine._cap_take_layer_dirty(id, rectPointer)) continue;
      const rect = engine.HEAP32.subarray(rectPointer >> 2, (rectPointer >> 2) + 4);
      const left = Math.floor(rect[0] / TILE_SIZE) * TILE_SIZE;
      const top = Math.floor(rect[1] / TILE_SIZE) * TILE_SIZE;
      const right = Math.min(target.width, Math.ceil(rect[2] / TILE_SIZE) * TILE_SIZE);
      const bottom = Math.min(target.height, Math.ceil(rect[3] / TILE_SIZE) * TILE_SIZE);
      const size = (right - left) * (bottom - top) * 4;
      const pointer = engine._malloc(size);
      engine._cap_read_layer_region(id, left, top, right, bottom, pointer);
      regions.set(id, { left, top, width: right - left, height: bottom - top, pixels: engine.HEAPU8.slice(pointer, pointer + size) });
      engine._free(pointer);
    }
  }
  const thumbnail = encodeComposite(THUMBNAIL_WIDTH, thumbnailHeight(target.width, target.height), "image/png");
  const preview = withPreview ? encodeComposite(PREVIEW_WIDTH, previewHeight(target.width, target.height), "image/jpeg") : null;
  unsaved = false;
  if (withPreview) previewStale = false;
  target.fresh = false;
  companion.setMood("thinking");
  const saveStore = store;
  saving = saving.then(async () => {
    await saveStore.save(target, regions, await thumbnail, preview && await preview);
  }).catch((error) => {
    console.error(error);
    // Those changes never reached disk, so the next save has to include them.
    if (doc === target) {
      for (const id of regions.keys()) engine._cap_mark_layer_dirty(id);
      unsaved = true;
    }
    say("Couldn't save your artwork", true);
  }).finally(() => companion.setMood("idle"));
  return saving;
}

// Puts an artwork in the engine: its layers bottom first, their masks, and their settings.
async function openDocument(next) {
  ready = false;
  doc = next;
  canvasWidth = next.width;
  canvasHeight = next.height;
  showStudio();
  engine._cap_open(canvasWidth, canvasHeight);
  opened = true;
  fitView();
  const manifest = next.manifest;
  const groups = new Map((manifest.groups || []).map((group) => [group.id, group]));
  const size = canvasWidth * canvasHeight * 4;
  const pixels = new Uint8Array(size);
  const pointer = engine._malloc(size);
  const ids = [];
  try {
    for (const layer of manifest.layers) {
      engine._cap_create_layer(layer.id);
      ids.push(layer.id);
      if (!next.fresh && await store.readLayer(next, layer.tiles, layer.file, pixels)) {
        engine.HEAPU8.set(pixels, pointer);
        engine._cap_write_layer(layer.id, pointer);
      }
      if (layer.mask) {
        engine._cap_create_layer(layer.mask);
        engine._cap_set_layer_mask(layer.id, layer.mask, layer.maskEnabled === false ? 0 : 1);
        if (await store.readLayer(next, layer.maskTiles, layer.maskFile, pixels)) {
          engine.HEAPU8.set(pixels, pointer);
          engine._cap_write_layer(layer.mask, pointer);
        }
      }
      // Older Android artworks move to tiles on their next save, all at once.
      if (!layer.tiles) engine._cap_mark_layer_dirty(layer.id);
      if (layer.mask && !layer.maskTiles) engine._cap_mark_layer_dirty(layer.mask);
      const group = groups.get(layer.group);
      engine._cap_set_layer_properties(layer.id, layer.opacity * (group ? group.opacity : 1),
        layer.visible && (!group || group.visible) ? 1 : 0, layer.blend || 0, layer.clip ? 1 : 0);
    }
  } catch (error) {
    console.error(error);
    say("Part of this artwork couldn't be read", true);
  } finally {
    engine._free(pointer);
  }
  engine._cap_set_active_layer(manifest.activeLayer || ids[0]);
  symmetry = manifest.symmetry || 0;
  const center = manifest.symmetryCenter && manifest.symmetryCenter.length === 2 ? manifest.symmetryCenter : [-1, -1];
  engine._cap_set_symmetry(symmetry, center[0], center[1]);
  viewMode = 0;
  guide = 0;
  engine._cap_set_view_options(0, 0);
  unsaved = false;
  previewStale = !!next.fresh;
  queueCompose();
  ready = true;
}

async function openArtwork(id) {
  const loaded = await store.load(id).catch(() => null);
  if (!loaded) {
    sayNote("Couldn't open that artwork", { long: true });
    return;
  }
  await openDocument(loaded);
}

async function newArtwork(width, height, name, projectId) {
  await openDocument(await store.create(name, projectId, width, height));
  markUnsaved();
}

function showStudio() {
  homeView.hidden = true;
  studioView.hidden = false;
  closePanel();
  placeRecentStrip();
  placeCompanion();
  companion.resize();
  selectTool(brush);
}

async function leaveStudio() {
  if (!ready) return;
  ready = false;
  closePanel();
  clearTimeout(saveTimer);
  if (unsaved || previewStale || doc?.fresh) save(true);
  await saving;
  doc = null;
  studioView.hidden = true;
  homeView.hidden = false;
  await home.render();
}

window.addEventListener("beforeunload", (event) => {
  if (!doc || !(unsaved || previewStale)) return;
  save(true);
  event.preventDefault();
});

function setStore(next, waiting) {
  store = next;
  waitingFolder = waiting;
}

// --- Start -------------------------------------------------------------------------------------

window.addEventListener("resize", () => {
  resize();
  placeRecentStrip();
  placeCompanion();
});
resize();
setColor(color);
selectTool(brush);
showRecentColors();
setStats(statsOn);

({ store, waiting: waitingFolder } = await ArtworkStore.open());
home = new Home(homeView, {
  get store() { return store; },
  get waiting() { return waitingFolder; },
  setStore,
  openArtwork,
  newArtwork,
});
await home.render();
window.addEventListener("resize", () => {
  if (!homeView.hidden) home.render();
});

// Exposed for checking the page from the console and from automated tests.
window.cappuccino = { engine, fitView, undo, redo, selectTool, setColor, leaveStudio, get store() { return store; } };
