// Cappuccino's drawing screen on the web, laid out like MainActivity.showChrome() on Android, over
// the shared C++ engine running as WebAssembly on WebGL 2.
//
// Strokes draw straight from the pointer handlers into a desynchronized canvas, the way the
// Android app draws into its front buffer. Pan, zoom, undo, and resizing redraw the whole view
// on the next animation frame. Nothing here allocates per sample or per frame except the arrays
// the browser itself returns from getCoalescedEvents() and getPredictedEvents().

import { LANGUAGES, language, saveLanguage, translatePage } from "./i18n.js";
import createEngine from "./engine.js";
import ICONS from "./icons.js";
import {
  BRUSH_FAMILIES, DEFAULT_BRUSH, DEFAULT_ERASER, ERASERS, JavaRandom, TIP_SIZE, TOOLS,
  isTool, javaHash, stampFlow,
} from "./brushes.js";
import { Companion, LOOKS, drawHands } from "./companion.js";
import { analyzeArtwork, clearestAtASmallSize, finishNote, progressNote, withoutMuted } from "./analysis.js";
import { psdFolder, withPsdGroups, writePsd } from "./psd.js";
import { WARMUPS, WARMUP_TOPICS } from "./warmups.js";
import { WarmupLayout, WarmupWork, drawWarmupGuide, singleSun, warmupReview } from "./practice.js";
import {
  applyUiScale, askSheet, choiceSheet, confirmSheet, h, pixelRatio, say as sayNote, sheet, svg, toUi, uiScale, viewHeight, viewWidth,
} from "./ui.js";
import { Home, warmupPicker } from "./home.js";
import {
  BOARD_PURPOSES, PURPOSE_LABELS, ReferenceWindow, STARRED_FILTER, chooseBoard, chooseSketchbook, decodeReference,
  aspectReferences, extractPalette, filterReferences, fitReferenceWidth, referenceBlocks, referenceOptions, referencePreview,
} from "./references.js";
import { REFERENCE_PROFILE_SIZE, compareWithReferences, profileImage } from "./match.js";
import { reshapeShape, shapeAnchors, snapShape } from "./shapes.js";
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
const HOLD_REPEAT_MS = 500;
const REPEAT_MS = 150;
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
const LEFT_PANELS = new Set(["actions", "references", "view", "tools", "sticker", "variants"]);

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
translatePage();

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
let rotation = 0;  // Radians the view is turned, by two fingers.
let panX = 0;
let panY = 0;
let composeQueued = false;

function writeView() {
  const heap = engine.HEAPF32;
  const [a, b, c, d] = viewLinear();
  heap[viewOffset] = a;
  heap[viewOffset + 1] = b;
  heap[viewOffset + 2] = panX;
  heap[viewOffset + 3] = c;
  heap[viewOffset + 4] = d;
  heap[viewOffset + 5] = panY;
  heap[viewOffset + 6] = 0;
  heap[viewOffset + 7] = 0;
  heap[viewOffset + 8] = 1;
  if (guideHandleList.length) positionGuideHandles();
  for (const [, place] of shapeHandleList) place();
}

// The view's scale, turn, and flip, as a 2x2 matrix [a b; c d] from canvas to buffer pixels.
function viewLinear() {
  const cos = Math.cos(rotation) * zoom;
  const sin = Math.sin(rotation) * zoom;
  const fx = flipped ? -1 : 1;
  return [cos * fx, -sin, sin * fx, cos];
}

// Buffer pixels to canvas pixels.
function toCanvasX(x, y) {
  const dx = x - panX;
  const dy = y - panY;
  const along = (Math.cos(rotation) * dx + Math.sin(rotation) * dy) / zoom;
  return flipped ? -along : along;
}

function toCanvasY(x, y) {
  const dx = x - panX;
  const dy = y - panY;
  return (-Math.sin(rotation) * dx + Math.cos(rotation) * dy) / zoom;
}

function fitView() {
  zoom = Math.min(canvas.width / canvasWidth, canvas.height / canvasHeight) * 0.95;
  flipped = false;
  rotation = 0;
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
  rotation = -rotation;
  panX = canvas.width - panX;
  writeView();
  queueCompose();
}

function resize() {
  applyUiScale();
  canvas.style.zoom = String(1 / uiScale());
  overlay.style.zoom = String(1 / uiScale());
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
  if (noteMarks.length) drawOverlay();
  if (transformCorners) moveTransformHandles();
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
    kind.hueJitter, kind.tip ? 1 : 0, alphaLocked() ? 1 : 0,
  );
  strokeKind = kind;
  strokePath = kind.eraser ? null : [];
  pressureTotal = 0;
  pressureSamples = 0;
  stabilizerFollow = kind.eraser ? 1 : 1 - MAX_SMOOTHING * settings.stabilizer;
  stabilizerStarted = false;
  strokeHadSamples = false;
}

// Writes one pointer sample, in canvas pixels, into the engine's buffer at slot.
function writeSample(offset, slot, event, stabilize) {
  const heap = engine.HEAPF32;
  let x = toCanvasX(event.clientX * dpr, event.clientY * dpr);
  let y = toCanvasY(event.clientX * dpr, event.clientY * dpr);
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
      azimuth = event.azimuthAngle - rotation;
    } else if (event.tiltX || event.tiltY) {
      const tx = Math.tan(event.tiltX * Math.PI / 180);
      const ty = Math.tan(event.tiltY * Math.PI / 180);
      tilt = Math.atan(Math.hypot(tx, ty));
      azimuth = Math.atan2(ty, tx) - rotation;
    }
  }
  // A mouse draws like a relaxed pen. Browsers report 0.5 for it, but not all of them do.
  let pressure = event.pointerType === "mouse" ? 0.5 : event.pressure;
  if (pressure === 0) pressure = 0.01;
  if (stabilize && strokePath) {
    strokePath.push(x, y);
    pressureTotal += pressure;
    pressureSamples++;
  }
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
  if (strokePath && strokePath.length >= 4) checkPerspective(strokePath);
  if (doc?.warmup && strokePath && strokePath.length >= 4) {
    warmupStrokes.push(strokePath);
    undoneWarmupStrokes.length = 0;
  }
  strokePath = null;
  recordStroke();
  markUnsaved();
  queueCompose();  // Clears any predicted ink left past the real end.
}

// --- Gradient and lasso fill -------------------------------------------------------------------

function beginToolGesture(kind, event) {
  const x = toCanvasX(event.clientX * dpr, event.clientY * dpr);
  const y = toCanvasY(event.clientX * dpr, event.clientY * dpr);
  toolGesture = { kind, startX: x, startY: y, count: 0 };
  if (kind.mode === "lasso" || kind.mode === "select") addLassoPoint(event);
}

function moveToolGesture(event) {
  if (toolGesture.kind.mode === "gradient") {
    const rgb = hexToRgb(color);
    engine._cap_set_gradient(1, toolGesture.startX, toolGesture.startY,
      toCanvasX(event.clientX * dpr, event.clientY * dpr), toCanvasY(event.clientX * dpr, event.clientY * dpr), rgb[0], rgb[1], rgb[2]);
    queueCompose();
    return;
  }
  const events = event.getCoalescedEvents ? event.getCoalescedEvents() : [event];
  for (const sample of events.length ? events : [event]) addLassoPoint(sample);
  drawOverlay();
}

function addLassoPoint(event) {
  if (toolGesture.count >= MAX_POINTS) return;
  const heap = engine.HEAPF32;
  const i = pointsOffset + toolGesture.count * 2;
  heap[i] = toCanvasX(event.clientX * dpr, event.clientY * dpr);
  heap[i + 1] = toCanvasY(event.clientX * dpr, event.clientY * dpr);
  toolGesture.count++;
}

// Shows the lasso as you draw it, as LassoOverlay does: a white line under accent dashes.
function drawLasso() {
  const c = overlayContext;
  if (!toolGesture || toolGesture.count < 2) return;
  const heap = engine.HEAPF32;
  c.beginPath();
  for (let n = 0; n < toolGesture.count; n++) {
    const [x, y] = canvasToScreen(heap[pointsOffset + n * 2], heap[pointsOffset + n * 2 + 1]);
    if (n === 0) c.moveTo(x, y); else c.lineTo(x, y);
  }
  c.lineWidth = 2 * dpr;
  c.setLineDash([]);
  c.strokeStyle = "#FFFFFF";
  c.stroke();
  c.setLineDash([14, 10]);
  c.strokeStyle = "#D85A30";
  c.stroke();
  c.setLineDash([]);
}

