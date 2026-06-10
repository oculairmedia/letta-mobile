#!/usr/bin/env python3
"""
push-design-tokens-to-penpot.py — push DTCG tokens into Penpot's native catalog.

Reads docs/design/penpot-tokens.json (produced by export-design-tokens.py) and
writes it into the `letta mobile` Penpot file's native token catalog
(design-tokens/v1) via the authed REST `update-file` change-ops:
  :set-token-set  {:id, :attrs {:name}}
  :set-token      {:set-id, :token-id, :attrs {:name, :type, :value, :description}}

Auth: access token from Vaultwarden item
  "Penpot API — prototype.oculair.ca (access token)" (field access_token).
The Penpot box is only reachable from the dockge host (192.168.50.80), so every
RPC is issued via SSH + curl on that host (same pattern as the sync doc).

Token :type uses Penpot's INTERNAL keyword names (not DTCG strings):
  color, spacing, dimensions, border-radius, font-size, font-weight,
  font-family, letter-spacing, line-height(->dimensions), opacity, number,
  typography (composite). See common/types/token.cljc token-type->dtcg-token-type.

Transit+json encoding: keywords -> "~:kw", uuids -> "~uXXXX", maps as
  ["^ ", k1, v1, k2, v2, ...]. We hand-encode the small change-op payloads.

Usage:
  python3 scripts/push-design-tokens-to-penpot.py --only color/default   # one set
  python3 scripts/push-design-tokens-to-penpot.py                        # all sets
  python3 scripts/push-design-tokens-to-penpot.py --verify-only          # read back
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
TOKENS_JSON = REPO / "docs/design/penpot-tokens.json"
# Default target = the "letta mobile" file; override with --file-id.
DEFAULT_FILE_ID = "46e68d89-bd4b-8008-8007-fa0da54a88af"
HOST = os.environ.get("PENPOT_SSH_HOST", "192.168.50.80")
# SSH password for the Penpot host. NEVER hardcode — repo is public.
# Set PENPOT_SSH_PASSWORD, or rely on ssh-agent/key auth (preferred; leave unset).
HOST_PW = os.environ.get("PENPOT_SSH_PASSWORD", "")
RPC = "http://localhost:9001/api/rpc/command"

# DTCG $type -> Penpot internal token :type keyword
DTCG_TO_PENPOT = {
    "color": "color",
    "dimension": "dimensions",
    "spacing": "spacing",
    "borderRadius": "border-radius",
    "fontSize": "font-size",
    "fontWeight": "font-weight",
    "fontFamily": "font-family",
    "letterSpacing": "letter-spacing",
    "opacity": "opacity",
    "number": "number",
    "typography": "typography",
}


def vault_token() -> str:
    cached = Path("/tmp/.pp_tok")
    if cached.exists():
        t = cached.read_text().strip()
        if t:
            return t
    sess = subprocess.run(["vw-unlock"], capture_output=True, text=True).stdout.strip()
    out = subprocess.run(
        ["bw", "get", "item", "Penpot API — prototype.oculair.ca (access token)"],
        capture_output=True, text=True, env={"BW_SESSION": sess, "PATH": "/usr/local/bin:/usr/bin:/bin"},
    ).stdout
    item = json.loads(out)
    for f in item.get("fields", []):
        if f.get("name") == "access_token":
            return f["value"]
    raise SystemExit("no access_token in vault item")


def rpc(token: str, command: str, payload: str) -> tuple[int, str]:
    """Issue an RPC via ssh+curl on the Penpot host. payload is a raw JSON string."""
    # Write payload to a temp file on the remote to avoid shell-escaping hell.
    remote_cmd = (
        f"cat > /tmp/_pp_payload.json <<'PPEOF'\n{payload}\nPPEOF\n"
        f"curl -s -m 30 -X POST {RPC}/{command} "
        f"-H 'Authorization: Token {token}' "
        f"-H 'Content-Type: application/transit+json' "
        f"-H 'Accept: application/transit+json' "
        f"--data-binary @/tmp/_pp_payload.json -w '\\n__HTTP__%{{http_code}}'"
    )
    ssh_cmd = ["ssh", "-o", "StrictHostKeyChecking=no",
               "-o", "ConnectTimeout=10", f"root@{HOST}", remote_cmd]
    if HOST_PW:
        ssh_cmd = ["sshpass", "-p", HOST_PW] + ssh_cmd
    res = subprocess.run(ssh_cmd, capture_output=True, text=True)
    body = res.stdout
    m = re.search(r"__HTTP__(\d+)\s*$", body)
    code = int(m.group(1)) if m else 0
    body = re.sub(r"__HTTP__\d+\s*$", "", body)
    return code, body


# ── transit encoding helpers ─────────────────────────────────────────────

def t_map(*pairs) -> list:
    """Build a transit map ['^ ', k, v, ...] from (key, value) pairs."""
    out = ["^ "]
    for k, v in pairs:
        out.append(k)
        out.append(v)
    return out


def kw(name: str) -> str:
    return f"~:{name}"


def u(val: str) -> str:
    return f"~u{val}"


def get_file_payload(file_id: str) -> str:
    return json.dumps(t_map((kw("id"), u(file_id))), ensure_ascii=False)


def fetch_file(token: str, file_id: str) -> str:
    code, body = rpc(token, "get-file", get_file_payload(file_id))
    if code != 200:
        raise SystemExit(f"get-file failed http={code}: {body[:200]}")
    return body


def read_revn_vern(body: str) -> tuple[int, int]:
    m = re.search(r'"~:revn",(\d+)', body)
    if not m:
        raise SystemExit("could not read revn")
    vm = re.search(r'"~:vern",(\d+)', body)
    return int(m.group(1)), (int(vm.group(1)) if vm else 0)


def read_features(body: str) -> list[str]:
    """Extract the file's declared feature set so update-file can echo it."""
    m = re.search(r'"~:features","~#set",\[(.*?)\]', body)
    if not m:
        # transit may render features as a tagged set elsewhere; fall back to a
        # broad superset known to exist on token-enabled files.
        return ["fdata/path-data", "plugins/runtime", "design-tokens/v1",
                "variants/v1", "layout/grid", "styles/v2", "fdata/objects-map",
                "components/v2", "fdata/shape-data-type"]
    return re.findall(r'"([^"]+)"', m.group(1))


