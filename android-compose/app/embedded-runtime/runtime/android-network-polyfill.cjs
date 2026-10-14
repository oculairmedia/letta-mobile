// Shipped next to letta.js and preloaded with `node --require`. When
// LETTA_ANDROID_NETWORK_BRIDGE_URL is set, routes fetch (forced or when absent) and dns.lookup
// through the app's AndroidNetworkBridge HTTP endpoint, since nodejs-mobile cannot use Android's
// resolver/network stack directly. IP literals short-circuit locally.
'use strict';
const bridgeBaseUrl = process.env.LETTA_ANDROID_NETWORK_BRIDGE_URL;
if (bridgeBaseUrl) {
  const http = require('http');
  const dns = require('dns');
  const { Buffer } = require('buffer');
  function postJson(path, payload) {
    return new Promise((resolve, reject) => {
      const body = JSON.stringify(payload || {});
      const url = new URL(path, bridgeBaseUrl);
      const req = http.request({
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
        },
      }, (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let parsed;
          try { parsed = text ? JSON.parse(text) : {}; } catch (error) { reject(error); return; }
          if (res.statusCode >= 400) {
            const message = parsed && parsed.error && parsed.error.message ? parsed.error.message : 'Android bridge HTTP ' + res.statusCode;
            reject(new Error(message));
          } else {
            resolve(parsed);
          }
        });
      });
      req.on('error', reject);
      req.write(body);
      req.end();
    });
  }
  function postFetch(payload) {
    return new Promise((resolve, reject) => {
      const body = JSON.stringify(payload || {});
      const url = new URL('/fetch', bridgeBaseUrl);
      const req = http.request({
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
        },
      }, (res) => {
        const rawHeaders = res.headers || {};
        const status = Number(rawHeaders['x-android-bridge-upstream-status'] || res.statusCode || 0);
        const encodedStatusText = rawHeaders['x-android-bridge-upstream-status-text'];
        const statusText = encodedStatusText ? decodeURIComponent(String(encodedStatusText)) : (res.statusMessage || '');
        if (res.statusCode >= 400 && !rawHeaders['x-android-bridge-upstream-status']) {
          const chunks = [];
          res.on('data', (chunk) => chunks.push(chunk));
          res.on('end', () => {
            const text = Buffer.concat(chunks).toString('utf8');
            let parsed;
            try { parsed = text ? JSON.parse(text) : {}; } catch (_) { parsed = {}; }
            const message = parsed && parsed.error && parsed.error.message ? parsed.error.message : 'Android bridge HTTP ' + res.statusCode;
            reject(new Error(message));
          });
          return;
        }
        res.on('error', reject);
        resolve({ status, statusText, headers: rawHeaders, body: res });
      });
      req.on('error', reject);
      req.write(body);
      req.end();
    });
  }
  const nativeFetch = globalThis.fetch;
  const forceAndroidFetch = process.env.LETTA_ANDROID_NETWORK_BRIDGE_FORCE_FETCH === '1';
  if (forceAndroidFetch || typeof nativeFetch !== 'function') {
    globalThis.fetch = async function androidBridgeFetch(input, init = {}) {
      const url = typeof input === 'string' ? input : (input && input.url) ? input.url : String(input);
      const method = init.method || (input && input.method) || 'GET';
      const headers = {};
      const sourceHeaders = init.headers || (input && input.headers);
      if (sourceHeaders) {
        if (typeof sourceHeaders.forEach === 'function') {
          sourceHeaders.forEach((value, key) => { headers[key] = value; });
        } else if (Array.isArray(sourceHeaders)) {
          for (const [key, value] of sourceHeaders) headers[key] = value;
        } else {
          Object.assign(headers, sourceHeaders);
        }
      }
      const requestBody = init.body == null ? undefined : String(init.body);
      const bridged = await postFetch({ url, method, headers, body: requestBody });
      const rawHeaders = bridged.headers || {};
      const headerEntries = Object.entries(rawHeaders)
        .filter(([key]) => !String(key).toLowerCase().startsWith('x-android-bridge-upstream-'))
        .map(([key, value]) => [String(key).toLowerCase(), Array.isArray(value) ? value.join(', ') : String(value)]);
      const responseHeaders = {
        get(name) {
          const key = String(name).toLowerCase();
          const found = headerEntries.find(([entryKey]) => entryKey === key);
          return found ? found[1] : null;
        },
        has(name) { return this.get(name) !== null; },
        forEach(callback, thisArg) { headerEntries.forEach(([key, value]) => callback.call(thisArg, value, key, responseHeaders)); },
        entries() { return headerEntries[Symbol.iterator](); },
        keys() { return headerEntries.map(([key]) => key)[Symbol.iterator](); },
        values() { return headerEntries.map(([, value]) => value)[Symbol.iterator](); },
        [Symbol.iterator]() { return this.entries(); },
      };
      const readBody = async () => {
        const chunks = [];
        for await (const chunk of bridged.body) chunks.push(Buffer.from(chunk));
        return Buffer.concat(chunks);
      };
      return {
        ok: bridged.status >= 200 && bridged.status < 300,
        status: bridged.status,
        statusText: bridged.statusText || '',
        url,
        headers: responseHeaders,
        body: bridged.body,
        arrayBuffer: async () => {
          const responseBody = await readBody();
          return responseBody.buffer.slice(responseBody.byteOffset, responseBody.byteOffset + responseBody.byteLength);
        },
        text: async () => (await readBody()).toString('utf8'),
        json: async () => JSON.parse((await readBody()).toString('utf8')),
      };
    };
    globalThis.fetch.__androidBridgeOriginal = nativeFetch;
  }
  const originalLookup = dns.lookup;
  // IP literals need no DNS — resolving them through the bridge adds
  // an HTTP round-trip per request (e.g. every call to a LAN provider
  // like 192.168.50.90). Short-circuit them locally.
  const IPV4_RE = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;
  function bridgeLookup(hostname, options, callback) {
    if (typeof options === 'function') { callback = options; options = {}; }
    const wantsAll = options && options.all === true;
    if (typeof hostname === 'string' && (IPV4_RE.test(hostname) || hostname.indexOf(':') !== -1)) {
      const family = hostname.indexOf(':') !== -1 ? 6 : 4;
      if (wantsAll) { callback(null, [{ address: hostname, family }]); }
      else { callback(null, hostname, family); }
      return;
    }
    postJson('/dns/lookup', { hostname }).then((result) => {
      const addresses = result.addresses || [];
      if (wantsAll) {
        callback(null, addresses.map((item) => ({ address: item.address, family: item.family })));
      } else {
        const first = addresses[0];
        if (!first) callback(new Error('No DNS records for ' + hostname));
        else callback(null, first.address, first.family);
      }
    }, callback);
  }
  dns.lookup = bridgeLookup;
  const dnsPromises = dns.promises;
  if (dnsPromises) {
    dnsPromises.lookup = async (hostname, options = {}) => new Promise((resolve, reject) => {
      bridgeLookup(hostname, options, (error, address, family) => {
        if (error) reject(error);
        else resolve(options && options.all ? address : { address, family });
      });
    });
  }
  dns.lookup.__androidBridgeOriginal = originalLookup;
}
