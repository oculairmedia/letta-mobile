// Decodes the PNGs letta.chart writes (indexed, filter None), checking every chunk's CRC.
import assert from 'node:assert/strict';
import { inflateSync } from 'node:zlib';
import { crc32 } from '../png.mjs';

/** The chunks of a PNG, each checked against its CRC. */
export function parsePng(bytes) {
  assert.deepEqual([...bytes.subarray(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 'PNG signature');
  const chunks = [];
  for (let at = 8; at < bytes.length;) {
    const length = bytes.readUInt32BE(at);
    const type = bytes.toString('ascii', at + 4, at + 8);
    const data = bytes.subarray(at + 8, at + 8 + length);
    assert.equal(bytes.readUInt32BE(at + 8 + length), crc32(bytes.subarray(at + 4, at + 8 + length)), `${type} CRC`);
    chunks.push({ type, data });
    at += 12 + length;
  }
  return chunks;
}

/** The decoded image: IHDR fields and one palette index per pixel. */
export function decodePng(bytes) {
  const chunks = parsePng(bytes);
  assert.deepEqual(chunks.map((c) => c.type), ['IHDR', 'PLTE', 'IDAT', 'IEND']);
  const ihdr = chunks[0].data;
  const header = { width: ihdr.readUInt32BE(0), height: ihdr.readUInt32BE(4), bitDepth: ihdr[8], colorType: ihdr[9], interlace: ihdr[12] };
  const raw = inflateSync(chunks[2].data);
  assert.equal(raw.length, header.height * (header.width + 1), 'one filter byte and one index per pixel per row');
  const pixels = [];
  for (let y = 0; y < header.height; y++) {
    assert.equal(raw[y * (header.width + 1)], 0, `row ${y} filter None`);
    pixels.push(...raw.subarray(y * (header.width + 1) + 1, (y + 1) * (header.width + 1)));
  }
  return { header, palette: chunks[1].data, pixels };
}
