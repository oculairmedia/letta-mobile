//! Standalone pen probe.
//!
//! Opens a plain window and prints every tablet event. This exists to answer one question with no
//! other variables in play: does `octotablet` see this tablet on this machine at all? If it does,
//! the fault is in how the app integrates it. If it does not, the crate or the driver is the
//! problem and no amount of work inside the app will help.
//!
//! Run with: cargo run --example probe

use std::sync::Arc;

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
            match event {
                winit::event::Event::WindowEvent {
                    event: winit::event::WindowEvent::CloseRequested,
                    ..
                } => {
                    target.exit();
                    return;
                }
                // Pump once per idle turn rather than per event, and sleep between turns: a bare
                // Poll loop spins a core flat out and Windows paints the window as "not
                // responding" even though it is fine.
                winit::event::Event::AboutToWait => {}
                _ => return,
            }

            let Ok(events) = manager.pump() else {
                std::thread::sleep(std::time::Duration::from_millis(5));
                return;
            };
            for event in events {
                if let octotablet::events::Event::Tool { event, .. } = event {
                    match event {
                        octotablet::events::ToolEvent::Pose(pose) => {
                            seen += 1;
                            if seen % 20 == 1 {
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
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        })
        .expect("run");
}
