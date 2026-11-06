// The app's languages, as Translations.kt on Android. The page is written in English, and while
// another language is chosen, text is translated as it appears: whole lines from the shared table,
// and lines with numbers or names through the table's %1 marks. Names you gave, such as artwork
// and layer names, sit under translate="no" and stay as they are.

import { SPANISH } from "./translations.js";

export const LANGUAGES = [["en", "English"], ["es", "Español"]];
const KEY = "cappuccino.language";

function saved() {
  try {
    return JSON.parse(localStorage.getItem(KEY));
  } catch {
    return null;
  }
}

// The saved language, or the browser's when there isn't one.
export const language = (() => {
  const code = saved() || (navigator.language || "en").slice(0, 2);
  return LANGUAGES.some(([value]) => value === code) ? code : "en";
})();

export function saveLanguage(code) {
  try {
    localStorage.setItem(KEY, JSON.stringify(code));
  } catch {
    // Private windows may refuse; the language then lasts for this visit.
  }
}

// Lines only the web version shows, such as the ones about folders and browsers.
const WEB_SPANISH = {
  "This browser doesn't support WebGL 2.": "Este navegador no admite WebGL 2.",
  "Couldn't start the drawing engine.": "No se pudo iniciar el motor de dibujo.",
  "Coming to the web version soon.": "Pronto en la versión web.",
  "Your own brushes come to the web soon": "Tus propios pinceles llegan pronto a la web",
  "Make a selection to keep painting inside it, or to move part of a layer.": "Haz una selección para pintar solo dentro de ella o mover parte de una capa.",
  "Color picker": "Selector de color",
  "Flattened, saved to %1/Exports": "Aplanada, guardada en %1/Exports",
  "Flattened, saved to your downloads": "Aplanada, guardada en tus descargas",
  "Keeps your layers": "Conserva tus capas",
  "Saved a variation. This one stays as it was.": "Variación guardada. Esta queda como estaba.",
  "Saved to %1": "Guardado en %1",
  "your downloads": "tus descargas",
  "This browser can't record video": "Este navegador no puede grabar video",
  "Add images, or drop them here from another app.": "Agrega imágenes o suéltalas aquí desde otra app.",
  "Added to references": "Agregada a referencias",
  "Added %1 references": "%1 referencias agregadas",
  "Part of this artwork couldn't be read": "No se pudo leer parte de esta obra",
  "Couldn't open that artwork": "No se pudo abrir esa obra",
  "Your artworks are in “%1”.": "Tus obras están en “%1”.",
  "Let Cappuccino open that folder again to see them.": "Deja que Cappuccino vuelva a abrir esa carpeta para verlas.",
  "Start your first artwork with New artwork.": "Empieza tu primera obra con Nueva obra.",
  "Your artworks are saved in “%1”.": "Tus obras se guardan en “%1”.",
  "Your artworks are saved in this browser.": "Tus obras se guardan en este navegador.",
  "Save artworks in another folder": "Guardar obras en otra carpeta",
  "Save artworks in a folder": "Guardar obras en una carpeta",
  "Keep artworks in this browser instead": "Mejor guardar obras en este navegador",
  "Artworks now stay in this browser": "Ahora las obras quedan en este navegador",
  "Folders need Chrome or Edge": "Las carpetas necesitan Chrome o Edge",
  "Saving to “%1”": "Guardando en “%1”",
  "Couldn't open that folder": "No se pudo abrir esa carpeta",
  "Where should your artworks live?": "¿Dónde guardamos tus obras?",
  "Choose a folder on this computer, like Documents or a synced folder, and every artwork is saved there as you draw. Or keep them in this browser.":
    "Elige una carpeta de esta computadora, como Documentos o una carpeta sincronizada, y cada obra se guarda ahí mientras dibujas. O guárdalas en este navegador.",
  "Keep in this browser": "Guardar en este navegador",
  "Choose a folder": "Elegir una carpeta",
  "%1 x %2 pixels · %3 of %4 layers": "%1 x %2 píxeles · %3 de %4 capas",
  "1 minute ago": "hace 1 minuto",
  "%1 minutes ago": "hace %1 minutos",
  "1 hour ago": "hace 1 hora",
  "%1 hours ago": "hace %1 horas",
  "Yesterday": "Ayer",
  "%1 days ago": "hace %1 días",
};

