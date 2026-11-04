// The built-in brushes, as BrushKind in app/src/main/java/art/cappuccino/paint/Model.kt defines
// them. Keep the two in step until step 3 shares the Kotlin model with the web.

export const TIP_SIZE = 128;

// String.hashCode from Java, which seeds each brush preview's randomness.
export function javaHash(text) {
  let hash = 0;
  for (let i = 0; i < text.length; i++) hash = (Math.imul(hash, 31) + text.charCodeAt(i)) | 0;
  return hash;
}

// java.util.Random, so the generated tips come out the same as on Android.
export class JavaRandom {
  constructor(seed) {
    this.seed = (BigInt.asUintN(64, BigInt(seed)) ^ 0x5DEECE66Dn) & ((1n << 48n) - 1n);
  }
  next(bits) {
    this.seed = (this.seed * 0x5DEECE66Dn + 0xBn) & ((1n << 48n) - 1n);
    const value = Number(this.seed >> BigInt(48 - bits));
    return value >= 2 ** (bits - 1) && bits === 32 ? value - 2 ** 32 : value;
  }
  nextFloat() {
    return this.next(24) / (1 << 24);
  }
  nextInt(bound) {
    if ((bound & -bound) === bound) return Number((BigInt(bound) * BigInt(this.next(31))) >> 31n);
    let bits, value;
    do {
      bits = this.next(31);
      value = bits % bound;
    } while (bits - value + (bound - 1) >= 2 ** 31);
    return value;
  }
}

function spongeTip() {
  const random = new JavaRandom(11);
  const tip = new Uint8Array(TIP_SIZE * TIP_SIZE);
  for (let n = 0; n < 420; n++) {
    const x = random.nextInt(TIP_SIZE);
    const y = random.nextInt(TIP_SIZE);
    const radius = 1 + random.nextInt(4);
    for (let dy = -radius; dy <= radius; dy++) {
      for (let dx = -radius; dx <= radius; dx++) {
        const px = x + dx;
        const py = y + dy;
        if (px >= 0 && px < TIP_SIZE && py >= 0 && py < TIP_SIZE && dx * dx + dy * dy <= radius * radius) {
          tip[py * TIP_SIZE + px] = 255;
        }
      }
    }
  }
  return tip;
}

function dryBrushTip() {
  const random = new JavaRandom(23);
  const tip = new Uint8Array(TIP_SIZE * TIP_SIZE);
  let row = 0;
  while (row < TIP_SIZE) {
    const thickness = 1 + random.nextInt(3);
    const start = random.nextInt(24);
    const end = TIP_SIZE - random.nextInt(24);
    for (let y = row; y < Math.min(TIP_SIZE, row + thickness); y++) {
      for (let x = start; x < end; x++) {
        if (random.nextInt(9) !== 0) tip[y * TIP_SIZE + x] = 255;
      }
    }
    row += thickness + 1 + random.nextInt(3);
  }
  return tip;
}

function blobTip(paint) {
  const tip = new Uint8Array(TIP_SIZE * TIP_SIZE);
  for (let index = 0; index < tip.length; index++) {
    const x = (index % TIP_SIZE) / (TIP_SIZE - 1) * 2 - 1;
    const y = Math.floor(index / TIP_SIZE) / (TIP_SIZE - 1) * 2 - 1;
    if (paint(x, y)) tip[index] = 255;
  }
  return tip;
}

// id, label, minScale, spacing, flow, pressureFlow, hardness, grain, defaultSize, and extras.
function kind(id, label, minScale, spacing, flow, pressureFlow, hardness, grain, size, extra = {}) {
  return {
    id, label, minScale, spacing, flow, pressureFlow, hardness, grain, size,
    eraser: false, mode: "paint", tip: null,
    scatter: 0, sizeJitter: 0, angleJitter: 0, shadeJitter: 0, hueJitter: 0, strokeJitter: 0,
    ...extra,
  };
}

