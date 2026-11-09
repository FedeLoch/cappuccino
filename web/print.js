// Book covers laid out for print, as Print.kt: trim sizes, spine width, bleed, guides, and checks.

import { tr } from "./i18n.js";

export const PRINT_DPI = 300;
export const PRINT_BLEED = 0.125;
export const PRINT_SAFE = 0.25;
export const SPINE_SAFE = 0.0625;
const BARCODE_WIDTH = 2;
const BARCODE_HEIGHT = 1.2;

export const BOOK_TRIMS = [
  ["5 × 8 in", 5, 8], ["5.5 × 8.5 in", 5.5, 8.5], ["6 × 9 in", 6, 9], ["7 × 10 in", 7, 10],
  ["8.5 × 11 in", 8.5, 11], ["8.5 × 8.5 in", 8.5, 8.5], ["A5", 5.83, 8.27],
];

// Paper for the inside pages, with the thickness one page adds to the spine, in inches.
export const BOOK_PAPERS = [["White paper", 0.002252], ["Cream paper", 0.0025], ["Color paper", 0.002347]];

export const spineWidth = (pages, paper) => pages * paper[1];

// A setup is stored in the artwork's manifest with the same keys as on Android.
export function printSetup(trimWidth, trimHeight, spine = 0, wrap = false) {
  return { trimWidth, trimHeight, spine, wrap, bleed: PRINT_BLEED, dpi: PRINT_DPI };
}

export const trimmedWidth = (s) => (s.wrap ? s.trimWidth * 2 + s.spine : s.trimWidth);
export const pixelWidth = (s) => Math.round((trimmedWidth(s) + s.bleed * 2) * s.dpi);
export const pixelHeight = (s) => Math.round((s.trimHeight + s.bleed * 2) * s.dpi);
const px = (s, inches) => inches * s.dpi;

// Left, top, right, bottom of the trim line, in canvas pixels.
export function trimBox(s) {
  return [px(s, s.bleed), px(s, s.bleed), px(s, s.bleed + trimmedWidth(s)), px(s, s.bleed + s.trimHeight)];
}

export function folds(s) {
  if (!s.wrap || s.spine <= 0) return null;
  return [px(s, s.bleed + s.trimWidth), px(s, s.bleed + s.trimWidth + s.spine)];
}

export function safeAreas(s) {
  const top = px(s, s.bleed + PRINT_SAFE);
  const bottom = px(s, s.bleed + s.trimHeight - PRINT_SAFE);
  const back = [px(s, s.bleed + PRINT_SAFE), top, px(s, s.bleed + s.trimWidth - PRINT_SAFE), bottom];
  if (!s.wrap) return [back];
  const frontLeft = s.bleed + s.trimWidth + s.spine;
  const front = [px(s, frontLeft + PRINT_SAFE), top, px(s, frontLeft + s.trimWidth - PRINT_SAFE), bottom];
  if (s.spine <= SPINE_SAFE * 2) return [back, front];
  const spine = [px(s, s.bleed + s.trimWidth + SPINE_SAFE), top, px(s, s.bleed + s.trimWidth + s.spine - SPINE_SAFE), bottom];
  return [back, spine, front];
}

// The back cover's lower corner by the spine, where printers put the barcode.
export function barcodeBox(s) {
  if (!s.wrap) return null;
  const right = s.bleed + s.trimWidth - PRINT_SAFE;
  const bottom = s.bleed + s.trimHeight - PRINT_SAFE;
  return [px(s, right - BARCODE_WIDTH), px(s, bottom - BARCODE_HEIGHT), px(s, right), px(s, bottom)];
}

// The front panel, trimmed: what an online shop shows as the cover.
export function frontBox(s) {
  const left = px(s, s.bleed + (s.wrap ? s.trimWidth + s.spine : 0));
  return [Math.round(left), Math.round(px(s, s.bleed)), Math.round(left + px(s, s.trimWidth)), Math.round(px(s, s.bleed + s.trimHeight))];
}

export function describe(s) {
  const inches = (value) => String(Number(value.toFixed(3)));
  const size = `${inches(s.trimWidth)} × ${inches(s.trimHeight)} in`;
  return s.wrap ? tr("%1 book, %2 in spine", size, inches(s.spine)) : tr("%1 front cover", size);
}

