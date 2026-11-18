package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.host.HostVoiceEndpoint
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus

/** Where one voice session sends speech. */
sealed interface VoiceRoute {
    /**
     * Through the host over its connection, to the speech server the host keeps for every device:
     * [status] is the shared setup the session starts with, [origin] the server's origin that this
     * device's audio acknowledgement names.
     */
    class Host(
        /** The host's voice endpoint. */
        val endpoint: HostVoiceEndpoint,
        /** The shared setup when the session was built. */
        val status: HostVoiceSetupStatus,
        /** The shared server's origin; null when the status names none. */
        val origin: EndpointOrigin?,
    ) : VoiceRoute {
        override fun toString(): String = "VoiceRoute.Host"
    }

    /**
     * Straight to this device's own speech server: the host does not serve the voice-endpoint
     * contract, has no setup, or the person chose this device's own server.
     */
    data object Direct : VoiceRoute
}
