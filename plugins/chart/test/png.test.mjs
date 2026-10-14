// The PNG writer: parse the bytes back (signature, chunk CRCs, IHDR, PLTE, inflated IDAT).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { PALETTE, SIZE, chartPng, crc32, encodePng, renderChart } from '../png.mjs';
import { decodePng } from './png-decode.mjs';

test('crc32 matches the standard check value', () => {
  assert.equal(crc32(Buffer.from('123456789')), 0xcbf43926);
});

test('a hand-made 2x2 image round-trips', () => {
  const png = encodePng({ width: 2, height: 2, palette: [[0, 0, 0], [255, 0, 0]], pixels: Uint8Array.from([0, 1, 1, 0]) });
  const { header, palette, pixels } = decodePng(png);
  assert.deepEqual(header, { width: 2, height: 2, bitDepth: 8, colorType: 3, interlace: 0 });
  assert.deepEqual([...palette], [0, 0, 0, 255, 0, 0]);
  assert.deepEqual(pixels, [0, 1, 1, 0]);
});

test('a chart PNG is a valid indexed image of the chart size, small and deterministic', () => {
  const chart = { kind: 'bar', labels: ['a', 'b', 'c'], values: [1, -2, 3] };
  const png = chartPng(chart);
  const { header, palette, pixels } = decodePng(png);
  assert.equal(header.width, SIZE.width);
  assert.equal(header.height, SIZE.height);
  assert.equal(palette.length, PALETTE.length * 3);
  assert.ok(pixels.every((p) => p < PALETTE.length), 'every index is in the palette');
  assert.ok(pixels.includes(2), 'the series is drawn');
  assert.ok(png.length < 8 * 1024, `${png.length} bytes`);
  assert.deepEqual(chartPng(chart), png);
});

test('bar and line render differently', () => {
  const bar = renderChart({ kind: 'bar', values: [1, 2, 3] }).pixels;
  const line = renderChart({ kind: 'line', values: [1, 2, 3] }).pixels;
  assert.notDeepEqual(bar, line);
});
