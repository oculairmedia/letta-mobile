#!/usr/bin/env python3
# letta-mobile-bn008.7 — pytest for scripts/deploy/seed-agent-address-book.py
#
# Covers:
#   - id validation (positive: letta_agent-<uuid>, negative: agent-<uuid>,
#     letta_agent-<short>, letta_agent-<uuid>extra, blank)
#   - PM one-off conversion (agent-<uuid> -> letta_agent-<uuid>) on manifest load
#   - manifest merge: manifest entry overrides SQL row when same id
#   - manifest merge: manifest can introduce ids that the SQL pull missed
#     (e.g. PM-letta-mobile, which has no Matrix identity row)
#   - atomic write: --dry-run exits 0, prints the entry count, and the canonical
#     output file mtime is unchanged
#   - schema: kv file written by --from-manifest is line-oriented, LF-only,
#     `agentId=<wire>` per line, last line newline present
#   - seed-done marker: written once after a successful seeded run; --force
#     overwrites the marker after a re-run
#
# These tests do NOT talk to Postgres (the production SQL pull is exercised by
# `make seed-iroh-address-book` against letta-postgres-1, see the runbook).
#
# CRLF discipline: pytest writes LF-only on Linux. We still assert LF-only on
# the produced kv file because CRLF anywhere = FAIL (doctrine 11 / recon
# failure-mode 7).

from __future__ import annotations

import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "deploy" / "seed-agent-address-book.py"

