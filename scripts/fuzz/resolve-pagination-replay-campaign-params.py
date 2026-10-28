#!/usr/bin/env python3
"""Validate fuzz campaign inputs and emit a shell-safe env file (no untrusted interpolation)."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

MIN_SEED_COUNT = 1
MAX_SEED_COUNT = 256
MIN_SCENARIOS = 1
MAX_SCENARIOS = 1000

DEFAULT_NIGHTLY_SEED_COUNT = 32
DEFAULT_NIGHTLY_SCENARIOS = 200
DEFAULT_PR_SEED_COUNT = 2
DEFAULT_PR_SCENARIOS = 3

NIGHTLY_SEED_BASE = 0x91A80C2B_0001

ENV_FILENAME = "fuzz-params.env"
JSON_FILENAME = "fuzz-params.json"


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def campaign_output_paths() -> tuple[Path, Path]:
    root = repo_root()
    campaign_dir = (root / "campaign").resolve()
    root_resolved = root.resolve()
    if not campaign_dir.is_relative_to(root_resolved):
        raise SystemExit(f"campaign directory escapes repo root: {campaign_dir}")
    if campaign_dir.is_symlink():
        raise SystemExit(f"campaign directory must not be a symlink: {campaign_dir}")
    campaign_dir.mkdir(parents=True, exist_ok=True)
    env_path = (campaign_dir / ENV_FILENAME).resolve()
    json_path = (campaign_dir / JSON_FILENAME).resolve()
    if not env_path.is_relative_to(root_resolved) or not json_path.is_relative_to(root_resolved):
        raise SystemExit("campaign output paths must stay under repo root")
    return env_path, json_path


def _parse_int(name: str, raw: str) -> int:
    try:
        return int(raw, 0)
    except ValueError as exc:
        raise SystemExit(f"invalid {name}: {raw}") from exc


def _bounded(name: str, value: int, low: int, high: int) -> int:
    if value < low or value > high:
        raise SystemExit(f"{name}={value} outside {low}..{high}")
    return value


def scheduled_seed_base(utc_date: str) -> int:
    digest = hashlib.sha256(f"pagination-replay-fuzz:{utc_date}".encode()).hexdigest()
    return int(digest[:15], 16)


@dataclass(frozen=True)
class ManualCampaignInput:
    seed_base: int
    seed_count: int
    scenarios_per_seed: int


@dataclass(frozen=True)
class ResolveRequest:
    kind: str
    utc_date: str | None
    manual: ManualCampaignInput | None


def _require_manual_field(name: str, raw: str | None) -> str:
    if raw is None:
        raise SystemExit("manual kind requires seed_base, seed_count, scenarios_per_seed")
    return raw


def parse_manual_input(
    seed_base_raw: str | None,
    seed_count_raw: str | None,
    scenarios_raw: str | None,
) -> ManualCampaignInput:
    return ManualCampaignInput(
        seed_base=_parse_int("seed_base", _require_manual_field("seed_base", seed_base_raw)),
        seed_count=_bounded(
            "seed_count",
            _parse_int("seed_count", _require_manual_field("seed_count", seed_count_raw)),
            MIN_SEED_COUNT,
            MAX_SEED_COUNT,
        ),
        scenarios_per_seed=_bounded(
            "scenarios_per_seed",
            _parse_int("scenarios_per_seed", _require_manual_field("scenarios_per_seed", scenarios_raw)),
            MIN_SCENARIOS,
            MAX_SCENARIOS,
        ),
    )


def _resolved_values(resolved_kind: str, seed_base: int, seed_count: int, scenarios: int) -> dict[str, str | int]:
    expected_per_family = seed_count * scenarios
    return {
        "FUZZ_CAMPAIGN_KIND": resolved_kind,
        "FUZZ_CAMPAIGN_SEED_BASE": str(seed_base),
        "FUZZ_CAMPAIGN_SEED_COUNT": str(seed_count),
        "FUZZ_CAMPAIGN_SCENARIOS_PER_SEED": str(scenarios),
        "FUZZ_CAMPAIGN_EXPECTED_PER_FAMILY": str(expected_per_family),
        "FUZZ_CAMPAIGN_EXPECTED_TOTAL": str(expected_per_family * 4),
    }


def resolve_pr_smoke() -> dict[str, str | int]:
    return _resolved_values("pr-smoke", NIGHTLY_SEED_BASE, DEFAULT_PR_SEED_COUNT, DEFAULT_PR_SCENARIOS)


def resolve_nightly(utc_date: str | None) -> dict[str, str | int]:
    date = utc_date or datetime.now(timezone.utc).strftime("%Y-%m-%d")
    return _resolved_values(
        "nightly",
        scheduled_seed_base(date),
        DEFAULT_NIGHTLY_SEED_COUNT,
        DEFAULT_NIGHTLY_SCENARIOS,
    )


def resolve_manual(manual: ManualCampaignInput) -> dict[str, str | int]:
    return _resolved_values("manual", manual.seed_base, manual.seed_count, manual.scenarios_per_seed)


def resolve(request: ResolveRequest) -> dict[str, str | int]:
    if request.kind == "pr-smoke":
        return resolve_pr_smoke()
    if request.kind == "nightly":
        return resolve_nightly(request.utc_date)
    if request.kind == "manual":
        if request.manual is None:
            raise SystemExit("manual kind requires validated manual input")
        return resolve_manual(request.manual)
    raise SystemExit(f"unknown kind: {request.kind}")


def write_env(path: Path, values: dict[str, str | int]) -> None:
    lines = [f"{key}={value}" for key, value in values.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kind", required=True, choices=["pr-smoke", "nightly", "manual"])
    parser.add_argument("--seed-base")
    parser.add_argument("--seed-count")
    parser.add_argument("--scenarios-per-seed")
    parser.add_argument("--utc-date")
    args = parser.parse_args()

    manual = None
    if args.kind == "manual":
        manual = parse_manual_input(args.seed_base, args.seed_count, args.scenarios_per_seed)

    request = ResolveRequest(kind=args.kind, utc_date=args.utc_date, manual=manual)
    resolved = resolve(request)
    env_path, json_path = campaign_output_paths()
    write_env(env_path, resolved)
    json_path.write_text(json.dumps(resolved, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
