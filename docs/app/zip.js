// A small zip writer and reader for backup files, the same format java.util.zip makes on Android.
// Entries are deflated with the browser's own CompressionStream.

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

async function transform(bytes, stream) {
  return new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(stream)).arrayBuffer());
}

function dosTime(date) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return [time, day];
}

// Builds a zip as a Blob from [path, bytes] pairs. Already-compressed files are only stored.
export async function writeZip(entries) {
  const parts = [];
  const central = [];
  let offset = 0;
  const encoder = new TextEncoder();
  const [time, day] = dosTime(new Date());
  for (const [path, data] of entries) {
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(await new Blob([data]).arrayBuffer());
    const name = encoder.encode(path);
    const stored = /\.(png|jpg|jpeg|bin|webp|gif)$/i.test(path);
    const body = stored ? bytes : await transform(bytes, new CompressionStream("deflate-raw"));
    const method = stored ? 0 : 8;
    const crc = crc32(bytes);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034B50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true);  // Names are UTF-8.
    local.setUint16(8, method, true);
    local.setUint16(10, time, true);
    local.setUint16(12, day, true);
    local.setUint32(14, crc, true);
    local.setUint32(18, body.length, true);
    local.setUint32(22, bytes.length, true);
    local.setUint16(26, name.length, true);
    local.setUint16(28, 0, true);
    parts.push(local.buffer, name, body);
    const header = new DataView(new ArrayBuffer(46));
    header.setUint32(0, 0x02014B50, true);
    header.setUint16(4, 20, true);
    header.setUint16(6, 20, true);
    header.setUint16(8, 0x0800, true);
    header.setUint16(10, method, true);
    header.setUint16(12, time, true);
    header.setUint16(14, day, true);
    header.setUint32(16, crc, true);
    header.setUint32(20, body.length, true);
    header.setUint32(24, bytes.length, true);
    header.setUint16(28, name.length, true);
    header.setUint32(42, offset, true);
    central.push(header.buffer, name);
    offset += 30 + name.length + body.length;
  }
  const centralSize = central.reduce((sum, part) => sum + part.byteLength, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054B50, true);
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, centralSize, true);
  end.setUint32(16, offset, true);
  return new Blob([...parts, ...central, end.buffer], { type: "application/zip" });
}

// Reads a zip into [path, bytes] pairs, using its central directory as Android's reader does.
export async function readZip(blob) {
  const buffer = await blob.arrayBuffer();
  const view = new DataView(buffer);
  let end = -1;
  for (let i = buffer.byteLength - 22; i >= Math.max(0, buffer.byteLength - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054B50) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("Not a zip file");
  const count = view.getUint16(end + 10, true);
  let position = view.getUint32(end + 16, true);
  const decoder = new TextDecoder();
  const entries = [];
  for (let n = 0; n < count; n++) {
    if (view.getUint32(position, true) !== 0x02014B50) throw new Error("Damaged zip file");
    const method = view.getUint16(position + 10, true);
    const compressed = view.getUint32(position + 20, true);
    const nameLength = view.getUint16(position + 28, true);
    const extraLength = view.getUint16(position + 30, true);
    const commentLength = view.getUint16(position + 32, true);
    const local = view.getUint32(position + 42, true);
    const name = decoder.decode(new Uint8Array(buffer, position + 46, nameLength));
    position += 46 + nameLength + extraLength + commentLength;
    if (name.endsWith("/")) continue;
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    const body = new Uint8Array(buffer, start, compressed);
    let bytes;
    if (method === 0) bytes = body.slice();
    else if (method === 8) bytes = await transform(body, new DecompressionStream("deflate-raw"));
    else throw new Error("Unsupported zip compression");
    entries.push([name, bytes]);
  }
  return entries;
}
