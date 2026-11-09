// How each warm-up is laid out, drawn, and read, ported from WarmupLayout.kt, Warmups.kt,
// WarmupForms.kt, and WarmupExtras.kt on Android. Points are shares of the canvas's width and
// height. Keep this in step with the Kotlin until step 3 shares that code with the web.

import { JavaRandom } from "./brushes.js";

const LETTER_ROWS = 3;

// Words with both tall and small letters, so each row practices both heights.
const LETTER_WORDS = [
  "harbor", "quiet", "ember", "lantern", "willow", "drift", "marble", "hollow",
  "copper", "meadow", "velvet", "orchard", "thistle", "biscuit", "kindle", "hearth",
];

const PI = Math.PI;
const GUIDE_GRAY = "#B9B2A8";
const GUIDE_DOT = "#8C7F72";
const GUIDE_LUMINANCE = 0.772;
const FORM_GRAY = "#C6C5C3";
const FORM_LUMINANCE = 0.772;
const SUN_CORE = "#F2B544";

export const SUN_SPOTS = [
  { name: "top left", x: -0.7071, y: -0.7071 },
  { name: "top", x: 0, y: -1 },
  { name: "top right", x: 0.7071, y: -0.7071 },
  { name: "left", x: -1, y: 0 },
  { name: "right", x: 1, y: 0 },
];

const spot = (x, y) => ({ x, y });
const average = (values) => values.reduce((sum, value) => sum + value, 0) / values.length;
const minBy = (items, key) => items.reduce((best, item) => (best === undefined || key(item) < key(best) ? item : best), undefined);
const maxBy = (items, key) => items.reduce((best, item) => (best === undefined || key(item) > key(best) ? item : best), undefined);
const indices = (n) => Array.from({ length: n }, (_, i) => i);
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

// --- Layout, from WarmupLayout.kt ------------------------------------------------------------

// Everything a warm-up places on the canvas, made fresh from one seed each session, so the same
// exercise never repeats exactly.
export class WarmupLayout {
  constructor(seed) {
    this.seed = seed;
    const random = new JavaRandom(seed);
    const between = (low, high) => low + random.nextFloat() * (high - low);
    const pick = (items) => items[random.nextInt(items.length)];
    const boxes = (count, minWide, maxWide, minTall, maxTall, margin = 0.06) => {
      const placed = [];
      let tries = 0;
      while (placed.length < count && tries < 400) {
        tries++;
        const wide = between(minWide, maxWide);
        const tall = between(minTall, maxTall);
        const left = between(margin, 1 - margin - wide);
        const top = between(margin, 1 - margin - tall);
        const box = [left, top, left + wide, top + tall];
        const gap = 0.03;
        if (!placed.some((it) => box[0] < it[2] + gap && it[0] < box[2] + gap && box[1] < it[3] + gap && it[1] < box[3] + gap)) placed.push(box);
      }
      return placed;
    };

    this.dots = [];
    {
      let tries = 0;
      while (this.dots.length < 6 && tries < 600) {
        tries++;
        const start = spot(between(0.14, 0.92), between(0.08, 0.9));
        const angle = between(0, 2 * PI);
        const length = between(0.22, 0.58);
        const end = spot(start.x + Math.cos(angle) * length, start.y + Math.sin(angle) * length);
        if (end.x < 0.14 || end.x > 0.92 || end.y < 0.08 || end.y > 0.9) continue;
        const ends = this.dots.flatMap((pair) => pair);
        if (ends.some((it) => Math.hypot(it.x - start.x, it.y - start.y) < 0.08 || Math.hypot(it.x - end.x, it.y - end.y) < 0.08)) continue;
        this.dots.push([start, end]);
      }
    }
    this.hatchBoxes = boxes(Math.trunc(between(3, 4.99)), 0.14, 0.3, 0.18, 0.42);
    this.ellipseBoxes = boxes(Math.trunc(between(5, 7.99)), 0.1, 0.3, 0.1, 0.32);
    {
      const size = between(0.11, 0.16);
      const gap = between(0.01, 0.03);
      const run = size * 5 + gap * 4;
      const kind = random.nextInt(3);
      if (kind === 0) {
        const left = between(0.05, 0.95 - run);
        const top = between(0.15, 0.85 - size);
        this.valueBoxes = indices(5).map((i) => [left + i * (size + gap), top, left + i * (size + gap) + size, top + size]);
      } else if (kind === 1) {
        const left = between(0.15, 0.85 - size);
        const top = between(0.05, 0.95 - run);
        this.valueBoxes = indices(5).map((i) => [left, top + i * (size + gap), left + size, top + i * (size + gap) + size]);
      } else {
        const left = between(0.05, 0.95 - run);
        const rise = between(0.04, 0.08);
        const top = between(0.1 + rise * 4, 0.9 - size);
        this.valueBoxes = indices(5).map((i) => [left + i * (size + gap), top - i * rise, left + i * (size + gap) + size, top - i * rise + size]);
      }
    }
    {
      const cells = random.shuffled([spot(0.27, 0.27), spot(0.73, 0.27), spot(0.27, 0.73), spot(0.73, 0.73)]);
      const motifs = random.shuffled(TRACE_MOTIFS).slice(0, 4);
      this.tracePaths = motifs.flatMap((motif, index) => {
        const cell = cells[index];
        const scale = between(0.75, 1.05);
        const angle = between(-0.5, 0.5);
        const center = spot(cell.x + between(-0.03, 0.03), cell.y + between(-0.03, 0.03));
        return motif().map((path) => path.map((it) => place(it, center, scale * 0.42, angle)));
      });
    }
    {
      const count = 4 + random.nextInt(3);
      const ground = between(0.72, 0.86);
      const slot = 0.84 / count;
      this.figures = indices(count).map((index) => {
        const scale = between(0.7, 1.15);
        const x = 0.08 + slot * (index + 0.5) + between(-slot * 0.15, slot * 0.15);
        return posedFigure(pick(POSES), x, ground - between(0, 0.08), scale, random.nextBoolean(), random);
      });
    }
    {
      const count = 2 + random.nextInt(3);
      const ground = between(0.6, 0.72);
      const slot = 0.86 / count;
      this.spheres = indices(count).map((index) => {
        const radius = Math.min(slot * 0.36, between(0.07, 0.14));
        return [0.07 + slot * (index + 0.5), ground - radius, radius];
      });
    }
    this.cubeFirst = random.nextBoolean();
    this.cubeSize = between(0.11, 0.16);
    this.cylinderRadius = between(0.08, 0.13);
    this.cylinderTall = between(0.22, 0.34);
    this.colorSphere = [between(0.4, 0.6), between(0.42, 0.55), between(0.18, 0.25)];
    {
      const spots = random.shuffled(SUN_SPOTS);
      this.suns = indices(6).map((i) => spots[i % spots.length]);
    }
    this.sphereSuns = this.spheres.map((_, index) => pick(SUN_SPOTS.filter((sun) =>
      sun.y < -0.5 || (index === 0 && sun.x < -0.9) || (index === this.spheres.length - 1 && sun.x > 0.9))));
    this.faceSun = pick(SUN_SPOTS.filter((it) => it.x !== 0));
    this.lipSun = pick(SUN_SPOTS.filter((it) => it.y < -0.5));
    this.featureScale = between(0.82, 1.08);
    this.featureTilt = between(-0.12, 0.12);
    this.featureShift = spot(between(-0.05, 0.05), between(-0.04, 0.04));
    this.eyeLook = between(-0.05, 0.05);
    this.eyeOpen = between(0.75, 1.15);
    this.noseWidth = between(0.85, 1.2);
    this.lipFullness = between(0.75, 1.25);
    this.farHills = indices(14).map((i) => (i % 2 === 0 ? i / 12 : between(0.26, 0.46)));
    this.nearHills = indices(12).map((i) => (i % 2 === 0 ? i / 10 : between(0.52, 0.62)));
    this.houseX = random.nextBoolean() ? between(0.55, 0.72) : between(0.12, 0.3);
    this.houseWide = between(0.14, 0.2);
    this.houseTop = between(0.6, 0.66);
    this.trees = indices(2 + random.nextInt(3)).map(() => {
      let x;
      do {
        x = between(0.08, 0.9);
      } while (Math.abs(x - (this.houseX + this.houseWide / 2)) < this.houseWide * 0.9);
      return spot(x, between(0.72, 0.84));
    });
    this.moon = spot(this.houseX > 0.5 ? between(0.1, 0.3) : between(0.7, 0.9), between(0.1, 0.18));
    this.horizon = between(0.3, 0.48);
    this.vanishLeft = spot(between(0.03, 0.15), this.horizon);
    this.vanishRight = spot(between(0.85, 0.97), this.horizon);
    {
      const count = 2 + random.nextInt(2);
      const slot = 0.56 / count;
      this.corners = indices(count).map((index) => {
        const x = 0.22 + slot * (index + 0.5) + between(-slot * 0.2, slot * 0.2);
        const above = random.nextInt(3) !== 0;
        const tall = between(0.12, 0.22);
        const top = above ? this.horizon + between(0.08, 0.2) : this.horizon - tall - between(0.06, 0.14);
        return [x, top, x, top + tall];
      });
    }
    {
      const long = between(0.6, 0.8);
      const short = between(0.12, 0.2);
      if (random.nextBoolean()) {
        const left = between(0.08, 0.92 - long);
        const top = between(0.2, 0.8 - short);
        this.blendBox = [left, top, left + long, top + short];
      } else {
        const left = between(0.2, 0.8 - short);
        const top = between(0.08, 0.92 - long);
        this.blendBox = [left, top, left + short, top + long];
      }
    }
    this.blendDarkFirst = random.nextBoolean();
    {
      const knots = indices(6).map((index) => spot(0.5 - between(0.06, 0.28), 0.16 + index * 0.136));
      this.mirrorHalf = indices(101).map((index) => {
        const t = index / 100 * (knots.length - 1);
        const i = Math.min(Math.trunc(t), knots.length - 2);
        const f = t - i;
        const ease = (1 - Math.cos(f * PI)) / 2;
        return spot(knots[i].x + (knots[i + 1].x - knots[i].x) * ease, knots[i].y + (knots[i + 1].y - knots[i].y) * f);
      });
    }
    // Writing lines for lettering, one row per word: left, cap line, x-height, baseline, and right.
    {
      const tall = between(0.12, 0.16);
      const small = tall * between(0.5, 0.62);
      const gap = (0.78 - LETTER_ROWS * tall) / (LETTER_ROWS - 1);
      this.letterRows = indices(LETTER_ROWS).map((index) => {
        const cap = 0.12 + index * (tall + gap);
        return [0.1, cap, cap + tall - small, cap + tall, 0.9];
      });
      // Shuffled the way Collections.shuffle does, so a seed gives the same words as on Android.
      const words = [...LETTER_WORDS];
      for (let i = words.length - 1; i > 0; i--) {
        const j = random.nextInt(i + 1);
        [words[i], words[j]] = [words[j], words[i]];
      }
      this.letterWords = words.slice(0, LETTER_ROWS);
    }
  }
}

