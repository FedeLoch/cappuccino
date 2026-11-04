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

export const TILE_SIZE = 256;
export const THUMBNAIL_WIDTH = 480;
export const PREVIEW_WIDTH = 1600;
const MANIFEST = "manifest.json";
const PREVIOUS_MANIFEST = "manifest.prev.json";
const THUMBNAIL = "thumbnail.png";
const PREVIEW = "preview.jpg";

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
    const existing = await this.projects();
    const added = (await other.projects()).filter((project) => !existing.some((p) => p.id === project.id));
    if (added.length) await this.writeProjects([...existing, ...added]);
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

  // A new artwork's folder and manifest, as MainActivity.openDocument() starts one.
  async create(name, projectId, width, height) {
    const id = crypto.randomUUID();
    const dir = await this.artworkDir(id, true);
    const manifest = {
      version: 1, name: name || "Untitled", project: projectId, width, height,
      groups: [], nextGroupId: 1, light: "OFF", figure: [], variationOf: "", journalSequence: 0,
      symmetry: 0, followedBoards: [], animation: false, fps: 8, symmetryCenter: [],
      activeLayer: 1, nextLayerId: 2, updated: Date.now(), packs: [], perspective: [], references: [],
      layers: [{
        id: 1, name: "Layer 1", opacity: 1, visible: true, blend: 0, alphaLock: false, clip: false,
        group: 0, file: "", tiles: null, mask: 0, maskEnabled: true, maskFile: "", text: "",
        textSize: 0, textColor: 0, sticker: false, maskTiles: null,
      }],
    };
    return { id, dir, manifest, width, height, fresh: true };
  }
}
