use wasm_bindgen::prelude::*;
use web_sys::console;
use serde::{Deserialize, Serialize};
use iroh::{Endpoint, EndpointId};
use std::str::FromStr;

#[wasm_bindgen]
pub struct IrohWasmClient {
    node_id: String,
    relay_url: Option<String>,
    direct_addr: Option<String>,
}

#[derive(Serialize, Deserialize)]
pub struct ParsedIrohTicket {
    pub node_id: String,
    pub public_key_valid: bool,
    pub direct_addr: Option<String>,
    pub relay_url: Option<String>,
}

#[wasm_bindgen]
impl IrohWasmClient {
    #[wasm_bindgen(constructor)]
    pub fn new(ticket_or_url: &str) -> Result<IrohWasmClient, JsValue> {
        console_error_panic_hook::set_once();
        console::log_1(&format!("Initializing Iroh 1.0 Wasm Client with: {}", ticket_or_url).into());
        let parsed = Self::parse_ticket_internal(ticket_or_url);
        
        Ok(IrohWasmClient {
            node_id: parsed.node_id,
            relay_url: parsed.relay_url,
            direct_addr: parsed.direct_addr,
        })
    }

    #[wasm_bindgen]
    pub fn parse_ticket(ticket_str: &str) -> JsValue {
        let parsed = Self::parse_ticket_internal(ticket_str);
        serde_wasm_bindgen::to_value(&parsed).unwrap_or(JsValue::NULL)
    }

    fn parse_ticket_internal(ticket_str: &str) -> ParsedIrohTicket {
        let trimmed = ticket_str.trim();
        let body = if trimmed.starts_with("iroh://") {
            trimmed.trim_start_matches("iroh://")
        } else {
            trimmed
        };

        let (node_part, addr_part) = if let Some((node, addr)) = body.split_once('@') {
            (node, Some(addr.to_string()))
        } else {
            (body, None)
        };

        let pk_valid = EndpointId::from_str(node_part).is_ok();

        ParsedIrohTicket {
            node_id: node_part.to_string(),
            public_key_valid: pk_valid,
            direct_addr: addr_part,
            relay_url: None,
        }
    }

    #[wasm_bindgen]
    pub fn get_node_id(&self) -> String {
        self.node_id.clone()
    }

    #[wasm_bindgen]
    pub fn get_direct_addr(&self) -> Option<String> {
        self.direct_addr.clone()
    }

    /// Connect to a remote Iroh Node over P2P (via Iroh Relays) and execute an RPC turn
    #[wasm_bindgen]
    pub async fn dial_and_send(&self, target_endpoint_id: &str, alpn: &str, payload: &str) -> Result<String, JsValue> {
        console::log_1(&format!("Creating Iroh Wasm Endpoint (preset N0)...").into());
        
        let endpoint_id = EndpointId::from_str(target_endpoint_id)
            .map_err(|e| JsValue::from_str(&format!("Invalid endpoint id: {}", e)))?;

        let endpoint = Endpoint::builder(iroh::endpoint::presets::N0)
            .alpns(vec![alpn.as_bytes().to_vec()])
            .bind()
            .await
            .map_err(|e| JsValue::from_str(&format!("Failed to bind Iroh Wasm endpoint: {}", e)))?;

        console::log_1(&format!("Connecting to Iroh peer {} over ALPN {}...", target_endpoint_id, alpn).into());
        
        let conn = endpoint.connect(endpoint_id, alpn.as_bytes())
            .await
            .map_err(|e| JsValue::from_str(&format!("Failed to connect to Iroh peer: {}", e)))?;

        console::log_1(&"Iroh connection established! Opening bi-directional stream...".into());
        
        let (mut send, mut recv) = conn.open_bi()
            .await
            .map_err(|e| JsValue::from_str(&format!("Failed to open bi-directional stream: {}", e)))?;

        send.write_all(payload.as_bytes())
            .await
            .map_err(|e| JsValue::from_str(&format!("Failed to write payload: {}", e)))?;
            
        send.finish()
            .map_err(|e| JsValue::from_str(&format!("Failed to finish send stream: {}", e)))?;

        let buf = recv.read_to_end(1024 * 1024)
            .await
            .map_err(|e| JsValue::from_str(&format!("Failed to read response: {}", e)))?;

        let response_str = String::from_utf8(buf)
            .map_err(|e| JsValue::from_str(&format!("Invalid UTF-8 in response: {}", e)))?;

        Ok(response_str)
    }
}