const table = language === "es" ? { ...WEB_SPANISH, ...SPANISH } : null;

// Templates such as "%1 of your last %2 straight lines…" become patterns that capture the values.
const templates = table
  ? Object.entries(table).filter(([english]) => /%\d/.test(english)).map(([english, translated]) => {
    const marks = [];
    const pattern = english.split(/(%\d)/).map((part) => {
      if (/^%\d$/.test(part)) {
        marks.push(Number(part.slice(1)));
        return "(.+?)";
      }
      return part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    }).join("");
    return [new RegExp(`^${pattern}$`), marks, translated];
  })
  : [];

// Translates English source text. %1 and on are replaced by values, as tr() on Android.
export function tr(text, ...values) {
  let result = table?.[text] ?? text;
  values.forEach((value, index) => {
    result = result.replaceAll(`%${index + 1}`, String(value));
  });
  return result;
}

// Finds a translation for a finished line, filling a template when one matches. Notes joined from
// several sentences, as the companion writes them, are translated one sentence at a time.
function translateLine(text) {
  if (!table) return null;
  const whole = translateOne(text);
  if (whole !== null) return whole;
  const sentences = text.split(/(?<=[.!?])\s+/);
  if (sentences.length < 2) return null;
  let changed = false;
  const result = sentences.map((sentence) => {
    const next = translateOne(sentence);
    if (next === null) return sentence;
    changed = true;
    return next;
  }).join(" ");
  return changed ? result : null;
}

function translateOne(text) {
  const trimmed = text.trim();
  if (!trimmed) return null;
  const exact = table[trimmed];
  if (exact !== undefined) return text.replace(trimmed, exact);
  for (const [pattern, marks, translated] of templates) {
    const found = pattern.exec(trimmed);
    if (!found) continue;
    let result = translated;
    marks.forEach((mark, index) => {
      const value = found[index + 1];
      result = result.replaceAll(`%${mark}`, table[value] ?? value);
    });
    return text.replace(trimmed, result);
  }
  return null;
}

const ATTRIBUTES = ["aria-label", "title", "placeholder"];

// What each node or attribute was last set to here, so a translation isn't translated again.
const written = new WeakMap();

function keep(node) {
  const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
  return !element || element.closest('[translate="no"], script, style, textarea');
}

function translateNode(node) {
  if (node.nodeType === Node.TEXT_NODE) {
    if (keep(node) || written.get(node) === node.nodeValue) return;
    const next = translateLine(node.nodeValue);
    if (next !== null && next !== node.nodeValue) {
      written.set(node, next);
      node.nodeValue = next;
    }
    return;
  }
  if (node.nodeType !== Node.ELEMENT_NODE || keep(node)) return;
  for (const name of ATTRIBUTES) {
    const value = node.getAttribute(name);
    if (!value) continue;
    const done = written.get(node) || {};
    if (done[name] === value) continue;
    const next = translateLine(value);
    if (next !== null && next !== value) {
      done[name] = next;
      written.set(node, done);
      node.setAttribute(name, next);
    }
  }
  // aria-label set as a property shows up as the attribute, so both are covered above.
  for (const child of node.childNodes) translateNode(child);
}

// Keeps the page in the chosen language as it changes.
export function translatePage(root = document.body) {
  if (!table) return;
  document.documentElement.lang = language;
  translateNode(root);
  new MutationObserver((changes) => {
    for (const change of changes) {
      if (change.type === "childList") change.addedNodes.forEach(translateNode);
      else if (change.type === "characterData") translateNode(change.target);
      else if (change.type === "attributes") translateNode(change.target);
    }
  }).observe(root, { childList: true, characterData: true, subtree: true, attributes: true, attributeFilter: ATTRIBUTES });
}
