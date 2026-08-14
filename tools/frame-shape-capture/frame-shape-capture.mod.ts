/**
 * frame-shape-capture — capture-then-gate tooling for wire/frame-shape bugs.
 *
 * Source of truth: letta-mobile repo, tools/frame-shape-capture/. This file
 * must be installed into an agent's mod loading path
 * (~/.letta/mods/ or $MEMORY_DIR/mods/) to actually run — see
 * tools/frame-shape-capture/install.sh and README.md. There is no
 * "load a mod straight out of a git repo" path.
 *
 * Problem this addresses: historically, a wire/frame-shape bug (the Iroh
 * streaming class of defect — StreamTextAccumulator.textKey,
 * IrohFrameFlowDiagnostics gates, LettaFrameFlowDiag-tagged logcat lines)
 * gets "fixed" from a single observed capture. The fix and its regression
 * test both get shaped around that one example, so a fixture that can't
 * distinguish the fix from a no-op passes cleanly and the bug survives in
 * shapes the single capture never exercised. See the dir4k mask-bug
 * postmortem for the general pattern: a discriminating fixture must be
 * constructed to force two hypotheses apart before it proves anything.
 *
 * This mod exposes three capabilities so that discipline is a tool call
 * instead of something re-derived by hand every time:
 *
 *   1. capture_frame_shape (tool)
 *      Append a raw capture (logcat excerpt, probe dump, JSON frame, etc.)
 *      for a named seam to a local, gitignored JSONL corpus file. Computes
 *      a coarse structural digest (which top-level keys/fields are present,
 *      not full content) so near-duplicate captures are recorded but
 *      flagged as non-novel — they should not inflate apparent coverage.
 *
 *   2. check_shape_corpus_coverage (tool)
 *      Given a seam_id, reports whether the corpus has enough *divergent*
 *      captures (not just N captures) to be worth designing a fix from,
 *      and if not, names what's missing (e.g. "all captures agree on every
 *      observed field — no discriminating example yet").
 *
 *   3. /draft-shape-bead (command)
 *      Once coverage passes, files a bd issue with the corpus path
 *      embedded in the description and acceptance criteria that require
 *      the regression test to load fixtures from that corpus file rather
 *      than hand-built literals.
 *
 * Explicit non-goal: no LLM call is embedded in capture or gate, no code
 * generation happens here, and nothing here mutates a running process
 * (contrast with KotlinLLM's live JDI hot-swap). The output of this
 * pipeline is a well-evidenced bead brief; a human or agent still designs
 * and reviews the actual fix through the normal PR path.
 */

import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { createHash } from "node:crypto";
import { mkdir, appendFile, readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import * as path from "node:path";

const execFileAsync = promisify(execFile);

const CORPUS_DIRNAME = "frame-shape-corpus";
const MIN_CAPTURES_FOR_COVERAGE = 3;
const MIN_DIVERGENT_FIELD_SETS = 2;

interface CaptureRecord {
  timestamp: string;
  seamId: string;
  capture: string;
  digest: string;
  fieldKeys: string[];
  novel: boolean;
}

function corpusDir(cwd: string): string {
  return path.join(cwd, "tools", "frame-shape-capture", "corpus");
}

function corpusFile(cwd: string, seamId: string): string {
  const safeSeam = seamId.replace(/[^a-zA-Z0-9_.-]/g, "_");
  return path.join(corpusDir(cwd), `${safeSeam}.jsonl`);
}

/**
 * Extract a coarse structural fingerprint from a capture string: attempts
 * JSON parse and returns sorted top-level keys (recursing one level into
 * nested objects) if it's JSON; otherwise falls back to extracting
 * key=value / key: value style tokens common in our logcat gate lines
 * (e.g. Telemetry.event's "key" "value" pairs, gate=, len=, otid=).
 */
function extractFieldKeys(capture: string): string[] {
  const trimmed = capture.trim();
  try {
    const parsed = JSON.parse(trimmed);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      const keys = new Set<string>();
      for (const [k, v] of Object.entries(parsed)) {
        keys.add(k);
        if (v && typeof v === "object" && !Array.isArray(v)) {
          for (const nested of Object.keys(v as Record<string, unknown>)) {
            keys.add(`${k}.${nested}`);
          }
        }
      }
      return [...keys].sort();
    }
  } catch {
    // not JSON — fall through to token scan
  }

  const keys = new Set<string>();
  const tokenPattern = /\b([a-zA-Z_][a-zA-Z0-9_]*)\s*[:=]\s*\S/g;
  let match: RegExpExecArray | null;
  while ((match = tokenPattern.exec(trimmed)) !== null) {
    keys.add(match[1]);
  }
  return [...keys].sort();
}

function digestOf(fieldKeys: string[]): string {
  return createHash("sha256").update(fieldKeys.join("|")).digest("hex").slice(0, 16);
}

