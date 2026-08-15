pub mod frame;
mod session;

pub use session::IrohAppServerSession;

use wasm_bindgen::prelude::wasm_bindgen;

#[wasm_bindgen(start)]
fn start() {
    console_error_panic_hook::set_once();
}
