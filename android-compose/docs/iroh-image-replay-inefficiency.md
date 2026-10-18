# Iroh image replay investigation

**Status:** Source audit updated 2026-10-18. Client request shaping verified;
provider history behavior remains unverified without an upstream request capture.

The current Kotlin input builder,
`sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/runtime/TurnInputAcceptance.kt`,
constructs `AppServerInputPayload.CreateMessage.messages` as a single
`AppServerInputMessage` for the current `TurnInput.UserMessage`. Its content is
`contentPartsJson`, when supplied, or the current text; `clientMessageId` is the
local message id. `IrohChannelTransport` supplies that input through the shared
turn engine. This path does not construct and upload the previous conversation
history on each new send. A reconnect retry can resend the same pending input;
that is a separate delivery behavior, not a historical-context upload.

The earlier investigation attributed image stripping to Android's
`LocalImageContextStripper`. That class and the old local shim are absent from
the current tree. It also placed request construction directly in
`AppServerTurnEngine`; that code now lives in `TurnInputAcceptance`.

These source facts do **not** prove how the pinned Letta Code runtime constructs
provider context, whether old images are retained or compressed there, or where
an observed bandwidth regression originates. Capture the actual provider request
on both local and Iroh-hosted paths before assigning a fix. Any pointer scheme
must preserve image storage and UI rehydration and be validated against the
pinned runtime; the former recommendation to patch a shim is not a current
implementation instruction.
