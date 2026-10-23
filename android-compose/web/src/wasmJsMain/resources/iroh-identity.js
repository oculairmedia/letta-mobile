// The browser's persistent Iroh identity (letta-mobile-wisqe S1 / by4vm.120).
//
// One 32-byte Ed25519 secret per browser profile, so the page dials as the same
// NodeId after a reload and a host can keep a pairing grant for it. The secret
// is stored encrypted under an AES-GCM key generated NON-EXTRACTABLE: the
// wrapping key lives in IndexedDB as a CryptoKey (structured clone), so a copy
// of the profile's storage alone cannot recover the secret. Script running in
// this page can still decrypt it — that exposure is the same as any in-page
// credential and is bounded by CSP and per-peer revocation on the host.
//
// Iroh signs TLS synchronously with the raw key, so a non-extractable signing
// key cannot be used directly (S0, recorded on letta-mobile-wisqe).

const DB_NAME = 'letta-iroh-identity';
const STORE = 'keys';
const RECORD = 'device';
const KEY_BYTES = 32;

/** [bytes] as lowercase hex: how the identity crosses the JS/Wasm boundary (wisqe S1b). */
export const toHex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

/** The bytes of lowercase or uppercase [hex]; anything that is not whole hex pairs is refused. */
export const fromHex = (hex) => {
    if (!/^(?:[0-9a-fA-F]{2})*$/.test(hex)) throw new Error('identity key is not hex');
    return Uint8Array.from(hex.match(/../g) ?? [], (pair) => parseInt(pair, 16));
};

/** Thin promise wrapper over one IndexedDB object store. */
export const indexedDbStore = (indexedDB = globalThis.indexedDB) => {
    const open = () => new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, 1);
        request.onupgradeneeded = () => request.result.createObjectStore(STORE);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
    const run = async (mode, op) => {
        const db = await open();
        try {
            return await new Promise((resolve, reject) => {
                const request = op(db.transaction(STORE, mode).objectStore(STORE));
                request.onsuccess = () => resolve(request.result);
                request.onerror = () => reject(request.error);
            });
        } finally {
            db.close();
        }
    };
    return {
        get: (key) => run('readonly', (store) => store.get(key)),
        put: (key, value) => run('readwrite', (store) => store.put(value, key)),
        delete: (key) => run('readwrite', (store) => store.delete(key)),
    };
};

/**
 * Returns this browser's identity secret (Uint8Array(32)), creating and storing
 * one on first use. Returns null when storage or WebCrypto is unavailable (a
 * private window, an insecure origin): the caller then dials with an ephemeral
 * key, exactly as before S1, and the browser simply cannot stay paired.
 */
export const loadOrCreateIdentity = async (options = {}) => {
    // `'subtle' in options` so a caller (or test) can state "no WebCrypto" explicitly;
    // a default parameter would silently replace an explicit undefined.
    const subtle = 'subtle' in options ? options.subtle : globalThis.crypto?.subtle;
    if (!subtle) return null;
    const randomBytes = options.randomBytes ?? ((n) => globalThis.crypto.getRandomValues(new Uint8Array(n)));
    try {
        const store = options.store ?? indexedDbStore();
        const saved = await store.get(RECORD);
        if (saved) {
            const plain = await subtle.decrypt({ name: 'AES-GCM', iv: saved.iv }, saved.wrapKey, saved.ciphertext);
            const secret = new Uint8Array(plain);
            if (secret.length === KEY_BYTES) return secret;
            // A record that does not decrypt to a key is never reused as one.
            await store.delete(RECORD);
        }
        const secret = randomBytes(KEY_BYTES);
        const wrapKey = await subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
        const iv = randomBytes(12);
        const ciphertext = await subtle.encrypt({ name: 'AES-GCM', iv }, wrapKey, secret);
        await store.put(RECORD, { wrapKey, iv, ciphertext });
        return secret;
    } catch (error) {
        console.warn('Letta Web: no persistent Iroh identity, using an ephemeral one', error?.name ?? error);
        return null;
    }
};

/** Forgets this browser's identity; the next connect dials as a new, unpaired NodeId. */
export const forgetIdentity = async ({ store = indexedDbStore() } = {}) => {
    await store.delete(RECORD);
};
