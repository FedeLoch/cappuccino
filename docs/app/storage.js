// Keeps artworks in the same layout and formats as ArtworkStore.kt and TileStore.kt on Android:
//
//   projects.json
//   artworks/<id>/manifest.json, manifest.prev.json, thumbnail.png, preview.jpg, pack-<n>.bin
//
// Layer pixels are 256 px tiles of RGBA, each compressed with zlib like java.util.zip.Deflater,
// packed one after another into pack files that the manifest points into. So a folder written
// here opens on Android after a backup restore, and an unzipped Android backup opens here.
//
// The files live either in a folder you choose (the File System Access API, in Chrome and Edge)
// or in this browser's private storage (the origin private file system, everywhere else).

import { readZip, writeZip } from "./zip.js";

export const TILE_SIZE = 256;
export const THUMBNAIL_WIDTH = 480;
export const PREVIEW_WIDTH = 1600;
const MANIFEST = "manifest.json";
const PREVIOUS_MANIFEST = "manifest.prev.json";
const THUMBNAIL = "thumbnail.png";
const PREVIEW = "preview.jpg";
const REFERENCE_NOTES = "library.json";
const BOARD_PURPOSES = "boards.json";

// Ready-made reference blocks, each already telling the companion what to read in it.
export const REFERENCE_BLOCK_PURPOSES = {
  "Color palettes": ["PALETTE"],
  "Lights": ["VALUES"],
  "Composition": ["COMPOSITION"],
  "Mood": ["MOOD"],
  "Character design": ["PALETTE", "VALUES"],
  "Environment": ["PALETTE", "VALUES", "COMPOSITION", "MOOD"],
  "Perspective": ["INSPIRATION"],
  "Inspiration": ["INSPIRATION"],
};

// Android keeps colors as signed ARGB integers.
const colorToInt = (hex) => (0xFF000000 | parseInt(hex.slice(1), 16)) | 0;
const colorFromInt = (value) => `#${((value >>> 0) & 0xFFFFFF).toString(16).padStart(6, "0").toUpperCase()}`;

export const canChooseFolder = typeof window.showDirectoryPicker === "function";

export function thumbnailHeight(width, height) {
  return Math.max(1, Math.floor(THUMBNAIL_WIDTH * height / width));
}

export function previewHeight(width, height) {
  return Math.max(1, Math.floor(PREVIEW_WIDTH * height / width));
}

export function variationLetter(index) {
  return index < 26 ? String.fromCharCode(65 + index) : variationLetter(Math.floor(index / 26) - 1) + String.fromCharCode(65 + index % 26);
}

// --- Remembering the chosen folder ----------------------------------------------------------