async function readCorpus(file: string): Promise<CaptureRecord[]> {
  if (!existsSync(file)) return [];
  const raw = await readFile(file, "utf8");
  const records: CaptureRecord[] = [];
  for (const line of raw.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      records.push(JSON.parse(trimmed));
    } catch {
      // skip malformed line rather than fail the whole read
    }
  }
  return records;
}

export default function activate(letta: any) {
  const disposers: Array<() => void> = [];

  if (letta.capabilities.tools) {
    disposers.push(
      letta.tools.register({
        name: "capture_frame_shape",
        description:
          "Record a raw capture (logcat excerpt, probe dump, JSON frame, etc.) for a named " +
          "wire/frame-shape seam (e.g. 'StreamTextAccumulator.textKey', 'IrohFrameFlowDiagnostics') " +
          "into a local corpus for that seam. Use this any time you observe the seam behave " +
          "unexpectedly, or when gathering ground truth before designing a fix for a frame-shape " +
          "bug. Call this multiple times across genuinely different scenarios for the same seam " +
          "before trusting a fix design — one capture proves nothing about the seam's real shape.",
        parameters: {
          type: "object",
          properties: {
            seam_id: {
              type: "string",
              description:
                "Stable identifier for the code seam under investigation, e.g. " +
                "'StreamTextAccumulator.textKey' or 'IrohFrameFlowDiagnostics.gate4'.",
            },
            capture: {
              type: "string",
              description:
                "The raw capture payload: a logcat excerpt, JSON frame dump, probe output, etc.",
            },
            note: {
              type: "string",
              description: "Optional short note on what scenario produced this capture.",
            },
          },
          required: ["seam_id", "capture"],
          additionalProperties: false,
        },
        requiresApproval: false,
        parallelSafe: false,
        async run(ctx: any) {
          const seamId = String(ctx.args.seam_id ?? "").trim();
          const capture = String(ctx.args.capture ?? "");
          const note = ctx.args.note ? String(ctx.args.note) : undefined;
          if (!seamId) return { status: "error", content: "seam_id is required" };
          if (!capture.trim()) return { status: "error", content: "capture is required" };

          const dir = corpusDir(ctx.cwd);
          await mkdir(dir, { recursive: true });
          const file = corpusFile(ctx.cwd, seamId);

          const existing = await readCorpus(file);
          const fieldKeys = extractFieldKeys(capture);
          const digest = digestOf(fieldKeys);
          const novel = !existing.some((r) => r.digest === digest);

          const record: CaptureRecord = {
            timestamp: new Date().toISOString(),
            seamId,
            capture,
            digest,
            fieldKeys,
            novel,
          };
          const line = JSON.stringify({ ...record, note });
          await appendFile(file, line + "\n", "utf8");

          const distinctDigests = new Set([...existing.map((r) => r.digest), digest]);
          return [
            `Captured for seam '${seamId}' (${existing.length + 1} total, ${distinctDigests.size} distinct shape${distinctDigests.size === 1 ? "" : "s"}).`,
            novel
              ? "This capture's field shape is NEW relative to the existing corpus."
              : "This capture's field shape matches an existing capture (not novel) — still recorded, but does not add coverage.",
            `Corpus file: ${path.relative(ctx.cwd, file)}`,
          ].join("\n");
        },
      }),
    );

    disposers.push(
      letta.tools.register({
        name: "check_shape_corpus_coverage",
        description:
          "Check whether the capture corpus for a wire/frame-shape seam has enough genuinely " +
          "divergent examples to be worth designing a fix from. Refuses 'ready' if there are too " +
          "few captures, or if all captures agree on every observed field (no discriminating " +
          "example — the same trap as a fixture that can't distinguish two hypotheses). Reports " +
          "which fields diverge across captures so a fix design knows what it must actually handle. " +
          "Call this before proposing a fix for a frame-shape bug, and again before filing a bead.",
        parameters: {
          type: "object",
          properties: {
            seam_id: {
              type: "string",
              description: "The seam identifier previously used with capture_frame_shape.",
            },
          },
          required: ["seam_id"],
          additionalProperties: false,
        },
        requiresApproval: false,
        parallelSafe: true,
        async run(ctx: any) {
          const seamId = String(ctx.args.seam_id ?? "").trim();
          if (!seamId) return { status: "error", content: "seam_id is required" };

          const file = corpusFile(ctx.cwd, seamId);
          const records = await readCorpus(file);

          if (records.length === 0) {
            return [
              `NOT READY: no captures found for seam '${seamId}'.`,
              `Expected corpus file: ${path.relative(ctx.cwd, file)}`,
              "Use capture_frame_shape to record observed scenarios for this seam first.",
            ].join("\n");
          }

          const distinctDigests = new Map<string, string[]>();
          for (const r of records) {
            if (!distinctDigests.has(r.digest)) distinctDigests.set(r.digest, r.fieldKeys);
          }

          const allFieldKeys = new Set<string>();
          for (const keys of distinctDigests.values()) {
            for (const k of keys) allFieldKeys.add(k);
          }
          const fieldsThatDiverge = [...allFieldKeys].filter((field) => {
            const presentIn = [...distinctDigests.values()].filter((keys) => keys.includes(field)).length;
            return presentIn > 0 && presentIn < distinctDigests.size;
          });

          const reasons: string[] = [];
          if (records.length < MIN_CAPTURES_FOR_COVERAGE) {
            reasons.push(
              `Only ${records.length} capture(s) recorded; want at least ${MIN_CAPTURES_FOR_COVERAGE}.`,
            );
          }
          if (distinctDigests.size < MIN_DIVERGENT_FIELD_SETS) {
            reasons.push(
              `All ${records.length} capture(s) share the same structural shape (${distinctDigests.size} distinct shape). ` +
                "Need captures from a genuinely different scenario for this seam — same shape repeated does not " +
                "discriminate between hypotheses about the bug.",
            );
          }

          if (reasons.length > 0) {
            return [
              `NOT READY for seam '${seamId}':`,
              ...reasons.map((r) => `  - ${r}`),
              `Corpus file: ${path.relative(ctx.cwd, file)}`,
            ].join("\n");
          }

          return [
            `READY for seam '${seamId}': ${records.length} captures, ${distinctDigests.size} distinct shapes.`,
            fieldsThatDiverge.length > 0
              ? `Fields that diverge across captures (a fix must account for these): ${fieldsThatDiverge.join(", ")}`
              : "No individual field presence diverges, but multiple distinct shapes were recorded — inspect capture content directly for the actual difference.",
            `Corpus file: ${path.relative(ctx.cwd, file)}`,
          ].join("\n");
        },
      }),
    );
  }

  if (letta.capabilities.commands) {
    disposers.push(
      letta.commands.register({
        id: "draft-shape-bead",
        description:
          "File a bd issue for a frame-shape bug seam, once its capture corpus clears the " +
          "coverage gate. Embeds the corpus path and requires the regression test to load " +
          "fixtures from it.",
        args: "<seam-id>",
        showInTranscript: true,
        async run(ctx: any) {
          const seamId = ctx.args.trim();
          if (!seamId) {
            return {
              type: "output",
              output: "Usage: /draft-shape-bead <seam-id>",
            };
          }

          const file = corpusFile(ctx.cwd, seamId);
          const records = await readCorpus(file);

          if (records.length === 0) {
            return {
              type: "output",
              output: `No captures found for seam '${seamId}'. Run capture_frame_shape first.`,
            };
          }

          const distinctDigests = new Set(records.map((r) => r.digest));
          if (records.length < MIN_CAPTURES_FOR_COVERAGE || distinctDigests.size < MIN_DIVERGENT_FIELD_SETS) {
            return {
              type: "output",
              output:
                `Corpus for '${seamId}' has ${records.length} capture(s), ${distinctDigests.size} distinct shape(s) — ` +
                `not enough to file a well-evidenced bead yet (want >= ${MIN_CAPTURES_FOR_COVERAGE} captures and ` +
                `>= ${MIN_DIVERGENT_FIELD_SETS} distinct shapes). Run check_shape_corpus_coverage for details.`,
            };
          }

          const relCorpusPath = path.relative(ctx.cwd, file);
          const title = `Frame-shape bug: ${seamId}`;
          const description =
            `Evidenced by a discriminating capture corpus at ${relCorpusPath} ` +
            `(${records.length} captures, ${distinctDigests.size} distinct structural shapes). ` +
            `Filed via /draft-shape-bead.`;
          const acceptance =
            `The regression test MUST load fixtures from ${relCorpusPath} (or a subset of its ` +
            `distinct-shape records), not hand-built literals. A test built from a single ` +
            `hand-picked capture will be rejected — it must exercise at least ${MIN_DIVERGENT_FIELD_SETS} ` +
            `of the distinct shapes recorded in the corpus.`;

          try {
            const { stdout } = await execFileAsync(
              "bd",
              [
                "create",
                title,
                "--description",
                description,
                "--acceptance",
                acceptance,
                "--labels",
                "frame-shape,capture-gate",
              ],
              { cwd: ctx.cwd },
            );
            return { type: "output", output: stdout.trim() || "Bead created." };
          } catch (error: any) {
            return {
              type: "output",
              output: `bd create failed: ${error?.message ?? String(error)}`,
            };
          }
        },
      }),
    );
  }

  return () => {
    for (const dispose of disposers.reverse()) dispose();
  };
}
