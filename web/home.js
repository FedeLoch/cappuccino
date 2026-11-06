// The home screen, laid out like GalleryActivity on Android: the wordmark and buttons, a
// greeting from the companion, the artwork to continue, your sketchbooks, and recent artworks.
// The sheets follow ArtworkSheets.kt.

import { Companion } from "./companion.js";
import {
  viewHeight, viewWidth, accentChip, askSheet, choiceSheet, confirmSheet, creamChip, h, relativeTime, say, serif, sheet,
  sheetButton, svg,
} from "./ui.js";
import { ArtworkStore, canChooseFolder } from "./storage.js";
import { WARMUPS, WARMUP_GROUPS, warmupFor } from "./warmups.js";
import { WarmupLayout, drawWarmupGuide } from "./practice.js";
import { readPsd } from "./psd.js";
import { THUMBNAIL_WIDTH, thumbnailHeight } from "./storage.js";

const ALL = "all";
const NARROW = 600;
const SEARCH_FROM = 6;
const MAX_CANVAS_SIDE = 8192;
// Cover and spine colors, from SKETCHBOOK_COLORS in Ui.kt.
export const SKETCHBOOK_COLORS = [
  ["#D85A30", "#993C1D"], ["#1D9E75", "#0F6E56"], ["#534AB7", "#3C3489"],
  ["#BA7517", "#854F0B"], ["#D4537E", "#993556"], ["#378ADD", "#185FA5"],
];
const OAT = "#E9DDCB";
const COCOA = "#6B4226";
const ESPRESSO = "#2A1D16";
const CANVAS_PRESETS = [
  ["Default", 4096, 2732, false], ["Square", 3000, 3000, false], ["A4", 3508, 2480, true],
  ["A3", 4961, 3508, true], ["Wide video", 3840, 2160, false], ["Phone story", 1920, 1080, true],
];

function oriented(width, height, portrait) {
  return portrait ? [Math.min(width, height), Math.max(width, height)] : [Math.max(width, height), Math.min(width, height)];
}

// Layers use a share of memory, as layerLimit() in Model.kt. Browsers report at most 8 GB.
function layerLimit(width, height) {
  const memory = (navigator.deviceMemory || 8) * 1024 ** 3;
  return Math.min(64, Math.max(8, Math.floor(memory / 5 / (width * height * 4))));
}

// The companion chosen in the studio, or the coffee drop the home screen starts with.
export function mascot() {
  try {
    return JSON.parse(localStorage.getItem("cappuccino.mascot")) || "COFFEE_DROP";
  } catch {
    return "COFFEE_DROP";
  }
}

export class Home {
  constructor(root, app) {
    this.root = root;
    this.app = app;  // {store, waiting, openArtwork(id), newArtwork(...), setStore(store, waiting)}
    this.selected = ALL;
    this.searchText = "";
    this.urls = [];
  }

  get store() {
    return this.app.store;
  }

  async render() {
    const projects = await this.store.projects();
    const artworks = await this.store.artworks();
    if (this.selected !== ALL && this.selected !== "" && !projects.some((p) => p.id === this.selected)) this.selected = ALL;
    for (const url of this.urls) URL.revokeObjectURL(url);
    this.urls = [];
    const page = h("div", { className: "home-page" });
    page.append(this.header());
    if (this.app.waiting) page.append(this.folderNote());
    if (this.selected === ALL) this.renderHome(page, projects, artworks);
    else this.renderSketchbook(page, projects, artworks);
    this.root.replaceChildren(page);
  }

  header() {
    const narrow = viewWidth() < NARROW;
    const buttons = h("div", { className: "home-buttons" },
      creamChip("Backup", false, () => this.backupOptions()),
      creamChip("Import PSD", false, () => this.pickPsd()),
      accentChip("New artwork", () => this.showNewArtwork()));
    return h("div", { className: narrow ? "home-header narrow" : "home-header" },
      h("div", { className: "wordmark" }, "Cappuccino"), buttons);
  }

  // After a reload the browser asks again before letting the page into your folder.
  folderNote() {
    const name = this.app.waiting.name;
    return h("div", { className: "folder-note" },
      h("div", { className: "grow" },
        h("div", {}, `Your artworks are in “${name}”.`),
        h("div", { className: "folder-detail" }, "Let Cappuccino open that folder again to see them.")),
      accentChip("Open folder", async () => {
        const store = await ArtworkStore.reopen(this.app.waiting).catch(() => null);
        if (store) {
          this.app.setStore(store, null);
          await this.render();
        }
      }));
  }

