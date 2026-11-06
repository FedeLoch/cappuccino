// Reference images: the library rules from ReferenceLibrary.kt and the floating window from
// ReferenceWindow.kt. Positions and sizes are in interface units.

import { h, svg, say, toUi, viewWidth, viewHeight, choiceSheet, askSheet } from "./ui.js";
import { REFERENCE_BLOCK_PURPOSES } from "./storage.js";

// The companion compares with at most this many images, so saves stay quick.
export const COMPANION_REFERENCE_LIMIT = 40;
export const STARRED_FILTER = "*";
export const BOARD_PURPOSES = ["PALETTE", "VALUES", "COMPOSITION", "MOOD", "INSPIRATION"];
export const PURPOSE_LABELS = {
  PALETTE: "Palette", VALUES: "Values", COMPOSITION: "Composition", MOOD: "Mood", INSPIRATION: "Inspiration",
};
export const COMPARED_PURPOSES = BOARD_PURPOSES.filter((purpose) => purpose !== "INSPIRATION");
export const REFERENCE_SIZE = 2048;

export function referenceBoards(notes) {
  const boards = [...new Set(Object.values(notes).map((note) => note.board).filter(Boolean))];
  return boards.sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
}

// The ready-made blocks first, then the ones you named.
export function referenceBlocks(notes) {
  const ready = Object.keys(REFERENCE_BLOCK_PURPOSES);
  return [...ready, ...referenceBoards(notes).filter((board) => !ready.includes(board))];
}

// An empty filter keeps everything, STARRED_FILTER keeps stars, and others name a board.
export function filterReferences(names, notes, filter) {
  if (filter === "") return names;
  if (filter === STARRED_FILTER) return names.filter((name) => notes[name]?.starred);
  return names.filter((name) => notes[name]?.board === filter);
}

// Open references come first, then starred ones, then the newest of the library.
export function companionReferences(newestFirst, open, notes) {
  const opened = newestFirst.filter((name) => open.has(name));
  if (opened.length) return { names: opened, scope: "OPEN" };
  const starred = newestFirst.filter((name) => notes[name]?.starred);
  if (starred.length) return { names: starred.slice(0, COMPANION_REFERENCE_LIMIT), scope: "STARRED" };
  return { names: newestFirst.slice(0, COMPANION_REFERENCE_LIMIT), scope: "NEWEST" };
}

// Open references still answer everything. Otherwise each kind of note reads the boards made for
// it, and falls back on references that aren't in a purposed board. followed narrows the choice to
// the boards of one artwork; empty means all boards count.
export function aspectReferences(newestFirst, open, notes, purposes, followed) {
  const allowed = (board) => followed.length === 0 || followed.includes(board);
  const purposeful = Object.fromEntries(Object.entries(purposes).filter(([, uses]) => uses.length > 0));
  const inPurposedBoard = (name) => notes[name]?.board in purposeful;
  const inFollowedBoard = (name) => followed.length === 0 || followed.includes(notes[name]?.board);
  const general = companionReferences(newestFirst.filter((name) => !inPurposedBoard(name) && inFollowedBoard(name)), open, notes);
  if (newestFirst.some((name) => open.has(name))) {
    const opened = companionReferences(newestFirst, open, notes).names;
    const aspects = Object.fromEntries(COMPARED_PURPOSES.map((purpose) => [purpose, opened.map((_, i) => i)]));
    return { names: opened, aspects, boards: {}, scope: "OPEN" };
  }
  const chosen = {};
  const boards = {};
  for (const purpose of COMPARED_PURPOSES) {
    const named = Object.entries(purposeful).filter(([board, uses]) => uses.includes(purpose) && allowed(board)).map(([board]) => board);
    const fromBoards = newestFirst.filter((name) => named.includes(notes[name]?.board)).slice(0, COMPANION_REFERENCE_LIMIT);
    if (fromBoards.length) boards[purpose] = named.filter((board) => fromBoards.some((name) => notes[name]?.board === board)).sort();
    chosen[purpose] = fromBoards.length ? fromBoards : general.names;
  }
  const union = [...new Set(Object.values(chosen).flat())];
  const aspects = Object.fromEntries(Object.entries(chosen).map(([purpose, names]) => [purpose, names.map((name) => union.indexOf(name))]));
  return { names: union, aspects, boards, scope: Object.keys(boards).length ? "BOARDS" : general.scope };
}

