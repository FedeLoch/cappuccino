// How text layers are set, as Lettering.kt: font, weight, spacing, alignment, turn, outline, and shadow.

// Same keys as on Android, so a text layer keeps its style across a backup.
export const DEFAULT_STYLE = {
  font: "", bold: false, italic: false, tracking: 0, leading: 1, align: 1, turn: 0, outline: 0, outlineColor: -16777216, shadow: false,
};

// System families by key, with stacks that find a close face on Windows, macOS, and Android.
export const SYSTEM_FONTS = [
  ["", "Sans", 'Roboto, "Segoe UI", system-ui, sans-serif'],
  ["serif", "Serif", 'Georgia, "Noto Serif", "Times New Roman", serif'],
  ["condensed", "Condensed", '"Roboto Condensed", "Arial Narrow", "Segoe UI", sans-serif'],
  ["light", "Thin", '"Segoe UI Light", "Helvetica Neue", Roboto, sans-serif'],
  ["mono", "Mono", 'Consolas, "Roboto Mono", "Courier New", monospace'],
  ["script", "Script", '"Segoe Script", "Brush Script MT", "Dancing Script", cursive'],
  ["casual", "Casual", '"Comic Sans MS", "Segoe Print", "Coming Soon", cursive'],
];

const FILE_FONT = "file:";
const FIT_SHARE = 0.92;
const loaded = new Map();

// Makes an imported font usable by canvas text. data is the font file's bytes.
export async function loadFont(name, data) {
  if (loaded.has(name)) return;
  const face = new FontFace(`cap-${loaded.size}`, data);
  await face.load();
  document.fonts.add(face);
  loaded.set(name, face.family);
}

export const fontKey = (name) => `${FILE_FONT}${name}`;
export const importedFontNames = () => [...loaded.keys()];

function family(style) {
  if (style.font?.startsWith(FILE_FONT)) {
    const name = loaded.get(style.font.slice(FILE_FONT.length));
    if (name) return `"${name}", sans-serif`;
  }
  return (SYSTEM_FONTS.find(([key]) => key === style.font) || SYSTEM_FONTS[0])[2];
}

export function cssFont(style, size) {
  const weight = style.bold ? "bold" : style.font === "light" ? "300" : "normal";
  const stretch = style.font === "condensed" ? "condensed " : "";
  return `${style.italic ? "italic " : ""}${weight} ${stretch}${size}px ${family(style)}`;
}

const intColor = (value) => {
  const unsigned = value >>> 0;
  return `#${(unsigned & 0xFFFFFF).toString(16).padStart(6, "0")}`;
};

// Paints text centered on (centerX, centerY), turned for a spine if asked, and shrunk if needed
// so no line runs off the canvas. color is a CSS color.
export function drawLettering(c, text, style, size, color, centerX, centerY) {
  const lines = text.split("\n");
  const sideways = style.turn !== 0;
  const room = (sideways ? c.canvas.height : c.canvas.width) * FIT_SHARE;
  const measure = (fontSize) => {
    c.font = cssFont(style, fontSize);
    c.letterSpacing = `${style.tracking * fontSize}px`;
    return Math.max(...lines.map((line) => c.measureText(line).width));
  };
  let fitted = size;
  let widest = measure(fitted);
  if (widest > room) {
    fitted = size * room / widest;
    widest = measure(fitted);
  }
  const lineHeight = fitted * 1.17 * style.leading;
  const blockHeight = lineHeight * lines.length;
  c.save();
  c.translate(centerX, centerY);
  if (sideways) c.rotate(style.turn * Math.PI / 180);
  c.textBaseline = "middle";
  c.textAlign = style.align === 0 ? "left" : style.align === 2 ? "right" : "center";
  const x = style.align === 0 ? -widest / 2 : style.align === 2 ? widest / 2 : 0;
  const passes = [];
  if (style.outline > 0) passes.push("outline");
  passes.push("fill");
  passes.forEach((pass, index) => {
    if (style.shadow && index === 0) {
      // The shadow rides on the lowest pass, so it sits under the outline too.
      c.shadowColor = "rgba(0, 0, 0, 0.45)";
      c.shadowBlur = fitted * 0.08;
      c.shadowOffsetX = fitted * 0.03;
      c.shadowOffsetY = fitted * 0.042;
    } else {
      c.shadowColor = "transparent";
    }
    lines.forEach((line, row) => {
      const y = -blockHeight / 2 + lineHeight * (row + 0.5);
      if (pass === "outline") {
        c.lineJoin = "round";
        c.lineWidth = fitted * style.outline * 2;
        c.strokeStyle = intColor(style.outlineColor);
        c.strokeText(line, x, y);
      } else {
        c.fillStyle = color;
        c.fillText(line, x, y);
      }
    });
  });
  c.restore();
}