  greeting() {
    const face = h("canvas", { className: "greeting-face", ariaLabel: "Your companion" });
    const hour = new Date().getHours();
    const hello = hour < 12 ? "Good morning." : hour < 19 ? "Good afternoon." : "Good evening.";
    const row = h("div", { className: "greeting" }, face,
      h("span", { className: viewWidth() < NARROW ? "grow" : "" }, `${hello} Ready for another layer?`),
      creamChip("Warm up", false, () => this.offerWarmup()));
    requestAnimationFrame(() => new Companion(face, { look: mascot(), outline: COCOA }));
    return row;
  }

  pickPsd() {
    const input = h("input", { type: "file", accept: ".psd,image/vnd.adobe.photoshop" });
    input.addEventListener("change", async () => {
      const file = input.files[0];
      if (!file) return;
      const project = this.selected === ALL ? "" : this.selected;
      say("Importing PSD");
      try {
        const image = readPsd(await file.arrayBuffer());
        if (image.width > MAX_CANVAS_SIDE || image.height > MAX_CANVAS_SIDE) throw new Error("too large");
        await this.store.importPsd(image, "Imported PSD", project, await psdThumbnail(image));
      } catch (error) {
        console.error(error);
        say("Couldn't import this PSD. Use an 8-bit RGB file.", { long: true });
      }
      this.render();
    });
    input.click();
  }

  offerWarmup() {
    let topic = null;
    try {
      topic = JSON.parse(localStorage.getItem("cappuccino.warmupTopic"));
    } catch {
      // No topic yet.
    }
    const day = Math.floor(Date.now() / (24 * 60 * 60 * 1000));
    warmupPicker(mascot(), warmupFor(topic, day), null, (warmup) => this.app.startWarmup(warmup));
  }

  renderHome(page, projects, artworks) {
    page.append(this.greeting());
    const shelf = h("div", { className: "shelf" },
      projects.map((project) => this.sketchbook(project.name, artworks.filter((a) => a.projectId === project.id).length,
        project.color, () => this.select(project.id), () => this.projectOptions(project))),
      this.sketchbook("Unsorted", artworks.filter((a) => !a.projectId).length, null, () => this.select("")),
      h("button", { className: "new-book", onClick: () => this.newSketchbook(projects.length) }, "New sketchbook"));
    const books = h("div", { className: "books" }, h("div", { className: "heading" }, "Your sketchbooks"), shelf);
    const portrait = viewHeight() > viewWidth();
    const top = h("div", { className: portrait ? "home-top portrait" : "home-top" });
    if (artworks[0]) top.append(this.continueCard(artworks[0]));
    top.append(books);
    page.append(top);
    if (artworks.length > 1) this.searchableGrid(page, artworks.slice(1), 220, "Recent");
    if (artworks.length === 0) {
      page.append(h("div", { className: "empty" }, "Start your first artwork with New artwork."));
    }
  }

  thumbnail(art, preview) {
    const image = h("img", { className: "art-image", alt: art.name, draggable: false });
    this.store.imageUrl(art.id, preview).then((url) => {
      if (!url) return;
      this.urls.push(url);
      image.src = url;
    });
    return image;
  }

  moreButton(art) {
    return h("button", {
      className: "more", ariaLabel: `More for ${art.name}`,
      onClick: (event) => {
        event.stopPropagation();
        this.artworkOptions(art);
      },
    }, h("span", {}, "···"));
  }

  continueCard(art) {
    return h("div", {
      className: "continue", role: "button", tabIndex: 0,
      onClick: () => this.app.openArtwork(art.id),
      onContextmenu: (event) => {
        event.preventDefault();
        this.artworkOptions(art);
      },
    }, h("div", { className: "continue-image" }, this.thumbnail(art, true), this.moreButton(art)),
    h("div", { className: "continue-label" }, `Continue ${art.name}`));
  }

