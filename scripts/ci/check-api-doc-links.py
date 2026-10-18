#!/usr/bin/env python3
"""Check tracked Markdown's published API links against a local Dokka build."""
from __future__ import annotations

import argparse
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit

API_URL = "https://oculairmedia.github.io/letta-mobile/api/"
URL_PATTERN = re.compile(re.escape(API_URL.rstrip("/")) + r"(?:/[^\s<>\)\"`]*|(?=[\s<>\)\"`]|$))")


class Anchors(HTMLParser):
    def __init__(self, text: str):
        super().__init__()
        self.values: set[str] = set()
        self.feed(text)

    def handle_starttag(self, tag: str, attrs):
        anchor_attributes = {"id", "name"} if tag == "a" else {"id"}
        for key, value in attrs:
            if key in anchor_attributes and value is not None:
                self.values.add(value)


def published_links(text: str):
    # Code samples do not promise a navigable documentation target.
    text = re.sub(r"^(`{3,}|~{3,}).*?^\1[^\n]*$", "", text, flags=re.M | re.S)
    return list(dict.fromkeys(unescape(url) for url in URL_PATTERN.findall(text)))


def check_link(url: str, api_root: Path) -> str | None:
    parsed = urlsplit(url)
    base = urlsplit(API_URL).path.rstrip("/")
    relative = unquote(parsed.path[len(base):]).lstrip("/")
    root = api_root.resolve()
    target = (root / relative).resolve()
    if not target.is_relative_to(root):
        return "path escapes the generated API directory"
    if target.is_dir():
        target /= "index.html"
    if not target.is_file():
        return "page is absent from generated API documentation"
    if parsed.fragment:
        anchor = unquote(parsed.fragment)
        if anchor not in Anchors(target.read_text(encoding="utf-8")).values:
            return f"anchor #{anchor} is absent from generated page"
    return None


def check_document(repo: Path, relative: str, api_root: Path):
    urls = published_links((repo / relative).read_text(encoding="utf-8"))
    errors = []
    for url in urls:
        if error := check_link(url, api_root):
            errors.append(f"{relative}: {url}: {error}")
    return len(urls), errors


def check_documents(repo: Path, api_root: Path):
    tracked = subprocess.check_output(
        ["git", "-C", str(repo), "ls-files", "-z", "--", "*.md"],
    ).decode("utf-8").split("\0")
    results = [check_document(repo, name, api_root) for name in filter(None, tracked)
               if (repo / name).is_file()]
    return sum(count for count, _ in results), [error for _, errors in results for error in errors]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--api-root", type=Path)
    args = parser.parse_args()
    api_root = args.api_root or args.repo_root / "android-compose/build/dokka/html"
    if not (api_root / "index.html").is_file():
        parser.error(f"generate the API publication first: missing {api_root / 'index.html'}")
    count, errors = check_documents(args.repo_root, api_root)
    if errors:
        print("\n".join(errors))
        return 1
    print(f"API doc links: OK ({count} published links)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
