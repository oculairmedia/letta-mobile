# Meridian Canvas Synchronization & Conflict Resolution Policy

## Architecture Overview
Canvas synchronization in Meridian follows a hybrid operation-based model with element-level Last-Write-Wins (LWW) resolution and agent batching.

DrawBox acts exclusively as a local view/editor in `:sharedUI`. In-memory state, document identity, op logging, and network synchronization are owned by Meridian's `:sharedLogic`.

```
[ DrawBox UI (local) ]
        |
        v (local scene JSON on autosave)
[ CanvasOpDiffer ] -> [ CanvasOp ] -> [ CanvasOpLog ]
                                          |
                                    +-----+-----+
                                    |           |
                                    v           v
                            [ CanvasSession ]  [ CanvasSyncTransport (Iroh QUIC) ]
                                    |
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
3. **Agent Replace Precedence (P2/P3)**:
   - An agent `ReplaceSceneOp` represents a full authoritative diagram generation. It replaces the scene at its logical revision and advances the document version.
4. **Wire Framing Isolation**:
   - Canvas synchronization operates over a dedicated Iroh QUIC ALPN (`meridian/canvas-sync/1`) or loopback memory transport.
   - Canvas operations are **never** multiplexed onto App Server WebSocket chat frames.
