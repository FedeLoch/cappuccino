// Pen test page for the Cappuccino engine running as WebAssembly on WebGL 2.
//
// Strokes draw straight from the pointer handlers into a desynchronized canvas, the way the
// Android app draws into its front buffer. Pan, zoom, undo, and resizing redraw the whole view
// on the next animation frame. Nothing here allocates per sample or per frame except the arrays
// the browser itself returns from getCoalescedEvents() and getPredictedEvents().

import createEngine from "./engine.js";

const CANVAS_WIDTH = 2048;
const CANVAS_HEIGHT = 1536;
const MIN_ZOOM = 0.05;
const MAX_ZOOM = 32;

// Brush settings copied from BrushKind in app/src/main/java/art/cappuccino/paint/Model.kt.
const TOOLS = {
  ink:    { minScale: 0.12, spacing: 0.08, flow: 1.0,  pressureFlow: 0.0, hardness: 1.0, grain: 0.0, size: 24, eraser: false },
  pencil: { minScale: 0.5,  spacing: 0.1,  flow: 0.35, pressureFlow: 0.8, hardness: 0.7, grain: 0.8, size: 16, eraser: false },
  eraser: { minScale: 0.12, spacing: 0.08, flow: 1.0,  pressureFlow: 0.0, hardness: 1.0, grain: 0.0, size: 40, eraser: true },
};
const PRESSURE_GAMMA = 0.75;
const COLORS = ["#2C2C2A", "#D85A30", "#2F6DB5", "#3E8A5C", "#E8B83A", "#FFFFFF"];

const canvas = document.getElementById("canvas");
const statsBox = document.getElementById("stats");
const message = document.getElementById("message");
const undoButton = document.getElementById("undo");
const redoButton = document.getElementById("redo");
const sizeInput = document.getElementById("size");
const sizeValue = document.getElementById("size-value");

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
if (!engine._cap_init(CANVAS_WIDTH, CANVAS_HEIGHT)) fail("Couldn't start the drawing engine.");

const SAMPLE_FLOATS = engine._cap_sample_floats();
const MAX_SAMPLES = engine._cap_max_samples();
const samplesOffset = engine._cap_samples() >> 2;
const predictedOffset = engine._cap_predicted() >> 2;
const viewOffset = engine._cap_view() >> 2;

// --- State -------------------------------------------------------------------------------------

let tool = "ink";
let color = hexToRgb(COLORS[0]);
const sizes = { ink: TOOLS.ink.size, pencil: TOOLS.pencil.size, eraser: TOOLS.eraser.size };

let dpr = 1;
let zoom = 1;
let panX = 0;
let panY = 0;
let composeQueued = false;
let undoCount = 0;
let redoCount = 0;

let strokePointer = -1;  // The pen or mouse drawing right now.
let strokeSamples = 0;
const touches = new Map();  // pointerId -> {x, y} in buffer pixels, for pan and zoom.
let gesture = null;
let panPointer = -1;  // Middle button or space + drag.
let panLastX = 0;
let panLastY = 0;
let spaceHeld = false;

// --- Stats -------------------------------------------------------------------------------------

const STAT_WINDOW = 120;
const drawTimes = new Float64Array(STAT_WINDOW);
const inputLatencies = new Float64Array(STAT_WINDOW);
const frameLatencies = new Float64Array(STAT_WINDOW);
const frameIntervals = new Float64Array(STAT_WINDOW);
const composeTimes = new Float64Array(STAT_WINDOW);
let drawIndex = 0, inputIndex = 0, frameLatencyIndex = 0, frameIndex = 0, composeIndex = 0;
let pendingEventTime = -1;  // Newest drawn sample still waiting for a frame.
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

// --- View --------------------------------------------------------------------------------------

function writeView() {
  const heap = engine.HEAPF32;
  heap[viewOffset] = zoom;
  heap[viewOffset + 1] = 0;
  heap[viewOffset + 2] = panX;
  heap[viewOffset + 3] = 0;
  heap[viewOffset + 4] = zoom;
  heap[viewOffset + 5] = panY;
  heap[viewOffset + 6] = 0;
  heap[viewOffset + 7] = 0;
  heap[viewOffset + 8] = 1;
}

