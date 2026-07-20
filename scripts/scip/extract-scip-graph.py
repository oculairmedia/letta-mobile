#!/usr/bin/env python3
"""Extract a bounded, advisory JSONL graph seam from a SCIP index."""

import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

SCHEMA = "letta.architecture.graph.v1"


def emit(handle, record, state):
    if state[0] >= state[1]:
        raise RuntimeError(f"record limit exceeded ({state[1]})")
    handle.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
    state[0] += 1


def first(value, *names, default=None):
    for name in names:
        if name in value:
            return value[name]
    return default


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("index", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--max-records", type=int, default=20000)
    parser.add_argument("--scip-command", default="scip")
    args = parser.parse_args()
    if args.max_records <= 0:
        parser.error("--max-records must be positive")
    if not args.index.is_file():
        parser.error(f"index does not exist: {args.index}")

    try:
        result = subprocess.run(
            [args.scip_command, "print", "--json", str(args.index)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except FileNotFoundError:
        print("extract-scip-graph: scip CLI is required on PATH", file=sys.stderr)
        return 69
    except subprocess.CalledProcessError as error:
        print(error.stderr.rstrip(), file=sys.stderr)
        return error.returncode

    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        print(f"extract-scip-graph: invalid scip JSON: {error}", file=sys.stderr)
        return 65
    if not isinstance(payload, dict):
        print("extract-scip-graph: scip JSON root must be an object", file=sys.stderr)
        return 65
    documents = first(payload, "documents", default=[])
    if not isinstance(documents, list):
        print("extract-scip-graph: scip JSON documents must be an array", file=sys.stderr)
        return 65
    args.output.parent.mkdir(parents=True, exist_ok=True)
    state = [0, args.max_records]
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=args.output.parent,
            prefix=f".{args.output.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temporary = pathlib.Path(handle.name)
            emit(handle, {"schema": SCHEMA, "type": "meta", "source": "scip-java", "advisory": True}, state)
            declared = set()
            for document in documents:
                path = first(document, "relativePath", "relative_path", default="")
                file_id = f"file:{path}"
                emit(handle, {"schema": SCHEMA, "type": "node", "id": file_id, "kind": "file", "path": path}, state)
                for symbol in first(document, "symbols", default=[]):
                    symbol_id = first(symbol, "symbol", default="")
                    if symbol_id and symbol_id not in declared:
                        emit(handle, {"schema": SCHEMA, "type": "node", "id": symbol_id, "kind": "symbol", "display": first(symbol, "displayName", "display_name", default=symbol_id)}, state)
                        emit(handle, {"schema": SCHEMA, "type": "edge", "from": file_id, "to": symbol_id, "kind": "declares"}, state)
                        declared.add(symbol_id)
                for occurrence in first(document, "occurrences", default=[]):
                    symbol_id = first(occurrence, "symbol", default="")
                    if not symbol_id:
                        continue
                    roles = int(first(occurrence, "symbolRoles", "symbol_roles", default=0))
                    if roles & 1:
                        continue
                    emit(handle, {"schema": SCHEMA, "type": "edge", "from": file_id, "to": symbol_id, "kind": "references", "range": first(occurrence, "range", default=[])}, state)
        temporary.replace(args.output)
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        print(f"extract-scip-graph: {error}", file=sys.stderr)
        return 75

    print(f"extract-scip-graph: wrote {state[0]} advisory records to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
