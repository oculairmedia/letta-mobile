import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Use this for LEGACY / dev-dep assertions — the pinned admin-shim
// node_modules copy (0.19.x as of writing). For modern anchors (>=0.27.x)
// use `resolveDeployedLettaBundle()` instead, which probes the live runtime
// bundle that `LETTA_CLI_PATH_REAL` points at in production.
//
// Resolve the REAL letta.js bundle so the guard test works in CI
// (admin-shim/node_modules/...) AND locally — never a machine-specific
// absolute path. `letta.js` is NOT an exported subpath, so resolve the
// package root via package.json and join the bundle filename to its dir.
export function resolveLettaBundle(): string | null {
  // The package's strict `exports` map blocks require.resolve of subpaths
  // (including package.json), so walk node_modules by filesystem: from this
  // test file up to filesystem root, check <dir>/node_modules/@letta-ai/
  // letta-code/letta.js. Covers admin-shim/node_modules (CI) and any parent.
  const rel = "node_modules/@letta-ai/letta-code/letta.js";
  // fileURLToPath decodes %20 and non-ASCII escapes that URL.pathname keeps.
  let dir = dirname(fileURLToPath(import.meta.url));
  for (;;) {
    const candidate = join(dir, rel);
    if (existsSync(candidate)) return candidate;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  // No deployed / global-bun fallback here — that role belongs to
  // `resolveDeployedLettaBundle()`. Keeping this resolver scoped to the
  // pinned dev dep prevents a stale global install from silently
  // satisfying a test that the deployed bundle would have failed.
  return null;
}

// The lcp-aioi8 patches target the DEPLOYED runtime bundle (>= 0.27.x, the
// global bun install that LETTA_CLI_PATH_REAL points at in production), not
// the pinned dev dependency in node_modules (0.19.x, which predates
// persistCompiledSystemPrompt entirely — the patches simply fail-open there).
export function resolveDeployedLettaBundle(): string | null {
  // Deployment order, newest first. The stale copy under .bun is several releases behind what
  // the service runs, and asserting "nothing fails open" against it asserts nothing useful.
  const versioned = existsSync("/root")
    ? readdirSync("/root")
        .filter((entry) => entry.startsWith("letta-code-") && !entry.endsWith(".tgz"))
        .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
        .map((entry) => join("/root", entry, "node_modules/@letta-ai/letta-code/letta.js"))
    : [];
  const candidates = [
    process.env["LETTA_CLI_PATH_REAL"] ?? "",
    ...versioned,
    "/root/.bun/install/global/node_modules/@letta-ai/letta-code/letta.js",
    resolveLettaBundle() ?? "",
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (!existsSync(candidate)) continue;
    const source = readFileSync(candidate, "utf8");
    if (source.includes("  persistCompiledSystemPrompt(conversationId, agentId) {")) {
      return candidate;
    }
  }
  return null;
}
