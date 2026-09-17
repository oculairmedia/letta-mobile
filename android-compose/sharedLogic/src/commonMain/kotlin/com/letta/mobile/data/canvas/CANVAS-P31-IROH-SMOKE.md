# Meridian Canvas Workspace: Phase P3.1 Live Iroh Sync Smoke Runbook

This runbook documents the multi-process live synchronization and peer presence verification for Phase P3.1 of the Meridian Canvas Workspace program.

---

## 1. Architecture Summary

Phase P3.1 promotes canvas collaboration from in-process loopback to live multi-device QUIC networking:
- **Operation Sync Transport**: `IrohCanvasSyncTransport` over dedicated ALPN `meridian/canvas-sync/1`.
- **Peer Presence Transport**: `IrohCanvasPresenceTransport` over dedicated ALPN `meridian/canvas-presence/1`.
- **Durable Operation Logs**:
  - Android: SQLite/Room table `canvas_ops` in `letta_database` (Schema v16).
  - Desktop: Append-only JSONL files in `~/.letta/canvas/ops/{canvasId_sha256}.jsonl`.
- **Document Snapshot Store**: `CanvasDocumentStore` (`canvas_documents` in Room / `~/.letta/canvas/{id}.json`).
- **Wire Isolation**: App Server chat WebSocket framing is completely untouched; canvas sync operates on dedicated bi-directional QUIC streams.

---

## 2. Pre-Requisites

1. Two running processes:
   - **Process A**: Desktop app (`MERIDIAN_CANVAS_DEBUG=1 ./gradlew run`) or Android emulator/device.
   - **Process B**: Second desktop instance or Android companion device on the same LAN or reachable via Iroh relay.
2. Iroh endpoints initialized with both ALPNs registered:
   - `meridian/canvas-sync/1`
   - `meridian/canvas-presence/1`

---

## 3. Two-Process Smoke Test Execution

### Step 1: Retrieve Node Ticket on Process A
From Process A (the hosting or listener peer), obtain the local endpoint ticket:
```kotlin
val ticket = EndpointTicket.fromAddr(endpoint.addr()).toString()
println("Process A Ticket: $ticket")
```

### Step 2: Connect Process B to Process A
From Process B, connect to Process A's ticket:
```kotlin
val syncTransport = IrohCanvasSyncTransport(scope, endpoint, opLog)
val presenceTransport = IrohCanvasPresenceTransport(scope, endpoint)

syncTransport.connectToPeerByTicket(ticket)
presenceTransport.connectToPeerByTicket(ticket)
```
Verify telemetry events emitted:
- `CanvasSync: dial.start`
- `CanvasSync: dial.connected`
- `CanvasPresence: dial.start`
- `CanvasPresence: dial.connected`
On Process A:
- `CanvasSync: incoming.connected`
- `CanvasPresence: incoming.connected`

### Step 3: Open the Same Canvas on Both Peers
Initialize a `CanvasSession` for `canvasId = "smoke-test-shared-1"` on both Process A and Process B:
```kotlin
val sessionA = CanvasSession(
    canvasId = CanvasId("smoke-test-shared-1"),
    store = storeA,
    opLog = opLogA,
    syncTransport = syncTransportA,
)
sessionA.load()

val sessionB = CanvasSession(
    canvasId = CanvasId("smoke-test-shared-1"),
    store = storeB,
    opLog = opLogB,
    syncTransport = syncTransportB,
)
sessionB.load()
```

### Step 4: Live Mutation Propagation
1. On Process A, perform a canvas edit (e.g. DrawBox stroke, add rectangle, or set background color).
2. Autosave debounces and computes delta operations using `CanvasOpDiffer`.
3. `sessionA.applyLocalScene(...)` appends the op to `opLogA` and calls `syncTransportA.publish(canvasId, op)`.
4. `IrohCanvasSyncTransport` serializes `CanvasOpWirePacket` with 4-byte length-prefix framing and transmits across QUIC bi-stream.
5. Process B reads the frame, emits the op to `sessionB`, projects the op via `CanvasOpProjector`, and updates the UI canvas.
6. Verify the visual change is rendered on Process B within 50-100ms.

### Step 5: Live Peer Cursor & Presence
1. Move the pointer over the canvas in Process B.
2. `presenceTransportB.updatePresence(canvasId, CanvasPresence(peerId = "peer-b", displayName = "Desktop B", ...))` transmits `CanvasPresenceWirePacket` on ALPN `meridian/canvas-presence/1`.
3. Process A receives the packet and renders peer cursor with label "Desktop B".
4. Stop pointer movement for 10 seconds.
5. Verify the background reaper automatically removes the inactive peer from the presence overlay.

### Step 6: Cold Restart & Catch-Up Recovery
1. Terminate Process B (`kill -9` or close window).
2. On Process A, perform 3 additional edits (e.g. Add shapes 1, 2, and 3).
3. Confirm ops are safely written to Process A's durable op log (`canvas_ops` table or `.jsonl`).
4. Restart Process B and re-connect to Process A.
5. Process B calls `syncTransportB.subscribe(canvasId)` which emits `requestCatchUpSinceLamport = 0L` (or last known lamport).
6. Process A's accept loop receives the catch-up request, queries `opLogA.getOps(canvasId, sinceLamport)`, and replays the 3 ops over the wire.
7. Process B receives the historical ops, applies them, and brings its local document up to date.

---

## 4. Diagnostics & Troubleshooting

| Symptom | Cause | Resolution |
| --- | --- | --- |
| `dial.start` hangs or times out | Firewall / LAN UDP blocked | Ensure UDP port is permitted or allow Iroh relay fallback. |
| `incoming.error` with unrecognized ALPN | Mismatched ALPN string | Verify both sides register `meridian/canvas-sync/1` and `meridian/canvas-presence/1`. |
| Ops duplicate on receive | Deduplication failure | Ensure `opLog.has(canvasId, op.opId)` check is active prior to projection. |
| Phantom ops on cold load | Differ comparing LWW metadata | Verify `cleanScene` in `CanvasOpDiffer` strips `_lamport` and `_actorId` before comparison. |
| Inactive cursors remain indefinitely | Reaper inactive | Ensure `startReaper()` coroutine is running in `IrohCanvasPresenceTransport`. |