function place(point, center, scale, angle) {
  return spot(
    center.x + (point.x * Math.cos(angle) - point.y * Math.sin(angle)) * scale,
    center.y + (point.x * Math.sin(angle) + point.y * Math.cos(angle)) * scale,
  );
}

function motifOval(cx, cy, rx, ry, from = 0, to = 2 * PI, samples = 60) {
  return indices(samples + 1).map((index) => {
    const angle = from + (to - from) * index / samples;
    return spot(cx + rx * Math.cos(angle), cy + ry * Math.sin(angle));
  });
}

const root = (value) => Math.sqrt(Math.max(0, value));

// Shapes to trace, each drawn in its own space from about -0.5 to 0.5.
const TRACE_MOTIFS = [
  () => {
    const length = 0.9;
    const width = 0.24;
    const edge = (sign) => indices(41).map((index) => {
      const t = index / 40;
      return spot((t - 0.5) * length, sign * width * root(Math.sin(t * PI)) * (1 - 0.3 * t));
    });
    const rib = indices(21).map((index) => spot((index / 20 - 0.5) * length * 1.08, 0));
    const veins = [0.3, 0.5, 0.7].flatMap((t) => [1, -1].map((sign) =>
      indices(8).map((index) => spot((t - 0.5) * length + index / 7 * length * 0.16, sign * index / 7 * width * 0.7))));
    return [edge(1), edge(-1), rib, ...veins];
  },
  () => [
    motifOval(0, -0.12, 0.34, 0.08),
    indices(31).map((index) => {
      const angle = PI * index / 30;
      return spot(-0.34 * Math.cos(angle), -0.12 + 0.44 * root(Math.sin(angle)));
    }),
    motifOval(0.36, 0.08, 0.1, 0.13, -1.4, 1.4),
    motifOval(0, 0.36, 0.5, 0.07),
    indices(30).map((index) => { const t = index / 29; return spot(-0.09 + 0.05 * Math.sin(t * 2.2 * PI), -0.26 - 0.24 * t); }),
    indices(30).map((index) => { const t = index / 29; return spot(0.09 + 0.05 * Math.sin(t * 2.2 * PI + 1.3), -0.24 - 0.22 * t); }),
  ],
  () => [indices(160).map((index) => {
    const t = index / 159;
    return spot(-0.48 + 0.96 * t + 0.07 * Math.sin(t * 10 * PI), 0.13 * Math.cos(t * 10 * PI));
  })],
  () => [indices(140).map((index) => {
    const t = index / 139;
    const angle = t * 5 * PI;
    const radius = 0.03 + 0.4 * t;
    return spot(radius * Math.cos(angle), radius * Math.sin(angle));
  })],
  () => [indices(121).map((index) => {
    const t = index / 120;
    const angle = t * 2 * PI;
    const radius = 0.22 + 0.2 * (index % 24 < 12 ? (index % 12) / 12 : 1 - (index % 12) / 12);
    return spot(radius * Math.cos(angle - PI / 2), radius * Math.sin(angle - PI / 2));
  })],
  () => [indices(121).map((index) => {
    const t = index / 120 * 2 * PI;
    return spot(0.026 * 16 * Math.sin(t) ** 3, -0.026 * (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)));
  })],
  () => [indices(160).map((index) => {
    const t = index / 159;
    return spot(-0.48 + 0.96 * t, 0.16 * Math.sin(t * 4 * PI) * (1 - 0.5 * t));
  })],
  () => {
    const petals = 6;
    return [indices(181).map((index) => {
      const angle = index / 180 * 2 * PI;
      const radius = 0.2 + 0.22 * Math.abs(Math.sin(angle * petals / 2));
      return spot(radius * Math.cos(angle), radius * Math.sin(angle));
    }), motifOval(0, 0, 0.09, 0.09)];
  },
];

// One pose: two arms, then two legs, each as its middle joint and its end, with the chest lean.
const POSES = [
  [0.0, [-0.04, 0.45, -0.05, 0.53, 0.04, 0.45, 0.05, 0.53, -0.02, 0.62, -0.025, 0.73, 0.02, 0.62, 0.03, 0.73]],
  [0.015, [-0.035, 0.44, -0.06, 0.5, 0.045, 0.45, 0.07, 0.49, -0.03, 0.62, -0.07, 0.72, 0.05, 0.62, 0.05, 0.73]],
  [-0.01, [-0.035, 0.31, -0.04, 0.22, 0.045, 0.36, 0.07, 0.29, -0.025, 0.62, -0.03, 0.73, 0.025, 0.62, 0.035, 0.73]],
  [0.02, [-0.04, 0.45, -0.02, 0.51, 0.05, 0.44, 0.08, 0.47, -0.04, 0.62, -0.07, 0.66, 0.05, 0.6, 0.05, 0.72]],
  [0.0, [-0.055, 0.34, -0.085, 0.27, 0.055, 0.34, 0.085, 0.27, -0.045, 0.58, -0.02, 0.66, 0.04, 0.6, 0.07, 0.66]],
  [-0.015, [-0.04, 0.46, -0.045, 0.54, 0.06, 0.33, 0.09, 0.26, -0.02, 0.62, -0.03, 0.73, 0.03, 0.62, 0.04, 0.73]],
  [0.03, [-0.03, 0.45, -0.06, 0.53, 0.06, 0.44, 0.09, 0.5, 0.07, 0.56, 0.1, 0.66, 0.08, 0.58, 0.13, 0.62]],
];

// Places a pose standing on ground, at scale, mirrored if flip, with a little wobble in each joint.
function posedFigure([lean, joints], x, ground, scale, flip, random) {
  const sign = flip ? -1 : 1;
  const at = (dx, y) => spot(x + sign * dx * scale, ground - (0.73 - y) * scale);
  const jitter = () => (random.nextFloat() - 0.5) * 0.016;
  const points = [];
  for (let i = 0; i < joints.length; i += 2) points.push(at(joints[i] + jitter(), joints[i + 1] + jitter()));
  const figure = {
    head: at(0, 0.3), neck: at(0, 0.33), chest: at(lean * 0.5, 0.4), hips: at(lean, 0.52),
    shoulders: [at(lean * 0.5 - 0.028, 0.37), at(lean * 0.5 + 0.028, 0.37)],
    hipJoints: [at(lean - 0.018, 0.53), at(lean + 0.018, 0.53)],
    limbs: [[points[0], points[1]], [points[2], points[3]], [points[4], points[5]], [points[6], points[7]]],
    scale,
  };
  const all = [figure.head, figure.chest, figure.hips, ...figure.limbs.flat()];
  figure.box = [Math.min(...all.map((it) => it.x)) - 0.03 * scale, figure.head.y - 0.04 * scale,
    Math.max(...all.map((it) => it.x)) + 0.03 * scale, Math.max(...all.map((it) => it.y)) + 0.02 * scale];
  return figure;
}

// --- Forms to light, from WarmupForms.kt -----------------------------------------------------

function insidePolygon(points, x, y) {
  let result = false;
  let previous = points.length - 2;
  for (let index = 0; index < points.length; index += 2) {
    const xi = points[index];
    const yi = points[index + 1];
    const xj = points[previous];
    const yj = points[previous + 1];
    if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result;
    previous = index;
  }
  return result;
}

function boundsOf(points) {
  const xs = points.filter((_, i) => i % 2 === 0);
  const ys = points.filter((_, i) => i % 2 === 1);
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
}

const plane = (name, normalX, normalY, bounds, contains) => ({ name, normalX, normalY, bounds, contains });

function sphereForm(name, sun, cx, cy, r) {
  const side = (lit) => (x, y) => {
    const dx = (x - cx) / r;
    const dy = (y - cy) / r;
    const toward = dx * sun.x + dy * sun.y;
    return dx * dx + dy * dy < 0.85 && (lit ? toward > 0.3 : toward < -0.3);
  };
  const bounds = [cx - r, cy - r, cx + r, cy + r];
  return {
    name, sun, center: spot(cx, cy), radius: r,
    planes: [plane("lit side", sun.x, sun.y, bounds, side(true)), plane("shadow side", -sun.x, -sun.y, bounds, side(false))],
    outline: (c, width, height) => {
      c.beginPath();
      c.arc(cx * width, cy * height, r * Math.min(width, height), 0, 2 * PI);
      c.fillStyle = FORM_GRAY;
      c.fill();
    },
  };
}

function cubeForm(sun, cx, cy, size) {
  const rise = size * 0.55;
  const side = size * 1.36;
  const top = cy - (rise * 2 + side) / 2;
  const faces = [
    [cx, top, cx + size, top + rise, cx, top + rise * 2, cx - size, top + rise],
    [cx - size, top + rise, cx, top + rise * 2, cx, top + rise * 2 + side, cx - size, top + rise + side],
    [cx, top + rise * 2, cx + size, top + rise, cx + size, top + rise + side, cx, top + rise * 2 + side],
  ];
  const names = ["top face", "left face", "right face"];
  const normals = [[0, -1], [-0.89, 0.45], [0.89, 0.45]];
  return {
    name: "cube", sun, center: spot(cx, cy), radius: size,
    planes: faces.map((face, i) => plane(names[i], normals[i][0], normals[i][1], boundsOf(face), (x, y) => insidePolygon(face, x, y))),
    outline: (c, width, height) => {
      for (const face of faces) {
        c.beginPath();
        c.moveTo(face[0] * width, face[1] * height);
        for (let i = 2; i < face.length; i += 2) c.lineTo(face[i] * width, face[i + 1] * height);
        c.closePath();
        c.fillStyle = FORM_GRAY;
        c.fill();
        c.stroke();
      }
    },
  };
}

