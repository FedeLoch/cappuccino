// Layered PSD files, ported from PsdWriter.kt and PsdReader.kt on Android. Pixels are premultiplied
// RGBA for the whole canvas, as the engine keeps them.

const OPEN_FOLDER = 1;
const GROUP_START = 3;
const BLEND_KEYS = [
  "norm", "mul ", "scrn", "lddg", "dark", "lite", "sLit", "fsub", "smud",
  "over", "hLit", "div ", "idiv", "diff", "hue ", "sat ", "colr", "lum ",
];

// A growing big-endian byte buffer, like DataOutputStream over a ByteArrayOutputStream.
class Bytes {
  constructor(size = 1 << 16) {
    this.buffer = new Uint8Array(size);
    this.length = 0;
  }
  ensure(extra) {
    if (this.length + extra <= this.buffer.length) return;
    let size = this.buffer.length * 2;
    while (size < this.length + extra) size *= 2;
    const next = new Uint8Array(size);
    next.set(this.buffer.subarray(0, this.length));
    this.buffer = next;
  }
  byte(value) {
    this.ensure(1);
    this.buffer[this.length++] = value & 255;
  }
  short(value) {
    this.byte(value >> 8);
    this.byte(value);
  }
  int(value) {
    this.byte(value >>> 24);
    this.byte(value >>> 16);
    this.byte(value >>> 8);
    this.byte(value);
  }
  key(text) {
    for (let i = 0; i < text.length; i++) this.byte(text.charCodeAt(i));
  }
  bytes(array, start = 0, end = array.length) {
    this.ensure(end - start);
    this.buffer.set(array.subarray(start, end), this.length);
    this.length += end - start;
  }
  result() {
    return this.buffer.subarray(0, this.length);
  }
}

// Wraps each run of layers in a group with the two records PSD uses for it.
export function withPsdGroups(layers, groupOf, folder) {
  const result = [];
  let open = null;
  const close = () => {
    if (open) result.push(folder(open));
    open = null;
  };
  layers.forEach((layer, index) => {
    const group = groupOf(index);
    if (group !== open) {
      close();
      if (group) {
        result.push({ name: "</Layer group>", opacity: 1, visible: true, blend: 0, clipped: false, rgba: new Uint8Array(0), section: GROUP_START });
        open = group;
      }
    }
    result.push(layer);
  });
  close();
  return result;
}

export const psdFolder = (name, opacity, visible) => ({ name, opacity, visible, blend: 0, clipped: false, rgba: new Uint8Array(0), section: OPEN_FOLDER });

function packRow(row, out) {
  let index = 0;
  while (index < row.length) {
    let run = 1;
    while (index + run < row.length && run < 128 && row[index + run] === row[index]) run++;
    if (run >= 2) {
      out.byte(1 - run);
      out.byte(row[index]);
      index += run;
      continue;
    }
    const start = index;
    index++;
    while (index < row.length && index - start < 128) {
      if (index + 1 < row.length && row[index] === row[index + 1]) break;
      index++;
    }
    out.byte(index - start - 1);
    out.bytes(row, start, index);
  }
}

// Separates one image into a channel, with its row lengths and packed rows.
function encodeChannel(rgba, width, height, channel, opaque, withKind) {
  const lengths = new Bytes(height * 2);
  const body = new Bytes(width * height / 4 + 1024);
  const row = new Uint8Array(width);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const offset = (y * width + x) * 4;
      const alpha = rgba[offset + 3];
      const value = rgba[offset + channel];
      if (channel === 3 || opaque || alpha === 255) row[x] = value;
      else if (alpha === 0) row[x] = 255;
      else row[x] = Math.min(255, Math.floor(value * 255 / alpha));
    }
    const before = body.length;
    packRow(row, body);
    lengths.short(body.length - before);
  }
  const result = new Bytes(lengths.length + body.length + 2);
  if (withKind) result.short(1);
  result.bytes(lengths.result());
  result.bytes(body.result());
  return result.result();
}

function layerExtras(name, section) {
  const out = new Bytes(256);
  out.int(0);
  out.int(0);
  const ascii = [...name].filter((ch) => ch.charCodeAt(0) >= 32 && ch.charCodeAt(0) <= 126).join("").slice(0, 255);
  const padded = Math.floor((ascii.length + 4) / 4) * 4;
  out.byte(ascii.length);
  out.key(ascii);
  for (let i = 0; i < padded - 1 - ascii.length; i++) out.byte(0);
  const unicode = new Bytes(64);
  unicode.int(name.length);
  for (let i = 0; i < name.length; i++) unicode.short(name.charCodeAt(i));
  if (unicode.length % 2 !== 0) unicode.byte(0);
  out.key("8BIM");
  out.key("luni");
  out.int(unicode.length);
  out.bytes(unicode.result());
  if (section !== 0) {
    out.key("8BIM");
    out.key("lsct");
    out.int(4);
    out.int(section);
  }
  return out.result();
}