  sketchbook(name, count, color, open, options) {
    const [cover, spine] = color === null ? [OAT, COCOA] : SKETCHBOOK_COLORS[color % SKETCHBOOK_COLORS.length];
    const text = color === null ? ESPRESSO : "#FFFFFF";
    return h("button", {
      className: "book", ariaLabel: `Open ${name}`, style: { background: cover, color: text },
      onClick: open,
      onContextmenu: (event) => {
        event.preventDefault();
        options?.();
      },
    }, h("span", { className: "spine", style: { background: spine } }),
    h("span", { className: "book-text" }, h("span", { className: "book-name" }, name),
      h("span", { className: "book-count" }, count === 1 ? "1 artwork" : `${count} artworks`)));
  }

  select(id) {
    this.selected = id;
    this.render();
    window.scrollTo(0, 0);
  }

  renderSketchbook(page, projects, artworks) {
    const project = projects.find((p) => p.id === this.selected);
    const [cover, spine] = project ? SKETCHBOOK_COLORS[project.color % SKETCHBOOK_COLORS.length] : [OAT, COCOA];
    const text = project ? "#FFFFFF" : ESPRESSO;
    const inside = artworks.filter((a) => a.projectId === this.selected);
    page.append(h("div", { className: "cover", style: { background: cover, color: text } },
      h("span", { className: "cover-spine", style: { background: spine } }),
      h("div", { className: "cover-text" },
        h("div", { className: "serif cover-name" }, project ? project.name : "Unsorted"),
        h("div", {}, `${inside.length === 1 ? "1 artwork" : `${inside.length} artworks`} · 0 references`)),
      project && creamChip("Edit", false, () => this.projectOptions(project)),
      creamChip("Back", false, () => this.select(ALL))));
    page.append(h("div", { className: "tabs" }, creamChip("Artworks", true, () => {}),
      creamChip("References", false, () => say("References come to the web soon"))));
    if (inside.length === 0) {
      page.append(h("div", { className: "empty" }, "Start your first artwork here."));
      return;
    }
    this.searchableGrid(page, inside, 240, null);
  }

  // A grid with a quiet magnifier. The field only appears when asked for.
  searchableGrid(page, artworks, cell, title) {
    const grid = h("div", { className: "grid-cards", style: { gridTemplateColumns: `repeat(auto-fill, ${cell}px)` } });
    const fill = () => {
      const query = this.searchText.trim().toLowerCase();
      grid.replaceChildren(...artworks.filter((a) => a.name.toLowerCase().includes(query)).map((art) => this.card(art, cell)));
    };
    const field = h("input", {
      className: "field search", placeholder: "Search", value: this.searchText, hidden: !this.searchText,
      onInput: () => {
        this.searchText = field.value;
        fill();
      },
    });
    if (title || artworks.length >= SEARCH_FROM) {
      page.append(h("div", { className: "row heading-row" },
        h("div", { className: "heading grow" }, title || ""),
        artworks.length >= SEARCH_FROM && h("button", {
          className: "search-button", ariaLabel: "Search",
          onClick: () => {
            field.hidden = !field.hidden;
            if (!field.hidden) field.focus();
            else {
              field.value = "";
              this.searchText = "";
              fill();
            }
          },
        }, svg("search", COCOA))));
    }
    page.append(field, grid);
    fill();
  }

  card(art, width) {
    return h("div", {
      className: "card-art", role: "button", tabIndex: 0, style: { width: `${width}px` },
      onClick: () => this.app.openArtwork(art.id),
      onContextmenu: (event) => {
        event.preventDefault();
        this.artworkOptions(art);
      },
    }, h("div", { className: "card-image", style: { height: `${Math.floor(width * 2 / 3)}px` } }, this.thumbnail(art, false), this.moreButton(art)),
    h("div", { className: "card-name" }, art.name),
    h("div", { className: "card-time" }, relativeTime(art.updated)));
  }

  // --- Where artworks are saved --------------------------------------------------------------

  backupOptions() {
    const store = this.store;
    const note = store.folderName ? `Your artworks are saved in “${store.folderName}”.` : "Your artworks are saved in this browser.";
    const choices = [];
    if (canChooseFolder) {
      choices.push({
        label: store.folderName ? "Save artworks in another folder" : "Save artworks in a folder",
        icon: "gallery",
        run: () => this.chooseFolder(),
      });
    }
    if (store.folderName || this.app.waiting) {
      choices.push({
        label: "Keep artworks in this browser instead",
        icon: "copy",
        run: async () => {
          await ArtworkStore.forget();
          this.app.setStore(await ArtworkStore.browser(), null);
          say("Artworks now stay in this browser");
          this.render();
        },
      });
    }
    if (!canChooseFolder) {
      choices.push({ label: "Folders need Chrome or Edge", icon: "gallery", run: () => {} });
    }
    choiceSheet("Backup", choices, note);
  }