function cylinderForm(sun, cx, cy, radius, tall) {
  const tilt = radius * 0.32;
  const top = cy - tall / 2;
  const bottom = cy + tall / 2;
  const inTop = (x, y) => ((x - cx) / radius) ** 2 + ((y - top) / tilt) ** 2 < 0.8;
  const inBody = (x, y) => Math.abs(x - cx) < radius * 0.92 && y > top + tilt * 0.5
    && y < bottom + tilt * root(1 - ((x - cx) / radius) ** 2) - 0.01;
  return {
    name: "cylinder", sun, center: spot(cx, cy), radius,
    planes: [
      plane("top", 0, -1, [cx - radius, top - tilt, cx + radius, top + tilt], inTop),
      plane("left side", -1, 0, [cx - radius, top, cx, bottom + tilt], (x, y) => x < cx - radius * 0.25 && inBody(x, y)),
      plane("right side", 1, 0, [cx, top, cx + radius, bottom + tilt], (x, y) => x > cx + radius * 0.25 && inBody(x, y)),
    ],
    outline: (c, width, height) => {
      const left = (cx - radius) * width;
      const right = (cx + radius) * width;
      c.beginPath();
      c.moveTo(left, top * height);
      c.lineTo(left, bottom * height);
      c.ellipse(cx * width, bottom * height, radius * width, tilt * height, 0, PI, 0, true);
      c.lineTo(right, top * height);
      c.closePath();
      c.fillStyle = FORM_GRAY;
      c.fill();
      c.stroke();
      c.beginPath();
      c.ellipse(cx * width, top * height, radius * width, tilt * height, 0, 0, 2 * PI);
      c.fill();
      c.stroke();
    },
  };
}

export function lightForms(kind, layout) {
  if (kind === "SPHERE_LIGHT") {
    return layout.spheres.map(([cx, cy, r], index) => {
      let name = "middle sphere";
      if (layout.spheres.length === 1) name = "sphere";
      else if (index === 0) name = "left sphere";
      else if (index === layout.spheres.length - 1) name = "right sphere";
      return sphereForm(name, layout.sphereSuns[index], cx, cy, r);
    });
  }
  if (kind === "CUBE_SHADOW") {
    const [cubeX, cylinderX] = layout.cubeFirst ? [0.3, 0.72] : [0.72, 0.3];
    return [cubeForm(layout.suns[0], cubeX, 0.5, layout.cubeSize), cylinderForm(layout.suns[1], cylinderX, 0.5, layout.cylinderRadius, layout.cylinderTall)];
  }
  if (kind === "WARM_COOL" || kind === "HUE_SHIFT") {
    const [cx, cy, r] = layout.colorSphere;
    return [sphereForm("sphere", layout.suns[0], cx, cy, r)];
  }
  return [];
}

export const featureSun = (kind, layout) => (kind === "FACE_LIPS" ? layout.lipSun : layout.faceSun);

// The one sun lighting the whole exercise, when there is only one.
export function singleSun(kind, layout) {
  if (kind === "WARM_COOL" || kind === "HUE_SHIFT") return layout.suns[0];
  if (kind === "FACE_EYE" || kind === "FACE_NOSE" || kind === "FACE_LIPS") return featureSun(kind, layout);
  return null;
}

export const usesSun = (kind) => ["SPHERE_LIGHT", "CUBE_SHADOW", "WARM_COOL", "HUE_SHIFT", "FACE_EYE", "FACE_NOSE", "FACE_LIPS"].includes(kind);

// A soft yellow sun: a warm core that fades into the paper.
function drawSun(c, x, y, radius) {
  const glow = c.createRadialGradient(x, y, 0, x, y, radius * 2.4);
  glow.addColorStop(0, SUN_CORE);
  glow.addColorStop(0.42, "rgba(242, 181, 68, 0.4)");
  glow.addColorStop(1, "rgba(242, 181, 68, 0)");
  c.fillStyle = glow;
  c.beginPath();
  c.arc(x, y, radius * 2.4, 0, 2 * PI);
  c.fill();
  c.fillStyle = SUN_CORE;
  c.beginPath();
  c.arc(x, y, radius, 0, 2 * PI);
  c.fill();
}

function drawLightForms(c, forms, width, height) {
  const side = Math.min(width, height);
  const lineWidth = c.lineWidth;
  for (const form of forms) {
    const floor = Math.max(...form.planes.map((it) => it.bounds[3]));
    c.lineWidth = side * 0.003;
    c.beginPath();
    c.moveTo((form.center.x - form.radius * 1.8) * width, floor * height);
    c.lineTo((form.center.x + form.radius * 1.8) * width, floor * height);
    c.stroke();
    c.lineWidth = lineWidth;
    form.outline(c, width, height);
    const reach = form.radius * 1.45 + 0.035;
    const sunX = clamp(form.center.x + form.sun.x * reach, 0.05, 0.95);
    const sunY = clamp(form.center.y + form.sun.y * reach * width / height, 0.05, 0.95);
    if (forms.length > 1) {
      // A faint ray ties each sun to its own form.
      c.save();
      c.lineWidth = side * 0.002;
      c.setLineDash([side * 0.008, side * 0.01]);
      c.globalAlpha = 150 / 255;
      const edge = form.radius * 1.08;
      c.beginPath();
      c.moveTo(sunX * width, sunY * height);
      c.lineTo((form.center.x + form.sun.x * edge) * width, (form.center.y + form.sun.y * edge * width / height) * height);
      c.stroke();
      c.restore();
    }
    drawSun(c, sunX * width, sunY * height, side * (forms.length > 1 ? 0.018 : 0.026));
  }
}

// Points along a smooth curve through the points, for drawing and comparing with your line.
function smooth(points, samples = 16) {
  const result = [];
  for (let index = 0; index < points.length - 1; index++) {
    const p0 = points[Math.max(0, index - 1)];
    const p1 = points[index];
    const p2 = points[index + 1];
    const p3 = points[Math.min(points.length - 1, index + 2)];
    for (let step = 0; step < samples; step++) {
      const t = step / samples;
      const t2 = t * t;
      const t3 = t2 * t;
      const blend = (a, b, c, d) => 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
      result.push(spot(blend(p0.x, p1.x, p2.x, p3.x), blend(p0.y, p1.y, p2.y, p3.y)));
    }
  }
  result.push(points[points.length - 1]);
  return result;
}

function ringPoints(cx, cy, rx, ry, samples = 48) {
  return indices(samples + 1).map((index) => {
    const angle = index * 2 * PI / samples;
    return spot(cx + rx * Math.cos(angle), cy + ry * Math.sin(angle));
  });
}

const polygon = (points) => points.flatMap((it) => [it.x, it.y]);

function halves(bounds, sun, inside) {
  const cx = (bounds[0] + bounds[2]) / 2;
  const cy = (bounds[1] + bounds[3]) / 2;
  const toward = (x, y) => (x - cx) * sun.x + (y - cy) * sun.y;
  return [
    plane("lit side", sun.x, sun.y, bounds, (x, y) => inside(x, y) && toward(x, y) > 0.02),
    plane("shadow side", -sun.x, -sun.y, bounds, (x, y) => inside(x, y) && toward(x, y) < -0.02),
  ];
}

function eyeFeature(sun, look, open) {
  const lid = (y) => 0.52 + (y - 0.52) * open;
  const upper = smooth([spot(0.2, 0.53), spot(0.34, lid(0.41)), spot(0.52, lid(0.37)), spot(0.68, lid(0.42)), spot(0.8, 0.51)]);
  const lower = smooth([spot(0.2, 0.53), spot(0.36, lid(0.6)), spot(0.54, lid(0.62)), spot(0.7, lid(0.58)), spot(0.8, 0.51)]);
  const crease = smooth([spot(0.27, 0.42), spot(0.42, 0.31), spot(0.6, 0.3), spot(0.76, 0.4)]);
  const brow = smooth([spot(0.16, 0.32), spot(0.34, 0.2), spot(0.58, 0.18), spot(0.84, 0.27)]);
  const almond = polygon([...upper, ...[...lower].reverse()]);
  const [lit, shade] = halves(boundsOf(almond), sun, (x, y) => insidePolygon(almond, x, y));
  const iris = 0.5 + look;
  return {
    contours: [upper, lower, crease, brow, ringPoints(iris, 0.49, 0.1 * Math.min(1, open), 0.1 * Math.min(1, open)), ringPoints(iris, 0.49, 0.04, 0.04)],
    lit, shade,
  };
}

function noseFeature(sun, width) {
  const w = (x) => 0.5 + (x - 0.5) * width;
  const left = smooth([spot(0.45, 0.16), spot(0.44, 0.36), spot(0.42, 0.56)]);
  const right = smooth([spot(0.55, 0.16), spot(0.56, 0.36), spot(0.58, 0.56)]);
  const ball = ringPoints(0.5, 0.64, 0.075 * width, 0.07);
  const wingLeft = smooth([spot(w(0.43), 0.6), spot(w(0.35), 0.64), spot(w(0.36), 0.73), spot(w(0.44), 0.74)]);
  const wingRight = smooth([spot(w(0.57), 0.6), spot(w(0.65), 0.64), spot(w(0.64), 0.73), spot(w(0.56), 0.74)]);
  const nostrils = [ringPoints(w(0.455), 0.735, 0.022, 0.011), ringPoints(w(0.545), 0.735, 0.022, 0.011)];
  const outline = polygon([...left, ...wingLeft, ...[...wingRight].reverse(), ...[...right].reverse()]);
  const bounds = [0.33, 0.14, 0.67, 0.78];
  const [lit, shade] = halves(bounds, sun, (x, y) => x >= 0.33 && x <= 0.67 && y >= 0.14 && y <= 0.78
    && (insidePolygon(outline, x, y) || Math.hypot(x - 0.5, y - 0.64) < 0.08));
  return { contours: [left, right, ball, wingLeft, wingRight, ...nostrils], lit, shade };
}

function lipsFeature(full) {
  const f = (y) => 0.53 + (y - 0.53) * full;
  const upper = smooth([spot(0.22, 0.53), spot(0.36, f(0.45)), spot(0.46, f(0.42)), spot(0.5, f(0.445)), spot(0.54, f(0.42)), spot(0.64, f(0.45)), spot(0.78, 0.53)]);
  const middle = smooth([spot(0.22, 0.53), spot(0.36, 0.535), spot(0.5, 0.555), spot(0.64, 0.535), spot(0.78, 0.53)]);
  const lower = smooth([spot(0.22, 0.53), spot(0.34, f(0.62)), spot(0.5, f(0.66)), spot(0.66, f(0.62)), spot(0.78, 0.53)]);
  const philtrum = [smooth([spot(0.478, 0.425), spot(0.472, 0.37)]), smooth([spot(0.522, 0.425), spot(0.528, 0.37)])];
  const top = polygon([...upper, ...[...middle].reverse()]);
  const bottom = polygon([...middle, ...[...lower].reverse()]);
  return {
    contours: [upper, middle, lower, ...philtrum],
    lit: plane("lower lip", 0, -1, boundsOf(bottom), (x, y) => insidePolygon(bottom, x, y)),
    shade: plane("upper lip", 0, 1, boundsOf(top), (x, y) => insidePolygon(top, x, y)),
  };
}

