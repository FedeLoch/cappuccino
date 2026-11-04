// What the companion notices, ported from Analysis.kt and CompanionMemory.kt on Android. It reads
// a small flattened copy of the artwork on white paper, without any network or AI model.
// Keep it in step with the Kotlin until step 3 shares that code with the web.

const PAPER_DISTANCE = 18;
const HUE_BINS = 12;
const MARK_COLUMNS = 12;
const MARK_ROWS = 8;
const MARK_RADIUS = 0.07;
const TARGET_FAR = 0.72;
const TARGET_NEAR = 0.28;
const MIN_CONTRAST = 0.002;
const WHOLE_CANVAS = 0.9;
const HUE_NAMES = ["red", "orange", "yellow", "lime", "green", "teal", "cyan", "blue", "indigo", "violet", "magenta", "pink"];
const SKETCH_COVERAGE = 0.25;
const BLOCK_IN_COVERAGE = 0.7;
const CLEARER_VALUES = 0.1;
const MORE_FINISHED = 0.2;
const MUTE_TOPIC_AFTER = 3;
const CLEARER_SINCE_START = 0.08;
const MORE_COLOR_SINCE_START = 0.15;

const percent = (value) => `${Math.trunc(value * 100)}%`;
const indexOfMax = (values) => values.reduce((best, value, index) => (value > values[best] ? index : best), 0);

// pixels holds RGBA bytes, rows from the top. Returns the tips, the value and silhouette previews
// as RGBA, the ideas, and the numbers the companion remembers between sittings.
export function analyzeArtwork(pixels, width, height, lightDegrees = null) {
  const count = width * height;
  const values = new Uint8ClampedArray(count * 4);
  const silhouette = new Uint8ClampedArray(count * 4);
  const hues = new Float32Array(HUE_BINS);
  const luminances = [];
  let markCount = 0;
  let coloredWeight = 0;
  let saturationTotal = 0;
  let weightTotal = 0;
  let weightX = 0;
  let weightY = 0;
  let lightTotal = 0;
  let lightX = 0;
  let lightY = 0;
  let left = width;
  let top = height;
  let right = -1;
  let bottom = -1;
  const cells = MARK_COLUMNS * MARK_ROWS;
  const cellLight = new Float32Array(cells);
  const cellSquares = new Float32Array(cells);
  const cellCounts = new Int32Array(cells);
  const cellColor = new Float32Array(cells);

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const index = y * width + x;
      const red = pixels[index * 4];
      const green = pixels[index * 4 + 1];
      const blue = pixels[index * 4 + 2];
      const luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255;
      const cell = Math.floor(y * MARK_ROWS / height) * MARK_COLUMNS + Math.floor(x * MARK_COLUMNS / width);
      cellLight[cell] += luminance;
      cellSquares[cell] += luminance * luminance;
      cellCounts[cell]++;
      const level = Math.trunc(luminance * 255);
      values.set([level, level, level, 255], index * 4);
      const marked = Math.max(255 - red, 255 - green, 255 - blue) > PAPER_DISTANCE;
      silhouette.set(marked ? [0x2C, 0x2C, 0x2A, 255] : [255, 255, 255, 255], index * 4);
      if (!marked) continue;

      markCount++;
      luminances.push(luminance);
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x);
      bottom = Math.max(bottom, y);
      const weight = 1 - luminance + 0.1;
      weightTotal += weight;
      weightX += x * weight;
      weightY += y * weight;
      lightTotal += luminance;
      lightX += x * luminance;
      lightY += y * luminance;

      const high = Math.max(red, green, blue);
      const low = Math.min(red, green, blue);
      const saturation = high === 0 ? 0 : (high - low) / high;
      if (saturation > 0.2 && high > 40) {
        hues[hueBin(red, green, blue, high, low)] += 1;
        coloredWeight += 1;
        saturationTotal += saturation;
        cellColor[cell] += saturation;
      }
    }
  }

  if (markCount < count * 0.015) {
    return { tips: [], width, height, values, silhouette, ideas: [], contrast: 0, coverage: 0, colored: 0 };
  }
  const coverage = markCount / count;
  const lightDirection = Math.atan2(
    lightY / Math.max(lightTotal, 1) - weightY / weightTotal,
    lightX / Math.max(lightTotal, 1) - weightX / weightTotal,
  ) * 180 / Math.PI;
  const balanceX = weightX / weightTotal / width;
  const balanceY = weightY / weightTotal / height;
  const variance = Array.from({ length: cells }, (_, cell) => {
    const n = Math.max(cellCounts[cell], 1);
    const mean = cellLight[cell] / n;
    return cellSquares[cell] / n - mean * mean;
  });
  const busiest = indexOfMax(variance);
  const strongest = indexOfMax(Array.from(cellColor));
  const tips = [];
  const stage = stageTip(coverage, coloredWeight, markCount);
  if (stage) {
    tips.push({ ...stage, why: `Marks cover ${percent(coverage)} of the canvas, and ${percent(coloredWeight / markCount)} of them have color.` });
  }
  if (lightDegrees !== null) {
    const light = lightTip(lightDegrees, lightDirection, lightTotal);
    if (light) {
      tips.push({
        ...light,
        why: `Your lighter marks gather ${directionName(lightDirection)} of the darker ones.`,
        marks: [ring("Light gathers here", lightX / Math.max(lightTotal, 1) / width, lightY / Math.max(lightTotal, 1) / height)],
        action: "OPEN_GUIDES",
      });
    }
  }
  tips.push({
    ...paletteTip(hues, coloredWeight, saturationTotal, markCount),
    why: paletteWhy(hues, coloredWeight, saturationTotal),
    marks: cellColor[strongest] > 0 ? [cellRing("Strongest color", strongest)] : [],
    action: "OPEN_COLORS",
  });
  tips.push({
    ...valueTip(luminances),
    why: valueWhy(luminances),
    marks: variance[busiest] > MIN_CONTRAST ? [cellRing("Strongest contrast", busiest)] : [],
    action: "SEE_VALUES",
  });
  tips.push({
    ...compositionTip(balanceX, balanceY),
    why: `Your visual weight sits ${percent(balanceX)} across and ${percent(balanceY)} down the canvas.`,
    marks: compositionMarks(balanceX, balanceY),
    action: "SHOW_THIRDS",
  });
  tips.push({
    ...silhouetteTip(markCount, left, top, right, bottom),
    why: `Your marks fill ${percent(markCount / ((right - left + 1) * (bottom - top + 1)))} of the area around them.`,
    marks: silhouetteMarks(left, top, right, bottom, width, height),
    action: "CHECK_SHAPES",
  });
  const mainHue = indexOfMax(Array.from(hues));
  const ideas = creativeIdeas(
    hues[mainHue] > 0 ? (mainHue + 0.5) * 360 / HUE_BINS : null,
    coloredWeight / markCount,
    coloredWeight > 0 ? saturationTotal / coloredWeight : 0,
    coverage,
  );
  const cellMeans = [];
  for (let cell = 0; cell < cells; cell++) if (cellCounts[cell] > 0) cellMeans.push(cellLight[cell] / cellCounts[cell]);
  const contrast = cellMeans.length ? Math.max(...cellMeans) - Math.min(...cellMeans) : 0;
  return {
    tips: orderForStage(tips.map(withDefaults), artStage(coverage)),
    width, height, values, silhouette, ideas, contrast, coverage,
    colored: coloredWeight / Math.max(markCount, 1),
  };
}