// Decodes a reference no larger than size on its long side.
export async function decodeReference(file, size = REFERENCE_SIZE) {
  const image = await createImageBitmap(file);
  const shrink = Math.min(1, size / Math.max(image.width, image.height));
  if (shrink === 1) return image;
  const resized = await createImageBitmap(image, {
    resizeWidth: Math.max(1, Math.round(image.width * shrink)),
    resizeHeight: Math.max(1, Math.round(image.height * shrink)),
    resizeQuality: "high",
  });
  image.close();
  return resized;
}

// Picks a starting width that shows a whole reference, even a very tall one, on screen.
export function fitReferenceWidth(imageWidth, imageHeight, screenWidth, screenHeight) {
  return Math.floor(Math.min(screenWidth * 0.4, screenHeight * 0.7 * imageWidth / Math.max(imageHeight, 1)));
}

const clampPan = (pan, zoom, size) => {
  const limit = size * (zoom - 1) / 2;
  return Math.min(limit, Math.max(-limit, pan));
};
const clampPosition = (position, windowSize, screenSize, visible) =>
  Math.min(screenSize - visible, Math.max(visible - windowSize, position));

const MIN_WIDTH = 96;
const MAX_TOOL = 36;
const MIN_TOOL = 22;
const MAX_ZOOM = 12;
const VISIBLE_EDGE = 56;
const DRAWING_ALPHA = 0.35;
const LONG_PRESS_MS = 500;
const TAP_TRAVEL = 8;

// A floating image that can be moved, resized, zoomed, and checked while drawing.
// host: {changed(), closed(window), pick(color, clientX, clientY), pickEnd(), palette(window), toLayer(window)}
export class ReferenceWindow {
  constructor(parent, state, bitmap, host) {
    this.state = state;
    this.bitmap = bitmap;
    this.host = host;
    this.ratio = bitmap.height / bitmap.width;
    this.image = h("canvas", { className: "reference-image", ariaLabel: "Floating reference" });
    this.bar = this.createBar();
    this.bar.hidden = true;
    const handle = h("div", { className: "reference-resize", ariaLabel: "Resize reference" }, "◢");
    this.root = h("div", { className: "reference" }, this.image, handle, this.bar);
    this.root.style.opacity = String(state.opacity);
    parent.append(this.root);
    this.listenToImage();
    this.listenToHandle(handle);
    this.refit();
  }

  get height() {
    return Math.round(this.state.width * this.ratio);
  }

  dismiss() {
    this.root.remove();
  }

  setDrawing(drawing) {
    const { state } = this;
    this.root.style.opacity = String(drawing && !state.pinned ? Math.min(DRAWING_ALPHA, state.opacity) : state.opacity);
  }

  refit() {
    this.clampToScreen();
    this.applyBounds();
  }

  clampToScreen() {
    const { state } = this;
    state.x = Math.round(clampPosition(state.x, state.width, viewWidth(), VISIBLE_EDGE));
    state.y = Math.round(clampPosition(state.y, this.height, viewHeight(), VISIBLE_EDGE));
  }

  applyBounds() {
    const { state, root } = this;
    root.style.left = `${state.x}px`;
    root.style.top = `${state.y}px`;
    root.style.width = `${state.width}px`;
    root.style.height = `${this.height}px`;
    root.classList.toggle("pinned", state.pinned);
    this.fitToolbar();
    this.draw();
  }

  // Keeps all the tools in one row by shrinking them as the window gets smaller.
  fitToolbar() {
    const size = Math.min(MAX_TOOL, Math.max(MIN_TOOL, Math.floor((this.state.width - 10) / this.tools.length)));
    for (const button of this.tools) {
      button.style.width = `${size}px`;
      button.style.height = `${size}px`;
      button.style.padding = `${Math.floor(size / 5)}px`;
    }
  }

  matrix() {
    const { state, bitmap } = this;
    const width = this.state.width;
    const height = this.height;
    const scale = width / bitmap.width * state.zoom;
    let left = width / 2 - bitmap.width * scale / 2 + state.panX;
    const top = height / 2 - bitmap.height * scale / 2 + state.panY;
    if (state.flipped) left = width - left - bitmap.width * scale;
    return { scale, left, top };
  }

