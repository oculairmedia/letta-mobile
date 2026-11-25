const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');

const resources = path.join(__dirname, 'src/wasmJsMain/resources');
const source = fs.readFileSync(path.join(resources, 'sw.js'), 'utf8');

// letta-mobile-ckmub.17.19: sw.js names its cache after the build that stamped it. @BUILD_ID@ is
// expanded by :web's wasmJsProcessResources (build.gradle.kts) into the short git sha of the
// built tree, so every deploy serves from its own cache and its activate prunes every older
// letta-web-* cache. This test reads the raw resource, so the token is what it sees.
const cacheName = 'letta-web-@BUILD_ID@';

function worker(scope, options = {}) {
    const listeners = {};
    const shell = { page: 'app shell' };
    const asPath = (request) => new URL(request.url ?? request, scope).pathname;
    // CacheStorage as the browser holds it: what previous requests cached (the navigation
    // fallback keys the same './index.html' the worker asks for), plus seeded entries.
    const warm = new Map([[asPath('./index.html'), shell]]);
    const warmEntries = (options.warm ?? []).map((url) => {
        const entry = { url, warm: true };
        warm.set(asPath(url), entry);
        return entry;
    });
    const fetches = [];
    const openedCaches = [];
    const cachedPuts = [];
    const deletedCaches = [];
    vm.runInNewContext(source, {
        URL,
        self: {
            registration: { scope },
            location: { origin: new URL(scope).origin },
            clients: { claim: () => {} },
            addEventListener: (type, handler) => { listeners[type] = handler; },
        },
        fetch: async (request) => {
            fetches.push(asPath(request));
            return options.fetch ? options.fetch(request) : Promise.reject(new Error('offline'));
        },
        caches: {
            match: async (request) => warm.get(asPath(request)),
            open: async (name) => {
                openedCaches.push(name);
                return {
                    put: async (request, copy) => {
                        warm.set(asPath(request), copy);
                        cachedPuts.push([name, asPath(request)]);
                    },
                    add: async () => {},
                };
            },
            keys: async () => options.caches ?? [],
            delete: async (key) => {
                deletedCaches.push(key);
                return true;
            },
        },
        Response: { error: () => ({ error: true }) },
    });
    return { listeners, shell, warmEntries, fetches, openedCaches, cachedPuts, deletedCaches };
}

// The two pre-existing behaviors: /api requests are never intercepted, and navigation keeps its
// offline fallback. These ran before letta-mobile-ckmub.17.19 and must keep passing.
for (const prefix of ['/', '/letta-mobile/']) {
    const scope = `https://example.com${prefix}`;

    test(`docs bypass app offline handling under ${prefix}`, () => {
        const { listeners } = worker(scope);
        for (const suffix of ['api', 'api/', 'api/core/ids/index.html', 'api/styles/main.css']) {
            let intercepted = false;
            listeners.fetch({
                request: { method: 'GET', mode: 'navigate', url: new URL(suffix, scope).href },
                respondWith: () => { intercepted = true; },
            });
            assert.equal(intercepted, false, suffix);
        }
    });

    test(`app navigation keeps its offline fallback under ${prefix}`, async () => {
        const { listeners, shell } = worker(scope);
        let response;
        listeners.fetch({
            request: { method: 'GET', mode: 'navigate', url: new URL('chat', scope).href },
            respondWith: (result) => { response = result; },
        });
        assert.equal(await response, shell);
    });
}

// letta-mobile-ckmub.17.19: the cache-first, build-versioned worker.

test('sw.js names its cache after the build that stamped it', () => {
    assert.ok(source.includes(`const CACHE = '${cacheName}';`),
        'the raw sw.js carries the @BUILD_ID@ token');
    const gradle = fs.readFileSync(path.join(__dirname, 'build.gradle.kts'), 'utf8');
    assert.ok(gradle.includes('filesMatching(listOf("index.html", "manifest.webmanifest", "sw.js"))'),
        'wasmJsProcessResources filters sw.js with the shell documents');
    assert.ok(gradle.includes('@BUILD_ID@'), 'the token is replaced with the build id');
    assert.ok(gradle.includes('providers.exec'), 'the id comes through the provider API');
    assert.ok(!/ProcessBuilder|Runtime\.getRuntime/.test(gradle), 'no eager process spawn');
});

test('a warm wasm/js/riv reload serves from the cache without touching the network', async () => {
    const scope = 'https://example.com/';
    const { listeners, warmEntries, fetches } = worker(scope, {
        warm: ['./skiko.wasm', './letta-web.js', './mascot/mascot.riv'],
    });
    const responses = [];
    for (const entry of warmEntries) {
        let response;
        listeners.fetch({
            request: { method: 'GET', mode: 'no-cors', url: new URL(entry.url, scope).href },
            respondWith: (result) => { response = result; },
            waitUntil: () => {},
        });
        responses.push(await response);
    }
    assert.deepEqual(responses, warmEntries);
    assert.equal(fetches.length, 0, 'cache-first: no network round-trip for warm assets');
});

