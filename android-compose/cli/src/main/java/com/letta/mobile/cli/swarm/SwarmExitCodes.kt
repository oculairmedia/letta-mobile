package com.letta.mobile.cli.swarm

import com.letta.mobile.data.pairing.PairingError
import com.letta.mobile.data.swarm.DeliveryEvent
import com.letta.mobile.data.swarm.DeliveryFailure
import com.letta.mobile.data.swarm.DeliveryReceipt
import com.letta.mobile.data.swarm.TurnTerminal

/**
 * letta-mobile-jvctq: the swarm commands' exit codes. Each fail-closed refusal and each turn
 * terminal has its own code, so a calling script (the orchestrator) can branch without parsing
 * text. 1 stays Clikt's usage error.
 */
enum class SwarmExit(val code: Int) {
    Ok(0),
    NotPaired(10),
    Denied(11),
    Unreachable(12),
    AgentNotFound(13),
    AgentAmbiguous(14),
    ConversationRefused(15),
    HostError(16),
    Rejected(20),
    TimedOut(21),
    Unconfirmed(22),
    Expired(23),
    IdReused(24),
    TurnFailed(30),
    TurnCancelled(31),
    NeedsApproval(32),
    ReplyUnobserved(33),
    InvalidInvite(40),
    InviteExpired(41),
    PairHostUnreachable(42),
    PairHostMismatch(43),
    InviteRefused(44),
    PairSaveFailed(45),
    AlreadyPaired(46),
    PairDeclined(47),
}

val DeliveryFailure.exit: SwarmExit
    get() = when (this) {
        DeliveryFailure.NotPaired -> SwarmExit.NotPaired
        is DeliveryFailure.Denied -> SwarmExit.Denied
        is DeliveryFailure.Unreachable -> SwarmExit.Unreachable
        is DeliveryFailure.AgentNotFound -> SwarmExit.AgentNotFound
        is DeliveryFailure.AgentAmbiguous -> SwarmExit.AgentAmbiguous
        is DeliveryFailure.ConversationNotFound, is DeliveryFailure.ConversationOutOfScope -> SwarmExit.ConversationRefused
        is DeliveryFailure.HostError -> SwarmExit.HostError
        is DeliveryFailure.Rejected -> SwarmExit.Rejected
        is DeliveryFailure.TimedOut -> SwarmExit.TimedOut
        is DeliveryFailure.Unconfirmed -> SwarmExit.Unconfirmed
        is DeliveryFailure.Expired -> SwarmExit.Expired
        is DeliveryFailure.IdReused -> SwarmExit.IdReused
    }

val TurnTerminal.exit: SwarmExit
    get() = when (this) {
        TurnTerminal.Completed -> SwarmExit.Ok
        is TurnTerminal.Failed -> SwarmExit.TurnFailed
        is TurnTerminal.Cancelled -> SwarmExit.TurnCancelled
        is TurnTerminal.NeedsApproval -> SwarmExit.NeedsApproval
        TurnTerminal.Unobserved -> SwarmExit.ReplyUnobserved
    }

/** The exit of a send whose flow ended with [last]: a wait stage reached is success. */
fun sendExit(last: DeliveryEvent?): SwarmExit = when (last) {
    is DeliveryFailure -> last.exit
    is DeliveryReceipt.Processed -> last.terminal.exit
    is DeliveryReceipt -> SwarmExit.Ok
    else -> SwarmExit.HostError
}

val PairingError.exit: SwarmExit
    get() = when (this) {
        PairingError.NOT_A_PAIRING_CODE, PairingError.UNSUPPORTED_VERSION, PairingError.MALFORMED -> SwarmExit.InvalidInvite
        PairingError.EXPIRED -> SwarmExit.InviteExpired
        PairingError.HOST_UNREACHABLE -> SwarmExit.PairHostUnreachable
        PairingError.HOST_MISMATCH -> SwarmExit.PairHostMismatch
        PairingError.INVITE_INVALID, PairingError.DENIED, PairingError.RATE_LIMITED -> SwarmExit.InviteRefused
        PairingError.SAVE_FAILED -> SwarmExit.PairSaveFailed
    }
