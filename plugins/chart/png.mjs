// letta.chart: a zero-dependency PNG writer and a tiny chart rasterizer.
//
// The PNG is 8-bit indexed colour (colour type 3): one palette index per pixel, every scanline
// filtered with "None", the whole image deflated once with node:zlib. That keeps a 320x200 chart
// at a few KiB and the encoder at a few dozen lines. No text is drawn: the element's fallback card
// and the live page carry the title and labels.
import { deflateSync } from 'node:zlib';

export const SIZE = Object.freeze({ width: 320, height: 200 });

/** Palette indices: background, axis, series, grid. */
const BG = 0, AXIS = 1, SERIES = 2, GRID = 3;
export const PALETTE = Object.freeze([[255, 255, 255], [96, 96, 104], [103, 80, 164], [228, 228, 234]]);

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

/** CRC-32 (ISO 3309, as PNG uses it) of [bytes]. */
export function crc32(bytes) {
  let c = 0xffffffff;
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** One PNG chunk: length, type, data, CRC over type and data. */
function chunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, crc]);
}

/** An indexed PNG of [pixels] (one palette index per pixel, row-major). */
export function encodePng({ width, height, palette, pixels }) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr.set([8, 3, 0, 0, 0], 8); // bit depth 8, colour type 3 (indexed), deflate, filter 0, no interlace
  const raw = Buffer.alloc(height * (width + 1));
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0; // filter type None
    raw.set(pixels.subarray(y * width, (y + 1) * width), y * (width + 1) + 1);
  }
  return Buffer.concat([
    SIGNATURE,
    chunk('IHDR', ihdr),
    chunk('PLTE', Buffer.from(palette.flat())),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** A canvas of palette indices with the few primitives a chart needs. */
function canvas(width, height) {
  const pixels = new Uint8Array(width * height).fill(BG);
  const within = (v, max) => v >= 0 && v < max;
  const set = (x, y, c) => {
    if (within(x, width) && within(y, height)) pixels[y * width + x] = c;
  };
  const rect = (x0, y0, x1, y1, c) => {
    for (let y = Math.round(Math.min(y0, y1)); y <= Math.round(Math.max(y0, y1)); y++) {
      for (let x = Math.round(Math.min(x0, x1)); x <= Math.round(Math.max(x0, x1)); x++) set(x, y, c);
    }
  };
  const line = (x0, y0, x1, y1, c, r = 1) => {
    const steps = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0)));
    for (let i = 0; i <= steps; i++) {
      const x = x0 + ((x1 - x0) * i) / steps, y = y0 + ((y1 - y0) * i) / steps;
      rect(x - r, y - r, x + r, y + r, c);
    }
  };
  return { pixels, rect, line };
}

/** The chart of [props] ({kind, labels[], values[]}) as palette pixels of [SIZE]. */
export function renderChart({ kind, values }, { width, height } = SIZE) {
  const { pixels, rect, line } = canvas(width, height);
  const left = 16, right = width - 12, top = 12, bottom = height - 16;
  const lo = Math.min(0, ...values), hi = Math.max(0, ...values);
  const span = hi - lo || 1;
  const yOf = (v) => top + ((hi - v) / span) * (bottom - top);
  for (let g = 0; g <= 4; g++) rect(left, top + (g * (bottom - top)) / 4, right, top + (g * (bottom - top)) / 4, GRID);
  const slot = (right - left) / values.length;
  const centre = (i) => left + slot * (i + 0.5);
  if (kind === 'line') {
    values.forEach((v, i) => i > 0 && line(centre(i - 1), yOf(values[i - 1]), centre(i), yOf(v), SERIES, 1));
    values.forEach((v, i) => rect(centre(i) - 3, yOf(v) - 3, centre(i) + 3, yOf(v) + 3, SERIES));
  } else {
    values.forEach((v, i) => rect(centre(i) - slot * 0.3, yOf(0), centre(i) + slot * 0.3, yOf(v), SERIES));
  }
  rect(left, yOf(0), right, yOf(0), AXIS);
  rect(left, top, left, bottom, AXIS);
  return { width, height, palette: PALETTE, pixels };
}

/** The PNG bytes of a chart. */
export const chartPng = (chart) => encodePng(renderChart(chart));