  async chooseFolder() {
    try {
      const store = await ArtworkStore.choose(this.store);
      if (!store) return false;
      this.app.setStore(store, null);
      say(`Saving to “${store.folderName}”`);
      await this.render();
      return true;
    } catch (error) {
      if (error.name !== "AbortError") say("Couldn't open that folder", { long: true });
      return false;
    }
  }

  // The first new artwork asks where artworks should live, once.
  askWhereFirst(then) {
    let asked = false;
    try {
      asked = localStorage.getItem("cappuccino.askedFolder") === "1";
      localStorage.setItem("cappuccino.askedFolder", "1");
    } catch {
      // No storage: ask each visit.
    }
    if (asked || !canChooseFolder || this.store.folderName || this.app.waiting) {
      then();
      return;
    }
    let close;
    const body = h("div", { className: "sheet-body confirm-body" },
      serif("Where should your artworks live?", 22),
      h("div", { className: "sheet-message" },
        "Choose a folder on this computer, like Documents or a synced folder, and every artwork is saved there as you draw. Or keep them in this browser."),
      h("div", { className: "end gap18" },
        creamChip("Keep in this browser", false, () => {
          close();
          then();
        }),
        sheetButton("Choose a folder", async () => {
          close();
          await this.chooseFolder();
          then();
        })));
    close = sheet(body, { small: true });
  }

  // --- New artwork ---------------------------------------------------------------------------

  showNewArtwork() {
    this.askWhereFirst(() => this.newArtworkSheet());
  }

  async newArtworkSheet() {
    const projects = await this.store.projects();
    let preset = CANVAS_PRESETS[0];
    let portrait = false;
    let name = "";
    let customWidth = "";
    let customHeight = "";
    let project = this.selected === ALL ? "" : this.selected;
    const body = h("div", { className: "sheet-body new-body" });
    let close;
    const size = () => {
      if (preset) return oriented(preset[1], preset[2], portrait);
      const width = Number.parseInt(customWidth, 10);
      const height = Number.parseInt(customHeight, 10);
      return width >= 64 && width <= MAX_CANVAS_SIDE && height >= 64 && height <= MAX_CANVAS_SIDE ? [width, height] : null;
    };
    const render = () => {
      const chosen = size();
      const summary = h("div", { className: "summary grow" }, chosen
        ? `${chosen[0]} x ${chosen[1]} pixels · up to ${layerLimit(chosen[0], chosen[1])} layers`
        : `Use sizes from 64 to ${MAX_CANVAS_SIDE} pixels`);
      const create = accentChip("Create", () => {
        const picked = size();
        if (!picked) return;
        close();
        this.app.newArtwork(picked[0], picked[1], name.trim(), project);
      });
      create.style.opacity = chosen ? "1" : "0.4";
      const refresh = () => {
        const picked = size();
        summary.textContent = picked
          ? `${picked[0]} x ${picked[1]} pixels · up to ${layerLimit(picked[0], picked[1])} layers`
          : `Use sizes from 64 to ${MAX_CANVAS_SIDE} pixels`;
        create.style.opacity = picked ? "1" : "0.4";
      };
      const presets = h("div", { className: "presets" }, [...CANVAS_PRESETS, null].map((option) => {
        const on = option === preset;
        const tall = on ? portrait : option?.[3] === true;
        const [w, hgt] = option ? oriented(option[1], option[2], tall) : [0, 0];
        return h("button", {
          className: on ? "preset on" : "preset",
          onClick: () => {
            preset = option;
            portrait = option?.[3] === true;
            render();
          },
        }, option ? paper(w / hgt, on) : h("span", { className: "plus" }, "+"),
        h("span", { className: "preset-name" }, option ? option[0] : "Custom size"),
        h("span", { className: "preset-size" }, option ? `${w} x ${hgt}` : "Your own"));
      }));
      const parts = [
        h("div", { className: "row" }, h("div", { className: "grow" }, serif("New artwork", 26)), creamChip("Cancel", false, () => close())),
        h("div", { className: "gap14" }, presets),
      ];
      if (!preset) {
        const field = (value, hint, change) => h("input", {
          className: "field", inputMode: "numeric", placeholder: hint, value,
          onInput: (event) => {
            change(event.target.value);
            refresh();
          },
        });
        parts.push(h("div", { className: "row gap10 two" },
          field(customWidth, "Width in pixels", (v) => { customWidth = v; }),
          field(customHeight, "Height in pixels", (v) => { customHeight = v; })));
      } else if (preset[1] !== preset[2]) {
        parts.push(h("div", { className: "row gap10 chips-row" },
          creamChip("Landscape", !portrait, () => { portrait = false; render(); }),
          creamChip("Portrait", portrait, () => { portrait = true; render(); })));
      }
      parts.push(h("div", { className: "label gap14" }, "Name"),
        h("input", { className: "field gap6", placeholder: "Untitled", value: name, onInput: (event) => { name = event.target.value; } }),
        h("div", { className: "label gap14" }, "Sketchbook"),
        h("div", { className: "row gap6 chips-row scroll" }, [...projects.map((p) => [p.id, p.name]), ["", "Unsorted"]].map(([id, title]) =>
          creamChip(title, project === id, () => { project = id; render(); }))),
        h("div", { className: "row gap18" }, summary, create));
      body.replaceChildren(...parts);
    };
    render();
    close = sheet(body);
  }

