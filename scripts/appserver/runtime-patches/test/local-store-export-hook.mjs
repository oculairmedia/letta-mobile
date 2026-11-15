// Test-only load hook: registered AFTER letta-code-patch-loader.mjs, so it runs first and
// receives the runtime-patched letta.js from the production loader. It swaps only the CLI
// entry statement for an export of the real LocalStore class, so a test can drive the
// shipped store against a temp storage dir without starting the CLI.
import { pathToFileURL } from "node:url";

const TARGET = process.env["LETTA_CLI_PATH_REAL"] ?? "";
const ENTRY = "await init_src5().then(() => exports_src2);";

/** @type {import("node:module").LoadHook} */
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!TARGET || url !== pathToFileURL(TARGET).href) return result;
  const source = typeof result.source === "string" ? result.source : Buffer.from(result.source).toString("utf8");
  if (source.split(ENTRY).length !== 2) throw new Error(`local-store-export-hook: CLI entry not found exactly once in ${TARGET}`);
  return { ...result, source: source.replace(ENTRY, "init_local_store();\nexport { LocalStore };"), shortCircuit: true };
}
