import { pixelRatio } from "./ui.js";

// The companion, drawn as CompanionView and CompanionHands in
// app/src/main/java/art/cappuccino/paint/CompanionView.kt draw it, in a 120 by 120 box.

const CREAM = "#F1EFE8";
const INK = "#2C2C2A";
const COFFEE = "#6B4226";
const COFFEE_LINE = "#4A2C1A";
const MILKY_COFFEE = "#C69A6B";
const CREMA = "#A87448";
const STEAM = "#D2C0AC";
const SPARKLE = "#FAC775";
const BLINK_EVERY_MS = 4200;
const BLINK_MS = 140;
const MOTION_MS = 70;
const STEAM_SPEED = 1.1;
const STEAM_CYCLE = 2.8;
const STEAM_SOFT = 0.22;
const STEAM_BOTTOM = -29;
const STEAM_HEIGHT = 22;
const STEAM_SWAY = 2.2;
const STEAM_LINE = 2.6;
const STEAM_STEPS = 12;
const SPARKLE_EVERY = 3.5;
const SPARKLE_SHARE = 0.32;
const SPARKLE_SIZE = 6;
const ROSETTA_SQUASH = 0.27;
const ROSETTA_SCALE = 0.72;
const ROSETTA_LINES = [[26, 36], [7, 34], [-12, 24]];
const PEEK_FACE_SHIFT = 12;
const HAND_TILT = 20;

// The looks, by the names Android saves in the "mascot" preference.
export const LOOKS = [
  ["PAINT_DROP", "Paint drop"],
  ["INK_BLOT", "Ink blot"],
  ["FOAM_CUP", "Foam cup"],
  ["COFFEE_DROP", "Coffee drop"],
  ["FLAT_WHITE", "Flat white"],
];

const DROP = new Path2D("M0,-50C0,-50 -34,-8 -34,14C-34,36 -18,46 0,46C18,46 34,36 34,14C34,-8 0,-50 0,-50Z");
const BLOT = new Path2D(
  "M0,-42C24,-48 36,-28 40,-10C50,8 42,30 22,36C8,48 -18,46 -30,34C-50,30 -54,0 -40,-14C-38,-34 -18,-44 0,-42Z",
);
const CUP = new Path2D("M-34,-14L34,-14L28,26C26,38 16,42 8,42L-8,42C-16,42 -26,38 -28,26Z");
const FOAM_HEART = new Path2D("M0,-8C-16,-14 -14,-24 -6,-23C-3,-23 -1,-21 0,-19C1,-21 3,-23 6,-23C14,-24 16,-14 0,-8Z");
const FOAM_CAP = new Path2D("M-40,-60L40,-60L40,-15L24,-15Q17,-2 9,-12Q2,3 -7,-11Q-15,-3 -24,-15L-40,-15Z");
const BOWL = new Path2D("M-40,-12L40,-12C40,14 26,34 10,36L-10,36C-26,34 -40,14 -40,-12Z");
const ROSETTA = new Path2D("M0,-38C14,-26 34,0 27,22C22,36 8,41 0,41C-8,41 -22,36 -27,22C-34,0 -14,-26 0,-38Z");
const ROSETTA_LINE_PATH = (() => {
  const path = new Path2D();
  for (const [y, reach] of ROSETTA_LINES) {
    path.moveTo(-reach, y - 13);
    path.quadraticCurveTo(-reach * 0.45, y - 1, 0, y);
    path.quadraticCurveTo(reach * 0.45, y - 1, reach, y - 13);
  }
  path.moveTo(0, 40);
  path.lineTo(0, -30);
  return path;
})();

export function luminance(hex) {
  const value = parseInt(hex.slice(1), 16);
  const linear = (c) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * linear((value >> 16) & 255) + 0.7152 * linear((value >> 8) & 255) + 0.0722 * linear(value & 255);
}

// Android's drawArc and drawOval take bounds, and degrees clockwise from 3 o'clock.
function ellipsePath(c, left, top, right, bottom, start = 0, sweep = 360) {
  c.beginPath();
  c.ellipse((left + right) / 2, (top + bottom) / 2, (right - left) / 2, (bottom - top) / 2, 0,
    start * Math.PI / 180, (start + sweep) * Math.PI / 180);
}