  // --- Sketchbooks ---------------------------------------------------------------------------

  newSketchbook(count) {
    let color = count % SKETCHBOOK_COLORS.length;
    let close;
    const title = serif("Untitled", 20);
    title.classList.add("book-title");
    const spine = h("span", { className: "big-spine" });
    const book = h("div", { className: "big-book" }, spine, title);
    const swatches = SKETCHBOOK_COLORS.map((_, index) => h("button", {
      className: "cover-swatch", ariaLabel: `Cover color ${index + 1}`,
      onClick: () => {
        color = index;
        paint();
      },
    }));
    const paint = () => {
      book.style.background = SKETCHBOOK_COLORS[color][0];
      spine.style.background = SKETCHBOOK_COLORS[color][1];
      swatches.forEach((swatch, index) => {
        swatch.style.background = SKETCHBOOK_COLORS[index][0];
        swatch.style.border = index === color ? `3px solid ${ESPRESSO}` : `1px solid ${OAT}`;
      });
    };
    const create = sheetButton("Create", async () => {
      const name = field.value.trim();
      if (!name) return;
      close();
      const project = await this.store.createProject(name, color);
      this.select(project.id);
    });
    create.style.opacity = "0.45";
    const field = h("input", {
      className: "field gap6", placeholder: "Untitled",
      onInput: () => {
        const name = field.value.trim();
        title.textContent = name || "Untitled";
        create.style.opacity = name ? "1" : "0.45";
      },
    });
    paint();
    const wide = viewWidth() >= 620;
    const body = h("div", { className: "sheet-body book-body" },
      h("div", { className: "row" }, h("div", { className: "grow" }, serif("New sketchbook", 26)), creamChip("Cancel", false, () => close())),
      h("div", { className: wide ? "row top gap18" : "column gap18" }, book,
        h("div", { className: wide ? "grow book-choices" : "book-choices" },
          h("div", { className: "label" }, "Name"), field,
          h("div", { className: "label gap18" }, "Cover"),
          h("div", { className: "swatch-grid gap8" }, swatches))),
      h("div", { className: "end gap8" }, create));
    close = sheet(body);
    field.focus();
  }

  projectOptions(project) {
    choiceSheet(project.name, [
      { label: "Rename", icon: "rename", run: () => askSheet("Rename sketchbook", project.name, async (name) => {
        await this.store.renameProject(project.id, name);
        this.render();
      }) },
      { label: "Change cover color", icon: "palette", run: () => this.chooseCover(project) },
      { label: "Delete", icon: "trash", danger: true, run: () => confirmSheet(`Delete ${project.name}?`,
        "Its artworks move to Unsorted. Its references are deleted.", async () => {
          await this.store.deleteProject(project.id);
          this.selected = ALL;
          this.render();
        }) },
    ]);
  }

