#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const repositoryRoot = resolve(fileURLToPath(new URL("../..", import.meta.url)));
export const fixtureRoot = join(repositoryRoot, "android-compose/sharedLogic/src/jvmTest/resources/appserver");

/** The command and message unions an installed package declares, in declaration order. */
export function installedUnions(packageRoot, protocolDeclaration) {
  const corpus = loadDeclarationCorpus(packageRoot, join(packageRoot, protocolDeclaration)).map(({ text }) => text).join("\n");
  return {
    commands: extractDiscriminants(corpus, "WsProtocolCommand"),
    messages: extractDiscriminants(corpus, "WsProtocolMessage"),
  };
}

/** Protocol hashes and corpus files of an installed package, as the matrix and inventory record them. */
export function installedProtocolSource(packageRoot, protocolDeclaration) {
  const declaration = join(packageRoot, protocolDeclaration);
  const declarations = loadDeclarationCorpus(packageRoot, declaration);
  return {
    protocol_sha256: sha256(readFileSync(declaration, "utf8")),
    protocol_corpus_sha256: sha256DeclarationCorpus(declarations),
    protocol_corpus_files: declarations.map(({ path }) => path),
  };
}

function main() {
  const args = process.argv.slice(2);
  const packageRootIndex = args.indexOf("--package-root");
  if (packageRootIndex < 0 || !args[packageRootIndex + 1]) {
    throw new Error("Usage: verify-contract-baseline.mjs --package-root <installed-package-root> [--unions-only]");
  }

  const packageRootArgument = resolve(args[packageRootIndex + 1]);
  // --unions-only: compare just the command/message unions, e.g. to ask whether a newer release
  // changed the protocol surface the Kotlin client is pinned to.
  const unionsOnly = args.includes("--unions-only");
  const matrix = readJson(join(fixtureRoot, "app-server-v2-contract-matrix.json"));
  const inventory = readJson(join(fixtureRoot, "installed-protocol-v2-inventory.json"));
  const packageRoot = resolveInstalledPackageRoot(packageRootArgument, matrix.baseline.package);
  if (unionsOnly) {
    const unions = installedUnions(packageRoot, inventory.source.protocol_declaration);
    assertEqual("command union", inventory.commands, unions.commands);
    assertEqual("message union", inventory.messages, unions.messages);
    console.log(`Unions match the pinned App Server v2 inventory at ${packageRoot}.`);
    return;
  }
  const packageJson = readJson(join(packageRoot, "package.json"));
  const entrypoint = join(packageRoot, "letta.js");
  const source = installedProtocolSource(packageRoot, inventory.source.protocol_declaration);
  const unions = installedUnions(packageRoot, inventory.source.protocol_declaration);

  assertEqual("package name", matrix.baseline.package, packageJson.name);
  assertEqual("package version", matrix.baseline.version, packageJson.version);
  assertEqual("Node version", matrix.baseline.node, process.version);
  assertEqual("protocol hash", matrix.baseline.protocol_sha256, source.protocol_sha256);
  assertEqual("inventory protocol hash", inventory.source.protocol_sha256, source.protocol_sha256);
  assertEqual("protocol corpus hash", matrix.baseline.protocol_corpus_sha256, source.protocol_corpus_sha256);
  assertEqual("inventory protocol corpus hash", inventory.source.protocol_corpus_sha256, source.protocol_corpus_sha256);
  assertEqual("protocol corpus files", matrix.baseline.protocol_corpus_files, source.protocol_corpus_files);
  assertEqual("inventory protocol corpus files", inventory.source.protocol_corpus_files, source.protocol_corpus_files);
  assertEqual("command union", inventory.commands, unions.commands);
  assertEqual("message union", inventory.messages, unions.messages);

  const probes = new Map(matrix.cli_probes.map((probe) => [probe.classification, probe]));
  for (const probe of matrix.cli_probes) {
    const expected = readFileSync(join(fixtureRoot, probe.fixture), "utf8");
    assertEqual(`${probe.classification} output`, expected, runCliProbe(probe.classification, entrypoint));
  }
  if (probes.size !== CLI_PROBE_ARGS.size) throw new Error("Missing CLI probe classification");

  console.log(`Verified App Server v2 baseline for ${packageJson.name}@${packageJson.version} at ${packageRoot}.`);
}

export function resolveInstalledPackageRoot(candidate, expectedName) {
  const directPackageJson = join(candidate, "package.json");
  try {
    if (readJson(directPackageJson).name === expectedName) return candidate;
  } catch {
    // The argument may be an npm prefix or node_modules directory.
  }

  const packagePath = expectedName.split("/");
  const candidates = [join(candidate, ...packagePath), join(candidate, "node_modules", ...packagePath)];
  for (const packageCandidate of candidates) {
    try {
      if (readJson(join(packageCandidate, "package.json")).name === expectedName) return packageCandidate;
    } catch {
      // Continue until a matching installed package is found.
    }
  }
  throw new Error(`Could not resolve installed package ${expectedName} from ${candidate}`);
}

// Each CLI probe the matrix records, as the arguments that produce its fixture. The Node probe
// runs the Node binary itself; the others run the package entrypoint with it.
const CLI_PROBE_ARGS = new Map([
  ["installed_node_version", () => ["--version"]],
  ["installed_version", (entrypoint) => [entrypoint, "--version"]],
  ["server_listener", (entrypoint) => [entrypoint, "server", "--help"]],
  ["app_server_v2", (entrypoint) => [entrypoint, "app-server", "--help"]],
]);