  draw() {
    const { image, bitmap, state } = this;
    const ratio = window.devicePixelRatio || 1;
    const zoom = parseFloat(document.documentElement.style.zoom) || 1;
    const width = Math.max(1, Math.round(state.width * ratio * zoom));
    const height = Math.max(1, Math.round(this.height * ratio * zoom));
    if (image.width !== width || image.height !== height) {
      image.width = width;
      image.height = height;
    }
    const c = image.getContext("2d");
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.clearRect(0, 0, width, height);
    const unit = width / state.width;
    const { scale, left, top } = this.matrix();
    c.filter = state.grayscale ? "grayscale(1)" : "none";
    c.imageSmoothingQuality = "high";
    if (state.flipped) {
      c.translate(width, 0);
      c.scale(-1, 1);
      const unflippedLeft = state.width - left - bitmap.width * scale;
      c.drawImage(bitmap, unflippedLeft * unit, top * unit, bitmap.width * scale * unit, bitmap.height * scale * unit);
    } else {
      c.drawImage(bitmap, left * unit, top * unit, bitmap.width * scale * unit, bitmap.height * scale * unit);
    }
  }

  limitPan() {
    const { state } = this;
    state.panX = clampPan(state.panX, state.zoom, state.width);
    state.panY = clampPan(state.panY, state.zoom, this.height);
    this.draw();
  }

