// Compares an artwork with its references without any network or AI model, as ReferenceMatch.kt
// does on Android. Pixels are RGBA bytes.

const HUE_BINS = 12;
const NEUTRAL_BIN = HUE_BINS;
const VALUE_BINS = 5;
const GRID_COLUMNS = 6;
const GRID_ROWS = 8;
const MAX_SUGGESTIONS = 4;
const CLOSE = 0.72;
const CLOSER = 0.5;
const FULL_COVERAGE = 0.5;
const EARLY_COVERAGE = 0.2;
export const REFERENCE_PROFILE_SIZE = 240;

const HUE_NAMES = ["red", "orange", "yellow", "lime", "green", "teal", "cyan", "blue", "indigo", "violet", "magenta", "pink"];
const COMPARED = ["PALETTE", "VALUES", "COMPOSITION", "MOOD"];

const average = (values) => (values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : NaN);
const clamp01 = (value) => Math.min(1, Math.max(0, value));

function hueBin(red, green, blue, high, low) {
  const delta = high - low;
  let hue;
  if (high === red) hue = (((green - blue) / delta + 6) % 6);
  else if (high === green) hue = (blue - red) / delta + 2;
  else hue = (red - green) / delta + 4;
  return Math.min(HUE_BINS - 1, Math.max(0, Math.floor(hue * 60 / 360 * HUE_BINS)));
}

export function profileImage(pixels, width, height) {
  const count = width * height;
  const colors = new Float32Array(HUE_BINS + 1);
  const reds = new Float32Array(HUE_BINS + 1);
  const greens = new Float32Array(HUE_BINS + 1);
  const blues = new Float32Array(HUE_BINS + 1);
  const values = new Float32Array(VALUE_BINS);
  const layout = new Float32Array(GRID_COLUMNS * GRID_ROWS);
  const cellCounts = new Int32Array(layout.length);
  const luminances = new Float32Array(count);
  let saturationTotal = 0;
  let warmTotal = 0;
  let brightnessTotal = 0;
  let marked = 0;
  for (let index = 0; index < count; index++) {
    const red = pixels[index * 4];
    const green = pixels[index * 4 + 1];
    const blue = pixels[index * 4 + 2];
    const high = Math.max(red, green, blue);
    const low = Math.min(red, green, blue);
    if (low < 235) marked++;
    const saturation = high === 0 ? 0 : (high - low) / high;
    const luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255;
    const bin = saturation < 0.18 || high < 30 ? NEUTRAL_BIN : hueBin(red, green, blue, high, low);
    colors[bin] += 1;
    reds[bin] += red;
    greens[bin] += green;
    blues[bin] += blue;
    values[Math.min(VALUE_BINS - 1, Math.floor(luminance * VALUE_BINS))] += 1;
    const cell = Math.floor((index % width) * GRID_COLUMNS / width) + Math.floor(Math.floor(index / width) * GRID_ROWS / height) * GRID_COLUMNS;
    layout[cell] += luminance;
    cellCounts[cell]++;
    saturationTotal += saturation;
    warmTotal += (red - blue) / 255;
    brightnessTotal += luminance;
    luminances[index] = luminance;
  }
  const binColors = Array.from(colors, (amount, bin) => {
    const n = Math.max(amount, 1);
    const hex = (value) => Math.floor(value / n).toString(16).padStart(2, "0");
    return `#${hex(reds[bin])}${hex(greens[bin])}${hex(blues[bin])}`.toUpperCase();
  });
  const total = Math.max(count, 1);
  for (let bin = 0; bin < colors.length; bin++) colors[bin] /= total;
  for (let bin = 0; bin < values.length; bin++) values[bin] /= total;
  for (let cell = 0; cell < layout.length; cell++) layout[cell] /= Math.max(cellCounts[cell], 1);
  luminances.sort();
  const contrast = luminances[Math.floor(count * 9 / 10)] - luminances[Math.floor(count / 10)];
  return {
    colors, binColors, values, layout,
    brightness: brightnessTotal / total, saturation: saturationTotal / total, warmth: warmTotal / total, contrast, coverage: marked / total,
  };
}