/** Stdout of the CLI probe `classification` against `entrypoint`, run with the current Node. */
export function runCliProbe(classification, entrypoint) {
  const probeArgs = CLI_PROBE_ARGS.get(classification);
  if (!probeArgs) throw new Error(`Unknown CLI probe classification ${classification}`);
  return execFileSync(process.execPath, probeArgs(entrypoint), { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
}

function loadDeclarationCorpus(packageRoot, primaryPath) {
  const pending = [
    primaryPath,
    join(packageRoot, "dist/types/types/app-server-protocol.d.ts"),
  ];
  const declarations = new Map();

  while (pending.length > 0) {
    const declarationPath = pending.pop();
    const packageRelativePath = normalizePath(relative(packageRoot, declarationPath));
    if (declarations.has(packageRelativePath)) continue;

    const text = readFileSync(declarationPath, "utf8");
    declarations.set(packageRelativePath, { path: packageRelativePath, text });

    // Follow both `./` and `../` relative imports so dist/types/* siblings
    // contribute to protocol_corpus_sha256 (parent-relative imports are common).
    const importPattern = /(?:from\s+|import\s*\(\s*)["'](\.\.?\/[^"']+)["']/g;
    let match;
    while ((match = importPattern.exec(text)) !== null) {
      pending.push(resolveLocalDeclaration(declarationPath, match[1]));
    }
  }

  return [...declarations.values()].sort((left, right) => {
    if (left.path < right.path) return -1;
    if (left.path > right.path) return 1;
    return 0;
  });
}

function resolveLocalDeclaration(importerPath, specifier) {
  const candidates = declarationCandidates(specifier);

  for (const candidate of candidates) {
    const declarationPath = resolve(dirname(importerPath), candidate);
    try {
      readFileSync(declarationPath, "utf8");
      return declarationPath;
    } catch {
      // Try the next TypeScript declaration resolution form.
    }
  }
  throw new Error(`Missing local declaration ${specifier} imported by ${importerPath}`);
}

function declarationCandidates(specifier) {
  if (specifier.endsWith(".d.ts") || specifier.endsWith(".ts")) return [specifier];
  if (specifier.endsWith(".js")) return [specifier.replace(/\.js$/, ".d.ts"), specifier];
  return [`${specifier}.d.ts`, join(specifier, "index.d.ts")];
}

function sha256DeclarationCorpus(declarations) {
  const hash = createHash("sha256");
  for (const declaration of declarations) {
    // Path and byte-length separators make concatenation unambiguous while
    // preserving the exact declaration bytes.
    hash.update(declaration.path);
    hash.update("\0");
    hash.update(String(Buffer.byteLength(declaration.text, "utf8")));
    hash.update("\0");
    hash.update(declaration.text);
    hash.update("\0");
  }
  return hash.digest("hex");
}

function normalizePath(path) {
  return path.replaceAll("\\", "/");
}

function extractDiscriminants(source, unionName) {
  return unionMembers(source, unionName).map((member) => {
    // 0.33 also declares members as intersections: `export type X = SomeResult & { type: "x" };`.
    const declarationMatch = source.match(new RegExp(`export (?:interface|type) ${member}(?:<[^>]*>)?(?: extends [^{]+| = [^{;]+&)? \\{([\\s\\S]*?)\\n\\}`));
    if (!declarationMatch) throw new Error(`Missing declaration for ${member}`);
    const typeMatch = declarationMatch[1].match(/\btype:\s*"([^"]+)"/);
    if (!typeMatch) throw new Error(`Missing type discriminant for ${member}`);
    return typeMatch[1];
  });
}

// Flattens a union to its object members, in declaration order. Since 0.32.x members can be
// written `Name`, `Namespace.Name` or `import("./module").Name`, and a member may itself be a
// union declared in a sibling module (e.g. TeleportProtocol.TeleportProtocolCommand). The corpus
// holds every followed sibling, so a bare name resolves against all of it.
function unionMembers(source, unionName, seen = new Set()) {
  if (seen.has(unionName)) throw new Error(`Cyclic union ${unionName}`);
  seen.add(unionName);
  const unionMatch = source.match(new RegExp(`export type ${unionName} = ([^;{]+);`));
  if (!unionMatch) throw new Error(`Missing ${unionName} union`);

  return unionMatch[1].split("|").map((member) => bareTypeName(member.trim())).flatMap((member) => {
    const nestedUnion = source.match(new RegExp(`export type ${member} = ([^;{]+);`));
    return nestedUnion ? unionMembers(source, member, seen) : [member];
  });
}

function bareTypeName(member) {
  const withoutImport = member.replace(/^import\(\s*["'][^"']+["']\s*\)\./, "");
  return withoutImport.includes(".") ? withoutImport.slice(withoutImport.lastIndexOf(".") + 1) : withoutImport;
}

export function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function assertEqual(label, expected, actual) {
  if (JSON.stringify(expected) !== JSON.stringify(actual)) {
    throw new Error(`${label} mismatch\nexpected: ${JSON.stringify(expected)}\nactual:   ${JSON.stringify(actual)}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