function endToolGesture(cancelled) {
  const gesture = toolGesture;
  toolGesture = null;
  if (gesture.kind.mode === "gradient") {
    if (cancelled) {
      engine._cap_set_gradient(0, 0, 0, 0, 0, 0, 0, 0);
    } else {
      engine._cap_commit_gradient();
      recordStroke();
      markUnsaved();
    }
  } else if (gesture.kind.mode === "select") {
    drawOverlay();
    if (!cancelled) finishLasso(gesture.count);
  } else {
    drawOverlay();
    if (!cancelled && gesture.count >= 3) {
      const rgb = hexToRgb(color);
      if (engine._cap_fill_polygon(gesture.count, rgb[0], rgb[1], rgb[2], settingsFor(gesture.kind).opacity)) {
        recordStroke();
        markUnsaved();
      }
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
let tapSpent = false;  // The current fingers already tapped or moved; the next to land start a new tap.
let holdTimer = 0;
let panPointer = -1;  // Middle button or space + drag.
let panLastX = 0;
let panLastY = 0;
let spaceHeld = false;
let pickPointer = -1;

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
  if (transforming) {
    transformPointer(event, "down");
    return;
  }
  if (liquifying && event.pointerType !== "touch" && event.button === 0 && !spaceHeld) {
    liquifyPointerEvent(event, "down");
    return;
  }
  if (event.pointerType === "touch") {
    if (strokePointer !== -1 || toolGesture) return;  // Palm on the screen while the pen draws.
    capture(event);
    // A finger still resting from the last tap doesn't count toward the next one.
    if (touches.size === 0 || tapSpent) {
      tapStart = event.timeStamp;
      tapTravel = 0;
      tapFingers = 0;
      tapSpent = false;
    }
    touches.set(event.pointerId, { x: event.clientX * dpr, y: event.clientY * dpr });
    tapFingers += 1;
    clearTimeout(holdTimer);
    if (tapFingers === 2 || tapFingers === 3) holdTimer = setTimeout(holdRepeat, HOLD_REPEAT_MS);
    gesture = null;
    clearTimeout(pickTimer);
    if (touches.size === 1) {
      const { clientX, clientY } = event;
      // Holding one finger still picks the color under it, as on Android.
      pickTimer = setTimeout(() => {
        if (touches.size === 1 && tapTravel < TAP_TRAVEL) {
          pickPointer = event.pointerId;
          touches.clear();
          pickAt(clientX, clientY);
        }
      }, LONG_PRESS_MS);
    }
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
  // The pen's barrel button does what Actions sets: pick a color, erase, or undo.
  const barrel = event.pointerType === "pen" && (event.buttons & 2) !== 0;
  if (barrel && penButtonAction === 2) {
    undo();
    return;
  }
  if ((barrel && penButtonAction === 0) || (event.altKey && event.button === 0)) {
    capture(event);
    pickPointer = event.pointerId;
    pickAt(event.clientX, event.clientY);
    return;
  }
  if ((event.button !== 0 && !barrel) || strokePointer !== -1 || toolGesture) return;
  if (selectionTool === "wand") {
    finishWand(Math.floor(toCanvasX(event.clientX * dpr, event.clientY * dpr)), Math.floor(toCanvasY(event.clientX * dpr, event.clientY * dpr)));
    return;
  }
  if (selectionTool === "lasso") {
    capture(event);
    strokePointer = event.pointerId;
    beginToolGesture({ mode: "select" }, event);
    return;
  }
  const eraserEnd = event.pointerType === "pen" && ((event.buttons & 32) !== 0 || (barrel && penButtonAction === 1));
  const kind = eraserEnd ? lastEraser : brush;
  capture(event);
  if (kind.mode === "fill") {
    const rgb = hexToRgb(color);
    if (engine._cap_fill_at(Math.floor(toCanvasX(event.clientX * dpr, event.clientY * dpr)), Math.floor(toCanvasY(event.clientX * dpr, event.clientY * dpr)),
      rgb[0], rgb[1], rgb[2])) {
      recordStroke();
      markUnsaved();
    }
    queueCompose();
    return;
  }
  if (kind.mode === "gradient" || kind.mode === "lasso") {
    strokePointer = event.pointerId;
    beginToolGesture(kind, event);
    return;
  }
  strokePointer = event.pointerId;
  clearTimeout(napTimer);
  if (companion.mood === "nap") setCompanionMood(restingMood());
  fadeChrome(0.15);
  hideShapeHandles();
  snapAnchor = null;
  beginStroke(kind);
  drawSamples(event, false);
});

canvas.addEventListener("pointermove", (event) => {
  if (transforming) {
    transformPointer(event, "move");
    return;
  }
  if (liquifying && event.pointerType !== "touch") {
    if (event.pointerId === liquifyPointer) liquifyPointerEvent(event, "move");
    else if (event.buttons === 0) showLiquifyRing(event);
    if (event.pointerId === liquifyPointer) return;
  }
  if (event.pointerId === pickPointer) {
    pickAt(event.clientX, event.clientY);
    return;
  }
  if (event.pointerId === strokePointer) {
    if (toolGesture) {
      moveToolGesture(event);
    } else {
      drawSamples(event, false);
      waitForSnap(event);
    }
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
  if (transforming) {
    transformPointer(event, "up");
    return;
  }
  if (event.pointerId === liquifyPointer) {
    liquifyPointerEvent(event, "up");
    return;
  }
  if (event.pointerId === pickPointer) {
    pickPointer = -1;
    endPick();
    return;
  }
  if (event.pointerId === strokePointer) {
    strokePointer = -1;
    clearTimeout(snapTimer);
    if (toolGesture) {
      endToolGesture(cancelled);
    } else if (cancelled) {
      engine._cap_cancel_stroke();
      queueCompose();
    } else {
      endStroke();
    }
    fadeChrome(1);
    scheduleNap();
    return;
  }
  if (event.pointerId === panPointer) {
    panPointer = -1;
    canvas.style.cursor = spaceHeld ? "grab" : "";
    return;
  }
  if (!touches.delete(event.pointerId)) return;
  gesture = null;
  clearTimeout(holdTimer);
  // Taps with two, three, and four fingers undo, redo, and hide the interface, as on Android.
  // The first finger to lift ends the tap, so quick taps in a row each count.
  if (tapSpent || tapFingers < 2) return;
  tapSpent = true;
  const isTap = !cancelled && tapTravel < TAP_TRAVEL && event.timeStamp - tapStart < TAP_TIMEOUT_MS;
  if (isTap && tapFingers === 2) undo();
  if (isTap && tapFingers === 3) redo();
  if (isTap && tapFingers === 4) toggleInterface();
}

// Holding two or three fingers still keeps undoing or redoing until they lift or move.
function holdRepeat() {
  if (tapTravel >= TAP_TRAVEL || touches.size < 2) return;
  tapSpent = true;
  if (tapFingers === 2) undo();
  else if (tapFingers === 3) redo();
  else return;
  holdTimer = setTimeout(holdRepeat, REPEAT_MS);
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
  const angle = n === 2 ? Math.atan2(by - ay, bx - ax) : 0;
  if (gesture && gesture.count === n) {
    panX += x - gesture.x;
    panY += y - gesture.y;
    if (n === 2 && gesture.span > 0) {
      zoomAt(x, y, span / gesture.span);
      // Two fingers also turn the canvas around their midpoint, as on Android.
      let turn = angle - gesture.angle;
      if (turn > Math.PI) turn -= 2 * Math.PI;
      if (turn < -Math.PI) turn += 2 * Math.PI;
      const cos = Math.cos(turn);
      const sin = Math.sin(turn);
      const dx = panX - x;
      const dy = panY - y;
      panX = x + cos * dx - sin * dy;
      panY = y + sin * dx + cos * dy;
      rotation += turn;
    }
    writeView();
    queueCompose();
  } else {
    gesture = gesture || { x: 0, y: 0, span: 0, count: 0, angle: 0 };
  }
  gesture.x = x;
  gesture.y = y;
  gesture.span = span;
  gesture.angle = angle;
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

// Steps back through strokes and layer changes in the order they happened, as undoEdit() does.
function undo() {
  if (strokePointer !== -1 || !ready || liquifying) return;
  const edit = history.undo();
  if (!edit) return;
  if (edit.kind === "stroke") {
    engine._cap_undo();
    const stroke = warmupStrokes.pop();
    if (stroke) undoneWarmupStrokes.push(stroke);
  } else {
    if (edit.pixels) engine._cap_undo();
    restoreLayers(edit.before);
  }
  markUnsaved();
  queueCompose();
}

function redo() {
  if (strokePointer !== -1 || !ready || liquifying) return;
  const edit = history.redo();
  if (!edit) return;
  if (edit.kind === "stroke") {
    engine._cap_redo();
    const stroke = undoneWarmupStrokes.pop();
    if (stroke) warmupStrokes.push(stroke);
  } else {
    if (edit.pixels) engine._cap_redo();
    restoreLayers(edit.after);
  }
  markUnsaved();
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

colorButton.addEventListener("click", async () => {
  if (panelKey !== "color") {
    colorBeforePanel = color;
    savedPalettes = await store.palettes();
  }
  togglePanel("color", colorPanel);
});

for (const button of document.querySelectorAll("[data-action]")) {
  const action = button.dataset.action;
  button.addEventListener("click", () => {
    if (action === "projects") leaveStudio();
    else if (action === "view") togglePanel("view", viewPanel);
    else if (action === "tools") togglePanel("tools", toolsPanel);
    else if (action === "layers") togglePanel("layers", layersPanel);
    else if (action === "actions") togglePanel("actions", actionsPanel);
    else if (action === "references") {
      if (panelKey === "references") closePanel();
      else showReferences();
    }
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
  markTools();
}

// The tools button shows the tool in use, and stays lit while there's a selection.
function markTools() {
  toolsButton.classList.toggle("on", hasSelection || isTool(brush));
  const icon = { smudge: "smudge", fill: "fill", gradient: "gradient", lasso: "lasso_fill" }[brush.mode];
  if (selectionTool === "wand") toolsButton.innerHTML = ICONS.wand;
  else if (selectionTool === "lasso") toolsButton.innerHTML = ICONS.select;
  else toolsButton.innerHTML = ICONS[isTool(brush) ? icon : "select"];
}

function setColor(next) {
  color = next.toUpperCase();
  colorButton.firstElementChild.style.background = color;
  companion.setColor(color);
  peek.setColor(color);
  if (!companionVisible) drawHands(handsCanvas, companionLook, color);
  if (brush.eraser) selectTool(lastBrush);
}

function allBrushes() {
  return [...BRUSH_FAMILIES.flatMap(([, kinds]) => kinds), ...customBrushes, ...ERASERS, ...Object.values(TOOLS)];
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
    const edge = 20 * uiScale();
    set((box.bottom - edge - event.clientY) / (box.height - edge * 2) * 1000);
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
  const top = viewHeight() / 2 + sliders.offsetHeight / 2 + 8;
  recentStrip.style.top = `${top}px`;
}

function toggleInterface() {
  interfaceHidden = !interfaceHidden;
  closePanel();
  for (const element of chrome) element.hidden = interfaceHidden;
  showCompanionState();
  showWarmupBar();
  refreshAnimationBar();
}

// Chrome steps back while the pen draws, as fadeChrome() does on Android.
function fadeChrome(opacity) {
  for (const window of references.values()) window.setDrawing(opacity < 1);
  for (const element of chrome) element.style.opacity = String(opacity);
  warmupBar.style.opacity = String(opacity);
  animationBar.style.opacity = String(opacity);
  peekCanvas.style.opacity = String(opacity);
  handsCanvas.style.opacity = String(opacity);
  companion.still = opacity < 1;
  peek.still = opacity < 1;
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
  if (key === "view") showViewHandles();
}

// Builds the open panel again in place, keeping its scroll.
function rerender() {
  if (!panelBuilder) return;
  const scroll = panel.scrollTop;
  panelBody.replaceChildren(panelBuilder());
  panel.scrollTop = scroll;
  if (panelKey === "companion") placeBubble();
}

function closePanel() {
  if (!panelKey) return;
  hideViewHandles();
  if (panelKey === "color") rememberColor();
  if (panelKey === "companion") showNoteMarks([]);
  if (panelKey === "adjust") {
    engine.HEAPF32.fill(0, pointsOffset, pointsOffset + 6);
    engine._cap_set_adjustment(0);
    queueCompose();
  }
  if (panelKey === "filter") {
    engine._cap_set_filter(0, 0, 0, 0, 0);
    queueCompose();
  }
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
  const all = [...BRUSH_FAMILIES.flatMap(([, kinds]) => kinds), ...customBrushes];
  const families = [];
  if (favorites.size > 0) families.push(["★", all.filter((kind) => favorites.has(kind.id))]);
  families.push(...BRUSH_FAMILIES);
  if (customBrushes.length) families.push(["Mine", customBrushes.slice()]);
  return families;
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
        onClick: () => openBrushEditor(null),
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
      gap(h("div", { className: "small" }, "Snap shapes")),
      segmented(["Off", "On"], snapShapes ? 1 : 0, (index) => {
        snapShapes = index === 1;
        savePreference("snapShapes", snapShapes);
        rerender();
      }),
      gap(h("div", { className: "small" }, "Hold at the end of a stroke for a clean line, ellipse, or box.")),
    );
    if (brush.custom) {
      body.append(gap(h("div", { className: "wrap-row" },
        smallChip("Edit", false, () => openBrushEditor(brush)),
        smallChip("Delete", false, () => deleteBrush(brush)))));
    }
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
    const preview = h("canvas", { width: 130 * pixelRatio(), height: 48 * pixelRatio(), ariaLabel: `${kind.label} preview` });
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

const tintedTips = new WeakMap();  // Tip bytes -> tint -> a canvas of that tip in that color.

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
  if (!tintedTips.has(kind.tip)) tintedTips.set(kind.tip, new Map());
  const cache = tintedTips.get(kind.tip);
  let tip = cache.get(tint);
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
  cache.set(tint, tip);
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
      toolCard("select", "Lasso", "Draw around an area", false, () => startSelection("lasso", "Draw around an area with your pen")),
      toolCard("wand", "Magic wand", "Pick a color area", false, () => startSelection("wand", "Tap a color area with your pen")))),
    gap(h("div", { className: "caption" },
      "Make a selection to keep painting inside it, or to move part of a layer.")),
    ...selectionControls());
}

// --- Selection, as MainActivity's lasso, magic wand, and selection actions -----------------------

const FEATHER_SHARE = 4000;
let selectionTool = "none";
let hasSelection = false;
let selectionMode = 0;  // 0 new, 1 add, 2 remove.
let selectionFeather = 0;

function startSelection(tool, hint) {
  selectionTool = tool;
  markTools();
  closePanel();
  say(hint);
}

function markSelection(active) {
  hasSelection = active;
  if (!active) selectionMode = 0;
  markTools();
}

function finishLasso(count) {
  selectionTool = "none";
  engine._cap_set_selection_polygon(count, selectionMode);
  markSelection(engine._cap_has_selection() === 1);
  queueCompose();
}

function finishWand(x, y) {
  selectionTool = "none";
  engine._cap_select_color_at(x, y, 40, selectionMode);
  markSelection(engine._cap_has_selection() === 1);
  queueCompose();
}

function clearSelection() {
  engine._cap_clear_selection();
  markSelection(false);
  queueCompose();
}

// Fades the selection's edge over about twice radius canvas pixels.
function featherSelection(radius) {
  engine._cap_set_selection_feather(radius);
  queueCompose();
}

function selectionToLayer(cut, transform) {
  if (layerSlots() >= maxLayers) return sayFull();
  setEditingMask(false);
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  engine._cap_copy_selection_to_layer(id, cut ? 1 : 0);
  editLayers(() => {
    const index = layers().findIndex((layer) => layer.id === doc.manifest.activeLayer) + 1;
    layers().splice(index, 0, newLayer(id, `Selection ${id}`));
    doc.manifest.activeLayer = id;
  }, { pixels: cut });
  clearSelection();
  if (transform) startTransform();
  else closePanel();
}

function selectionControls() {
  if (!hasSelection) return [];
  const row = (icon, title, detail, click) => gap(h("button", { className: "action-row", onClick: click },
    svg(icon), h("div", { className: "text" }, h("div", {}, title), detail && h("div", { className: "detail" }, detail))));
  return [
    gap(segmented(["New", "Add", "Remove"], selectionMode, (index) => {
      selectionMode = index;
      rerender();
    })),
    gap(toolSlider("Soft edge", selectionFeather, (value) => {
      selectionFeather = value;
      featherSelection(Math.round(value * Math.max(doc.width, doc.height) / FEATHER_SHARE));
    })),
    row("transform", "Transform", "Move, scale, or rotate the selection", () => selectionToLayer(true, true)),
    row("copy", "Copy to layer", null, () => selectionToLayer(false, false)),
    row("cut", "Cut to layer", null, () => selectionToLayer(true, false)),
    row("invert", "Invert", null, () => {
      engine._cap_invert_selection();
      queueCompose();
      closePanel();
    }),
    row("close", "Clear selection", null, () => clearSelection()),
  ];
}

// --- Art direction ---------------------------------------------------------------------------

function viewPanel() {
  const apply = () => {
    const frames = thumbnailFrames(canvasWidth, canvasHeight).flat();
    engine.HEAPF32.set(frames, pointsOffset);
    engine._cap_set_frames(frames.length / 4);
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
    gap(h("div", { className: "chips" }, GUIDES.map((name, index) =>
      smallChip(name === "None" ? "Off" : name, guide === index, () => {
        guide = index;
        apply();
        rerender();
      })))),
    gap(h("div", { className: "small" }, "Perspective")),
    segmented(["Off", "1 point", "2 points", "3 points"], (doc?.manifest.perspective || []).length / 2, setPerspectiveCount),
    gap(h("div", { className: "small" }, "Symmetry")),
    segmented(["Off", "↔", "↕", "✛", "✺"], symmetry, setSymmetry),
    gap(h("div", { className: "small" }, "Light direction")),
    segmented(LIGHTS.map(([label]) => label), Math.max(0, LIGHTS.findIndex(([, name]) => name === (doc?.manifest.light || "OFF"))), (index) => {
      doc.manifest.light = LIGHTS[index][1];
      markUnsaved();
      rerender();
    }),
    gap(h("div", { className: "small" }, "Figure height")),
    segmented(["Off", "6 heads", "7.5 heads", "8 heads"],
      Math.max(0, FIGURES.indexOf((doc?.manifest.figure || [])[2] ?? 0)), (index) => setFigure(FIGURES[index])),
    ((doc?.manifest.perspective || []).length || (doc?.manifest.figure || []).length || symmetry !== 0) &&
      gap(h("div", { className: "small" }, "Drag the circles to place your guides.")),
    gap(h("button", { className: "action-row", onClick: flipCanvas },
      svg("flip"),
      h("div", { className: "text" }, h("div", {}, "Flip canvas"),
        h("div", { className: "detail" }, "A fresh look at your drawing")))),
    gap(h("div", { className: "small" }, "These change your view only, never your artwork.")));
}

// --- Guides with handles: perspective, symmetry, and figure height, as in MainActivity -----------

const LIGHTS = [["Off", "OFF"], ["Left", "LEFT"], ["Top", "TOP"], ["Right", "RIGHT"]];
const FIGURES = [0, 6, 7.5, 8];
const PERSPECTIVE_TOLERANCE = 4;
const HANDLE_COLORS = ["#D85A30", "#1D9E75", "#534AB7"];
const guideHandles = h("div", { className: "handles" });
studioView.append(guideHandles);
let guideHandleList = [];  // [element, () => [canvasX, canvasY]]

// Starting vanishing points in canvas coordinates, as x and y pairs.
function defaultVanishingPoints(count, width, height) {
  const horizon = height * 0.45;
  if (count === 0) return [];
  if (count === 1) return [width * 0.5, horizon];
  if (count === 2) return [width * 0.12, horizon, width * 0.88, horizon];
  return [width * 0.12, horizon, width * 0.88, horizon, width * 0.5, height * 0.92];
}

function setPerspectiveCount(count) {
  const kept = (doc.manifest.perspective || []).slice(0, count * 2);
  doc.manifest.perspective = kept.concat(defaultVanishingPoints(count, doc.width, doc.height).slice(kept.length));
  perspectiveLog.reset();
  pushPerspective();
  markUnsaved();
  rerender();
  showViewHandles();
}

function pushPerspective() {
  const points = doc?.manifest.perspective || [];
  engine.HEAPF32.set(points, pointsOffset);
  engine._cap_set_perspective(points.length / 2);
  queueCompose();
}

function setSymmetry(index) {
  symmetry = index;
  if (doc) {
    doc.manifest.symmetry = index;
    if ((doc.manifest.symmetryCenter || []).length !== 2) doc.manifest.symmetryCenter = [canvasWidth / 2, canvasHeight / 2];
    markUnsaved();
  }
  pushSymmetry();
  rerender();
  showViewHandles();
}

function pushSymmetry() {
  const center = (doc?.manifest.symmetryCenter || []).length === 2 ? doc.manifest.symmetryCenter : [-1, -1];
  engine._cap_set_symmetry(symmetry, center[0], center[1]);
  queueCompose();
}

function setFigure(heads) {
  const height = doc.height;
  const figure = doc.manifest.figure || [];
  doc.manifest.figure = heads <= 0 ? [] : [figure[0] ?? height * 0.08, figure[1] ?? height * 0.92, heads];
  pushFigure();
  markUnsaved();
  rerender();
  showViewHandles();
}

function pushFigure() {
  const figure = doc?.manifest.figure || [];
  if (figure.length === 3) engine._cap_set_figure(figure[0], figure[1], figure[2]);
  else engine._cap_set_figure(0, 0, 0);
  queueCompose();
}

function pushGuides() {
  pushPerspective();
  pushSymmetry();
  pushFigure();
}

// A round handle that drags a guide point. place() gives its canvas point, move(x, y) takes one.
function guideHandle(color, label, place, move) {
  const handle = h("div", { className: "guide-handle", ariaLabel: label, title: label, style: { background: color } });
  handle.addEventListener("pointerdown", (event) => {
    event.stopPropagation();
    try {
      handle.setPointerCapture(event.pointerId);
    } catch {
      // Moves still arrive while the pointer stays over the handle.
    }
  });
  handle.addEventListener("pointermove", (event) => {
    if (!handle.hasPointerCapture(event.pointerId)) return;
    move(toCanvasX(event.clientX * dpr, event.clientY * dpr), toCanvasY(event.clientX * dpr, event.clientY * dpr));
    positionGuideHandles();
  });
  handle.addEventListener("pointerup", () => markUnsaved());
  guideHandles.append(handle);
  guideHandleList.push([handle, place]);
}

function showViewHandles() {
  hideViewHandles();
  if (!doc || panelKey !== "view") return;
  const perspective = doc.manifest.perspective || [];
  for (let index = 0; index < perspective.length / 2; index++) {
    guideHandle(HANDLE_COLORS[index], `Vanishing point ${index + 1}`,
      () => [doc.manifest.perspective[index * 2], doc.manifest.perspective[index * 2 + 1]],
      (x, y) => {
        doc.manifest.perspective[index * 2] = x;
        doc.manifest.perspective[index * 2 + 1] = y;
        perspectiveLog.reset();
        pushPerspective();
      });
  }
  if ((doc.manifest.figure || []).length === 3) {
    for (const index of [0, 1]) {
      guideHandle("#1D9E75", index === 0 ? "Top of head" : "Bottom of feet",
        () => [doc.width * 0.95, doc.manifest.figure[index]],
        (x, y) => {
          doc.manifest.figure[index] = y;
          pushFigure();
        });
    }
  }
  if (symmetry !== 0) {
    if ((doc.manifest.symmetryCenter || []).length !== 2) doc.manifest.symmetryCenter = [canvasWidth / 2, canvasHeight / 2];
    guideHandle("#D85A30", "Symmetry center", () => doc.manifest.symmetryCenter, (x, y) => {
      doc.manifest.symmetryCenter = [x, y];
      pushSymmetry();
    });
  }
  positionGuideHandles();
}

function positionGuideHandles() {
  for (const [handle, place] of guideHandleList) {
    const [x, y] = canvasToScreen(...place());
    handle.style.left = `${toUi(x / dpr) - 18}px`;
    handle.style.top = `${toUi(y / dpr) - 18}px`;
  }
}

function hideViewHandles() {
  guideHandles.replaceChildren();
  guideHandleList = [];
}

function lineGap(first, second) {
  const gap = Math.abs(first - second) % Math.PI;
  return Math.min(gap, Math.PI - gap) * 180 / Math.PI;
}

// How many degrees a straight stroke is from the nearest allowed perspective direction, or null
// when the stroke is short or curved, as strokeDrift() in Perspective.kt.
function strokeDrift(path, vanishingPoints) {
  if (!vanishingPoints.length || path.length < 4) return null;
  const [startX, startY] = path;
  const endX = path[path.length - 2];
  const endY = path[path.length - 1];
  const length = Math.hypot(endX - startX, endY - startY);
  if (length < 160) return null;
  for (let index = 0; index < path.length; index += 2) {
    const offset = Math.abs((endX - startX) * (startY - path[index + 1]) - (startX - path[index]) * (endY - startY));
    if (offset / length > length * 0.04) return null;
  }
  const direction = Math.atan2(endY - startY, endX - startX);
  const middleX = (startX + endX) / 2;
  const middleY = (startY + endY) / 2;
  let best = Infinity;
  for (let index = 0; index < vanishingPoints.length; index += 2) {
    best = Math.min(best, lineGap(direction, Math.atan2(vanishingPoints[index + 1] - middleY, vanishingPoints[index] - middleX)));
  }
  const pointCount = vanishingPoints.length / 2;
  if (pointCount <= 2) best = Math.min(best, lineGap(direction, Math.PI / 2));
  if (pointCount === 1) best = Math.min(best, lineGap(direction, 0));
  return best;
}

// Remembers recent straight strokes, so the companion can describe them calmly.
const perspectiveLog = {
  drifts: [],
  driftedLine: null,
  add(drift, line) {
    if (drift > PERSPECTIVE_TOLERANCE && line) this.driftedLine = line;
    this.drifts.push(drift);
    if (this.drifts.length > 10) this.drifts.shift();
  },
  reset() {
    this.drifts = [];
    this.driftedLine = null;
  },
  summary() {
    if (!this.drifts.length) return null;
    const off = this.drifts.filter((drift) => drift > PERSPECTIVE_TOLERANCE);
    if (!off.length) return `Your last ${this.drifts.length} straight lines follow the guide.`;
    return `${off.length} of your last ${this.drifts.length} straight lines drift from the guide, up to ${Math.round(Math.max(...off))}°. That can be a deliberate choice.`;
  },
};

function checkPerspective(path) {
  const drift = strokeDrift(path, doc?.manifest.perspective || []);
  if (drift === null) return;
  perspectiveLog.add(drift, [path[0], path[1], path[path.length - 2], path[path.length - 1]]);
  if (drift > PERSPECTIVE_TOLERANCE) {
    unreadTips = true;
    setCompanionMood("tip");
  }
}

// --- Your own brushes, as openBrushEditor() and brushEditor() in MainActivity ---------------------

let customBrushes = [];
let editingBrush = null;
let draftTip = null;

async function loadCustomBrushes() {
  customBrushes = await store.customBrushes().catch(() => []);
}

function deleteBrush(kind) {
  store.deleteBrush(kind.id);
  customBrushes = customBrushes.filter((other) => other.id !== kind.id);
  selectTool(DEFAULT_BRUSH);
  rerender();
}

function openBrushEditor(base) {
  editingBrush = base;
  draftTip = base?.tip || null;
  closePanel();
  showPanel("brushEditor", () => brushEditor(base || DEFAULT_BRUSH, !!base));
}

const BRUSH_SHAPE = [
  ["Size taper", "minScale", 1], ["Spacing", "spacing", 0.5], ["Flow", "flow", 1], ["Pressure fade", "pressureFlow", 1],
  ["Hardness", "hardness", 1], ["Grain", "grain", 1], ["Scatter", "scatter", 2], ["Size variation", "sizeJitter", 1],
  ["Angle variation", "angleJitter", 1], ["Shade variation", "shadeJitter", 1], ["Hue variation", "hueJitter", 1],
  ["Color per stroke", "strokeJitter", 1],
];

function draftBrush(source, name, shape) {
  return {
    ...source, ...shape,
    id: editingBrush?.id || crypto.randomUUID(), label: name.trim() || "My brush",
    size: source.size, eraser: false, mode: "paint", tip: draftTip, custom: true,
  };
}

function brushEditor(source, editing) {
  const shape = Object.fromEntries(BRUSH_SHAPE.map(([, field]) => [field, source[field]]));
  const name = h("input", { className: "field dark", value: editing ? source.label : "", placeholder: "Brush name" });
  const preview = h("canvas", { className: "brush-preview", width: 300 * pixelRatio(), height: 64 * pixelRatio(), ariaLabel: "Brush preview" });
  const refresh = () => {
    preview.getContext("2d").clearRect(0, 0, preview.width, preview.height);
    drawBrushPreview(preview, draftBrush(source, name.value, shape), TEXT_COLOR);
  };
  const body = h("div", { className: "column" },
    h("div", { className: "title" }, editing ? "Edit brush" : "New brush"),
    gap(name), gap(preview));
  for (const [title, field, range] of BRUSH_SHAPE) {
    body.append(toolSlider(title, Math.round(shape[field] / range * 100), (value) => {
      const index = BRUSH_SHAPE.findIndex(([, other]) => other === field);
      shape[field] = Math.max(index >= 6 ? 0 : 0.02, value / 100 * range);
      refresh();
    }));
  }
  body.append(gap(h("div", { className: "wrap-row" },
    smallChip(draftTip ? "Replace tip" : "Import tip", false, () => {
      const input = h("input", { type: "file", accept: "image/*" });
      input.addEventListener("change", async () => {
        const file = input.files[0];
        if (!file) return;
        try {
          draftTip = tipFrom(await createImageBitmap(file));
        } catch {
          say("Couldn't read this image");
          return;
        }
        const label = name.value;
        const saved = { ...shape };
        showPanel("brushEditor", () => {
          const next = brushEditor({ ...source, ...saved, label }, editing);
          next.querySelector("input.field").value = label;
          return next;
        });
      });
      input.click();
    }),
    smallChip("Save", true, async () => {
      const kind = draftBrush(source, name.value, shape);
      await store.saveBrush(kind);
      customBrushes = [...customBrushes.filter((other) => other.id !== kind.id), kind];
      closePanel();
      selectTool(kind);
    }))));
  requestAnimationFrame(refresh);
  return body;
}

// A brush tip from an image: dark, opaque parts paint, as tipFrom() in MainActivity.
function tipFrom(image) {
  const surface = new OffscreenCanvas(TIP_SIZE, TIP_SIZE);
  const c = surface.getContext("2d");
  c.drawImage(image, 0, 0, TIP_SIZE, TIP_SIZE);
  const pixels = c.getImageData(0, 0, TIP_SIZE, TIP_SIZE).data;
  const tip = new Uint8Array(TIP_SIZE * TIP_SIZE);
  for (let i = 0; i < tip.length; i++) {
    const [r, g, b, a] = [pixels[i * 4] / 255, pixels[i * 4 + 1] / 255, pixels[i * 4 + 2] / 255, pixels[i * 4 + 3]];
    const linear = (v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
    const lum = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
    tip[i] = Math.floor((1 - lum) * a);
  }
  return tip;
}

// --- Snap shapes: holding still at the end of a stroke draws the clean shape it resembles -------

const SNAP_HOLD_MS = 600;
const SNAP_TRAVEL = 8;
let snapShapes = loadPreference("snapShapes", true);
let snapTimer = 0;
let snapAnchor = null;
let pressureTotal = 0;
let pressureSamples = 0;
let shapeHandleList = [];

function waitForSnap(event) {
  if (!snapShapes || strokeKind.mode !== "paint" || strokeKind.eraser) return;
  if (snapAnchor && Math.hypot(event.clientX - snapAnchor[0], event.clientY - snapAnchor[1]) <= SNAP_TRAVEL) return;
  snapAnchor = [event.clientX, event.clientY];
  clearTimeout(snapTimer);
  snapTimer = setTimeout(snapStroke, SNAP_HOLD_MS);
}

function snapStroke() {
  if (strokePointer === -1 || !strokePath) return;
  const shape = snapShape(strokePath, doc?.manifest.perspective || []);
  if (!shape) return;
  const kind = strokeKind;
  const pressure = pressureSamples > 0 ? pressureTotal / pressureSamples : 0.5;
  strokePointer = -1;  // The pen stays down, but this stroke is finished.
  snapAnchor = null;
  engine._cap_cancel_stroke();
  drawShape(kind, shape, pressure);
  fadeChrome(1);
  scheduleNap();
  showShapeHandles(kind, shape, pressure);
}

// Draws a shape as one stroke, with the stroke's average pressure all along it.
function drawShape(kind, shape, pressure) {
  beginStroke(kind);
  strokePath = null;
  const heap = engine.HEAPF32;
  const count = shape.length / 2;
  for (let start = 0; start < count; start += MAX_SAMPLES) {
    const n = Math.min(MAX_SAMPLES, count - start);
    for (let i = 0; i < n; i++) {
      const at = samplesOffset + i * SAMPLE_FLOATS;
      heap.fill(0, at, at + SAMPLE_FLOATS);
      heap[at] = shape[(start + i) * 2];
      heap[at + 1] = shape[(start + i) * 2 + 1];
      heap[at + 2] = pressure;
    }
    engine._cap_draw(n, 0, start + n >= count ? 1 : 0);
  }
  recordStroke();
  markUnsaved();
  queueCompose();
  checkPerspective(shape);
}

function hideShapeHandles() {
  for (const [handle] of shapeHandleList) handle.remove();
  shapeHandleList = [];
}

// Two handles that stay on a snapped shape until the next stroke, so it can still be adjusted.
function showShapeHandles(kind, shape, pressure) {
  hideShapeHandles();
  let current = shape;
  let anchors = shapeAnchors(shape);
  const place = (handle, end) => {
    const [x, y] = canvasToScreen(anchors[end], anchors[end + 1]);
    handle.style.left = `${toUi(x / dpr) - 18}px`;
    handle.style.top = `${toUi(y / dpr) - 18}px`;
  };
  for (const end of [0, 2]) {
    const handle = h("div", { className: "quiet-handle shape-handle", ariaLabel: "Adjust shape" });
    handle.addEventListener("pointerdown", (event) => {
      event.stopPropagation();
      try {
        handle.setPointerCapture(event.pointerId);
      } catch {
        // Moves still arrive while the pointer stays over the handle.
      }
    });
    handle.addEventListener("pointermove", (event) => {
      if (!handle.hasPointerCapture(event.pointerId)) return;
      anchors = anchors.slice();
      anchors[end] = toCanvasX(event.clientX * dpr, event.clientY * dpr);
      anchors[end + 1] = toCanvasY(event.clientX * dpr, event.clientY * dpr);
      place(handle, end);
    });
    handle.addEventListener("pointerup", () => {
      current = reshapeShape(current, shapeAnchors(current), anchors);
      undo();
      drawShape(kind, current, pressure);
    });
    guideHandles.append(handle);
    shapeHandleList.push([handle, () => place(handle, end)]);
    place(handle, end);
  }
}

// --- Print colors, as Cmyk.kt: how cyan, magenta, yellow, and black ink look on coated paper ----

let pickerInks = false;
let inks = null;  // The last ink amounts set with the CMYK sliders.
const BLACK_INK = 0.13;
const DULLER_BY = 0.14;
const INKS = [
  [1, 1, 1], [0, 0.68, 0.94], [0.93, 0, 0.55], [0.18, 0.19, 0.57],
  [1, 0.95, 0], [0, 0.65, 0.32], [0.93, 0.11, 0.14], [0.23, 0.21, 0.21],
];
const mix = (a, b, t) => a + (b - a) * t;

function toCmyk(hex) {
  const [red, green, blue] = hexToRgb(hex);
  const black = 1 - Math.max(red, green, blue);
  if (black >= 1) return [0, 0, 0, 1];
  const left = 1 - black;
  return [(1 - red - black) / left, (1 - green - black) / left, (1 - blue - black) / left, black];
}

function inkColor([cyan, magenta, yellow, black]) {
  const channel = (index) => {
    const noYellow = mix(mix(INKS[0][index], INKS[1][index], cyan), mix(INKS[2][index], INKS[3][index], cyan), magenta);
    const withYellow = mix(mix(INKS[4][index], INKS[5][index], cyan), mix(INKS[6][index], INKS[7][index], cyan), magenta);
    return Math.min(255, Math.max(0, Math.round(mix(noYellow, withYellow, yellow) * mix(1, BLACK_INK, black) * 255)));
  };
  return `#${[0, 1, 2].map((index) => channel(index).toString(16).padStart(2, "0")).join("").toUpperCase()}`;
}

const printedColor = (hex) => inkColor(toCmyk(hex));

// True when a color will look clearly different once printed.
function printsDuller(hex) {
  const before = hexToRgb(hex);
  const after = hexToRgb(printedColor(hex));
  return Math.max(...before.map((value, index) => Math.abs(value - after[index]))) > DULLER_BY;
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
    const printed = h("span", { className: "printed" });
    const printNote = h("div", { className: "print-note" }, printed,
      h("span", { className: "small grow" }, "Prints duller"),
      smallChip("Use this", false, () => {
        setColor(printedColor(color));
        rerender();
      }));
    const show = (value) => {
      current.style.background = value;
      hex.textContent = value;
      printed.style.background = printedColor(value);
      printNote.style.visibility = printsDuller(value) ? "visible" : "hidden";
    };
    body.append(gap(h("div", { className: "compare" },
      h("button", {
        className: "swatch", ariaLabel: "Previous color", style: { background: colorBeforePanel },
        onClick: () => {
          setColor(colorBeforePanel);
          rerender();
        },
      }),
      current, hex)));
    body.append(gap(segmented(["RGB", "CMYK"], pickerInks ? 1 : 0, (index) => {
      pickerInks = index === 1;
      rerender();
    })));
    if (pickerInks) {
      const amounts = inks && inkColor(inks) === color ? inks : toCmyk(color);
      inks = amounts;
      ["Cyan", "Magenta", "Yellow", "Black"].forEach((name, index) => {
        body.append(toolSlider(name, Math.round(amounts[index] * 100), (value) => {
          amounts[index] = value / 100;
          const next = inkColor(amounts);
          setColor(next);
          show(next);
        }));
      });
    } else {
      body.append(gap(colorPicker((value) => {
        setColor(value);
        show(value);
      })));
      body.append(gap(printNote));
      show(color);
    }
    body.append(gap(h("div", { className: "caption" }, "Values of this hue")), swatchRow(valueScale(color)));
    if (recentColors.length > 0) {
      body.append(gap(h("div", { className: "caption" }, "Recent")), swatchRow(recentColors, true));
    }
  } else if (colorTab === 1) {
    for (const [name, colors] of harmoniesFor(color)) {
      body.append(gap(h("div", { className: "caption" }, name)), swatchRow(colors));
    }
  } else {
    body.append(gap(h("div", { className: "wrap-row" },
      smallChip("From artwork", false, paletteFromArtwork),
      smallChip("From reference", false, () => {
        const reference = [...references.values()].pop();
        if (reference) referencePalette(reference);
        else say("Open a reference first");
      }),
      smallChip("Save recent", false, async () => {
        if (recentColors.length < 2) return say("Use at least two colors first");
        await store.addPalette("Recent colors", [...recentColors]);
        savedPalettes = await store.palettes();
        rerender();
      }))));
    for (const palette of savedPalettes) {
      const row = swatchRow(palette.colors);
      row.addEventListener("contextmenu", (event) => {
        event.preventDefault();
        confirmSheet(`Delete ${palette.name}?`, null, async () => {
          await store.deletePalette(palette.id);
          savedPalettes = await store.palettes();
          rerender();
        });
      });
      holdToRun(row, () => row.dispatchEvent(new MouseEvent("contextmenu", { cancelable: true })));
      body.append(gap(h("div", { className: "caption" }, palette.name)), row);
    }
    for (const [name, colors] of STARTER_PALETTES) {
      body.append(gap(h("div", { className: "caption" }, name)), swatchRow(colors));
    }
  }
  return body;
}

let savedPalettes = [];

// Calls run after a half-second hold, like a long press on Android.
function holdToRun(element, run) {
  let timer = 0;
  element.addEventListener("pointerdown", () => {
    timer = setTimeout(run, 500);
  });
  for (const type of ["pointerup", "pointerleave", "pointercancel"]) element.addEventListener(type, () => clearTimeout(timer));
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
    element.width = width * pixelRatio();
    element.height = height * pixelRatio();
    const c = element.getContext("2d");
    c.scale(pixelRatio(), pixelRatio());
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
    const square = box.height - (barHeight + gapHeight) * uiScale();
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
    draggingHue = event.clientY - box.top > box.height - (barHeight + gapHeight) * uiScale();
    move(event);
  });
  holder.addEventListener("pointermove", (event) => {
    if (holder.hasPointerCapture(event.pointerId)) move(event);
  });
  requestAnimationFrame(draw);
  return holder;
}

// --- 3x3 matrices, row-major like android.graphics.Matrix ---------------------------------------

const IDENTITY = [1, 0, 0, 0, 1, 0, 0, 0, 1];

function multiply3(a, b) {
  const out = new Array(9);
  for (let row = 0; row < 3; row++) {
    for (let column = 0; column < 3; column++) {
      out[row * 3 + column] = a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column];
    }
  }
  return out;
}

function invert3(m) {
  const [a, b, c, d, e, f, g, h2, i] = m;
  const A = e * i - f * h2;
  const B = -(d * i - f * g);
  const C = d * h2 - e * g;
  const det = a * A + b * B + c * C;
  if (Math.abs(det) < 1e-12) return null;
  return [A / det, -(b * i - c * h2) / det, (b * f - c * e) / det,
    B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
    C / det, -(a * h2 - b * g) / det, (a * e - b * d) / det];
}

function mapPoint(m, x, y) {
  const w = m[6] * x + m[7] * y + m[8];
  return [(m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w];
}

function viewMatrix() {
  const [a, b, c, d] = viewLinear();
  return [a, b, panX, c, d, panY, 0, 0, 1];
}
const translate3 = (x, y) => [1, 0, x, 0, 1, y, 0, 0, 1];
const scaleAround = (s, x, y) => [s, 0, x - s * x, 0, s, y - s * y, 0, 0, 1];
function rotateAround(angle, x, y) {
  const c = Math.cos(angle);
  const s = Math.sin(angle);
  return [c, -s, x - c * x + s * y, s, c, y - s * x - c * y, 0, 0, 1];
}

// The matrix that maps four points onto four others, as Matrix.setPolyToPoly() does.
function polyToPoly(src, dst) {
  const rows = [];
  const values = [];
  for (let i = 0; i < 4; i++) {
    const [x, y] = [src[i * 2], src[i * 2 + 1]];
    const [u, v] = [dst[i * 2], dst[i * 2 + 1]];
    rows.push([x, y, 1, 0, 0, 0, -u * x, -u * y]);
    values.push(u);
    rows.push([0, 0, 0, x, y, 1, -v * x, -v * y]);
    values.push(v);
  }
  // Gaussian elimination with partial pivoting.
  for (let col = 0; col < 8; col++) {
    let pivot = col;
    for (let r = col + 1; r < 8; r++) if (Math.abs(rows[r][col]) > Math.abs(rows[pivot][col])) pivot = r;
    if (Math.abs(rows[pivot][col]) < 1e-12) return null;
    [rows[col], rows[pivot]] = [rows[pivot], rows[col]];
    [values[col], values[pivot]] = [values[pivot], values[col]];
    for (let r = 0; r < 8; r++) {
      if (r === col) continue;
      const factor = rows[r][col] / rows[col][col];
      for (let c = col; c < 8; c++) rows[r][c] -= factor * rows[col][c];
      values[r] -= factor * values[col];
    }
  }
  const h8 = values.map((value, i) => value / rows[i][i]);
  return [h8[0], h8[1], h8[2], h8[3], h8[4], h8[5], h8[6], h8[7], 1];
}

// --- Transform, as CanvasView's transform mode and MainActivity.startTransform() ------------------

const TEXT_FIND_WIDTH = 512;
let transforming = false;
let transformLayer = 0;
let layerMatrix = IDENTITY;
let transformCorners = null;
let distorting = false;
const transformTouches = new Map();
let transformGesture = null;
const transformBar = h("div", { className: "tool-bar", hidden: true });
const handleLayer = h("div", { className: "handles" });
studioView.append(transformBar, handleLayer);

function publishTransform() {
  engine.HEAPF32.set(layerMatrix, pointsOffset);
  engine._cap_set_layer_transform(transformLayer);
  queueCompose();
  moveTransformHandles();
}

function startTransform() {
  const layer = activeLayer();
  if (!layer) return;
  closePanel();
  setEditingMask(false);
  transforming = true;
  transformLayer = layer.id;
  layerMatrix = IDENTITY;
  distorting = false;
  publishTransform();
  const distort = h("button", { className: "chip-dark" }, "Distort");
  distort.addEventListener("click", () => {
    distorting = !distorting;
    distort.classList.toggle("on", distorting);
    say(distorting ? "Drag a corner to bend the layer into perspective." : "Drag a corner to resize.");
  });
  transformBar.replaceChildren(h("div", { className: "pill-bar" }, distort,
    h("button", { className: "chip-dark", onClick: () => finishTransform(false) }, "Cancel"),
    h("button", { className: "chip-dark on", onClick: () => finishTransform(true) }, "Done")));
  transformBar.hidden = false;
  say("Drag to move. Drag a corner to resize. Pinch to rotate.");
  showTransformHandles(layer.id);
}

function finishTransform(keep) {
  if (!transforming) return;
  transforming = false;
  if (keep) {
    engine.HEAPF32.set(layerMatrix, pointsOffset);
    engine._cap_set_layer_transform(transformLayer);
    engine._cap_commit_layer_transform();
    recordStroke();
    markUnsaved();
  } else {
    engine.HEAPF32.fill(0, pointsOffset, pointsOffset + 9);
    engine._cap_set_layer_transform(0);
  }
  handleLayer.replaceChildren();
  transformCorners = null;
  transformBar.hidden = true;
  queueCompose();
}

// Finds what's drawn on the layer, so its corners can carry handles that work with a pen.
function showTransformHandles(id) {
  const width = TEXT_FIND_WIDTH;
  const height = Math.max(1, Math.floor(width * doc.height / doc.width));
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_layer_thumbnail(id, width, height, pointer);
  const small = engine.HEAPU8.subarray(pointer, pointer + size);
  let left = width;
  let top = height;
  let right = -1;
  let bottom = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (small[(y * width + x) * 4 + 3] <= 8) continue;
      left = Math.min(left, x);
      right = Math.max(right, x + 1);
      top = Math.min(top, y);
      bottom = Math.max(bottom, y + 1);
    }
  }
  engine._free(pointer);
  if (right < 0) return;
  const scale = doc.width / width;
  transformCorners = [left * scale, top * scale, right * scale, top * scale, right * scale, bottom * scale, left * scale, bottom * scale];
  handleLayer.replaceChildren(...[0, 1, 2, 3].map((corner) => transformHandle(corner)));
  moveTransformHandles();
}

