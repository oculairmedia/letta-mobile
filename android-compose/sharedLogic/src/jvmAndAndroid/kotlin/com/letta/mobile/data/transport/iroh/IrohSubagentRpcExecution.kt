package com.letta.mobile.data.transport.iroh

/** Require explicit dismissal support while preserving legacy list and todos negotiation. */
internal fun supportsScopedSubagentRpc(method: String, advertised: Set<String>?): Boolean {
    if (advertised == null) return method != "subagent.dismiss"
    if (IrohSubagentRpcClient.SUBAGENT_RPC_CAPABILITY !in advertised) return false
    if (method == "subagent.dismiss") return "subagent_dismiss_v1" in advertised
    return true
}
