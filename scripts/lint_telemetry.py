from pathlib import Path
import re
import sys


REPO = Path(__file__).resolve().parent.parent
CONVENTIONS = REPO / "docs" / "observability" / "conventions.md"

DOCUMENTED_TAGS = set(re.findall(r"\| `([^`]+)`\s+\|", CONVENTIONS.read_text()))

LITERAL_TAG_RE = re.compile(r'Telemetry\.(?:event|error|startTimer)\(\s*"([^"]+)"')
ERROR_EVENT_RE = re.compile(r"Telemetry\.event\([\s\S]{0,400}?level\s*=\s*Telemetry\.Level\.ERROR")
MANUAL_ERROR_ATTR_RE = re.compile(
    r'Telemetry\.(?:event|measure)\([\s\S]{0,400}?"(?:errorClass|errorMessage)"\s+to'
)


def main() -> int:
    violations: list[str] = []

    for path in REPO.rglob("*.kt"):
        text = path.read_text()
        rel = path.relative_to(REPO)

        if ERROR_EVENT_RE.search(text):
            violations.append(f"{rel}: Telemetry.event(... level = Telemetry.Level.ERROR) found")

        if MANUAL_ERROR_ATTR_RE.search(text):
            violations.append(f"{rel}: hand-rolled errorClass/errorMessage in Telemetry call")

        if path.name != "Telemetry.kt":
            for tag in LITERAL_TAG_RE.findall(text):
                if tag not in DOCUMENTED_TAGS:
                    violations.append(f"{rel}: undocumented telemetry tag '{tag}'")

    if violations:
        print("FAIL lint-telemetry")
        for item in violations:
            print(f" - {item}")
        return 1

    print("PASS lint-telemetry")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
