/**
 * Passive runtime introspection (lcp-d0za).
 *
 * Provides ambient runtime state to the agent via system-reminders
 * appended to user messages — no tool call needed. The agent always
 * knows its serving model, context utilization, and session role
 * without probing.
 *
 * Scope:
 *   - Serving model handle (read from agent record on disk)
 *   - Context window utilization summary (same estimate as the REST
 *     /v1/agents/{id}/context endpoint)
 *   - Session role discriminator (main / fork / subagent) —
 *     load-bearing for lcp-wd3i fork-verdict exemptions
 *   - Model-change delta reminders: when the serving model changes
 *     mid-conversation, a delta system-reminder surfaces the change
 *     to the agent on the next turn
 *
 * Storage: all state is in-process (Map). No disk persistence — roles
 * are assigned per connection/session and are ephemeral.
 */
import { getAgentRecord, readSystemPrompt } from "./store.js";
const conversationState = new Map();
function convKey(agentId, conversationId) {
    return `${agentId}::${conversationId}`;
}
// ──────────────────────────────────────────────────────────────────────
// Session role
// ──────────────────────────────────────────────────────────────────────
/**
 * Tag a conversation with a session role. Called when a session is
 * created or when a fork/subagent is spawned. Default if never set
 * is "main".
 */
export function setSessionRole(agentId, conversationId, role) {
    const key = convKey(agentId, conversationId);
    const existing = conversationState.get(key);
    if (existing) {
        existing.sessionRole = role;
    }
    else {
        conversationState.set(key, { lastKnownModel: null, sessionRole: role });
    }
}
/**
 * Get the session role for a conversation. Returns "main" when no
 * explicit role has been assigned (backward-compatible default).
 */
export function getSessionRole(agentId, conversationId) {
    return conversationState.get(convKey(agentId, conversationId))?.sessionRole ?? "main";
}
// ──────────────────────────────────────────────────────────────────────
// Serving model handle
// ──────────────────────────────────────────────────────────────────────
/**
 * Read the serving model handle from the agent's on-disk record.
 * Returns null when the record is absent or the model field is unset.
 */
export function getServingModelHandle(agentId) {
    const record = getAgentRecord(agentId);
    if (!record)
        return null;
    return typeof record.model === "string" && record.model.length > 0
        ? record.model
        : null;
}
// ──────────────────────────────────────────────────────────────────────
// Context utilization
// ──────────────────────────────────────────────────────────────────────
/**
 * Lightweight context-window utilization summary. Uses the same
 * character-counting heuristic as the REST /v1/agents/{id}/context
 * endpoint so the agent sees a consistent estimate. This is a
 * synchronous floor — the actual message count requires an async
 * listMessages() call, so we use a rough floor constant.
 */
export function getContextUtilizationSummary(agentId, conversationId) {
    const record = getAgentRecord(agentId);
    if (!record)
        return null;
    // Replicate the /v1/agents/{id}/context estimate.
    const sp = readSystemPrompt(conversationId, agentId);
    const spContent = sp && typeof sp === "object" && "content" in sp &&
        typeof sp.content === "string"
        ? sp.content
        : undefined;
    const systemPrompt = spContent ??
        (typeof record.system === "string" ? record.system : "") ??
        "";
    const systemTokens = Math.ceil(systemPrompt.length / 4);
    // Rough floor for message tokens — the REST endpoint multiplies
    // message count × 50. Without awaiting listMessages(), use a
    // conservative floor so the agent sees at least a lower bound.
    const messageFloor = 200;
    const current = systemTokens + messageFloor;
    const max = 200_000;
    const pct = ((current / max) * 100).toFixed(1);
    return `${pct}% (≈${current} / ${max} tokens)`;
}
// ──────────────────────────────────────────────────────────────────────
// Model-change delta tracking
// ──────────────────────────────────────────────────────────────────────
/**
 * Check whether the serving model changed since the last turn on this
 * conversation. When it has, return a delta system-reminder surfacing
 * the change; otherwise return null. Always updates lastKnownModel so
 * subsequent calls with the same model are no-ops.
 */
export function detectModelChange(agentId, conversationId, newModel) {
    if (!newModel)
        return null;
    const key = convKey(agentId, conversationId);
    let state = conversationState.get(key);
    if (!state) {
        state = { lastKnownModel: newModel, sessionRole: "main" };
        conversationState.set(key, state);
        return null;
    }
    if (state.lastKnownModel && state.lastKnownModel !== newModel) {
        const delta = `<system-reminder>\nModel changed: ${state.lastKnownModel} → ${newModel}\n</system-reminder>`;
        state.lastKnownModel = newModel;
        return delta;
    }
    state.lastKnownModel = newModel;
    return null;
}
/**
 * Force-update the tracked model without triggering a delta reminder.
 * Used at connection time so the initial system-reminder carries the
 * model without also emitting a spurious "changed" frame.
 */