function overlap(first, second) {
  let sum = 0;
  for (let i = 0; i < first.length; i++) sum += Math.min(first[i], second[i]);
  return sum;
}

function layoutScore(art, reference) {
  const artMean = average(Array.from(art.layout));
  const referenceMean = average(Array.from(reference.layout));
  let difference = 0;
  for (let i = 0; i < art.layout.length; i++) difference += Math.abs((art.layout[i] - artMean) - (reference.layout[i] - referenceMean));
  return clamp01(1 - difference / art.layout.length * 3);
}

function moodScore(art, reference) {
  return clamp01(1 - (Math.abs(art.saturation - reference.saturation) + Math.abs(art.warmth - reference.warmth) + Math.abs(art.contrast - reference.contrast)));
}

const mean = (items, read) => average(items.map(read));

function missingBins(art, references) {
  const wanted = Array.from({ length: HUE_BINS }, (_, bin) => average(references.map((profile) => profile.colors[bin])));
  return [...Array(HUE_BINS).keys()]
    .filter((bin) => wanted[bin] > 0.03 && wanted[bin] > art.colors[bin] * 1.5)
    .sort((a, b) => (wanted[b] - art.colors[b]) - (wanted[a] - art.colors[a]));
}

function colorAdvice(art, references) {
  const names = missingBins(art, references).slice(0, 2).map((bin) => HUE_NAMES[bin]);
  if (!names.length) return "Your colors are on track. Check that the main color takes the most area.";
  if (names.length === 1) return `Your references lean on ${names[0]}. Try bringing more of it in.`;
  return `Your references lean on ${names[0]} and ${names[1]}. Try bringing more of them in.`;
}

function valueAdvice(art, references) {
  const brightness = mean(references, (profile) => profile.brightness);
  const contrast = mean(references, (profile) => profile.contrast);
  if (art.brightness > brightness + 0.1) return "Your references are darker overall. Block in deeper shadow shapes.";
  if (art.brightness < brightness - 0.1) return "Your references are lighter overall. Open up more light areas.";
  if (art.contrast < contrast - 0.1) return "Your references have stronger contrast. Push your darks and lights further apart.";
  return "Your values are on track. Keep the strongest contrast at your focus.";
}

function layoutAdvice(art, references) {
  const half = GRID_ROWS / 2 * GRID_COLUMNS;
  const topMinusBottom = (profile) => average(Array.from(profile.layout.slice(0, half))) - average(Array.from(profile.layout.slice(half)));
  const target = average(references.map(topMinusBottom));
  const current = topMinusBottom(art);
  if (target > current + 0.08) return "Your references keep more dark weight low. Try anchoring the bottom of the image.";
  if (target < current - 0.08) return "Your references keep more dark weight high. Try a heavier top.";
  return "Compare your big light and dark shapes with the reference layout.";
}

function moodAdvice(art, references) {
  const saturation = mean(references, (profile) => profile.saturation);
  const warmth = mean(references, (profile) => profile.warmth);
  if (art.saturation < saturation - 0.1) return "Your references feel more colorful. Try richer color in your focus areas.";
  if (art.saturation > saturation + 0.1) return "Your references feel more muted. Try softening your strongest colors.";
  if (art.warmth < warmth - 0.08) return "Your references feel warmer. Lean into warm lights.";
  if (art.warmth > warmth + 0.08) return "Your references feel cooler. Try cooler shadows.";
  return "The mood is on track.";
}

const percent = (value) => `${Math.trunc(value * 100)}%`;

