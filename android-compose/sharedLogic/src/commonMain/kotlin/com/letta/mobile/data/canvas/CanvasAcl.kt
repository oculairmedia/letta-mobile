package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.PrincipalAcl
import com.letta.mobile.data.document.UnauthorizedDocumentMutationException

/**
 * Access Control List for a collaborative [CanvasDocument]: the shared-document
 * [PrincipalAcl] (P7), which began as this class. Same fields, same serial form.
 */
typealias CanvasAcl = PrincipalAcl

/**
 * Thrown when an actor attempts an unauthorized mutation on a [CanvasSession].
 */
class UnauthorizedCanvasMutationException(
    actorId: String?,
    val canvasId: CanvasId,
    message: String = "Actor '${actorId ?: "anonymous"}' is not authorized to mutate canvas '$canvasId'",
) : UnauthorizedDocumentMutationException(actorId, canvasId.value, message)