  // The image's color under a point in the window, in interface units.
  pickColor(x, y) {
    const { bitmap, state } = this;
    const { scale, left, top } = this.matrix();
    let pixelX = (x - left) / scale;
    if (state.flipped) pixelX = bitmap.width - 1 - pixelX;
    const px = Math.min(bitmap.width - 1, Math.max(0, Math.floor(pixelX)));
    const py = Math.min(bitmap.height - 1, Math.max(0, Math.floor((y - top) / scale)));
    if (!this.sampler) {
      const surface = new OffscreenCanvas(bitmap.width, bitmap.height);
      const c = surface.getContext("2d", { willReadFrequently: true });
      c.drawImage(bitmap, 0, 0);
      this.sampler = c;
    }
    const [r, g, b] = this.sampler.getImageData(px, py, 1, 1).data;
    return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, "0").toUpperCase()}`;
  }

  createBar() {
    const { state } = this;
    const toggle = (button, on) => button.classList.toggle("on", on);
    const actions = [
      ["invert", "Grayscale", (button) => {
        state.grayscale = !state.grayscale;
        toggle(button, state.grayscale);
        this.draw();
        this.host.changed();
      }],
      ["flip", "Flip", (button) => {
        state.flipped = !state.flipped;
        toggle(button, state.flipped);
        this.draw();
        this.host.changed();
      }],
      ["fit", "Fit", () => {
        state.zoom = 1;
        state.panX = 0;
        state.panY = 0;
        this.draw();
        this.host.changed();
      }],
      ["pin", "Pin for tracing", () => this.pin()],
      ["palette", "Save its palette", () => this.host.palette(this)],
      ["layersfile", "Add as layer", () => this.host.toLayer(this)],
      ["close", "Close", () => this.host.closed(this)],
    ];
    this.tools = actions.map(([icon, name, run]) => {
      const button = h("button", { className: "icon", ariaLabel: name, title: name });
      button.append(svg(icon));
      button.addEventListener("click", () => run(button));
      if (icon === "invert") toggle(button, state.grayscale);
      if (icon === "flip") toggle(button, state.flipped);
      return button;
    });
    const opacity = h("input", {
      className: "range", type: "range", min: "15", max: "100", value: String(Math.round(state.opacity * 100)),
      ariaLabel: "Reference opacity",
      onInput: (event) => {
        state.opacity = Number(event.target.value) / 100;
        this.root.style.opacity = String(state.opacity);
        this.host.changed();
      },
    });
    const bar = h("div", { className: "reference-bar" }, h("div", { className: "reference-tools" }, this.tools), opacity);
    bar.addEventListener("pointerdown", (event) => event.stopPropagation());
    return bar;
  }

  pin() {
    this.state.pinned = true;
    this.bar.hidden = true;
    this.applyBounds();
    this.host.changed();
    say("Pinned. You can draw over it. Tap it in References to close it.", { long: true });
  }

  listenToImage() {
    const { image, state } = this;
    const pointers = new Map();
    let pinch = 0;
    let pickTimer = 0;
    let picking = false;
    let travel = 0;
    let last = null;
    const local = (event) => {
      const box = image.getBoundingClientRect();
      return [toUi(event.clientX - box.left), toUi(event.clientY - box.top)];
    };
    const spread = () => {
      const [a, b] = [...pointers.values()];
      return Math.hypot(a.x - b.x, a.y - b.y);
    };
    image.addEventListener("pointerdown", (event) => {
      event.stopPropagation();
      try {
        image.setPointerCapture(event.pointerId);
      } catch {
        // A pointer that already left can't be captured; moves still arrive while it's over the image.
      }
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      last = { x: event.clientX, y: event.clientY };
      travel = 0;
      if (pointers.size === 2) {
        clearTimeout(pickTimer);
        pinch = spread();
        return;
      }
      clearTimeout(pickTimer);
      pickTimer = setTimeout(() => {
        picking = true;
        const [x, y] = local(event);
        this.host.pick(this.pickColor(x, y), last.x, last.y);
      }, LONG_PRESS_MS);
    });
    image.addEventListener("pointermove", (event) => {
      if (!pointers.has(event.pointerId)) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (picking) {
        const [x, y] = local(event);
        this.host.pick(this.pickColor(x, y), event.clientX, event.clientY);
        return;
      }
      if (pointers.size === 2) {
        const next = spread();
        if (pinch > 0) {
          state.zoom = Math.min(MAX_ZOOM, Math.max(1, state.zoom * next / pinch));
          this.limitPan();
        }
        pinch = next;
        return;
      }
      const dx = toUi(event.clientX - last.x);
      const dy = toUi(event.clientY - last.y);
      last = { x: event.clientX, y: event.clientY };
      travel += Math.abs(dx) + Math.abs(dy);
      if (travel > TAP_TRAVEL) clearTimeout(pickTimer);
      if (state.zoom > 1.01) {
        state.panX += state.flipped ? -dx : dx;
        state.panY += dy;
        this.limitPan();
      } else {
        state.x += dx;
        state.y += dy;
        this.clampToScreen();
        this.root.style.left = `${state.x}px`;
        this.root.style.top = `${state.y}px`;
      }
    });
    const end = (event) => {
      if (!pointers.has(event.pointerId)) return;
      pointers.delete(event.pointerId);
      clearTimeout(pickTimer);
      if (picking) {
        if (pointers.size === 0) {
          picking = false;
          this.host.pickEnd();
        }
        return;
      }
      if (pointers.size === 1) {
        const [rest] = pointers.values();
        last = { ...rest };
        pinch = 0;
        return;
      }
      if (event.type === "pointerup" && travel <= TAP_TRAVEL) this.bar.hidden = !this.bar.hidden;
      this.host.changed();
    };
    image.addEventListener("pointerup", end);
    image.addEventListener("pointercancel", end);
    // A mouse wheel or trackpad pinch zooms, as two fingers do on the tablet.
    image.addEventListener("wheel", (event) => {
      event.preventDefault();
      event.stopPropagation();
      state.zoom = Math.min(MAX_ZOOM, Math.max(1, state.zoom * Math.exp(-event.deltaY * (event.ctrlKey ? 0.01 : 0.002))));
      this.limitPan();
      clearTimeout(this.wheelTimer);
      this.wheelTimer = setTimeout(() => this.host.changed(), 300);
    }, { passive: false });
  }

  listenToHandle(handle) {
    const { state } = this;
    let startX = 0;
    let startWidth = 0;
    handle.addEventListener("pointerdown", (event) => {
      event.stopPropagation();
      handle.setPointerCapture(event.pointerId);
      startX = event.clientX;
      startWidth = state.width;
    });
    handle.addEventListener("pointermove", (event) => {
      if (!handle.hasPointerCapture(event.pointerId)) return;
      state.width = Math.round(Math.min(viewWidth(), Math.max(MIN_WIDTH, startWidth + toUi(event.clientX - startX))));
      this.applyBounds();
      this.limitPan();
    });
    handle.addEventListener("pointerup", () => this.host.changed());
  }
}

// Groups pixels into the main colors of an image, most common first. Paper white is skipped.
// Same as extractPalette() in ColorTools.kt. pixels is RGBA bytes.
export function extractPalette(pixels, count = 6) {
  const samples = [];
  for (let i = 0, n = 0; i < pixels.length; i += 4, n++) {
    if (n % 3 !== 0) continue;
    const r = pixels[i];
    const g = pixels[i + 1];
    const b = pixels[i + 2];
    if (r < 245 || g < 245 || b < 245) samples.push([r, g, b]);
  }
  if (!samples.length) return [];
  const luminance = ([r, g, b]) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
  const spaced = [...samples].sort((a, b) => luminance(a) - luminance(b));
  const centers = Array.from({ length: count }, (_, index) => [...spaced[Math.floor(index * (spaced.length - 1) / Math.max(1, count - 1))]]);
  const sizes = new Array(count).fill(0);
  for (let round = 0; round < 8; round++) {
    const totals = Array.from({ length: count }, () => [0, 0, 0]);
    sizes.fill(0);
    for (const [r, g, b] of samples) {
      let best = 0;
      let bestDistance = Infinity;
      for (let index = 0; index < count; index++) {
        const [cr, cg, cb] = centers[index];
        const distance = (r - cr) ** 2 + (g - cg) ** 2 + (b - cb) ** 2;
        if (distance < bestDistance) {
          bestDistance = distance;
          best = index;
        }
      }
      sizes[best]++;
      totals[best][0] += r;
      totals[best][1] += g;
      totals[best][2] += b;
    }
    for (let index = 0; index < count; index++) {
      if (sizes[index]) centers[index] = totals[index].map((total) => total / sizes[index]);
    }
  }
  const colors = [...Array(count).keys()].filter((index) => sizes[index] > 0)
    .sort((a, b) => sizes[b] - sizes[a])
    .map((index) => `#${centers[index].map((v) => Math.floor(v).toString(16).padStart(2, "0")).join("").toUpperCase()}`);
  return [...new Set(colors)];
}