// Writes a layered PSD with PackBits compression. layers are listed bottom first, and merged is
// the flattened artwork.
export function writePsd(width, height, layers, merged) {
  const records = new Bytes();
  records.short(layers.length);
  const channels = layers.map((layer) => {
    if (layer.section) return [0, 1, 2, 3].map(() => new Uint8Array([0, 0]));
    return [3, 0, 1, 2].map((channel) => encodeChannel(layer.rgba, width, height, channel, false, true));
  });
  layers.forEach((layer, index) => {
    const empty = layer.section !== 0 && layer.section !== undefined;
    records.int(0);
    records.int(0);
    records.int(empty ? 0 : height);
    records.int(empty ? 0 : width);
    records.short(4);
    [-1, 0, 1, 2].forEach((id, position) => {
      records.short(id & 0xFFFF);
      records.int(channels[index][position].length);
    });
    records.key("8BIM");
    records.key(BLEND_KEYS[layer.blend] || "norm");
    records.byte(Math.trunc(layer.opacity * 255));
    records.byte(layer.clipped ? 1 : 0);
    records.byte(layer.visible ? 0 : 2);
    records.byte(0);
    const extras = layerExtras(layer.name, layer.section || 0);
    records.int(extras.length);
    records.bytes(extras);
  });
  for (const layer of channels) for (const data of layer) records.bytes(data);
  if (records.length % 2 !== 0) records.byte(0);
  const info = records.result();

  const out = new Bytes(info.length + width * height + 1024);
  out.key("8BPS");
  out.short(1);
  for (let i = 0; i < 6; i++) out.byte(0);
  out.short(3);
  out.int(height);
  out.int(width);
  out.short(8);
  out.short(3);
  out.int(0);
  out.int(0);
  // Layer and mask section: its length, the layer info, then an empty global mask.
  out.int(info.length + 8);
  out.int(info.length);
  out.bytes(info);
  out.int(0);
  out.short(1);
  const encoded = [0, 1, 2].map((channel) => encodeChannel(merged, width, height, channel, true, false));
  const split = height * 2;
  for (const data of encoded) out.bytes(data, 0, split);
  for (const data of encoded) out.bytes(data, split);
  return out.result();
}

// --- Reading ---------------------------------------------------------------------------------

const BLENDS = {
  "norm": 0, "mul ": 1, "scrn": 2, "lddg": 3, "dark": 4, "lite": 5, "sLit": 6, "fsub": 7, "smud": 8,
  "over": 9, "hLit": 10, "div ": 11, "idiv": 12, "diff": 13, "hue ": 14, "sat ": 15, "colr": 16, "lum ": 17,
};

class Reader {
  constructor(buffer) {
    this.view = new DataView(buffer);
    this.bytes = new Uint8Array(buffer);
    this.position = 0;
  }
  byte() { return this.view.getUint8(this.position++); }
  signedByte() { return this.view.getInt8(this.position++); }
  short() { const value = this.view.getInt16(this.position); this.position += 2; return value; }
  unsignedShort() { const value = this.view.getUint16(this.position); this.position += 2; return value; }
  int() { const value = this.view.getInt32(this.position); this.position += 4; return value; }
  ascii(count) {
    let text = "";
    for (let i = 0; i < count; i++) text += String.fromCharCode(this.bytes[this.position + i]);
    this.position += count;
    return text;
  }
  skipSection() {
    const length = this.int();
    this.position += length;
  }
}

// Reads 8-bit RGB PSD files. One level of group folders is kept, and nested ones join it.
export function readPsd(buffer) {
  const input = new Reader(buffer);
  if (input.ascii(4) !== "8BPS" || input.short() !== 1) throw new Error("Not a PSD file");
  input.position += 6;
  const colorChannels = input.short();
  const height = input.int();
  const width = input.int();
  if (input.short() !== 8 || input.short() !== 3) throw new Error("Only 8-bit RGB is supported");
  input.skipSection();
  input.skipSection();
  const sectionLength = input.int();
  const sectionEnd = input.position + sectionLength;
  const groups = [];
  const layers = sectionLength > 4 ? readLayers(input, width, height, groups) : [];
  input.position = sectionEnd;
  if (layers.length) return { width, height, layers, groups };
  return { width, height, layers: [readMerged(input, width, height, colorChannels)], groups };
}

function readLayers(input, width, height, groups) {
  input.int();
  const count = Math.abs(input.short());
  const records = Array.from({ length: count }, () => readRecord(input));
  const layers = [];
  let depth = 0;
  let firstInGroup = 0;
  for (const record of records) {
    if (record.section === 3) {
      if (depth++ === 0) firstInGroup = layers.length;
    } else if (record.isDivider && depth > 0 && --depth === 0) {
      groups.push(record.name);
      for (let index = firstInGroup; index < layers.length; index++) layers[index].group = groups.length;
    }
    const channels = new Map();
    for (const channel of record.channels) {
      const start = input.position;
      channels.set(channel.id, record.isDivider ? null : readChannel(input, record, channel));
      input.position = start + channel.length;
    }
    if (!record.isDivider) layers.push(assemble(record, channels, width, height));
  }
  return layers;
}