  chooseCover(project) {
    let close;
    const body = h("div", { className: "sheet-body cover-body" }, serif("Change cover color", 24),
      h("div", { className: "cover-grid gap14" }, SKETCHBOOK_COLORS.map(([cover, spine], index) => h("button", {
        className: "cover-choice", ariaLabel: `Cover color ${index + 1}`,
        style: { background: cover, outline: index === project.color % SKETCHBOOK_COLORS.length ? `3px solid ${ESPRESSO}` : "none" },
        onClick: async () => {
          close();
          await this.store.recolorProject(project.id, index);
          this.render();
        },
      }, h("span", { className: "cover-choice-spine", style: { background: spine } })))));
    close = sheet(body);
  }

  // --- One artwork ---------------------------------------------------------------------------

  async artworkOptions(art) {
    const projects = await this.store.projects();
    const family = await this.store.variations(art.id);
    const sketchbook = projects.find((p) => p.id === art.projectId)?.name || "Unsorted";
    let close;
    const action = (icon, label, run, danger = false) => h("button", {
      className: danger ? "sheet-row danger" : "sheet-row",
      onClick: () => {
        close();
        run();
      },
    }, svg(icon, danger ? "#D85A30" : COCOA), h("span", {}, label));
    const size = art.layers === 1 ? `${art.width} x ${art.height} pixels · 1 layer` : `${art.width} x ${art.height} pixels · ${art.layers} layers`;
    const wide = viewWidth() >= 620;
    const preview = h("div", { className: "sheet-preview" }, this.thumbnail(art, true));
    const facts = h("div", { className: "facts" },
      serif(art.name, 26),
      h("div", { className: "fact gap4" }, `Edited ${relativeTime(art.updated).toLowerCase()}`),
      h("div", { className: "fact" }, size),
      h("div", { className: "fact" }, sketchbook),
      h("button", { className: "continue-button gap16", onClick: () => { close(); this.app.openArtwork(art.id); } }, "Continue drawing"),
      h("div", { style: { height: "6px" } }),
      action("rename", "Rename", () => askSheet("Rename artwork", art.name, async (name) => {
        await this.store.renameArtwork(art.id, name);
        this.render();
      })),
      action("gallery", "Move to sketchbook", () => choiceSheet("Move to",
        [["", "Unsorted"], ...projects.map((p) => [p.id, p.name])].map(([id, name]) => ({
          label: name,
          run: async () => {
            await this.store.moveArtwork(art.id, id);
            this.render();
          },
        })))),
      action("duplicate", "Duplicate", async () => {
        await this.store.duplicateArtwork(art.id);
        this.render();
      }),
      action("palette", "Make a variation", async () => {
        await this.store.makeVariation(art.id);
        this.render();
      }),
      action("trash", "Delete", () => confirmSheet(`Delete ${art.name}?`, "You can't undo this.", async () => {
        await this.store.deleteArtwork(art.id);
        this.render();
      }), true));
    const body = h("div", { className: "sheet-body art-body" },
      h("div", { className: wide ? "row top" : "column" }, preview, facts));
    if (family.length > 1) {
      body.append(h("div", { className: "label gap14" }, "Variations"),
        h("div", { className: "row gap6 scroll" }, family.map((member) => h("button", {
          className: member.id === art.id ? "variation on" : "variation",
          onClick: () => {
            close();
            this.app.openArtwork(member.id);
          },
        }, this.thumbnail(member, false)))));
    }
    close = sheet(body);
  }
}

// A small sheet of paper in a canvas's proportions, as PaperShape in ArtworkSheets.kt.
function paper(ratio, chosen) {
  const box = h("span", { className: "paper-box" });
  const sheetOf = h("span", { className: "paper", style: { borderColor: chosen ? ESPRESSO : COCOA } });
  const longest = 52 * 0.86;
  sheetOf.style.width = `${ratio >= 1 ? longest : longest * ratio}px`;
  sheetOf.style.height = `${ratio >= 1 ? longest / ratio : longest}px`;
  box.append(sheetOf);
  return box;
}

// --- Warm-ups, as warmupPicker() in ArtworkSheets.kt --------------------------------------------

const PREVIEW_PIXELS = 256;
const PREVIEW_SEED = 7;  // Previews show one steady example; each session still lays it out fresh.
const MUTED_COCOA = "#9A7B66";

