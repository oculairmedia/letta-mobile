# node.info: Node Info v1

- Method: `node.info` over the authenticated Iroh `admin_rpc` channel.
- Contract version: 1 (`result.schema: 1`).
- Authorization: any authenticated peer, whatever capabilities it holds (the `peer.authenticated`
  gate, never grantable). A client or a peer node reads it before anything else to judge compatibility.
- Params: a JSON object; no required fields. Unknown fields are ignored.
- Response: an `admin_rpc_response` with `success: true` and object `result`, every field present:
  - `schema` (int): the node.info schema the node writes.
  - `os` (string): the OS family, one of `windows`, `linux`, `macos`, `android`, `ios`, `unknown`.
  - `app_version` (string): the Baken build, or `unknown`.
  - `letta_code_version` (string): the version the node's App Server reported, or `unknown`
    (no App Server, or it has not reported yet).
  - `store_format` (object `{major, minor}`): the agent store layout the node writes.
  - `capabilities` (string array): any of `ui`, `catalog`, `approve`, `execute`, `store`, `always-on`.
    Clients ignore names they do not know.
  - `models` (string array): the model handles `model.list` shows by default; empty without an App Server.
  - `runtime_patches` (object): the letta-code runtime patch set. `source` is `boot_line` when the
    node read the patch loader's `lcp-patches applied=N skipped=M` line from an App Server it
    supervises, else `unknown`, in which case `applied`, `skipped` and `patch_ids_sha256` are null.
    `patch_ids_sha256` is the lowercase hex sha256 of the sorted applied patch ids (the `lcp:` part of
    the runtime parity string `letta-code@<letta_code_version>+lcp:<patch_ids_sha256>`), and is null
    until a node can see the applied patch ids.
- Every free-text value is reduced to a version-like token; no secret, hostname or host path is returned.
- Errors: none specific. A failing App Server makes its fields read `unknown` or empty, never a failure.
- Old peers: a node without this method answers the router's unknown-method error, or `forbidden`
  (it still gated unknown methods as admin-only). Clients treat both as a node that predates node.info:
  unchecked and blocked for nothing.

Compatibility (`NodeCompatibilityRule`, shared code): another `schema` makes the node unavailable;
another `store_format.major`, or (against a reference node) another `letta_code_version` or
runtime patch set, blocks `execute` and `store` while `ui` and `catalog` stay usable. A value
either side reports as `unknown` is not a mismatch.

The fixture is a node configured with nothing and an App Server that rejects every call. Host
conformance uses `AdminRpcRegistry.buildRouter` and the real handler.