export function feature(kind, layout) {
  const sun = featureSun(kind, layout);
  let base;
  if (kind === "FACE_EYE") base = eyeFeature(sun, layout.eyeLook, layout.eyeOpen);
  else if (kind === "FACE_NOSE") base = noseFeature(sun, layout.noseWidth);
  else if (kind === "FACE_LIPS") base = lipsFeature(layout.lipFullness);
  else return null;
  return turned(base, layout.featureScale, layout.featureTilt, layout.featureShift);
}

// The same feature, sized, tilted, and moved a little, so it looks new each session.
function turned(base, scale, tilt, shift) {
  const move = (it) => {
    const dx = (it.x - 0.5) * scale;
    const dy = (it.y - 0.5) * scale;
    return spot(0.5 + shift.x + dx * Math.cos(tilt) - dy * Math.sin(tilt), 0.5 + shift.y + dx * Math.sin(tilt) + dy * Math.cos(tilt));
  };
  const back = (x, y) => {
    const dx = x - 0.5 - shift.x;
    const dy = y - 0.5 - shift.y;
    return spot(0.5 + (dx * Math.cos(tilt) + dy * Math.sin(tilt)) / scale, 0.5 + (-dx * Math.sin(tilt) + dy * Math.cos(tilt)) / scale);
  };
  const movePlane = (p) => {
    const corners = [spot(p.bounds[0], p.bounds[1]), spot(p.bounds[2], p.bounds[1]), spot(p.bounds[2], p.bounds[3]), spot(p.bounds[0], p.bounds[3])].map(move);
    const bounds = [Math.min(...corners.map((it) => it.x)), Math.min(...corners.map((it) => it.y)), Math.max(...corners.map((it) => it.x)), Math.max(...corners.map((it) => it.y))];
    return plane(p.name, p.normalX, p.normalY, bounds, (x, y) => {
      const point = back(x, y);
      return p.contains(point.x, point.y);
    });
  };
  return { contours: base.contours.map((points) => points.map(move)), lit: movePlane(base.lit), shade: movePlane(base.shade) };
}

function drawFeature(c, shape, sun, width, height) {
  for (const points of shape.contours) {
    c.beginPath();
    points.forEach((it, index) => (index === 0 ? c.moveTo(it.x * width, it.y * height) : c.lineTo(it.x * width, it.y * height)));
    c.stroke();
  }
  const sunX = clamp(0.5 + sun.x * 0.4, 0.07, 0.93);
  const sunY = clamp(0.5 + sun.y * 0.4, 0.07, 0.93);
  drawSun(c, sunX * width, sunY * height, Math.min(width, height) * 0.024);
}

// --- Guides, from drawWarmupGuide() and drawExtraGuide() ---------------------------------------

// Paints the exercise's starting point onto a 2D context, such as dots to join or a sphere to light.
export function drawWarmupGuide(c, kind, width, height, layout) {
  const side = Math.min(width, height);
  c.lineCap = "round";
  c.lineJoin = "round";
  c.lineWidth = side * 0.004;
  c.strokeStyle = GUIDE_GRAY;
  const x = (value) => value * width;
  const y = (value) => value * height;
  const rect = (box) => c.strokeRect(x(box[0]), y(box[1]), x(box[2]) - x(box[0]), y(box[3]) - y(box[1]));
  const dot = (cx, cy, radius, color) => {
    c.fillStyle = color;
    c.beginPath();
    c.arc(cx, cy, radius, 0, 2 * PI);
    c.fill();
  };
  const line = (x1, y1, x2, y2) => {
    c.beginPath();
    c.moveTo(x1, y1);
    c.lineTo(x2, y2);
    c.stroke();
  };
  const closed = (points) => {
    c.beginPath();
    c.moveTo(x(points[0]), y(points[1]));
    for (let i = 2; i < points.length; i += 2) c.lineTo(x(points[i]), y(points[i + 1]));
    c.closePath();
    c.stroke();
  };
  switch (kind) {
    case "STRAIGHT_LINES":
      for (const [from, to] of layout.dots) {
        dot(x(from.x), y(from.y), side * 0.01, GUIDE_DOT);
        dot(x(to.x), y(to.y), side * 0.01, GUIDE_DOT);
      }
      break;
    case "HATCHING": layout.hatchBoxes.forEach(rect); break;
    case "VALUE_SCALE": layout.valueBoxes.forEach(rect); break;
    case "ELLIPSES": layout.ellipseBoxes.forEach(rect); break;
    case "TRACING":
      c.lineWidth = side * 0.006;
      c.strokeStyle = "#D3CDC4";
      for (const points of layout.tracePaths) {
        c.beginPath();
        points.forEach((it, index) => (index === 0 ? c.moveTo(x(it.x), y(it.y)) : c.lineTo(x(it.x), y(it.y))));
        c.stroke();
      }
      break;
    case "SPHERE_LIGHT": case "CUBE_SHADOW": case "WARM_COOL": case "HUE_SHIFT":
      drawLightForms(c, lightForms(kind, layout), width, height);
      break;
    case "FACE_EYE": case "FACE_NOSE": case "FACE_LIPS": {
      c.lineWidth = side * 0.005;
      c.strokeStyle = "#C9C2B8";
      const shape = feature(kind, layout);
      if (shape) drawFeature(c, shape, featureSun(kind, layout), width, height);
      break;
    }
    case "PERSPECTIVE":
      c.lineWidth = side * 0.003;
      line(0, y(layout.horizon), width, y(layout.horizon));
      for (const it of [layout.vanishLeft, layout.vanishRight]) dot(x(it.x), y(it.y), side * 0.011, GUIDE_DOT);
      c.lineWidth = side * 0.005;
      for (const corner of layout.corners) line(x(corner[0]), y(corner[1]), x(corner[2]), y(corner[3]));
      break;
    case "GRADIENT": {
      const box = layout.blendBox;
      rect(box);
      const horizontal = box[2] - box[0] > box[3] - box[1];
      const ends = horizontal
        ? [spot(box[0] - 0.03, (box[1] + box[3]) / 2), spot(box[2] + 0.03, (box[1] + box[3]) / 2)]
        : [spot((box[0] + box[2]) / 2, box[1] - 0.03), spot((box[0] + box[2]) / 2, box[3] + 0.03)];
      const [dark, light] = layout.blendDarkFirst ? ends : [ends[1], ends[0]];
      dot(x(dark.x), y(dark.y), side * 0.012, "#2A1D16");
      c.lineWidth = side * 0.003;
      c.beginPath();
      c.arc(x(light.x), y(light.y), side * 0.012, 0, 2 * PI);
      c.stroke();
      break;
    }
    case "LETTERING":
      layout.letterRows.forEach((row, index) => {
        c.save();
        c.lineWidth = side * 0.003;
        line(x(row[0]), y(row[1]), x(row[4]), y(row[1]));
        c.setLineDash([side * 0.012, side * 0.01]);
        line(x(row[0]), y(row[2]), x(row[4]), y(row[2]));
        c.setLineDash([]);
        c.lineWidth = side * 0.005;
        line(x(row[0]), y(row[3]), x(row[4]), y(row[3]));
        c.fillStyle = "#9A8F84";
        c.font = `${side * 0.03}px Georgia, serif`;
        c.fillText(layout.letterWords[index], x(row[0]), y(row[1]) - side * 0.012);
        c.restore();
      });
      break;
    case "MIRROR":
      c.save();
      c.lineWidth = side * 0.003;
      c.setLineDash([side * 0.015, side * 0.012]);
      line(x(0.5), y(0.08), x(0.5), y(0.92));
      c.restore();
      c.lineWidth = side * 0.006;
      c.strokeStyle = "#B9B2A8";
      c.beginPath();
      layout.mirrorHalf.forEach((it, index) => (index === 0 ? c.moveTo(x(it.x), y(it.y)) : c.lineTo(x(it.x), y(it.y))));
      c.stroke();
      break;
    case "SILHOUETTES":
      for (const figure of layout.figures) {
        c.lineWidth = side * 0.008 * figure.scale;
        c.strokeStyle = "#CFC8BE";
        const bone = (from, to) => line(x(from.x), y(from.y), x(to.x), y(to.y));
        const starts = [...figure.shoulders, ...figure.hipJoints];
        figure.limbs.forEach(([middle, end], index) => {
          bone(starts[index], middle);
          bone(middle, end);
          dot(x(middle.x), y(middle.y), side * 0.006 * figure.scale, "#B9B2A8");
        });
        bone(figure.neck, figure.chest);
        bone(figure.chest, figure.hips);
        const ellipse = (center, rx, ry) => {
          c.beginPath();
          c.ellipse(x(center.x), y(center.y), rx * figure.scale * width, ry * figure.scale * height, 0, 0, 2 * PI);
          c.stroke();
        };
        ellipse(figure.head, 0.022, 0.03);
        ellipse(figure.chest, 0.026, 0.042);
        ellipse(figure.hips, 0.024, 0.018);
      }
      break;
    case "THREE_VALUES": case "ONE_HUE": {
      const ridge = (points) => {
        c.beginPath();
        c.moveTo(x(points[0]), y(points[1]));
        for (let i = 2; i < points.length; i += 2) {
          const midX = (points[i - 2] + points[i]) / 2;
          const midY = (points[i - 1] + points[i + 1]) / 2;
          c.quadraticCurveTo(x(points[i - 2]), y(points[i - 1]), x(midX), y(midY));
        }
        c.lineTo(x(points[points.length - 2]), y(points[points.length - 1]));
        c.stroke();
      };
      ridge(layout.farHills);
      ridge(layout.nearHills);
      c.beginPath();
      c.arc(x(layout.moon.x), y(layout.moon.y), side * 0.045, 0, 2 * PI);
      c.stroke();
      const left = layout.houseX;
      const right = left + layout.houseWide;
      const top = layout.houseTop;
      const bottom = top + layout.houseWide * 0.75;
      const middle = (left + right) / 2;
      rect([left, top, right, bottom]);
      closed([left - 0.02, top, middle, top - layout.houseWide * 0.5, right + 0.02, top]);
      rect([middle - 0.015, bottom - 0.08, middle + 0.015, bottom]);
      rect([right - 0.05, top + 0.03, right - 0.015, top + 0.065]);
      for (const tree of layout.trees) {
        closed([tree.x, tree.y - 0.2, tree.x + 0.05, tree.y - 0.03, tree.x - 0.05, tree.y - 0.03]);
        line(x(tree.x), y(tree.y - 0.03), x(tree.x), y(tree.y));
      }
      closed([middle - 0.07, 1.0, middle, 1.0, middle + 0.015, bottom, middle - 0.015, bottom]);
      break;
    }
    default:
      break;
  }
}