function withDefaults(tip) {
  return { why: "", marks: [], action: null, ...tip };
}

// Open invitations to experiment. They suggest what to try, and never say what's wrong.
function creativeIdeas(mainHue, colored, saturation, coverage) {
  const ideas = [];
  if (mainHue !== null) {
    ideas.push({ text: "Try a small touch of the opposite color near your focus. It can make the main color glow.", accentHue: (mainHue + 180) % 360 });
    ideas.push({ text: "Try a shifted shadow color instead of a darker one. A cooler or warmer hue adds life.", accentHue: (mainHue + 40) % 360 });
  } else {
    ideas.push({ text: "Try one bold accent color on a neutral image. It can become your focal point.", accentHue: 15 });
  }
  if (coverage > 0.25) {
    ideas.push({ text: "Try a texture pass: add a layer above, tap Clip, and dab with Sponge for surface detail.", brushId: "sponge" });
    ideas.push({ text: "Try Dry brush along an edge to suggest fabric, stone, or wood.", brushId: "dry-brush" });
  } else {
    ideas.push({ text: "Try laying in big shapes with Chalk. Its grain keeps early passes loose.", brushId: "chalk" });
  }
  if (saturation > 0.5) ideas.push({ text: "Try a Wash over part of the image to soften strong colors.", brushId: "wash" });
  if (colored < 0.2) ideas.push({ text: "Try a single Wash color on a new Multiply layer for a limited palette.", brushId: "wash" });
  ideas.push({ text: "Try a new layer in Multiply for shadows, or Additive for glowing light." });
  ideas.push({ text: "Try flipping the canvas. A fresh view can reveal your next idea." });
  return ideas;
}

const ring = (label, x, y) => ({ label, shape: "RING", x1: x, y1: y, x2: MARK_RADIUS, y2: 0 });

