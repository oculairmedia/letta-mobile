import assert from "node:assert/strict";
import { retrySourceBetween, retryAnchors } from "./retry-source-fixtures.ts";
import type { RetryFixtureMessage, RetryFixtureState, RetryFixtureRuntime } from "./retry-fixture-types.ts";

export function retryFixtureRuntime(
  source: string,
  local: boolean,
  validateHead: (path: string, headBytes: number) => void = () => {},
): RetryFixtureRuntime {
  const factory = new Function("getBackend", "randomUUID9", "crypto", "validateUnreadTranscriptHead", `
    ${retrySourceBetween(source, { start: "function optionalString3(", end: "function currentIsoTimestamp2(" })}
    ${retrySourceBetween(source, { start: "function textContent(", end: "function localImageContentFromLegacyImage(" })}
    ${retrySourceBetween(source, { start: "function timestampFromIso(", end: "\nfunction " })}
    ${retrySourceBetween(source, { start: "function refreshInputOtidsForNewRequest(", end: "function rebuildInputWithFreshDenials(" })}
    ${retrySourceBetween(source, { start: "function ensureTurnInputMessageOtids(", end: "var init_turn_input_state =" })}
    ${retrySourceBetween(source, { start: "async function prepareProviderRetryInput(", end: "async function shouldRetryPostStopTurn(" })}
    ${retrySourceBetween(source, { start: "function residentCoversIds(", end: "\nfunction " })}
    ${retrySourceBetween(source, { start: "function overlayResidentLocalMessageTail(", end: "\nfunction " })}
    const getRetryDelayMs2 = () => 0;
    const emitRecoverableRetryNotice = () => {};
    const getRetryStatusMessage = () => "retry";
    const LLM_API_ERROR_MAX_RETRIES = 3;
    return {
      ensure: ensureTurnInputMessageOtids,
      refresh: refreshTurnInputOtidsForNewRequest,
      update: updateTurnInputMessagesPreservingOtids,
      providerRetry: prepareProviderRetryInput,
      storeMethods: {
        ${retrySourceBetween(source, { start: "  appendTurnInput(", end: "  appendStreamChunk(" })},
        ${retryAnchors(source).persist},
        ${retrySourceBetween(source, { start: "  localMessagesForConversation(", end: "  residentLocalMessagesForConversation(" })},
        ${retrySourceBetween(source, { start: "  residentLocalMessagesForConversation(", end: "  readConversationMessagesFromDisk(" })},
        ${retrySourceBetween(source, { start: "  pushLocalMessage(", end: "  validateUnreadTranscriptHeadOnce(" })},
        ${retrySourceBetween(source, { start: "  validateUnreadTranscriptHeadOnce(", end: "  persistPendingAssistantMessage(" })}
      }
    };
  `);
  let seq = 0;
  const uuid = () => `retry-uuid-${++seq}`;
  const result: unknown = factory(
    () => ({ capabilities: { localModelCatalog: local, remoteMemfs: !local } }), uuid, { randomUUID: uuid }, validateHead,
  );
  assert.ok(result && typeof result === "object" && "refresh" in result && typeof result.refresh === "function");
  return result as RetryFixtureRuntime;
}
