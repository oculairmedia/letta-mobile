"""Resolve user-supplied Git refs before using them in gate commands."""

import re
import subprocess
from pathlib import Path

# Branch names, tags, remote refs, SHAs and ~/^ suffixes only: nothing that git could read as an option.
_REF = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/~^+-]{0,199}")
_SHA = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")


def resolve_commit(root: Path, ref: str) -> str:
    """The full commit SHA [ref] names; only that SHA is ever passed on to git afterwards."""
    if not _REF.fullmatch(ref):
        raise ValueError(f"invalid diff base: {ref!r}")
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--verify", "--quiet", "--end-of-options", f"{ref}^{{commit}}"],
        capture_output=True,
        text=True,
    )
    sha = result.stdout.strip()
    if result.returncode or not _SHA.fullmatch(sha):
        raise ValueError(f"diff base does not resolve to a commit: {ref!r}")
    return sha
