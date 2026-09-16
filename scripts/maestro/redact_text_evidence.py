#!/usr/bin/env python3
"""Redact known credential shapes from local Maestro text evidence."""

import os
import re
import sys
from pathlib import Path


SECRET_ENV_KEYS = (
    "AUTOMATION_ACCESS_TOKEN",
    "AUTOMATION_SERVER_URL",
    "AUTOMATION_GATEWAY_API_KEY",
    "LETTA_DESKTOP_SERVER_URL",
)
PATTERNS = (
    re.compile(r"(?i)(authorization\s*:\s*bearer\s+)[^\s,;]+"),
    re.compile(
        r'''(?ix)
        ("?(?:access[_-]?token|api[_-]?key|server[_-]?url|ticket)"?\s*[:=]\s*)
        ("[^"]*"|'[^']*'|[^\s,;]+)
        ''',
    ),
    re.compile(r"(?i)\b(?:iroh|wss?|https?)://[^\s\"'<>]+"),
)


def redact(text: str) -> str:
    for key in SECRET_ENV_KEYS:
        value = os.environ.get(key, "")
        if value:
            text = text.replace(value, "[REDACTED]")
    text = PATTERNS[0].sub(r"\1[REDACTED]", text)
    text = PATTERNS[1].sub(r"\1[REDACTED]", text)
    return PATTERNS[2].sub("[REDACTED_URL]", text)


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: redact_text_evidence.py FILE...")
    for raw_path in sys.argv[1:]:
        path = Path(raw_path)
        if not path.is_file():
            continue
        path.write_text(redact(path.read_text(encoding="utf-8", errors="replace")), encoding="utf-8")


if __name__ == "__main__":
    main()