function transformedToScreen(x, y) {
  const [lx, ly] = mapPoint(layerMatrix, x, y);
  const [sx, sy] = canvasToScreen(lx, ly);
  return [sx / dpr, sy / dpr];
}

function transformHandle(corner) {
  const handle = h("div", { className: "quiet-handle", ariaLabel: "Resize" });
  let lastDistance = 0;
  let startX = 0;
  let startY = 0;
  let startSpot = [0, 0];
  handle.addEventListener("pointerdown", (event) => {
    event.stopPropagation();
    handle.setPointerCapture(event.pointerId);
    startX = event.clientX;
    startY = event.clientY;
    startSpot = transformedToScreen(transformCorners[corner * 2], transformCorners[corner * 2 + 1]);
    const across = (corner + 2) % 4;
    const anchor = transformedToScreen(transformCorners[across * 2], transformCorners[across * 2 + 1]);
    lastDistance = Math.hypot(event.clientX - anchor[0], event.clientY - anchor[1]);
  });
  handle.addEventListener("pointermove", (event) => {
    if (!handle.hasPointerCapture(event.pointerId)) return;
    if (distorting) {
      moveTransformCorner(corner, startSpot[0] + event.clientX - startX, startSpot[1] + event.clientY - startY);
      return;
    }
    const across = (corner + 2) % 4;
    const anchor = transformedToScreen(transformCorners[across * 2], transformCorners[across * 2 + 1]);
    const distance = Math.hypot(event.clientX - anchor[0], event.clientY - anchor[1]);
    if (lastDistance > 1 && distance > 1) {
      applyTransform(scaleAround(distance / lastDistance, anchor[0] * dpr, anchor[1] * dpr));
      lastDistance = distance;
    }
  });
  return handle;
}

function moveTransformHandles() {
  if (!transformCorners) return;
  [...handleLayer.children].forEach((handle, corner) => {
    const [x, y] = transformedToScreen(transformCorners[corner * 2], transformCorners[corner * 2 + 1]);
    handle.style.left = `${toUi(x) - 18}px`;
    handle.style.top = `${toUi(y) - 18}px`;
  });
}

// Applies a change made on screen, in buffer pixels, to the layer's own transform.
function applyTransform(change) {
  const view = viewMatrix();
  const inverse = invert3(view);
  if (!inverse) return;
  layerMatrix = multiply3(inverse, multiply3(change, multiply3(view, layerMatrix)));
  publishTransform();
}

// Pins one corner to a point on screen while the other three stay, bending the layer.
function moveTransformCorner(corner, screenX, screenY) {
  const inverse = invert3(viewMatrix());
  const target = [];
  for (let index = 0; index < 4; index++) {
    const [x, y] = index === corner ? [screenX, screenY] : transformedToScreen(transformCorners[index * 2], transformCorners[index * 2 + 1]);
    target.push(...mapPoint(inverse, x * dpr, y * dpr));
  }
  const bent = polyToPoly(transformCorners, target);
  if (!bent) return;
  layerMatrix = bent;
  publishTransform();
}

// One pointer moves the layer; two pinch, turn, and move it together.
function transformPointer(event, type) {
  const x = event.clientX * dpr;
  const y = event.clientY * dpr;
  if (type === "down") {
    capture(event);
    transformTouches.set(event.pointerId, [x, y]);
    transformGesture = null;
    return;
  }
  if (!transformTouches.has(event.pointerId)) return;
  if (type === "up") {
    transformTouches.delete(event.pointerId);
    transformGesture = null;
    return;
  }
  transformTouches.set(event.pointerId, [x, y]);
  const points = [...transformTouches.values()].slice(0, 2);
  const cx = points.reduce((s, p) => s + p[0], 0) / points.length;
  const cy = points.reduce((s, p) => s + p[1], 0) / points.length;
  const span = points.length === 2 ? Math.hypot(points[1][0] - points[0][0], points[1][1] - points[0][1]) : 0;
  const angle = points.length === 2 ? Math.atan2(points[1][1] - points[0][1], points[1][0] - points[0][0]) : 0;
  if (transformGesture && transformGesture.count === points.length) {
    let change = translate3(cx - transformGesture.x, cy - transformGesture.y);
    if (points.length === 2 && transformGesture.span > 1 && span > 1) {
      change = multiply3(scaleAround(span / transformGesture.span, cx, cy), change);
      change = multiply3(rotateAround(angle - transformGesture.angle, cx, cy), change);
    }
    applyTransform(change);
  }
  transformGesture = { x: cx, y: cy, span, angle, count: points.length };
}

// --- Liquify, as MainActivity.startLiquify() ------------------------------------------------------

const LIQUIFY_TOOLS = ["Push", "Grow", "Shrink", "Restore"];
let liquifying = false;
let liquifyTool = 0;
let liquifyStrength = 0.5;
let liquifyPointer = -1;
let liquifyLast = [0, 0];
let liquifyRing = null;

function startLiquify() {
  const layer = activeLayer();
  if (!layer) return;
  closePanel();
  setEditingMask(false);
  liquifying = true;
  liquifyTool = 0;
  engine._cap_begin_liquify(layer.id);
  const build = () => {
    transformBar.replaceChildren(h("div", { className: "pill-bar" },
      h("div", { className: "liquify-tools" }, segmented(LIQUIFY_TOOLS, liquifyTool, (index) => {
        liquifyTool = index;
        build();
      })),
      h("input", {
        className: "range liquify-strength", type: "range", min: "0", max: "100", value: String(Math.round(liquifyStrength * 100)), ariaLabel: "Strength",
        onInput: (event) => { liquifyStrength = Math.max(Number(event.target.value), 5) / 100; },
      }),
      h("button", { className: "chip-dark", onClick: () => finishLiquify(false) }, "Cancel"),
      h("button", { className: "chip-dark on", onClick: () => finishLiquify(true) }, "Done")));
  };
  build();
  transformBar.hidden = false;
  say("Push the image with your pen. Your size slider sets the brush.", true);
  queueCompose();
}

function finishLiquify(keep) {
  if (!liquifying) return;
  liquifying = false;
  if (keep) {
    engine._cap_commit_liquify();
    recordStroke();
    markUnsaved();
  } else {
    engine._cap_cancel_liquify();
  }
  transformBar.hidden = true;
  liquifyRing = null;
  drawOverlay();
  queueCompose();
}

// Shows the reach of the liquify brush, as BrushRing does.
function showLiquifyRing(event) {
  liquifyRing = [event.clientX * dpr, event.clientY * dpr, settingsFor(brush).diameter / 2 * zoom];
  drawOverlay();
}

function liquifyPointerEvent(event, type) {
  showLiquifyRing(event);
  const x = toCanvasX(event.clientX * dpr, event.clientY * dpr);
  const y = toCanvasY(event.clientX * dpr, event.clientY * dpr);
  if (type === "down") {
    capture(event);
    liquifyPointer = event.pointerId;
    liquifyLast = [x, y];
    return;
  }
  if (event.pointerId !== liquifyPointer) return;
  if (type === "up") {
    liquifyPointer = -1;
    return;
  }
  const events = event.getCoalescedEvents ? event.getCoalescedEvents() : [event];
  for (const sample of events.length ? events : [event]) {
    const sx = toCanvasX(sample.clientX * dpr, sample.clientY * dpr);
    const sy = toCanvasY(sample.clientX * dpr, sample.clientY * dpr);
    const pressure = sample.pointerType === "mouse" ? 0.5 : sample.pressure;
    const strength = liquifyStrength * Math.min(1, Math.max(0.2, pressure));
    engine._cap_liquify_dab(liquifyLast[0], liquifyLast[1], sx, sy, settingsFor(brush).diameter / 2, strength, liquifyTool);
    liquifyLast = [sx, sy];
  }
  queueCompose();
}

// --- Adjust and filters, as MainActivity.adjustPanel() and filterPanel() ---------------------------

const FILTERS = [
  { label: "Blur", id: 1, sliders: [["Radius", 0, 120, 12]] },
  { label: "Motion blur", id: 2, sliders: [["Distance", 0, 240, 40], ["Angle", 0, 3.1416, 0]] },
  { label: "Sharpen", id: 3, sliders: [["Amount", 0, 3, 1]] },
  { label: "Noise", id: 4, sliders: [["Amount", 0, 0.5, 0.1]] },
  { label: "Gradient map", id: 5, sliders: [["Amount", 0, 1, 1]], usesColor: true },
  { label: "Levels", id: 6, sliders: [["Black point", 0, 0.5, 0], ["Midtones", 0.3, 3, 1], ["White point", 0.5, 1, 1]] },
  { label: "Depth fade", id: 7, sliders: [["Amount", 0, 1, 0.3]], usesColor: true },
];
let adjustment = [0, 1, 0, 0, 0, 0];
let filter = FILTERS[0];
let filterValues = [0, 0, 0, 0];

function pushAdjustment() {
  engine.HEAPF32.set(adjustment, pointsOffset);
  engine._cap_set_adjustment(1);
  queueCompose();
}

function adjustPanel() {
  adjustment = [0, 1, 0, 0, 0, 0];
  pushAdjustment();
  const body = h("div", { className: "column" }, h("div", { className: "title" }, "Adjust layer"));
  const slider = (title, index, low, high) => body.append(toolSlider(title, 50, (value) => {
    adjustment[index] = low + (high - low) * value / 100;
    pushAdjustment();
  }));
  slider("Hue", 0, -0.5, 0.5);
  slider("Saturation", 1, 0, 2);
  slider("Brightness", 2, -0.5, 0.5);
  slider("Shadows", 3, -0.5, 0.5);
  slider("Midtones", 4, -0.5, 0.5);
  slider("Highlights", 5, -0.5, 0.5);
  body.append(gap(h("div", { className: "chips" },
    smallChip("Cancel", false, () => closePanel()),
    smallChip("Apply", true, () => {
      panelKey = "";
      panel.hidden = true;
      pushAdjustment();
      engine._cap_commit_adjustment(0);
      recordStroke();
      markUnsaved();
      queueCompose();
    }))));
  return body;
}

function pushFilter() {
  engine.HEAPF32.set(filterValues, pointsOffset);
  const rgb = hexToRgb(color);
  engine._cap_set_filter(1, filter.id, rgb[0], rgb[1], rgb[2]);
  queueCompose();
}

function chooseFilter(kind) {
  filter = kind;
  filterValues = [0, 0, 0, 0];
  kind.sliders.forEach(([, , , start], index) => { filterValues[index] = start; });
  pushFilter();
  rerender();
}

function openFilters() {
  setEditingMask(false);
  closePanel();
  filter = filter || FILTERS[0];
  filterValues = [0, 0, 0, 0];
  filter.sliders.forEach(([, , , start], index) => { filterValues[index] = start; });
  pushFilter();
  showPanel("filter", filterPanel);
}

function filterPanel() {
  const body = h("div", { className: "column" }, h("div", { className: "title" }, "Filters"),
    gap(h("div", { className: "chips wrap" }, FILTERS.map((kind) => smallChip(kind.label, kind === filter, () => chooseFilter(kind))))));
  filter.sliders.forEach(([label, low, high], index) => {
    const span = high - low;
    body.append(toolSlider(label, Math.round((filterValues[index] - low) / span * 100), (value) => {
      filterValues[index] = low + span * value / 100;
      pushFilter();
    }));
  });
  if (filter.usesColor) body.append(gap(h("div", { className: "small" }, "Uses your current color.")));
  body.append(gap(h("div", { className: "chips" },
    smallChip("Cancel", false, () => closePanel()),
    smallChip("Apply", true, () => {
      panelKey = "";
      panel.hidden = true;
      pushFilter();
      engine._cap_commit_filter();
      recordStroke();
      markUnsaved();
      queueCompose();
    }))));
  return body;
}

// --- Actions, as MainActivity.actionsPanel() -----------------------------------------------------

const TEXT_SIZES = [0.03, 0.05, 0.09];
const FINISH_NOTE_DELAY_MS = 3500;
let penButtonAction = loadPreference("penButton", 0);  // 0 picks a color, 1 erases, 2 undoes.
let variationFamily = [];