// Draws the guides over the canvas: bleed shaded past the trim, safe areas, folds, and the barcode spot.
// toScreen maps canvas pixels to the overlay's pixels.
export function drawPrintGuides(c, s, canvasWidth, canvasHeight, toScreen, unit) {
  const quad = (left, top, right, bottom) => {
    const corners = [[left, top], [right, top], [right, bottom], [left, bottom]].map(([x, y]) => toScreen(x, y));
    c.moveTo(corners[0][0], corners[0][1]);
    corners.slice(1).forEach(([x, y]) => c.lineTo(x, y));
    c.closePath();
  };
  const trim = trimBox(s);
  c.save();
  c.beginPath();
  quad(0, 0, canvasWidth, canvasHeight);
  quad(trim[0], trim[1], trim[2], trim[3]);
  c.fillStyle = "rgba(40, 30, 25, 0.27)";
  c.fill("evenodd");
  const line = (draw) => {
    c.beginPath();
    draw();
    c.setLineDash([]);
    c.lineWidth = 3 * unit;
    c.strokeStyle = "rgba(0, 0, 0, 0.35)";
    c.stroke();
    c.lineWidth = 1.5 * unit;
    c.strokeStyle = "#FFFFFF";
    c.stroke();
  };
  line(() => quad(trim[0], trim[1], trim[2], trim[3]));
  (folds(s) || []).forEach((x) => line(() => quad(x, trim[1], x, trim[3])));
  c.lineWidth = unit;
  c.strokeStyle = "#D85A30";
  c.fillStyle = "#D85A30";
  c.font = `${11 * unit}px system-ui, sans-serif`;
  c.textAlign = "center";
  const code = barcodeBox(s);
  if (code) {
    c.beginPath();
    quad(...code);
    c.fillStyle = "rgba(255, 255, 255, 0.6)";
    c.fill();
    c.setLineDash([12 * unit / 2, 8 * unit / 2]);
    c.stroke();
    c.fillStyle = "#D85A30";
    const [x, y] = toScreen((code[0] + code[2]) / 2, (code[1] + code[3]) / 2);
    c.fillText(tr("Barcode"), x, y + 4 * unit);
  }
  const areas = safeAreas(s);
  const names = (areas.length === 3 ? ["Back cover", "Spine", "Front"] : areas.length === 2 ? ["Back cover", "Front"] : []).map((name) => tr(name));
  areas.forEach((area, index) => {
    c.beginPath();
    quad(...area);
    c.setLineDash([12 * unit / 2, 8 * unit / 2]);
    c.stroke();
    if (names[index]) {
      const [x, y] = toScreen((area[0] + area[2]) / 2, area[1]);
      c.fillText(names[index], x, y - 6 * unit);
    }
  });
  c.restore();
}

// Below this alpha, or whiter than this, a pixel counts as paper.
const blank = (r, g, b, a) => a < 12 || (r > 245 && g > 245 && b > 245);

// pixels: RGBA bytes, width by height, a flattened copy of the canvas. texts: [{name, left, top, right, bottom}] in canvas pixels.
// printsDuller: (r, g, b) => boolean, as Cmyk.kt. Returns the problems found, in plain words.
export function preflight(s, pixels, width, height, texts, printsDuller) {
  const issues = [];
  const safe = safeAreas(s);
  const code = barcodeBox(s);
  for (const box of texts) {
    const inside = safe.some((area) => box.left >= area[0] - 2 && box.top >= area[1] - 2 && box.right <= area[2] + 2 && box.bottom <= area[3] + 2);
    if (!inside) issues.push(tr("“%1” runs outside the safe area, so the cut could clip it.", box.name));
    if (code && box.left < code[2] && box.right > code[0] && box.top < code[3] && box.bottom > code[1]) {
      issues.push(tr("“%1” overlaps the barcode area.", box.name));
    }
  }
  const scaleX = width / pixelWidth(s);
  const scaleY = height / pixelHeight(s);
  const bleedX = px(s, s.bleed) * scaleX;
  const bleedY = px(s, s.bleed) * scaleY;
  const at = (x, y) => {
    const index = (Math.min(height - 1, Math.max(0, Math.floor(y))) * width + Math.min(width - 1, Math.max(0, Math.floor(x)))) * 4;
    return blank(pixels[index], pixels[index + 1], pixels[index + 2], pixels[index + 3]);
  };
  let checked = 0;
  let missing = 0;
  for (let index = 0; index < 60; index++) {
    const t = (index + 0.5) / 60;
    const x = bleedX + t * (width - bleedX * 2);
    const y = bleedY + t * (height - bleedY * 2);
    for (const [outside, inside] of [
      [at(x, bleedY / 3), at(x, bleedY * 2)], [at(x, height - bleedY / 3), at(x, height - bleedY * 2)],
      [at(bleedX / 3, y), at(bleedX * 2, y)], [at(width - bleedX / 3, y), at(width - bleedX * 2, y)],
    ]) {
      if (inside) continue;
      checked++;
      if (outside) missing++;
    }
  }
  if (checked > 0 && missing > checked * 0.15) {
    issues.push(tr("Your art stops at the trim line in places. Paint it out to the canvas edge, so no white sliver shows after the cut."));
  }
  const trim = trimBox(s);
  let colored = 0;
  let dull = 0;
  for (let y = Math.floor(trim[1] * scaleY); y < trim[3] * scaleY; y += 2) {
    for (let x = Math.floor(trim[0] * scaleX); x < trim[2] * scaleX; x += 2) {
      const index = (Math.min(height - 1, y) * width + Math.min(width - 1, x)) * 4;
      const [r, g, b, a] = [pixels[index], pixels[index + 1], pixels[index + 2], pixels[index + 3]];
      if (blank(r, g, b, a)) continue;
      colored++;
      if (printsDuller(r, g, b)) dull++;
    }
  }
  if (colored > 0 && dull > colored * 0.2) {
    issues.push(tr("About %1 of the cover uses colors that print duller than on screen. Check them with the Print view.", `${Math.round(dull * 100 / colored)}%`));
  }
  return issues;
}

