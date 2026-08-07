#!/usr/bin/env python3
# letta-mobile-bn008.7 — Seed host a2a address book + stable per-agent Iroh identities.
#
# PURPOSE
#   Populate ~/.letta/iroh/ with the static address book + identity artefacts the
#   wrapper reads at boot to resolve agentId -> Iroh EndpointAddr, so that
#   `meridian agent-message send --to <seeded agent_id>` (after bn008.6 lands
#   the receiver) has a dialable target.
#
# ARTIFACTS (per the Meridian ruling, 2026-08-06):
#   1. ~/.letta/iroh/                       (mode 0700)
#   2. ~/.letta/iroh/identities/            (mode 0700)  — IrohAgentIdentity.loadOrCreate dir
#   3. ~/.letta/iroh/identities/<agentId>   (mode 0600)  — populated by the wrapper on first dial
#   4. ~/.letta/iroh/agent-addresses.kv     (mode 0644)  — FileIrohAgentAddressStore
#   5. ~/.letta/iroh/.seedDone              (mode 0600)  — iroh.addressbook.seedDone marker
#
#   We do NOT write per-agent JSON identity files from this script: that
#   would require generating real Iroh Ed25519 secret keys, which must happen
#   on the host that owns the identity (each wrapper instance is its own
#   Iroh node). IrohAgentIdentity.loadOrCreate generates + persists the key
#   the first time an agent calls it; the empty dir + a README are the
#   correct seed. bn008.6's wrapper merge is the trigger that wires each
#   kv entry to the per-agent identity file.
#
# DATA SOURCES (no HTTP fallback — failure is loud):
#   - Default:   docker exec letta-postgres-1 psql -U letta -d matrix_letta -c
#                "SELECT id, mxid FROM identities
#                 WHERE identity_type='letta' AND is_active=true;"
#   - Always:    --from-manifest <path> entries (for PM-letta-mobile, which has
#                no Matrix identity row in matrix_letta.identities)
#
# ATOMIC WRITE DISCIPLINE
#   Each output is written tmp -> fsync -> rename. A partial canonical-path
#   file is a corruption (recon failure-mode 2: "partial file = corruption").
#
# LF-ONLY LINE ENDINGS
#   All output files are LF-only. CRLF anywhere = FAIL (recon failure-mode 7).
#
# ID FORMAT
#   Canonical:  ^letta_agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$
#   PM exception: the manifest loader accepts `agent-<uuid>` (the source form
#   of PM-letta-mobile's id, per the dispatch brief) and converts it to the
#   canonical `letta_agent-<uuid>` on the way in. Anything else is rejected
#   with a loud ValueError before any write happens.
#
# USAGE
#   seed-agent-address-book.py --dry-run
#   seed-agent-address-book.py --from-manifest /etc/meridian/agent-address-book.manifest.json
#   seed-agent-address-book.py --from-manifest <p> --stub-sql   # no docker
#   seed-agent-address-book.py --force                           # ignore .seedDone

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Defaults — overridable via envvars so the test suite can sandbox HOME.
# ---------------------------------------------------------------------------

DEFAULT_IROH_HOME = Path(
    os.environ.get("LETTA_IROH_HOME", str(Path.home() / ".letta" / "iroh"))
)
DEFAULT_IDENTITIES_DIR = Path(
    os.environ.get("LETTA_IROH_IDENTITIES_DIR", str(DEFAULT_IROH_HOME / "identities"))
)
DEFAULT_KV = Path(
    os.environ.get("LETTA_IROH_ADDRESSES_KV", str(DEFAULT_IROH_HOME / "agent-addresses.kv"))
)
DEFAULT_SEED_DONE = Path(
    os.environ.get("LETTA_IROH_SEED_DONE", str(DEFAULT_IROH_HOME / ".seedDone"))
)

DEFAULT_PSQL = [
    "docker", "exec", "letta-postgres-1",
    "psql", "-U", "letta", "-d", "matrix_letta",
    "-tAc",
    "SELECT id, mxid FROM identities "
    "WHERE identity_type='letta' AND is_active=true ORDER BY id;",
]

