# Iroh Image Replay Inefficiency Investigation (audit-ody9m)

## Objective
Investigate whether images sent over the Kotlin Iroh/App Server path are re-transmitted in FULL (base64) by the mobile client on every subsequent turn, causing multi-turn sessions to waste bandwidth/tokens. Compare this with the local embedded runtime (`letta.js`) which pointers old images before sending them upstream.

## Data Flow
1. **User Message Emission:**
   When a user sends an image, `IrohChannelTransport.send(...)` is invoked with a populated `contentParts` JSON array.
   *See: `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/transport/iroh/IrohChannelTransport.kt`, line 308.*

2. **Command Construction:**
   The transport constructs a `TurnInput.UserMessage` containing the image data (`contentPartsJson = contentParts?.toString()`) and dispatches it to the turn engine via `TurnCommand`.
   *See: `IrohChannelTransport.kt`, line 403.*

3. **Engine Translation:**
   The `AppServerTurnEngine.runTurn(...)` processes the command and translates the `TurnInput.UserMessage` into an `AppServerCommand.Input`. The engine populates the `CreateMessage` payload with exactly **one** message representing the *current* user input.
   *See: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/AppServerTurnEngine.kt`, line ~450.*
   ```kotlin
   messages = listOf(
       AppServerInputMessage(
           role = "user",
           content = turnInput.contentPartsJson
               ?.let { AppServerProtocol.json.parseToJsonElement(it) }
               ?: kotlinx.serialization.json.JsonPrimitive(turnInput.text),
           clientMessageId = turnInput.localMessageId,
       ),
   )
   ```

## Findings: Client Behavior
The Kotlin client **does NOT** re-transmit previous turn images. The `AppServerTurnEngine` constructs an `AppServerInputPayload.CreateMessage` containing a strict `listOf()` holding only the newly sent message. The mobile app has no concept of re-uploading conversation history to the App Server. Therefore, the inefficiency of re-sending full base64 images is entirely decoupled from the Kotlin layer.

## Findings: Server/Shim Behavior
The re-transmission of the full history to the LLM provider happens within the App Server (or the embedded runtime/shim it wraps).

In the local environment, the client actively intercepts and strips images from `messages.jsonl` using `LocalImageContextStripper.stripTranscript(...)`, because:
> "the embedded letta.js runtime OWNS messages.jsonl and REPLAYS it to the provider on every turn."
*See: `android-compose/app/src/main/java/com/letta/mobile/runtime/local/LocalImageContextStripper.kt`, line 126.*

When operating over the Iroh/App Server path, the App Server's backend store rebuilds the message history independently. Since there is no equivalent "stripping" interceptor running against the App Server's managed history before it queries the LLM provider, all prior-turn images remain in full base64 inside the server's context window.

## Conclusion and Recommendation
The mobile Kotlin application is **innocent**. The fix belongs exclusively in the **shim patch-loader / App Server**, not the client application.

**Recommended Fix (App Server / Shim side):**
Implement the pointer-not-payload pattern within the App Server's context builder before the final payload is transmitted to the LLM:
1. Identify all `type:"image"` content parts in historical messages.
2. Ensure the image data is persisted to a content-addressed blob store.
3. Replace the historical image with a lightweight `{type:"text"}` placeholder that carries an `image_ref` pointer metadata field to remain compatible with UI rehydration. This guarantees the LLM receives only a textual placeholder rather than raw base64 data for old turns.