function actionsPanel() {
  const row = (icon, title, detail, click) => h("button", { className: "action-row", onClick: click },
    svg(icon), h("div", { className: "text" }, h("div", {}, title), detail && h("div", { className: "detail" }, detail)));
  const body = h("div", { className: "column" },
    h("div", { className: "title" }, doc.manifest.name),
    h("div", { className: "small" }, `${doc.width} x ${doc.height} pixels · ${layerSlots()} of ${maxLayers} layers`),
    gap(row("rename", "Rename artwork", null, () => {
      closePanel();
      askSheet("Rename artwork", doc.manifest.name, (name) => {
        doc.manifest.name = name;
        markUnsaved();
      });
    })),
    gap(row("text", "Add text", "Label a design sheet", () => {
      closePanel();
      askForText();
    })),
    gap(row("copy", "Make a sticker", "Add a die-cut border around your drawing", () => openStickerPanel())),
    gap(row("palette", "Color variants", "Try this design in new colors", () => {
      closePanel();
      showPanel("variants", variantsPanel);
    })),
    gap(h("div", { className: "caption" }, "Variations")),
    gap(row("duplicate", "Make a variation", "Try another version, keeping this one", () => makeVariation())));
  if (variationFamily.length > 1) {
    body.append(gap(h("div", { className: "variation-grid" }, variationFamily.map((art) => {
      const image = h("img", { className: art.id === doc.id ? "variation-thumb on" : "variation-thumb", alt: `${art.name} preview` });
      store.imageUrl(art.id, false).then((url) => { if (url) image.src = url; });
      return h("button", { className: "variation-cell", onClick: () => { if (art.id !== doc.id) openVariation(art.id); } },
        image, h("span", { className: "small", translate: false }, art.name));
    }))));
  }
  body.append(
    gap(row("video", doc.manifest.animation ? "Stop animating" : "Animate", "Each layer becomes a frame", () => {
      closePanel();
      setAnimation(!doc.manifest.animation);
    })),
    gap(h("div", { className: "caption" }, "Export")),
    gap(row("layers", "Design sheet", "All variations on one page", () => { closePanel(); exportSheet(); })),
    gap(row("export", "Frames as PNGs", "One image per thumbnail frame", () => { closePanel(); exportFrames(); })),
    gap(row("export", "PNG image", store.folderName ? `Flattened, saved to ${store.folderName}/Exports` : "Flattened, saved to your downloads", () => { closePanel(); exportPng(); })),
    gap(row("layersfile", "PSD file", "Keeps your layers", () => { closePanel(); exportPsd(); })),
    gap(row("video", "Time-lapse", "A video of your progress", () => { closePanel(); askTimelapseLength(); })),
    gap(h("div", { className: "caption" }, "Language")),
    gap(segmented(LANGUAGES.map(([, label]) => label), LANGUAGES.findIndex(([code]) => code === language), async (index) => {
      const [code] = LANGUAGES[index];
      if (code === language) return;
      saveLanguage(code);
      await leaveStudio();
      location.reload();
    })),
    gap(h("div", { className: "caption" }, "Pen button")),
    gap(segmented(["Pick color", "Eraser", "Undo"], penButtonAction, (action) => {
      penButtonAction = action;
      savePreference("penButton", action);
      rerender();
    })),
    gap(h("div", { className: "caption" }, "Companion")),
    gap(row("companion", companionVisible ? "Hide companion" : "Wake companion", null, () => setCompanionVisible(!companionVisible))));
  if (doc && !doc.warmup) {
    store.variations(doc.id).then((family) => {
      const changed = family.map((art) => art.id).join() !== variationFamily.map((art) => art.id).join();
      variationFamily = family;
      if (changed && panelKey === "actions") rerender();
    }).catch(() => {});
  }
  return body;
}

// --- Text layers ------------------------------------------------------------------------------

function askForText(editing = null) {
  let size = editing ? Math.max(0, TEXT_SIZES.findIndex((it) => it >= (editing.textSize || 0) - 0.001)) : 1;
  const input = h("textarea", { className: "field text-field", placeholder: "Your label", value: editing?.text || "", rows: 2 });
  const sizes = h("div", {});
  const showSizes = () => sizes.replaceChildren(segmentedLight(["Small", "Medium", "Large"], size, (index) => {
    size = index;
    showSizes();
  }));
  showSizes();
  let close;
  const body = h("div", { className: "sheet-body small-body" },
    h("div", { className: "row" }, h("div", { className: "grow" }, serifTitle(editing ? "Edit text" : "Add text")),
      h("button", { className: "chip-cream", onClick: () => close() }, "Cancel")),
    h("div", { className: "gap14" }, input),
    h("div", { className: "gap14" }, sizes),
    h("div", { className: "end gap16" }, h("button", {
      className: "sheet-button",
      onClick: () => {
        const text = input.value;
        if (!text.trim()) return;
        close();
        if (editing) replaceText(editing, text, TEXT_SIZES[size]);
        else addText(text, TEXT_SIZES[size]);
      },
    }, editing ? "Save" : "Add")));
  close = sheet(body, { small: true });
  input.focus();
}

function serifTitle(text) {
  return h("div", { className: "serif", style: { fontSize: "22px" } }, text);
}

// The cream sheets' segmented choice.
function segmentedLight(options, selected, pick) {
  return h("div", { className: "segmented light" }, options.map((option, index) =>
    h("button", { className: index === selected ? "on" : "", onClick: () => pick(index) }, option)));
}

function drawTextPage(text, size, textColor, centerX, centerY) {
  const surface = new OffscreenCanvas(doc.width, doc.height);
  const c = surface.getContext("2d");
  c.fillStyle = textColor;
  c.font = `${size}px Roboto, "Segoe UI", system-ui, sans-serif`;
  c.textAlign = "center";
  const lines = text.split("\n");
  const top = centerY - lines.length * size / 2;
  lines.forEach((line, index) => c.fillText(line, centerX, top + (index + 1) * size));
  return premultiplied(c.getImageData(0, 0, doc.width, doc.height).data);
}

function addText(text, heightShare) {
  if (layerSlots() >= maxLayers) return sayFull();
  setEditingMask(false);
  const pixels = drawTextPage(text, doc.height * heightShare, color, doc.width / 2, doc.height / 2);
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  engine._cap_create_layer(id);
  writeLayerPixels(id, pixels);
  engine._cap_mark_layer_dirty(id);
  editLayers(() => {
    const index = layers().findIndex((layer) => layer.id === doc.manifest.activeLayer) + 1;
    layers().splice(index, 0, { ...newLayer(id, text.split("\n")[0].slice(0, 24)), text, textSize: heightShare, textColor: colorInt(color) });
    doc.manifest.activeLayer = id;
  });
  if (transform) startTransform();
}

// Swaps in freshly set text where the old text now sits, so editing it keeps your placement.
function replaceText(layer, text, heightShare) {
  const width = TEXT_FIND_WIDTH;
  const height = Math.max(1, Math.floor(width * doc.height / doc.width));
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_layer_thumbnail(layer.id, width, height, pointer);
  const small = engine.HEAPU8.slice(pointer, pointer + size);
  engine._free(pointer);
  let left = width;
  let top = height;
  let right = -1;
  let bottom = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (small[(y * width + x) * 4 + 3] <= 8) continue;
      left = Math.min(left, x);
      right = Math.max(right, x);
      top = Math.min(top, y);
      bottom = Math.max(bottom, y);
    }
  }
  const found = right >= 0;
  const centerX = found ? (left + right + 1) / 2 / width * doc.width : doc.width / 2;
  const centerY = found ? (top + bottom + 1) / 2 / height * doc.height : doc.height / 2;
  const pixels = drawTextPage(text, doc.height * heightShare, colorHex(layer.textColor), centerX, centerY);
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  engine._cap_create_layer(id);
  writeLayerPixels(id, pixels);
  engine._cap_mark_layer_dirty(id);
  editLayers(() => {
    const index = layers().findIndex((it) => it.id === layer.id);
    layers()[index] = { ...structuredClone(layer), id, name: text.split("\n")[0].slice(0, 24), text, textSize: heightShare, file: "", tiles: null, mask: 0, maskFile: "", maskTiles: null };
    doc.manifest.activeLayer = id;
  });
  refreshLayers();
}

// Android keeps colors as signed ARGB ints in the manifest.
function colorInt(hex) {
  return (0xFF000000 | parseInt(hex.slice(1), 16)) | 0;
}

function colorHex(value) {
  return `#${((value >>> 0) & 0xFFFFFF).toString(16).padStart(6, "0").toUpperCase()}`;
}

// --- Variations -------------------------------------------------------------------------------

async function makeVariation() {
  closePanel();
  await save(true);
  const id = await store.makeVariation(doc.id);
  say("Saved a variation. This one stays as it was.");
  await openArtwork(id);
}

async function openVariation(id) {
  closePanel();
  if (unsaved || previewStale) await save(true);
  await openArtwork(id);
}

// --- Exports ----------------------------------------------------------------------------------

function safeName(name) {
  return (name || "Untitled").replace(/[\\/:*?"<>|]/g, "-").trim() || "Untitled";
}

function readComposite(width, height) {
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_composite(width, height, pointer);
  const pixels = engine.HEAPU8.slice(pointer, pointer + size);
  engine._free(pointer);
  return pixels;
}

function readFullComposite() {
  const size = doc.width * doc.height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_composite(doc.width, doc.height, pointer);
  const pixels = engine.HEAPU8.slice(pointer, pointer + size);
  engine._free(pointer);
  return pixels;
}

async function pngBlob(pixels, width, height) {
  const surface = new OffscreenCanvas(width, height);
  surface.getContext("2d").putImageData(new ImageData(new Uint8ClampedArray(pixels.buffer, pixels.byteOffset, pixels.length), width, height), 0, 0);
  return surface.convertToBlob({ type: "image/png" });
}

async function exportPng() {
  const name = doc.manifest.name;
  try {
    const where = await store.saveExport(`${safeName(name)}.png`, await pngBlob(readFullComposite(), doc.width, doc.height));
    say(`Saved to ${where}`, true);
    celebrateFinish();
  } catch (error) {
    console.error(error);
    say("Couldn't export this PNG", true);
  }
}

// PSD layers carry their masks baked in, so they look the same in other apps.
function applyMaskPixels(pixels, mask) {
  for (let index = 0; index < pixels.length; index += 4) {
    const hidden = mask[index + 3];
    if (hidden === 0) continue;
    for (let channel = 0; channel < 4; channel++) pixels[index + channel] = Math.floor(pixels[index + channel] * (255 - hidden) / 255);
  }
}

function readLayerPixels(id) {
  const size = doc.width * doc.height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_layer(id, pointer);
  const pixels = engine.HEAPU8.slice(pointer, pointer + size);
  engine._free(pointer);
  return pixels;
}

async function exportPsd() {
  say("Exporting PSD");
  await new Promise((resolve) => setTimeout(resolve, 30));
  try {
    const parts = layers().map((layer) => {
      const pixels = readLayerPixels(layer.id);
      if (layer.mask && layer.maskEnabled !== false) applyMaskPixels(pixels, readLayerPixels(layer.mask));
      return { layer, pixels };
    });
    const flat = readFullComposite();
    const psdLayers = withPsdGroups(
      parts.map(({ layer, pixels }) => ({ name: layer.name, opacity: layer.opacity, visible: layer.visible, blend: layer.blend || 0, clipped: !!layer.clip, rgba: pixels, section: 0 })),
      (index) => groupsOf().find((group) => group.id === parts[index].layer.group) || null,
      (group) => psdFolder(group.name, group.opacity, group.visible),
    );
    const bytes = writePsd(doc.width, doc.height, psdLayers, flat);
    const where = await store.saveExport(`${safeName(doc.manifest.name)}.psd`, new Blob([bytes], { type: "image/vnd.adobe.photoshop" }));
    say(`Saved to ${where}`, true);
  } catch (error) {
    console.error(error);
    say("Couldn't export this PSD", true);
  }
}

// After an export, the companion looks back at how far the piece came.
async function celebrateFinish() {
  const now = latestSnapshot;
  if (!now || !doc) return;
  const first = await store.firstSnapshot(doc).catch(() => null);
  if (!first) return;
  const note = finishNote(first, now);
  setTimeout(() => { if (doc) say(note, true); }, FINISH_NOTE_DELAY_MS);
}

// --- Picking a color, with a loupe ring that shows the new color over the previous one ---------

const LONG_PRESS_MS = 500;
let pickRing = null;
let pickedColor = null;
let pickTimer = 0;

function pickAt(clientX, clientY) {
  const x = Math.floor(toCanvasX(clientX * dpr, clientY * dpr));
  const y = Math.floor(toCanvasY(clientX * dpr, clientY * dpr));
  if (x < 0 || y < 0 || x >= canvasWidth || y >= canvasHeight) return;
  const value = engine._cap_pick_color(x, y) >>> 0;
  pickedColor = `#${(value & 0xFFFFFF).toString(16).padStart(6, "0").toUpperCase()}`;
  showPickRing(pickedColor, clientX, clientY);
}

function showPickRing(next, x, y) {
  if (!pickRing) {
    pickRing = h("canvas", { className: "pick-ring", width: 96 * pixelRatio(), height: 96 * pixelRatio() });
    studioView.append(pickRing);
  }
  pickRing.style.left = `${toUi(x) - 48}px`;
  pickRing.style.top = `${toUi(y) - 120}px`;
  const c = pickRing.getContext("2d");
  const size = pickRing.width;
  const thickness = size * 0.16;
  const radius = size / 2 - thickness / 2 - 2;
  c.clearRect(0, 0, size, size);
  c.lineWidth = thickness + 4;
  c.strokeStyle = "#FFFFFF";
  c.beginPath();
  c.arc(size / 2, size / 2, radius, 0, Math.PI * 2);
  c.stroke();
  c.lineWidth = thickness;
  c.strokeStyle = next;
  c.beginPath();
  c.arc(size / 2, size / 2, radius, Math.PI, Math.PI * 2);
  c.stroke();
  c.strokeStyle = color;
  c.beginPath();
  c.arc(size / 2, size / 2, radius, 0, Math.PI);
  c.stroke();
}

function endPick() {
  clearTimeout(pickTimer);
  if (!pickRing) return;
  pickRing.remove();
  pickRing = null;
  if (pickedColor) {
    setColor(pickedColor);
    rememberColor();
  }
  pickedColor = null;
}

// --- Stickers, as Sticker.kt and MainActivity's sticker panel -------------------------------------

const STICKER_SIDE = 1024;
const STICKER_PREVIEW = 300;
const STICKER_PREVIEW_STEP = 4;
const MAX_BORDER = 0.05;
const SHADOW_ALPHA = 90;
const SOLID_ALPHA = 24;
const FULL_CANVAS_SHARE = 0.97;
const FAR = 1e12;
let stickerBorder = 0.35;
let stickerColor = "#FFFFFF";
let stickerShadow = true;
let sticker = null;  // {alpha, small, art, width, height}

// Squared distance along one row or column to the nearest cell in cost.
function nearestAlong(cost, size, output, sites, edges) {
  let top = 0;
  sites[0] = 0;
  edges[0] = -FAR;
  edges[1] = FAR;
  for (let cell = 1; cell < size; cell++) {
    let crossing;
    for (;;) {
      const site = sites[top];
      crossing = ((cost[cell] + cell * cell) - (cost[site] + site * site)) / (2 * (cell - site));
      if (crossing > edges[top] || top === 0) break;
      top--;
    }
    top++;
    sites[top] = cell;
    edges[top] = crossing;
    edges[top + 1] = FAR;
  }
  top = 0;
  for (let cell = 0; cell < size; cell++) {
    while (edges[top + 1] < cell) top++;
    const away = cell - sites[top];
    output[cell] = away * away + cost[sites[top]];
  }
}

function distances(solid, width, height) {
  const squared = new Float64Array(solid.length);
  for (let i = 0; i < solid.length; i++) squared[i] = solid[i] ? 0 : FAR;
  const longest = Math.max(width, height);
  const line = new Float64Array(longest);
  const result = new Float64Array(longest);
  const sites = new Int32Array(longest + 1);
  const edges = new Float64Array(longest + 2);
  for (let x = 0; x < width; x++) {
    for (let y = 0; y < height; y++) line[y] = squared[y * width + x];
    nearestAlong(line, height, result, sites, edges);
    for (let y = 0; y < height; y++) squared[y * width + x] = result[y];
  }
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) line[x] = squared[y * width + x];
    nearestAlong(line, width, result, sites, edges);
    for (let x = 0; x < width; x++) squared[y * width + x] = result[x];
  }
  return squared;
}

// The die-cut shape: everything within radius of the drawing, with holes filled.
function stickerShape(alpha, width, height, radius) {
  const solid = new Uint8Array(alpha.length);
  let any = false;
  for (let i = 0; i < alpha.length; i++) if (alpha[i] >= SOLID_ALPHA) { solid[i] = 1; any = true; }
  if (!any) return null;
  const squared = distances(solid, width, height);
  const shape = new Uint8Array(alpha.length);
  for (let i = 0; i < shape.length; i++) shape[i] = Math.trunc(Math.min(1, Math.max(0, radius - Math.sqrt(squared[i]) + 0.5)) * 255);
  const outside = new Uint8Array(alpha.length);
  const pending = [];
  const visit = (index) => {
    if (!outside[index] && shape[index] === 0) {
      outside[index] = 1;
      pending.push(index);
    }
  };
  for (let x = 0; x < width; x++) {
    visit(x);
    visit((height - 1) * width + x);
  }
  for (let y = 0; y < height; y++) {
    visit(y * width);
    visit(y * width + width - 1);
  }
  while (pending.length) {
    const index = pending.pop();
    const x = index % width;
    if (x > 0) visit(index - 1);
    if (x < width - 1) visit(index + 1);
    if (index >= width) visit(index - width);
    if (index < alpha.length - width) visit(index + width);
  }
  for (let i = 0; i < shape.length; i++) if (shape[i] === 0 && !outside[i]) shape[i] = 255;
  return shape;
}

// Shrinks a drawing's alpha, keeping thin marks, so the preview can follow a slider.
function shrinkAlpha(alpha, width, height, step) {
  const outWidth = Math.floor(width / step);
  const outHeight = Math.floor(height / step);
  const result = new Uint8Array(outWidth * outHeight);
  for (let y = 0; y < outHeight * step; y++) {
    for (let x = 0; x < outWidth * step; x++) {
      const target = Math.floor(y / step) * outWidth + Math.floor(x / step);
      result[target] = Math.max(result[target], alpha[y * width + x]);
    }
  }
  return result;
}

const stickerLayers = () => layers().filter((layer) => layer.visible && !layer.sticker && !groupsOf().some((group) => group.id === layer.group && !group.visible));

// Turns a premultiplied engine thumbnail into an image a 2D canvas can draw.
function unpremultipliedImage(pixels, width, height) {
  const out = new Uint8ClampedArray(pixels.length);
  for (let i = 0; i < pixels.length; i += 4) {
    const alpha = pixels[i + 3];
    const scale = alpha ? 255 / alpha : 0;
    out[i] = pixels[i] * scale;
    out[i + 1] = pixels[i + 1] * scale;
    out[i + 2] = pixels[i + 2] * scale;
    out[i + 3] = alpha;
  }
  const surface = new OffscreenCanvas(width, height);
  surface.getContext("2d").putImageData(new ImageData(out, width, height), 0, 0);
  return surface;
}

function readThumbnail(id, width, height) {
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_layer_thumbnail(id, width, height, pointer);
  const pixels = engine.HEAPU8.slice(pointer, pointer + size);
  engine._free(pointer);
  return pixels;
}

// Reads a small copy of the drawing once, so the border slider can answer right away.
function openStickerPanel() {
  const longest = Math.max(doc.width, doc.height);
  const width = Math.max(1, Math.floor(doc.width * STICKER_SIDE / longest));
  const height = Math.max(1, Math.floor(doc.height * STICKER_SIDE / longest));
  const art = new OffscreenCanvas(width, height);
  const painter = art.getContext("2d");
  const alpha = new Uint8Array(width * height);
  for (const layer of stickerLayers()) {
    const pixels = readThumbnail(layer.id, width, height);
    for (let i = 0; i < alpha.length; i++) alpha[i] = Math.max(alpha[i], Math.trunc(pixels[i * 4 + 3] * layer.opacity));
    painter.globalAlpha = layer.opacity;
    painter.drawImage(unpremultipliedImage(pixels, width, height), 0, 0);
  }
  sticker = { alpha, small: null, art, width, height };
  closePanel();
  showPanel("sticker", stickerPanel);
}

const stickerRadius = () => Math.max(1, stickerBorder * MAX_BORDER * STICKER_SIDE);

// The border, with its shadow behind it, drawn at any size.
function paintSticker(target, shape, shapeWidth, shapeHeight, outputWidth, outputHeight) {
  const [r, g, b] = hexToRgb(stickerColor).map((value) => Math.round(value * 255));
  const scale = outputWidth / shapeWidth;
  const small = new OffscreenCanvas(shapeWidth, shapeHeight);
  const pixels = new Uint8ClampedArray(shape.length * 4);
  // Sharpens an enlarged edge, so a small shape still cuts a clean line.
  const enlarged = scale > 1;
  for (let i = 0; i < shape.length; i++) {
    pixels[i * 4] = r;
    pixels[i * 4 + 1] = g;
    pixels[i * 4 + 2] = b;
    pixels[i * 4 + 3] = shape[i];
  }
  small.getContext("2d").putImageData(new ImageData(pixels, shapeWidth, shapeHeight), 0, 0);
  if (stickerShadow) {
    const blur = 6 * scale;
    const shadow = new OffscreenCanvas(outputWidth, outputHeight);
    const c = shadow.getContext("2d");
    c.drawImage(small, 0, 0, outputWidth, outputHeight);
    c.globalCompositeOperation = "source-in";
    c.fillStyle = "#000000";
    c.fillRect(0, 0, outputWidth, outputHeight);
    target.save();
    target.filter = `blur(${blur * 0.57735 + 0.5}px)`;
    target.globalAlpha = SHADOW_ALPHA / 255;
    target.drawImage(shadow, 0, blur * 0.6);
    target.restore();
  }
  if (enlarged) {
    const edge = new OffscreenCanvas(outputWidth, outputHeight);
    const c = edge.getContext("2d");
    c.imageSmoothingEnabled = true;
    c.drawImage(small, 0, 0, outputWidth, outputHeight);
    const data = c.getImageData(0, 0, outputWidth, outputHeight);
    for (let i = 3; i < data.data.length; i += 4) data.data[i] = Math.min(255, Math.max(0, data.data[i] * 5 - 510));
    c.putImageData(data, 0, 0);
    target.drawImage(edge, 0, 0);
  } else {
    target.drawImage(small, 0, 0, outputWidth, outputHeight);
  }
}

function stickerPanel() {
  const body = h("div", { className: "column" }, h("div", { className: "title" }, "Make a sticker"));
  const filled = sticker ? sticker.alpha.filter((value) => value > 24).length / sticker.alpha.length : 0;
  if (filled === 0) {
    body.append(gap(h("div", { className: "caption" }, "Draw something first.")));
    return body;
  }
  const preview = h("canvas", { className: "sticker-preview", ariaLabel: "Sticker preview" });
  body.append(gap(preview));
  if (filled >= FULL_CANVAS_SHARE) {
    body.append(gap(h("div", { className: "small" }, "Your drawing fills the canvas. Keep the background on its own hidden layer for a shaped sticker.")));
  }
  body.append(toolSlider("Border size", Math.round(stickerBorder * 100), (value) => {
    stickerBorder = value / 100;
    drawStickerPreview(preview);
  }));
  body.append(gap(h("div", { className: "chips wrap" },
    [["White", "#FFFFFF"], ["Black", "#000000"], ["Your color", color]].map(([name, value]) => smallChip(name, stickerColor === value, () => {
      stickerColor = value;
      rerender();
    })),
    smallChip("Shadow", stickerShadow, () => {
      stickerShadow = !stickerShadow;
      rerender();
    }))));
  body.append(gap(h("div", { className: "chips wrap" },
    smallChip("Add sticker layer", true, () => makeSticker(false)),
    smallChip("Save as PNG", false, () => makeSticker(true)))));
  requestAnimationFrame(() => drawStickerPreview(preview));
  return body;
}

