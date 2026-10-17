use iroh::{
    Endpoint, EndpointId,
    endpoint::{Connection, SendStream},
};
use n0_future::task::AbortOnDropHandle;
use tokio::sync::Mutex;
use wasm_bindgen::{JsError, prelude::wasm_bindgen};

use crate::frame::{FrameDecoder, encode_frame_with_max};
use crate::session::{EVENT_QUEUE_CAPACITY, EventReceiver, js_error, spawn_reader, take_events};

/// The host's canvas relay protocol (`IrohCanvasRelay.CANVAS_RELAY_ALPN`).
pub const CANVAS_RELAY_ALPN: &[u8] = b"meridian/canvas-relay/1";
/// Largest canvas relay frame (`CanvasRelayFraming.MAX_FRAME_BYTES`): plain, never split in parts.
pub const CANVAS_RELAY_MAX_FRAME_BYTES: usize = 8 * 1024 * 1024;

/// letta-mobile-ckmub.17.9: one canvas relay stream to the host, opened from the session's own
/// endpoint. Text frames go both ways, length-prefixed as `CanvasRelayFraming` writes them; what
/// they say is the shared Kotlin canvas relay protocol's business, not this binding's.
#[wasm_bindgen]
pub struct IrohCanvasRelayStream {
    connection: Connection,
    send: Mutex<SendStream>,
    events: Option<EventReceiver>,
    reader: Option<AbortOnDropHandle<()>>,
}

impl IrohCanvasRelayStream {
    pub(crate) async fn open(endpoint: &Endpoint, host: EndpointId) -> Result<Self, JsError> {
        let connection = endpoint
            .connect(host, CANVAS_RELAY_ALPN)
            .await
            .map_err(|error| js_error("connecting to the canvas relay", error))?;
        let (send, recv) = connection
            .open_bi()
            .await
            .map_err(|error| js_error("opening canvas relay stream", error))?;
        let (events_tx, events_rx) = async_channel::bounded(EVENT_QUEUE_CAPACITY);
        let decoder = FrameDecoder::with_max_frame_bytes(CANVAS_RELAY_MAX_FRAME_BYTES);
        let reader = spawn_reader(recv, events_tx, decoder, None);
        Ok(Self {
            connection,
            send: Mutex::new(send),
            events: Some(events_rx),
            reader: Some(reader),
        })
    }
}

#[wasm_bindgen]
impl IrohCanvasRelayStream {
    /// Writes [payload] as one frame, under a lock so frames never interleave.
    pub async fn send(&self, payload: &str) -> Result<(), JsError> {
        let frame = encode_frame_with_max(payload, CANVAS_RELAY_MAX_FRAME_BYTES)
            .map_err(|error| JsError::new(&error.to_string()))?;
        self.send
            .lock()
            .await
            .write_all(&frame)
            .await
            .map_err(|error| js_error("writing canvas relay frame", error))
    }

    pub fn take_events(&mut self) -> Result<web_sys::ReadableStream, JsError> {
        take_events(&mut self.events, "canvas relay")
    }

    /// Ends this stream's connection only; the session and its endpoint stay up.
    pub fn close(&mut self) {
        drop(self.reader.take());
        self.connection.close(0u8.into(), b"canvas relay closed");
    }
}

#[cfg(test)]
mod tests {
    use super::{CANVAS_RELAY_ALPN, CANVAS_RELAY_MAX_FRAME_BYTES};

    #[test]
    fn canvas_relay_alpn_matches_native_contract() {
        assert_eq!(CANVAS_RELAY_ALPN, b"meridian/canvas-relay/1");
    }

    #[test]
    fn canvas_relay_frames_match_native_limit() {
        assert_eq!(CANVAS_RELAY_MAX_FRAME_BYTES, 8 * 1024 * 1024);
    }
}