# Strict canonical id form per the dispatch brief.
CANONICAL_ID = re.compile(
    r"^letta_agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
# PM exception form (the source form from the recon: `agent-<uuid>`).
PM_SOURCE_ID = re.compile(
    r"^agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)


# ---------------------------------------------------------------------------
# ID validation + PM normalization
# ---------------------------------------------------------------------------

def validate_id(agent_id: str) -> str:
    """Strict canonical id check. Raises ValueError on anything else."""
    if not isinstance(agent_id, str) or not agent_id.strip():
        raise ValueError("agent id is blank")
    candidate = agent_id.strip()
    if candidate.startswith("agent-") and not candidate.startswith("letta_agent-"):
        raise ValueError(
            f"agent id {candidate!r} uses 'agent-' prefix; canonical form is "
            f"'letta_agent-<uuid>'. Use --from-manifest to load PM-letta-mobile "
            f"(its 'agent-<uuid>' source form is converted to 'letta_agent-<uuid>' "
            f"by normalize_manifest_id)."
        )
    if not CANONICAL_ID.match(candidate):
        raise ValueError(
            f"agent id {candidate!r} does not match "
            f"^letta_agent-[0-9a-f]{{8}}-[0-9a-f]{{4}}-[0-9a-f]{{4}}-[0-9a-f]{{4}}-[0-9a-f]{{12}}$"
        )
    return candidate


def normalize_manifest_id(agent_id: str) -> str:
    """One-off conversion for PM-letta-mobile: `agent-<uuid>` -> `letta_agent-<uuid>`.
    Anything else is rejected loudly before it reaches the kv write."""
    if not isinstance(agent_id, str) or not agent_id.strip():
        raise ValueError("manifest agent_id is blank")
    candidate = agent_id.strip()
    if PM_SOURCE_ID.match(candidate):
        converted = "letta_agent-" + candidate[len("agent-"):]
        # The converted form MUST match the canonical regex.
        if not CANONICAL_ID.match(converted):
            raise ValueError(f"PM conversion produced non-canonical id: {converted!r}")
        return converted
    if CANONICAL_ID.match(candidate):
        return candidate
    raise ValueError(
        f"manifest agent_id {candidate!r} is neither canonical 'letta_agent-<uuid>' "
        f"nor PM source 'agent-<uuid>'"
    )


# ---------------------------------------------------------------------------
# Manifest + SQL pull + merge
# ---------------------------------------------------------------------------

def load_manifest(path: Path) -> list[dict]:
    """Load a manifest file with shape {"version": 1, "entries": [{agent_id, mxid, ...}]}.
    Returns a list of normalized entries with `agent_id` in canonical form."""
    if not path.exists():
        raise FileNotFoundError(f"manifest not found: {path}")
    raw = json.loads(path.read_text())
    if not isinstance(raw, dict) or raw.get("version") != 1:
        raise ValueError(f"manifest {path} must have version=1, got {raw!r}")
    entries = raw.get("entries", [])
    if not isinstance(entries, list):
        raise ValueError(f"manifest {path} entries must be a list, got {type(entries).__name__}")
    out = []
    for i, e in enumerate(entries):
        if not isinstance(e, dict):
            raise ValueError(f"manifest entry #{i} is not an object: {e!r}")
        if "agent_id" not in e:
            raise ValueError(f"manifest entry #{i} missing agent_id: {e!r}")
        canonical = normalize_manifest_id(e["agent_id"])
        out.append({
            "agent_id": canonical,
            "mxid": e.get("mxid"),
            "note": e.get("note"),
        })
    return out


def pull_sql_rows() -> list[dict]:
    """Pull active `letta` identities from matrix_letta via docker exec psql.
    Returns a list of {"id": ..., "mxid": ...} dicts.
    Raises RuntimeError on any failure (no silent fallback)."""
    proc = subprocess.run(
        DEFAULT_PSQL,
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"psql pull failed (rc={proc.returncode}): {proc.stderr.strip()}\n"
            f"command: {' '.join(DEFAULT_PSQL)}"
        )
    rows: list[dict] = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split("|", 1)
        if len(parts) != 2:
            raise RuntimeError(f"psql returned unexpected row shape: {line!r}")
        rows.append({"id": parts[0].strip(), "mxid": parts[1].strip()})
    return rows


def merge_sql_and_manifest(
    sql_rows: list[dict], manifest_entries: list[dict]
) -> list[dict]:
    """Manifest entries override SQL rows on id collision; manifest can add ids
    the SQL pull missed. Manifest entries with mxid=None are kept (PM placeholder).
    All ids are validated before write."""
    by_id: dict[str, dict] = {}
    # SQL first (all sql rows have non-null mxid by schema).
    for row in sql_rows:
        canonical = validate_id(row["id"])
        by_id[canonical] = {
            "agent_id": canonical,
            "mxid": row["mxid"],
            "note": None,
        }
    # Manifest second — overrides on collision, adds new on miss. Manifest
    # entries may use the PM source form (`agent-<uuid>`); normalize first
    # so they land in canonical form. validate_id is the final belt-and-braces
    # check, but normalize_manifest_id already enforces the strict shape.
    for entry in manifest_entries:
        canonical = normalize_manifest_id(entry["agent_id"])
        # Double-check (defense in depth) — normalize already enforced this.
        validate_id(canonical)
        existing = by_id.get(canonical)
        by_id[canonical] = {
            "agent_id": canonical,
            "mxid": entry.get("mxid"),
            "note": entry.get("note"),
        }
        # If we replaced a SQL row, preserve a hint that this was a manifest override.
        if existing is not None and entry.get("mxid") != existing.get("mxid"):
            by_id[canonical]["note"] = entry.get("note") or "manifest override"
    return list(by_id.values())


# ---------------------------------------------------------------------------
# Atomic write
# ---------------------------------------------------------------------------

def atomic_write_text(target: Path, content: str, mode: int) -> None:
    """Write `content` to `target` atomically: tmp -> fsync -> rename.
    `content` MUST end with a newline; we re-assert LF-only."""
    if "\r" in content:
        raise ValueError(
            f"refusing to write {target}: input contains CR byte (CRLF violation)"
        )
    if not content.endswith("\n"):
        content = content + "\n"
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".tmp")
    # If a leftover tmp from a prior crash exists, remove it before writing.
    if tmp.exists():
        tmp.unlink()
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, target)
    os.chmod(target, mode)