// --- Sheets shared by the home screen and the studio ----------------------------------------------

// Small previews as object URLs, kept while the page lives so lists open at once.
const previews = new Map();
export function referencePreview(projectId, reference) {
  const key = `${projectId}/${reference.name}:${reference.time}`;
  if (!previews.has(key)) previews.set(key, URL.createObjectURL(reference.file));
  return previews.get(key);
}

// The actions for one reference.
export async function referenceOptions(store, projectId, name, { changed, moved, remove }) {
  const note = (await store.referenceNotes(projectId))[name] || { starred: false, board: "" };
  choiceSheet("Reference", [
    { label: note.board ? "Change block" : "Move to a block", icon: "gallery", run: () => chooseBoard(store, projectId, [name], changed) },
    {
      label: note.starred ? "Remove star" : "Add star", icon: "pin",
      run: async () => {
        await store.updateReferenceNote(projectId, name, (current) => ({ ...current, starred: !current.starred }));
        changed();
      },
    },
    { label: "Move to another sketchbook", icon: "copy", run: () => chooseSketchbook(store, projectId, [name], moved) },
    { label: "Delete", icon: "trash", danger: true, run: remove },
  ]);
}

export async function chooseBoard(store, projectId, names, changed) {
  const assign = async (board) => {
    for (const name of names) await store.updateReferenceNote(projectId, name, (note) => ({ ...note, board }));
    changed();
  };
  const blocks = referenceBlocks(await store.referenceNotes(projectId));
  choiceSheet("Block", [
    ...blocks.map((block) => ({ label: block, run: () => assign(block) })),
    { label: "New block", run: () => askSheet("New block", "", assign) },
    { label: "Not in a block", run: () => assign("") },
  ]);
}

export async function chooseSketchbook(store, projectId, names, moved) {
  const targets = [...(await store.projects()).map((project) => [project.id, project.name]), ["", "Unsorted"]]
    .filter(([id]) => id !== projectId);
  if (!targets.length) return;
  choiceSheet("Move to", targets.map(([id, label]) => ({
    label,
    run: async () => {
      let count = 0;
      for (const name of names) if (await store.moveReference(name, projectId, id)) count++;
      if (count) moved();
    },
  })));
}
