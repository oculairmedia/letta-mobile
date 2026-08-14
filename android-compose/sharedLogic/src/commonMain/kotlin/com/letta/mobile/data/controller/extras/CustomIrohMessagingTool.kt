package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec).
 *
 * The agent-visible Iroh agent-to-agent messaging tool, distinct from
 * letta-code's `matrix_agent_message`:
 *
 *   - **matrix_agent_message** (letta-code upstream): agent ↔ HUMAN surface
 *     via Matrix / Facebook Messenger. Stays unchanged — this bead is
 *     explicitly NOT a re-point of that tool. See bn008-phase2-handoff §1
 *     "Architecture distinction".
 *
 *   - **agent_message_send** (this tool): agent ↔ AGENT surface via the
 *     `meridian agent-message send` CLI binary, which itself uses the Iroh
 *     a2a ALPN (bn008 Layer 1 substrate). Injected into every agent's
 *     runtime_start `external_tools` so the model sees it without operator
 *     intervention.
 *
 * The tool is wired via [ExternalToolRegistry] (the existing controller
 * mechanism) and gated behind a new capability so it lights up only when
 * the controller wants it (the production wrapper always wants it; the
 * factory-default Android controller does not — it has no CLI binary to
 * call).
 *
 * ## Multi-line bodies
 * The body is supplied to the CLI binary via `--body-file -` (stdin). This
 * is the load-bearing choice — using `--body "<...>"` here would re-create
 * the Meridian→Lester regression from bn008-phase2-handoff risk #1, where
 * a multi-line body collapsed to "". The pinned regression test
 * `multiLineBodyRoundTripsViaStdin` exercises the full stdin path with a
 * body containing newlines, quotes, ampersands, and a URL.
 *
 * ## Why this lives in sharedLogic and not :cli
 * The existing `AgentMessageSendCommand` in `:cli` is an Android library
 * module — no installable distribution. The wrapper distribution
 * `:iroh-wrapper-cli` IS installable but doesn't yet carry the
 * `agent-message send` subcommand. The cleanest path is: keep the
 * `AgentMessageSendCommand` unchanged (the bead says "do not modify the
 * CLI"), invoke the SAME CLI from a controller-owned tool wrapper, and let
 * the controller's binary-path config point at whatever `meridian` binary
 * the operator has deployed. This keeps the wire path
 * (`a2a.create_and_deliver`) identical to the operator flow.
 *
 * ## Code health
 * The validation chain and the runner dispatch are extracted into private
 * helpers ([validateInputs], [dispatchValidated]) so [invoke] stays a
 * thin dispatcher — CodeScene flags a guard-chain of 5+ early returns
 * inside a single suspend fun as Complex Method.
 */
class CustomIrohMessagingTool(
    /**
     * Absolute path or PATH-relative name of the `meridian` binary. Wired in
     * by the controller (`AppServerServeIrohCommand`) so test fixtures can
     * pass a stub script and production uses the operator-deployed binary.
     */
    private val binary: String,
    /**
     * Per-agent Iroh identity directory. Optional — the CLI defaults to
     * `~/.letta/iroh/identities` when unset.
     */
    private val identityDir: String? = null,
    /**
     * Agent-address kv file path. Optional — the CLI defaults to
     * `~/.letta/iroh/agent-addresses.kv` when unset.
     */
    private val addressStore: String? = null,
    /**
     * The runner that actually spawns the CLI. The default (`null`) is
     * resolved by [resolveRunner] at invoke time, which the JVM/Android
     * source sets fill with [DefaultIrohCliRunner]. Tests inject a stub
     * that captures the argv and stdin bytes without spawning a process —
     * that's how `multiLineBodyRoundTripsViaStdin` and the regression test
     * for the Meridian→Lester bug both stay hermetic.
     */
    private val runner: IrohCliRunner? = null,
) : ExternalTool {

    override val name: String = TOOL_NAME

    override val description: String =
        "Send a direct message to another agent over Iroh. delivered=true is returned " +
            "only after recipient application acceptance; accepted=true, delivered=false " +
            "means transport receipt without application confirmation. Distinct from " +
            "matrix_agent_message, which is the agent↔human surface."

    override val capability: Capability = Capability.AgentMessaging

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("to", buildJsonObject {
                put("type", "string")
                put("description", "Recipient agentId (the `to` field on the a2a envelope).")
            })
            put("body", buildJsonObject {
                put("type", "string")
                put("description", "Message body. Multi-line content round-trips exactly " +
                    "(the wrapper pipes this through stdin to avoid shell-quoting collapse).")
            })
        })
        put("required", buildJsonArray { add("to"); add("body") })
    }

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult {
        // Validation lives in a helper; the call site just dispatches on
        // the result. The helper extracts the CodeScene complex-method
        // guard-chain into a separate function and returns a sealed result
        // so we don't need a parallel error channel or nullable return.
        return when (val v = validateInputs(agentId, input)) {
            is Validated.Error -> ExternalToolResult.Error(
                "agent_message_send: ${v.reason}",
            )
            is Validated.Ok -> dispatchValidated(v.fromAgentId, v.toAgentId, v.body)
        }
    }

    /**
     * Sealed result of the validation chain. Replaces the prior "nullable
     * triple + parallel mutable error channel" anti-pattern with a single
     * return value that's both typesafe and side-effect-free.
     */
    private sealed interface Validated {
        data class Ok(
            val fromAgentId: String,
            val toAgentId: String,
            val body: String,
        ) : Validated
        data class Error(val reason: String) : Validated
    }

    /**
     * Validate the call surface against the schema and runtime constraints.
     * Returns the first failure as [Validated.Error] or the parsed triple
     * as [Validated.Ok]. Order of checks matters for the error message:
     * runtime-context (caller identity) first, then schema, then semantic.
     */
    private fun validateInputs(agentId: String?, input: JsonObject): Validated {
        // Runtime context: cannot function without knowing who is sending.
        val fromAgentId = agentId ?: return Validated.Error(
            "cannot determine calling agentId " +
                "(inbound frame has no runtime scope); refusing to send.",
        )
        // Schema marks both required, but the App Server may still hand us
        // a partial payload (or an LLM may omit/rename a field); validate
        // here rather than relying on schema enforcement alone.
        val toAgentId = input["to"]?.jsonPrimitive?.contentOrNull
            ?: return Validated.Error("missing required input field 'to'")
        val body = input["body"]?.jsonPrimitive?.contentOrNull
            ?: return Validated.Error("missing required input field 'body'")
        if (toAgentId.isBlank()) {
            return Validated.Error("'to' must not be blank")
        }
        if (fromAgentId == toAgentId) {
            // Self-echo guard (letta-mobile-hj69d sibling): the receiver's
            // a2a-recv handler also filters this, but failing fast here
            // saves a round trip and surfaces a clearer error to the agent.
            return Validated.Error("refusing to send to self (from == to: $fromAgentId)")
        }
        return Validated.Ok(fromAgentId, toAgentId, body)
    }

    /**
     * Resolve the runner, dispatch the CLI subprocess invocation, and map
     * the typed result to [ExternalToolResult]. Split out of [invoke] for
     * the same code-health reason as [validateInputs].
     */
    private suspend fun dispatchValidated(
        fromAgentId: String,
        toAgentId: String,
        body: String,
    ): ExternalToolResult {
        // Tests pass an explicit runner; production relies on the
        // platform-specific `DefaultIrohCliRunner` resolved at invoke time.
        // If no runner is available (e.g. iOS/js targets without a
        // ProcessBuilder equivalent), surface a clear error rather than
        // crashing.
        val effectiveRunner = runner ?: resolveDefaultRunner()
            ?: return ExternalToolResult.Error(
                "agent_message_send: no IrohCliRunner available on this platform; " +
                    "the controller is not configured for agent-to-agent messaging.",
            )

        val result = effectiveRunner.send(
            binary = binary,
            fromAgentId = fromAgentId,
            toAgentId = toAgentId,
            body = body,
            paths = IrohCliPaths(identityDir = identityDir, addressStore = addressStore),
        )
        return when (result) {
            is IrohCliSendResult.Delivered -> ExternalToolResult.Success(
                """{"ok":true,"delivered":true,"msgId":"${result.msgId}","to":"$toAgentId"}""",
            )
            is IrohCliSendResult.Accepted -> ExternalToolResult.Success(
                """{"ok":true,"accepted":true,"delivered":false,"msgId":"${result.msgId}","to":"${result.toAgentId}"}""",
            )
            is IrohCliSendResult.Unaddressable -> ExternalToolResult.Error(
                "agent_message_send: target '$toAgentId' is unaddressable: ${result.reason}",
            )
            is IrohCliSendResult.Failed -> ExternalToolResult.Error(
                "agent_message_send: send to '$toAgentId' failed: ${result.reason}",
            )
        }
    }

    /**
     * Resolved at invoke time by the platform-specific source set:
     *  - JVM / Android (jvmAndAndroid source set): returns a singleton
     *    [DefaultIrohCliRunner] that uses ProcessBuilder.
     *  - Other platforms: returns null (the tool surfaces "not configured"
     *    to the agent rather than crashing).
     *
     * Lives in the same file as the JVM implementation so the common
     * source has a single symbol to call; `expect`/`actual` would also work
     * but adds a source-set for a one-line override, which is more weight
     * than the call site needs.
     */
    private fun resolveDefaultRunner(): IrohCliRunner? = defaultIrohCliRunnerOrNull()

    companion object {
        /**
         * The tool name agents see in their tool list. Picked to be
         * unambiguous alongside `matrix_agent_message` — agents picking the
         * Iroh tool for Iroh traffic is the whole point of this injection.
         */
        const val TOOL_NAME: String = "agent_message_send"
    }
}