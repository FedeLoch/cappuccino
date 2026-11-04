// The companion, drawn as CompanionView in app/src/main/java/art/cappuccino/paint/CompanionView.kt
// draws its PAINT_DROP look in the studio and its COFFEE_DROP look on the home screen.

const CREAM = "#F1EFE8";
const INK = "#2C2C2A";
const COFFEE = "#6B4226";
const BLINK_EVERY_MS = 4200;
const BLINK_MS = 140;

const DROP = new Path2D(
  "M0,-50C0,-50 -34,-8 -34,14C-34,36 -18,46 0,46C18,46 34,36 34,14C34,-8 0,-50 0,-50Z",
);

const FOAM_CAP = new Path2D(
  "M-40,-60L40,-60L40,-15L24,-15Q17,-2 9,-12Q2,3 -7,-11Q-15,-3 -24,-15L-40,-15Z",
);

function luminance(hex) {
  const value = parseInt(hex.slice(1), 16);
  const linear = (c) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * linear((value >> 16) & 255) + 0.7152 * linear((value >> 8) & 255) + 0.0722 * linear(value & 255);
}

export class Companion {
  constructor(canvas, { look = "paint", outline = CREAM, blink = true } = {}) {
    this.canvas = canvas;
    this.context = canvas.getContext("2d");
    this.look = look;
    this.outline = outline;
    this.bodyColor = INK;
    this.mood = "idle";
    this.blinking = false;
    const blinkNow = () => {
      this.blinking = !this.blinking;
      this.draw();
      setTimeout(blinkNow, this.blinking ? BLINK_MS : BLINK_EVERY_MS);
    };
    if (blink) setTimeout(blinkNow, BLINK_EVERY_MS);
    this.resize();
  }

  resize() {
    const ratio = window.devicePixelRatio || 1;
    const size = this.canvas.clientWidth || 56;
    this.canvas.width = Math.round(size * ratio);
    this.canvas.height = Math.round(size * ratio);
    this.draw();
  }

  setColor(color) {
    this.bodyColor = color;
    this.draw();
  }

  setMood(mood) {
    this.mood = mood;
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
    if (this.look === "coffee") {
      c.fillStyle = COFFEE;
      c.fill(DROP);
      c.save();
      c.clip(DROP);
      c.fillStyle = CREAM;
      c.fill(FOAM_CAP);
      c.restore();
      c.strokeStyle = this.outline;
      c.stroke(DROP);
      this.drawFace(14, CREAM);
      return;
    }
    c.fillStyle = this.bodyColor;
    c.fill(DROP);
    c.strokeStyle = this.outline;
    c.stroke(DROP);
    this.drawFace(10, luminance(this.bodyColor) > 0.35 ? INK : CREAM);
  }

  drawFace(y, color) {
    const c = this.context;
    c.fillStyle = color;
    c.strokeStyle = color;
    const dot = (x, cy, r) => {
      c.beginPath();
      c.arc(x, cy, r, 0, Math.PI * 2);
      c.fill();
    };
    const line = (x1, y1, x2, y2) => {
      c.beginPath();
      c.moveTo(x1, y1);
      c.lineTo(x2, y2);
      c.stroke();
    };
    // Android's drawArc takes degrees clockwise from 3 o'clock, inside an oval's bounds.
    const arc = (left, top, right, bottom, start, sweep) => {
      c.beginPath();
      c.ellipse((left + right) / 2, (top + bottom) / 2, (right - left) / 2, (bottom - top) / 2, 0,
        start * Math.PI / 180, (start + sweep) * Math.PI / 180);
      c.stroke();
    };
    if (this.mood === "tip") {
      dot(-13, y, 6.5);
      dot(13, y, 6.5);
      c.beginPath();
      c.ellipse(0, y + 16, 5, 4, 0, 0, Math.PI * 2);
      c.fill();
      c.fillStyle = "#FAC775";
      dot(42, -42, 8);
      return;
    }
    if (this.mood === "thinking") {
      dot(-10, y - 4, 4.5);
      dot(16, y - 4, 4.5);
      line(-5, y + 15, 7, y + 13);
      return;
    }
    if (this.blinking) {
      line(-18, y, -8, y);
      line(8, y, 18, y);
    } else {
      dot(-13, y, 4.5);
      dot(13, y, 4.5);
    }
    arc(-8, y + 4, 8, y + 18, 30, 120);
  }
}