function drawStickerPreview(preview) {
  if (!sticker) return;
  const radius = stickerRadius() / STICKER_PREVIEW_STEP;
  const shapeWidth = Math.floor(sticker.width / STICKER_PREVIEW_STEP);
  const shapeHeight = Math.floor(sticker.height / STICKER_PREVIEW_STEP);
  sticker.small ||= shrinkAlpha(sticker.alpha, sticker.width, sticker.height, STICKER_PREVIEW_STEP);
  const shape = stickerShape(sticker.small, shapeWidth, shapeHeight, radius);
  if (!shape) return;
  const height = Math.floor(STICKER_PREVIEW * sticker.height / sticker.width);
  preview.width = STICKER_PREVIEW;
  preview.height = height;
  const c = preview.getContext("2d");
  c.fillStyle = "#3A291F";
  c.fillRect(0, 0, STICKER_PREVIEW, height);
  paintSticker(c, shape, shapeWidth, shapeHeight, STICKER_PREVIEW, height);
  c.drawImage(sticker.art, 0, 0, STICKER_PREVIEW, height);
}

// Puts the drawing and its border together on one new top layer, replacing an older sticker.
async function makeSticker(exportIt) {
  if (!sticker) return;
  const old = layers().filter((layer) => layer.sticker);
  if (!old.length && layerSlots() >= maxLayers) return sayFull();
  closePanel();
  say("Making your sticker");
  await new Promise((resolve) => setTimeout(resolve, 30));
  const shape = stickerShape(sticker.alpha, sticker.width, sticker.height, stickerRadius());
  if (!shape) return;
  const page = new OffscreenCanvas(doc.width, doc.height);
  const painter = page.getContext("2d");
  paintSticker(painter, shape, sticker.width, sticker.height, doc.width, doc.height);
  for (const layer of stickerLayers()) {
    painter.globalAlpha = layer.opacity;
    painter.drawImage(unpremultipliedImage(readThumbnail(layer.id, doc.width, doc.height), doc.width, doc.height), 0, 0);
  }
  painter.globalAlpha = 1;
  const image = painter.getImageData(0, 0, doc.width, doc.height);
  const pixels = premultiplied(new Uint8Array(image.data.buffer.slice(0)));
  setEditingMask(false);
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  engine._cap_create_layer(id);
  writeLayerPixels(id, pixels);
  engine._cap_mark_layer_dirty(id);
  editLayers(() => {
    doc.manifest.layers = layers().filter((layer) => !layer.sticker);
    layers().push({ ...newLayer(id, "Sticker"), sticker: true });
    doc.manifest.activeLayer = id;
  });
  if (exportIt) {
    try {
      const blob = await page.convertToBlob({ type: "image/png" });
      const where = await store.saveExport(`${safeName(doc.manifest.name)} sticker.png`, blob);
      say(`Saved to ${where}`, true);
    } catch {
      say("Couldn't export this PNG", true);
    }
  }
}

// --- Color variants, design sheets, and frames, as DesignSheet.kt -------------------------------

const COLOR_VARIANTS = [1 / 12, 1 / 6, 1 / 3, 1 / 2, 2 / 3, 5 / 6];
const FRAME_COLUMNS = 3;
const FRAME_ROWS = 2;
const FRAME_GAP_SHARE = 0.04;

// Evenly spaced frames for thumbnail sketches, as left, top, right, bottom in canvas pixels.
function thumbnailFrames(width, height, columns = FRAME_COLUMNS, rows = FRAME_ROWS) {
  const space = Math.min(width, height) * FRAME_GAP_SHARE;
  const frameWidth = (width - space * (columns + 1)) / columns;
  const frameHeight = (height - space * (rows + 1)) / rows;
  const frames = [];
  for (let row = 0; row < rows; row++) {
    for (let column = 0; column < columns; column++) {
      const left = space + column * (frameWidth + space);
      const top = space + row * (frameHeight + space);
      frames.push([left, top, left + frameWidth, top + frameHeight]);
    }
  }
  return frames;
}

// Turns the hue of RGBA pixels, keeping saturation, value, and alpha.
function shiftHue(pixels, turn) {
  const out = new Uint8ClampedArray(pixels.length);
  for (let i = 0; i < pixels.length; i += 4) {
    const red = pixels[i] / 255;
    const green = pixels[i + 1] / 255;
    const blue = pixels[i + 2] / 255;
    out[i + 3] = pixels[i + 3];
    const high = Math.max(red, green, blue);
    const low = Math.min(red, green, blue);
    const range = high - low;
    if (range === 0) {
      out[i] = pixels[i];
      out[i + 1] = pixels[i + 1];
      out[i + 2] = pixels[i + 2];
      continue;
    }
    let hue;
    if (high === red) hue = ((green - blue) / range + 6) % 6;
    else if (high === green) hue = (blue - red) / range + 2;
    else hue = (red - green) / range + 4;
    const turned = (hue + turn * 6) % 6;
    const spread = range * (1 - Math.abs(turned % 2 - 1));
    const [r, g, b] = [[range, spread, 0], [spread, range, 0], [0, range, spread], [0, spread, range], [spread, 0, range], [range, 0, spread]][Math.trunc(turned)];
    out[i] = Math.trunc((r + low) * 255 + 0.5);
    out[i + 1] = Math.trunc((g + low) * 255 + 0.5);
    out[i + 2] = Math.trunc((b + low) * 255 + 0.5);
  }
  return out;
}

function variantsPanel() {
  const body = h("div", { className: "column" }, h("div", { className: "title" }, "Color variants"));
  const width = THUMBNAIL_WIDTH / 2;
  const height = Math.max(1, Math.floor(width * doc.height / doc.width));
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_composite(width, height, pointer);
  const pixels = engine.HEAPU8.slice(pointer, pointer + size);
  engine._free(pointer);
  body.append(gap(h("div", { className: "variant-grid" }, COLOR_VARIANTS.map((turn) => {
    const tile = h("canvas", { className: "variant", width, height, ariaLabel: "Color variants" });
    tile.getContext("2d").putImageData(new ImageData(shiftHue(pixels, turn), width, height), 0, 0);
    return h("button", { className: "variant-button", onClick: () => applyColorVariant(turn) }, tile);
  }))));
  body.append(gap(h("div", { className: "small" }, "Tap one to recolor every layer. Make a variation first to keep this version.")));
  return body;
}

// Turns the hue of every layer. One undo restores them all.
function applyColorVariant(turn) {
  closePanel();
  const target = paintTarget();
  layers().forEach((layer, index) => {
    engine._cap_set_active_layer(layer.id);
    engine.HEAPF32.set([turn, 1, 0, 0, 0, 0], pointsOffset);
    engine._cap_set_adjustment(1);
    engine._cap_commit_adjustment(index > 0 ? 1 : 0);
  });
  engine._cap_set_active_layer(target);
  recordStroke();
  markUnsaved();
  queueCompose();
}

const SHEET_CELL = 1000;
const SHEET_MARGIN = 80;
const SHEET_LABEL = 90;
const SHEET_COLUMNS = 3;

// Lays out named images on a white page, ready to send to a client.
function contactSheet(title, images) {
  const columns = Math.min(SHEET_COLUMNS, Math.max(1, images.length));
  const rows = Math.ceil(images.length / columns);
  const tallest = Math.max(...images.map(([, image]) => Math.floor(SHEET_CELL * image.height / image.width)));
  const rowHeight = tallest + SHEET_LABEL;
  const page = new OffscreenCanvas(SHEET_MARGIN + columns * (SHEET_CELL + SHEET_MARGIN), SHEET_MARGIN * 2 + SHEET_LABEL + rows * (rowHeight + SHEET_MARGIN));
  const c = page.getContext("2d");
  c.fillStyle = "#FFFFFF";
  c.fillRect(0, 0, page.width, page.height);
  c.fillStyle = "#2C2C2A";
  c.font = 'bold 64px Roboto, "Segoe UI", system-ui, sans-serif';
  c.fillText(title, SHEET_MARGIN, SHEET_MARGIN + 64);
  c.font = '44px Roboto, "Segoe UI", system-ui, sans-serif';
  images.forEach(([name, image], index) => {
    const left = SHEET_MARGIN + index % columns * (SHEET_CELL + SHEET_MARGIN);
    const top = SHEET_MARGIN + SHEET_LABEL + Math.floor(index / columns) * (rowHeight + SHEET_MARGIN);
    const height = Math.floor(SHEET_CELL * image.height / image.width);
    c.drawImage(image, left, top, SHEET_CELL, height);
    c.fillText(name, left, top + height + 60);
  });
  return page;
}

async function exportSheet() {
  say("Making your design sheet");
  try {
    await save(true);
    const family = await store.variations(doc.id);
    const images = [];
    for (const art of family) {
      const url = await store.imageUrl(art.id, true);
      if (!url) continue;
      const image = await createImageBitmap(await (await fetch(url)).blob());
      URL.revokeObjectURL(url);
      images.push([art.name, image]);
    }
    const page = contactSheet(doc.manifest.name, images);
    const where = await store.saveExport(`${safeName(doc.manifest.name)} design sheet.png`, await page.convertToBlob({ type: "image/png" }));
    say(`Saved to ${where}`, true);
  } catch (error) {
    console.error(error);
    say("Couldn't export this PNG", true);
  }
}

async function exportFrames() {
  try {
    const whole = unpremultipliedImage(readFullComposite(), doc.width, doc.height);
    let where = "";
    for (const [index, frame] of thumbnailFrames(doc.width, doc.height).entries()) {
      const left = Math.trunc(frame[0]);
      const top = Math.trunc(frame[1]);
      const width = Math.trunc(frame[2]) - left;
      const height = Math.trunc(frame[3]) - top;
      const crop = new OffscreenCanvas(width, height);
      crop.getContext("2d").drawImage(whole, left, top, width, height, 0, 0, width, height);
      where = await store.saveExport(`${safeName(doc.manifest.name)} ${index + 1}.png`, await crop.convertToBlob({ type: "image/png" }));
    }
    say(`Saved to ${where}`, true);
  } catch (error) {
    console.error(error);
    say("Couldn't export this PNG", true);
  }
}

// --- Animation, as Animation.kt and MainActivity's animation bar ----------------------------------

const ONION_SHARE = 0.25;
const ANIMATION_SPEEDS = [4, 8, 12, 24];
const ANIMATION_SIDE = 1280;
const ANIMATION_SECONDS = 6;
let onionSkin = true;
let playingFrame = -1;
let playTimer = 0;
const animationBar = h("div", { className: "tool-bar animation-bar", hidden: true });
studioView.append(animationBar);

// How much of each frame shows: the current one fully, its neighbors faintly with onion skin.
function frameShares(count, current, onion) {
  return Array.from({ length: count }, (_, index) => {
    if (index === current) return 1;
    if (onion && Math.abs(index - current) === 1) return ONION_SHARE;
    return 0;
  });
}

function animationShare(layer) {
  if (!doc?.manifest.animation) return 1;
  const list = layers();
  const current = playingFrame >= 0 ? playingFrame : list.findIndex((it) => it.id === doc.manifest.activeLayer);
  return frameShares(list.length, current, onionSkin && playingFrame < 0)[list.indexOf(layer)] ?? 1;
}

function setAnimation(on) {
  stopPlaying();
  doc.manifest.animation = on;
  doc.manifest.fps ||= 8;
  markUnsaved();
  syncLayers(false);
  if (on) say("Each layer is a frame now. Add frames with +, and the frames beside this one show faintly.", true);
  refreshAnimationBar();
}

function refreshAnimationBar() {
  const on = !!doc?.manifest.animation && !interfaceHidden;
  animationBar.hidden = !on;
  if (!on) return;
  const list = layers();
  const current = playingFrame >= 0 ? playingFrame : list.findIndex((it) => it.id === doc.manifest.activeLayer);
  animationBar.replaceChildren(h("div", { className: "pill-bar scroll-bar" },
    h("button", { className: playingFrame >= 0 ? "chip-dark on" : "chip-dark", onClick: () => togglePlaying() }, playingFrame >= 0 ? "Stop" : "Play"),
    ...list.map((layer, index) => h("button", {
      className: index === current ? "chip frame-chip on" : "chip frame-chip",
      onClick: () => {
        stopPlaying();
        selectLayer(layer);
        syncLayers(false);
        refreshLayers();
        refreshAnimationBar();
      },
    }, String(index + 1))),
    h("button", { className: "chip frame-chip", onClick: () => { addLayer(); refreshAnimationBar(); } }, "+"),
    h("button", {
      className: onionSkin ? "chip-dark on" : "chip-dark",
      onClick: () => {
        onionSkin = !onionSkin;
        syncLayers(false);
        refreshAnimationBar();
      },
    }, "Onion skin"),
    h("button", {
      className: "chip-dark",
      onClick: () => {
        const at = ANIMATION_SPEEDS.indexOf(doc.manifest.fps);
        doc.manifest.fps = ANIMATION_SPEEDS[(at + 1) % ANIMATION_SPEEDS.length];
        markUnsaved();
        if (playingFrame >= 0) {
          stopPlaying();
          togglePlaying();
        }
        refreshAnimationBar();
      },
    }, `${doc.manifest.fps} fps`),
    h("button", { className: "chip-dark", onClick: () => exportAnimation() }, "Export")));
}

function togglePlaying() {
  if (playingFrame >= 0) {
    stopPlaying();
    syncLayers(false);
    refreshAnimationBar();
    return;
  }
  if (layers().length < 2) {
    say("Add a second frame to make an animation");
    return;
  }
  playingFrame = Math.max(0, layers().findIndex((it) => it.id === doc.manifest.activeLayer));
  playTimer = setInterval(() => {
    playingFrame = (playingFrame + 1) % Math.max(1, layers().length);
    syncLayers(false);
  }, 1000 / doc.manifest.fps);
  refreshAnimationBar();
}

function stopPlaying() {
  playingFrame = -1;
  clearInterval(playTimer);
}

// Renders each frame alone, then plays them a few times in a video.
async function exportAnimation() {
  stopPlaying();
  const list = layers();
  if (list.length < 2) {
    say("Add a second frame to make an animation");
    return;
  }
  const scale = Math.min(1, ANIMATION_SIDE / Math.max(doc.width, doc.height));
  const width = Math.floor(doc.width * scale) & ~1;
  const height = Math.floor(doc.height * scale) & ~1;
  const fps = doc.manifest.fps || 8;
  say("Exporting animation");
  const frames = [];
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  for (let frame = 0; frame < list.length; frame++) {
    const shares = frameShares(list.length, frame, false);
    list.forEach((layer, index) => applyLayerProperties(layer, shares[index]));
    engine._cap_read_composite(width, height, pointer);
    frames.push(unpremultipliedImage(engine.HEAPU8.slice(pointer, pointer + size), width, height));
  }
  engine._free(pointer);
  syncLayers(false);
  const loops = Math.max(1, Math.ceil(ANIMATION_SECONDS * fps / frames.length));
  const timeline = Array.from({ length: frames.length * loops }, (_, i) => i % frames.length);
  try {
    const video = await encodeVideo(frames, width, height, timeline, fps);
    const where = await store.saveExport(`${safeName(doc.manifest.name)} animation.${video.extension}`, video.blob);
    say(`Saved to ${where}`, true);
  } catch (error) {
    console.error(error);
    say("Couldn't export this animation", true);
  }
}

// --- Videos, as TimelapseEncoder.kt --------------------------------------------------------------

const TIMELAPSE_RATE = 15;
const HOLD_LAST_FRAMES = 45;
const OUTRO_DELAY_FRAMES = 5;
const OUTRO_POP_FRAMES = 8;
const OUTRO_HEIGHT = 0.11;
const OUTRO_MARGIN = 0.035;

// Picks evenly spread frames for a video of seconds, or keeps them all, then holds the last one.
function timelapseTimeline(count, seconds) {
  if (!count) return [];
  const wanted = seconds ? Math.min(seconds * TIMELAPSE_RATE, count) : count;
  const picked = Array.from({ length: wanted }, (_, i) => (wanted === 1 ? count - 1 : Math.floor(i * (count - 1) / (wanted - 1))));
  return [...picked, ...Array(HOLD_LAST_FRAMES).fill(count - 1)];
}

// Plays frames in the order of the timeline into a video. Browsers record in real time, so a
// video takes as long to export as it runs.
async function encodeVideo(frames, width, height, timeline, frameRate, outro = null) {
  const surface = h("canvas", { width, height });
  const c = surface.getContext("2d");
  const types = ["video/mp4;codecs=avc1", "video/mp4", "video/webm;codecs=vp9", "video/webm"];
  const type = types.find((it) => window.MediaRecorder?.isTypeSupported(it));
  if (!type) throw new Error("This browser can't record video");
  const stream = surface.captureStream(0);
  const track = stream.getVideoTracks()[0];
  const recorder = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 3_000_000 });
  const chunks = [];
  recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
  const stopped = new Promise((resolve) => { recorder.onstop = resolve; });
  recorder.start();
  const step = 1000 / frameRate;
  const start = performance.now();
  for (const [position, index] of timeline.entries()) {
    c.fillStyle = "#FFFFFF";
    c.fillRect(0, 0, width, height);
    c.drawImage(frames[index], 0, 0, width, height);
    if (outro && position >= outro.start) outro.draw(c, width, height, position - outro.start);
    track.requestFrame();
    const wait = start + (position + 1) * step - performance.now();
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  }
  recorder.stop();
  await stopped;
  return { blob: new Blob(chunks, { type: type.split(";")[0] }), extension: type.startsWith("video/mp4") ? "mp4" : "webm" };
}

// A small dark note in the corner, like the app's own, that grows in with a little bounce.
function timelapseOutro(start) {
  const face = new OffscreenCanvas(128, 128);
  const signature = "Made with Cappuccino";
  return {
    start,
    draw(c, width, height, frame) {
      const t = (frame + 1) / OUTRO_POP_FRAMES;
      const pop = t >= 1 ? 1 : 1 + 2.7 * (t - 1) ** 3 + 1.7 * (t - 1) ** 2;
      const tall = Math.min(width, height) * OUTRO_HEIGHT;
      c.save();
      c.font = `${tall * 0.32}px Roboto, "Segoe UI", system-ui, sans-serif`;
      const wide = tall * 1.05 + c.measureText(signature).width + tall * 0.4;
      const margin = Math.min(width, height) * OUTRO_MARGIN;
      const right = width - margin;
      const bottom = height - margin;
      const left = right - wide;
      const top = bottom - tall;
      c.translate(right, bottom);
      c.scale(pop, pop);
      c.translate(-right, -bottom);
      c.fillStyle = "#3A291F";
      c.beginPath();
      c.roundRect(left, top, wide, tall, tall / 2);
      c.fill();
      const size = Math.floor(tall * 0.9);
      const pal = new Companion(face, { look: companionLook, blink: false, still: true });
      pal.motionTime = frame / TIMELAPSE_RATE;
      pal.setColor(color);
      c.drawImage(face, left + tall * 0.12, top + (tall - size) / 2, size, size);
      c.fillStyle = TEXT_COLOR;
      c.textBaseline = "middle";
      c.fillText(signature, left + tall * 1.05, top + tall / 2);
      c.restore();
    },
  };
}

function askTimelapseLength() {
  choiceSheet("Time-lapse length", [
    ...[15, 30, 60].map((seconds) => ({ label: `${seconds} seconds`, run: () => exportTimelapse(seconds) })),
    { label: "Every saved moment", run: () => exportTimelapse(null) },
  ]);
}

async function exportTimelapse(seconds) {
  await save(false);
  const files = await store.timelapseFrames(doc).catch(() => []);
  if (files.length < 2) {
    say("Draw a little more to build a time-lapse");
    return;
  }
  say("Exporting time-lapse");
  try {
    const frames = [];
    for (const file of files) frames.push(await createImageBitmap(file));
    const width = THUMBNAIL_WIDTH * 2;
    const height = thumbnailHeight(doc.width, doc.height) * 2 & ~1;
    const timeline = timelapseTimeline(frames.length, seconds);
    const outro = timelapseOutro(timeline.length - HOLD_LAST_FRAMES + OUTRO_DELAY_FRAMES);
    const video = await encodeVideo(frames, width, height, timeline, TIMELAPSE_RATE, outro);
    const where = await store.saveExport(`${safeName(doc.manifest.name)} time-lapse.${video.extension}`, video.blob);
    say(`Saved to ${where}`, true);
  } catch (error) {
    console.error(error);
    say("Couldn't export this time-lapse", true);
  }
}

// --- Layers, as MainActivity's layer panel and EditHistory.kt ------------------------------------

const MAX_EDITS = 300;
const RETIRED_LAYERS = 6;
const LAYER_ROW = 56;
const LAYER_GAP = 4;
const THUMBNAIL_WIDTH_DP = 72;
const THUMBNAIL_HEIGHT_DP = 48;
const BLEND_MODES = ["Normal", "Multiply", "Screen", "Additive", "Darken", "Lighten", "Soft light", "Subtract", "Exclusion"];

// One timeline for strokes and layer changes, so undo always steps back in drawing order.
class EditHistory {
  constructor() {
    this.undoStack = [];
    this.redoStack = [];
  }

  record(edit) {
    this.redoStack.length = 0;
    const last = this.undoStack[this.undoStack.length - 1];
    if (edit.kind === "layers" && edit.mergeKey && last?.kind === "layers" && last.mergeKey === edit.mergeKey) {
      this.undoStack[this.undoStack.length - 1] = { kind: "layers", before: last.before, after: edit.after, pixels: false, mergeKey: edit.mergeKey };
      return;
    }
    this.undoStack.push(edit);
    if (this.undoStack.length > MAX_EDITS) this.undoStack.shift();
  }

  undo() {
    const edit = this.undoStack.pop();
    if (edit) this.redoStack.push(edit);
    return edit;
  }

  redo() {
    const edit = this.redoStack.pop();
    if (edit) this.undoStack.push(edit);
    return edit;
  }

  clear() {
    this.undoStack.length = 0;
    this.redoStack.length = 0;
  }
}

const history = new EditHistory();
const engineLayers = new Set();
let editingMask = false;
let maxLayers = 12;
let pickingLayers = false;
const pickedLayers = new Set();
const layersButton = document.querySelector('[data-action="layers"]');

const layers = () => doc.manifest.layers;
const layerById = (id) => layers().find((layer) => layer.id === id);
const activeLayer = () => layerById(doc.manifest.activeLayer);
const groupsOf = () => (doc.manifest.groups ||= []);
const layerSlots = () => layers().length + layers().filter((layer) => layer.mask).length;

function newLayer(id, name) {
  return {
    id, name, opacity: 1, visible: true, blend: 0, alphaLock: false, clip: false, group: 0,
    file: "", tiles: null, mask: 0, maskEnabled: true, maskFile: "", text: "", textSize: 0, textColor: 0,
    sticker: false, maskTiles: null,
  };
}

function captureLayers() {
  return {
    layers: structuredClone(layers()),
    activeLayer: doc.manifest.activeLayer,
    groups: structuredClone(groupsOf()),
  };
}

// The engine layer that strokes land on: the layer itself, or its mask.
function paintTarget() {
  const layer = activeLayer();
  return layer?.mask && editingMask ? layer.mask : doc.manifest.activeLayer;
}

function alphaLocked() {
  return activeLayer()?.alphaLock === true && !editingMask;
}

function markMaskEditing() {
  layersButton.classList.toggle("mask-on", editingMask);
}