// Shrinks the front cover to a store list's size and notes when it reads flat there, as coverRead() in Print.kt.
// pixels: RGBA bytes on white, width by height. Returns a note or null.
export function coverRead(s, pixels, width, height) {
  const SHELF = 60;
  const front = frontBox(s);
  const scaleX = width / pixelWidth(s);
  const scaleY = height / pixelHeight(s);
  const frontWidth = (front[2] - front[0]) * scaleX;
  const frontHeight = (front[3] - front[1]) * scaleY;
  if (frontWidth < 4 || frontHeight < 4) return null;
  const shelfHeight = Math.max(1, Math.floor(SHELF * frontHeight / frontWidth));
  const values = [];
  let painted = 0;
  for (let row = 0; row < shelfHeight; row++) {
    for (let column = 0; column < SHELF; column++) {
      const x = Math.min(width - 1, Math.floor(front[0] * scaleX + (column + 0.5) * frontWidth / SHELF));
      const y = Math.min(height - 1, Math.floor(front[1] * scaleY + (row + 0.5) * frontHeight / shelfHeight));
      const index = (y * width + x) * 4;
      const [r, g, b] = [pixels[index], pixels[index + 1], pixels[index + 2]];
      if (r < 245 || g < 245 || b < 245) painted++;
      values.push((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255);
    }
  }
  if (painted < values.length / 4) return null;
  values.sort((a, b) => a - b);
  const spread = values[Math.floor(values.length * 0.9)] - values[Math.floor(values.length / 10)];
  if (spread >= 0.3) return null;
  return {
    topic: "Cover",
    text: tr("Shrunk to the size a store shows it, your front cover reads flat. Push the title's value against what's behind it, much lighter or much darker."),
    why: tr("At %1 pixels wide, the lightest and darkest tenths of the cover are only %2 apart. Covers that stand out in a list are usually over %3 apart.", SHELF, `${Math.round(spread * 100)}%`, "30%"),
    marks: [{
      label: tr("Your front cover"), shape: "BOX",
      x1: front[0] / pixelWidth(s), y1: front[1] / pixelHeight(s), x2: front[2] / pixelWidth(s), y2: front[3] / pixelHeight(s),
    }],
    action: "SEE_VALUES",
  };
}

// A one-page PDF with the art as a lossless image, sized in inches. rgb: Uint8Array of width × height × 3.
export async function makePdf(rgb, width, height, widthInches, heightInches) {
  const stream = new Blob([rgb]).stream().pipeThrough(new CompressionStream("deflate"));
  const image = new Uint8Array(await new Response(stream).arrayBuffer());
  const pageWidth = (widthInches * 72).toFixed(2);
  const pageHeight = (heightInches * 72).toFixed(2);
  const content = `q ${pageWidth} 0 0 ${pageHeight} 0 0 cm /Art Do Q`;
  const encoder = new TextEncoder();
  const parts = [];
  const offsets = [];
  let length = 0;
  const add = (piece) => {
    const bytes = typeof piece === "string" ? encoder.encode(piece) : piece;
    parts.push(bytes);
    length += bytes.length;
  };
  const object = (id, body, data) => {
    offsets[id] = length;
    add(`${id} 0 obj\n${body}\n`);
    if (data) {
      add("stream\n");
      add(data);
      add("\nendstream\n");
    }
    add("endobj\n");
  };
  add("%PDF-1.4\n%\xE2\xE3\xCF\xD3\n");
  object(1, "<< /Type /Catalog /Pages 2 0 R >>");
  object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
  object(3, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${pageWidth} ${pageHeight}] /Resources << /XObject << /Art 4 0 R >> >> /Contents 5 0 R >>`);
  object(4, `<< /Type /XObject /Subtype /Image /Width ${width} /Height ${height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${image.length} >>`, image);
  object(5, `<< /Length ${content.length} >>`, encoder.encode(content));
  const table = length;
  add(`xref\n0 6\n0000000000 65535 f \n${[1, 2, 3, 4, 5].map((id) => `${String(offsets[id]).padStart(10, "0")} 00000 n \n`).join("")}`);
  add(`trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${table}\n%%EOF\n`);
  return new Blob(parts, { type: "application/pdf" });
}
