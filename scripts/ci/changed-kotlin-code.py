#!/usr/bin/env python3
"""Select changed Kotlin files, excluding changes limited to comments/whitespace."""
import argparse
from pathlib import Path
import re
import subprocess


TOKEN = re.compile(r"[\w$]+|===|!==|>>>|<<|>>|==|!=|<=|>=|&&|\|\||\+\+|--|->|::|\?\.|\?:|\.\.<|\.\.|[^\s]", re.UNICODE)


def comment_step(text, position):
    if text.startswith("/*", position):
        return position + 2, 1
    if text.startswith("*/", position):
        return position + 2, -1
    return position + 1, 0


def comment_end(text, position):
    depth = 1
    position += 2
    while position < len(text) and depth:
        position, change = comment_step(text, position)
        depth += change
    if depth:
        raise ValueError("Unterminated Kotlin comment")
    return position


def line_comment_end(text, position):
    end = text.find("\n", position)
    return len(text) if end < 0 else end


def template_step(text, position):
    if text.startswith("/*", position):
        return comment_end(text, position), 0
    if text.startswith("//", position):
        return line_comment_end(text, position), 0
    if text[position] in "\"'`":
        return literal_end(text, position), 0
    return position + 1, (text[position] == "{") - (text[position] == "}")


def template_end(text, position):
    depth = 1
    while position < len(text):
        position, change = template_step(text, position)
        depth += change
        if depth == 0:
            return position
    raise ValueError("Unterminated Kotlin template")


def literal_step(text, position, delimiter):
    if delimiter in ('"', '"""') and text.startswith('${', position):
        return template_end(text, position + 2)
    if delimiter in ('"', "'") and text[position] == "\\":
        return position + 2
    return position + 1


def literal_end(text, position):
    delimiter = '"""' if text.startswith('"""', position) else text[position]
    position += len(delimiter)
    while position < len(text):
        if text.startswith(delimiter, position):
            return position + len(delimiter)
        position = literal_step(text, position, delimiter)
    raise ValueError("Unterminated Kotlin literal")


def lexeme(text, position):
    """Return the next source region's kind and exclusive end offset."""
    if text[position].isspace():
        return "space", position + 1
    if text.startswith("//", position):
        return "line-comment", line_comment_end(text, position)
    if text.startswith("/*", position):
        return "block-comment", comment_end(text, position)
    if text[position] in "\"'`":
        return "code", literal_end(text, position)
    return "code", TOKEN.match(text, position).end()


def separator_after(previous, kind, fragment):
    if kind == "line-comment":
        return previous
    if previous == "\n" or "\n" in fragment:
        return "\n"
    if kind == "space" and fragment == "\r":
        return "\n"
    return " "


def code_tokens(text):
    """Keep literals opaque; malformed input fails closed and stays in scope."""
    tokens, separator, position = [], "", 0
    while position < len(text):
        kind, end = lexeme(text, position)
        fragment, position = text[position:end], end
        if kind != "code":
            separator = separator_after(separator, kind, fragment)
            continue
        if tokens and separator:
            tokens.append(separator)
        tokens.append(fragment)
        separator = ""
    return tokens


def git(root, *arguments, input=None):
    return subprocess.run(["git", *arguments], cwd=root, check=True, capture_output=True, input=input).stdout


def valid_object_id(value):
    return re.fullmatch(rb"[0-9a-f]{40}|[0-9a-f]{64}", value) is not None


def tree_blob(record):
    metadata, name = record.split(b"\t", 1)
    _, kind, object_id = metadata.split()
    if kind != b"blob":
        return {}
    if not valid_object_id(object_id):
        raise ValueError("Invalid tree blob ID")
    return {name.decode("utf-8"): object_id}


def tree_blobs(data):
    """NUL-delimited tree paths may contain tabs or newlines; object IDs cannot."""
    blobs = {}
    for record in filter(None, data.split(b"\0")):
        blobs.update(tree_blob(record))
    return blobs


def batch_size(header, object_id):
    fields = header.split()
    if len(fields) != 3:
        raise ValueError("Unexpected batch blob header")
    if fields[:2] != [object_id, b"blob"] or not fields[2].isdigit():
        raise ValueError("Unexpected batch blob header")
    return int(fields[2])


def batch_blob(output, offset, object_id):
    end = output.find(b"\n", offset)
    if end < 0:
        raise ValueError("Missing batch blob header")
    start = end + 1
    end = start + batch_size(output[offset:end], object_id)
    if output[end:end + 1] != b"\n":
        raise ValueError("Truncated batch blob contents")
    return output[start:end], end + 1


def read_blobs(root, object_ids):
    """Read immutable objects once, with byte counts separating arbitrary source contents."""
    requested = sorted(set(object_ids))
    if not requested:
        return {}
    if not all(map(valid_object_id, requested)):
        raise ValueError("Expected immutable blob IDs")
    output = git(root, "cat-file", "--batch", input=b"\n".join(requested) + b"\n")
    blobs = {}
    offset = 0
    for object_id in requested:
        blobs[object_id], offset = batch_blob(output, offset, object_id)
    if offset != len(output):
        raise ValueError("Unexpected trailing batch output")
    return blobs


def commit_result(root, *arguments):
    commit = git(root, *arguments).strip()
    if not valid_object_id(commit):
        raise ValueError("Expected a full commit ID")
    return commit.decode("ascii")


def changed_names(root, ancestor):
    names = set()
    for arguments in (("diff", "--name-only", "-z", ancestor, "HEAD", "--"),
                      ("diff", "--name-only", "-z", "HEAD", "--"),
                      ("ls-files", "--others", "--exclude-standard", "-z")):
        names.update(git(root, *arguments).decode("utf-8").split("\0"))
    return names


def candidate_source(root, name):
    if not name.startswith("android-compose/") or not name.endswith(".kt"):
        return False
    if name.startswith(("android-compose/drawbox/", "android-compose/quality/detekt-rules/")):
        return False
    path = root / name
    if {"build", "generated"}.intersection(path.parts):
        return False
    return path.is_file()


def code_changed(path, original):
    old = original.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    try:
        return code_tokens(old) != code_tokens(path.read_text(encoding="utf-8-sig"))
    except ValueError:
        return True  # Broken syntax must reach the Kotlin checks.


def changed_files(root, base):
    commit = commit_result(root, "rev-parse", "--verify", "--end-of-options", base + "^{commit}")
    ancestor = commit_result(root, "merge-base", commit, "HEAD")
    candidates = sorted(name for name in changed_names(root, ancestor) if candidate_source(root, name))
    tracked = tree_blobs(git(root, "ls-tree", "-r", "-z", ancestor))
    blobs = read_blobs(root, (tracked[name] for name in candidates if name in tracked))
    for name in candidates:
        if name not in tracked or code_changed(root / name, blobs[tracked[name]]):
            yield name


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main")
    args = parser.parse_args()
    print("\n".join(changed_files(Path(__file__).resolve().parents[2], args.base)))