def read_existing_ids(body: str) -> tuple[dict, dict]:
    """
    Parse the tokens-lib to map existing set-name -> set-id and
    (set-name, token-name) -> token-id, so re-runs UPDATE in place
    (true idempotency) instead of relying on server-side name-merge.

    The transit payload uses cache refs (^=, ^A, etc.) so we locate each
    set/token by its literal name string and grab the nearest preceding ~u id.
    """
    set_ids: dict[str, str] = {}
    token_ids: dict[tuple[str, str], str] = {}

    # Each set serializes (after transit cache-refs) as roughly:
    #   ...,"~u<set-id>","<cacheref-or-~:name>","<set-name>",...
    # Match a uuid immediately followed by a quoted ref/key then a set-path name.
    for m in re.finditer(
            r'"~u([0-9a-f-]{36})","[^"]{1,6}","((?:color|spacing|typography)/[A-Za-z0-9]+)"',
            body):
        set_ids.setdefault(m.group(2), m.group(1))

    # Tokens: a uuid followed by a ref/key then a token leaf name (e.g. light.primary)
    # then eventually a value. We can't always bind to a set here, so key by ("*", name).
    for m in re.finditer(
            r'"~u([0-9a-f-]{36})","[^"]{1,6}","([A-Za-z][\w.]*)","[^"]{1,6}"',
            body):
        name = m.group(2)
        if "/" in name:  # that's a set, not a token leaf
            continue
        token_ids.setdefault(("*", name), m.group(1))
    return set_ids, token_ids


def count_tokens(token: str, file_id: str) -> dict:
    body = fetch_file(token, file_id)
    set_ids, token_ids = read_existing_ids(body)
    return {
        "http": 200,
        "revn": read_revn_vern(body)[0],
        "token-sets": len(set_ids),
        "set-names": sorted(set_ids.keys()),
        "tokens-counted": body.count('"~:value"'),
    }


def dtcg_type_of(token_obj: dict) -> str:
    dtcg = token_obj.get("$type", "")
    return DTCG_TO_PENPOT.get(dtcg, dtcg)


def flatten_set(set_name: str, node: dict, prefix="") -> list[tuple[str, str, object]]:
    """Yield (token_name, penpot_type, value) for every leaf token in a DTCG group."""
    leaves = []
    for key, val in node.items():
        if key.startswith("$"):
            continue
        if isinstance(val, dict) and "$type" in val:
            name = f"{prefix}{key}" if prefix else key
            leaves.append((name, dtcg_type_of(val), val["$value"]))
        elif isinstance(val, dict):
            sub = f"{prefix}{key}." if prefix else f"{key}."
            leaves.extend(flatten_set(set_name, val, sub))
    return leaves


