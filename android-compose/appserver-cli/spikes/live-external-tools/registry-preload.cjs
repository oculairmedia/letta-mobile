// Spike preload: observe letta-code's global external-tool registry (Symbol.for('@letta/externalTools')).
const fs = require('fs');
const KEY = Symbol.for('@letta/externalTools');
const LOG = process.env.SPIKE_REGISTRY_LOG;
class LoggingMap extends Map {
  set(k, v) { const r = super.set(k, v); dump('set', k); return r; }
  delete(k) { const r = super.delete(k); dump('delete', k); return r; }
}
function dump(op, k) {
  const names = Array.from(super_values()).map(t => t.name).sort();
  fs.appendFileSync(LOG, JSON.stringify({ t: Date.now(), op, key: String(k), names }) + '\n');
}
function super_values() { return Map.prototype.values.call(globalThis[KEY]); }
globalThis[KEY] = new LoggingMap();
