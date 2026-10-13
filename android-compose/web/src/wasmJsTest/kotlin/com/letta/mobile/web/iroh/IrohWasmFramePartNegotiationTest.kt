package com.letta.mobile.web.iroh

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IrohWasmFramePartNegotiationTest {
    private fun control(raw: String) = AppServerProtocol.decodeFrame(raw, AppServerChannel.Control).frame

    @Test
    fun `a successful auth response listing frame_part allows split sends`() {
        val auth = control("""{"type":"auth_response","request_id":"a","success":true,"capabilities":["frame_part","canvas"]}""")

        assertEquals(true, serverAcceptsFramePartsAfter(auth))
    }

    @Test
    fun `a successful auth response without frame_part keeps sends to one frame`() {
        assertEquals(false, serverAcceptsFramePartsAfter(control("""{"type":"auth_response","request_id":"a","success":true}""")))
        assertEquals(
            false,
            serverAcceptsFramePartsAfter(
                control("""{"type":"auth_response","request_id":"a","success":true,"capabilities":["canvas"]}"""),
            ),
        )
    }

    @Test
    fun `a failed auth response or any other frame changes nothing`() {
        val failed = control("""{"type":"auth_response","request_id":"a","success":false,"capabilities":["frame_part"]}""")
        val adminReply = control("""{"type":"admin_rpc_response","request_id":"b","success":true,"result":{}}""")

        assertNull(serverAcceptsFramePartsAfter(failed))
        assertNull(serverAcceptsFramePartsAfter(adminReply))
    }
}