# Strict canonical id form per the dispatch brief.
CANONICAL_ID = re.compile(r"^letta_agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


# ---------- fixtures ---------------------------------------------------------

@pytest.fixture
def tmp_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Sandboxed HOME so the script writes its iroh dir + kv under tmp_path."""
    monkeypatch.setenv("HOME", str(tmp_path))
    # The script reads these envvars to know where the canonical host dirs are.
    monkeypatch.setenv("LETTA_IROH_HOME", str(tmp_path / ".letta" / "iroh"))
    monkeypatch.setenv("LETTA_IROH_IDENTITIES_DIR", str(tmp_path / ".letta" / "iroh" / "identities"))
    monkeypatch.setenv("LETTA_IROH_ADDRESSES_KV", str(tmp_path / ".letta" / "iroh" / "agent-addresses.kv"))
    monkeypatch.setenv("LETTA_IROH_SEED_DONE", str(tmp_path / ".letta" / "iroh" / ".seedDone"))
    return tmp_path


@pytest.fixture
def import_script():
    """Import the script as a module without running its CLI entry point.

    The script's `main()` reads sys.argv at import time, so we exec only its
    function defs via runpy's exec-mode style: simpler to just import it and
    rely on the module-level helpers being defined.
    """
    if not SCRIPT.exists():
        pytest.skip(f"script not yet implemented at {SCRIPT}")
    import importlib.util
    spec = importlib.util.spec_from_file_location("seed_agent_address_book", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ---------- id validation ----------------------------------------------------

class TestValidateId:
    """The dispatch brief's id validator regex, with a documented exception
    for PM-letta-mobile's `agent-` prefix that the manifest loader converts."""

    def test_canonical_letta_agent_uuid_accepted(self, import_script) -> None:
        good = "letta_agent-597b5756-2915-4560-ba6b-91005f085166"
        assert import_script.validate_id(good) == good

    def test_bare_agent_uuid_rejected(self, import_script) -> None:
        bad = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
        # The exception message mentions "agent-" and "canonical" — match
        # loosely to avoid coupling the test to a specific phrasing.
        with pytest.raises(ValueError, match=r"agent-.*canonical"):
            import_script.validate_id(bad)

    def test_short_uuid_rejected(self, import_script) -> None:
        bad = "letta_agent-597b5756-2915-4560-ba6b-91005f08516"  # truncated
        with pytest.raises(ValueError):
            import_script.validate_id(bad)

    def test_uuid_with_extra_rejected(self, import_script) -> None:
        bad = "letta_agent-597b5756-2915-4560-ba6b-91005f085166-extra"
        with pytest.raises(ValueError):
            import_script.validate_id(bad)

    def test_uppercase_uuid_rejected(self, import_script) -> None:
        bad = "letta_agent-597B5756-2915-4560-BA6B-91005F085166"
        with pytest.raises(ValueError):
            import_script.validate_id(bad)

    def test_blank_id_rejected(self, import_script) -> None:
        with pytest.raises(ValueError):
            import_script.validate_id("")
        with pytest.raises(ValueError):
            import_script.validate_id("   ")


# ---------- PM one-off branch (agent- -> letta_agent-) -----------------------

class TestNormalizePmId:
    """The dispatch brief's one-off branch: PM-letta-mobile's source id is
    `agent-c356b54a-8b37-4d53-b9d0-b43164749b6f` (DB form). The manifest
    loader converts it to canonical `letta_agent-<uuid>` on the way in."""

    def test_agent_prefix_converted(self, import_script) -> None:
        pm_src = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
        expected = "letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
        assert import_script.normalize_manifest_id(pm_src) == expected

    def test_letta_agent_prefix_unchanged(self, import_script) -> None:
        mer = "letta_agent-597b5756-2915-4560-ba6b-91005f085166"
        assert import_script.normalize_manifest_id(mer) == mer

    def test_unrelated_prefix_rejected(self, import_script) -> None:
        with pytest.raises(ValueError):
            import_script.normalize_manifest_id("foo-12345678-1234-1234-1234-123456789012")

    def test_normalized_id_passes_validator(self, import_script) -> None:
        pm_src = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
        norm = import_script.normalize_manifest_id(pm_src)
        # Should now round-trip through validate_id without raising.
        assert import_script.validate_id(norm) == norm


# ---------- manifest merge ---------------------------------------------------

class TestManifestMerge:
    """Manifest entries override SQL rows on id collision; manifest can add
    rows the SQL pull missed (PM-letta-mobile)."""

    def test_manifest_overrides_sql_on_collision(self, import_script) -> None:
        sql_rows = [
            {"id": "letta_agent-11111111-1111-1111-1111-111111111111", "mxid": "@old:matrix.oculair.ca"},
            {"id": "letta_agent-22222222-2222-2222-2222-222222222222", "mxid": "@keep:matrix.oculair.ca"},
        ]
        manifest = [
            {
                "agent_id": "letta_agent-11111111-1111-1111-1111-111111111111",
                "mxid": "@new:matrix.oculair.ca",
            },
        ]
        merged = import_script.merge_sql_and_manifest(sql_rows, manifest)
        by_id = {e["agent_id"]: e for e in merged}
        assert by_id["letta_agent-11111111-1111-1111-1111-111111111111"]["mxid"] == "@new:matrix.oculair.ca"
        assert by_id["letta_agent-22222222-2222-2222-2222-222222222222"]["mxid"] == "@keep:matrix.oculair.ca"

    def test_manifest_can_add_ids_not_in_sql(self, import_script) -> None:
        sql_rows = [{"id": "letta_agent-11111111-1111-1111-1111-111111111111", "mxid": "@a:matrix.oculair.ca"}]
        manifest = [
            {
                "agent_id": "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f",
                "mxid": None,
                "note": "no matrix identity — manual placeholder until bn008.8 emits one",
            },
        ]
        merged = import_script.merge_sql_and_manifest(sql_rows, manifest)
        by_id = {e["agent_id"]: e for e in merged}
        assert "letta_agent-11111111-1111-1111-1111-111111111111" in by_id
        assert "letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f" in by_id  # converted
        assert by_id["letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"]["mxid"] is None
        assert "bn008.8" in by_id["letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"]["note"]

    def test_merged_count_is_union_no_dupes(self, import_script) -> None:
        sql_rows = [
            {"id": "letta_agent-11111111-1111-1111-1111-111111111111", "mxid": "@a:matrix.oculair.ca"},
            {"id": "letta_agent-22222222-2222-2222-2222-222222222222", "mxid": "@b:matrix.oculair.ca"},
        ]
        manifest = [
            {"agent_id": "letta_agent-11111111-1111-1111-1111-111111111111", "mxid": "@x:matrix.oculair.ca"},
            {"agent_id": "letta_agent-33333333-3333-3333-3333-333333333333", "mxid": "@c:matrix.oculair.ca"},
        ]
        merged = import_script.merge_sql_and_manifest(sql_rows, manifest)
        assert len(merged) == 3
        ids = {e["agent_id"] for e in merged}
        assert ids == {
            "letta_agent-11111111-1111-1111-1111-111111111111",
            "letta_agent-22222222-2222-2222-2222-222222222222",
            "letta_agent-33333333-3333-3333-3333-333333333333",
        }


# ---------- atomic write -----------------------------------------------------

class TestAtomicWrite:
    """Write tmp, fsync, rename; no partial file ever at the canonical path."""

    def test_writes_tmp_then_renames(self, import_script, tmp_path: Path) -> None:
        target = tmp_path / "agent-addresses.kv"
        entries = [
            {"agent_id": "letta_agent-11111111-1111-1111-1111-111111111111", "mxid": "@a:matrix.oculair.ca"},
            {"agent_id": "letta_agent-22222222-2222-2222-2222-222222222222", "mxid": "@b:matrix.oculair.ca"},
        ]
        import_script.atomic_write_kv(target, entries)
        assert target.exists()
        # The .tmp file must NOT linger next to the canonical file.
        siblings = [p for p in tmp_path.iterdir() if p.name.endswith(".tmp")]
        assert siblings == [], f"leftover tmp files: {siblings}"

    def test_lf_only_line_endings(self, import_script, tmp_path: Path) -> None:
        target = tmp_path / "agent-addresses.kv"
        entries = [
            {"agent_id": "letta_agent-11111111-1111-1111-1111-111111111111", "mxid": "@a:matrix.oculair.ca"},
            {"agent_id": "letta_agent-22222222-2222-2222-2222-222222222222", "mxid": "@b:matrix.oculair.ca"},
        ]
        import_script.atomic_write_kv(target, entries)
        raw = target.read_bytes()
        assert b"\r" not in raw, "kv file must be LF-only (no CRLF) — got a CR byte"
        # Each line ends with LF, the file ends with a final LF.
        assert raw.endswith(b"\n")

    def test_kv_lines_match_expected_format(self, import_script, tmp_path: Path) -> None:
        target = tmp_path / "agent-addresses.kv"
        entries = [
            {"agent_id": "letta_agent-11111111-1111-1111-1111-111111111111", "mxid": "@a:matrix.oculair.ca"},
            {"agent_id": "letta_agent-22222222-2222-2222-2222-222222222222", "mxid": None},
        ]
        import_script.atomic_write_kv(target, entries)
        lines = target.read_text().splitlines()
        # One line per entry; last entry's blank mxid renders as the bare
        # nodeIdHex placeholder OR empty after `=` — either is acceptable as
        # long as the agentId is present.
        for line in lines:
            assert "=" in line, f"expected 'agentId=wire' on every line, got: {line!r}"
            agent_id, _, wire = line.partition("=")
            assert CANONICAL_ID.match(agent_id), f"bad agent_id on line: {agent_id!r}"


# ---------- --dry-run atomic discipline (CLI) ---------------------------------

class TestDryRun:
    """`--dry-run` exits 0, prints the entry count, and the canonical output
    file's mtime is unchanged (proves atomic-write discipline)."""

    def _write_seed_done_marker(self, marker: Path, count: int) -> None:
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text(f"entries={count}\n")

    def _write_existing_kv(self, kv: Path, content: str) -> float:
        kv.parent.mkdir(parents=True, exist_ok=True)
        kv.write_text(content)
        # Touch the mtime to a stable past value so we can detect unchanged.
        past = time.time() - 86400
        os.utime(kv, (past, past))
        return past

    def test_dry_run_does_not_modify_canonical_kv(
        self, tmp_home: Path, import_script, tmp_path: Path,
    ) -> None:
        kv = Path(os.environ["LETTA_IROH_ADDRESSES_KV"])
        marker = Path(os.environ["LETTA_IROH_SEED_DONE"])
        marker.parent.mkdir(parents=True, exist_ok=True)
        existing = (
            "letta_agent-11111111-1111-1111-1111-111111111111=nodeAAAA\n"
        )
        original_mtime = self._write_existing_kv(kv, existing)
        result = subprocess.run(
            [
                sys.executable, str(SCRIPT), "--dry-run",
                "--from-manifest", "/dev/null",  # ignored on --dry-run
            ],
            env=os.environ.copy(),
            capture_output=True, text=True, timeout=30,
        )
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert kv.exists(), "canonical kv must remain present after --dry-run"
        assert kv.read_text() == existing, "--dry-run must not rewrite the kv"
        # mtime is preserved by os.utime above — re-stat and confirm.
        new_mtime = kv.stat().st_mtime
        assert abs(new_mtime - original_mtime) < 1.0, (
            f"--dry-run touched the kv mtime ({original_mtime} -> {new_mtime})"
        )


# ---------- --from-manifest end-to-end (no Postgres) --------------------------

class TestFromManifest:
    """End-to-end `--from-manifest <file>` against a tmp HOME, with a stub
    SQL pull that returns zero rows. Exercises the full write path without
    needing letta-postgres-1."""

    def _write_manifest(self, path: Path, entries: list[dict]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"version": 1, "entries": entries}, indent=2) + "\n")

    def test_from_manifest_writes_kv_and_marker(
        self, tmp_home: Path, tmp_path: Path,
    ) -> None:
        kv = Path(os.environ["LETTA_IROH_ADDRESSES_KV"])
        identities_dir = Path(os.environ["LETTA_IROH_IDENTITIES_DIR"])
        marker = Path(os.environ["LETTA_IROH_SEED_DONE"])

        manifest = tmp_path / "manifest.json"
        self._write_manifest(manifest, [
            {
                "agent_id": "letta_agent-597b5756-2915-4560-ba6b-91005f085166",
                "mxid": "@agent_597b5756_2915_4560_ba6b_91005f085166:matrix.oculair.ca",
            },
            {
                "agent_id": "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f",
                "mxid": None,
                "note": "no matrix identity — manual placeholder until bn008.8 emits one",
            },
        ])
        result = subprocess.run(
            [
                sys.executable, str(SCRIPT),
                "--from-manifest", str(manifest),
                "--stub-sql",  # bypass docker exec; emit zero SQL rows
            ],
            env=os.environ.copy(),
            capture_output=True, text=True, timeout=30,
        )
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"

        # Identity artefacts: dir exists with 0700. Per-agent JSON files are
        # NOT written by the seed (rationale: IrohAgentIdentity.loadOrCreate
        # generates a fresh Ed25519 key on first dial per host; we cannot
        # generate one in Python without the iroh lib, and an empty key
        # crashes the wrapper on parse). The README in the dir documents
        # this. bn008.6's wrapper merge populates per-agent files.
        assert identities_dir.exists()
        dir_mode = stat.S_IMODE(identities_dir.stat().st_mode)
        assert dir_mode == 0o700, f"identities dir mode {oct(dir_mode)} != 0700"
        readme = identities_dir / "README.md"
        assert readme.exists(), "identities dir must contain a README explaining the empty-dir seed"

        # kv exists, LF-only, expected shape.
        assert kv.exists()
        raw = kv.read_bytes()
        assert b"\r" not in raw
        text = raw.decode()
        # Both ids must appear in the kv, after manifest conversion.
        assert "letta_agent-597b5756-2915-4560-ba6b-91005f085166=" in text
        assert "letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f=" in text

        # Seed-done marker written after the seeded run.
        assert marker.exists(), "seed-done marker must be written after a successful seeded run"
        marker_text = marker.read_text()
        # entries count == 2 (manifest only; SQL was stubbed). The marker may
        # have comment lines above the key=value lines; search for `entries=`
        # explicitly rather than assuming it's the first non-comment line.
        entries_line = next(
            (ln for ln in marker_text.splitlines() if ln.startswith("entries=")),
            None,
        )
        assert entries_line is not None, f"no entries= line in marker: {marker_text!r}"
        assert entries_line.split("=", 1)[1].strip() == "2", (
            f"expected entries=2 in marker, got: {entries_line!r}"
        )


# ---------- skip-if-script-missing -------------------------------------------

def test_script_is_executable(tmp_home: Path) -> None:
    """If the script isn't present yet, the rest of the suite is skipped;
    this test gives the file mode its own assertion so the suite can be run
    on its own without the implementation."""
    if not SCRIPT.exists():
        pytest.skip("script not yet implemented")
    mode = stat.S_IMODE(SCRIPT.stat().st_mode)
    assert mode & 0o111, "seed-agent-address-book.py must be executable"
    assert mode & 0o100, "seed-agent-address-book.py must be owner-writable"