function setEditingMask(editing) {
  if (editingMask === editing) return;
  editingMask = editing;
  engine._cap_set_active_layer(paintTarget());
  markMaskEditing();
}

function applyLayerProperties(layer, share = animationShare(layer)) {
  const group = groupsOf().find((it) => it.id === layer.group);
  engine._cap_set_layer_properties(layer.id, layer.opacity * (group ? group.opacity : 1) * share,
    layer.visible && (!group || group.visible) && share > 0 ? 1 : 0, layer.blend || 0, layer.clip ? 1 : 0);
  engine._cap_set_layer_mask(layer.id, layer.mask || 0, layer.maskEnabled === false ? 0 : 1);
}

function applyLayerOrder() {
  const ids = layers().map((layer) => layer.id);
  const pointer = engine._malloc(ids.length * 4);
  engine.HEAP32.set(ids, pointer >> 2);
  engine._cap_set_layer_order(pointer, ids.length);
  engine._free(pointer);
}

// Sends the document's layers to the engine. Light edits skip the active layer copy.
function syncLayers(structural) {
  if (doc?.manifest.animation && typeof refreshAnimationBar === "function") queueMicrotask(refreshAnimationBar);
  if (structural) applyLayerOrder();
  layers().forEach(applyLayerProperties);
  if (structural) engine._cap_set_active_layer(paintTarget());
  queueCompose();
}

function editLayers(change, { mergeKey = null, structural = true, pixels = false } = {}) {
  const before = captureLayers();
  change();
  history.record({ kind: "layers", before, after: captureLayers(), pixels, mergeKey });
  syncLayers(structural);
  markUnsaved();
  releaseRetiredLayers();
}

// Layers removed from the document stay in the engine for undo, within a memory limit.
function releaseRetiredLayers() {
  if (engineLayers.size <= maxLayers + RETIRED_LAYERS) return;
  const current = new Set(layers().flatMap((layer) => [layer.id, layer.mask]));
  history.clear();
  for (const id of [...engineLayers]) {
    if (current.has(id)) continue;
    engineLayers.delete(id);
    engine._cap_delete_layer(id);
  }
  say("Older undo steps were cleared to free memory", true);
}

function restoreLayers(state) {
  const previous = new Set(layers().flatMap((layer) => [layer.id, layer.mask]));
  doc.manifest.layers = structuredClone(state.layers);
  doc.manifest.activeLayer = state.activeLayer;
  doc.manifest.groups = structuredClone(state.groups);
  // Only layers coming back need saving again. Their old tiles may already be gone.
  for (const layer of layers()) {
    for (const id of [layer.id, layer.mask]) if (id && !previous.has(id)) engine._cap_mark_layer_dirty(id);
  }
  if (!activeLayer()?.mask) setEditingMask(false);
  syncLayers(true);
  refreshLayers();
}

function recordStroke() {
  history.record({ kind: "stroke" });
}

function refreshLayers() {
  if (panelKey === "layers") rerender();
}

function selectLayer(layer) {
  editingMask = false;
  markMaskEditing();
  doc.manifest.activeLayer = layer.id;
  engine._cap_set_active_layer(layer.id);
  if (doc.manifest.animation) {
    syncLayers(false);
    refreshAnimationBar();
  }
  markUnsaved();
}

function sayFull() {
  say(`This canvas can hold ${maxLayers} layers`, true);
}

// Adds a layer above the active one, as MainActivity.addLayer() does.
function addLayer({ blend = 0 } = {}) {
  if (!doc) return;
  if (layerSlots() >= maxLayers) return sayFull();
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  engine._cap_create_layer(id);
  editLayers(() => {
    const index = layers().findIndex((layer) => layer.id === doc.manifest.activeLayer) + 1;
    layers().splice(index, 0, { ...newLayer(id, `Layer ${id}`), blend });
    doc.manifest.activeLayer = id;
  });
  refreshLayers();
}

function duplicateLayer(layer) {
  if (layerSlots() >= maxLayers) return sayFull();
  setEditingMask(false);
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  const mask = layer.mask ? doc.manifest.nextLayerId++ : 0;
  if (mask) engineLayers.add(mask);
  engine._cap_duplicate_layer(layer.id, id);
  if (mask) engine._cap_duplicate_layer(layer.mask, mask);
  editLayers(() => {
    const copy = { ...structuredClone(layer), id, name: `${layer.name} copy`, file: "", tiles: null, mask, maskFile: "", maskTiles: null };
    layers().splice(layers().indexOf(layerById(layer.id)) + 1, 0, copy);
    doc.manifest.activeLayer = id;
  });
  refreshLayers();
}

function mergeDown(layer) {
  const index = layers().findIndex((it) => it.id === layer.id);
  if (index === 0) {
    say("There isn't a layer below this one");
    return;
  }
  const below = layers()[index - 1];
  setEditingMask(false);
  engine._cap_merge_layer_down(layer.id, below.id);
  editLayers(() => {
    layers().splice(index, 1);
    if (below.maskEnabled) below.mask = 0;
    doc.manifest.activeLayer = below.id;
  }, { pixels: true });
  refreshLayers();
}

function moveLayerTo(id, target) {
  const layer = layerById(id);
  if (!layer) return;
  const to = clampIndex(target, layers().length);
  if (to === layers().indexOf(layer)) return;
  editLayers(() => {
    layers().splice(layers().indexOf(layer), 1);
    layers().splice(to, 0, layer);
  });
  refreshLayers();
}

const clampIndex = (value, size) => Math.min(size - 1, Math.max(0, value));

function deleteLayer(layer) {
  if (layers().length === 1) {
    say("An artwork needs at least one layer");
    return;
  }
  editingMask = false;
  markMaskEditing();
  editLayers(() => {
    const index = layers().findIndex((it) => it.id === layer.id);
    layers().splice(index, 1);
    doc.manifest.activeLayer = layers()[Math.max(0, index - 1)].id;
  });
  refreshLayers();
}

function renameLayer(layer) {
  askSheet("Rename layer", layer.name, (name) => {
    editLayers(() => { layerById(layer.id).name = name; }, { structural: false });
    refreshLayers();
  });
}

function setClip(layer, clip) {
  editLayers(() => { layerById(layer.id).clip = clip; }, { structural: false });
}

// Adds a mask to the active layer. Any selection stays visible and the rest is hidden.
function addMask() {
  const layer = activeLayer();
  if (!layer) return;
  if (layerSlots() >= maxLayers) return sayFull();
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  engine._cap_create_mask(layer.id, id);
  engine._cap_clear_selection();
  markSelection(false);
  editLayers(() => {
    layer.mask = id;
    layer.maskEnabled = true;
  }, { structural: false });
  setEditingMask(true);
  say("Paint to hide. Erase to bring it back.");
}

function applyMask(layer) {
  setEditingMask(false);
  engine._cap_apply_mask(layer.id);
  editLayers(() => { layerById(layer.id).mask = 0; }, { structural: false, pixels: true });
}

function deleteMask(layer) {
  setEditingMask(false);
  editLayers(() => { layerById(layer.id).mask = 0; }, { structural: false });
}

function setMaskEnabled(layer, enabled) {
  if (!enabled) setEditingMask(false);
  editLayers(() => { layerById(layer.id).maskEnabled = enabled; }, { structural: false });
}

function toggleGroup(layer) {
  if (layer.group) {
    const id = layer.group;
    editLayers(() => {
      layers().filter((it) => it.group === id).forEach((it) => { it.group = 0; });
      doc.manifest.groups = groupsOf().filter((group) => group.id !== id);
    });
  } else {
    const index = layers().findIndex((it) => it.id === layer.id);
    const below = layers()[index - 1];
    const id = below?.group || doc.manifest.nextGroupId++;
    editLayers(() => {
      if (!groupsOf().some((group) => group.id === id)) groupsOf().push({ id, name: `Group ${id}`, visible: true, opacity: 1, collapsed: false });
      layerById(layer.id).group = id;
      if (below) below.group = id;
    });
  }
  refreshLayers();
}

// Places an image in the middle of a new layer, as MainActivity.addImage() does.
function importLayerImage() {
  const input = h("input", { type: "file", accept: "image/*" });
  input.addEventListener("change", async () => {
    const file = input.files[0];
    if (!file) return;
    try {
      const image = await createImageBitmap(file);
      const shrink = Math.min(1, doc.width / image.width, doc.height / image.height);
      const width = Math.max(1, Math.floor(image.width * shrink));
      const height = Math.max(1, Math.floor(image.height * shrink));
      addImage(image, width, height, file.name.replace(/\.[^.]*$/, "").slice(0, 24) || "Image");
    } catch {
      say("Couldn't read this image");
    }
  });
  input.click();
}

function addImage(image, width, height, name, transform = true) {
  if (layerSlots() >= maxLayers) return sayFull();
  setEditingMask(false);
  const surface = new OffscreenCanvas(doc.width, doc.height);
  const c = surface.getContext("2d");
  c.drawImage(image, (doc.width - width) / 2, (doc.height - height) / 2, width, height);
  const pixels = premultiplied(c.getImageData(0, 0, doc.width, doc.height).data);
  const id = doc.manifest.nextLayerId++;
  engineLayers.add(id);
  engine._cap_create_layer(id);
  writeLayerPixels(id, pixels);
  engine._cap_mark_layer_dirty(id);
  editLayers(() => {
    const index = layers().findIndex((layer) => layer.id === doc.manifest.activeLayer) + 1;
    layers().splice(index, 0, newLayer(id, name));
    doc.manifest.activeLayer = id;
  });
  if (transform) startTransform();
}

// The engine keeps layers with premultiplied alpha, as Android's bitmaps do.
function premultiplied(pixels) {
  for (let i = 0; i < pixels.length; i += 4) {
    const alpha = pixels[i + 3] / 255;
    pixels[i] = Math.round(pixels[i] * alpha);
    pixels[i + 1] = Math.round(pixels[i + 1] * alpha);
    pixels[i + 2] = Math.round(pixels[i + 2] * alpha);
  }
  return pixels;
}

function writeLayerPixels(id, pixels) {
  const pointer = engine._malloc(pixels.length);
  engine.HEAPU8.set(pixels, pointer);
  engine._cap_write_layer(id, pointer);
  engine._free(pointer);
}

// A layer's preview on white, read straight from the engine.
function layerThumbnail(id) {
  const width = THUMBNAIL_WIDTH_DP * 2;
  const height = THUMBNAIL_HEIGHT_DP * 2;
  const element = h("canvas", { className: "layer-thumb", width, height });
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_layer_thumbnail(id, width, height, pointer);
  const pixels = new Uint8ClampedArray(engine.HEAPU8.buffer, pointer, size).slice();
  engine._free(pointer);
  for (let i = 0; i < pixels.length; i += 4) {
    const clear = 255 - pixels[i + 3];
    pixels[i] += clear;
    pixels[i + 1] += clear;
    pixels[i + 2] += clear;
    pixels[i + 3] = 255;
  }
  element.getContext("2d").putImageData(new ImageData(pixels, width, height), 0, 0);
  return element;
}

function layersPanel() {
  const active = activeLayer();
  const body = h("div", { className: "column" },
    h("div", { className: "row header" },
      h("div", { className: "title grow" }, "Layers"),
      h("button", {
        className: pickingLayers ? "icon on" : "icon", ariaLabel: "Select layers", innerHTML: ICONS.group,
        onClick: () => {
          pickingLayers = !pickingLayers;
          pickedLayers.clear();
          refreshLayers();
        },
      }),
      h("button", { className: "icon", ariaLabel: "Add layer", innerHTML: ICONS.add, onClick: () => addLayer() })));
  const list = h("div", { className: "column gap layer-list" });
  let shownGroup = 0;
  for (const layer of [...layers()].reverse()) {
    const group = groupsOf().find((it) => it.id === layer.group);
    if (group && group.id !== shownGroup) list.append(groupRow(group));
    shownGroup = group?.id || 0;
    if (group?.collapsed) continue;
    const row = layerRow(layer);
    row.style.marginLeft = `${(group ? 12 : 0) + (layer.clip ? 20 : 0)}px`;
    list.append(row);
  }
  body.append(list);
  if (pickingLayers) {
    body.append(gap(h("div", { className: "chips wrap" }, layerPickActions())));
    return body;
  }
  if (!active) return body;
  body.append(gap(h("div", { className: "small" }, "Opacity")),
    h("input", {
      className: "range", type: "range", min: "0", max: "100", value: String(Math.round(active.opacity * 100)), ariaLabel: "Layer opacity",
      onInput: (event) => {
        const value = Number(event.target.value) / 100;
        editLayers(() => { layerById(active.id).opacity = value; }, { mergeKey: `opacity${active.id}`, structural: false });
      },
    }));
  const blendChip = smallChip(`${BLEND_MODES[active.blend || 0]} ▾`, false, () => chooseBlend(blendChip, active));
  body.append(gap(h("div", { className: "row" }, h("div", { className: "small grow" }, "Blend"), blendChip)));
  const tile = (icon, title, click, selected = false) => h("button", { className: selected ? "icon-tile on" : "icon-tile", ariaLabel: title, onClick: click },
    svg(icon), h("span", {}, title));
  body.append(gap(h("div", { className: "tile-grid" },
    tile("transform", "Transform", () => startTransform()),
    tile("liquify", "Liquify", () => startLiquify()),
    tile("filter", "Filters", () => openFilters()),
    tile("mask", "Mask", () => {
      if (!active.mask) addMask(); else setEditingMask(!editingMask);
      refreshLayers();
    }, editingMask),
    tile("adjust", "Adjust", () => {
      setEditingMask(false);
      closePanel();
      showPanel("adjust", adjustPanel);
    }),
    tile("clip", "Clip", () => {
      setClip(active, !active.clip);
      refreshLayers();
    }, active.clip),
    tile("references", "Import", () => importLayerImage()),
    tile("group", active.group ? "Ungroup" : "Group", () => toggleGroup(active)),
    tile("duplicate", "Duplicate", () => duplicateLayer(active)),
    tile("merge", "Merge", () => mergeDown(active)),
    tile("trash", "Delete", () => deleteLayer(active)))));
  if (active.mask) {
    body.append(gap(h("div", { className: "chips wrap" },
      smallChip(active.maskEnabled ? "Turn mask off" : "Turn mask on", false, () => {
        setMaskEnabled(active, !active.maskEnabled);
        refreshLayers();
      }),
      smallChip("Apply mask", false, () => {
        applyMask(active);
        refreshLayers();
      }),
      smallChip("Delete mask", false, () => {
        deleteMask(active);
        refreshLayers();
      }))));
  }
  if (active.text) body.append(gap(h("div", { className: "chips" }, smallChip("Edit text", false, () => askForText(active)))));
  if (editingMask) body.append(gap(h("div", { className: "small accent" }, "Painting on the mask. Tap Mask to return to the layer.")));
  return body;
}

function layerPickActions() {
  const picked = layers().filter((layer) => pickedLayers.has(layer.id));
  const finish = () => {
    pickingLayers = false;
    pickedLayers.clear();
    refreshLayers();
  };
  const actions = [smallChip("Done", false, finish)];
  if (!picked.length) return actions;
  actions.push(smallChip("Group", false, () => {
    const id = doc.manifest.nextGroupId++;
    editLayers(() => {
      groupsOf().push({ id, name: `Group ${id}`, visible: true, opacity: 1, collapsed: false });
      picked.forEach((layer) => { layerById(layer.id).group = id; });
    });
    finish();
  }));
  actions.push(smallChip("Show or hide", false, () => {
    const visible = !picked.some((layer) => layer.visible);
    editLayers(() => picked.forEach((layer) => { layerById(layer.id).visible = visible; }), { structural: false });
    refreshLayers();
  }));
  actions.push(smallChip("Merge", false, () => {
    const places = picked.map((layer) => layers().indexOf(layer)).sort((a, b) => a - b);
    if (places[places.length - 1] - places[0] !== places.length - 1) {
      say("Pick layers next to each other");
    } else {
      picked.slice(1).reverse().forEach((layer) => mergeDown(layerById(layer.id)));
      finish();
    }
  }));
  actions.push(smallChip("Delete", false, () => {
    if (picked.length >= layers().length) {
      say("An artwork needs at least one layer");
      return;
    }
    setEditingMask(false);
    editLayers(() => {
      const first = layers().indexOf(picked[0]);
      doc.manifest.layers = layers().filter((layer) => !pickedLayers.has(layer.id));
      doc.manifest.activeLayer = layers()[Math.max(0, first - 1)].id;
    });
    finish();
  }));
  return actions;
}

function groupRow(group) {
  const row = h("div", {
    className: "group-row", role: "button", tabIndex: 0,
    onClick: () => {
      group.collapsed = !group.collapsed;
      markUnsaved();
      refreshLayers();
    },
    onContextmenu: (event) => {
      event.preventDefault();
      askSheet("Rename group", group.name, (name) => {
        editLayers(() => { groupsOf().find((it) => it.id === group.id).name = name; }, { structural: false });
        refreshLayers();
      });
    },
  }, h("span", { className: "grow" }, `${group.collapsed ? "▸" : "▾"} ${group.name}`),
  h("button", {
    className: "icon", ariaLabel: `Show or hide ${group.name}`, innerHTML: ICONS[group.visible ? "visible" : "hidden"],
    onClick: (event) => {
      event.stopPropagation();
      editLayers(() => {
        const it = groupsOf().find((g) => g.id === group.id);
        it.visible = !it.visible;
      }, { structural: false });
      refreshLayers();
    },
  }));
  return row;
}

function layerRow(layer) {
  const selected = layer.id === doc.manifest.activeLayer;
  const highlighted = pickingLayers ? pickedLayers.has(layer.id) : selected;
  const handle = h("span", { className: "layer-handle", ariaLabel: `Reorder ${layer.name}`, innerHTML: ICONS.handle });
  const row = h("div", {
    className: highlighted ? "layer-row on" : "layer-row", role: "button", tabIndex: 0,
    onClick: () => {
      if (pickingLayers) {
        if (!pickedLayers.delete(layer.id)) pickedLayers.add(layer.id);
      } else {
        selectLayer(layer);
      }
      refreshLayers();
    },
    onContextmenu: (event) => {
      event.preventDefault();
      renameLayer(layer);
    },
  }, handle, layerThumbnail(layer.id),
  h("span", { className: "layer-name", translate: false }, layer.mask ? `◐ ${layer.name}` : layer.name),
  h("button", {
    className: "icon", ariaLabel: "Lock transparency", innerHTML: ICONS[layer.alphaLock ? "lock" : "unlock"],
    onClick: (event) => {
      event.stopPropagation();
      editLayers(() => { layerById(layer.id).alphaLock = !layer.alphaLock; }, { structural: false });
      refreshLayers();
    },
  }),
  h("button", {
    className: "icon", ariaLabel: `Show or hide ${layer.name}`, innerHTML: ICONS[layer.visible ? "visible" : "hidden"],
    onClick: (event) => {
      event.stopPropagation();
      editLayers(() => { layerById(layer.id).visible = !layer.visible; }, { structural: false });
      refreshLayers();
    },
  }));
  // Drag the handle up or down to move the layer, a row at a time.
  let startY = 0;
  handle.addEventListener("pointerdown", (event) => {
    event.stopPropagation();
    handle.setPointerCapture(event.pointerId);
    startY = event.clientY;
    row.classList.add("dragging");
  });
  handle.addEventListener("pointermove", (event) => {
    if (handle.hasPointerCapture(event.pointerId)) row.style.transform = `translateY(${toUi(event.clientY - startY)}px)`;
  });
  const drop = (event, keep) => {
    if (!handle.hasPointerCapture(event.pointerId)) return;
    const moved = Math.round(toUi(event.clientY - startY) / (LAYER_ROW + LAYER_GAP));
    row.style.transform = "";
    row.classList.remove("dragging");
    if (keep && moved !== 0) moveLayerTo(layer.id, layers().findIndex((it) => it.id === layer.id) - moved);
  };
  handle.addEventListener("pointerup", (event) => drop(event, true));
  handle.addEventListener("pointercancel", (event) => drop(event, false));
  handle.addEventListener("click", (event) => event.stopPropagation());
  return row;
}

// The blend modes in a small list under their chip.
function chooseBlend(anchor, layer) {
  document.querySelector(".blend-list")?.remove();
  const box = anchor.getBoundingClientRect();
  const list = h("div", { className: "blend-list" }, BLEND_MODES.map((name, index) => h("button", {
    className: index === (layer.blend || 0) ? "on" : "",
    onClick: () => {
      list.remove();
      editLayers(() => { layerById(layer.id).blend = index; }, { structural: false });
      refreshLayers();
    },
  }, name)));
  list.style.top = `${toUi(box.bottom) + 4}px`;
  list.style.right = `${toUi(window.innerWidth - box.right)}px`;
  studioView.append(list);
  const away = (event) => {
    if (!list.contains(event.target)) {
      list.remove();
      document.removeEventListener("pointerdown", away, true);
    }
  };
  document.addEventListener("pointerdown", away, true);
}

// --- Companion, as MainActivity's companion, peek, and notes -------------------------------------

const PEEK_SIZE = 60;
const PEEK_OVERLAP = 24;
const PEEK_TILT = 10;
const HANDS_OVERLAP = 19;
const HANDS_OVERHANG = 1;
const HIDE_MS = 480;
const HIDE_HOP = 26;
const NAP_DELAY_MS = 90_000;
const NEW_SITTING_MS = 6 * 60 * 60 * 1000;
const BUBBLE_TAIL = 10;

const companionCanvas = document.getElementById("companion");
const peekCanvas = document.getElementById("peek");
const handsCanvas = document.getElementById("hands");
let companionLook = loadPreference("mascot", "PAINT_DROP");
const companion = new Companion(companionCanvas, { look: companionLook });
const peek = new Companion(peekCanvas, { look: companionLook });
let companionX = loadPreference("companionX", null);
let companionY = loadPreference("companionY", null);
let companionVisible = loadPreference("companion", true);
let unreadTips = false;
let report = null;  // The latest analysis of the open artwork.
let progress = null;  // A note on how far the artwork came since the last sitting.
let referenceMatch = null;  // How close the artwork is to its references, from match.js.
let matchedFiles = [];  // The references it was compared with, as {name, file, time}.
let matchedScope = "NEWEST";
let matchedBoards = {};
const referenceProfiles = new Map();  // Reference file name and time -> its profile.
let tipIndex = 0;
let ideaIndex = 0;
let companionSection = 0;  // 1 why, 2 companion settings, 5 idea, 6 comparison.
let comparison = null;
let earlier = null;
let earlierLoaded = false;
let latestSnapshot = null;
let napTimer = 0;

function restingMood() {
  return unreadTips ? "tip" : "idle";
}

function setCompanionMood(mood) {
  companion.setMood(mood);
  peek.setMood(mood === "thinking" ? restingMood() : mood);
}

function scheduleNap() {
  clearTimeout(napTimer);
  napTimer = setTimeout(() => {
    if (!unreadTips) setCompanionMood("nap");
  }, NAP_DELAY_MS);
}

