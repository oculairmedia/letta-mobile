// letta-mobile-2cson: makes Letta Web installable and lets its shell open offline.
// letta-mobile-ckmub.17.19: build-versioned, cache-first assets. @BUILD_ID@ below is expanded by
// :web's wasmJsProcessResources (web/build.gradle.kts) into the short git sha of the built tree,
// so a deploy ships a new cache name with its new bundle, and activate here prunes every older
// letta-web-* cache: no build serves another build's files.
//
// Page loads stay network-first: online, the app shell is fetched from the network, so a new
// deploy is picked up on the next open and an old cached build never shadows it. The copy kept
// here is only a fallback for when the network is gone. Talking to agents still needs the
// network (Iroh relays or the App Server socket); offline only means the app opens.
//
// The heavy, immutable bundle files (the ~10 MB Skiko/Compose Wasm above all) are cache-first:
// with a warm cache a reload serves them with no network round-trip at all, and a fresh build
// downloads them once, into this build's own cache, until the next deploy.
const CACHE = 'letta-web-@BUILD_ID@';
const SHELL = ['./', './index.html', './manifest.webmanifest', './favicon.svg'];

// The shell documents and this worker itself decide which build a visitor runs, so they stay
// network-first even when a same-named copy is already cached.
const shellPaths = ['index.html', 'manifest.webmanifest', 'sw.js'].map(
    (name) => new URL(name, self.registration.scope).pathname,
);

// The page's heavy bundle files, every one tied to the build that cached it: served straight
// from the cache when the build's own copy is already there.
const ASSET = /\.(?:wasm|js|mjs|riv|ttf|otf|woff2|png|svg|webp|css)$/i;

self.addEventListener('install', (event) => {
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) =>
                Promise.all(
                    keys
                        .filter((key) => key !== CACHE && key.startsWith('letta-web-'))
                        .map((key) => caches.delete(key)),
                ),
            )
            .then(() => self.clients.claim()),
    );
});

const cachedOrOffline = async (request) => {
    const hit = await caches.match(request);
    if (hit) return hit;
    if (request.mode === 'navigate') return (await caches.match('./index.html')) ?? Response.error();
    return Response.error();
};

// The cache write runs alongside the response; waitUntil keeps the worker alive until it lands.
const networkFirst = async (event) => {
    const { request } = event;
    try {
        const response = await fetch(request);
        if (response.ok) {
            const copy = response.clone();
            event.waitUntil(caches.open(CACHE).then((cache) => cache.put(request, copy)));
        }
        return response;
    } catch {
        return cachedOrOffline(request);
    }
};

// A warm cache answers with no network round-trip at all; a miss fetches the file and caches it
// the same way networkFirst does, so the next open is warm.
const cacheFirst = async (event) => {
    const { request } = event;
    const hit = await caches.match(request);
    if (hit) return hit;
    try {
        const response = await fetch(request);
        if (response.ok) {
            const copy = response.clone();
            event.waitUntil(caches.open(CACHE).then((cache) => cache.put(request, copy)));
        }
        return response;
    } catch {
        return Response.error();
    }
};

// The first visit loads the app before this worker controls the page, so none of those files
// reached the cache. The page lists them once the worker is ready (see index.html), including the
// hashed .wasm names no fixed list could know, and they are cached here for the next offline open.
self.addEventListener('message', (event) => {
    // Only this site's own pages may ask; the listed URLs are filtered to this origin as well.
    if (event.origin !== self.location.origin) return;
    if (event.data?.type !== 'cache-loaded-files' || !Array.isArray(event.data.urls)) return;
    const own = event.data.urls.filter((url) => new URL(url).origin === self.location.origin);
    event.waitUntil(caches.open(CACHE).then((cache) => Promise.allSettled(own.map((url) => cache.add(url)))));
});

self.addEventListener('fetch', (event) => {
    const { request } = event;
    const url = new URL(request.url);
    const docsPath = new URL('api/', self.registration.scope).pathname;
    // Dokka is a separate static site, not an app route or an offline app-shell fallback.
    if (url.pathname === docsPath.slice(0, -1) || url.pathname.startsWith(docsPath)) return;
    // Only this site's own files; WebSocket and relay traffic never comes through here anyway.
    if (request.method !== 'GET' || url.origin !== self.location.origin) return;
    // The shell stays network-first (falling back to the cache offline); the heavy bundle files
    // below it never pay a network round-trip once this build has cached them.
    if (request.mode === 'navigate' || shellPaths.includes(url.pathname)) {
        event.respondWith(networkFirst(event));
        return;
    }
    if (ASSET.test(url.pathname)) {
        event.respondWith(cacheFirst(event));
        return;
    }
    // Anything else this site serves keeps the app's original network-first behavior.
    event.respondWith(networkFirst(event));
});