function warmupPreview(warmup, size) {
  const preview = h("canvas", { className: "warmup-preview", width: PREVIEW_PIXELS, height: PREVIEW_PIXELS });
  preview.style.width = `${size}px`;
  preview.style.height = `${size}px`;
  const c = preview.getContext("2d");
  c.fillStyle = "#FFFFFF";
  c.fillRect(0, 0, PREVIEW_PIXELS, PREVIEW_PIXELS);
  drawWarmupGuide(c, warmup.kind, PREVIEW_PIXELS, PREVIEW_PIXELS, new WarmupLayout(PREVIEW_SEED));
  return preview;
}

// Lets you pick a warm-up exercise, with the one your notes point to suggested first. Warm-ups
// are only practice, so nothing from them is saved.
export function warmupPicker(look, suggested, current, pick) {
  let close;
  const start = (warmup) => {
    close();
    pick(warmup);
  };
  const about = h("div", { className: "warmup-about" }, "Hold an exercise to see what it trains.");
  const face = h("canvas", { className: "warmup-face", ariaLabel: "Your companion" });
  const body = h("div", { className: "sheet-body warmup-body" },
    h("div", { className: "row" }, face,
      h("div", { className: "grow warmup-intro" }, serif("Warm up", 24),
        h("div", { className: "sheet-message" }, "Pick an exercise. Warm-ups are just practice, so nothing is saved."))));
  if (suggested !== current) {
    body.append(h("div", { className: "suggested", role: "button", tabIndex: 0, onClick: () => start(suggested) },
      warmupPreview(suggested, 104),
      h("div", { className: "grow suggested-text" },
        h("div", { className: "suggested-label" }, "Suggested for you"),
        serif(suggested.title, 20),
        h("div", { className: "suggested-steps" }, suggested.steps),
        h("div", { className: "suggested-purpose" }, suggested.purpose)),
      sheetButton("Start", (event) => {
        event.stopPropagation();
        start(suggested);
      })));
  }
  body.append(about);
  for (const group of WARMUP_GROUPS) {
    body.append(h("div", { className: "label gap18" }, group));
    body.append(h("div", { className: "warmup-row" }, WARMUPS.filter((warmup) => warmup.group === group).map((warmup) => {
      let held = false;
      let timer = 0;
      return h("button", {
        className: warmup === current ? "warmup-card on" : "warmup-card",
        ariaLabel: warmup.title,
        onPointerdown: () => {
          held = false;
          timer = setTimeout(() => {
            held = true;
            about.textContent = `${warmup.title}: ${warmup.purpose}`;
            about.style.color = ESPRESSO;
          }, 500);
        },
        onPointerup: () => clearTimeout(timer),
        onPointerleave: () => clearTimeout(timer),
        onContextmenu: (event) => {
          event.preventDefault();
          about.textContent = `${warmup.title}: ${warmup.purpose}`;
          about.style.color = ESPRESSO;
        },
        onClick: () => {
          if (!held) start(warmup);
        },
      }, warmupPreview(warmup, 112), h("span", { className: "warmup-title" }, warmup.title));
    })));
  }
  body.append(h("div", { className: "end gap18" }, creamChip("Not now", false, () => close())));
  close = sheet(body);
  requestAnimationFrame(() => new Companion(face, { look }));
}

// A small flattened picture of a PSD's visible layers on white, for its card.
async function psdThumbnail(image) {
  const width = THUMBNAIL_WIDTH;
  const height = thumbnailHeight(image.width, image.height);
  const surface = new OffscreenCanvas(width, height);
  const c = surface.getContext("2d");
  c.fillStyle = "#FFFFFF";
  c.fillRect(0, 0, width, height);
  const page = new OffscreenCanvas(image.width, image.height);
  const pageContext = page.getContext("2d");
  for (const layer of image.layers.filter((it) => it.visible)) {
    const pixels = new Uint8ClampedArray(layer.rgba.length);
    for (let i = 0; i < pixels.length; i += 4) {
      const alpha = layer.rgba[i + 3];
      const scale = alpha ? 255 / alpha : 0;
      pixels[i] = layer.rgba[i] * scale;
      pixels[i + 1] = layer.rgba[i + 1] * scale;
      pixels[i + 2] = layer.rgba[i + 2] * scale;
      pixels[i + 3] = alpha;
    }
    pageContext.putImageData(new ImageData(pixels, image.width, image.height), 0, 0);
    c.globalAlpha = layer.opacity;
    c.drawImage(page, 0, 0, width, height);
  }
  return surface.convertToBlob({ type: "image/png" });
}
