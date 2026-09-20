//! Standalone pen probe.
//!
//! Opens a plain window and prints every tablet event. This exists to answer one question with no
//! other variables in play: does `octotablet` see this tablet on this machine at all? If it does,
//! the fault is in how the app integrates it. If it does not, the crate or the driver is the
//! problem and no amount of work inside the app will help.
//!
//! Run with: cargo run --example probe

#[cfg(windows)]
use std::sync::Arc;

/// `octotablet` is declared only for Windows, so everything below it is too: without this gate a
/// plain `cargo build --examples` on any other host fails to resolve the crate.
#[cfg(windows)]
fn main() {
    let event_loop = winit::event_loop::EventLoopBuilder::<()>::default()
        .build()
        .expect("event loop");
    let window = Arc::new(
        winit::window::WindowBuilder::default()
            .with_title("Letta pen probe - draw here, watch the console")
            .with_inner_size(winit::dpi::PhysicalSize::new(800, 600))
            .build(&event_loop)
            .expect("window"),
    );

    let mut manager = match octotablet::builder::Builder::default().build_shared(&window) {
        Ok(manager) => manager,
        Err(error) => {
            println!("PROBE: could not open a tablet connection: {error:?}");
            return;
        }
    };
    println!("PROBE: backend = {:?}", manager.backed());
    println!("PROBE: tablets = {}", manager.tablets().len());
    println!("PROBE: tools   = {}", manager.tools().len());
    println!("PROBE: window open. Touch the pen to the tablet over this window.");

    let mut seen = 0u32;
    event_loop
        .run(move |event, target| {
            if should_exit_probe(&event) {
                target.exit();
                return;
            }
            if event == winit::event::Event::AboutToWait {
                pump_probe_events(&mut manager, &mut seen);
            }
        })
        .expect("run");
}

#[cfg(windows)]
fn should_exit_probe(event: &winit::event::Event<()>) -> bool {
    matches!(
        event,
        winit::event::Event::WindowEvent {
            event: winit::event::WindowEvent::CloseRequested,
            ..
        }
    )
}

#[cfg(windows)]
fn pump_probe_events(manager: &mut octotablet::Manager, seen: &mut u32) {
    let Ok(events) = manager.pump();
    for event in events {
        if let octotablet::events::Event::Tool { event, .. } = event {
            log_tool_event(event, seen);
        }
    }
    std::thread::sleep(std::time::Duration::from_millis(5));
}

#[cfg(windows)]
fn log_tool_event(event: octotablet::events::ToolEvent, seen: &mut u32) {
    match event {
        octotablet::events::ToolEvent::Pose(pose) => {
            *seen += 1;
            if *seen % 20 == 1 {
                println!(
                    "PROBE: pose at {:?} pressure={:?}",
                    pose.position,
                    pose.pressure.get()
                );
            }
        }
        octotablet::events::ToolEvent::Down => println!("PROBE: DOWN"),
        octotablet::events::ToolEvent::Up => println!("PROBE: UP"),
        octotablet::events::ToolEvent::In { .. } => println!("PROBE: IN range"),
        octotablet::events::ToolEvent::Out => println!("PROBE: OUT of range"),
        octotablet::events::ToolEvent::Added => println!("PROBE: tool added"),
        _ => {}
    }
}

#[cfg(not(windows))]
fn main() {
    eprintln!("The pen probe is Windows-only: it reads Windows Ink through octotablet.");
}
