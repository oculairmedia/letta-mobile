# frame-shape-capture

Capture-then-gate tooling for wire/frame-shape bugs (the Iroh streaming class
of defect: `StreamTextAccumulator.textKey`, `IrohFrameFlowDiagnostics`,
`LettaFrameFlowDiag`-tagged gates, and similar seams where the real shape of
a payload only reveals itself at runtime).

## Why this exists

The historical failure mode on this class of bug: capture ONE example,
theorize a fix from it, ship a test built from that same hand-picked
example, and the fix silently fails on a shape the single capture never
exercised (see the dir4k mask-bug postmortem — a fixture that can't
distinguish two hypotheses proves nothing).

This tooling forces a **discriminating-fixture gate** ahead of any fix: no
"ready to design a fix" verdict until the corpus for a seam has genuinely
divergent captures, not just repeated captures of the same path.

## Pieces

- `frame-shape-capture.mod.ts` — a Letta Code mod exposing three
  capabilities. See the header comment in that file for the exact
  tool/command contracts.
- `corpus/` — gitignored, per-machine capture data
  (`corpus/<seam-id>.jsonl`). Not committed; this is raw diagnostic capture
  data (potentially containing live conversation content), not source.
- `install.sh` — copies the mod into an agent's own
  `$MEMORY_DIR/mods/` directory and reminds the operator to `/reload`.
  Mods only load from `~/.letta/mods/` or an agent's `$MEMORY_DIR/mods/` —
  there is no "load a mod straight out of a git repo" path, so this file
  must be installed into the harness, not just present in the checkout.

## Install (for an agent that should be able to use this)

```bash
./tools/frame-shape-capture/install.sh /path/to/agent/memory/mods
```

or, for the current machine's default harness mods dir:

```bash
./tools/frame-shape-capture/install.sh ~/.letta/mods
```

Then run `/reload` in that agent's session.

**Keeping installs in sync:** the repo copy under
`tools/frame-shape-capture/frame-shape-capture.mod.ts` is the source of
truth. If you edit it, re-run `install.sh` for every agent that has it
installed and have them `/reload` — nothing here auto-syncs a running
agent's copy.

## Workflow

1. **Capture** — call the `capture_frame_shape` tool with a `seam_id` and
   the raw capture payload (a logcat excerpt, a probe dump, a JSON frame,
   etc.) any time you observe the seam misbehave or you want ground truth
   for a seam under investigation. Near-duplicate captures (same structural
   digest) are recorded but flagged as non-novel so they don't inflate
   apparent coverage.
2. **Check coverage** — call `check_shape_corpus_coverage` with the same
   `seam_id`. It reports whether the corpus has enough *divergent* examples
   to be worth designing a fix from, and if not, what's missing.
3. **File the bead** — once coverage passes, run `/draft-shape-bead
   <seam-id>` to file a `bd create` issue with the corpus path embedded and
   acceptance criteria that require the regression test to load fixtures
   from the corpus file, not hand-built literals.

## Non-goals

This does not attempt anything like KotlinLLM's live JDI hot-swap / runtime
code synthesis. There is no LLM call embedded in the capture or gate step,
no code generation, and nothing here mutates a running process. The output
of this pipeline is a well-evidenced bead brief; a human or an agent still
designs and reviews the actual fix through the normal PR path.
