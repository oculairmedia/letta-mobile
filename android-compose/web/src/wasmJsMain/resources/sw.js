// letta-mobile-2cson: makes Letta Web installable and lets its shell open offline.
//
// Network first: online, every request goes to the network, so a new deploy is picked up on the
// next load and an old cached build never shadows it. The copy kept here is only a fallback for
// when the network is gone. Talking to agents still needs the network (Iroh relays or the App
// Server socket); offline only means the app opens.
const CACHE = 'letta-web-v1';
const SHELL = ['./', './index.html', './manifest.webmanifest', './favicon.svg'];

self.addEventListener('install', (event) => {
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
            .then(() => self.clients.claim()),
    );
});

const cachedOrOffline = async (request) => {
    const hit = await caches.match(request);
    if (hit) return hit;
    if (request.mode === 'navigate') return (await caches.match('./index.html')) ?? Response.error();
    return Response.error();
};

const networkFirst = async (request) => {
    try {
        const response = await fetch(request);
        if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
    } catch {
        return cachedOrOffline(request);
    }
};

// The first visit loads the app before this worker controls the page, so none of those files
// reached the cache. The page lists them once the worker is ready (see index.html), including the
// hashed .wasm names no fixed list could know, and they are cached here for the next offline open.
self.addEventListener('message', (event) => {
    if (event.data?.type !== 'cache-loaded-files' || !Array.isArray(event.data.urls)) return;
    const own = event.data.urls.filter((url) => new URL(url).origin === self.location.origin);
    event.waitUntil(caches.open(CACHE).then((cache) => Promise.allSettled(own.map((url) => cache.add(url)))));
});

self.addEventListener('fetch', (event) => {
    const { request } = event;
    // Only this site's own files; WebSocket and relay traffic never comes through here anyway.
    if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
    event.respondWith(networkFirst(request));
});