// --- Reading the work, from Warmups.kt ---------------------------------------------------------

// What you drew: strokes as flat x, y arrays in canvas shares, and a small RGBA image.
export class WarmupWork {
  constructor(strokes, pixels, width, height, layout) {
    this.strokes = strokes;
    this.pixels = pixels;
    this.width = width;
    this.height = height;
    this.layout = layout;
    this.sun = layout.suns[0];
  }

  luminance(x, y) {
    const i = (y * this.width + x) * 4;
    return (0.2126 * this.pixels[i] + 0.7152 * this.pixels[i + 1] + 0.0722 * this.pixels[i + 2]) / 255;
  }

  red(x, y) {
    return this.pixels[(y * this.width + x) * 4] / 255;
  }

  blue(x, y) {
    return this.pixels[(y * this.width + x) * 4 + 2] / 255;
  }

  color(x, y) {
    const i = (y * this.width + x) * 4;
    return [this.pixels[i], this.pixels[i + 1], this.pixels[i + 2]];
  }
}

const strokeLength = (stroke) => {
  let total = 0;
  for (let i = 2; i < stroke.length; i += 2) total += Math.hypot(stroke[i] - stroke[i - 2], stroke[i + 1] - stroke[i - 1]);
  return total;
};

// How far a stroke strays from the straight line between its ends, as a share of that line.
function wobble(stroke) {
  const ax = stroke[0];
  const ay = stroke[1];
  const bx = stroke[stroke.length - 2];
  const by = stroke[stroke.length - 1];
  const chord = Math.hypot(bx - ax, by - ay);
  if (chord < 1e-4) return 1;
  let worst = 0;
  for (let i = 0; i < stroke.length; i += 2) {
    worst = Math.max(worst, Math.abs((bx - ax) * (ay - stroke[i + 1]) - (ax - stroke[i]) * (by - ay)) / chord);
  }
  return worst / chord;
}

const longStrokes = (strokes, shortest = 0.03) => strokes.filter((it) => it.length >= 4 && strokeLength(it) >= shortest);
const xsOf = (stroke) => stroke.filter((_, i) => i % 2 === 0);
const ysOf = (stroke) => stroke.filter((_, i) => i % 2 === 1);
const pointsOf = (stroke) => indices(stroke.length / 2).map((i) => spot(stroke[i * 2], stroke[i * 2 + 1]));
const lineAngle = (it) => (Math.atan2(it[it.length - 1] - it[1], it[it.length - 2] - it[0]) + PI) % PI;
const angleOff = (angle, mean) => Math.abs((((angle - mean) % PI) + 1.5 * PI) % PI - PI / 2);

function lineFeedback(strokes, pairs) {
  const lines = longStrokes(strokes);
  if (!lines.length) return "Join a few dots first, then ask me again.";
  const bend = average(lines.map(wobble));
  const dots = pairs.flat();
  const miss = (x, y) => Math.min(...dots.map((it) => Math.hypot(it.x - x, it.y - y)));
  const landing = average(lines.map((it) => (miss(it[0], it[1]) + miss(it[it.length - 2], it[it.length - 1])) / 2)) * 100;
  const straight = bend < 0.012 ? "Your lines are really straight."
    : bend < 0.03 ? "Your lines are mostly straight, with a little wobble."
      : "Your lines wobble. Try a faster, more confident stroke.";
  const aim = landing < 1.2 ? "You land right on the dots."
    : landing < 3 ? "You land close to the dots. Look at the end dot, not the pen, as you go."
      : "Your ends miss the dots. Hover over the path once, then commit.";
  return `${straight} ${aim}`;
}

function hatchingFeedback(strokes) {
  const lines = longStrokes(strokes, 0.02);
  if (lines.length < 4) return "Draw at least four hatching lines, then ask me again.";
  const angles = lines.map(lineAngle);
  const mean = Math.atan2(angles.reduce((s, a) => s + Math.sin(2 * a), 0), angles.reduce((s, a) => s + Math.cos(2 * a), 0)) / 2;
  const spread = Math.sqrt(average(angles.map((a) => angleOff(a, mean) ** 2))) * 180 / PI;
  const normalX = -Math.sin(mean);
  const normalY = Math.cos(mean);
  const offsets = lines.map((it) => (it[0] + it[it.length - 2]) / 2 * normalX + (it[1] + it[it.length - 1]) / 2 * normalY).sort((a, b) => a - b);
  const gaps = offsets.slice(1).map((value, i) => value - offsets[i]).filter((it) => it > 0.002);
  const gapMean = gaps.length ? average(gaps) : 0;
  const gapSpread = gaps.length < 2 || gapMean <= 0 ? 0 : Math.sqrt(average(gaps.map((it) => (it - gapMean) ** 2))) / gapMean;
  const angle = spread < 4 ? "Your lines keep the same angle beautifully."
    : spread < 9 ? "The angle drifts a little between lines."
      : "The angle changes a lot. Turn the canvas so the lines go in your most comfortable direction.";
  const spacing = gapSpread < 0.35 ? "The spacing is nice and even."
    : gapSpread < 0.7 ? "The spacing is a bit uneven. Use the last line as your ruler for the next."
      : "The spacing jumps around. Slow down and measure each gap by eye.";
  return `${angle} ${spacing}`;
}

// How evenly a loop follows its own best-fitting ellipse, where 0 is perfect.
function ellipseUnevenness(stroke) {
  const count = stroke.length / 2;
  let cx = 0;
  let cy = 0;
  for (let i = 0; i < count; i++) {
    cx += stroke[i * 2];
    cy += stroke[i * 2 + 1];
  }
  cx /= count;
  cy /= count;
  let xx = 0;
  let xy = 0;
  let yy = 0;
  for (let i = 0; i < count; i++) {
    const dx = stroke[i * 2] - cx;
    const dy = stroke[i * 2 + 1] - cy;
    xx += dx * dx;
    xy += dx * dy;
    yy += dy * dy;
  }
  const angle = 0.5 * Math.atan2(2 * xy, xx - yy);
  const cosine = Math.cos(angle);
  const sine = Math.sin(angle);
  let major = 0;
  let minor = 0;
  for (let i = 0; i < count; i++) {
    const dx = stroke[i * 2] - cx;
    const dy = stroke[i * 2 + 1] - cy;
    major += (dx * cosine + dy * sine) ** 2;
    minor += (-dx * sine + dy * cosine) ** 2;
  }
  major = Math.sqrt(major / count);
  minor = Math.sqrt(minor / count);
  if (major < 1e-5 || minor < 1e-5) return 1;
  const radii = indices(count).map((i) => {
    const dx = stroke[i * 2] - cx;
    const dy = stroke[i * 2 + 1] - cy;
    return Math.hypot((dx * cosine + dy * sine) / major, (-dx * sine + dy * cosine) / minor);
  });
  const mean = average(radii);
  return Math.sqrt(average(radii.map((it) => (it - mean) ** 2))) / mean;
}

// The loop drawn in each box, if any, chosen by where its middle falls.
function loopsInBoxes(strokes, boxes) {
  const loops = longStrokes(strokes, 0.05);
  return boxes.map((box) => {
    const inside = loops.filter((loop) => {
      const cx = average(xsOf(loop));
      const cy = average(ysOf(loop));
      return cx >= box[0] && cx <= box[2] && cy >= box[1] && cy <= box[3];
    });
    return inside.length ? inside[inside.length - 1] : null;
  });
}

// How far a loop's outer edges sit from its box's sides, as a share of the box.
function boxMiss(loop, box) {
  const xs = xsOf(loop);
  const ys = ysOf(loop);
  const width = box[2] - box[0];
  const height = box[3] - box[1];
  return average([Math.abs(Math.min(...xs) - box[0]) / width, Math.abs(Math.max(...xs) - box[2]) / width,
    Math.abs(Math.min(...ys) - box[1]) / height, Math.abs(Math.max(...ys) - box[3]) / height]);
}

function ellipseFeedback(strokes, boxes) {
  const placed = loopsInBoxes(strokes, boxes);
  const loops = placed.filter(Boolean);
  if (!loops.length) return "Draw an ellipse in one of the boxes first, then ask me again.";
  const gap = average(loops.map((it) => Math.hypot(it[0] - it[it.length - 2], it[1] - it[it.length - 1]) / strokeLength(it)));
  const uneven = average(loops.map(ellipseUnevenness));
  const miss = average(loops.map((loop) => boxMiss(loop, boxes[placed.indexOf(loop)])));
  const shape = uneven < 0.06 ? "Your ellipses are smooth and even."
    : uneven < 0.13 ? "Your ellipses are fairly smooth, with a few flat spots."
      : "Your ellipses get lumpy. Draw a couple of ghost loops in the air before touching down.";
  const fit = miss < 0.06 ? "They touch the sides of their boxes nicely."
    : miss < 0.14 ? "Some fall short of the box sides. Aim for all four edges."
      : "They float inside the boxes. Let each one touch all four sides.";
  const close = gap >= 0.05 ? " Some stay open. Overlap the end with the start." : "";
  const left = placed.filter((it) => it === null).length;
  const rest = left > 0 ? ` ${left} boxes to go.` : "";
  return `${shape} ${fit}${close}${rest}`;
}

function tracingFeedback(strokes, paths) {
  const traced = longStrokes(strokes, 0.02);
  if (!traced.length) return "Trace one of the gray paths first, then ask me again.";
  const guide = paths.flat();
  const distances = traced.flatMap((stroke) => pointsOf(stroke).map((point) => Math.min(...guide.map((it) => Math.hypot(it.x - point.x, it.y - point.y)))));
  const close = distances.filter((it) => it < 0.008).length / distances.length * 100;
  if (close >= 85) return `Very precise: ${Math.trunc(close)}% of your line sits right on the path.`;
  if (close >= 55) return `${Math.trunc(close)}% of your line sits on the path. Slow down on the tight turns.`;
  return `Only ${Math.trunc(close)}% of your line sits on the path. Go slower, and lift the pen to reset when you drift.`;
}

