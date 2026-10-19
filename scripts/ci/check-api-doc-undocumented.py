#!/usr/bin/env python3
"""Ratchet actual Dokka Undocumented signatures; no lexical KDoc estimation.

Capture a successful Dokka module build with --console=plain and tee its output.
The documentation plugin forces monitored module doc tasks to execute, because cached
HTML does not replay diagnostics. Bootstrap a missing baseline with --write-baseline;
normal CI checks both warning parity and that the baseline only shrinks against --diff-base.
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys

MONITORED_MODULES = (":core:runtime", ":plugin-api", ":sharedLogic")
BASELINE = "scripts/ci/api-doc-undocumented-baseline.txt"
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
DIAGNOSTIC = re.compile(r"\[(:[^] ]+):dokkaGenerateModuleHtml\]\s+Undocumented:\s*(.*)")
HEADER = "# Dokka Undocumented signatures (including overload and source-set identity).\n"


def warnings_from_log(text):
    """Retain Dokka's complete diagnostic identity, including callable signatures."""
    signatures = set()
    for line in ANSI.sub("", text).splitlines():
        if "Undocumented:" not in line:
            continue
        match = DIAGNOSTIC.search(line)
        if not match or match[1] not in MONITORED_MODULES:
            raise ValueError(f"Unrecognized or unscoped Dokka diagnostic: {line}")
        signature = match[2].strip()
        if not signature or "/" not in signature:
            raise ValueError(f"Unrecognized Dokka undocumented diagnostic: {line}")
        signatures.add(match[1] + " " + signature)
    return signatures


def require_successful_build(text):
    if not re.search(r"(?m)^BUILD SUCCESSFUL\b", text):
        raise ValueError("Expected the complete output of a successful Dokka build")
    if re.search(r"(?m)^BUILD FAILED\b", text):
        raise ValueError("Expected the complete output of a successful Dokka build")


def require_fresh_module(text, module):
    task = re.escape(module + ":dokkaGenerateModuleHtml")
    records = re.findall(rf"(?m)^> Task {task}([^\r\n]*)$", text)
    if not records:
        raise ValueError(f"{module}:dokkaGenerateModuleHtml must execute, not be cached or skipped")
    if any(record.strip() for record in records):
        raise ValueError(f"{module}:dokkaGenerateModuleHtml must execute, not be cached or skipped")


def require_fresh_generation(text):
    """An empty or cached log is not evidence that the API has no KDoc gaps."""
    text = ANSI.sub("", text)
    require_successful_build(text)
    for module in MONITORED_MODULES:
        require_fresh_module(text, module)


def read_baseline(text):
    signatures = [line for line in text.splitlines() if line and not line.startswith("#")]
    require_canonical_baseline(signatures)
    return set(signatures)


def require_canonical_baseline(signatures):
    if signatures != sorted(set(signatures)):
        raise ValueError("Undocumented baseline must contain sorted, unique Dokka signatures")
    for signature in signatures:
        require_scoped_signature(signature)


def require_scoped_signature(signature):
    """Keep module ownership and Dokka identity explicit in every stored entry."""
    if signature != signature.strip():
        raise ValueError("Baseline signatures must not have surrounding whitespace")
    if "/" not in signature or signature.split(" ", 1)[0] not in MONITORED_MODULES:
        raise ValueError("Baseline entries must name a monitored module and a Dokka signature")


def render_baseline(signatures):
    return HEADER + "".join(signature + "\n" for signature in sorted(signatures))


def compare(warnings, baseline):
    errors = []
    for label, values in (("New undocumented APIs", warnings - baseline), ("Stale baseline entries (remove them)", baseline - warnings)):
        if values:
            errors.append(f"{label} ({len(values)}):\n" + "\n".join("  " + value for value in sorted(values)))
    return errors


def resolve_commit(root, diff_base):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./~^\-]*", diff_base):
        raise ValueError("Comparison ref must be a plain Git ref or revision")
    resolved = subprocess.run(["git", "rev-parse", "--verify", "--end-of-options", diff_base + "^{commit}"], cwd=root, capture_output=True, text=True, encoding="utf-8")
    if resolved.returncode:
        raise ValueError(f"Cannot resolve baseline comparison ref {diff_base}")
    commit = resolved.stdout.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", commit):
        raise ValueError("Git comparison ref did not resolve to a full object ID")
    return commit


def repository_path(path, root):
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise ValueError("Documentation input/output path must stay within the repository")
    return resolved


def previous_baseline(root, baseline, diff_base):
    relative = baseline.resolve().relative_to(root.resolve()).as_posix()
    commit = resolve_commit(root, diff_base)
    tree = subprocess.run(["git", "ls-tree", "--name-only", commit, "--", relative], cwd=root, capture_output=True, text=True, encoding="utf-8", check=True)
    if not tree.stdout.strip():
        return None  # First introduction is explicit; subsequent versions may only shrink.
    prior = subprocess.run(["git", "show", f"{commit}:{relative}"], cwd=root, capture_output=True, text=True, encoding="utf-8", check=True)
    return read_baseline(prior.stdout)


def parse_arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="Complete plain-console output from the Dokka module build")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--baseline", default=BASELINE, help="Repository-relative warning baseline")
    parser.add_argument("--diff-base", default="origin/main", help="Baseline entries may not grow relative to this commit")
    parser.add_argument("--write-baseline", action="store_true", help="Write actual signatures; this does not authorize baseline growth in CI")
    return parser.parse_args(argv)


def baseline_growth_errors(accepted, prior):
    if prior is None:
        return []
    added = accepted - prior
    if not added:
        return []
    return ["Undocumented baseline grew (new APIs need KDoc):\n" + "\n".join("  " + value for value in sorted(added))]


def verify_baseline(warnings, baseline, root, diff_base):
    accepted = read_baseline(baseline.read_text(encoding="utf-8"))
    prior = previous_baseline(root, baseline, diff_base)
    errors = compare(warnings, accepted) + baseline_growth_errors(accepted, prior)
    if errors:
        raise ValueError("\n".join(errors))
    status = "initial baseline" if prior is None else "no growth"
    counts = ", ".join(f"{module}={sum(item.startswith(module + ' ') for item in accepted)}" for module in MONITORED_MODULES)
    print(f"API KDoc ratchet: OK ({counts}; {status})")


def write_baseline(warnings, baseline):
    baseline.parent.mkdir(parents=True, exist_ok=True)
    baseline.write_text(render_baseline(warnings), encoding="utf-8", newline="\n")
    print(f"Wrote {len(warnings)} actual Dokka signatures to {baseline}")


def execute(args):
    text = repository_path(args.log, args.root).read_text(encoding="utf-8-sig")
    require_fresh_generation(text)
    warnings = warnings_from_log(text)
    runtime_gaps = sorted(item for item in warnings if item.startswith(":core:runtime "))
    if runtime_gaps:
        raise ValueError("core:runtime must be fully documented; no baseline allowed:\n" + "\n".join(runtime_gaps))
    baseline = repository_path(args.root / args.baseline, args.root)
    if args.write_baseline:
        write_baseline(warnings, baseline)
    else:
        verify_baseline(warnings, baseline, args.root, args.diff_base)
    return 0


def main(argv=None):
    args = parse_arguments(argv)
    try:
        return execute(args)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
