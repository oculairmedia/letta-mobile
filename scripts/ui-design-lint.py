#!/usr/bin/env python3
"""Catch UI design-system violations in Compose source. No Gradle, no compile.

Why this exists
---------------
Desktop UI carries ~1000 hand-picked `.dp` literals across ~60 files. Nothing
forces two controls on the same row to agree, so scale, contrast and spacing
drift per file. Patching individual numbers does not converge: the next surface
invents its own. This makes the drift enumerable, so it can be worked through
and then held.

It is deliberately a text/regex pass over Kotlin source rather than a compiler
plugin: the alignment pass edits hundreds of call sites, and waiting on a
Kotlin compile between each batch is the difference between minutes and hours.
`MobileDesignSystemRules` in :quality:detekt-rules enforces the same rules in
CI, where correctness matters more than turnaround.

Rules
-----
raw-dimension        `12.dp` / `14.sp` literal outside the token file.
hardcoded-color      `Color(0xFF...)` instead of a theme role.
low-content-alpha    `.copy(alpha = <0.6)` on a content colour — the
                     barely-visible-control bug.

Usage
-----
    python scripts/ui-design-lint.py                       # human-readable
    python scripts/ui-design-lint.py --format=json         # machine-readable
    python scripts/ui-design-lint.py --rule=raw-dimension  # one rule
    python scripts/ui-design-lint.py --summary             # counts per file
    python scripts/ui-design-lint.py --baseline            # write a baseline
    python scripts/ui-design-lint.py --max=0               # fail on any new hit

Exit code is 1 when findings exceed `--max` (default: the baseline, else
unlimited), so it can gate CI once the alignment pass lands.
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GRADLE_ROOT = REPO_ROOT / "android-compose"
BASELINE_PATH = REPO_ROOT / "config" / "ui-design-lint-baseline.json"

# UI source roots. sharedLogic is excluded on purpose: it holds no Compose UI.
SCAN_ROOTS = [
    "sharedUI/src/commonMain",
    "sharedUI/src/jvmMain",
    "sharedUI/src/androidMain",
    "desktop/src/main",
    "app/src/main",
    "feature-chat/src/main",
    "feature-editagent/src/main",
    "designsystem/src/main",
]

# Files allowed to hold raw values: they are where the tokens are DEFINED.
TOKEN_FILES = (
    "ui/theme/",
    "ui/tokens/",
    "LettaDimens.kt",
    "LettaTokens.kt",
    "Dimens.kt",
    "Type.kt",
    "Theme.kt",
    "Color.kt",
    "Shape.kt",
)

# Spikes, benches and generated code are not product surfaces.
EXCLUDED_PATH_PARTS = ("/build/", "/generated/", "Spike", "Bench", "Preview")

# 0.dp and 1.dp are structural, not scale: "no inset" and "hairline". Forcing
# them through a token buys nothing and would bury the real findings.
ALLOWED_DP = {"0", "1"}

DP_SP_RE = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\.(dp|sp)\b")
COLOR_RE = re.compile(r"\bColor\(\s*0x[0-9A-Fa-f]{6,8}\s*\)")
ALPHA_RE = re.compile(r"\.copy\(\s*alpha\s*=\s*(\d*\.?\d+)f?\s*\)")
ALPHA_FLOOR = 0.6

RULES = ("raw-dimension", "hardcoded-color", "low-content-alpha")


def is_scannable(path: Path) -> bool:
    text = str(path).replace("\\", "/")
    if not text.endswith(".kt"):
        return False
    if any(part in text for part in EXCLUDED_PATH_PARTS):
        return False
    return True


def is_token_file(path: Path) -> bool:
    text = str(path).replace("\\", "/")
    return any(marker in text for marker in TOKEN_FILES)


def strip_noise(line: str) -> str:
    """Blank out line comments and string literals so they cannot match."""
    line = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)
    comment = line.find("//")
    if comment >= 0:
        line = line[:comment]
    return line


def scan_file(path: Path, token_file: bool):
    findings = []
    rel = str(path.relative_to(REPO_ROOT)).replace("\\", "/")
    in_block_comment = False

    for number, raw in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = raw
        # Crude block-comment tracking: enough for KDoc, which is where most
        # of the false positives would otherwise come from.
        if in_block_comment:
            if "*/" in line:
                in_block_comment = False
                line = line.split("*/", 1)[1]
            else:
                continue
        if "/*" in line:
            before, _, after = line.partition("/*")
            if "*/" in after:
                line = before + after.split("*/", 1)[1]
            else:
                in_block_comment = True
                line = before

        line = strip_noise(line)
        if not line.strip():
            continue

        if not token_file:
            for match in DP_SP_RE.finditer(line):
                value, unit = match.group(1), match.group(2)
                if unit == "dp" and value in ALLOWED_DP:
                    continue
                findings.append(
                    {
                        "rule": "raw-dimension",
                        "file": rel,
                        "line": number,
                        "match": match.group(0),
                        "message": (
                            f"`{match.group(0)}` is a raw dimension. Use a design token so "
                            f"controls on the same surface agree."
                        ),
                    }
                )

            for match in COLOR_RE.finditer(line):
                findings.append(
                    {
                        "rule": "hardcoded-color",
                        "file": rel,
                        "line": number,
                        "match": match.group(0),
                        "message": (
                            f"`{match.group(0)}` bypasses the theme. Use a MaterialTheme "
                            f"colour role so light/dark stay correct."
                        ),
                    }
                )

        for match in ALPHA_RE.finditer(line):
            try:
                alpha = float(match.group(1))
            except ValueError:
                continue
            if alpha >= ALPHA_FLOOR:
                continue
            findings.append(
                {
                    "rule": "low-content-alpha",
                    "file": rel,
                    "line": number,
                    "match": match.group(0),
                    "message": (
                        f"alpha {alpha} is below the {ALPHA_FLOOR} floor. Muted content roles "
                        f"(onSurfaceVariant) are already dimmed; halving them again makes a "
                        f"control unreadable on dark themes."
                    ),
                }
            )

    return findings


def collect():
    findings = []
    for root in SCAN_ROOTS:
        base = GRADLE_ROOT / root
        if not base.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in ("build", "generated")]
            for name in filenames:
                path = Path(dirpath) / name
                if not is_scannable(path):
                    continue
                findings.extend(scan_file(path, is_token_file(path)))
    findings.sort(key=lambda f: (f["rule"], f["file"], f["line"]))
    return findings


def load_baseline():
    if not BASELINE_PATH.is_file():
        return None
    try:
        return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("text", "json"), default="text")
    parser.add_argument("--rule", choices=RULES, help="Only this rule.")
    parser.add_argument("--path", help="Only files whose path contains this substring.")
    parser.add_argument("--summary", action="store_true", help="Counts per file, worst first.")
    parser.add_argument("--baseline", action="store_true", help="Write the current counts as the baseline.")
    parser.add_argument("--max", type=int, default=None, help="Fail above this many findings.")
    args = parser.parse_args()

    findings = collect()
    if args.rule:
        findings = [f for f in findings if f["rule"] == args.rule]
    if args.path:
        findings = [f for f in findings if args.path in f["file"]]

    if args.baseline:
        counts = {rule: sum(1 for f in findings if f["rule"] == rule) for rule in RULES}
        BASELINE_PATH.parent.mkdir(parents=True, exist_ok=True)
        BASELINE_PATH.write_text(
            json.dumps({"total": len(findings), "by_rule": counts}, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"Baseline written to {BASELINE_PATH.relative_to(REPO_ROOT)}: {len(findings)} findings")
        return 0

    if args.format == "json":
        print(json.dumps(findings, indent=2))
    elif args.summary:
        per_file = {}
        for finding in findings:
            per_file[finding["file"]] = per_file.get(finding["file"], 0) + 1
        for path, count in sorted(per_file.items(), key=lambda kv: (-kv[1], kv[0])):
            print(f"{count:5d}  {path}")
        print(f"\n{len(findings)} findings across {len(per_file)} files")
        for rule in RULES:
            print(f"  {rule}: {sum(1 for f in findings if f['rule'] == rule)}")
    else:
        for finding in findings:
            print(f"{finding['file']}:{finding['line']}: [{finding['rule']}] {finding['message']}")
        print(f"\n{len(findings)} findings")
        for rule in RULES:
            print(f"  {rule}: {sum(1 for f in findings if f['rule'] == rule)}")

    limit = args.max
    if limit is None:
        baseline = load_baseline()
        limit = baseline.get("total") if baseline else None
    if limit is not None and len(findings) > limit:
        print(f"\nFAIL: {len(findings)} findings exceeds the limit of {limit}.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
