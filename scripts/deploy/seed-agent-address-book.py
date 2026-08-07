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

def _require_nonblank_str(value: object, blank_message: str) -> str:
    """Raise ValueError unless `value` is a non-blank string."""
    if not isinstance(value, str):
        raise ValueError(blank_message)
    candidate = value.strip()
    if not candidate:
        raise ValueError(blank_message)
    return candidate


def _is_bare_agent_prefix(candidate: str) -> bool:
    """True for `agent-<…>` that is not already `letta_agent-<…>`."""
    return candidate.startswith("agent-") and not candidate.startswith("letta_agent-")


def validate_id(agent_id: str) -> str:
    """Strict canonical id check. Raises ValueError on anything else."""
    candidate = _require_nonblank_str(agent_id, "agent id is blank")
    if _is_bare_agent_prefix(candidate):
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
    candidate = _require_nonblank_str(agent_id, "manifest agent_id is blank")
    if PM_SOURCE_ID.match(candidate):
        converted = "letta_agent-" + candidate[len("agent-"):]
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

def _normalize_manifest_entry(index: int, entry: object) -> dict:
    """Validate one manifest entry object and return its canonical form."""
    if not isinstance(entry, dict):
        raise ValueError(f"manifest entry #{index} is not an object: {entry!r}")
    if "agent_id" not in entry:
        raise ValueError(f"manifest entry #{index} missing agent_id: {entry!r}")
    return {
        "agent_id": normalize_manifest_id(entry["agent_id"]),
        "mxid": entry.get("mxid"),
        "note": entry.get("note"),
    }


def _read_manifest_document(path: Path) -> dict:
    """Load + validate the top-level manifest document (version=1)."""
    if not path.exists():
        raise FileNotFoundError(f"manifest not found: {path}")
    raw = json.loads(path.read_text())
    if not isinstance(raw, dict):
        raise ValueError(f"manifest {path} must have version=1, got {raw!r}")
    if raw.get("version") != 1:
        raise ValueError(f"manifest {path} must have version=1, got {raw!r}")
    return raw


def _manifest_entries_list(path: Path, raw: dict) -> list:
    """Extract the entries list from a validated manifest document."""
    entries = raw.get("entries", [])
    if not isinstance(entries, list):
        raise ValueError(
            f"manifest {path} entries must be a list, got {type(entries).__name__}"
        )
    return entries


def load_manifest(path: Path) -> list[dict]:
    """Load a manifest file with shape {"version": 1, "entries": [{agent_id, mxid, ...}]}.
    Returns a list of normalized entries with `agent_id` in canonical form."""
    raw = _read_manifest_document(path)
    entries = _manifest_entries_list(path, raw)
    return [_normalize_manifest_entry(i, e) for i, e in enumerate(entries)]


def _parse_psql_row(line: str) -> dict:
    """Parse one `id|mxid` psql `-tAc` line into a row dict."""
    parts = line.split("|", 1)
    if len(parts) != 2:
        raise RuntimeError(f"psql returned unexpected row shape: {line!r}")
    return {"id": parts[0].strip(), "mxid": parts[1].strip()}


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
        stripped = line.strip()
        if not stripped:
            continue
        rows.append(_parse_psql_row(stripped))
    return rows


def _sql_row_to_entry(row: dict) -> dict:
    canonical = validate_id(row["id"])
    return {"agent_id": canonical, "mxid": row["mxid"], "note": None}


def _manifest_override_note(entry: dict, existing: dict | None) -> str | None:
    note = entry.get("note")
    if existing is None:
        return note
    if entry.get("mxid") == existing.get("mxid"):
        return note
    return note or "manifest override"


def merge_sql_and_manifest(
    sql_rows: list[dict], manifest_entries: list[dict]
) -> list[dict]:
    """Manifest entries override SQL rows on id collision; manifest can add ids
    the SQL pull missed. Manifest entries with mxid=None are kept (PM placeholder).
    All ids are validated before write."""
    by_id: dict[str, dict] = {}
    for row in sql_rows:
        entry = _sql_row_to_entry(row)
        by_id[entry["agent_id"]] = entry
    for entry in manifest_entries:
        canonical = normalize_manifest_id(entry["agent_id"])
        validate_id(canonical)
        existing = by_id.get(canonical)
        by_id[canonical] = {
            "agent_id": canonical,
            "mxid": entry.get("mxid"),
            "note": _manifest_override_note(entry, existing),
        }
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
    if tmp.exists():
        tmp.unlink()
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, target)
    os.chmod(target, mode)


def _is_skippable_kv_line(stripped: str) -> bool:
    """True for blank, comment, or non key=value kv lines."""
    if not stripped:
        return True
    if stripped.startswith("#"):
        return True
    return "=" not in stripped