def read_existing_wires(target: Path) -> dict[str, str]:
    """Parse an existing agent-addresses.kv into {agent_id: wire}.

    Missing or empty files yield {}. Comment / blank lines are skipped.
    Used so a --force re-seed does not wipe wires that
    FileIrohAgentAddressStore.register already persisted.
    """
    if not target.exists():
        return {}
    wires: dict[str, str] = {}
    for line in target.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        agent_id, _, wire = stripped.partition("=")
        agent_id = agent_id.strip()
        if agent_id:
            wires[agent_id] = wire
    return wires


def atomic_write_kv(target: Path, entries: list[dict]) -> None:
    """Render the kv file (one `agentId=<wire>` per line, LF-only, trailing LF).

    When a merged entry omits `iroh_endpoint`, any previously stored non-empty
    wire for that agent_id is preserved. An explicitly supplied endpoint
    (including empty string) replaces the stored value. This keeps
    FileIrohAgentAddressStore.register results intact across --force re-seeds.
    """
    existing = read_existing_wires(target)
    lines = []
    for entry in entries:
        agent_id = validate_id(entry["agent_id"])
        # Wire form per FileIrohAgentAddressStore: `agentId=<hexNodeId>` when
        # no direct addrs; `<hexNodeId>@a,b` when present. Stage-1 SQL/manifest
        # merges omit iroh_endpoint — preserve any registered wire in that case.
        if "iroh_endpoint" in entry:
            wire = entry["iroh_endpoint"] or ""
        else:
            wire = existing.get(agent_id) or ""
        lines.append(f"{agent_id}={wire}")
    content = "\n".join(lines) + "\n"
    # Mode 0644 since all entries have null endpoints in stage 1 (dispatch
    # brief: "default 0644 since all are null in stage 1").
    atomic_write_text(target, content, 0o644)


def write_seed_done_marker(target: Path, count: int, source: str) -> None:
    """Write the iroh.addressbook.seedDone marker. Mode 0600."""
    content = (
        f"# iroh.addressbook.seedDone — written by scripts/deploy/seed-agent-address-book.py\n"
        f"# Format: key=value, one per line. Consumed by re-runs to short-circuit.\n"
        f"entries={count}\n"
        f"source={source}\n"
    )
    atomic_write_text(target, content, 0o600)


# ---------------------------------------------------------------------------
# Identity artefacts
# ---------------------------------------------------------------------------

IDENTITIES_README = """# Iroh agent identities

This directory is the load-or-create target for IrohAgentIdentity (Kotlin) at
runtime. Each agent whose address is published in agent-addresses.kv will, on
first dial, write a per-agent secret key file here:

    {agent_id}.json

schema: `{{"agentId": "<agentId>", "secretKeyB64": "<base64>"}}`

mode:    0700 (dir) / 0600 (per-agent file) — set by the wrapper at first write.

WHY THIS DIRECTORY IS EMPTY AT SEED TIME
The seed script MUST NOT write per-agent secret key files: each host that
publishes an agent's address needs its OWN Iroh identity (the node id is the
agent's dialable handle on THAT host). Inventing a key in Python would either
(a) collide with the wrapper's own generated key or (b) leave a broken
empty-key file that crashes the wrapper on read (parse() succeeds with
secretKeyB64=\"\", but SecretKey.fromBytes([]) throws).

bn008.6's wrapper merge is what causes each agent's identity file to be
written here. This seed only ensures the dir exists with the right perms.
"""


