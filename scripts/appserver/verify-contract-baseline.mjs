#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const packageRootIndex = args.indexOf("--package-root");
if (packageRootIndex < 0 || !args[packageRootIndex + 1]) {
  throw new Error("Usage: verify-contract-baseline.mjs --package-root <installed-package-root>");
}

const packageRootArgument = resolve(args[packageRootIndex + 1]);
const repositoryRoot = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const fixtureRoot = join(repositoryRoot, "android-compose/sharedLogic/src/jvmTest/resources/appserver");
const matrix = readJson(join(fixtureRoot, "app-server-v2-contract-matrix.json"));
const inventory = readJson(join(fixtureRoot, "installed-protocol-v2-inventory.json"));
const packageRoot = resolveInstalledPackageRoot(packageRootArgument, matrix.baseline.package);
const packageJson = readJson(join(packageRoot, "package.json"));
const entrypoint = join(packageRoot, "letta.js");
const declaration = join(packageRoot, inventory.source.protocol_declaration);
const declarationText = readFileSync(declaration, "utf8");
// 0.29.x re-exports app_server_info types from a sibling module; unions still
// live in protocol_v2.d.ts but member interfaces may be declared next door.
const declarationCorpus = loadDeclarationCorpus(packageRoot, declaration, declarationText);

assertEqual("package name", matrix.baseline.package, packageJson.name);
assertEqual("package version", matrix.baseline.version, packageJson.version);
assertEqual("Node version", matrix.baseline.node, process.version);
assertEqual("protocol hash", matrix.baseline.protocol_sha256, sha256(declarationText));
assertEqual("inventory protocol hash", inventory.source.protocol_sha256, sha256(declarationText));
assertEqual("command union", inventory.commands, extractDiscriminants(declarationCorpus, "WsProtocolCommand"));
assertEqual("message union", inventory.messages, extractDiscriminants(declarationCorpus, "WsProtocolMessage"));

const probes = new Map(matrix.cli_probes.map((probe) => [probe.classification, probe]));
verifyProbe(probes.get("installed_node_version"), ["--version"], process.execPath);
verifyProbe(probes.get("installed_version"), [entrypoint, "--version"]);
verifyProbe(probes.get("server_listener"), [entrypoint, "server", "--help"]);
verifyProbe(probes.get("app_server_v2"), [entrypoint, "app-server", "--help"]);

console.log(`Verified App Server v2 baseline for ${packageJson.name}@${packageJson.version} at ${packageRoot}.`);

function resolveInstalledPackageRoot(candidate, expectedName) {
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

function verifyProbe(probe, probeArgs, executable = process.execPath) {
  if (!probe) throw new Error("Missing CLI probe classification");
  const actual = execFileSync(executable, probeArgs, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
  const expected = readFileSync(join(fixtureRoot, probe.fixture), "utf8");
  assertEqual(`${probe.classification} output`, expected, actual);
}

function loadDeclarationCorpus(packageRoot, primaryPath, primaryText) {
  const parts = [primaryText];
  const importPattern = /from\s+"(\.\/[^"]+)"/g;
  let match;
  while ((match = importPattern.exec(primaryText)) !== null) {
    const relative = match[1].endsWith(".ts") || match[1].endsWith(".d.ts")
      ? match[1]
      : `${match[1]}.d.ts`;
    const sibling = resolve(join(primaryPath, ".."), relative);
    try {
      parts.push(readFileSync(sibling, "utf8"));
    } catch {
      // Optional sibling; union members that need it will fail extractDiscriminants.
    }
  }
  // Also pull the public barrel when present so re-exported siblings are covered.
  try {
    const barrel = join(packageRoot, "dist/types/types/app-server-protocol.d.ts");
    const barrelText = readFileSync(barrel, "utf8");
    let barrelMatch;
    const barrelImports = /from\s+"(\.\/[^"]+)"/g;
    while ((barrelMatch = barrelImports.exec(barrelText)) !== null) {
      const relative = barrelMatch[1].endsWith(".d.ts") || barrelMatch[1].endsWith(".ts")
        ? barrelMatch[1]
        : `${barrelMatch[1]}.d.ts`;
      const sibling = resolve(join(barrel, ".."), relative);
      try {
        parts.push(readFileSync(sibling, "utf8"));
      } catch {
        // ignore missing
      }
    }
  } catch {
    // barrel optional
  }
  return parts.join("\n");
}

function extractDiscriminants(source, unionName) {
  const unionMatch = source.match(new RegExp(`export type ${unionName} = ([^;]+);`));
  if (!unionMatch) throw new Error(`Missing ${unionName} union`);

  return unionMatch[1].split("|").map((member) => member.trim()).map((member) => {
    const declarationMatch = source.match(new RegExp(`export (?:interface|type) ${member}(?: extends [^{]+)? \\{([\\s\\S]*?)\\n\\}`));
    if (!declarationMatch) throw new Error(`Missing declaration for ${member}`);
    const typeMatch = declarationMatch[1].match(/\btype:\s*"([^"]+)"/);
    if (!typeMatch) throw new Error(`Missing type discriminant for ${member}`);
    return typeMatch[1];
  });
}

function readJson(path) {
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