export class Companion {
  // look is a LOOKS name, or the short "paint" and "coffee" the first web version used.
  constructor(canvas, { look = "PAINT_DROP", outline = CREAM, blink = true, still = false } = {}) {
    this.canvas = canvas;
    this.context = canvas.getContext("2d");
    this.look = look === "paint" ? "PAINT_DROP" : look === "coffee" ? "COFFEE_DROP" : look;
    this.outline = outline;
    this.bodyColor = INK;
    this.mood = "idle";
    this.blinking = false;
    this.motionTime = 0;
    this.peekShare = 0;
    this.still = still;  // Rests the steam, as while you draw.
    if (blink) {
      const blinkNow = () => {
        if (!this.canvas.isConnected) return;
        this.blinking = !this.blinking;
        this.draw();
        setTimeout(blinkNow, this.blinking ? BLINK_MS : BLINK_EVERY_MS);
      };
      setTimeout(blinkNow, BLINK_EVERY_MS);
      // Keeps the flat white's steam moving.
      const motion = () => {
        if (!this.canvas.isConnected) return;
        if (this.look === "FLAT_WHITE" && !this.still) {
          this.motionTime += MOTION_MS / 1000;
          this.draw();
        }
        setTimeout(motion, MOTION_MS);
      };
      setTimeout(motion, MOTION_MS);
    }
    this.resize();
  }

  resize() {
    const ratio = pixelRatio();
    const size = this.canvas.clientWidth || 56;
    this.canvas.width = Math.round(size * ratio);
    this.canvas.height = Math.round((this.canvas.clientHeight || size) * ratio);
    this.draw();
  }

  setColor(color) {
    this.bodyColor = color;
    this.draw();
  }

  setMood(mood) {
    if (this.mood === mood) return;
    this.mood = mood;
    this.draw();
  }

  setLook(look) {
    this.look = look;
    this.draw();
  }

  setPeek(share) {
    this.peekShare = share;
    this.draw();
  }

  draw() {
    const c = this.context;
    const width = this.canvas.width;
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.clearRect(0, 0, width, this.canvas.height);
    c.translate(width / 2, this.canvas.height / 2);
    c.scale(width / 120, width / 120);
    c.lineWidth = 3.5;
    c.lineCap = "round";
    c.lineJoin = "round";
    c.globalAlpha = 1;
    switch (this.look) {
      case "INK_BLOT":
        this.body(BLOT, INK);
        c.fillStyle = INK;
        this.dot(46, -34, 5);
        this.dot(-48, 38, 4);
        this.face(2, CREAM);
        break;
      case "FOAM_CUP":
        this.foamCup();
        break;
      case "FLAT_WHITE":
        this.flatWhite();
        break;
      case "COFFEE_DROP":
        c.fillStyle = COFFEE;
        c.fill(DROP);
        c.save();
        c.clip(DROP);
        c.fillStyle = CREAM;
        c.fill(FOAM_CAP);
        c.restore();
        c.strokeStyle = this.outline;
        c.stroke(DROP);
        this.face(14, CREAM);
        break;
      default:
        this.body(DROP, this.bodyColor);
        this.face(10, luminance(this.bodyColor) > 0.35 ? INK : CREAM);
    }
  }

  body(shape, color) {
    const c = this.context;
    c.fillStyle = color;
    c.fill(shape);
    c.strokeStyle = this.outline;
    c.stroke(shape);
  }

  dot(x, y, radius) {
    const c = this.context;
    c.beginPath();
    c.arc(x, y, radius, 0, Math.PI * 2);
    c.fill();
  }

  foamCup() {
    const c = this.context;
    c.strokeStyle = COFFEE_LINE;
    ellipsePath(c, 26, -6, 52, 24, -90, 180);
    c.stroke();
    c.fillStyle = CREAM;
    c.fill(CUP);
    c.stroke(CUP);
    c.fillStyle = COFFEE;
    ellipsePath(c, -36, -30, 36, -2);
    c.fill();
    c.stroke();
    c.fillStyle = this.bodyColor;
    c.fill(FOAM_HEART);
    this.face(14, INK);
  }

  flatWhite() {
    const c = this.context;
    this.steam(-10, 0, 1);
    this.steam(11, 0.5, 0.74);
    if (this.mood === "idle") this.sparkle();
    c.strokeStyle = COFFEE_LINE;
    ellipsePath(c, 22, -6, 54, 20);
    c.stroke();
    c.fillStyle = CREAM;
    c.fill(BOWL);
    c.stroke(BOWL);
    c.fillStyle = CREMA;
    ellipsePath(c, -42, -26, 42, 2);
    c.fill();
    c.fillStyle = MILKY_COFFEE;
    ellipsePath(c, -36, -24, 36, 0);
    c.fill();
    ellipsePath(c, -42, -26, 42, 2);
    c.stroke();
    c.save();
    c.translate(0, -12);
    c.scale(ROSETTA_SCALE, ROSETTA_SQUASH);
    c.fillStyle = CREAM;
    c.fill(ROSETTA);
    c.strokeStyle = MILKY_COFFEE;
    c.lineWidth = 4;
    c.stroke(ROSETTA_LINE_PATH);
    c.restore();
    c.lineWidth = 3.5;
    this.face(11.5, INK);
  }

