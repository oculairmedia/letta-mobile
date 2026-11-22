# message.list: Byte-Bounded Pages v1

- Method: `message.list` over the authenticated Iroh `admin_rpc` channel. Capability: `chat.read`.
- Params: `conversation_id` (required), optional `limit` (default 50), `before`, `after`, `order`, `agent_id`.
  They go to the App Server's `conversation_messages_list` as given. Like the App Server, the host treats
  only `order: "asc"` as oldest first; any other value, or none, is newest first.
- Page: the App Server's page for the request, filtered and projected (tool-return text over 4 KiB becomes a
  2 KiB preview plus `tool_return_pointer` naming `tool_return.get`), then bounded by bytes as well as rows.
  The budget is 900 KiB of serialized rows. The page keeps the rows nearest the request's anchor (its cursor,
  or the newest rows for a descending page and the oldest for an ascending one) and stops before the first
  unit that would cross the budget. A unit is a tool call together with every return of it in the App
  Server's page, and the rows between them; any other row is its own unit.
- Result, page within budget: exactly the App Server page shape (a bare array, or its own object with
  `messages`), unchanged.
- Result, page trimmed: `{"messages": [...], "has_more": true, "next_before": id}` for a descending page and
  `{"messages": [...], "has_more": true, "next_after": id}` for an ascending one. The id is the last row in
  `messages`, the one farthest from the anchor. Continuing with `before` (descending) or `after` (ascending)
  set to the last row received returns the next rows, so no message is skipped or repeated.
- Oversized unit: when the anchor's first unit alone is over the budget, a peer that negotiated `frame_part`
  receives it whole, up to its logical response cap; inline images that would cross a peer's hard cap are
  replaced by `{"type": "image", "unavailable_reason": "too_large"}`, keeping ids and text. If even that does
  not fit, the anchor row is served alone.
- Rows: every row also carries the host turn identity ledger's `logical_message_id` and `turn_id` when the
  ledger knows it (letta-mobile-r1xkl).
- Errors: `forbidden: conversation out of authorized scope`;
  `app_server_error: message.list native command unsuccessful` when the App Server refuses the list (for
  example an unknown conversation); `capability_unavailable: ...` when there is no App Server v2 client.
- Old peers: older hosts keep the newest rows of every page and wrap only with `next_before`. Clients that
  page on from the last row they received are correct against both. `next_after` is additive.

Fixture: `message.list/pages-v1.json`. Its cases run through the real route and the wrapper's turn identity
ledger, over a copy of letta.js 0.34.39 paging (`LettaJsMessagePaging`): an unknown conversation fails, an
unknown cursor is ignored, and the page is cut to `limit`. Three 400 KB image tool returns make each direction
need two pages. Synthetic image rows keep the file small.