function fitView() {
  zoom = Math.min(canvas.width / CANVAS_WIDTH, canvas.height / CANVAS_HEIGHT) * 0.88;
  panX = (canvas.width - CANVAS_WIDTH * zoom) / 2;
  panY = (canvas.height - CANVAS_HEIGHT * zoom) / 2 + 24 * dpr;
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

function resize() {
  dpr = window.devicePixelRatio || 1;
  const width = Math.round(window.innerWidth * dpr);
  const height = Math.round(window.innerHeight * dpr);
  if (width === canvas.width && height === canvas.height) return false;
  const first = canvas.width === 300 && canvas.height === 150;
  // Keeps the middle of the screen in place.
  panX += (width - canvas.width) / 2;
  panY += (height - canvas.height) / 2;
  canvas.width = width;
  canvas.height = height;
  engine._cap_resize(width, height);
  if (first) fitView();
  writeView();
  composeNow();
  return true;
}

function queueCompose() {
  if (composeQueued) return;
  composeQueued = true;
  requestAnimationFrame(onFrame);
}

function composeNow() {
  composeQueued = false;
  const start = performance.now();
  engine._cap_compose();
  composeIndex = record(composeTimes, composeIndex, performance.now() - start);
}

function onFrame() {
  if (composeQueued) composeNow();
}

// The stats loop runs every frame only while the panel is open.
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

// --- Strokes -----------------------------------------------------------------------------------

function beginStroke() {
  const t = TOOLS[tool];
  engine._cap_begin_stroke(
    sizes[tool], color[0], color[1], color[2],
    t.flow, t.minScale, t.spacing, t.pressureFlow, t.hardness, t.grain,
    t.eraser ? 1 : 0, PRESSURE_GAMMA,
  );
  strokeSamples = 0;
}

// Writes one pointer sample, in canvas pixels, into the engine's buffer at slot.
function writeSample(offset, slot, event) {
  const heap = engine.HEAPF32;
  const x = event.clientX * dpr;
  const y = event.clientY * dpr;
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
  heap[i] = (x - panX) / zoom;
  heap[i + 1] = (y - panY) / zoom;
  heap[i + 2] = pressure;
  heap[i + 3] = tilt;
  heap[i + 4] = azimuth;
  lastPressure = pressure;
  lastTilt = tilt;
}

function drawSamples(event, end) {
  const coalesced = event.getCoalescedEvents ? event.getCoalescedEvents() : null;
  const predicted = !end && event.getPredictedEvents ? event.getPredictedEvents() : null;
  let count = 0;
  if (coalesced && coalesced.length > 0) {
    const n = Math.min(coalesced.length, MAX_SAMPLES);
    for (let i = 0; i < n; i++) writeSample(samplesOffset, count++, coalesced[i]);
  } else {
    writeSample(samplesOffset, count++, event);
  }
  let predictedCount = 0;
  if (predicted) {
    const n = Math.min(predicted.length, MAX_SAMPLES);
    for (let i = 0; i < n; i++) writeSample(predictedOffset, predictedCount++, predicted[i]);
  }

  const start = performance.now();
  engine._cap_draw(count, predictedCount, end ? 1 : 0);
  const done = performance.now();

  strokeSamples += count;
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

function endStroke(event) {
  // The lift itself carries no pressure on most pens, so it only closes the stroke.
  engine._cap_draw(0, 0, 1);
  strokePointer = -1;
  if (strokeSamples > 0) {
    undoCount++;
    redoCount = 0;
    updateHistoryButtons();
  }
  queueCompose();  // Clears any predicted ink left past the real end.
}

// --- Pointer input -----------------------------------------------------------------------------

function capture(event) {
  try {
    canvas.setPointerCapture(event.pointerId);
  } catch {
    // Synthetic events from tests have no real pointer to capture.
  }
}

canvas.addEventListener("pointerdown", (event) => {
  lastPointerType = event.pointerType;
  if (event.pointerType === "touch") {
    if (strokePointer !== -1) return;  // Palm on the screen while the pen draws.
    capture(event);
    touches.set(event.pointerId, { x: event.clientX * dpr, y: event.clientY * dpr });
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
  // Pen barrel buttons and the eraser end report other buttons; only the tip draws.
  if (event.button !== 0 || strokePointer !== -1) return;
  capture(event);
  strokePointer = event.pointerId;
  const previousTool = tool;
  if (event.pointerType === "pen" && (event.buttons & 32)) tool = "eraser";  // Eraser end.
  beginStroke();
  tool = previousTool;
  drawSamples(event, false);
});

canvas.addEventListener("pointermove", (event) => {
  if (event.pointerId === strokePointer) {
    drawSamples(event, false);
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
  touch.x = event.clientX * dpr;
  touch.y = event.clientY * dpr;
  moveGesture();
});

function finishPointer(event, cancelled) {
  if (event.pointerId === strokePointer) {
    if (cancelled) {
      engine._cap_cancel_stroke();
      strokePointer = -1;
      queueCompose();
    } else {
      endStroke(event);
    }
    return;
  }
  if (event.pointerId === panPointer) {
    panPointer = -1;
    canvas.style.cursor = spaceHeld ? "grab" : "";
    return;
  }
  if (touches.delete(event.pointerId)) gesture = null;
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
    if (n === 2 && gesture.span > 0) {
      zoomAt(x, y, span / gesture.span);
    }
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

canvas.addEventListener("contextmenu", (event) => event.preventDefault());

// --- History -----------------------------------------------------------------------------------

function undo() {
  if (strokePointer !== -1) return;
  if (engine._cap_undo()) {
    undoCount = Math.max(0, undoCount - 1);
    redoCount++;
  }
  updateHistoryButtons();
  queueCompose();
}

function redo() {
  if (strokePointer !== -1) return;
  if (engine._cap_redo()) {
    redoCount = Math.max(0, redoCount - 1);
    undoCount++;
  }
  updateHistoryButtons();
  queueCompose();
}

function updateHistoryButtons() {
  undoButton.disabled = undoCount === 0;
  redoButton.disabled = redoCount === 0;
}

undoButton.addEventListener("click", undo);
redoButton.addEventListener("click", redo);

// --- Keyboard ----------------------------------------------------------------------------------

window.addEventListener("keydown", (event) => {
  const mod = event.ctrlKey || event.metaKey;
  const key = event.key.toLowerCase();
  if (mod && key === "z") {
    event.preventDefault();
    if (event.shiftKey) redo(); else undo();
  } else if (mod && key === "y") {
    event.preventDefault();
    redo();
  } else if (mod) {
    return;
  } else if (event.key === " ") {
    if (event.target === document.body || event.target === canvas) event.preventDefault();
    spaceHeld = true;
    if (panPointer === -1) canvas.style.cursor = "grab";
  } else if (event.key === "[" || event.key === "]") {
    const step = Math.max(1, Math.round(sizes[tool] * 0.1));
    setSize(sizes[tool] + (event.key === "]" ? step : -step));
  } else if (key === "b") {
    selectTool("ink");
  } else if (key === "p") {
    selectTool("pencil");
  } else if (key === "e") {
    selectTool("eraser");
  } else if (event.key === "0") {
    fitView();
  }
});

window.addEventListener("keyup", (event) => {
  if (event.key === " ") {
    spaceHeld = false;
    if (panPointer === -1) canvas.style.cursor = "";
  }
});

// --- Toolbar -----------------------------------------------------------------------------------

const toolButtons = document.querySelectorAll(".tool");
for (const button of toolButtons) {
  button.addEventListener("click", () => selectTool(button.dataset.tool));
}

function selectTool(name) {
  tool = name;
  for (const button of toolButtons) {
    button.setAttribute("aria-pressed", String(button.dataset.tool === name));
  }
  setSize(sizes[name]);
}

function setSize(value) {
  const size = Math.min(200, Math.max(1, Math.round(value)));
  sizes[tool] = size;
  sizeInput.value = String(size);
  sizeValue.value = String(size);
}

sizeInput.addEventListener("input", () => setSize(Number(sizeInput.value)));

const colorBox = document.getElementById("colors");
for (const hex of COLORS) {
  const button = document.createElement("button");
  button.className = "swatch";
  button.style.background = hex;
  button.title = hex;
  button.setAttribute("aria-label", `Color ${hex}`);
  button.setAttribute("aria-pressed", String(hex === COLORS[0]));
  button.addEventListener("click", () => {
    color = hexToRgb(hex);
    for (const other of colorBox.children) other.setAttribute("aria-pressed", String(other === button));
    if (tool === "eraser") selectTool("ink");
  });
  colorBox.appendChild(button);
}

const statsToggle = document.getElementById("stats-toggle");
statsToggle.addEventListener("click", () => setStats(!statsOn));

function setStats(on) {
  statsOn = on;
  statsBox.hidden = !on;
  statsToggle.setAttribute("aria-pressed", String(on));
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

// --- Start -------------------------------------------------------------------------------------

function hexToRgb(hex) {
  const value = parseInt(hex.slice(1), 16);
  return [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255];
}

function fail(text) {
  message.textContent = text;
  message.hidden = false;
  throw new Error(text);
}

window.addEventListener("resize", resize);
resize();
updateHistoryButtons();
setStats(statsOn);
// Exposed for checking the page from the console and from automated tests.
window.cappuccino = { engine, fitView, undo, redo };