  // A wisp that lifts off the coffee, sways as it climbs, and fades before the next one.
  steam(x, offset, height) {
    const c = this.context;
    const time = this.motionTime / STEAM_CYCLE + offset;
    const life = time - Math.floor(time);
    const head = Math.min(1, life * 1.7);
    const tail = Math.max(0, (life - 0.4) / 0.6);
    const sway = this.motionTime * STEAM_SPEED + offset * 6.3;
    c.strokeStyle = STEAM;
    c.lineWidth = STEAM_LINE;
    let lastX = x;
    let lastY = STEAM_BOTTOM;
    for (let step = 0; step <= STEAM_STEPS; step++) {
      const share = step / STEAM_STEPS;
      const nextX = x + Math.sin(share * 6.3 - sway) * (1 + share * STEAM_SWAY);
      const nextY = STEAM_BOTTOM - share * STEAM_HEIGHT * height;
      const glow = Math.min(1, Math.max(0, Math.min((share - tail) / STEAM_SOFT, (head - share) / STEAM_SOFT)));
      if (step > 0 && glow > 0) {
        c.globalAlpha = glow * (1 - share * share * 0.5);
        c.beginPath();
        c.moveTo(lastX, lastY);
        c.lineTo(nextX, nextY);
        c.stroke();
      }
      lastX = nextX;
      lastY = nextY;
    }
    c.globalAlpha = 1;
    c.lineWidth = 3.5;
  }

  // A soft gold dot that comes and goes beside the cup, on one side and then the other.
  sparkle() {
    const c = this.context;
    const round = this.motionTime / SPARKLE_EVERY;
    const share = (round - Math.floor(round)) / SPARKLE_SHARE;
    if (share >= 1) return;
    const pulse = Math.sin(share * Math.PI);
    const right = Math.floor(round) % 2 === 0 || this.peekShare > 0.5;
    c.fillStyle = SPARKLE;
    c.globalAlpha = pulse;
    this.dot(right ? 42 : -46, right ? -40 : -32, pulse * SPARKLE_SIZE);
    c.globalAlpha = 1;
  }

  face(y, color) {
    const c = this.context;
    c.save();
    c.translate(PEEK_FACE_SHIFT * this.peekShare, 0);
    this.expression(y, color);
    c.restore();
  }

  expression(y, color) {
    const c = this.context;
    c.fillStyle = color;
    c.strokeStyle = color;
    const line = (x1, y1, x2, y2) => {
      c.beginPath();
      c.moveTo(x1, y1);
      c.lineTo(x2, y2);
      c.stroke();
    };
    if (this.mood === "nap") {
      ellipsePath(c, -19, y - 6, -7, y + 6, 20, 140);
      c.stroke();
      ellipsePath(c, 7, y - 6, 19, y + 6, 20, 140);
      c.stroke();
    } else if (this.mood === "tip") {
      this.dot(-13, y, 6.5);
      this.dot(13, y, 6.5);
      ellipsePath(c, -5, y + 12, 5, y + 20);
      c.fill();
      c.fillStyle = SPARKLE;
      this.dot(42 - PEEK_FACE_SHIFT * this.peekShare, -42, 8);
      return;
    } else if (this.mood === "thinking") {
      this.dot(-10, y - 4, 4.5);
      this.dot(16, y - 4, 4.5);
      line(-5, y + 15, 7, y + 13);
      return;
    } else if (this.blinking) {
      line(-18, y, -8, y);
      line(8, y, 18, y);
    } else {
      this.dot(-13, y, 4.5);
      this.dot(13, y, 4.5);
    }
    ellipsePath(c, -8, y + 4, 8, y + 18, 30, 120);
    c.stroke();
  }
}

// Two tiny hands holding the toolbar's corner, drawn above it.
export function drawHands(canvas, look, bodyColor) {
  const ratio = pixelRatio();
  canvas.width = Math.round(canvas.clientWidth * ratio);
  canvas.height = Math.round(canvas.clientHeight * ratio);
  const c = canvas.getContext("2d");
  c.setTransform(ratio, 0, 0, ratio, 0, 0);
  c.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
  const fill = look === "PAINT_DROP" ? bodyColor : look === "INK_BLOT" ? INK
    : look === "COFFEE_DROP" ? COFFEE : CREAM;
  const edge = look === "FOAM_CUP" || look === "FLAT_WHITE" ? COFFEE_LINE : CREAM;
  const hand = (x, y, tilt) => {
    c.save();
    c.translate(x, y);
    c.rotate(tilt * Math.PI / 180);
    c.beginPath();
    c.ellipse(0, 0, 6, 4, 0, 0, Math.PI * 2);
    c.fillStyle = fill;
    c.fill();
    c.lineWidth = 1;
    c.strokeStyle = edge;
    c.stroke();
    c.restore();
  };
  hand(9, 4 + 1, -HAND_TILT);
  hand(14, canvas.clientHeight - 4 - 1, HAND_TILT);
}
