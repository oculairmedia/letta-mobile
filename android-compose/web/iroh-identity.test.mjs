// Node unit test for web/src/wasmJsMain/resources/iroh-identity.js (letta-mobile-wisqe S1).
// Run: node --test android-compose/web/iroh-identity.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { forgetIdentity, fromHex, loadOrCreateIdentity, toHex } from './src/wasmJsMain/resources/iroh-identity.js';

// In-memory stand-in for the IndexedDB store; keeps values by reference like structured clone keeps CryptoKeys.
const memoryStore = () => {
    const data = new Map();
    return {
        data,
        get: async (key) => data.get(key),
        put: async (key, value) => { data.set(key, value); },
        delete: async (key) => { data.delete(key); },
    };
};

const subtle = webcrypto.subtle;
const hex = (bytes) => Buffer.from(bytes).toString('hex');

test('creates a 32-byte identity once and returns the same one after a reload', async () => {
    const store = memoryStore();
    const first = await loadOrCreateIdentity({ store, subtle });
    const second = await loadOrCreateIdentity({ store, subtle });
    assert.equal(first.length, 32);
    assert.equal(hex(second), hex(first), 'a reload must dial as the same NodeId');
});

test('stores the secret encrypted under a non-extractable key, never in the clear', async () => {
    const store = memoryStore();
    const secret = await loadOrCreateIdentity({ store, subtle });
    const record = store.data.get('device');
    assert.equal(record.wrapKey.extractable, false, 'wrapping key must be non-extractable');
    await assert.rejects(subtle.exportKey('raw', record.wrapKey));
    assert.notEqual(hex(new Uint8Array(record.ciphertext)).includes(hex(secret)), true);
    assert.equal(JSON.stringify(record).includes(hex(secret)), false);
});

test('a record that does not decrypt to a 32-byte key is replaced, not reused', async () => {
    const store = memoryStore();
    const wrapKey = await subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    const iv = webcrypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await subtle.encrypt({ name: 'AES-GCM', iv }, wrapKey, new Uint8Array(5));
    store.data.set('device', { wrapKey, iv, ciphertext });
    const secret = await loadOrCreateIdentity({ store, subtle });
    assert.equal(secret.length, 32);
});

test('no WebCrypto (insecure origin) falls back to an ephemeral identity', async () => {
    assert.equal(await loadOrCreateIdentity({ store: memoryStore(), subtle: undefined }), null);
});

test('storage failure falls back to an ephemeral identity instead of throwing', async () => {
    const broken = { get: async () => { throw new Error('private window'); }, put: async () => {}, delete: async () => {} };
    const original = console.warn;
    console.warn = () => {};
    try {
        assert.equal(await loadOrCreateIdentity({ store: broken, subtle }), null);
    } finally {
        console.warn = original;
    }
});

test('forgetIdentity makes the next connect a new NodeId', async () => {
    const store = memoryStore();
    const first = await loadOrCreateIdentity({ store, subtle });
    await forgetIdentity({ store });
    const next = await loadOrCreateIdentity({ store, subtle });
    assert.notEqual(hex(next), hex(first));
});

test('the identity round-trips through hex unchanged (wisqe S1b: how it crosses to Kotlin)', () => {
    const secret = Uint8Array.from({ length: 32 }, (_, i) => (i * 37 + 5) & 0xff);
    const text = toHex(secret);
    assert.equal(text, hex(secret));
    assert.deepEqual(fromHex(text), secret);
    assert.deepEqual(fromHex(text.toUpperCase()), secret);
});

test('fromHex refuses anything that is not whole hex pairs', () => {
    assert.throws(() => fromHex('abc'));
    assert.throws(() => fromHex('zz'));
    assert.deepEqual(fromHex(''), new Uint8Array(0));
});