def ensure_identity_dir(dir_path: Path) -> None:
    """mkpath ~/.letta/iroh/identities/ (mode 0700) + ~/.letta/iroh/ (mode 0700).
    Writes a README explaining the dir's role so future operators don't think
    the empty dir is a bug."""
    dir_path.mkdir(parents=True, exist_ok=True)
    os.chmod(dir_path, 0o700)
    readme = dir_path / "README.md"
    if not readme.exists():
        atomic_write_text(readme, IDENTITIES_README, 0o600)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_argparser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "Seed ~/.letta/iroh/ with the static Iroh address book + identity dir. "
            "Default mode runs `docker exec letta-postgres-1 psql ...`; --from-manifest "
            "adds manual entries (PM-letta-mobile has no Matrix identity row)."
        ),
    )
    p.add_argument(
        "--from-manifest", metavar="PATH", default=None,
        help="path to a JSON manifest with {version:1, entries:[{agent_id, mxid?, note?}]}",
    )
    p.add_argument(
        "--dry-run", action="store_true",
        help="print the planned entry count, do not write any artifact",
    )
    p.add_argument(
        "--stub-sql", action="store_true",
        help="skip the docker exec psql pull (emit zero SQL rows); useful for tests / dry layouts",
    )
    p.add_argument(
        "--force", action="store_true",
        help="ignore an existing .seedDone marker and re-seed",
    )
    p.add_argument(
        "--iroh-home", default=str(DEFAULT_IROH_HOME),
        help=f"override the Iroh home dir (default: {DEFAULT_IROH_HOME})",
    )
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_argparser().parse_args(argv)

    iroh_home = Path(args.iroh_home)
    identities_dir = iroh_home / "identities"
    kv = iroh_home / "agent-addresses.kv"
    seed_done = iroh_home / ".seedDone"

    # Idempotency guard: skip if marker present and --force not set.
    if seed_done.exists() and not args.force and not args.dry_run:
        print(f"seed-done marker present at {seed_done} — skipping (pass --force to re-seed)", file=sys.stderr)
        return 0

    # Data sources.
    sql_rows: list[dict] = []
    if not args.stub_sql:
        try:
            sql_rows = pull_sql_rows()
        except Exception as e:
            print(f"FATAL: SQL pull failed: {e}", file=sys.stderr)
            print("DO NOT add an HTTP fallback — the seed is offline by design.", file=sys.stderr)
            return 2

    manifest_entries: list[dict] = []
    if args.from_manifest:
        try:
            manifest_entries = load_manifest(Path(args.from_manifest))
        except (FileNotFoundError, ValueError) as e:
            # On --dry-run, a missing/empty/invalid manifest is treated as
            # "no manifest" so operators can verify SQL counts alone.
            # On a real (non-dry) run, this IS fatal — silent fallback
            # would let an operator think they seeded PM when they didn't.
            if args.dry_run:
                print(
                    f"dry-run: --from-manifest {args.from_manifest} unavailable "
                    f"({e}); ignoring",
                    file=sys.stderr,
                )
            else:
                print(f"FATAL: manifest load failed: {e}", file=sys.stderr)
                return 2

    # Validate every id before any write — loud error if any are wrong.
    merged = merge_sql_and_manifest(sql_rows, manifest_entries)
    for entry in merged:
        # validate_id raises on any non-canonical form; we already normalized
        # manifest entries, so this is a final belt-and-braces pass.
        validate_id(entry["agent_id"])

    print(f"plan: sql_rows={len(sql_rows)} manifest_entries={len(manifest_entries)} merged={len(merged)}")
    if args.dry_run:
        # Print the count and DO NOT write anything.
        print("dry-run: would write identity dir + kv + seed-done marker; skipping writes")
        return 0

    # Writes (atomic, LF-only).
    iroh_home.mkdir(parents=True, exist_ok=True)
    os.chmod(iroh_home, 0o700)
    ensure_identity_dir(identities_dir)
    atomic_write_kv(kv, merged)
    write_seed_done_marker(seed_done, len(merged), source=str(args.from_manifest or "sql+manifest"))
    print(f"wrote: {identities_dir} (0700), {kv} (0644), {seed_done} (0600)")
    print(f"iroh.addressbook.seedDone{{entries={len(merged)}}}")
    return 0


if __name__ == "__main__":
    sys.exit(main())