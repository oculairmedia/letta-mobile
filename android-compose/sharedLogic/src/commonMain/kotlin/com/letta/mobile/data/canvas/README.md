# Meridian Canvas Synchronization & Conflict Resolution Policy

## Architecture Overview
Canvas synchronization in Meridian follows a hybrid operation-based model with element-level Last-Write-Wins (LWW) resolution and agent batching.

DrawBox acts exclusively as a local view/editor in `:sharedUI`. In-memory state, document identity, op logging, and network synchronization are owned by Meridian's `:sharedLogic`.

```
[ DrawBox UI (local) ]
        |
        v (local scene JSON on autosave)
[ CanvasOpDiffer ] -> [ CanvasOp ] -> [ CanvasOpLog (Room on Android, JSONL on Desktop) ]
                                          |
                                    +-----+-----+
                                    |           |
                                    v           v
                            [ CanvasSession ]  [ CanvasSyncTransport (Iroh QUIC live / Loopback) ]
                                    |          [ CanvasPresenceTransport (Iroh QUIC live / InMemory) ]
                            [ CanvasDocumentStore ]
```

## Operation Model
All scene mutations are expressed as typed `CanvasOp` instances carrying:
1. `opId`: Cryptographically random UUID/token for deduplication.
2. `actorId`: Author identifier (`user:<id>` or `agent:<id>`).
3. `lamport`: Logical timestamp incremented monotonically by the originating actor.

### Supported Operations
- `ReplaceSceneOp`: Full scene replacement (primarily used by AI agent diagram generators and baseline initializers).
- `AddElementOp`: Adds a new element with a unique `elementId`.
- `UpdateElementOp`: Updates an existing element identified by `elementId`.
- `RemoveElementOp`: Removes an existing element by `elementId`.
- `SetBackgroundOp`: Updates the canvas background color (`bgColor`).
- `BatchOp`: Atomic bundle of multiple operations applied in a single revision increment.

## Conflict Resolution Rules
1. **Idempotency & Deduplication**:
   - Every operation is recorded in `CanvasOpLog`.
   - Incoming remote operations are checked via `opLog.has(opId)`. If already present, the op is discarded immediately to prevent sync loops.
2. **Element-level Last-Write-Wins (LWW)**:
   - Elements are uniquely keyed by `id`.
   - Concurrent updates to distinct elements merge cleanly without collision.
   - When concurrent edits target the same `elementId`, the operation with the higher `lamport` timestamp wins. If Lamport timestamps match, lexicographical ordering of `actorId` breaks ties deterministically.
   - Sync metadata keys (`_lamport`, `_actorId`) are preserved in session scene JSON for LWW comparison, but are stripped by `CanvasOpDiffer` during autosave diffing to prevent phantom ops, and stripped by `stripMetadataForDrawBox` before importing into DrawBox to ensure strict deserializer compatibility.
3. **Agent Replace Precedence (P2/P3)**:
   - An agent `ReplaceSceneOp` represents a full authoritative diagram generation. It replaces the scene at its logical revision and advances the document version.
4. **Wire Framing & Live Transport (P3.1 Live LAN MVP)**:
   - Canvas synchronization operates over a dedicated Iroh QUIC ALPN (`meridian/canvas-sync/1`).
   - Ephemeral peer presence and cursor telemetry operate over dedicated Iroh QUIC ALPN (`meridian/canvas-presence/1`).
   - Canvas operations and presence are **never** multiplexed onto App Server WebSocket chat frames.
   - **Current Status (P3.1)**:
     - `IrohCanvasSyncTransport`: Full live endpoint lifecycle with accept loop, dial APIs (`connectToPeer`, `connectToPeerByTicket`), length-prefixed binary framing, and historical catch-up via `requestCatchUpSinceLamport`. Fallback to `LoopbackCanvasSyncTransport` when endpoint is absent.
     - `IrohCanvasPresenceTransport`: Full live endpoint lifecycle with accept loop, dial APIs, length-prefixed framing, and 10-second TTL heartbeat with background reaper. Fallback to `InMemoryCanvasPresenceTransport` when endpoint is absent.
     - Multi-process verification runbook available at `CANVAS-P31-IROH-SMOKE.md`.
     - Labeled honestly as LAN / direct QUIC MVP (hard NAT traversal and production relay fleet hardening deferred to P4).

5. **Op Log Durability & Cold Recovery (P3.1)**:
   - **Android**: `RoomCanvasOpLog` backed by Room table `canvas_ops` in `letta_database` (Schema version 16, created via explicit `MIGRATION_15_16`).
   - **Desktop**: `DesktopCanvasOpLog` backed by append-only `.jsonl` files under `~/.letta/canvas/ops/{canvasId_sha256}.jsonl` with thread-safe Mutex and in-memory $O(1)$ op ID index.
   - **Cold Start Model**: `CanvasSession` loads `CanvasDocument.sceneJson` snapshot from `CanvasDocumentStore` for instant rendering. Historical operations are retained in `CanvasOpLog` for deduplication, live fanout, and peer catch-up.