const INK = kind("ink", "Ink", 0.12, 0.08, 1, 0, 1, 0, 24);
const LINER = kind("liner", "Liner", 0.85, 0.06, 1, 0, 1, 0, 12);
const PENCIL = kind("pencil", "Pencil", 0.5, 0.1, 0.35, 0.8, 0.7, 0.8, 16);
const FINE_PENCIL = kind("fine-pencil", "Fine pencil", 0.35, 0.05, 0.55, 0.95, 0.8, 0.65, 9);
const SOFT_PENCIL = kind("soft-pencil", "Soft pencil", 0.55, 0.07, 0.42, 0.9, 0.55, 0.95, 30);
const CHARCOAL = kind("charcoal", "Charcoal", 0.7, 0.1, 0.55, 0.6, 0.6, 1, 60);
const CHALK = kind("chalk", "Chalk", 0.8, 0.12, 0.6, 0.3, 0.8, 0.9, 80);
const MARKER = kind("marker", "Marker", 0.9, 0.05, 0.18, 0.2, 0.9, 0, 70);
const SOFT = kind("soft", "Soft", 0.6, 0.06, 0.08, 0.7, 0, 0, 160);
const WASH = kind("wash", "Wash", 0.7, 0.08, 0.05, 0.5, 0.2, 0.35, 220);
const SPONGE = kind("sponge", "Sponge", 0.8, 0.45, 0.55, 0.5, 0.5, 0, 120, { tip: spongeTip() });
const DRY_BRUSH = kind("dry-brush", "Dry brush", 0.7, 0.12, 0.45, 0.5, 0.5, 0.3, 110, { tip: dryBrushTip() });
const LEAVES = kind("leaves", "Leaves", 0.5, 0.55, 0.9, 0.2, 0.8, 0, 90, {
  tip: blobTip((x, y) => x * x * 7 + y * y < 0.95),
  scatter: 0.7, sizeJitter: 0.5, angleJitter: 1, shadeJitter: 0.25, hueJitter: 0.3, strokeJitter: 0.4,
});
const GRASS = kind("grass", "Grass", 0.6, 0.2, 0.9, 0.2, 0.8, 0, 120, {
  tip: blobTip((x, y) => Math.abs(x) < 0.09 * (1 - y) && y > -0.95),
  scatter: 0.45, sizeJitter: 0.5, angleJitter: 0.09, shadeJitter: 0.2, hueJitter: 0.25, strokeJitter: 0.3,
});
const ROCKS = kind("rocks", "Rocks", 0.6, 0.6, 1, 0.1, 0.9, 0.25, 110, {
  tip: blobTip((x, y) => Math.abs(x) + Math.abs(y) * 1.3 + Math.abs(x + y) * 0.35 < 1),
  scatter: 0.6, sizeJitter: 0.7, angleJitter: 1, shadeJitter: 0.3, strokeJitter: 0.2,
});
const PARTICLES = kind("particles", "Particles", 0.4, 0.5, 1, 0.4, 0.5, 0, 90, {
  scatter: 1.1, sizeJitter: 0.9, shadeJitter: 0.15,
});

const PAINT_BRUSHES = [INK, LINER, FINE_PENCIL, PENCIL, SOFT_PENCIL, CHARCOAL, CHALK, MARKER, SOFT, WASH];
export const BRUSH_FAMILIES = [
  ["Sketch", PAINT_BRUSHES.slice(0, 7)],
  ["Paint", [...PAINT_BRUSHES.slice(7), SPONGE, DRY_BRUSH]],
  ["Scatter", [LEAVES, GRASS, ROCKS, PARTICLES]],
];

const eraser = (id, label, minScale, spacing, flow, pressureFlow, hardness, grain, size) =>
  kind(id, label, minScale, spacing, flow, pressureFlow, hardness, grain, size, { eraser: true });

// The lasso eraser waits for selections on the web.
export const ERASERS = [
  eraser("erase-ink", "Ink", 0.12, 0.08, 1, 0, 1, 0, 40),
  eraser("erase-liner", "Liner", 0.85, 0.06, 1, 0, 1, 0, 24),
  eraser("erase-fine-pencil", "Fine pencil", 0.35, 0.05, 0.55, 0.95, 0.8, 0.65, 18),
  eraser("erase-pencil", "Pencil", 0.5, 0.1, 0.35, 0.8, 0.7, 0.8, 30),
  eraser("erase-soft-pencil", "Soft pencil", 0.55, 0.07, 0.42, 0.9, 0.55, 0.95, 50),
  eraser("erase-charcoal", "Charcoal", 0.7, 0.1, 0.55, 0.6, 0.6, 1, 80),
  eraser("erase-chalk", "Chalk", 0.8, 0.12, 0.6, 0.3, 0.8, 0.9, 80),
  eraser("erase-marker", "Marker", 0.9, 0.05, 0.18, 0.2, 0.9, 0, 90),
  eraser("erase-soft", "Soft", 0.6, 0.06, 0.15, 0.5, 0, 0, 160),
  eraser("erase-wash", "Wash", 0.7, 0.08, 0.08, 0.5, 0.2, 0.35, 220),
];

export const TOOLS = {
  smudge: kind("smudge", "Smudge", 0.6, 0.2, 0.65, 0.4, 0.5, 0, 90, { mode: "smudge" }),
  fill: kind("fill", "Fill", 1, 1, 1, 0, 1, 0, 40, { mode: "fill" }),
  gradient: kind("gradient", "Gradient", 1, 1, 1, 0, 1, 0, 40, { mode: "gradient" }),
  lassoFill: kind("lasso-fill", "Lasso fill", 1, 1, 1, 0, 1, 0, 40, { mode: "lasso" }),
};

export const DEFAULT_BRUSH = INK;
export const DEFAULT_ERASER = ERASERS[0];

export function isTool(brush) {
  return !brush.eraser && brush.mode !== "paint";
}

// Stamp alpha that builds up to about opacity across one stamp width.
export function stampFlow(brush, opacity) {
  return opacity >= 1 ? brush.flow : brush.flow * (1 - Math.pow(1 - opacity, brush.spacing));
}