const cellRing = (label, cell) => ring(label, (cell % MARK_COLUMNS + 0.5) / MARK_COLUMNS, (Math.floor(cell / MARK_COLUMNS) + 0.5) / MARK_ROWS);

// Marks the visual weight, and where to place something when the image needs balancing.
function compositionMarks(x, y) {
  const marks = [ring("Visual weight", x, y)];
  if (Math.abs(x - 0.5) < 0.06 && Math.abs(y - 0.5) < 0.06) marks.push(ring("Try moving it here", TARGET_FAR - 0.05, 0.38));
  else if (x < 0.22) marks.push(ring("Try an accent here", TARGET_FAR, y));
  else if (x > 0.78) marks.push(ring("Try an accent here", TARGET_NEAR, y));
  else if (y < 0.22) marks.push(ring("Try something here", x, TARGET_FAR));
  else if (y > 0.78) marks.push(ring("Try something here", x, TARGET_NEAR));
  return marks;
}

function silhouetteMarks(left, top, right, bottom, width, height) {
  const share = (right - left + 1) * (bottom - top + 1) / (width * height);
  if (share > WHOLE_CANVAS) return [];
  return [{ label: "Your silhouette", shape: "BOX", x1: left / width, y1: top / height, x2: (right + 1) / width, y2: (bottom + 1) / height }];
}

function directionName(degrees) {
  const normalized = ((degrees % 360) + 360) % 360;
  if (normalized < 45 || normalized >= 315) return "to the right";
  if (normalized < 135) return "below";
  if (normalized < 225) return "to the left";
  return "above";
}

function paletteWhy(hues, colored, saturation) {
  if (colored <= 0) return "None of your marks are strongly colored.";
  const main = Array.from(hues.keys()).sort((a, b) => hues[b] - hues[a]).slice(0, 3).filter((i) => hues[i] > 0);
  const list = main.map((i) => `${HUE_NAMES[i]} ${percent(hues[i] / colored)}`).join(", ");
  return `Your main hues are ${list}. Average strength is ${percent(saturation / colored)}.`;
}

function valueWhy(luminances) {
  const sorted = [...luminances].sort((a, b) => a - b);
  const middle = luminances.filter((v) => v >= 0.35 && v <= 0.65).length / luminances.length;
  return `Your marks run from ${percent(sorted[Math.floor(sorted.length / 10)])} to ${percent(sorted[Math.floor(sorted.length * 9 / 10)])} brightness, and ${percent(middle)} are midtones.`;
}

function stageTip(coverage, colored, marks) {
  if (coverage < 0.12 && colored < marks * 0.1) {
    return { topic: "Next step", text: "This looks like an early sketch. Block in the biggest shapes before adding detail.", action: "BLOCK_IN" };
  }
  if (coverage < 0.4 && colored < marks * 0.1) {
    return { topic: "Next step", text: "The drawing has a clear structure. A simple light and dark pass could give it form next.", action: "SHADOW_LAYER" };
  }
  return null;
}

// Compares where the lighter marks gather with the light direction the artist chose.
function lightTip(chosen, measured, light) {
  if (light <= 0) return null;
  const gap = Math.abs((((measured - chosen + 540) % 360) + 360) % 360 - 180);
  return {
    topic: "Light",
    text: gap < 70 ? "Your lighter areas follow the chosen light direction."
      : "Your lighter areas gather away from the chosen light. Check which side your shadows fall on.",
  };
}

function hueBin(red, green, blue, high, low) {
  const delta = high - low;
  let hue;
  if (high === red) hue = (((green - blue) / delta + 6) % 6);
  else if (high === green) hue = (blue - red) / delta + 2;
  else hue = (red - green) / delta + 4;
  return Math.min(HUE_BINS - 1, Math.max(0, Math.trunc(hue * 60 / 360 * HUE_BINS)));
}

function paletteTip(hues, colored, saturation, marks) {
  const topic = "Palette";
  if (colored < marks * 0.1) return { topic, text: "This is almost neutral. One small accent color could guide the eye." };
  const main = Array.from(hues.keys()).filter((i) => hues[i] >= colored * 0.08).sort((a, b) => hues[b] - hues[a]);
  if (saturation / colored > 0.75 && main.length > 1) {
    return { topic, text: "Most colors are at full strength. Muting the secondary ones lets the focus stand out." };
  }
  if (main.length >= 5) return { topic, text: "There are many competing hues. Try keeping one main hue and two supporting ones." };
  if (main.length <= 1) return { topic, text: "This palette uses one hue family, which feels unified. Vary lightness to add depth." };
  const distance = Math.abs(main[0] - main[1]);
  const gap = Math.min(distance, HUE_BINS - distance);
  if (gap >= 5) {
    return hues[main[1]] > hues[main[0]] * 0.7
      ? { topic, text: "These opposite hues are almost equal in area. Let one dominate so they don't fight." }
      : { topic, text: "This is a complementary palette with a clear main color. It should read with energy." };
  }
  return { topic, text: "These neighboring hues feel harmonious. A small cooler or warmer note could add interest." };
}

