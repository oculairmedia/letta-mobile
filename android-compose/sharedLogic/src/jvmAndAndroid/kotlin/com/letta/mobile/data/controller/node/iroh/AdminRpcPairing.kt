package com.letta.mobile.data.controller.node.iroh

/** Pairing services and optional QR signing configuration for admin routes. Null services leave pairing unavailable. */
class AdminRpcPairing(
    /** Host-owned invite and paired-peer state shared by the pairing handlers. */
    val pairingService: IrohPairingService? = null,
    /**
     * How `pair.invite.create {qr:true}` signs the QR
     * and which NodeId/dial address it names. Null = no QR minting.
     */
    val pairingQr: PairingQrConfig? = null,
)
