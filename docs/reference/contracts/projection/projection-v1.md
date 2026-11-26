# Projection v1: the letta-code store as log payloads, and back (R2)

Status: normative for `core-projection`. Tightens C1 §3.5 and §6 and log-v1 R-20 to R-23; the H1 split is
bead d0oir.27. Fixtures: `projection-v1-store.json` (a letta-code 0.34.x local-backend store for one agent, with
node-local files beside it) and `projection-v1-expected.json` (its checkpoint manifest and refs). Regenerate the
expected file with `BAKEN_REGEN_FIXTURES=1 cargo test -p core-projection`.

## 1. Files, never internals

- P-1 The projection reads and writes letta-code's local-backend **files**. It never calls letta-code, never reads its
  memory, and never rewrites a replicated byte (L12).
- P-2 Paths are backend-relative, `/`-separated, and obey log-v1 R-20.

## 2. Layout (store_format 1) and the H1 split

| Path | Class |
|---|---|
| `agents/<b64url(agentId)>.json` | replicated, `file_put` |
| `conversations/<b64url(key)>/conversation.json` | replicated, `file_put` |
| `conversations/<b64url(key)>/messages.jsonl` | replicated, `file_append` (E1) or `file_put` |
| `conversations/<b64url(key)>/blobs/<name>` | replicated, `file_put` |
| `memfs/<agentId>/memory/.git` objects, refs, `HEAD`, `packed-refs` (or `memfs/<agentId>/.git`) | replicated as `git_objects` and `git_ref` only |
| memfs `.git/hooks/*` | node-local (I3) |
| memfs `.git/config`, `.git/index`, other `.git` files (`letta-memory-layout-policy`) | node-local (I3, I4) |
| the memfs working tree | node-local: checked out from refs |
| `conversations/*/system-prompt.json`, `conversations/*/manifest.json` | node-local caches |
| `settings.json`, `providers/*`, `mods/*`, the bindings table | node-local |

- P-3 The conversation key is `default:<agentId>` or `conversation:<id>`; the directory name is its base64url (no padding).
  Readers also accept padded and standard base64.
- P-4 **Repository names.** A `git_objects` / `git_ref` payload's `repo` is the repository's backend-relative directory
  (`memfs/<agentId>/memory`, letta-code's layout, or `memfs/<agentId>`). C1's example value `"memfs"` cannot tell a tree
  agent's repository from its subagents' (they ride in the same log), so v1 names the directory. A materializer writes
  only repositories whose name starts `memfs/<agent>`.
- P-5 **Scope.** An exporter exports one agent tree: the tree agent and its subagents. A default conversation belongs to
  the agent its key names; another conversation to the `agent_id` (or `agentId`) in its `conversation.json`; a
  conversation whose owner is unknown is not exported yet.

## 3. Exporter (holder only)

- E1 A transcript is exported up to its last `\n`. If the file grew and the exported prefix (length, BLAKE3) is unchanged,
  the new complete bytes are a `file_append` at the exported length; any other change is a `file_put` of the complete
  prefix. A new transcript is a `file_put`. A torn last line waits for its newline.
- E2 One scan is one batch, appended in one store transaction: files first, then per repository `git_objects` before the
  `git_ref`s that need them. A deleted replicated file is a `file_delete`.
- E3 For each ref that differs from the log (and a detached `HEAD`), the objects reachable from the new tips that the log
  does not hold, as raw uncompressed content; traversal stops at an exported commit. Objects are byte-exact as
  committed. `git_objects` payloads hold at most 4 MiB of object bytes each. A ref gone from disk is a `git_ref` with
  `new_oid` null. A symbolic `HEAD` that changed is a `git_ref {name: "HEAD", symref}`.
- E4 Checkpoints are written by the node (node-v1 §5): `manifest` and `refs` are the log's fold of what was exported, never
  a fresh disk read, so a torn transcript tail never reaches a manifest.
- E5 Agent and conversation records are linted before export: `model` must start `profile/` (I2); `cwd`, `description`
  and memory-dir fields hold no absolute host path (I5); a conversation `id` is not `local-conv-<digits>` (I1). A finding
  is `I<n>:<path>:<field>`. The record is still exported; findings are listed in the next checkpoint's `h1_violations`
  until a later export of that path is clean. Transcripts are not linted.
- E6 Every payload's bytes (file content, appended bytes, git object content) pass the deny-scanner: provider key shapes
  (`sk-ant-…`, `sk-…`, `ghp_…`, `xox?-…`, `AKIA…`), PEM private keys, `Authorization: Bearer|Basic …`, and the node's own
  binding values of 8 bytes or more. A hit blocks that export (it is reported and retried on the next scan, never
  appended); a blocked repository exports none of its changes in that scan.
- E7 `runtime_parity` comes from node.info (node-v1 §6).
- P-6 The exporter keeps the log's view of the agent (the fold of everything exported). A new holder seeds it from the
  canonical line after materializing, so only later changes are exported. If appending a batch fails, the exporter is
  rebuilt from the log.

## 4. Materializer (on becoming holder, and in a move's verify)

1. Fold the canonical payloads in seq order (core-log's fold): `file_append` must start at the current length, `git_ref`
   must name the current old oid and an object already written. A path off the allowlist is skipped and reported as an
   alarm. An unknown kind below `0x8000` fails with `store_format_unsupported`; kinds from `0x8000` are skipped.
2. Write every file verbatim. For each repository: `git init` node-locally with `core.autocrlf=false`,
   `core.logAllRefUpdates=false` and `core.hooksPath` = the node's hooks directory; write every object as a loose object
   (its id is checked against its content); set the direct refs and `HEAD`; check the working tree out from `HEAD`.
3. Read the result back from disk: SHA-256 and length of every replicated file, and every repository's refs (`HEAD` only
   when detached). This is the backstop: verify compares it with the checkpoint's `manifest` and `refs` exactly, and any
   difference is a `verify_failed` with the per-file diffs.
4. Install swaps the staged agent into the live backend: each replicated file replaces its live copy; each repository
   directory replaces the live one. Node-local files in the live backend are left alone.

## 5. Divergence support

`diff_lines(inputs, canonical up to the fork, tail)` reports: each conversation a tail entry touched (`file_*` under its
directory, or `feed_append`), with the last message id of the canonical transcript at the fork (the last line whose `type`
is `message`, `message.id` else `id`) and its owning agent; each `refs/heads/<leaf>` the tail moved, renamed
`refs/heads/divergence/<n>-<claimant8>/<leaf>`; and each action the tail started without finishing.

## 6. Watching

The node watches the backend directory recursively (debounced, 200 ms by default). A burst that touched a replicated
file or a repository's objects, refs, `HEAD` or `packed-refs` triggers one scan; node-local paths never do. A lost-events
notice triggers a scan too. The watcher only schedules scans; the scan reads the disk.

## 7. Libraries

| Need | Library | Where |
|---|---|---|
| git objects, refs, init, loose writes, checkout | `gix` 0.91 (`gix-worktree-state` checkout, the path its own clone uses) | `gitoxide.rs` only, behind `GitBackend` |
| file watching | `notify` 9 through `notify-debouncer-mini` 0.8 | `watch.rs` only |
| directory walks | `walkdir` | exporter, manifest |
| secret patterns | `regex` | `lint.rs` |
| base64url | `base64` | `layout.rs` |
| SHA-256, BLAKE3, git object ids | core-log's `hashes` (sha2, sha1, blake3) | everywhere |

Nothing shells out to `git`. The tests write memfs commits as raw objects through `GitBackend`, so they need no git
binary either.