// Dark shapes in the image, each as the cells it covers on a coarse grid.
function darkShapes(work, grid = 64) {
  const dark = indices(grid * grid).map((cell) => work.luminance(Math.floor((cell % grid) * work.width / grid), Math.floor(Math.floor(cell / grid) * work.height / grid)) < 0.35);
  const seen = new Array(dark.length).fill(false);
  const shapes = [];
  for (let start = 0; start < dark.length; start++) {
    if (!dark[start] || seen[start]) continue;
    const cells = [];
    const queue = [start];
    seen[start] = true;
    while (queue.length) {
      const cell = queue.shift();
      cells.push(cell);
      const x = cell % grid;
      const y = Math.floor(cell / grid);
      for (const [nx, ny] of [[x - 1, y], [x + 1, y], [x, y - 1], [x, y + 1]]) {
        if (nx < 0 || nx >= grid || ny < 0 || ny >= grid) continue;
        const next = ny * grid + nx;
        if (dark[next] && !seen[next]) {
          seen[next] = true;
          queue.push(next);
        }
      }
    }
    if (cells.length >= 6) shapes.push(cells);
  }
  return shapes;
}

function meanIn(work, inside, value) {
  let total = 0;
  let count = 0;
  for (let y = 0; y < work.height; y += 2) {
    for (let x = 0; x < work.width; x += 2) {
      if (!inside(x / work.width, y / work.height)) continue;
      total += value(x, y);
      count++;
    }
  }
  return count === 0 ? null : total / count;
}

const within = (box, x, y) => x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3];

function figureFill(work) {
  return work.layout.figures.map((figure) => meanIn(work, (x, y) => within(figure.box, x, y), (x, y) => (work.luminance(x, y) < 0.35 ? 1 : 0)) ?? 0);
}

function silhouetteFeedback(work) {
  const fill = figureFill(work);
  const done = fill.filter((it) => it > 0.22).length;
  if (fill.every((it) => it < 0.06)) return "Fill one of the stick figures in solid black first, then ask me again.";
  const count = done < fill.length ? `${done} of ${fill.length} figures are filled.` : "Every figure is filled.";
  const shapes = darkShapes(work);
  let read = "";
  if (shapes.length) {
    const solid = average(shapes.map((cells) => {
      const xs = cells.map((it) => it % 64);
      const ys = cells.map((it) => Math.floor(it / 64));
      return cells.length / ((Math.max(...xs) - Math.min(...xs) + 1) * (Math.max(...ys) - Math.min(...ys) + 1));
    }));
    if (solid > 0.8) read = "They're quite boxy. Keep gaps between the limbs and the body, so each pose reads at a glance.";
    else if (solid > 0.35) read = "They have clear, readable outlines.";
    else read = "They're thin or broken up. Fill them in fully so the outline does the talking.";
  }
  return `${count} ${read}`.trim();
}

// True inside the sphere on the side toward the light, or away from it when lit is false.
function sphereSide(work, lit) {
  const [cx, cy, r] = work.layout.colorSphere;
  const radiusX = r * Math.min(work.width, work.height) / work.width;
  const radiusY = r * Math.min(work.width, work.height) / work.height;
  return (x, y) => {
    const dx = (x - cx) / radiusX;
    const dy = (y - cy) / radiusY;
    const toward = dx * work.sun.x + dy * work.sun.y;
    return dx * dx + dy * dy < 0.85 && (lit ? toward > 0.3 : toward < -0.3);
  };
}

function valueBands(work) {
  const bins = new Array(8).fill(0);
  let total = 0;
  for (let y = 0; y < work.height; y += 2) {
    for (let x = 0; x < work.width; x += 2) {
      bins[Math.min(7, Math.trunc(work.luminance(x, y) * 8))]++;
      total++;
    }
  }
  return bins.filter((it) => it > total * 0.04).length;
}

function threeValueFeedback(work) {
  const bands = valueBands(work);
  if (bands <= 1) return "Paint your scene first, then ask me again.";
  if (bands === 2) return "I see two values. Add a middle one to connect the light and the dark.";
  if (bands === 3) return "Exactly three values. Squint: the big shapes should read clearly.";
  return `I see ${bands} values. Merge the close ones, so only three remain.`;
}

const boxValues = (work) => work.layout.valueBoxes.map((box) => meanIn(work,
  (x, y) => x >= box[0] + 0.02 && x <= box[2] - 0.02 && y >= box[1] + 0.02 && y <= box[3] - 0.02, (x, y) => work.luminance(x, y)) ?? 1);

function valueScaleFeedback(work) {
  const boxes = boxValues(work);
  if (boxes.every((it) => it > 0.95)) return "Fill the boxes first, then ask me again.";
  const steps = boxes.slice(1).map((value, i) => boxes[i] - value);
  if (steps.some((it) => it < 0.02)) return "Some neighbors are too close or out of order. Each box should be a clear step darker.";
  const mean = average(steps);
  const spread = Math.sqrt(average(steps.map((it) => (it - mean) ** 2))) / mean;
  if (spread < 0.3) return "Smooth, even steps from light to dark. That's a great scale.";
  if (spread < 0.6) return "The scale goes the right way, but the steps vary. Compare each box with both neighbors.";
  return "The order is right, but some jumps are much bigger. Even them out.";
}

function hueOf([red, green, blue]) {
  const r = red / 255;
  const g = green / 255;
  const b = blue / 255;
  const high = Math.max(r, g, b);
  const low = Math.min(r, g, b);
  if (high < 0.15 || high - low < 0.15 * high || high - low < 0.06) return null;
  const delta = high - low;
  let hue;
  if (high === r) hue = ((((g - b) / delta) % 6) + 6) % 6;
  else if (high === g) hue = (b - r) / delta + 2;
  else hue = (r - g) / delta + 4;
  return hue * 60;
}

function oneHueFeedback(work) {
  const bins = new Array(12).fill(0);
  let colored = 0;
  const count = work.width * work.height;
  for (let index = 0; index < count; index += 3) {
    const i = index * 4;
    const hue = hueOf([work.pixels[i], work.pixels[i + 1], work.pixels[i + 2]]);
    if (hue === null) continue;
    bins[Math.trunc(hue / 30) % 12]++;
    colored++;
  }
  if (colored < count / 60) return "Paint with some color first, then ask me again.";
  const lead = Math.max(...bins) / colored;
  const strong = bins.filter((it) => it > colored * 0.15).length;
  if (lead > 0.55 && strong <= 3) return `One hue clearly leads with ${Math.trunc(lead * 100)}% of the color. That's a calm, unified palette.`;
  if (lead > 0.4) return "One hue leads, but the others compete a little. Mute or shrink the supporting ones.";
  return "Several hues share the stage equally. Pick one to lead and let it cover more of the study.";
}

const warmth = (work) => (x, y) => work.red(x, y) - work.blue(x, y);

function warmCoolFeedback(work) {
  const lit = meanIn(work, sphereSide(work, true), warmth(work));
  const shadow = meanIn(work, sphereSide(work, false), warmth(work));
  if (lit === null || shadow === null) return "Paint on the sphere first, then ask me again.";
  const litValue = meanIn(work, sphereSide(work, true), (x, y) => work.luminance(x, y)) ?? 1;
  if (Math.abs(lit - shadow) < 0.01 && Math.abs(litValue - GUIDE_LUMINANCE) < 0.02) return "Paint on the sphere first, then ask me again.";
  if (lit > 0.04 && shadow < lit - 0.08) return "Warm light and cooler shadows, nicely done. The sphere feels lit by the sun.";
  if (lit <= 0.04) return "The light side isn't warm yet. Push it toward yellow or orange.";
  return "The shadows are as warm as the light. Cool them toward blue or violet.";
}

function measureHueShift(work) {
  const litHues = [];
  const shadowHues = [];
  let shadowGray = 0;
  let shadowTotal = 0;
  const lit = sphereSide(work, true);
  const shadow = sphereSide(work, false);
  for (let y = 0; y < work.height; y += 2) {
    for (let x = 0; x < work.width; x += 2) {
      const sx = x / work.width;
      const sy = y / work.height;
      const hue = hueOf(work.color(x, y));
      if (lit(sx, sy) && hue !== null) litHues.push(hue);
      if (shadow(sx, sy)) {
        shadowTotal++;
        if (hue === null) shadowGray++;
        else shadowHues.push(hue);
      }
    }
  }
  const grayShare = shadowTotal === 0 ? 0 : shadowGray / shadowTotal;
  const litValue = meanIn(work, lit, (x, y) => work.luminance(x, y)) ?? GUIDE_LUMINANCE;
  const untouched = Math.abs(litValue - GUIDE_LUMINANCE) < 0.02 && !litHues.length;
  if (!litHues.length || !shadowHues.length) return { shift: null, grayShare, painted: !untouched };
  const mean = (hues) => Math.atan2(hues.reduce((s, h) => s + Math.sin(h * PI / 180), 0), hues.reduce((s, h) => s + Math.cos(h * PI / 180), 0)) * 180 / PI;
  return { shift: Math.abs(((mean(shadowHues) - mean(litHues)) % 360 + 540) % 360 - 180), grayShare, painted: true };
}

function hueShiftFeedback(work) {
  const { shift, grayShare, painted } = measureHueShift(work);
  if (shift === null && painted && grayShare > 0.6) return "The shadows went gray or black. Shade with a darker, shifted hue instead.";
  if (shift === null) return "Paint the sphere in color first, then ask me again.";
  if (grayShare > 0.5) return "Much of the shadow went gray. Keep color in it, just darker and shifted.";
  if (shift < 8) return "The hue barely moves. Try nudging the shadows toward a neighbor on the color wheel.";
  if (shift < 60) return `The hue shifts ${Math.trunc(shift)} degrees into the shadow. That's what makes it glow.`;
  return `The shadow hue jumps ${Math.trunc(shift)} degrees, which can look like two objects. A smaller shift reads more natural.`;
}

// --- Reviews with marks, from Warmups.kt, WarmupForms.kt, and WarmupExtras.kt ------------------

const ring = (label, x, y, radius) => ({ label, shape: "RING", x1: x, y1: y, x2: radius, y2: 0 });
const box = (label, area) => ({ label, shape: "BOX", x1: area[0], y1: area[1], x2: area[2], y2: area[3] });
const lineMark = (label, stroke) => ({ label, shape: "LINE", x1: stroke[0], y1: stroke[1], x2: stroke[stroke.length - 2], y2: stroke[stroke.length - 1] });

function meanOver(work, bounds, inside, value) {
  let total = 0;
  let count = 0;
  const left = clamp(Math.trunc(bounds[0] * work.width), 0, work.width - 1);
  const right = clamp(Math.trunc(bounds[2] * work.width), 0, work.width - 1);
  const top = clamp(Math.trunc(bounds[1] * work.height), 0, work.height - 1);
  const bottom = clamp(Math.trunc(bounds[3] * work.height), 0, work.height - 1);
  for (let y = top; y <= bottom; y++) {
    for (let x = left; x <= right; x++) {
      if (!inside(x / work.width, y / work.height)) continue;
      total += value(x, y);
      count++;
    }
  }
  return count === 0 ? null : total / count;
}

