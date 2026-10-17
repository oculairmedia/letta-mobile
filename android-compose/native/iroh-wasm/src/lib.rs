pub mod frame;
pub mod negotiation;
mod relay;
mod session;

pub use relay::IrohCanvasRelayStream;
pub use session::IrohAppServerSession;

use wasm_bindgen::prelude::wasm_bindgen;

#[wasm_bindgen(start)]
fn start() {
    console_error_panic_hook::set_once();
}