function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("cappuccino", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("settings");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function setting(key, value) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const store = db.transaction("settings", value === undefined ? "readonly" : "readwrite").objectStore("settings");
    const request = value === undefined ? store.get(key) : value === null ? store.delete(key) : store.put(value, key);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

// --- Compression, the same zlib streams as Java's Deflater and Inflater ------------------------

async function deflate(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream("deflate"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function inflate(blob) {
  const stream = blob.stream().pipeThrough(new DecompressionStream("deflate"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

// --- Small file helpers ------------------------------------------------------------------------

async function directory(parent, name, create = true) {
  return parent.getDirectoryHandle(name, { create });
}

async function readText(dir, name) {
  try {
    return await (await (await dir.getFileHandle(name)).getFile()).text();
  } catch {
    return null;
  }
}

async function readFile(dir, name) {
  try {
    return await (await dir.getFileHandle(name)).getFile();
  } catch {
    return null;
  }
}

// createWritable() writes to a side file and swaps it in on close, so a half-written file never
// replaces a good one, like writeAtomically() on Android.
async function writeFile(dir, name, data) {
  const handle = await dir.getFileHandle(name, { create: true });
  const writable = await handle.createWritable();
  await writable.write(data);
  await writable.close();
}

async function remove(dir, name, recursive = false) {
  try {
    await dir.removeEntry(name, { recursive });
  } catch {
    // Already gone.
  }
}

async function copyDirectory(from, to) {
  for await (const [name, handle] of from.entries()) {
    if (handle.kind === "directory") {
      await copyDirectory(handle, await directory(to, name));
    } else {
      await writeFile(to, name, await handle.getFile());
    }
  }
}

// --- The store ---------------------------------------------------------------------------------

export class ArtworkStore {
  constructor(root, folderName) {
    this.root = root;
    this.folderName = folderName;  // Null while artworks stay in the browser.
  }

  // Opens the folder chosen before, if the browser still lets us. Otherwise the browser's own
  // storage, and the folder waits for a click to ask again.
  static async open() {
    const handle = await setting("folder").catch(() => null);
    if (handle && (await handle.queryPermission({ mode: "readwrite" })) === "granted") {
      return { store: new ArtworkStore(handle, handle.name), waiting: null };
    }
    return { store: await ArtworkStore.browser(), waiting: handle || null };
  }

  static async browser() {
    const root = await directory(await navigator.storage.getDirectory(), "cappuccino");
    navigator.storage.persist?.().catch(() => {});
    return new ArtworkStore(root, null);
  }

  // Asks again for a folder chosen on an earlier visit. Needs a click.
  static async reopen(handle) {
    if ((await handle.requestPermission({ mode: "readwrite" })) !== "granted") return null;
    return new ArtworkStore(handle, handle.name);
  }

  // Lets you pick a folder, and copies in anything kept in the browser so far. Needs a click.
  static async choose(current) {
    const handle = await window.showDirectoryPicker({ id: "cappuccino", mode: "readwrite", startIn: "documents" });
    if ((await handle.requestPermission({ mode: "readwrite" })) !== "granted") return null;
    const store = new ArtworkStore(handle, handle.name);
    if (current && current.root !== handle && !(await current.root.isSameEntry(handle))) {
      await store.merge(current);
    }
    await setting("folder", handle);
    return store;
  }

  static async forget() {
    await setting("folder", null);
  }

  // Adds another store's artworks and sketchbooks, keeping anything already here, the way
  // ArtworkStore.restore() combines a backup on Android.
  async merge(other) {
    const theirs = await other.artworksDir();
    const ours = await this.artworksDir();
    for await (const [name, handle] of theirs.entries()) {
      if (handle.kind !== "directory") continue;
      try {
        await ours.getDirectoryHandle(name);
      } catch {
        await copyDirectory(handle, await directory(ours, name));
      }
    }
    // References: each sketchbook's folder keeps anything already here, file by file.
    const theirReferences = await directory(other.root, "references");
    const ourReferences = await directory(this.root, "references");
    for await (const [name, handle] of theirReferences.entries()) {
      if (handle.kind !== "directory") continue;
      const target = await directory(ourReferences, name);
      for await (const [file, entry] of handle.entries()) {
        if (entry.kind !== "file") continue;
        if (!(await readFile(target, file))) await writeFile(target, file, await entry.getFile());
      }
    }
    const existing = await this.projects();
    const added = (await other.projects()).filter((project) => !existing.some((p) => p.id === project.id));
    if (added.length) await this.writeProjects([...existing, ...added]);
    const palettes = await this.palettes();
    const addedPalettes = (await other.palettes()).filter((palette) => !palettes.some((p) => p.id === palette.id));
    if (addedPalettes.length) await this.writePalettes([...palettes, ...addedPalettes]);
  }

  // --- Fonts you imported for text layers, in fonts/ as on Android -----------------------------

  async fonts() {
    const dir = await directory(this.root, "fonts");
    const list = [];
    for await (const [name, handle] of dir.entries()) {
      if (handle.kind === "file" && /\.(ttf|otf)$/i.test(name)) list.push([name, await (await handle.getFile()).arrayBuffer()]);
    }
    return list;
  }

  async saveFont(name, data) {
    await writeFile(await directory(this.root, "fonts"), name, new Blob([data]));
  }

  // --- Backup files, the same zip ArtworkStore.backup() writes on Android ------------------------

  // Packs all artworks, references, sketchbooks, palettes, and brushes into one zip file.
  async backup() {
    const entries = [];
    const walk = async (dir, path) => {
      for await (const [name, handle] of dir.entries()) {
        if (name.endsWith(".tmp") || name.endsWith(".crswap")) continue;
        if (handle.kind === "directory") await walk(handle, `${path}${name}/`);
        else entries.push([`${path}${name}`, await handle.getFile()]);
      }
    };
    for (const folder of ["artworks", "references", "fonts"]) await walk(await directory(this.root, folder), `${folder}/`);
    for (const name of ["projects.json", "palettes.json", "brushes.json"]) {
      const file = await readFile(this.root, name);
      if (file) entries.push([name, file]);
    }
    return writeZip(entries);
  }

  // Adds a backup's contents. Existing files are kept, and sketchbooks and palettes are combined.
  async restore(blob) {
    const entries = await readZip(blob);
    const json = (name) => {
      const entry = entries.find(([path]) => path === name);
      try {
        return entry ? JSON.parse(new TextDecoder().decode(entry[1])) : [];
      } catch {
        return [];
      }
    };
    for (const [path, bytes] of entries) {
      const parts = path.split("/");
      const fonts = parts[0] === "fonts" && parts.length === 2;
      if (!fonts && (!["artworks", "references"].includes(parts[0]) || parts.length < 3)) continue;
      if (parts.some((part) => part === "" || part === "." || part === "..")) throw new Error("Invalid backup");
      let dir = this.root;
      for (const part of parts.slice(0, -1)) dir = await directory(dir, part);
      if (!(await readFile(dir, parts[parts.length - 1]))) await writeFile(dir, parts[parts.length - 1], bytes);
    }
    const existing = await this.projects();
    const added = json("projects.json").filter((project) => !existing.some((p) => p.id === project.id))
      .map((item) => ({ id: item.id, name: item.name, color: item.color ?? 0 }));
    await this.writeProjects([...existing, ...added]);
    const palettes = await this.palettes();
    const addedPalettes = json("palettes.json").filter((palette) => !palettes.some((p) => p.id === palette.id))
      .map((item) => ({ id: item.id, name: item.name, colors: item.colors.map(colorFromInt) }));
    await this.writePalettes([...palettes, ...addedPalettes]);
  }

  // --- Your own brushes, in brushes.json with each tip in brush-<id>.tip, as on Android ----------

  async customBrushes() {
    const text = await readText(this.root, "brushes.json");
    let items = [];
    try {
      items = text ? JSON.parse(text) : [];
    } catch {
      return [];
    }
    const brushes = [];
    for (const item of items) {
      const tipFile = await readFile(this.root, `brush-${item.id}.tip`);
      brushes.push({
        id: item.id, label: item.label, minScale: item.minScale, spacing: item.spacing, flow: item.flow,
        pressureFlow: item.pressureFlow, hardness: item.hardness, grain: item.grain, size: item.size,
        eraser: false, mode: "paint", custom: true,
        tip: tipFile ? new Uint8Array(await tipFile.arrayBuffer()) : null,
        scatter: item.scatter ?? 0, sizeJitter: item.sizeJitter ?? 0, angleJitter: item.angleJitter ?? 0,
        shadeJitter: item.shadeJitter ?? 0, hueJitter: item.hueJitter ?? 0, strokeJitter: item.strokeJitter ?? 0,
      });
    }
    return brushes;
  }

  async writeBrushes(brushes) {
    const fields = ["id", "label", "minScale", "spacing", "flow", "pressureFlow", "hardness", "grain", "size",
      "scatter", "sizeJitter", "angleJitter", "shadeJitter", "hueJitter", "strokeJitter"];
    await writeFile(this.root, "brushes.json", JSON.stringify(brushes.map((brush) =>
      Object.fromEntries(fields.map((field) => [field, brush[field]])))));
  }

  async saveBrush(brush) {
    await this.writeBrushes([...(await this.customBrushes()).filter((other) => other.id !== brush.id), brush]);
    if (brush.tip) await writeFile(this.root, `brush-${brush.id}.tip`, brush.tip);
  }

  async deleteBrush(id) {
    await this.writeBrushes((await this.customBrushes()).filter((brush) => brush.id !== id));
    await remove(this.root, `brush-${id}.tip`);
  }

  // --- Palettes, in palettes.json with colors as ARGB integers like Android ----------------------

  async palettes() {
    const text = await readText(this.root, "palettes.json");
    if (!text) return [];
    try {
      return JSON.parse(text).map((item) => ({ id: item.id, name: item.name, colors: item.colors.map(colorFromInt) }));
    } catch {
      return [];
    }
  }

  writePalettes(palettes) {
    return writeFile(this.root, "palettes.json", JSON.stringify(palettes.map((palette) => ({
      id: palette.id, name: palette.name, colors: palette.colors.map(colorToInt),
    }))));
  }

  async addPalette(name, colors) {
    await this.writePalettes([...(await this.palettes()), { id: crypto.randomUUID(), name, colors }]);
  }

  async deletePalette(id) {
    await this.writePalettes((await this.palettes()).filter((palette) => palette.id !== id));
  }

  // --- References, in references/<sketchbook or "unsorted">/ as ReferenceLibrary.kt keeps them ---

  async referenceDir(projectId) {
    return directory(await directory(this.root, "references"), projectId || "unsorted");
  }

  // Each image file with its time, newest first.
  async references(projectId) {
    const dir = await this.referenceDir(projectId);
    const files = [];
    for await (const [name, handle] of dir.entries()) {
      if (handle.kind !== "file" || name === REFERENCE_NOTES || name === BOARD_PURPOSES || name.endsWith(".tmp")) continue;
      const file = await handle.getFile();
      files.push({ name, file, time: file.lastModified });
    }
    return files.sort((a, b) => b.time - a.time);
  }

  async referenceFile(projectId, name) {
    return readFile(await this.referenceDir(projectId), name);
  }

  async addReference(projectId, file) {
    const name = crypto.randomUUID();
    await writeFile(await this.referenceDir(projectId), name, file);
    return name;
  }

  async referenceNotes(projectId) {
    const text = await readText(await this.referenceDir(projectId), REFERENCE_NOTES);
    try {
      const items = text ? JSON.parse(text) : {};
      const notes = {};
      for (const [name, item] of Object.entries(items)) notes[name] = { starred: !!item.starred, board: item.board || "" };
      return notes;
    } catch {
      return {};
    }
  }

  async writeReferenceNotes(projectId, notes) {
    const items = {};
    for (const [name, note] of Object.entries(notes)) {
      if (note.starred || note.board) items[name] = { starred: !!note.starred, board: note.board || "" };
    }
    await writeFile(await this.referenceDir(projectId), REFERENCE_NOTES, JSON.stringify(items));
  }

  async updateReferenceNote(projectId, name, change) {
    const notes = await this.referenceNotes(projectId);
    notes[name] = change(notes[name] || { starred: false, board: "" });
    await this.writeReferenceNotes(projectId, notes);
  }

  async deleteReference(projectId, name) {
    await remove(await this.referenceDir(projectId), name);
    const notes = await this.referenceNotes(projectId);
    delete notes[name];
    await this.writeReferenceNotes(projectId, notes);
  }

  // Moves a reference and its star to another sketchbook. Its board stays behind.
  async moveReference(name, fromProject, toProject) {
    const from = await this.referenceDir(fromProject);
    const file = await readFile(from, name);
    if (!file) return false;
    await writeFile(await this.referenceDir(toProject), name, file);
    await remove(from, name);
    const notes = await this.referenceNotes(fromProject);
    const note = notes[name] || { starred: false, board: "" };
    delete notes[name];
    await this.writeReferenceNotes(fromProject, notes);
    await this.updateReferenceNote(toProject, name, () => ({ ...note, board: "" }));
    return true;
  }

  // Boards and what the companion reads them for: the ready-made blocks' own purposes, unless changed.
  async boardPurposes(projectId) {
    return { ...REFERENCE_BLOCK_PURPOSES, ...(await this.savedBoardPurposes(projectId)) };
  }

  async savedBoardPurposes(projectId) {
    const text = await readText(await this.referenceDir(projectId), BOARD_PURPOSES);
    try {
      return text ? JSON.parse(text) : {};
    } catch {
      return {};
    }
  }

  async setBoardPurposes(projectId, board, uses) {
    const saved = await this.savedBoardPurposes(projectId);
    saved[board] = uses;
    await writeFile(await this.referenceDir(projectId), BOARD_PURPOSES, JSON.stringify(saved));
  }

  artworksDir() {
    return directory(this.root, "artworks");
  }

  async artworkDir(id, create = false) {
    return directory(await this.artworksDir(), id, create);
  }

  // --- Sketchbooks ---------------------------------------------------------------------------

  async projects() {
    const text = await readText(this.root, "projects.json");
    if (!text) return [];
    try {
      return JSON.parse(text).map((item) => ({ id: item.id, name: item.name, color: item.color ?? 0 }));
    } catch {
      return [];
    }
  }

  writeProjects(projects) {
    return writeFile(this.root, "projects.json", JSON.stringify(projects));
  }

  async createProject(name, color) {
    const project = { id: crypto.randomUUID(), name, color };
    await this.writeProjects([...(await this.projects()), project]);
    return project;
  }

  async renameProject(id, name) {
    await this.writeProjects((await this.projects()).map((p) => (p.id === id ? { ...p, name } : p)));
  }

  async recolorProject(id, color) {
    await this.writeProjects((await this.projects()).map((p) => (p.id === id ? { ...p, color } : p)));
  }

  async deleteProject(id) {
    for (const art of await this.artworks()) {
      if (art.projectId === id) await this.moveArtwork(art.id, "");
    }
    await this.writeProjects((await this.projects()).filter((p) => p.id !== id));
    await remove(await directory(this.root, "references"), id, true);
  }

  // --- Artworks ------------------------------------------------------------------------------

  async readManifest(dir) {
    for (const name of [MANIFEST, PREVIOUS_MANIFEST]) {
      const text = await readText(dir, name);
      if (!text) continue;
      try {
        return JSON.parse(text);
      } catch {
        // Try the previous one.
      }
    }
    return null;
  }

  async artworks() {
    const list = [];
    for await (const [id, dir] of (await this.artworksDir()).entries()) {
      if (dir.kind !== "directory") continue;
      const manifest = await this.readManifest(dir);
      if (!manifest) continue;
      list.push({
        id,
        name: manifest.name || "Untitled",
        projectId: manifest.project || "",
        updated: manifest.updated || 0,
        variationOf: manifest.variationOf || "",
        width: manifest.width || 4096,
        height: manifest.height || 2732,
        layers: (manifest.layers || []).length,
      });
    }
    return list.sort((a, b) => b.updated - a.updated);
  }

  async imageUrl(id, preview = false) {
    const dir = await this.artworkDir(id).catch(() => null);
    if (!dir) return null;
    const file = (preview && (await readFile(dir, PREVIEW))) || (await readFile(dir, THUMBNAIL));
    return file ? URL.createObjectURL(file) : null;
  }

  async updateManifest(id, change) {
    const dir = await this.artworkDir(id);
    const manifest = await this.readManifest(dir);
    if (!manifest) return;
    change(manifest);
    await writeFile(dir, MANIFEST, JSON.stringify(manifest));
  }

  renameArtwork(id, name) {
    return this.updateManifest(id, (m) => { m.name = name; });
  }

  moveArtwork(id, projectId) {
    return this.updateManifest(id, (m) => { m.project = projectId; });
  }

  async deleteArtwork(id) {
    await remove(await this.artworksDir(), id, true);
  }

  async duplicateArtwork(id) {
    const copy = crypto.randomUUID();
    await copyDirectory(await this.artworkDir(id), await this.artworkDir(copy, true));
    await this.updateManifest(copy, (m) => {
      m.name = `${m.name || "Untitled"} copy`;
      m.updated = Date.now();
    });
  }

  async variations(id) {
    const all = await this.artworks();
    const art = all.find((a) => a.id === id);
    const root = (art && art.variationOf) || id;
    return all.filter((a) => a.id === root || a.variationOf === root)
      .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  }

  async makeVariation(id) {
    const family = await this.variations(id);
    const root = family.find((a) => !a.variationOf) || family[0];
    const copy = crypto.randomUUID();
    await copyDirectory(await this.artworkDir(id), await this.artworkDir(copy, true));
    await this.updateManifest(copy, (m) => {
      m.name = `${root.name} ${variationLetter(family.length)}`;
      m.variationOf = root.id;
      m.updated = Date.now();
    });
    return copy;
  }

  // --- Earlier versions, as ArtworkStore's versions/<id>/<time> on Android -------------------

  // Keeps a copy of the artwork as it's saved, at most one an hour and only the newest twelve.
  async saveVersionIfDue(id) {
    const folder = await directory(await directory(this.root, "versions"), id);
    const times = [];
    for await (const [name, handle] of folder.entries()) if (handle.kind === "directory" && /^\d+$/.test(name)) times.push(Number(name));
    const now = Date.now();
    if (times.length && now - Math.max(...times) < 60 * 60 * 1000) return;
    await copyDirectory(await this.artworkDir(id), await directory(folder, String(now)));
    for (const old of times.sort((a, b) => b - a).slice(11)) await remove(folder, String(old), true);
  }

  // Newest first, as [time, picture file or null].
  async versions(id) {
    const list = [];
    let folder;
    try {
      folder = await directory(await directory(this.root, "versions"), id, false);
    } catch {
      return list;
    }
    for await (const [name, handle] of folder.entries()) {
      if (handle.kind !== "directory" || !/^\d+$/.test(name)) continue;
      list.push([Number(name), (await readFile(handle, "preview.jpg")) || (await readFile(handle, "thumbnail.png"))]);
    }
    return list.sort((a, b) => b[0] - a[0]);
  }

  // Opens an earlier version as a new variation, so nothing is overwritten.
  async restoreVersion(id, time, label) {
    const folder = await directory(await directory(await directory(this.root, "versions"), id), String(time), false);
    const family = await this.variations(id);
    const root = family.find((a) => !a.variationOf) || family[0];
    const copy = crypto.randomUUID();
    await copyDirectory(folder, await this.artworkDir(copy, true));
    await this.updateManifest(copy, (m) => {
      m.name = `${m.name || "Untitled"} · ${label}`;
      m.variationOf = root?.id || id;
      m.updated = Date.now();
    });
    return copy;
  }

  // Saves layers at a new size as a new variation, as ArtworkStore.saveResized() does.
  async saveResized(source, name, width, height, regions, thumbnail) {
    const family = await this.variations(source.id);
    const root = family.find((a) => !a.variationOf) || family[0];
    const doc = await this.create(name, source.manifest.project, width, height);
    const keep = structuredClone(source.manifest);
    doc.manifest = {
      ...keep, name, width, height, variationOf: root?.id || source.id, updated: Date.now(), packs: [], journalSequence: 0,
      layers: keep.layers.map((layer) => ({ ...layer, file: "", tiles: null, maskFile: "", maskTiles: null })),
    };
    delete doc.manifest.print;
    await this.save(doc, regions, thumbnail, null);
    return doc.id;
  }

  // --- Time-lapse frames, as timelapse/000000.jpg on Android --------------------------------

  async addTimelapseFrame(doc, pixels, width, height) {
    const dir = await directory(doc.dir, "timelapse");
    let count = 0;
    for await (const _ of dir.keys()) count++;
    const surface = new OffscreenCanvas(width, height);
    surface.getContext("2d").putImageData(new ImageData(new Uint8ClampedArray(pixels), width, height), 0, 0);
    const blob = await surface.convertToBlob({ type: "image/jpeg", quality: 0.85 });
    await writeFile(dir, `${String(count).padStart(6, "0")}.jpg`, blob);
  }

  async timelapseFrames(doc) {
    const dir = await directory(doc.dir, "timelapse");
    const files = [];
    for await (const [name, handle] of dir.entries()) if (handle.kind === "file") files.push([name, handle]);
    files.sort((a, b) => a[0].localeCompare(b[0]));
    return Promise.all(files.map(([, handle]) => handle.getFile()));
  }

  // --- What the companion remembers, as snapshot.json and first-snapshot.json --------------------

  async snapshot(doc) {
    const text = await readText(doc.dir, "snapshot.json");
    return text ? JSON.parse(text) : null;
  }

  async saveSnapshot(doc, snapshot) {
    await writeFile(doc.dir, "snapshot.json", JSON.stringify(snapshot));
  }

  async firstSnapshot(doc) {
    const text = await readText(doc.dir, "first-snapshot.json");
    return text ? JSON.parse(text) : null;
  }

  async saveFirstSnapshot(doc, snapshot) {
    if (await readFile(doc.dir, "first-snapshot.json")) return;
    await writeFile(doc.dir, "first-snapshot.json", JSON.stringify(snapshot));
  }

  // --- Loading and saving pixels ------------------------------------------------------------

  async load(id) {
    const dir = await this.artworkDir(id);
    const manifest = await this.readManifest(dir);
    if (!manifest) return null;
    return { id, dir, manifest, width: manifest.width || 4096, height: manifest.height || 2732 };
  }

  // Rebuilds one layer into pixels, a whole canvas of RGBA. Anything without a tile stays clear.
  async readLayer(doc, tiles, file, pixels) {
    const { width, height } = doc;
    pixels.fill(0);
    if (tiles) {
      const packs = doc.manifest.packs || [];
      const across = Math.ceil(width / TILE_SIZE);
      const opened = new Map();
      for (let i = 0; i + 3 < tiles.length; i += 4) {
        const number = tiles[i];
        const pack = packs[tiles[i + 1]];
        const offset = tiles[i + 2];
        const length = tiles[i + 3];
        let packFile = opened.get(pack);
        if (!packFile) {
          packFile = await readFile(doc.dir, `pack-${pack}.bin`);
          opened.set(pack, packFile);
        }
        if (!packFile) throw new Error(`Missing pack-${pack}.bin`);
        const bytes = await inflate(packFile.slice(offset, offset + length));
        const left = number % across * TILE_SIZE;
        const top = Math.floor(number / across) * TILE_SIZE;
        const tileWidth = Math.min(TILE_SIZE, width - left);
        const tileHeight = Math.min(TILE_SIZE, height - top);
        if (bytes.length !== tileWidth * tileHeight * 4) throw new Error("A tile is damaged");
        for (let row = 0; row < tileHeight; row++) {
          pixels.set(bytes.subarray(row * tileWidth * 4, (row + 1) * tileWidth * 4), ((top + row) * width + left) * 4);
        }
      }
      return true;
    }
    if (file) {
      // Older Android artworks kept each layer as one zlib stream.
      const blob = await readFile(doc.dir, file);
      if (!blob) return false;
      const bytes = await inflate(blob);
      pixels.set(bytes.subarray(0, Math.min(bytes.length, pixels.length)));
      return true;
    }
    return false;
  }

  // Writes changed tile-aligned regions into a new pack, then the manifest, the way
  // ArtworkStore.save() does. regions maps a layer id to {left, top, width, height, pixels}.
  async save(doc, regions, thumbnail, preview) {
    const { manifest, width, height } = doc;
    const dir = doc.dir;
    const generation = Date.now();
    const across = Math.ceil(width / TILE_SIZE);
    const oldPacks = manifest.packs || [];
    // Tile maps keyed by layer id: tile number -> [pack, offset, length].
    const maps = new Map();
    const readMap = (id, values) => {
      if (!values) return;
      const map = new Map();
      for (let i = 0; i + 3 < values.length; i += 4) map.set(values[i], [oldPacks[values[i + 1]], values[i + 2], values[i + 3]]);
      maps.set(id, map);
    };
    for (const layer of manifest.layers) {
      readMap(layer.id, layer.tiles);
      if (layer.mask) readMap(layer.mask, layer.maskTiles);
    }

    const chunks = [];
    let written = 0;
    for (const [id, region] of regions) {
      const map = maps.get(id) || new Map();
      maps.set(id, map);
      for (let top = region.top; top < region.top + region.height; top += TILE_SIZE) {
        const tileHeight = Math.min(TILE_SIZE, height - top);
        for (let left = region.left; left < region.left + region.width; left += TILE_SIZE) {
          const tileWidth = Math.min(TILE_SIZE, width - left);
          const bytes = new Uint8Array(tileWidth * tileHeight * 4);
          let empty = true;
          for (let row = 0; row < tileHeight; row++) {
            const start = ((top - region.top + row) * region.width + left - region.left) * 4;
            const line = region.pixels.subarray(start, start + tileWidth * 4);
            bytes.set(line, row * tileWidth * 4);
            if (empty) for (let i = 3; i < line.length; i += 4) if (line[i] !== 0 || line[i - 1] !== 0 || line[i - 2] !== 0 || line[i - 3] !== 0) { empty = false; break; }
          }
          const number = Math.floor(top / TILE_SIZE) * across + Math.floor(left / TILE_SIZE);
          if (empty) {
            map.delete(number);
          } else {
            const packed = await deflate(bytes);
            map.set(number, [generation, written, packed.length]);
            chunks.push(packed);
            written += packed.length;
          }
        }
      }
    }
    if (chunks.length) await writeFile(dir, `pack-${generation}.bin`, new Blob(chunks));

    const current = new Set(manifest.layers.flatMap((layer) => [layer.id, layer.mask || 0]));
    const packs = [...new Set([...maps].filter(([id]) => current.has(id)).flatMap(([, map]) => [...map.values()].map((ref) => ref[0])))];
    const tileJson = (map) => map ? [...map].flatMap(([number, [pack, offset, length]]) => [number, packs.indexOf(pack), offset, length]) : null;
    for (const layer of manifest.layers) {
      if (maps.has(layer.id)) {
        layer.tiles = tileJson(maps.get(layer.id));
        layer.file = "";
      }
      if (layer.mask && maps.has(layer.mask)) {
        layer.maskTiles = tileJson(maps.get(layer.mask));
        layer.maskFile = "";
      }
    }
    manifest.packs = packs;
    manifest.updated = generation;

    await writeFile(dir, THUMBNAIL, thumbnail);
    if (preview) await writeFile(dir, PREVIEW, preview);
    const previous = await readText(dir, MANIFEST);
    if (previous) await writeFile(dir, PREVIOUS_MANIFEST, previous);
    await writeFile(dir, MANIFEST, JSON.stringify(manifest));
    await this.removeUnusedFiles(dir);
  }

  async removeUnusedFiles(dir) {
    const used = new Set();
    for (const name of [MANIFEST, PREVIOUS_MANIFEST]) {
      const text = await readText(dir, name);
      if (!text) continue;
      try {
        const manifest = JSON.parse(text);
        for (const layer of manifest.layers || []) {
          used.add(layer.file);
          used.add(layer.maskFile);
        }
        for (const pack of manifest.packs || []) used.add(`pack-${pack}.bin`);
      } catch {
        return;  // Keep everything when a manifest can't be read.
      }
    }
    const unused = [];
    for await (const [name] of dir.entries()) {
      if ((name.startsWith("layer-") || name.startsWith("pack-")) && !used.has(name)) unused.push(name);
    }
    for (const name of unused) await remove(dir, name);
  }

  // Saves an exported file: into Exports in the chosen folder, or as a download from the browser.
  // Returns where it went, for the companion to say.
  async saveExport(fileName, blob) {
    if (this.folderName) {
      const exports = await directory(this.root, "Exports");
      await writeFile(exports, fileName, blob);
      return `${this.folderName}/Exports`;
    }
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = fileName;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 10000);
    return "your downloads";
  }

  // Turns a read PSD into an artwork, its layers bottom first, as ArtworkStore.importPsd() does.
  async importPsd(image, name, projectId, thumbnail) {
    const doc = await this.create(name, projectId, image.width, image.height);
    const base = doc.manifest.layers[0];
    doc.manifest.layers = image.layers.map((layer, index) => ({
      ...base, id: index + 1, name: layer.name, opacity: layer.opacity, visible: layer.visible,
      blend: layer.blend, clip: layer.clipped, group: layer.group,
    }));
    doc.manifest.groups = image.groups.map((group, index) => ({ id: index + 1, name: group, visible: true, opacity: 1, collapsed: false }));
    doc.manifest.nextGroupId = image.groups.length + 1;
    doc.manifest.activeLayer = image.layers.length;
    doc.manifest.nextLayerId = image.layers.length + 1;
    const regions = new Map(image.layers.map((layer, index) => [index + 1, { left: 0, top: 0, width: image.width, height: image.height, pixels: layer.rgba }]));
    await this.save(doc, regions, thumbnail, null);
    return doc.id;
  }

  // A new artwork's folder and manifest, as MainActivity.openDocument() starts one.
  async create(name, projectId, width, height, print = null) {
    const id = crypto.randomUUID();
    const dir = await this.artworkDir(id, true);
    const manifest = {
      version: 1, name: name || "Untitled", project: projectId, width, height,
      groups: [], nextGroupId: 1, light: "OFF", figure: [], variationOf: "", journalSequence: 0,
      symmetry: 0, followedBoards: [], animation: false, fps: 8, symmetryCenter: [],
      activeLayer: 1, nextLayerId: 2, updated: Date.now(), packs: [], perspective: [], references: [],
      ...(print ? { print } : {}),
      layers: [{
        id: 1, name: "Layer 1", opacity: 1, visible: true, blend: 0, alphaLock: false, clip: false,
        group: 0, file: "", tiles: null, mask: 0, maskEnabled: true, maskFile: "", text: "",
        textSize: 0, textColor: 0, sticker: false, maskTiles: null,
      }],
    };
    return { id, dir, manifest, width, height, fresh: true };
  }
}