export function seedModelHandle(agentId, conversationId, model) {
    if (!model)
        return;
    const key = convKey(agentId, conversationId);
    let state = conversationState.get(key);
    if (!state) {
        state = { lastKnownModel: model, sessionRole: "main" };
        conversationState.set(key, state);
    }
    else {
        state.lastKnownModel = model;
    }
}
// ──────────────────────────────────────────────────────────────────────
// Connection reminder builder
// ──────────────────────────────────────────────────────────────────────
/**
 * Build the connection system-reminder injected at turn start.
 * Includes serving model, context utilization, session role, and
 * subagent activity. Callers prepend this to the user message before
 * sending to the agent pool.
 *
 * **Fail-open**: this function must NEVER throw or block. The
 * reminder is an enhancement — if any lookup fails (missing agent
 * record, I/O error, unexpected input), the function silently
 * returns "" and the message path proceeds unblocked.
 */
export function buildConnectionReminder(agentId, conversationId) {
    try {
        const role = getSessionRole(agentId, conversationId);
        const model = getServingModelHandle(agentId);
        const ctx = getContextUtilizationSummary(agentId, conversationId);
        const subagents = buildSubagentSummaryLine();
        const lines = [];
        if (model)
            lines.push(`Serving model: ${model}`);
        if (ctx)
            lines.push(`Context utilization: ${ctx}`);
        lines.push(`Session role: ${role}`);
        if (subagents)
            lines.push(subagents);
        if (lines.length === 0)
            return "";
        return `<system-reminder>\n${lines.join("\n")}\n</system-reminder>`;
    }
    catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        console.error(`[runtime-introspection] buildConnectionReminder failed (fail-open): ${msg}`);
        return "";
    }
}
// ──────────────────────────────────────────────────────────────────────
// Subagent activity summary
// ──────────────────────────────────────────────────────────────────────
/**
 * letta-mobile-d9a7p: build the subagent activity summary line for the
 * connection reminder. Matches the SHIM wording (admin-shim PR #53):
 *   "Subagents: 2 running — worker (feat/x, 4m), tester (test/y, 1m); ⚠ 1 stuck-suspected — builder (12m)"
 *
 * Omit when no subagents. Fail-open: if getSubagentSnapshot throws or
 * returns unexpected shape, return null so the reminder proceeds without
 * the subagent line.
 */
export function buildSubagentSummaryLine() {
    try {
        if (typeof globalThis.__lettaMobileGetSubagentSnapshot !== "function")
            return null;
        const snapshot = globalThis.__lettaMobileGetSubagentSnapshot();
        if (!snapshot || !Array.isArray(snapshot.agents) || snapshot.agents.length === 0)
            return null;
        const running = snapshot.agents.filter((a) => a.status === "running" || a.status === "pending");
        if (running.length === 0)
            return null;
        const now = Date.now();
        const STUCK_THRESHOLD_MS = 5 * 60 * 1000;
        const stuck = running.filter((a) => {
            const start = typeof a.startTime === "number" ? a.startTime : 0;
            return start > 0 && now - start > STUCK_THRESHOLD_MS;
        });
        const healthy = running.filter((a) => !stuck.includes(a));
        const parts = [];
        if (healthy.length > 0) {
            const summaries = healthy.map((a) => {
                const desc = typeof a.description === "string" && a.description.trim()
                    ? a.description.trim()
                    : a.type || "subagent";
                const elapsed = typeof a.startTime === "number" && a.startTime > 0
                    ? formatElapsed(now - a.startTime)
                    : "";
                return elapsed ? `${desc} (${elapsed})` : desc;
            });
            parts.push(`${healthy.length} running — ${summaries.join(", ")}`);
        }
        if (stuck.length > 0) {
            const summaries = stuck.map((a) => {
                const desc = typeof a.description === "string" && a.description.trim()
                    ? a.description.trim()
                    : a.type || "subagent";
                const elapsed = typeof a.startTime === "number" && a.startTime > 0
                    ? formatElapsed(now - a.startTime)
                    : "";
                return elapsed ? `${desc} (${elapsed})` : desc;
            });
            parts.push(`⚠ ${stuck.length} stuck-suspected (>5m) — ${summaries.join(", ")}`);
        }
        return parts.length > 0 ? `Subagents: ${parts.join("; ")}` : null;
    }
    catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        console.error(`[runtime-introspection] buildSubagentSummaryLine failed (fail-open): ${msg}`);
        return null;
    }
}
/**
 * Format elapsed milliseconds as a compact duration string (e.g. "4m", "12m").
 */
function formatElapsed(ms) {
    const seconds = Math.floor(ms / 1000);
    if (seconds < 60)
        return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60)
        return `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = minutes % 60;
    return remainingMinutes > 0 ? `${hours}h${remainingMinutes}m` : `${hours}h`;
}
// ──────────────────────────────────────────────────────────────────────
// Test helpers
// ──────────────────────────────────────────────────────────────────────
/** Drop all in-process runtime state (test-only). */
export function __clearRuntimeState() {
    conversationState.clear();
}
