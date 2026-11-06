// Small pieces shared by the home screen and the studio: a DOM builder, the companion's notes
// (Activity.say in ArtworkSheets.kt), and the cream sheets the home screen opens.

import ICONS from "./icons.js";

// --- Scale: the interface keeps the tablet's proportions -------------------------------------------
//
// Android lays out in dp, and a tablet screen is about 1000 by 680 dp. A computer window is much
// wider in CSS pixels, so at one pixel per dp everything would look small. The whole page is zoomed
// to fill the window the way the app fills a tablet, never below one to one and at most twice.
// Pointer positions and bounding boxes stay in real pixels; styles are in interface units.

const TABLET_WIDTH = 1000;
const TABLET_HEIGHT = 680;
let scale = 1;

export function applyUiScale() {
  scale = Math.min(2, Math.max(1, Math.min(window.innerWidth / TABLET_WIDTH, window.innerHeight / TABLET_HEIGHT)));
  document.documentElement.style.zoom = String(scale);
  document.documentElement.style.setProperty("--ui", String(scale));
  return scale;
}

export const uiScale = () => scale;
// Turns real pixels, such as a pointer's clientX, into interface units for styles.
export const toUi = (value) => value / scale;
export const viewWidth = () => window.innerWidth / scale;
export const viewHeight = () => window.innerHeight / scale;
// Backing pixels per interface unit, so small canvases stay sharp when zoomed.
export const pixelRatio = () => (window.devicePixelRatio || 1) * scale;
import { Companion } from "./companion.js";

// h("div", {className: "row", onClick}, child, "text")
export function h(tag, props = {}, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(props)) {
    if (value === undefined) continue;
    if (name.startsWith("on")) element.addEventListener(name.slice(2).toLowerCase(), value);
    else if (name === "style") Object.assign(element.style, value);
    else if (name in element) element[name] = value;
    else element.setAttribute(name, value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return element;
}

export function svg(name, color) {
  const holder = document.createElement("span");
  holder.innerHTML = color ? ICONS[name].replaceAll("#F1EFE8", color) : ICONS[name];
  return holder.firstElementChild;
}

// --- Notes from the companion ------------------------------------------------------------------

let noteTimer = 0;
let noteElement = null;

// A small dark note at the top of the screen, with the companion beside it.
export function say(message, { long = false, look, color } = {}) {
  if (!look) {
    try {
      look = JSON.parse(localStorage.getItem("cappuccino.mascot")) || "COFFEE_DROP";
    } catch {
      look = "COFFEE_DROP";
    }
  }
  noteElement?.remove();
  const face = h("canvas", { className: "note-face" });
  const note = h("div", { className: "note", role: "status" }, face, h("span", {}, message));
  document.body.append(note);
  const companion = new Companion(face, { look, blink: false });
  if (color) companion.setColor(color);
  noteElement = note;
  requestAnimationFrame(() => note.classList.add("shown"));
  clearTimeout(noteTimer);
  noteTimer = setTimeout(() => {
    note.classList.remove("shown");
    setTimeout(() => note.remove(), 160);
  }, long ? 3800 : 2200);
}

// --- Sheets ------------------------------------------------------------------------------------

// A cream card floating over the page. Returns a function that closes it.
export function sheet(content, { small = false } = {}) {
  const card = h("div", { className: small ? "sheet small" : "sheet", role: "dialog", ariaModal: "true" }, content);
  const shade = h("div", { className: "shade" }, card);
  const close = () => {
    shade.remove();
    document.removeEventListener("keydown", onKey);
  };
  const onKey = (event) => {
    if (event.key === "Escape") close();
  };
  shade.addEventListener("pointerdown", (event) => {
    if (event.target === shade) close();
  });
  document.addEventListener("keydown", onKey);
  document.body.append(shade);
  return close;
}

export function creamChip(text, selected, click) {
  return h("button", { className: selected ? "chip-cream on" : "chip-cream", onClick: click }, text);
}

export function accentChip(text, click) {
  return h("button", { className: "chip-cream on", onClick: click }, text);
}

export function serif(text, size) {
  return h("div", { className: "serif", style: { fontSize: `${size}px` } }, text);
}

function smallSheetBody(title, close) {
  return h("div", { className: "sheet-body small-body" },
    h("div", { className: "row" }, h("div", { className: "grow" }, serif(title, 22)), creamChip("Cancel", false, () => close())));
}

export function sheetButton(text, click) {
  return h("button", { className: "sheet-button", onClick: click }, text);
}

// A short list of choices. Each choice is {label, icon, danger, run}; note is a line under the title.
export function choiceSheet(title, choices, note) {
  let close;
  const body = smallSheetBody(title, () => close());
  if (note) body.append(h("div", { className: "sheet-note" }, note));
  body.append(h("div", { style: { height: "8px" } }));
  for (const choice of choices) {
    body.append(h("button", {
      className: choice.danger ? "sheet-row danger" : "sheet-row",
      onClick: () => {
        close();
        choice.run();
      },
    }, choice.icon && svg(choice.icon, choice.danger ? "#D85A30" : "#6B4226"), h("span", {}, choice.label)));
  }
  close = sheet(body, { small: true });
}

// Asks for one short piece of text.
export function askSheet(title, current, done) {
  let close;
  const field = h("input", { className: "field", value: current });
  const save = () => {
    const text = field.value.trim();
    if (!text) return;
    close();
    done(text);
  };
  field.addEventListener("keydown", (event) => {
    if (event.key === "Enter") save();
  });
  const body = smallSheetBody(title, () => close());
  body.append(h("div", { className: "gap14" }, field), h("div", { className: "end gap16" }, sheetButton("Save", save)));
  close = sheet(body, { small: true });
  field.focus();
  field.select();
}

// Double checks before something that can't be taken back.
export function confirmSheet(title, message, action, label = "Delete") {
  let close;
  const body = h("div", { className: "sheet-body confirm-body" },
    serif(title, 22),
    message && h("div", { className: "sheet-message" }, message),
    h("div", { className: "end gap18" },
      creamChip("Keep", false, () => close()),
      sheetButton(label, () => {
        close();
        action();
      })));
  close = sheet(body, { small: true });
}

// "7 hours ago", like DateUtils.getRelativeTimeSpanString on Android.
export function relativeTime(time) {
  const minutes = Math.max(0, Math.floor((Date.now() - time) / 60000));
  if (minutes < 60) return minutes === 1 ? "1 minute ago" : `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return hours === 1 ? "1 hour ago" : `${hours} hours ago`;
  const days = Math.floor(hours / 24);
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;
  const date = new Date(time);
  const sameYear = date.getFullYear() === new Date().getFullYear();
  return date.toLocaleDateString("en-US", sameYear ? { month: "short", day: "numeric" } : { month: "short", day: "numeric", year: "numeric" });
}
