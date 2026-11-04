/**
 * lcp-ith — registers the letta-code source-mutation loader.
 *
 * The SDK wrapper passes this file as `node --import file://<this>` so the
 * loader hook installs before letta.js loads. `module.register()` is the
 * Node 20+ supported way to install loader hooks at runtime; it returns
 * immediately and the hook runs in a worker thread isolated from the main
 * application. See letta-code-patch-loader.mjs for the patch itself.
 */
import { register } from "node:module";

register("./letta-code-patch-loader.mjs", import.meta.url);