def _parse_kv_line(stripped: str) -> tuple[str, str] | None:
    """Parse one `agentId=wire` line. Returns None when the agent id is blank."""
    agent_id, _, wire = stripped.partition("=")
    agent_id = agent_id.strip()
    if not agent_id:
        return None
    return agent_id, wire


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
        if _is_skippable_kv_line(stripped):
            continue
        parsed = _parse_kv_line(stripped)
        if parsed is None:
            continue
        agent_id, wire = parsed
        wires[agent_id] = wire
    return wires


def _resolve_wire(entry: dict, existing: dict[str, str], agent_id: str) -> str:
    """Pick the wire for one kv line: explicit endpoint wins, else preserve."""
    if "iroh_endpoint" in entry:
        return entry["iroh_endpoint"] or ""
    return existing.get(agent_id) or ""


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
        wire = _resolve_wire(entry, existing, agent_id)
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


def _should_skip_seed(seed_done: Path, force: bool, dry_run: bool) -> bool:
    """True when a prior seed marker should short-circuit this run."""
    if dry_run:
        return False
    if force:
        return False
    return seed_done.exists()


def _load_sql_rows(stub_sql: bool) -> tuple[list[dict] | None, int]:
    """Return (rows, 0) on success, or (None, exit_code) on fatal SQL failure."""
    if stub_sql:
        return [], 0
    try:
        return pull_sql_rows(), 0
    except Exception as e:
        print(f"FATAL: SQL pull failed: {e}", file=sys.stderr)
        print("DO NOT add an HTTP fallback — the seed is offline by design.", file=sys.stderr)
        return None, 2


def _load_manifest_entries(
    manifest_path: str | None, dry_run: bool,
) -> tuple[list[dict] | None, int]:
    """Return (entries, 0) on success, or (None, exit_code) on fatal load failure.

    On --dry-run, a missing/invalid manifest is treated as "no manifest" so
    operators can verify SQL counts alone. On a real run it is fatal.
    """
    if not manifest_path:
        return [], 0
    try:
        return load_manifest(Path(manifest_path)), 0
    except (FileNotFoundError, ValueError) as e:
        if dry_run:
            print(
                f"dry-run: --from-manifest {manifest_path} unavailable "
                f"({e}); ignoring",
                file=sys.stderr,
            )
            return [], 0
        print(f"FATAL: manifest load failed: {e}", file=sys.stderr)
        return None, 2


def _seed_paths(iroh_home: Path) -> dict[str, Path]:
    """Resolve the three artifact paths under an Iroh home directory."""
    return {
        "iroh_home": iroh_home,
        "identities_dir": iroh_home / "identities",
        "kv": iroh_home / "agent-addresses.kv",
        "seed_done": iroh_home / ".seedDone",
    }


def _write_seed_artifacts(paths: dict[str, Path], merged: list[dict], source: str) -> None:
    """Create dirs + write kv + seed-done marker (atomic, LF-only)."""
    iroh_home = paths["iroh_home"]
    identities_dir = paths["identities_dir"]
    kv = paths["kv"]
    seed_done = paths["seed_done"]
    iroh_home.mkdir(parents=True, exist_ok=True)
    os.chmod(iroh_home, 0o700)
    ensure_identity_dir(identities_dir)
    atomic_write_kv(kv, merged)
    write_seed_done_marker(seed_done, len(merged), source=source)
    print(f"wrote: {identities_dir} (0700), {kv} (0644), {seed_done} (0600)")
    print(f"iroh.addressbook.seedDone{{entries={len(merged)}}}")


def main(argv: list[str] | None = None) -> int:
    args = build_argparser().parse_args(argv)
    paths = _seed_paths(Path(args.iroh_home))
    seed_done = paths["seed_done"]

    if _should_skip_seed(seed_done, args.force, args.dry_run):
        print(
            f"seed-done marker present at {seed_done} — skipping (pass --force to re-seed)",
            file=sys.stderr,
        )
        return 0

    sql_rows, sql_rc = _load_sql_rows(args.stub_sql)
    if sql_rows is None:
        return sql_rc

    manifest_entries, manifest_rc = _load_manifest_entries(
        args.from_manifest, args.dry_run,
    )
    if manifest_entries is None:
        return manifest_rc

    merged = merge_sql_and_manifest(sql_rows, manifest_entries)
    for entry in merged:
        validate_id(entry["agent_id"])

    print(
        f"plan: sql_rows={len(sql_rows)} "
        f"manifest_entries={len(manifest_entries)} merged={len(merged)}"
    )
    if args.dry_run:
        print("dry-run: would write identity dir + kv + seed-done marker; skipping writes")
        return 0

    _write_seed_artifacts(
        paths,
        merged,
        source=str(args.from_manifest or "sql+manifest"),
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