function checkForm(work, form) {
  const values = form.planes.map((p) => meanOver(work, p.bounds, p.contains, (x, y) => work.luminance(x, y)) ?? 1);
  const facing = form.planes.map((p) => p.normalX * form.sun.x + p.normalY * form.sun.y);
  let wrong = null;
  outer: for (let lit = 0; lit < form.planes.length; lit++) {
    for (let dim = 0; dim < form.planes.length; dim++) {
      if (facing[lit] > facing[dim] + 0.2 && values[lit] < values[dim] - 0.02) {
        wrong = [lit, dim];
        break outer;
      }
    }
  }
  const order = indices(form.planes.length);
  const brightest = maxBy(order, (i) => facing[i]);
  const darkest = minBy(order, (i) => facing[i]);
  return {
    form, values,
    brighter: wrong ? form.planes[wrong[0]] : null,
    dimmer: wrong ? form.planes[wrong[1]] : null,
    spread: values[brightest] - values[darkest],
    painted: values.some((it) => Math.abs(it - FORM_LUMINANCE) >= 0.02),
  };
}

function castArea(form) {
  const floor = Math.max(...form.planes.map((it) => it.bounds[3]));
  const away = -form.sun.x;
  const from = Math.min(form.center.x, form.center.x + away * form.radius * 1.8) - (away === 0 ? form.radius : 0);
  const to = Math.max(form.center.x, form.center.x + away * form.radius * 1.8) + (away === 0 ? form.radius : 0);
  return [from, floor - 0.015, to, floor + 0.05];
}

function lightFormsReview(work, kind) {
  const forms = lightForms(kind, work.layout);
  const checks = forms.map((form) => checkForm(work, form));
  if (!checks.some((it) => it.painted)) return { text: "Shade the forms first, then ask me again.", marks: [] };
  const painted = checks.filter((it) => it.painted);
  const worst = painted.find((it) => it.brighter) || minBy(painted, (it) => it.spread);
  const missingCast = kind === "SPHERE_LIGHT"
    ? checks.find((it) => it.painted && (meanOver(work, castArea(it.form), () => true, (x, y) => work.luminance(x, y)) ?? 1) > 0.8)
    : null;
  const marks = [];
  let text;
  if (worst.brighter) {
    marks.push(box("Lighter", worst.brighter.bounds));
    if (worst.dimmer) marks.push(box("Darker", worst.dimmer.bounds));
    text = `On the ${worst.form.name}, the ${worst.brighter.name} should be lighter, since it faces its sun.`;
  } else if (worst.spread < 0.12) {
    marks.push(box("Darker here", minBy(worst.form.planes, (p) => p.normalX * worst.form.sun.x + p.normalY * worst.form.sun.y).bounds));
    text = `The ${worst.form.name} still looks flat. Darken the side away from its sun.`;
  } else if (worst.spread < 0.28) {
    text = `Good form overall. The ${worst.form.name} could use more contrast between light and shadow.`;
  } else if (checks.every((it) => it.painted)) {
    text = "Every form turns clearly from its sun to its shadow. Lovely.";
  } else {
    text = "That one reads well. Now light the others from their own suns.";
  }
  let cast = "";
  if (missingCast) {
    marks.push(box("Cast shadow here", castArea(missingCast.form)));
    cast = ` Add a cast shadow beside the ${missingCast.form.name}, away from its sun.`;
  }
  return { text: text + cast, marks: marks.slice(0, 2) };
}

function featureReview(work, kind) {
  const shape = feature(kind, work.layout);
  if (!shape) return { text: "", marks: [] };
  const guide = shape.contours.flat();
  const points = work.strokes.flatMap(pointsOf);
  const lit = meanOver(work, shape.lit.bounds, shape.lit.contains, (x, y) => work.luminance(x, y)) ?? 1;
  const shade = meanOver(work, shape.shade.bounds, shape.shade.contains, (x, y) => work.luminance(x, y)) ?? 1;
  const shaded = Math.min(lit, shade) < 0.9;
  if (!points.length && !shaded) return { text: "Draw over the guide first, then ask me again.", marks: [] };
  const marks = [];
  let lines = "";
  if (points.length) {
    const near = 0.015;
    const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
    const covered = guide.filter((it) => points.some((p) => distance(p, it) < near)).length / guide.length;
    const onGuide = points.filter((p) => guide.some((it) => distance(p, it) < near)).length / points.length;
    if (covered < 0.35) {
      const missing = guide.filter((it) => !points.some((p) => distance(p, it) < near));
      const far = maxBy(missing, (it) => Math.min(...points.map((p) => distance(p, it))));
      if (far) marks.push(ring("Keep going here", far.x, far.y, 0.04));
      lines = `You've drawn about ${Math.trunc(covered * 100)}% of the guide. Keep going around the whole shape.`;
    } else if (onGuide < 0.6) {
      const far = maxBy(points, (p) => Math.min(...guide.map((it) => distance(p, it))));
      if (far) marks.push(ring("Drifted here", far.x, far.y, 0.04));
      lines = "Your lines wander off the guide. Slow down on the curves.";
    } else {
      lines = "Your lines follow the construction well.";
    }
  }
  let shading;
  if (kind === "FACE_LIPS" && !shaded) shading = "Now shade it: the upper lip faces down, so it's usually darker.";
  else if (kind === "FACE_LIPS" && shade < lit - 0.04) shading = "Nice: the upper lip sits in shadow and the lower one catches the light.";
  else if (kind === "FACE_LIPS") {
    marks.push(box("Darker here", shape.shade.bounds));
    shading = "Darken the upper lip. It faces down, away from the light.";
  } else if (!shaded) shading = "Now shade it: the side away from the sun is darker.";
  else if (shade < lit - 0.05) shading = "The shading turns away from the sun nicely.";
  else {
    marks.push(box("Darker here", shape.shade.bounds));
    shading = "Darken the side away from the sun so it turns into form.";
  }
  return { text: `${lines} ${shading}`.trim(), marks: marks.slice(0, 2) };
}

const degrees = (radians) => radians * 180 / PI;
const span = (it) => Math.hypot(it[it.length - 2] - it[0], it[it.length - 1] - it[1]);

function aimError(stroke, target) {
  const ax = stroke[0];
  const ay = stroke[1];
  const bx = stroke[stroke.length - 2];
  const by = stroke[stroke.length - 1];
  const direction = Math.atan2(by - ay, bx - ax);
  const toward = Math.atan2(target.y - (ay + by) / 2, target.x - (ax + bx) / 2);
  return degrees(angleOff(direction, toward));
}

function perspectiveReview(work) {
  const layout = work.layout;
  const lines = work.strokes.filter((it) => it.length >= 4 && span(it) > 0.03);
  if (!lines.length) return { text: "Draw the edges of a box first, then ask me again.", marks: [] };
  const errors = lines.map((stroke) => {
    const dx = Math.abs(stroke[stroke.length - 2] - stroke[0]);
    if (dx < span(stroke) * 0.2) return degrees(Math.atan2(dx, Math.abs(stroke[stroke.length - 1] - stroke[1])));
    return Math.min(aimError(stroke, layout.vanishLeft), aimError(stroke, layout.vanishRight));
  });
  const mean = average(errors);
  const worst = lines[maxBy(indices(errors.length), (i) => errors[i])];
  const marks = Math.max(...errors) > 4 ? [lineMark("Aim at a vanishing point", worst)] : [];
  let text;
  if (mean < 2.5) text = "Your edges run right into the vanishing points. Those boxes sit firmly in space.";
  else if (mean < 6) text = `Most edges head to the vanishing points, a few drift by ${Math.trunc(mean)} degrees or so.`;
  else text = `The edges miss the vanishing points by about ${Math.trunc(mean)} degrees. Lay the pen toward the dot before each stroke.`;
  return { text, marks };
}

function meanBox(work, area) {
  let total = 0;
  let count = 0;
  const left = clamp(Math.trunc(area[0] * work.width), 0, work.width - 1);
  const right = clamp(Math.trunc(area[2] * work.width), left, work.width - 1);
  const top = clamp(Math.trunc(area[1] * work.height), 0, work.height - 1);
  const bottom = clamp(Math.trunc(area[3] * work.height), top, work.height - 1);
  for (let y = top; y <= bottom; y++) {
    for (let x = left; x <= right; x++) {
      total += work.luminance(x, y);
      count++;
    }
  }
  return count === 0 ? 1 : total / count;
}

function gradientReview(work) {
  const area = work.layout.blendBox;
  const horizontal = area[2] - area[0] > area[3] - area[1];
  const strips = 16;
  let values = indices(strips).map((index) => {
    const from = index / strips;
    const to = (index + 1) / strips;
    const strip = horizontal
      ? [area[0] + (area[2] - area[0]) * from, area[1] + 0.01, area[0] + (area[2] - area[0]) * to, area[3] - 0.01]
      : [area[0] + 0.01, area[1] + (area[3] - area[1]) * from, area[2] - 0.01, area[1] + (area[3] - area[1]) * to];
    return [strip, meanBox(work, strip)];
  });
  if (!work.layout.blendDarkFirst) values = values.reverse();
  const levels = values.map((it) => it[1]);
  if (levels.every((it) => it > 0.95)) return { text: "Paint the blend first, then ask me again.", marks: [] };
  const range = levels[levels.length - 1] - levels[0];
  const steps = levels.slice(1).map((value, i) => value - levels[i]);
  const jump = maxBy(indices(steps.length), (i) => Math.abs(steps[i]));
  const backwards = steps.filter((it) => it < -0.04).length;
  const marks = [];
  let text;
  if (range < 0.25) {
    text = "The blend doesn't travel far yet. Push the dark end darker and keep the light end light.";
  } else if (backwards > 1) {
    marks.push(box("Smooth this", values[jump][0]));
    text = "The blend goes back and forth. Keep every step a little lighter than the one before.";
  } else if (Math.abs(steps[jump]) > range * 0.3) {
    marks.push(box("Smooth this", values[jump][0]));
    text = "There's a sudden jump in the middle. Soften it with a light, low-opacity pass.";
  } else {
    text = "A smooth, even blend from dark to light. That control will show in every rendering.";
  }
  return { text, marks };
}