function matchWhy(art, references) {
  const notes = [
    `Canvas filled: ${percent(art.coverage)}.`,
    `Brightness: yours ${percent(art.brightness)}, references ${percent(mean(references, (p) => p.brightness))}.`,
    `Contrast: yours ${percent(art.contrast)}, references ${percent(mean(references, (p) => p.contrast))}.`,
    `Color strength: yours ${percent(art.saturation)}, references ${percent(mean(references, (p) => p.saturation))}.`,
  ];
  const missing = missingBins(art, references).slice(0, 3).map((bin) => HUE_NAMES[bin]);
  if (missing.length) notes.push(`Stronger in references: ${missing.join(", ")}.`);
  return notes.join(" ");
}

const maxBy = (items, score) => items.reduce((best, item) => (best === undefined || score(item) > score(best) ? item : best), undefined);

// Compares artwork with each reference, keeping the closest result for every area.
export function compareWithReferences(art, references, aspects) {
  if (!references.length) return null;
  aspects = aspects || Object.fromEntries(COMPARED.map((purpose) => [purpose, references.map((_, i) => i)]));
  const set = (purpose) => (aspects[purpose] || []).map((index) => references[index]);
  const palette = set("PALETTE");
  const tones = set("VALUES");
  const layouts = set("COMPOSITION");
  const moods = set("MOOD");
  // Empty paper can look like a cream background, so scores grow only as the page fills in.
  const developed = clamp01(art.coverage / FULL_COVERAGE);
  const best = (items, score) => Math.max(...items.map(score));
  const measured = [];
  if (palette.length) measured.push([{ name: "Palette", score: best(palette, (p) => overlap(art.colors, p.colors)) * developed }, 0.35, "PALETTE"]);
  if (tones.length) measured.push([{ name: "Values", score: best(tones, (p) => overlap(art.values, p.values)) * developed }, 0.3, "VALUES"]);
  if (layouts.length) measured.push([{ name: "Composition", score: best(layouts, (p) => layoutScore(art, p)) * developed }, 0.2, "COMPOSITION"]);
  if (moods.length) measured.push([{ name: "Mood", score: best(moods, (p) => moodScore(art, p)) * developed }, 0.15, "MOOD"]);
  if (!measured.length) return null;
  const areas = measured.map(([area]) => area);
  const overall = measured.reduce((sum, [area, weight]) => sum + area.score * weight, 0) / measured.reduce((sum, [, weight]) => sum + weight, 0);
  let summary;
  if (art.coverage < EARLY_COVERAGE) summary = "It's still early to compare with your references.";
  else if (overall >= CLOSE) summary = "You're close to your references.";
  else if (overall >= CLOSER) summary = "You're getting closer to your references.";
  else summary = "You're still far from your references.";
  const weakest = measured.reduce((low, item) => (item[0].score < low[0].score ? item : low))[2];
  const advice = weakest === "PALETTE" ? colorAdvice(art, palette)
    : weakest === "VALUES" ? valueAdvice(art, tones)
      : weakest === "COMPOSITION" ? layoutAdvice(art, layouts)
        : moodAdvice(art, moods);
  const colors = palette.length
    ? missingBins(art, palette).slice(0, MAX_SUGGESTIONS).map((bin) => maxBy(palette, (p) => p.colors[bin]).binColors[bin])
    : [];
  const placed = (aspects.COMPOSITION || []).length ? aspects.COMPOSITION : references.map((_, i) => i);
  const closest = maxBy(placed, (index) => {
    const reference = references[index];
    return overlap(art.colors, reference.colors) * 0.35 + overlap(art.values, reference.values) * 0.3 +
      layoutScore(art, reference) * 0.2 + moodScore(art, reference) * 0.15;
  });
  const target = references[closest];
  const artMean = average(Array.from(art.layout));
  const targetMean = average(Array.from(target.layout));
  const cell = maxBy([...art.layout.keys()], (i) => Math.abs((art.layout[i] - artMean) - (target.layout[i] - targetMean)));
  const farthest = [(cell % GRID_COLUMNS + 0.5) / GRID_COLUMNS, (Math.floor(cell / GRID_COLUMNS) + 0.5) / GRID_ROWS];
  return { overall, areas, summary, advice, colors, why: matchWhy(art, references), closest, farthest, weakest, marksCanvas: layouts.length > 0 };
}
