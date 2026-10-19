#!/usr/bin/env python3
"""Advisory documentation progress from real Dokka diagnostics and page source links."""
import argparse
from html.parser import HTMLParser
import importlib.util
from pathlib import Path
import re
import subprocess
from urllib.parse import quote, unquote, urlsplit

SPEC = importlib.util.spec_from_file_location("ratchet", Path(__file__).with_name("check-api-doc-undocumented.py"))
ratchet = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ratchet)


VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}


class ApiPage(HTMLParser):
    """Only body source-link controls count; navigation URLs are not evidence."""
    def __init__(self):
        super().__init__()
        self.identity = None
        self.source_depth = 0
        self.content_depth = 0
        self.paths = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.track_content(tag, attrs)
        if tag == "span" and self.content_depth:
            self.track_source_span(attrs)
        if tag == "a" and self.source_depth:
            path = source_path(attrs.get("href", ""))
            if path:
                self.paths.add(path)

    def track_content(self, tag, attrs):
        if attrs.get("id") == "content":
            self.identity = attrs.get("pageids")
            self.content_depth = 1
        elif self.content_depth and tag not in VOID_TAGS:
            self.content_depth += 1

    def track_source_span(self, attrs):
        if attrs.get("data-element-type") == "source-link" or self.source_depth:
            self.source_depth += 1

    def handle_endtag(self, tag):
        if tag in VOID_TAGS:
            return
        if tag == "span" and self.source_depth:
            self.source_depth -= 1
        if self.content_depth:
            self.content_depth -= 1
            if not self.content_depth:
                self.source_depth = 0


def changed_paths(root, diff_base):
    resolved = subprocess.run(["git", "rev-parse", "--verify", "--end-of-options", diff_base + "^{commit}"], cwd=root, check=True, capture_output=True, text=True).stdout.strip()
    output = subprocess.run(["git", "diff", "--name-only", "-z", resolved, "HEAD", "--"], cwd=root, check=True, capture_output=True).stdout
    return set(output.decode("utf-8").split("\0")) - {""}


def source_path(href):
    parts = urlsplit(href)
    prefix = "/oculairmedia/letta-mobile/"
    if parts.netloc != "github.com" or not parts.path.startswith(prefix):
        return None
    location = parts.path[len(prefix):].split("/", 2)
    if len(location) == 3 and location[0] in {"tree", "blob"}:
        return unquote(location[2])
    return None


def warnings_by_dri(warnings):
    by_dri = {}
    for warning in warnings:
        module, signature = warning.split(" ", 1)
        dri = re.sub(r" \([^()]*\)$", "", signature)
        by_dri.setdefault(module + "::" + dri, set()).add(warning)
    return by_dri


def contains_source_hint(text, paths):
    return any(path in text or quote(path) in text for path in paths)


def candidate_pages(root, module, changed):
    module_path = "android-compose/" + module.lstrip(":").replace(":", "/")
    relevant = {path for path in changed if path.startswith(module_path + "/")}
    if not relevant:
        return
    directory = root / module_path / "build/dokka-module/html/module"
    if not directory.is_dir():
        raise ValueError(f"Missing generated module pages: {directory}")
    for file in directory.rglob("*.html"):
        text = file.read_text(encoding="utf-8")
        if contains_source_hint(text, relevant):
            page = ApiPage()
            page.feed(text)
            yield page


def page_matches(page, by_dri, changed):
    if not page.identity:
        return set()
    identity, marker, _ = page.identity.partition("PointingToDeclaration/")
    if not marker:
        return set()
    return {(gap, path) for gap in by_dri.get(identity, ()) for path in page.paths & changed}


def page_gaps(root, warnings, changed):
    """Match full DRI before platform identity; grouped pages remain page-level evidence."""
    by_dri = warnings_by_dri(warnings)
    found = set()
    modules = {warning.split(" ", 1)[0] for warning in warnings}
    for module in sorted(modules):
        for page in candidate_pages(root, module, changed):
            found.update(page_matches(page, by_dri, changed))
    return sorted(found)


def progress_rows(current, prior):
    rows = []
    for module in ratchet.MONITORED_MODULES:
        now = {entry for entry in current if entry.startswith(module + " ")}
        before = None if prior is None else {entry for entry in prior if entry.startswith(module + " ")}
        rows.append((module, len(now), None if before is None else len(before), None if before is None else len(before - now)))
    return rows


def render_summary(current, prior, gaps):
    lines = ["## API documentation progress (advisory)", "", "Counts are missing-documentation signature/source-set entries, not coverage percentages.", "", "| Module | Missing now | Missing before | Net change | Resolved or removed (unclassified) |", "| --- | ---: | ---: | ---: | ---: |"]
    for module, now, before, reductions in progress_rows(current, prior):
        delta = "n/a" if before is None else f"{now - before:+d}"
        lines.append(f"| `{module}` | {now} | {before if before is not None else 'initial baseline'} | {delta} | {reductions if reductions is not None else 'n/a'} |")
    lines += ["", f"### Existing gaps on API pages linking changed files ({len(gaps)})", "", "Page-level association only: this does not claim the undocumented declaration itself changed. No additional merge gate.", ""]
    for gap, path in gaps[:20]:
        # Keep repository-controlled identifiers from altering Markdown structure.
        safe_gap = gap.replace("`", "&#96;").replace("|", "&#124;")
        safe_path = path.replace("`", "&#96;")
        lines.append(f"- `{safe_gap}` â€” `{safe_path}`")
    if len(gaps) > 20:
        lines.append(f"\nShowing 20 of {len(gaps)}; full list is in the Dokka diagnostics artifact.")
    if not gaps:
        lines.append("No matching existing gap pages found for changed files.")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--diff-base", required=True)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--details", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    text = args.log.read_text(encoding="utf-8-sig")
    ratchet.require_fresh_generation(text)
    current = ratchet.warnings_from_log(text)
    prior = ratchet.previous_baseline(root, root / ratchet.BASELINE, args.diff_base)
    existing = current if prior is None else current & prior
    gaps = page_gaps(root, existing, changed_paths(root, args.diff_base))
    with args.summary.open("a", encoding="utf-8") as output:
        output.write(render_summary(current, prior, gaps))
    args.details.write_text("".join(f"{gap}\t{path}\n" for gap, path in gaps), encoding="utf-8")


if __name__ == "__main__":
    main()