def build_changes_for_set(set_name: str, node: dict,
                          existing_set_ids: dict, existing_token_ids: dict) -> list:
    """
    Build [set-token-set change, *set-token changes] for one DTCG token set.

    IDEMPOTENT: if the set (or a token within it) already exists, reuse its id
    so the change UPDATES in place instead of creating a duplicate. New entities
    get a fresh uuid.
    """
    set_id = existing_set_ids.get(set_name) or str(uuid.uuid4())
    changes = []
    # :set-token-set — attrs must carry :id + :name (schema:token-set-attrs)
    changes.append(t_map(
        (kw("type"), kw("set-token-set")),
        (kw("id"), u(set_id)),
        (kw("attrs"), t_map(
            (kw("id"), u(set_id)),
            (kw("name"), set_name),
        )),
    ))
    # one :set-token per leaf — attrs must carry :id + :name + :type + :value
    for tname, ttype, tvalue in flatten_set(set_name, node):
        token_id = (existing_token_ids.get((set_name, tname))
                    or existing_token_ids.get(("*", tname))
                    or str(uuid.uuid4()))
        if isinstance(tvalue, dict):
            tv = t_map(*[(kw(k), v) for k, v in tvalue.items()])
        else:
            tv = tvalue
        changes.append(t_map(
            (kw("type"), kw("set-token")),
            (kw("set-id"), u(set_id)),
            (kw("token-id"), u(token_id)),
            (kw("attrs"), t_map(
                (kw("id"), u(token_id)),
                (kw("name"), tname),
                (kw("type"), kw(ttype)),
                (kw("value"), tv),
            )),
        ))
    return changes


def push_set(token: str, file_id: str, features: list[str], set_name: str,
             node: dict, revn: int, vern: int,
             existing_set_ids: dict, existing_token_ids: dict) -> tuple[int, str, int]:
    changes = build_changes_for_set(set_name, node, existing_set_ids, existing_token_ids)
    session_id = str(uuid.uuid4())
    envelope = t_map(
        (kw("id"), u(file_id)),
        (kw("session-id"), u(session_id)),
        (kw("revn"), revn),
        (kw("vern"), vern),
        (kw("features"), ["~#set", list(features)]),
        (kw("changes"), changes),
    )
    payload = json.dumps(envelope, ensure_ascii=False)
    code, body = rpc(token, "update-file", payload)
    new_revn = revn
    m = re.search(r'"~:revn",(\d+)', body)
    if m:
        new_revn = int(m.group(1))
    return code, body, new_revn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="push just this set (e.g. color/default)")
    ap.add_argument("--verify-only", action="store_true")
    ap.add_argument("--file-id", default=DEFAULT_FILE_ID,
                    help="Penpot file id to push into (default: the 'letta mobile' file)")
    args = ap.parse_args()

    token = vault_token()
    file_id = args.file_id

    if args.verify_only:
        print(json.dumps(count_tokens(token, file_id), indent=2))
        return 0

    doc = json.loads(TOKENS_JSON.read_text())
    sets = {k: v for k, v in doc.items() if not k.startswith("$")}
    if args.only:
        if args.only not in sets:
            print(f"no such set: {args.only}. available: {list(sets)}", file=sys.stderr)
            return 1
        sets = {args.only: sets[args.only]}

    # One read up-front: revn/vern, dynamic feature set, and existing ids for
    # idempotent updates.
    body = fetch_file(token, file_id)
    revn, vern = read_revn_vern(body)
    features = read_features(body)
    existing_set_ids, existing_token_ids = read_existing_ids(body)
    print(f"target file = {file_id}")
    print(f"starting revn = {revn}, vern = {vern}")
    print(f"features ({len(features)}): {', '.join(features)}")
    print(f"existing sets: {sorted(existing_set_ids.keys()) or '(none)'}")

    for set_name, node in sets.items():
        code, body, revn = push_set(token, file_id, features, set_name, node,
                                    revn, vern, existing_set_ids, existing_token_ids)
        reused = "update" if set_name in existing_set_ids else "create"
        ok = "OK" if code == 200 else "FAIL"
        print(f"[{ok} http={code}] {reused} '{set_name}' -> revn {revn}")
        if code != 200:
            print(f"   body: {body[:240]}")
            print("   stopping on first failure for inspection.")
            return 1

    print("\nverify:")
    print(json.dumps(count_tokens(token, file_id), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