function valueTip(luminances) {
  const sorted = [...luminances].sort((a, b) => a - b);
  const low = sorted[Math.floor(sorted.length / 10)];
  const high = sorted[Math.floor(sorted.length * 9 / 10)];
  const middle = luminances.filter((v) => v >= 0.35 && v <= 0.65).length / luminances.length;
  let text;
  if (high - low < 0.2) text = "The marks sit close in value. Stronger darks or lights would separate the shapes.";
  else if (middle > 0.7) text = "Most of this is in the midtones. Try grouping it into clear lights and darks.";
  else text = "The values have a clear range. Check that the strongest contrast is at your focal point.";
  return { topic: "Values", text };
}

function compositionTip(x, y) {
  let text;
  if (Math.abs(x - 0.5) < 0.06 && Math.abs(y - 0.5) < 0.06) {
    text = "The visual weight sits at the center, which feels stable. Shift it toward a third for more movement.";
  } else if (x < 0.22) text = "The weight leans hard to the left. A small accent on the right could balance it.";
  else if (x > 0.78) text = "The weight leans hard to the right. A small accent on the left could balance it.";
  else if (y < 0.22) text = "The weight sits high. Something in the lower area could ground the image.";
  else if (y > 0.78) text = "The weight sits low. Some detail in the upper area could open up the image.";
  else text = "The visual weight is off-center, which helps movement. Check that the eye has a clear path.";
  return { topic: "Composition", text };
}

function silhouetteTip(marks, left, top, right, bottom) {
  const fill = marks / ((right - left + 1) * (bottom - top + 1));
  return {
    topic: "Silhouette",
    text: fill > 0.8 ? "The overall shape is quite solid. Breaking the outline can make it easier to recognize."
      : "Look at the silhouette preview. If the subject still reads in flat black, the shape is working.",
  };
}

// --- Memory, from CompanionMemory.kt ---------------------------------------------------------

export function artStage(coverage) {
  if (coverage < SKETCH_COVERAGE) return "SKETCH";
  if (coverage < BLOCK_IN_COVERAGE) return "BLOCK_IN";
  return "FINISHING";
}

// Puts first what matters now: shapes while sketching, values while blocking in, color at the end.
export function orderForStage(tips, stage) {
  const order = {
    SKETCH: ["Next step", "Silhouette", "Composition", "Values", "Light", "Palette"],
    BLOCK_IN: ["Next step", "Values", "Composition", "Light", "Silhouette", "Palette"],
    FINISHING: ["Palette", "Light", "Values", "Next step", "Composition", "Silhouette"],
  }[stage];
  const rank = (tip) => {
    const index = order.indexOf(tip.topic);
    return index < 0 ? order.length : index;
  };
  return tips.map((tip, index) => [tip, index]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).map(([tip]) => tip);
}

export function progressNote(before, now) {
  if (now.contrast - before.contrast >= CLEARER_VALUES) return "Your values are much clearer than last time. That's real progress.";
  if (now.coverage - before.coverage >= MORE_FINISHED) return "This has come a long way since last time. The big shapes are in.";
  if (now.colored - before.colored >= MORE_FINISHED) return "Color is arriving since last time, and it's starting to feel alive.";
  return null;
}

// A note you turned away stays away, and a topic you keep turning away goes quiet.
export function withoutMuted(tips, mutedNotes, mutedTopics) {
  return tips.filter((tip) => !mutedNotes.has(tip.text) && (mutedTopics[tip.topic] || 0) < MUTE_TOPIC_AFTER);
}

// A small image reads best with a wide range of values. Returns the index of the clearest one.
export function clearestAtASmallSize(reports) {
  if (!reports.length) return -1;
  return indexOfMax(reports.map((report) => report.contrast));
}

export function finishNote(first, now) {
  if (now.contrast - first.contrast >= CLEARER_SINCE_START) return "Finished! Your values are much clearer than when you started this one.";
  if (now.colored - first.colored >= MORE_COLOR_SINCE_START) return "Finished! This began almost without color, and now it really glows.";
  if (now.coverage > first.coverage) return "Finished! You took this from a few marks to a whole piece.";
  return "Finished! Nice work. I'll remember this one.";
}