function readRecord(input) {
  const top = input.int();
  const left = input.int();
  const bottom = input.int();
  const right = input.int();
  const channelCount = input.short();
  const channels = Array.from({ length: channelCount }, () => ({ id: input.short(), length: input.int() }));
  input.ascii(4);
  const blend = input.ascii(4);
  const opacity = input.byte();
  const clipped = input.byte() !== 0;
  const flags = input.byte();
  input.byte();
  const extraLength = input.int();
  const extraEnd = extraLength + input.position;
  input.skipSection();
  input.skipSection();
  const nameLength = input.byte();
  let name = input.ascii(nameLength);
  input.position += (4 - (nameLength + 1) % 4) % 4;
  let divider = false;
  let section = 0;
  while (input.position + 12 <= extraEnd) {
    input.ascii(4);
    const key = input.ascii(4);
    const length = input.int();
    const next = input.position + length + length % 2;
    if (key === "lsct" || key === "lsdk") {
      section = input.int();
      divider = section !== 0;
    }
    if (key === "luni") {
      const size = input.int();
      name = "";
      for (let i = 0; i < size; i++) name += String.fromCharCode(input.unsignedShort());
    }
    input.position = Math.min(next, extraEnd);
  }
  input.position = extraEnd;
  return { top, left, bottom, right, channels, blend, opacity, clipped, visible: (flags & 2) === 0, name, isDivider: divider, section };
}

function readChannel(input, record, channel) {
  const width = record.right - record.left;
  const height = record.bottom - record.top;
  const compression = input.short();
  const pixels = new Uint8Array(Math.max(0, width * height));
  if (width <= 0 || height <= 0 || channel.id < -1) return pixels;
  if (compression === 0) {
    pixels.set(input.bytes.subarray(input.position, input.position + pixels.length));
    input.position += pixels.length;
  } else if (compression === 1) {
    const lengths = Array.from({ length: height }, () => input.unsignedShort());
    for (let row = 0; row < height; row++) {
      const next = input.position + lengths[row];
      unpack(input, pixels, row * width, width);
      input.position = next;
    }
  } else {
    throw new Error("Unsupported PSD compression");
  }
  return pixels;
}

function unpack(input, target, start, count) {
  let offset = start;
  const end = start + count;
  while (offset < end) {
    const code = input.signedByte();
    if (code >= 0) {
      const length = Math.min(code + 1, end - offset);
      target.set(input.bytes.subarray(input.position, input.position + length), offset);
      input.position += code + 1;
      offset += length;
    } else if (code !== -128) {
      const length = Math.min(1 - code, end - offset);
      target.fill(input.byte(), offset, offset + length);
      offset += length;
    }
  }
}

function assemble(record, channels, width, height) {
  const rgba = new Uint8Array(width * height * 4);
  const layerWidth = record.right - record.left;
  const alphaChannel = channels.get(-1);
  for (let y = Math.max(0, record.top); y < Math.min(height, record.bottom); y++) {
    for (let x = Math.max(0, record.left); x < Math.min(width, record.right); x++) {
      const source = (y - record.top) * layerWidth + x - record.left;
      const alpha = alphaChannel ? alphaChannel[source] : 255;
      const target = (y * width + x) * 4;
      for (let channel = 0; channel < 3; channel++) {
        const values = channels.get(channel);
        rgba[target + channel] = Math.floor((values ? values[source] : 0) * alpha / 255);
      }
      rgba[target + 3] = alpha;
    }
  }
  return {
    name: record.name || "Layer", opacity: record.opacity / 255, visible: record.visible,
    blend: BLENDS[record.blend] ?? 0, clipped: record.clipped, rgba, group: 0,
  };
}

function readMerged(input, width, height, channels) {
  const compression = input.short();
  const planes = Array.from({ length: channels }, () => new Uint8Array(width * height));
  if (compression === 1) {
    const lengths = Array.from({ length: channels * height }, () => input.unsignedShort());
    for (let channel = 0; channel < channels; channel++) {
      for (let row = 0; row < height; row++) {
        const next = input.position + lengths[channel * height + row];
        unpack(input, planes[channel], row * width, width);
        input.position = next;
      }
    }
  } else {
    for (const plane of planes) {
      plane.set(input.bytes.subarray(input.position, input.position + plane.length));
      input.position += plane.length;
    }
  }
  const rgba = new Uint8Array(width * height * 4);
  for (let index = 0; index < width * height; index++) {
    for (let channel = 0; channel < 3; channel++) rgba[index * 4 + channel] = planes[channel][index];
    rgba[index * 4 + 3] = 255;
  }
  return { name: "Background", opacity: 1, visible: true, blend: 0, clipped: false, rgba, group: 0 };
}
