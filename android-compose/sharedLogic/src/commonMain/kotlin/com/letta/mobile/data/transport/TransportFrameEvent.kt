package com.letta.mobile.data.transport

/**
 * Raw transport frame plus delivery metadata.
 */
data class TransportFrameEvent(
    val frame: ServerFrame,
    val isReplay: Boolean = false,
) {
    /**
     * letta-mobile-qygvv.11: the chat projection of [frame], computed once per
     * published event and shared by every [WsChatBridge] subscriber (and the
     * ingest deduplicator). A transport publishes ONE instance per frame to all
     * of its collectors, so N subscribers used to parse the same frame N times;
     * now they each receive the same frame and the same projected event.
     * Not part of equals/hashCode/copy.
     */
    internal val timelineEvent: WsTimelineEvent? by lazy { projectTimelineEvent() }
}