// Reads each letter stroke against its row: on the baseline, and stopping at the x-height or cap line.
function letteringReview(work) {
  const rows = work.layout.letterRows;
  const placed = work.strokes.filter((stroke) => stroke.length >= 4).map((stroke) => {
    const ys = stroke.filter((_, index) => index % 2 === 1);
    const xs = stroke.filter((_, index) => index % 2 === 0);
    const top = Math.min(...ys);
    const bottom = Math.max(...ys);
    const middle = (top + bottom) / 2;
    const row = rows.find((it) => middle >= it[1] - (it[3] - it[1]) * 0.3 && middle <= it[3] + (it[3] - it[1]) * 0.3);
    if (!row) return null;
    const tall = row[3] - row[1];
    if (Math.max(...xs) - Math.min(...xs) > tall * 3) return null;
    // Descenders reach below on purpose, so only strokes ending near the baseline count there.
    const base = bottom < row[3] + tall * 0.25 ? Math.abs(bottom - row[3]) : 0;
    const reach = Math.min(Math.abs(top - row[1]), Math.abs(top - row[2]));
    return { xs, ys, error: (base + reach) / tall };
  }).filter(Boolean);
  if (placed.length < 3) return { text: "Letter a word on its lines first, then ask me again.", marks: [] };
  const mean = placed.reduce((sum, it) => sum + it.error, 0) / placed.length;
  const worst = placed.reduce((a, b) => (b.error > a.error ? b : a));
  const marks = worst.error > 0.15
    ? [box("Line this one up", [Math.min(...worst.xs) - 0.01, Math.min(...worst.ys) - 0.01, Math.max(...worst.xs) + 0.01, Math.max(...worst.ys) + 0.01])]
    : [];
  const text = mean < 0.08 ? "Your letters sit on the baseline and reach their lines evenly. That rhythm is what makes lettering look set."
    : mean < 0.16 ? "Mostly even. A few letters float off the baseline or stop short of their line."
      : "Letter heights wander. Slow down and let each letter touch the dashed line or the top line before you lift.";
  return { text, marks };
}

function mirrorReview(work) {
  const target = work.layout.mirrorHalf.map((it) => spot(1 - it.x, it.y));
  const points = work.strokes.flatMap(pointsOf).filter((it) => it.x > 0.5);
  if (!points.length) return { text: "Draw the other half on the right first, then ask me again.", marks: [] };
  const near = 0.015;
  const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const covered = target.filter((it) => points.some((p) => distance(p, it) < near * 1.5)).length / target.length;
  const distances = points.map((p) => Math.min(...target.map((it) => distance(p, it))));
  const onTarget = distances.filter((it) => it < near * 1.5).length / distances.length;
  const marks = [];
  let text;
  if (covered < 0.5) {
    const missing = target.find((it) => !points.some((p) => distance(p, it) < near * 1.5));
    if (missing) marks.push(ring("Keep going here", missing.x, missing.y, 0.04));
    text = `You've mirrored about ${Math.trunc(covered * 100)}% of the shape. Follow it all the way down.`;
  } else if (onTarget < 0.6) {
    const worst = points[maxBy(indices(distances.length), (i) => distances[i])];
    const mirror = minBy(target, (it) => distance(it, worst));
    marks.push(ring(Math.abs(worst.x - 0.5) > Math.abs(mirror.x - 0.5) ? "Closer to the middle" : "Further out", worst.x, worst.y, 0.045));
    text = "The halves don't match yet. Compare how far each curve sits from the middle line.";
  } else {
    text = "The two halves match beautifully. That's a balanced, symmetric shape.";
  }
  return { text, marks };
}

function sphereSpot(work, lit) {
  const sign = lit ? 1 : -1;
  const [cx, cy, r] = work.layout.colorSphere;
  const reach = r * 0.5 * sign;
  return spot(cx + work.sun.x * reach * Math.min(work.width, work.height) / work.width, cy + work.sun.y * reach * Math.min(work.width, work.height) / work.height);
}

// The companion's note on an exercise, based only on what the exercise practices.
function warmupFeedback(kind, work) {
  switch (kind) {
    case "STRAIGHT_LINES": return lineFeedback(work.strokes, work.layout.dots);
    case "HATCHING": return hatchingFeedback(work.strokes);
    case "ELLIPSES": return ellipseFeedback(work.strokes, work.layout.ellipseBoxes);
    case "TRACING": return tracingFeedback(work.strokes, work.layout.tracePaths);
    case "SILHOUETTES": return silhouetteFeedback(work);
    case "SPHERE_LIGHT": case "CUBE_SHADOW": return lightFormsReview(work, kind).text;
    case "FACE_EYE": case "FACE_NOSE": case "FACE_LIPS": return featureReview(work, kind).text;
    case "THREE_VALUES": return threeValueFeedback(work);
    case "VALUE_SCALE": return valueScaleFeedback(work);
    case "ONE_HUE": return oneHueFeedback(work);
    case "WARM_COOL": return warmCoolFeedback(work);
    case "HUE_SHIFT": return hueShiftFeedback(work);
    case "PERSPECTIVE": return perspectiveReview(work).text;
    case "GRADIENT": return gradientReview(work).text;
    case "MIRROR": return mirrorReview(work).text;
    case "LETTERING": return letteringReview(work).text;
    default: return "";
  }
}

// Where the companion points to show what to improve, at most two places.
function warmupMarks(kind, work) {
  const marks = [];
  switch (kind) {
    case "STRAIGHT_LINES": {
      const lines = longStrokes(work.strokes);
      const bent = maxBy(lines, wobble);
      if (bent && wobble(bent) > 0.012) marks.push(lineMark("Wobbliest line", bent));
      const dots = work.layout.dots.flat();
      const ends = lines.flatMap((it) => [spot(it[0], it[1]), spot(it[it.length - 2], it[it.length - 1])]);
      const pairs = ends.map((end) => [end, minBy(dots, (it) => Math.hypot(it.x - end.x, it.y - end.y))]);
      const far = maxBy(pairs, ([end, dot]) => Math.hypot(dot.x - end.x, dot.y - end.y));
      if (far && Math.hypot(far[1].x - far[0].x, far[1].y - far[0].y) > 0.012) marks.push(ring("Aim for this dot", far[1].x, far[1].y, 0.035));
      break;
    }
    case "HATCHING": {
      const lines = longStrokes(work.strokes, 0.02);
      if (lines.length >= 4) {
        const angles = lines.map(lineAngle);
        const mean = Math.atan2(angles.reduce((s, a) => s + Math.sin(2 * a), 0), angles.reduce((s, a) => s + Math.cos(2 * a), 0)) / 2;
        const off = maxBy(indices(angles.length), (i) => angleOff(angles[i], mean));
        if (angleOff(angles[off], mean) > 6 * PI / 180) marks.push(lineMark("Off angle", lines[off]));
      }
      break;
    }
    case "ELLIPSES": {
      const placed = loopsInBoxes(work.strokes, work.layout.ellipseBoxes);
      const filled = indices(placed.length).filter((i) => placed[i]);
      const missed = maxBy(filled, (i) => boxMiss(placed[i], work.layout.ellipseBoxes[i]));
      if (missed !== undefined && boxMiss(placed[missed], work.layout.ellipseBoxes[missed]) > 0.08) marks.push(box("Touch all four sides", work.layout.ellipseBoxes[missed]));
      const lumpy = maxBy(placed.filter(Boolean), ellipseUnevenness);
      if (lumpy && ellipseUnevenness(lumpy) > 0.08) {
        const xs = xsOf(lumpy);
        const ys = ysOf(lumpy);
        marks.push(ring("Smooth this one", average(xs), average(ys), (Math.max(...xs) - Math.min(...xs)) / 2 + 0.02));
      }
      break;
    }
    case "TRACING": {
      const guide = work.layout.tracePaths.flat();
      const points = longStrokes(work.strokes, 0.02).flatMap(pointsOf);
      const gap = (p) => Math.min(...guide.map((it) => Math.hypot(it.x - p.x, it.y - p.y)));
      const far = maxBy(points, gap);
      if (far && gap(far) > 0.012) marks.push(ring("Drifted here", far.x, far.y, 0.04));
      break;
    }
    case "SILHOUETTES": {
      const fill = figureFill(work);
      const empty = minBy(indices(fill.length).filter((i) => fill[i] < 0.22), (i) => fill[i]);
      if (empty !== undefined && fill.some((it) => it > 0.06)) marks.push(box("Fill this one", work.layout.figures[empty].box));
      break;
    }
    case "SPHERE_LIGHT": case "CUBE_SHADOW": return lightFormsReview(work, kind).marks;
    case "FACE_EYE": case "FACE_NOSE": case "FACE_LIPS": return featureReview(work, kind).marks;
    case "PERSPECTIVE": return perspectiveReview(work).marks;
    case "GRADIENT": return gradientReview(work).marks;
    case "MIRROR": return mirrorReview(work).marks;
    case "LETTERING": return letteringReview(work).marks;
    case "VALUE_SCALE": {
      const boxes = boxValues(work);
      if (boxes.every((it) => it > 0.95)) return marks;
      const step = boxes.slice(1).findIndex((value, i) => boxes[i] - value < 0.02);
      if (step >= 0) marks.push(box("Darker than its neighbor", work.layout.valueBoxes[step + 1]));
      break;
    }
    case "WARM_COOL": {
      const lit = meanIn(work, sphereSide(work, true), warmth(work));
      const shadow = meanIn(work, sphereSide(work, false), warmth(work));
      if (lit === null || shadow === null) return marks;
      const value = meanIn(work, sphereSide(work, true), (x, y) => work.luminance(x, y)) ?? 1;
      if (Math.abs(lit - shadow) < 0.01 && Math.abs(value - GUIDE_LUMINANCE) < 0.02) return marks;
      if (lit <= 0.04) {
        const it = sphereSpot(work, true);
        marks.push(ring("Warmer here", it.x, it.y, 0.08));
      } else if (shadow >= lit - 0.08) {
        const it = sphereSpot(work, false);
        marks.push(ring("Cooler here", it.x, it.y, 0.08));
      }
      break;
    }
    case "HUE_SHIFT": {
      const { shift, grayShare, painted } = measureHueShift(work);
      const needsWork = shift === null ? painted && grayShare > 0.6 : grayShare > 0.5 || shift < 8 || shift >= 60;
      if (needsWork) {
        const it = sphereSpot(work, false);
        marks.push(ring("Shift the hue here", it.x, it.y, 0.08));
      }
      break;
    }
    default:
      break;
  }
  return marks.slice(0, 2);
}

export function warmupReview(kind, work) {
  return { text: warmupFeedback(kind, work), marks: warmupMarks(kind, work) };
}