test('a cold wasm download populates the build-stamped cache', async () => {
    const scope = 'https://example.com/';
    const copy = { copy: true };
    const network = { ok: true, network: true, clone: () => copy };
    const { listeners, fetches, openedCaches, cachedPuts } = worker(scope, { fetch: () => network });
    let response;
    const side = [];
    const event = {
        request: { method: 'GET', mode: 'no-cors', url: `${scope}iroh/letta_iroh_wasm_bg.wasm` },
        respondWith: (result) => { response = result; },
        waitUntil: (promise) => { side.push(promise); },
    };
    listeners.fetch(event);
    assert.equal(await response, network, 'the miss falls through to the network');
    await Promise.all(side);
    assert.deepEqual(fetches, ['/iroh/letta_iroh_wasm_bg.wasm']);
    assert.deepEqual(openedCaches, [cacheName], 'the copy lands in the build-stamped cache');
    assert.deepEqual(cachedPuts, [[cacheName, '/iroh/letta_iroh_wasm_bg.wasm']]);
    // The next open serves the cached copy without another network round-trip.
    fetches.length = 0;
    let cachedResponse;
    listeners.fetch({
        ...event,
        respondWith: (result) => { cachedResponse = result; },
        waitUntil: () => {},
    });
    assert.equal(await cachedResponse, copy);
    assert.equal(fetches.length, 0);
});

test('page loads stay network-first while the network answers', async () => {
    const scope = 'https://example.com/';
    const network = { ok: true, network: true, clone: () => ({}) };
    const { listeners, fetches } = worker(scope, { fetch: () => network });
    // The shell documents decide which build a visitor runs, so they go to the network even
    // though index.html is warm in the cache (it is the offline fallback).
    for (const [url, mode] of [
        ['./index.html', 'no-cors'],
        ['./chat', 'navigate'],
        ['./manifest.webmanifest', 'no-cors'],
        ['./sw.js', 'no-cors'],
    ]) {
        let response;
        listeners.fetch({
            request: { method: 'GET', mode, url: new URL(url, scope).href },
            respondWith: (result) => { response = result; },
            waitUntil: () => {},
        });
        assert.equal(await response, network, url);
    }
    assert.deepEqual(fetches, ['/index.html', '/chat', '/manifest.webmanifest', '/sw.js']);
});

test('activate prunes old letta-web-* caches but keeps this build and other sites', async () => {
    const { listeners, deletedCaches } = worker('https://example.com/', {
        caches: ['letta-web-v1', cacheName, 'other-app'],
    });
    const side = [];
    listeners.activate({ waitUntil: (promise) => { side.push(promise); } });
    await Promise.all(side);
    assert.deepEqual(deletedCaches, ['letta-web-v1']);
});

// letta-mobile-ckmub.17.19: the page itself — parallel wasm preload, eager init, and the
// first-paint boot loader that leaves when Compose mounts its canvas.
const page = fs.readFileSync(path.join(resources, 'index.html'), 'utf8');

test('index.html preloads the Iroh wasm and the app bundle in parallel', () => {
    // The browser starts the transport wasm before the module graph runs (as fetch, to match the
    // CORS mode of the module's init() call) and the app script before first paint.
    assert.ok(page.includes('<link rel="preload" href="./iroh/letta_iroh_wasm_bg.wasm" as="fetch" type="application/wasm" crossorigin>'));
    assert.ok(page.includes('<link rel="preload" href="letta-web.js" as="script">'));
});

test('index.html starts the Iroh wasm eagerly and never reports an unhandled rejection', () => {
    assert.ok(page.includes('initialize().catch(() => {});'));
});

test('index.html paints a first-frame loader that leaves when the Compose canvas mounts', () => {
    const body = page.slice(page.indexOf('<body'));
    assert.ok(page.includes('<div id="letta-boot"'));
    assert.ok(body.includes('MutationObserver'), 'a MutationObserver watches for the canvas');
    assert.ok(body.includes("querySelector('canvas')"), 'canvas detection drives the removal');
    assert.ok(body.includes('remove()'), 'the observer removes the loader');
});

test('index.html stays well-formed and every inline script parses', () => {
    assert.ok(page.startsWith('<!DOCTYPE html>'));
    assert.ok(page.trimEnd().endsWith('</html>'));
    for (const [open, close] of [['<script', '</script>'], ['<style', '</style>'], ['<div', '</div>']]) {
        assert.equal(page.split(open).length - 1, page.split(close).length - 1, `${open} is closed`);
    }
    const inline = /<script\b([^>]*)>([\s\S]*?)<\/script>/g;
    const scripts = [];
    let match;
    while ((match = inline.exec(page)) !== null) {
        if (!/\bsrc=/.test(match[1])) {
            scripts.push({ module: /\btype="module"/.test(match[1]), code: match[2] });
        }
    }
    assert.ok(scripts.length >= 2, 'the head module script and the loader script are inline');
    scripts.forEach(({ module, code }, index) => {
        const file = path.join(os.tmpdir(), `letta-sw-page-${index}${module ? '.mjs' : '.js'}`);
        fs.writeFileSync(file, code);
        try {
            const check = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });
            assert.equal(check.status, 0, check.stderr);
        } finally {
            fs.rmSync(file, { force: true });
        }
    });
});