function placeCompanion() {
  const size = 56;
  const x = companionX === null ? 12 : Math.min(Math.max(0, companionX), viewWidth() - size);
  const y = companionY === null ? viewHeight() - size - 12 : Math.min(Math.max(0, companionY), viewHeight() - size);
  companionCanvas.style.left = `${x}px`;
  companionCanvas.style.top = `${y}px`;
  companionCanvas.style.bottom = "auto";
  return [x, y];
}

// Where the resting companion peeks from behind the toolbar's right end, and where its hands hold on.
function peekSpot() {
  const left = document.getElementById("left");
  const x = 12 + left.offsetWidth - PEEK_OVERLAP;
  const y = 12 + left.offsetHeight / 2 - PEEK_SIZE / 2;
  handsCanvas.style.left = `${12 + left.offsetWidth - HANDS_OVERLAP}px`;
  handsCanvas.style.top = `${12 - HANDS_OVERHANG}px`;
  handsCanvas.style.height = `${left.offsetHeight + HANDS_OVERHANG * 2}px`;
  return [x, y];
}

function placePeek(x, y, share) {
  peekCanvas.style.left = `${x}px`;
  peekCanvas.style.top = `${y}px`;
  peekCanvas.style.transform = `rotate(${PEEK_TILT * share}deg)`;
  peek.setPeek(share);
}

function showCompanionState() {
  const [x, y] = peekSpot();
  companionCanvas.hidden = !companionVisible || interfaceHidden;
  peekCanvas.hidden = companionVisible || interfaceHidden;
  handsCanvas.hidden = companionVisible || interfaceHidden;
  if (!companionVisible) {
    placePeek(x, y, 1);
    drawHands(handsCanvas, companionLook, color);
  }
}

// Hops between the corner and the toolbar, as moveCompanion() does on Android.
function setCompanionVisible(visible) {
  companionVisible = visible;
  savePreference("companion", visible);
  closePanel();
  const [restX, restY] = placeCompanion().map((v) => v - (PEEK_SIZE - 56) / 2);
  const [peekX, peekY] = peekSpot();
  companionCanvas.hidden = true;
  handsCanvas.hidden = true;
  peekCanvas.hidden = false;
  peek.setColor(color);
  peek.setMood(restingMood());
  const start = performance.now();
  const step = (now) => {
    const t = Math.min(1, (now - start) / HIDE_MS);
    const eased = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    const share = visible ? 1 - eased : eased;
    const hop = Math.sin(t * Math.PI) * HIDE_HOP;
    placePeek(restX + (peekX - restX) * share, restY + (peekY - restY) * share - hop, share);
    if (t < 1) {
      requestAnimationFrame(step);
    } else {
      showCompanionState();
    }
  };
  requestAnimationFrame(step);
}

peekCanvas.addEventListener("click", () => setCompanionVisible(true));

{
  let startX = 0, startY = 0, originX = 0, originY = 0, dragged = false;
  companionCanvas.addEventListener("pointerdown", (event) => {
    try {
      companionCanvas.setPointerCapture(event.pointerId);
    } catch {
      // Synthetic events in tests.
    }
    [originX, originY] = placeCompanion();
    startX = event.clientX;
    startY = event.clientY;
    dragged = false;
  });
  companionCanvas.addEventListener("pointermove", (event) => {
    if (!companionCanvas.hasPointerCapture(event.pointerId)) return;
    const dx = toUi(event.clientX - startX);
    const dy = toUi(event.clientY - startY);
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
      unreadTips = false;
      setCompanionMood("idle");
      togglePanel("companion", companionPanel);
    }
  });
}

// --- What the companion notices ---------------------------------------------------------------

// Runs after each save on the new thumbnail, as MainActivity.analyze() does.
async function analyze(target, pixels, width, height) {
  const lightDegrees = { LEFT: 180, TOP: 270, RIGHT: 0 }[target.manifest.light] ?? null;
  const result = analyzeArtwork(pixels, width, height, lightDegrees);
  const now = { time: Date.now(), contrast: result.contrast, coverage: result.coverage, colored: result.colored };
  latestSnapshot = now;
  if (!earlierLoaded) {
    earlierLoaded = true;
    await store.saveFirstSnapshot(target, now).catch(() => {});
    const saved = await store.snapshot(target).catch(() => null);
    if (!saved || now.time - saved.time >= NEW_SITTING_MS) {
      await store.saveSnapshot(target, now).catch(() => {});
      earlier = saved;
    }
  }
  const matched = await matchReferences(target, pixels, width, height).catch((error) => {
    console.error(error);
    return null;
  });
  if (doc !== target) return;
  const note = earlier ? progressNote(earlier, now) : null;
  const changed = JSON.stringify(result.tips.map((tip) => tip.text)) !== JSON.stringify((report?.tips || []).map((tip) => tip.text));
  const matchChanged = matched?.match?.summary !== referenceMatch?.summary;
  report = result;
  progress = note;
  referenceMatch = matched?.match || null;
  matchedFiles = matched?.files || [];
  matchedScope = matched?.scope || "NEWEST";
  matchedBoards = matched?.boards || {};
  if ((changed || matchChanged) && result.tips.length) unreadTips = true;
  const topic = result.tips.find((tip) => WARMUP_TOPICS.includes(tip.topic));
  if (topic) savePreference("warmupTopic", topic.topic);
  if (panelKey === "companion") {
    rerender();
  } else {
    setCompanionMood(restingMood());
  }
}

// Compares the artwork with the references the companion reads, as MainActivity.referenceChoice()
// and analyze() do.
async function matchReferences(target, pixels, width, height) {
  const project = target.manifest.project || "";
  const all = await store.references(project);
  if (!all.length) return null;
  const open = doc === target ? new Set(references.keys()) : new Set();
  const choice = aspectReferences(all.map((reference) => reference.name), open, await store.referenceNotes(project),
    await store.boardPurposes(project), target.manifest.followedBoards || []);
  const byName = new Map(all.map((reference) => [reference.name, reference]));
  const files = choice.names.map((name) => byName.get(name));
  const profiles = [];
  for (const reference of files) {
    const key = `${reference.name}:${reference.time}`;
    if (!referenceProfiles.has(key)) {
      const bitmap = await decodeReference(reference.file, REFERENCE_PROFILE_SIZE).catch(() => null);
      if (!bitmap) continue;
      const smallHeight = Math.max(1, Math.round(REFERENCE_PROFILE_SIZE * bitmap.height / bitmap.width));
      const surface = new OffscreenCanvas(REFERENCE_PROFILE_SIZE, smallHeight);
      const c = surface.getContext("2d");
      c.fillStyle = "#FFFFFF";
      c.fillRect(0, 0, REFERENCE_PROFILE_SIZE, smallHeight);
      c.drawImage(bitmap, 0, 0, REFERENCE_PROFILE_SIZE, smallHeight);
      bitmap.close?.();
      referenceProfiles.set(key, profileImage(c.getImageData(0, 0, REFERENCE_PROFILE_SIZE, smallHeight).data, REFERENCE_PROFILE_SIZE, smallHeight));
    }
    profiles.push(referenceProfiles.get(key));
  }
  const art = profileImage(pixels, width, height);
  const match = profiles.length === files.length ? compareWithReferences(art, profiles, choice.aspects) : compareWithReferences(art, profiles);
  return { match, files, scope: choice.scope, boards: choice.boards };
}

function mutedNotes() {
  return new Set(loadPreference("mutedNotes", []));
}

function mutedTopics() {
  return loadPreference("mutedTopics", {});
}

function muteNote(tip) {
  const topics = mutedTopics();
  topics[tip.topic] = (topics[tip.topic] || 0) + 1;
  savePreference("mutedNotes", [...mutedNotes(), tip.text]);
  savePreference("mutedTopics", topics);
  companionSection = 0;
  rerender();
}

function companionTips() {
  const all = [];
  if (progress) all.push({ topic: "Progress", text: progress, why: "", marks: [], action: null });
  if (referenceMatch) {
    const match = referenceMatch;
    const board = matchedBoards[match.weakest]?.[0];
    all.push({
      topic: "Your references",
      text: [match.summary, match.advice, board && `From your ${board} references.`].filter(Boolean).join(" "),
      why: match.why,
      marks: match.marksCanvas
        ? [{ label: "Furthest from your reference", shape: "RING", x1: match.farthest[0], y1: match.farthest[1], x2: 0.07, y2: 0 }]
        : [],
      action: "OPEN_REFERENCE",
    });
  }
  const perspectiveNote = perspectiveLog.summary();
  if (perspectiveNote) {
    const line = perspectiveLog.driftedLine;
    all.push({
      topic: "Perspective", text: perspectiveNote, why: "A straight line counts as drifted when it's over 4° from the guide.",
      marks: line ? [{ label: "This line drifts", shape: "LINE", x1: line[0] / doc.width, y1: line[1] / doc.height, x2: line[2] / doc.width, y2: line[3] / doc.height }] : [],
      action: "OPEN_GUIDES",
    });
  }
  return withoutMuted(all.concat(report?.tips || []), mutedNotes(), mutedTopics());
}

function currentTip() {
  const tips = companionTips();
  return tips.length ? tips[((tipIndex % tips.length) + tips.length) % tips.length] : null;
}

function companionPanel() {
  return h("div", { className: "companion-note" },
    h("button", { className: "hide-companion", ariaLabel: "Hide companion", title: "Hide companion", onClick: () => setCompanionVisible(false) }, svg("hidden")),
    companionNote());
}

function companionNote() {
  const feedback = warmupFeedbackNote();
  if (feedback) return feedback;
  const body = h("div", { className: "column" });
  const beside = (element) => {
    element.classList.add("beside");
    return element;
  };
  const tips = companionTips();
  const tip = currentTip();
  if (!tip) {
    body.append(beside(h("div", { className: "note-text" }, "Draw a little more, then pause. I'll have a note for you.")));
  } else {
    body.append(beside(h("div", { className: "caption" }, tip.topic)), beside(h("div", { className: "note-text" }, tip.text)));
    const chip = noteChip(tip);
    if (chip) body.append(gap(h("div", { className: "chips" }, chip)));
  }
  const actions = [];
  const toggleSection = (section) => () => {
    companionSection = companionSection === section ? 0 : section;
    rerender();
  };
  if (tips.length > 1) {
    actions.push(smallChip("Next", false, () => {
      tipIndex++;
      rerender();
    }));
  }
  if (tip?.why) actions.push(smallChip("Why", companionSection === 1, toggleSection(1)));
  if (referenceMatch) actions.push(smallChip("Match", companionSection === 3, toggleSection(3)));
  if (report?.ideas?.length) {
    actions.push(smallChip("Idea", companionSection === 5, () => {
      if (companionSection === 5) ideaIndex++;
      companionSection = 5;
      rerender();
    }));
  }
  actions.push(smallChip("Companion", companionSection === 2, toggleSection(2)));
  body.append(gap(h("div", { className: "chips wrap" }, actions)));

  if (companionSection === 5 && report?.ideas?.length) {
    const idea = report.ideas[ideaIndex % report.ideas.length];
    body.append(gap(h("div", { className: "small" }, "Idea")), h("div", { className: "note-text" }, idea.text));
    const brushKind = allBrushes().find((kind) => kind.id === idea.brushId);
    if (brushKind || idea.accentHue !== undefined) {
      body.append(gap(h("div", { className: "chips" }, smallChip("Try it", true, () => {
        if (brushKind) selectTool(brushKind);
        if (idea.accentHue !== undefined) {
          const [x, y] = placeCompanion();
          flashPickRing(hsvToColor([idea.accentHue, 0.7, 0.85]), x + 28, y);
        }
        closePanel();
      }))));
    }
  }
  const match = referenceMatch;
  if (companionSection === 3 && match) {
    const scope = {
      OPEN: "Compared with your open references",
      BOARDS: `Compared with: ${[...new Set(Object.values(matchedBoards).flat())].join(", ")}`,
      STARRED: "Compared with your starred references",
      NEWEST: `Compared with your ${matchedFiles.length} newest references`,
    }[matchedScope];
    body.append(gap(h("div", { className: "small" }, scope)));
    for (const area of match.areas) {
      body.append(gap(h("div", { className: "match-row" },
        h("div", { className: "caption match-name" }, area.name),
        h("div", { className: "match-bar", role: "progressbar", ariaLabel: `${area.name} match`, ariaValueNow: String(Math.round(area.score * 100)) },
          h("span", { style: { width: `${Math.round(area.score * 100)}%` } })))));
    }
    if (match.colors.length) {
      body.append(gap(h("div", { className: "caption" }, "Colors to try")),
        h("div", { className: "match-colors" }, match.colors.map((value) => h("button", {
          ariaLabel: "Use this color", style: { background: value },
          onClick: (event) => {
            const box = event.currentTarget.getBoundingClientRect();
            flashPickRing(value, box.left + box.width / 2, box.top);
          },
        }))));
    }
  }
  if (companionSection === 1 && tip) {
    body.append(gap(h("div", { className: "caption" }, tip.why)));
    if (tip.topic === "Your references" && match) {
      const closest = matchedFiles[match.closest];
      const pair = h("div", { className: "why-pair" });
      store.imageUrl(doc.id).then((url) => {
        if (url) pair.prepend(h("div", { className: "column" }, h("div", { className: "small" }, "Yours"), h("img", { src: url, alt: "Yours" })));
      }).catch(() => {});
      if (closest) {
        pair.append(h("div", { className: "column" }, h("div", { className: "small" }, "Closest reference"),
          h("img", { src: referencePreview(projectOf(), closest), alt: "Closest reference" })));
      }
      body.append(gap(pair));
    }
    if (report && (tip.topic === "Values" || tip.topic === "Silhouette")) {
      const preview = h("canvas", { className: "why-preview", width: report.width, height: report.height, ariaLabel: "Preview" });
      preview.getContext("2d").putImageData(new ImageData(tip.topic === "Values" ? report.values : report.silhouette, report.width, report.height), 0, 0);
      body.append(gap(h("div", { className: "column" }, h("div", { className: "small" }, "Preview"), preview)));
    }
  }
  if (companionSection === 6 && comparison) body.append(gap(h("div", { className: "note-text" }, comparison)));
  if (companionSection === 2) {
    const extras = [];
    if (tip) extras.push(smallChip("Not useful", false, () => muteNote(tip)));
    if (doc) {
      const compare = smallChip("Compare", false, () => compareVariations());
      compare.hidden = true;
      store.variations(doc.id).then((family) => { compare.hidden = family.length < 2; }).catch(() => {});
      extras.push(compare);
    }
    if (mutedNotes().size) {
      extras.push(smallChip("Bring notes back", false, () => {
        savePreference("mutedNotes", []);
        savePreference("mutedTopics", {});
        rerender();
      }));
    }
    if (extras.length) body.append(gap(h("div", { className: "chips wrap" }, extras)));
    body.append(gap(h("div", { className: "looks" }, LOOKS.map(([look, label]) => {
      const face = h("canvas", { className: "look-face" });
      const button = h("button", {
        className: look === companionLook ? "look on" : "look", ariaLabel: label, title: label,
        onClick: () => chooseCompanion(look),
      }, face);
      requestAnimationFrame(() => {
        const preview = new Companion(face, { look, blink: false, still: true });
        preview.setColor(color);
      });
      return button;
    }))));
  }
  showNoteMarks(tip?.marks || []);
  return body;
}

// --- Warm-ups, as MainActivity's warm-up bar and feedback -------------------------------------

const WARMUP_SIZE = 2048;
const WARMUP_READ_WIDTH = 512;
const WARMUP_NOTE_DELAY_MS = 900;
const warmupBar = document.getElementById("warmup-bar");
const warmupStrokes = [];
const undoneWarmupStrokes = [];
let strokePath = null;  // The stroke being drawn, as canvas x and y pairs.
let warmupBubble = null;
let lastWarmupNote = null;
let lastWarmupMarks = [];

function sunLine(warmup, layout) {
  const sun = singleSun(warmup.kind, layout);
  return sun ? `The sun is at the ${sun.name}.` : null;
}

// Opens a fresh square canvas for one exercise: the guide underneath, a practice layer on top.
async function startWarmup(warmup, seed = Math.floor(Math.random() * 2 ** 31)) {
  const layout = new WarmupLayout(seed);
  const next = await store.create("Warm-up", "", WARMUP_SIZE, WARMUP_SIZE);
  next.warmup = warmup;
  next.layout = layout;
  next.manifest.layers[0].name = "Guide";
  next.manifest.layers.push({ ...next.manifest.layers[0], id: 2, name: "Practice" });
  next.manifest.activeLayer = 2;
  next.manifest.nextLayerId = 3;
  warmupStrokes.length = 0;
  undoneWarmupStrokes.length = 0;
  warmupBubble = null;
  lastWarmupNote = null;
  lastWarmupMarks = [];
  await openDocument(next);
  const surface = new OffscreenCanvas(WARMUP_SIZE, WARMUP_SIZE);
  const c = surface.getContext("2d");
  drawWarmupGuide(c, warmup.kind, WARMUP_SIZE, WARMUP_SIZE, layout);
  const pixels = c.getImageData(0, 0, WARMUP_SIZE, WARMUP_SIZE).data;
  // The engine keeps layers with premultiplied alpha, as Android's bitmaps do.
  for (let i = 0; i < pixels.length; i += 4) {
    const alpha = pixels[i + 3] / 255;
    pixels[i] = Math.round(pixels[i] * alpha);
    pixels[i + 1] = Math.round(pixels[i + 1] * alpha);
    pixels[i + 2] = Math.round(pixels[i + 2] * alpha);
  }
  const pointer = engine._malloc(pixels.length);
  engine.HEAPU8.set(pixels, pointer);
  engine._cap_write_layer(1, pointer);
  engine._free(pointer);
  queueCompose();
  showWarmupBar();
  const steps = [sunLine(warmup, layout), warmup.steps].filter(Boolean).join(" ");
  setTimeout(() => {
    if (doc === next) say(steps, true);
  }, WARMUP_NOTE_DELAY_MS);
}

// A slim bar at the bottom right with the exercise, its feedback, and where to go next.
function showWarmupBar() {
  const warmup = doc?.warmup;
  warmupBar.hidden = !warmup || interfaceHidden;
  if (!warmup) return;
  const sun = sunLine(warmup, doc.layout);
  warmupBar.replaceChildren(
    h("div", { className: "column" }, h("div", { className: "warmup-name" }, warmup.title), sun && h("div", { className: "warmup-sun" }, sun)),
    h("div", { className: "chips" },
      smallChip(lastWarmupNote === null ? "How did I do?" : "Look again", true, () => warmupFeedbackNow()),
      smallChip("Why?", false, () => explainWarmup()),
      smallChip("Again", false, () => startWarmup(warmup)),
      smallChip("Another", false, () => warmupPicker(companionLook, WARMUPS[(WARMUPS.indexOf(warmup) + 1) % WARMUPS.length], warmup, (next) => startWarmup(next))),
      smallChip("Finish", false, () => leaveStudio())));
}

// Reads what you drew and lets the companion comment on just what the exercise practices.
async function warmupFeedbackNow() {
  const target = doc;
  if (!target?.warmup) return;
  closePanel();
  setCompanionMood("thinking");
  const strokes = warmupStrokes.map((path) => path.map((value, index) => (index % 2 === 0 ? value / target.width : value / target.height)));
  const width = WARMUP_READ_WIDTH;
  const height = Math.max(1, Math.floor(width * target.height / target.width));
  const size = width * height * 4;
  const pointer = engine._malloc(size);
  engine._cap_read_composite(width, height, pointer);
  const pixels = engine.HEAPU8.slice(pointer, pointer + size);
  engine._free(pointer);
  await new Promise((resolve) => setTimeout(resolve, 0));
  const review = warmupReview(target.warmup.kind, new WarmupWork(strokes, pixels, width, height, target.layout));
  if (doc !== target) return;
  lastWarmupNote = review.text;
  warmupBubble = review.text;
  lastWarmupMarks = review.marks;
  setCompanionMood("idle");
  showWarmupBar();
  if (!companionVisible) setCompanionVisible(true);
  showPanel("companion", companionPanel);
}

// The companion reminds you what the exercise is for.
function explainWarmup() {
  warmupBubble = doc.warmup.purpose;
  lastWarmupMarks = [];
  closePanel();
  if (!companionVisible) setCompanionVisible(true);
  showPanel("companion", companionPanel);
}

// In a warm-up, the companion's bubble tells you how the exercise went.
function warmupFeedbackNote() {
  const warmup = doc?.warmup;
  if (!warmup || !warmupBubble) return null;
  showNoteMarks(lastWarmupMarks);
  return h("div", { className: "column" },
    h("div", { className: "caption beside" }, warmupBubble === warmup.purpose ? `Why ${warmup.title}` : warmup.title),
    h("div", { className: "note-text beside" }, warmupBubble));
}

// One tap from a note to the tool that helps with it.
function noteChip(tip) {
  const toggle = (active, on, off, change) => smallChip(active ? off : on, true, () => {
    change();
    engine._cap_set_view_options(viewMode, guide);
    queueCompose();
    rerender();
  });
  const open = (name, run) => smallChip(name, true, () => {
    closePanel();
    run();
  });
  switch (tip.action) {
    case "SEE_VALUES": return toggle(viewMode === 1, "See in gray", "Back to color", () => { viewMode = viewMode === 1 ? 0 : 1; });
    case "CHECK_SHAPES": return toggle(viewMode === 2, "See big shapes", "Back to color", () => { viewMode = viewMode === 2 ? 0 : 2; });
    case "SHOW_THIRDS": return toggle(guide === 1, "Show thirds", "Hide thirds", () => { guide = guide === 1 ? 0 : 1; });
    case "OPEN_COLORS": return open("Open colors", () => {
      colorBeforePanel = color;
      showPanel("color", colorPanel);
    });
    case "OPEN_GUIDES": return open("Open guides", () => showPanel("view", viewPanel));
    case "BLOCK_IN": return open("Use Chalk", () => {
      const chalk = allBrushes().find((kind) => kind.id === "chalk");
      if (chalk) selectTool(chalk);
    });
    case "SHADOW_LAYER": return open("Add a shadow layer", () => addLayer({ blend: 1 }));
    case "OPEN_REFERENCE": {
      const file = referenceMatch && matchedFiles[referenceMatch.closest];
      if (!file || references.has(file.name)) return null;
      return open("Open this reference", () => toggleReference(file));
    }
    default: return null;
  }
}

function chooseCompanion(look) {
  companionLook = look;
  savePreference("mascot", look);
  companion.setLook(look);
  peek.setLook(look);
  rerender();
}

// Finds which variation of this artwork reads clearest at a small size.
async function compareVariations() {
  const family = await store.variations(doc.id);
  const reports = [];
  for (const art of family) {
    const url = await store.imageUrl(art.id, false);
    if (!url) continue;
    const image = new Image();
    image.src = url;
    await image.decode().catch(() => {});
    URL.revokeObjectURL(url);
    if (!image.naturalWidth) continue;
    const surface = new OffscreenCanvas(image.naturalWidth, image.naturalHeight);
    const c = surface.getContext("2d");
    c.drawImage(image, 0, 0);
    const pixels = c.getImageData(0, 0, surface.width, surface.height).data;
    reports.push([art, analyzeArtwork(pixels, surface.width, surface.height)]);
  }
  const best = reports[clearestAtASmallSize(reports.map(([, result]) => result))]?.[0];
  comparison = best ? `${best.name} reads clearest at a small size. Its lights and darks separate the most.` : null;
  companionSection = 6;
  rerender();
}

