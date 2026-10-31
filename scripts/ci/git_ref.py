"""Resolve user-supplied Git refs before using them in gate commands."""
import subprocess
from pathlib import Path


def resolve_commit(root: Path, ref: str) -> str:
    if ref.startswith("-"):
        raise ValueError(f"invalid diff base: {ref!r}")
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--verify", "--quiet", "--end-of-options", ref + "^{commit}"],
        capture_output=True,
        text=True,
    )
    if result.returncode:
        raise ValueError(f"diff base does not resolve to a commit: {ref!r}")
    return result.stdout.strip()