6. **Background Color Semantics (N3)**:
   - `SetBackgroundOp` follows last-apply order in current releases; element-level LWW is strictly enforced on discrete scene elements.

7. **Access Control & Authorization (P4.2)**:
   - `CanvasAcl` defines owners (`ownerUserId`), authorized writers (`writerUserIds`, `writerAgentIds`), and authorized readers (`readerUserIds`, `readerAgentIds`).
   - Identities support raw identifiers as well as `user:` and `agent:` prefixed strings. A prefix pins the principal type: `agent:agent-123` matches the agent entry `agent-123` but never a user entry of the same name, and `user:sam` never matches `writerAgentIds = {"sam"}`. A raw identifier carries no type and matches either.
   - `canWrite`: strictly requires the caller to be the document owner or an explicitly listed writer.
   - **Default Read Behavior**: When both `readerUserIds` and `readerAgentIds` are empty, read access defaults to public (anyone with the canvas ID can read), while write access remains strictly gated by `canWrite`. Once any explicit readers are added, read access is restricted to owners, writers, and explicitly listed readers.
   - **A canvas always has an ACL.** `CanvasSession.create` and `CanvasCreateTool` default to owner `local_user` (the actor the local human edits as) plus the creating agent as a writer when one is known; with no agent the canvas is owner-only. A null ACL is only ever a pre-P4 document and is treated as unrestricted. On Android the Room store refuses to load a row whose ACL column is present but unreadable rather than treating it as absent.
   - **External tools carry the transport identity.** Every `canvas.*` tool takes its caller from the runtime agent scope the App Server stamped on the tool-call frame and refuses a call without one; `agent_id` in the tool input is never consulted, and every `apply_ops` operation (including nested batches) is rebound to the caller before it is validated, logged, broadcast or stamped into provenance. `canvas.create` and `canvas.list` only reveal an existing conversation canvas to a caller its ACL lets read, and agent-scoped listing applies the same read check as a direct lookup.
   - **One canvas per conversation.** `CanvasDocumentStore.createForConversationIfAbsent` makes lookup-and-insert one atomic step (a Room transaction; the desktop store's directory lock; the in-memory mutex), so racing creators — `canvas.create` calls or `CanvasSession.getOrCreateForConversation` — share a canvas.
   - **Writes without a live session are optimistic.** Tool calls dispatch concurrently, so `replace_scene` / `apply_ops` against a canvas no `CanvasSession` holds persist through `CanvasDocumentStore.upsertIfRevision`; a call whose read revision is no longer current gets a `Conflict` error and must re-read, instead of overwriting the other writer under the same revision. The desktop store serialises writes per directory across store instances (an in-process lock keyed by the directory) and across processes (an OS lock on `.store.lock`), so the check holds however many stores point at `~/.letta/canvas`.

8. **In-Session Checkpoint History & Restore (P4.3)**:
   - `CanvasSession` records snapshots (`CanvasCheckpoint`) in a bounded in-memory ring (`MAX_CHECKPOINTS = 30`).
   - The document a session is created or reopened with is its first checkpoint before the session is handed out, so the pre-edit scene is restorable even when a sync collector or a local edit lands before `load()`.
   - `restoreCheckpoint(checkpointId, actorId)` verifies `canWrite` ACL, creates an authoritative `CanvasOp.ReplaceSceneOp`, records a restore checkpoint, advances document revision, and broadcasts the op over `syncTransport`.
   - **Lifecycle Note**: Checkpoints currently live in-session per process lifecycle. Durable multi-session snapshot persistence across process death is deferred to post-P4.

9. **Share-to-Chat Integration (P4.1)**:
   - Canvas exports are packaged via `CanvasShare.createChatImageAttachment` / `CanvasShare.packageForChat` enforcing `AttachmentLimits.maxRawBytesPerImage` (2 MiB limit).
   - If payload exceeds limits, `CanvasAttachmentTooLargeException` is thrown.
   - Sniffs MIME type (prefers raster `image/png` and `image/jpeg` for chat LLM vision capabilities; supports `image/svg+xml`).
   - The `CanvasShare` pending queue is the single delivery state. `stagedAttachmentEvents` only names the recipient that has something waiting; the chat screen answers by draining `consumeStagedAttachments` (and drains once on subscription), so an image is never added twice.
   - The recipient key is the chat screen instance the canvas was opened from (`AdminChatViewModel.canvasShareRecipient`, carried on `CanvasRoute.shareRecipient`), never a conversation id: the queue is process-wide and consuming is destructive, so a retained back-stack screen or one whose conversation has not resolved yet must not be able to take another chat's image.
   - On Android and Desktop, staged attachments are delivered directly into the owning conversation's `ChatComposerController` pending attachments bar.

