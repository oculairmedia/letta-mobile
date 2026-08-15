use std::{str::FromStr, sync::Arc};

use async_channel::{Receiver, Sender};
use iroh::{
    Endpoint, EndpointId,
    endpoint::{Connection, RecvStream, SendStream},
};
use n0_future::{
    StreamExt,
    task::{self, AbortOnDropHandle},
};
use tokio::sync::Mutex;
use wasm_bindgen::{JsError, JsValue, prelude::wasm_bindgen};
use wasm_streams::ReadableStream;

use crate::frame::{FrameDecoder, encode_frame};

const APP_SERVER_ALPN: &[u8] = b"/letta/appserver/0";
const READ_CHUNK_BYTES: usize = 64 * 1024;
const EVENT_QUEUE_CAPACITY: usize = 128;

type FrameEvent = Result<String, String>;
type EventReceiver = Receiver<FrameEvent>;

#[wasm_bindgen]
pub struct IrohAppServerSession {
    endpoint: Endpoint,
    connection: Connection,
    control_send: Arc<Mutex<SendStream>>,
    control_events: Option<EventReceiver>,
    stream_events: Option<EventReceiver>,
    reader_tasks: Vec<AbortOnDropHandle<()>>,
}

#[wasm_bindgen]
impl IrohAppServerSession {
    pub async fn connect(ticket_or_url: &str) -> Result<IrohAppServerSession, JsError> {
        let endpoint_id = parse_endpoint_id(ticket_or_url)?;
        let endpoint = Endpoint::builder(iroh::endpoint::presets::N0)
            .alpns(vec![APP_SERVER_ALPN.to_vec()])
            .bind()
            .await
            .map_err(|error| js_error("binding browser Iroh endpoint", error))?;
        let connection = endpoint
            .connect(endpoint_id, APP_SERVER_ALPN)
            .await
            .map_err(|error| js_error("connecting to Iroh App Server", error))?;

        let (control_send, control_recv) = connection
            .open_bi()
            .await
            .map_err(|error| js_error("opening control stream", error))?;
        let (mut stream_send, stream_recv) = connection
            .open_bi()
            .await
            .map_err(|error| js_error("opening event stream", error))?;
        stream_send
            .finish()
            .map_err(|error| js_error("finishing event stream send half", error))?;

        let (control_tx, control_rx) = async_channel::bounded(EVENT_QUEUE_CAPACITY);
        let (stream_tx, stream_rx) = async_channel::bounded(EVENT_QUEUE_CAPACITY);
        let reader_tasks = vec![
            spawn_reader(control_recv, control_tx),
            spawn_reader(stream_recv, stream_tx),
        ];

        Ok(Self {
            endpoint,
            connection,
            control_send: Arc::new(Mutex::new(control_send)),
            control_events: Some(control_rx),
            stream_events: Some(stream_rx),
            reader_tasks,
        })
    }

    pub async fn send_control(&self, payload: &str) -> Result<(), JsError> {
        let frame = encode_frame(payload).map_err(|error| JsError::new(&error.to_string()))?;
        self.control_send
            .lock()
            .await
            .write_all(&frame)
            .await
            .map_err(|error| js_error("writing control frame", error))
    }

    pub fn take_control_events(&mut self) -> Result<web_sys::ReadableStream, JsError> {
        take_events(&mut self.control_events, "control")
    }

    pub fn take_stream_events(&mut self) -> Result<web_sys::ReadableStream, JsError> {
        take_events(&mut self.stream_events, "stream")
    }

    pub async fn close(&mut self) {
        self.reader_tasks.clear();
        self.connection.close(0u8.into(), b"browser client closed");
        self.endpoint.close().await;
    }
}

fn parse_endpoint_id(ticket_or_url: &str) -> Result<EndpointId, JsError> {
    let body = ticket_or_url
        .trim()
        .strip_prefix("iroh://")
        .unwrap_or(ticket_or_url.trim());
    let endpoint_id = body.split_once('@').map_or(body, |(node, _)| node);
    EndpointId::from_str(endpoint_id)
        .map_err(|error| JsError::new(&format!("invalid Iroh endpoint id: {error}")))
}

fn spawn_reader(recv: RecvStream, sender: Sender<FrameEvent>) -> AbortOnDropHandle<()> {
    AbortOnDropHandle::new(task::spawn(read_frames(recv, sender)))
}

async fn read_frames(mut recv: RecvStream, sender: Sender<FrameEvent>) {
    let result = async {
        let mut decoder = FrameDecoder::new();
        while let Some(chunk) = recv
            .read_chunk(READ_CHUNK_BYTES)
            .await
            .map_err(|error| format!("reading framed stream: {error}"))?
        {
            let frames = decoder.push(&chunk).map_err(|error| error.to_string())?;
            for frame in frames {
                sender
                    .send(Ok(frame))
                    .await
                    .map_err(|_| "frame receiver closed".to_string())?;
            }
        }
        decoder.finish().map_err(|error| error.to_string())
    }
    .await;

    if let Err(error) = result {
        let _ = sender.send(Err(error)).await;
    }
}

fn take_events(
    receiver: &mut Option<EventReceiver>,
    channel: &str,
) -> Result<web_sys::ReadableStream, JsError> {
    let receiver = receiver
        .take()
        .ok_or_else(|| JsError::new(&format!("{channel} events already taken")))?;
    let stream = receiver.map(|event| match event {
        Ok(frame) => Ok(JsValue::from_str(&frame)),
        Err(error) => Err(JsValue::from_str(&error)),
    });
    Ok(ReadableStream::from_stream(stream).into_raw())
}

fn js_error(context: &str, error: impl std::fmt::Display) -> JsError {
    JsError::new(&format!("{context}: {error}"))
}

#[cfg(test)]
mod tests {
    use super::APP_SERVER_ALPN;

    #[test]
    fn app_server_alpn_matches_native_contract() {
        assert_eq!(APP_SERVER_ALPN, b"/letta/appserver/0");
    }
}