// --- The marks a note points at, drawn on the overlay above the canvas ------------------------

let noteMarks = [];

function showNoteMarks(marks) {
  noteMarks = marks;
  drawOverlay();
}

function canvasToScreen(x, y) {
  const [a, b, c, d] = viewLinear();
  return [a * x + b * y + panX, c * x + d * y + panY];
}

function drawOverlay() {
  const c = overlayContext;
  c.clearRect(0, 0, overlay.width, overlay.height);
  if (toolGesture && (toolGesture.kind.mode === "lasso" || toolGesture.kind.mode === "select")) drawLasso();
  if (liquifyRing) {
    const [x, y, radius] = liquifyRing;
    c.beginPath();
    c.arc(x, y, radius, 0, Math.PI * 2);
    c.lineWidth = 3 * dpr * uiScale();
    c.strokeStyle = "rgba(0, 0, 0, 0.43)";
    c.stroke();
    c.lineWidth = 1 * dpr * uiScale();
    c.strokeStyle = "#FFFFFF";
    c.stroke();
  }
  if (!noteMarks.length) return;
  const place = (x, y) => canvasToScreen(x * canvasWidth, y * canvasHeight);
  c.lineCap = "round";
  for (const mark of noteMarks) {
    const shape = new Path2D();
    let anchor;
    if (mark.shape === "RING") {
      const center = place(mark.x1, mark.y1);
      const edge = place(mark.x1 + mark.x2, mark.y1);
      const radius = Math.hypot(edge[0] - center[0], edge[1] - center[1]);
      shape.arc(center[0], center[1], radius, 0, Math.PI * 2);
      anchor = [center[0], center[1] + radius];
    } else if (mark.shape === "BOX") {
      const corners = [[mark.x1, mark.y1], [mark.x2, mark.y1], [mark.x2, mark.y2], [mark.x1, mark.y2]].map(([x, y]) => place(x, y));
      shape.moveTo(...corners[0]);
      corners.slice(1).forEach((corner) => shape.lineTo(...corner));
      shape.closePath();
      anchor = [(corners[2][0] + corners[3][0]) / 2, (corners[2][1] + corners[3][1]) / 2];
    } else {
      const start = place(mark.x1, mark.y1);
      const end = place(mark.x2, mark.y2);
      shape.moveTo(...start);
      shape.lineTo(...end);
      anchor = [(start[0] + end[0]) / 2, (start[1] + end[1]) / 2];
    }
    c.lineWidth = 4 * dpr * uiScale();
    c.strokeStyle = "rgba(0, 0, 0, 0.35)";
    c.stroke(shape);
    c.lineWidth = 2 * dpr * uiScale();
    c.strokeStyle = "#FFFFFF";
    c.stroke(shape);
    drawMarkLabel(c, mark.label, anchor[0], anchor[1] + 8 * dpr * uiScale());
  }
}

function drawMarkLabel(c, label, x, y) {
  const unit = dpr * uiScale();
  c.font = `${12 * unit}px Roboto, "Segoe UI", system-ui, sans-serif`;
  const padding = 8 * unit;
  const width = c.measureText(label).width + padding * 2;
  const height = 24 * unit;
  const edge = 72 * unit;
  const left = Math.min(Math.max(edge, x - width / 2), Math.max(edge, overlay.width - width - edge));
  const top = Math.min(Math.max(edge, y), Math.max(edge, overlay.height - height));
  c.fillStyle = "#2C2C2A";
  c.beginPath();
  c.roundRect(left, top, width, height, height / 2);
  c.fill();
  c.fillStyle = TEXT_COLOR;
  c.textBaseline = "middle";
  c.fillText(label, left + padding, top + height / 2);
}

// A loupe ring at a spot: the new color on top and the previous one below, for a moment.
function flashPickRing(next, x, y) {
  const previous = color;
  const ring = h("canvas", { className: "pick-ring", width: 96 * pixelRatio(), height: 96 * pixelRatio() });
  ring.style.left = `${x - 48}px`;
  ring.style.top = `${y - 96}px`;
  const c = ring.getContext("2d");
  const size = ring.width;
  const thickness = size * 0.16;
  const radius = size / 2 - thickness / 2 - 2;
  c.lineWidth = thickness + 4;
  c.strokeStyle = "#FFFFFF";
  c.beginPath();
  c.arc(size / 2, size / 2, radius, 0, Math.PI * 2);
  c.stroke();
  c.lineWidth = thickness;
  c.strokeStyle = next;
  c.beginPath();
  c.arc(size / 2, size / 2, radius, Math.PI, Math.PI * 2);
  c.stroke();
  c.strokeStyle = previous;
  c.beginPath();
  c.arc(size / 2, size / 2, radius, 0, Math.PI);
  c.stroke();
  studioView.append(ring);
  setColor(next);
  rememberColor();
  setTimeout(() => ring.remove(), 700);
}

// Places the bubble beside the companion, with a tail pointing at it.
function placeBubble() {
  const [x, y] = placeCompanion();
  const size = 56;
  const width = panel.offsetWidth;
  const height = panel.offsetHeight;
  const right = x + size + 2;
  const tailLeft = right + width <= viewWidth();
  const left = tailLeft ? right : Math.max(0, x - 2 - width);
  const top = Math.min(Math.max(0, y + size / 2 - height / 2), Math.max(0, viewHeight() - height));
  panel.style.left = `${left}px`;
  panel.style.top = `${top}px`;
  panel.classList.toggle("tail-left", tailLeft);
  panel.classList.toggle("tail-right", !tailLeft);
  const tailY = Math.min(Math.max(16 + BUBBLE_TAIL, y + 28 - top), height - 16 - BUBBLE_TAIL);
  panel.style.setProperty("--tail-y", `${tailY}px`);
}

const panelObserver = new MutationObserver(() => {
  if (!panel.classList.contains("bubble")) {
    panel.style.left = "";
    panel.style.top = "";
  }
});
panelObserver.observe(panel, { attributes: true, attributeFilter: ["class"] });

// --- Messages ----------------------------------------------------------------------------------

// Notes come from the studio's companion, in the brush color, as on Android.
function say(text, long = false) {
  sayNote(text, { long, look: companionLook, color });
}

function fail(text) {
  message.textContent = text;
  message.hidden = false;
  throw new Error(text);
}

// --- Artworks: opening, saving, and going home, as MainActivity -------------------------------

let store = null;
let waitingFolder = null;
// --- References, as referencesPanel() and the ReferenceHost in MainActivity ------------------------

const references = new Map();  // File name -> its open ReferenceWindow.
let referenceFilter = "";
let pickingReferences = false;
const pickedReferences = new Set();

const projectOf = () => doc.manifest.project || "";

const referenceHost = {
  changed: () => rememberReferences(),
  closed: (window) => {
    references.delete(window.state.file);
    window.dismiss();
    rememberReferences();
    if (panelKey === "references") showReferences();
  },
  pick: (value, x, y) => {
    pickedColor = value;
    showPickRing(value, x, y);
  },
  pickEnd: () => endPick(),
  palette: (window) => referencePalette(window),
  toLayer: (window) => referenceToLayer(window),
};

function rememberReferences() {
  doc.manifest.references = [...references.values()].map((window) => ({ ...window.state }));
  markUnsaved();
}

function closeReferences() {
  for (const window of references.values()) window.dismiss();
  references.clear();
}

async function restoreReferences() {
  closeReferences();
  const opening = doc;
  for (const state of doc.manifest.references || []) {
    const file = await store.referenceFile(projectOf(), state.file).catch(() => null);
    if (!file || doc !== opening) continue;
    const bitmap = await decodeReference(file).catch(() => null);
    if (bitmap && doc === opening) openReference({ ...state }, bitmap);
  }
}

function openReference(state, bitmap) {
  const window = new ReferenceWindow(studioView, state, bitmap, referenceHost);
  references.set(state.file, window);
}

async function toggleReference(reference) {
  const open = references.get(reference.name);
  if (open) return referenceHost.closed(open);
  const bitmap = await decodeReference(reference.file).catch(() => null);
  if (!bitmap) return say("Couldn't open this image");
  const width = fitReferenceWidth(bitmap.width, bitmap.height, viewWidth(), viewHeight());
  const offset = 32 * references.size;
  openReference({
    file: reference.name, x: 90 + offset, y: 90 + offset, width, zoom: 1, panX: 0, panY: 0,
    grayscale: false, flipped: false, opacity: 1, pinned: false,
  }, bitmap);
  rememberReferences();
}

function referencePalette(window) {
  const { bitmap } = window;
  const width = 240;
  const height = Math.max(1, Math.round(240 * bitmap.height / bitmap.width));
  const surface = new OffscreenCanvas(width, height);
  const c = surface.getContext("2d");
  c.drawImage(bitmap, 0, 0, width, height);
  savePalette("Reference colors", extractPalette(c.getImageData(0, 0, width, height).data));
}

function referenceToLayer(window) {
  const { bitmap } = window;
  const scale = Math.min(doc.width / bitmap.width, doc.height / bitmap.height);
  addImage(bitmap, bitmap.width * scale, bitmap.height * scale, `Reference ${doc.manifest.nextLayerId}`, false);
}

async function savePalette(name, colors) {
  if (!colors.length) return say("There aren't any colors to collect yet");
  await store.addPalette(name, colors);
  savedPalettes = await store.palettes();
  say("Palette saved");
  if (panelKey === "color") rerender();
}

function paletteFromArtwork() {
  const width = THUMBNAIL_WIDTH;
  const height = thumbnailHeight(doc.width, doc.height);
  savePalette(`${doc.manifest.name} colors`, extractPalette(readComposite(width, height)));
}

function pickReferenceFiles() {
  const input = h("input", { type: "file", accept: "image/*", multiple: true });
  const project = projectOf();
  const block = referenceFilter && referenceFilter !== STARRED_FILTER ? referenceFilter : null;
  input.addEventListener("change", async () => {
    for (const file of input.files) {
      try {
        const name = await store.addReference(project, file);
        if (block) await store.updateReferenceNote(project, name, (note) => ({ ...note, board: block }));
      } catch (error) {
        console.error(error);
      }
    }
    showReferences();
  });
  input.click();
}

// Reads the library, then shows the panel; the panel itself is built from what was read.
async function showReferences() {
  const project = projectOf();
  const [all, notes, purposes] = await Promise.all([
    store.references(project), store.referenceNotes(project), store.boardPurposes(project),
  ]);
  const build = () => referencesPanel(all, notes, purposes);
  if (panelKey === "references") {
    panelBuilder = build;
    rerender();
  } else {
    showPanel("references", build);
  }
}

function referencesPanel(all, notes, purposes) {
  const blocks = referenceBlocks(notes);
  if (referenceFilter && referenceFilter !== STARRED_FILTER && !blocks.includes(referenceFilter)) referenceFilter = "";
  const inside = referenceFilter !== "";
  const project = projectOf();
  const body = h("div", { className: "column" });
  const header = h("div", { className: "row header" });
  if (inside) {
    header.append(smallChip("‹", false, () => {
      referenceFilter = "";
      pickingReferences = false;
      pickedReferences.clear();
      showReferences();
    }));
    header.lastChild.style.marginRight = "8px";
  }
  header.append(h("div", { className: "title grow" },
    referenceFilter === STARRED_FILTER ? "★" : inside ? referenceFilter : "References"));
  if (all.length) {
    header.append(smallChip("Select", pickingReferences, () => {
      pickingReferences = !pickingReferences;
      pickedReferences.clear();
      showReferences();
    }));
  }
  header.append(h("button", { className: "icon", ariaLabel: "Add references", innerHTML: ICONS.add, onClick: pickReferenceFiles }));
  body.append(header);
  if (!all.length) {
    body.append(gap(h("div", { className: "small" }, "Add images, or drop them here from another app.")));
    return body;
  }
  const names = inside
    ? new Set(filterReferences(all.map((reference) => reference.name), notes, referenceFilter))
    : new Set(all.filter((reference) => !notes[reference.name]?.board).map((reference) => reference.name));
  const files = all.filter((reference) => names.has(reference.name));
  if (!inside) {
    const full = blocks.filter((block) => all.some((reference) => notes[reference.name]?.board === block));
    if (full.length) {
      body.append(gap(h("div", { className: "reference-blocks" }, full.map((block) => {
        const inBlock = all.filter((reference) => notes[reference.name]?.board === block);
        return h("button", {
          className: "reference-block", ariaLabel: block,
          onClick: () => {
            referenceFilter = block;
            showReferences();
          },
        },
        h("div", { className: "peeks" }, inBlock.slice(0, 3).map((reference) => h("img", { src: referencePreview(project, reference), alt: "" }))),
        h("div", { className: "name" }, `${block} · ${inBlock.length}`));
      }))));
    }
    const quiet = [];
    if (all.some((reference) => notes[reference.name]?.starred)) quiet.push(STARRED_FILTER);
    quiet.push(...blocks.filter((block) => !full.includes(block)));
    body.append(gap(h("div", { className: "wrap-row" }, quiet.map((block) => smallChip(block === STARRED_FILTER ? "★" : block, false, () => {
      referenceFilter = block;
      showReferences();
    })))));
    if (files.length) body.append(gap(h("div", { className: "small" }, "Not sorted")));
  }
  if (pickingReferences) body.append(gap(h("div", { className: "wrap-row" }, referencePickActions(files))));
  if (blocks.includes(referenceFilter)) boardControls(body, referenceFilter, purposes);
  if (files.length) {
    body.append(gap(h("div", { className: "reference-grid" }, files.map((reference) => {
      const open = references.has(reference.name);
      const marked = pickingReferences ? pickedReferences.has(reference.name) : open;
      const note = notes[reference.name];
      const image = h("img", { src: referencePreview(project, reference), alt: open ? "Close reference" : "Open reference" });
      const cell = h("button", { className: marked ? "reference-cell on" : "reference-cell", ariaLabel: image.alt }, image,
        note?.board && h("span", { className: "block" }, note.board),
        note?.starred && h("span", { className: "star" }, "★"));
      let held = false;
      let timer = 0;
      cell.addEventListener("pointerdown", () => {
        held = false;
        timer = setTimeout(() => {
          held = true;
          manageReference(reference);
        }, 500);
      });
      for (const type of ["pointerup", "pointerleave", "pointercancel"]) cell.addEventListener(type, () => clearTimeout(timer));
      cell.addEventListener("contextmenu", (event) => {
        event.preventDefault();
        clearTimeout(timer);
        if (!held) manageReference(reference);
        held = true;
      });
      cell.addEventListener("click", async () => {
        if (held) return;
        if (pickingReferences) {
          if (!pickedReferences.delete(reference.name)) pickedReferences.add(reference.name);
        } else {
          await toggleReference(reference);
        }
        showReferences();
      });
      return cell;
    }))));
  }
  return body;
}

// What the companion uses this board for, and whether this artwork follows it.
function boardControls(body, board, purposes) {
  const project = projectOf();
  const uses = purposes[board] || [];
  body.append(gap(h("div", { className: "small" }, "Your companion reads this block for")));
  body.append(gap(h("div", { className: "wrap-row" }, BOARD_PURPOSES.map((purpose) =>
    smallChip(PURPOSE_LABELS[purpose], uses.includes(purpose), async () => {
      let next;
      if (uses.includes(purpose)) next = uses.filter((use) => use !== purpose);
      else if (purpose === "INSPIRATION") next = [purpose];
      else next = [...uses.filter((use) => use !== "INSPIRATION"), purpose];
      await store.setBoardPurposes(project, board, next);
      markUnsaved();
      showReferences();
    })))));
  const followed = doc.manifest.followedBoards || [];
  const following = followed.includes(board);
  body.append(gap(h("div", { className: "wrap-row" }, smallChip("Only for this artwork", following, () => {
    doc.manifest.followedBoards = following ? followed.filter((name) => name !== board) : [...followed, board];
    markUnsaved();
    showReferences();
  }))));
}

function referencePickActions(files) {
  const picked = files.filter((reference) => pickedReferences.has(reference.name)).map((reference) => reference.name);
  const project = projectOf();
  const finish = () => {
    pickingReferences = false;
    pickedReferences.clear();
    showReferences();
  };
  const actions = [smallChip("All", false, () => {
    for (const reference of files) pickedReferences.add(reference.name);
    showReferences();
  })];
  if (!picked.length) return actions;
  actions.push(smallChip("★", false, async () => {
    for (const name of picked) await store.updateReferenceNote(project, name, (note) => ({ ...note, starred: true }));
    finish();
  }));
  actions.push(smallChip("Block", false, () => chooseBoard(store, project, picked, finish)));
  actions.push(smallChip("Move", false, () => chooseSketchbook(store, project, picked, () => {
    for (const name of picked) references.get(name)?.dismiss();
    for (const name of picked) references.delete(name);
    rememberReferences();
    finish();
  })));
  actions.push(smallChip("Delete", false, () => confirmSheet(`Delete ${picked.length} references?`,
    "They're removed from this project and every artwork using them.", async () => {
      for (const name of picked) {
        references.get(name)?.dismiss();
        references.delete(name);
        await store.deleteReference(project, name);
      }
      rememberReferences();
      finish();
    })));
  return actions;
}

function manageReference(reference) {
  const project = projectOf();
  const forget = () => {
    references.get(reference.name)?.dismiss();
    references.delete(reference.name);
    rememberReferences();
  };
  referenceOptions(store, project, reference.name, {
    changed: showReferences,
    moved: () => {
      forget();
      showReferences();
    },
    remove: () => confirmSheet("Delete this reference?", "It's removed from this project and every artwork using it.", async () => {
      forget();
      await store.deleteReference(project, reference.name);
      showReferences();
    }),
  });
}

let home = null;
let doc = null;  // The open artwork: {id, dir, manifest, width, height, fresh}.
let ready = false;
let unsaved = false;
let previewStale = false;
let saveTimer = 0;
let saving = Promise.resolve();
const rectPointer = engine._malloc(16);

function markUnsaved() {
  // A warm-up is only practice, so it never reaches the library.
  if (!doc || doc.warmup) return;
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
  const blob = await surface.convertToBlob(type === "image/jpeg" ? { type, quality: 0.92 } : { type });
  return { blob, pixels };
}

// Reads every layer's changes now, while the engine holds them, then writes in the background.
function save(withPreview) {
  if (!doc || doc.warmup) return saving;
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
  setCompanionMood("thinking");
  const saveStore = store;
  saving = saving.then(async () => {
    const small = await thumbnail;
    await saveStore.save(target, regions, small.blob, preview && (await preview).blob);
    await saveStore.addTimelapseFrame(target, small.pixels, THUMBNAIL_WIDTH, thumbnailHeight(target.width, target.height)).catch(() => {});
    await analyze(target, small.pixels, THUMBNAIL_WIDTH, thumbnailHeight(target.width, target.height));
  }).catch((error) => {
    console.error(error);
    // Those changes never reached disk, so the next save has to include them.
    if (doc === target) {
      for (const id of regions.keys()) engine._cap_mark_layer_dirty(id);
      unsaved = true;
    }
    say("Couldn't save your artwork", true);
  }).finally(() => {
    if (companion.mood === "thinking") setCompanionMood(restingMood());
  });
  return saving;
}

// Puts an artwork in the engine: its layers bottom first, their masks, and their settings.
async function openDocument(next) {
  ready = false;
  doc = next;
  report = null;
  progress = null;
  earlier = null;
  earlierLoaded = false;
  latestSnapshot = null;
  unreadTips = false;
  tipIndex = 0;
  comparison = null;
  referenceMatch = null;
  matchedFiles = [];
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
  history.clear();
  engineLayers.clear();
  selectionTool = "none";
  hasSelection = false;
  selectionMode = 0;
  selectionFeather = 0;
  editingMask = false;
  markMaskEditing();
  pickingLayers = false;
  maxLayers = Math.min(64, Math.max(8, Math.floor((navigator.deviceMemory || 8) * 1024 ** 3 / 5 / (canvasWidth * canvasHeight * 4))));
  try {
    for (const layer of manifest.layers) {
      engine._cap_create_layer(layer.id);
      engineLayers.add(layer.id);
      ids.push(layer.id);
      if (!next.fresh && await store.readLayer(next, layer.tiles, layer.file, pixels)) {
        engine.HEAPU8.set(pixels, pointer);
        engine._cap_write_layer(layer.id, pointer);
      }
      if (layer.mask) {
        engine._cap_create_layer(layer.mask);
        engineLayers.add(layer.mask);
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
  perspectiveLog.reset();
  pushGuides();
  viewMode = 0;
  guide = 0;
  engine._cap_set_view_options(0, 0);
  unsaved = false;
  previewStale = !!next.fresh;
  queueCompose();
  showWarmupBar();
  stopPlaying();
  refreshAnimationBar();
  ready = true;
  referenceFilter = "";
  pickingReferences = false;
  restoreReferences();
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
  peek.resize();
  showCompanionState();
  setCompanionMood("idle");
  scheduleNap();
  selectTool(brush);
}

async function leaveStudio() {
  if (!ready) return;
  finishTransform(false);
  finishLiquify(false);
  ready = false;
  closePanel();
  clearTimeout(saveTimer);
  if (unsaved || previewStale || doc?.fresh) save(true);
  await saving;
  stopPlaying();
  closeReferences();
  hideShapeHandles();
  doc = null;
  warmupBar.hidden = true;
  animationBar.hidden = true;
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
  loadCustomBrushes();
}

// --- Start -------------------------------------------------------------------------------------

window.addEventListener("resize", () => {
  resize();
  placeRecentStrip();
  placeCompanion();
  for (const window of references.values()) window.refit();
});

// Images dropped on the studio join the sketchbook's references, as sharing them does on Android.
studioView.addEventListener("dragover", (event) => {
  if ([...event.dataTransfer.items].some((item) => item.type.startsWith("image/"))) event.preventDefault();
});
studioView.addEventListener("drop", async (event) => {
  const files = [...event.dataTransfer.files].filter((file) => file.type.startsWith("image/"));
  if (!files.length || !doc) return;
  event.preventDefault();
  for (const file of files) await store.addReference(projectOf(), file).catch(() => null);
  say(files.length === 1 ? "Added to references" : `Added ${files.length} references`);
  showReferences();
});
resize();
setColor(color);
selectTool(brush);
showRecentColors();
setStats(statsOn);

({ store, waiting: waitingFolder } = await ArtworkStore.open());
await loadCustomBrushes();
home = new Home(homeView, {
  get store() { return store; },
  get waiting() { return waitingFolder; },
  setStore,
  openArtwork,
  newArtwork,
  startWarmup,
});
await home.render();
window.addEventListener("resize", () => {
  if (!homeView.hidden) home.render();
});

// Exposed for checking the page from the console and from automated tests.
window.cappuccino = { engine, fitView, undo, redo, selectTool, setColor, leaveStudio, save: () => { markUnsaved(); return save(true); }, get store() { return store; } };
