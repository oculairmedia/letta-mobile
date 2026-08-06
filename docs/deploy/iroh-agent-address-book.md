# Iroh agent address book — operator runbook

> Status: implemented in [letta-mobile-bn008.7](#) (PR title: `Seed host a2a
> address book + stable per-agent Iroh identities (bn008.7)`).
> Scope: local fs only. No host service mutation. No `letta` CLI invocation.
> No HTTP/RPC fallback. Failure is loud.

This runbook documents the three artifacts the Iroh wrapper reads at boot to
resolve `agentId` → dialable Iroh `EndpointAddr` (per the Meridian ruling
2026-08-06), the offline seed script that populates them, and the operator
recipe for re-seeding + adding new agents.

---

## The three artifacts

| Path                                       | Mode  | Writer                                 | Consumer (at boot)                         |
| ------------------------------------------ | ----- | -------------------------------------- | ------------------------------------------ |
| `~/.letta/iroh/identities/`                | 0700  | seed + wrapper (per-agent on first dial) | `IrohAgentIdentity.loadOrCreate` (Kotlin) |
| `~/.letta/iroh/agent-addresses.kv`         | 0644  | seed                                   | `FileIrohAgentAddressStore` (Kotlin)       |
| `~/.letta/iroh/.seedDone`                  | 0600  | seed                                   | short-circuits re-runs (`--force` to bypass) |

### Why the identities dir is empty at seed time

The seed script **does not** write per-agent JSON files (e.g.
`identities/<agentId>.json`) because:

- Each host that publishes an agent's address needs its OWN Iroh identity —
  the node id is the agent's dialable handle on THAT host.
- `IrohAgentIdentity.loadOrCreate` generates a fresh Ed25519 secret key the
  first time it sees an `agentId` and persists it as `<agentId>.json` with
  mode 0600. Generating one in Python without the iroh library is impossible.
- A stub file with `secretKeyB64: ""` would crash the wrapper: `parse()`
  succeeds (empty string is valid base64 for an empty byte array), but
  `SecretKey.fromBytes([])` throws.

So the seed reserves the *slot* (directory + correct modes + README) and the
wrapper merge in **bn008.6** populates the per-agent file when each agent
first dials.

---

## The seed script

`scripts/deploy/seed-agent-address-book.py` reads two sources and merges them:

1. **SQL pull** (default): `docker exec letta-postgres-1 psql -U letta -d
   matrix_letta -c "SELECT id, mxid FROM identities WHERE
   identity_type='letta' AND is_active=true;"`
2. **Manifest** (`--from-manifest <path>`): JSON with shape
   `{version:1, entries:[{agent_id, mxid?, note?}]}`. Manifest entries win on
   id collisions and may use the PM source form `agent-<uuid>` (converted to
   canonical `letta_agent-<uuid>` on load).

The merged set is written to `agent-addresses.kv` (LF-only,
`agentId=<wire>` per line, atomic write: tmp → fsync → rename).

### Flags

| Flag              | Effect                                                    |
| ----------------- | --------------------------------------------------------- |
| `--dry-run`       | Print the plan, write nothing. Exit 0.                    |
| `--from-manifest` | Add manifest entries (PM-letta-mobile lives here).        |
| `--stub-sql`      | Skip the docker exec psql pull (emit zero SQL rows).      |
| `--force`         | Ignore `.seedDone` and re-seed.                           |
| `--iroh-home`     | Override `~/.letta/iroh/` (default; envvar also honored). |

### Hard rules (recon failure modes — embedded as code comments)

1. **No HTTP fallback.** If `docker exec` fails, the seed exits non-zero with
   a loud error. Do not add "if SQL fails, call app-server over HTTP".
2. **Atomic write.** tmp → fsync → rename. A partial canonical-path file is
   a corruption (same family as recon failure-mode 2).
3. **LF-only.** Every output file is LF-only. CRLF anywhere = FAIL
   (recon failure-mode 7).
4. **Strict id validation.** `^letta_agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]
   {4}-[0-9a-f]{4}-[0-9a-f]{12}$` — with a documented PM source-form
   conversion at manifest load. Anything else raises `ValueError` before
   any write.
5. **Do not touch `paired-peers.json`** (`/etc/meridian/paired-peers.json`).
   That's runtime session-paired state written by the wrapper as peers dial
   in. This seed writes a separate file.

---

## Operator recipes

### First-time seed (host with no prior Iroh data)

```bash
# 1. Confirm SQL count (sanity check).
docker exec letta-postgres-1 psql -U letta -d matrix_letta -tAc \
  "SELECT count(*) FROM identities WHERE identity_type='letta' AND is_active=true;"
# Expect: ~114 (current at dispatch time 2026-08-06).

# 2. Dry-run to print the plan without writing anything.
python3 scripts/deploy/seed-agent-address-book.py --dry-run
# Expect: `plan: sql_rows=N manifest_entries=2 merged=N+1`

# 3. Real seed.
python3 scripts/deploy/seed-agent-address-book.py \
  --from-manifest scripts/deploy/agent-address-book.manifest.json
# Expect: writes ~/.letta/iroh/{identities, agent-addresses.kv, .seedDone}.
# Exit code: 0.
```

### Re-seed (after adding a new agent)

Edit `scripts/deploy/agent-address-book.manifest.json` and add a new entry.
Re-run with `--force` to overwrite `.seedDone`:

```bash
python3 scripts/deploy/seed-agent-address-book.py \
  --from-manifest scripts/deploy/agent-address-book.manifest.json --force
```

### Add a new agent (no manifest edit)

If the agent already has a row in `matrix_letta.identities` with
`identity_type='letta'` and `is_active=true`, the SQL pull picks it up
automatically. Just re-run:

```bash
python3 scripts/deploy/seed-agent-address-book.py --force
```

### Add a new agent before it has a Matrix identity

Add an entry to `agent-address-book.manifest.json` with `mxid: null` and a
note pointing at the upstream work (e.g. `bn008.8`). Re-run with `--force`.

---

## Verification (paste into PR body per doctrine 11)

```bash
# File modes on the seeded dirs.
stat -c '%a %n' ~/.letta/iroh ~/.letta/iroh/identities
# Expect: 700 .../iroh / 700 .../identities

# kv is LF-only.
od -c ~/.letta/iroh/agent-addresses.kv | head -3
file ~/.letta/iroh/agent-addresses.kv
# Expect: ASCII text (no "with CRLF line terminators")

# Spot-check Meridian + PM-letta-mobile entries are in the kv.
grep -E "letta_agent-(597b5756|c356b54a)" ~/.letta/iroh/agent-addresses.kv
# Expect:
#   letta_agent-597b5756-2915-4560-ba6b-91005f085166=
#   letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f=

# Active letta identities in SQL.
docker exec letta-postgres-1 psql -U letta -d matrix_letta -tAc \
  "SELECT count(*) FROM identities WHERE identity_type='letta' AND is_active=true;"
# Expect: ~114

# Dry-run (no writes).
python3 scripts/deploy/seed-agent-address-book.py --dry-run
# Expect: `plan: sql_rows=N manifest_entries=2 merged=N+1` then exit 0.

# Real seed.
python3 scripts/deploy/seed-agent-address-book.py \
  --from-manifest scripts/deploy/agent-address-book.manifest.json
# Expect: writes dirs + kv + marker; exit 0.

# .seedDone marker format.
cat ~/.letta/iroh/.seedDone
# Expect: entries=N+1 / source=... lines.

# Pytest for the script's invariants.
pytest tests/scripts/test_seed_agent_address_book.py -v

# Make target (wraps the script for release checklists).
make seed-iroh-address-book
```

---

## Deferred AC #5 — re-verifies after bn008.6 lands

> **AC #5 rewrite (per Meridian ruling 2026-08-06):** Removed "roundtrip
> proof via `meridian agent-message send --probe` at this PR" — unstageable
> without the IrohAgentMessageReceiver wire (bn008.6). Re-verifies after
> bn008.6 merge: a `meridian agent-message send --to <seeded agent_id> --probe`
> from one of the seeded agents arrives at the destination's wrapper log.
>
> **deferred-because: bn008.6**

The seed establishes the address book; bn008.6's wrapper merge wires the
per-agent identity file + receiver. Until then, `resolver.resolve(pmId)`
returns `AddressResolution.Unavailable` with `reason="corrupt_entry"` or
`"not_registered"` depending on whether a kv line is present.

---

## Files

- `scripts/deploy/seed-agent-address-book.py` (0755, Python 3.11+) — the seed.
- `scripts/deploy/agent-address-book.manifest.json` (0644) — Meridian + PM
  entries; checked into the repo so operators can re-run from a known-good
  input. Manifest entries override SQL rows on id collision.
- `tests/scripts/test_seed_agent_address_book.py` (pytest) — covers id
  validation, PM source-form conversion, manifest merge, atomic write,
  LF-only, --dry-run idempotency, --from-manifest end-to-end, executable bit.
- `Makefile` — `seed-iroh-address-book` target wraps the script for release
  checklists (no `--force`; pass `FORCE=1` to bypass the idempotency guard).

---

## Failure-mode index (cross-referenced from the recon)

| Recon # | Title                                                | Where enforced                       |
| ------- | ---------------------------------------------------- | ------------------------------------ |
| 1       | No HTTP fallback                                     | `seed-agent-address-book.py:336-341` |
| 2       | Don't mutate `paired-peers.json`                     | this script writes a separate kv     |
| 3       | IDs are `letta_agent-<uuid>`, not `agent-<uuid>`     | `validate_id` + `normalize_manifest_id` |
| 4       | PM-letta-mobile has no Matrix identity → manifest    | manifest loader + PM one-off branch  |
| 5       | Roundtrip proof is bn008.6's AC                      | deferred-because: bn008.6            |
| 6       | Scope = `identity_type='letta' AND is_active=true`   | the SQL query in `pull_sql_rows`     |
| 7       | CRLF / line endings matter                           | `atomic_write_text` + LF-only assert |