const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, 'src/wasmJsMain/resources/sw.js'), 'utf8');

function worker(scope) {
    const listeners = {};
    const shell = { page: 'app shell' };
    vm.runInNewContext(source, {
        URL,
        self: {
            registration: { scope },
            location: { origin: new URL(scope).origin },
            addEventListener: (type, handler) => { listeners[type] = handler; },
        },
        fetch: async () => { throw new Error('offline'); },
        caches: { match: async (request) => request === './index.html' ? shell : undefined },
        Response: { error: () => ({ error: true }) },
    });
    return { listeners, shell };
}

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
