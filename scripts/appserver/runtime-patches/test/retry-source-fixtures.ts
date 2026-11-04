import assert from "node:assert/strict";

type SourceBounds = { start: string; end: string };

export function retrySourceBetween(source: string, { start, end }: SourceBounds): string {
  const from = source.indexOf(start);
  assert.notEqual(from, -1, `missing retry fixture start: ${start}`);
  const to = source.indexOf(end, from + start.length);
  assert.notEqual(to, -1, `missing retry fixture end: ${end}`);
  return source.slice(from, to);
}

export function retryAnchors(bundle: string): { refresh: string; persist: string } {
  return {
    refresh: retrySourceBetween(bundle, { start: "function refreshTurnInputOtidsForNewRequest(", end: "\nfunction rebuildTurnInputWithFreshDenials(" }),
    persist: retrySourceBetween(bundle, { start: "  appendUserLocalMessage(", end: "  applyVisibleChunkToLocalMessages(" }),
  };
}
