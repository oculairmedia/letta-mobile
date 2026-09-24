//! Pen input for the desktop canvas.
//!
//! AWT never reports a stylus: Compose Desktop's runtime has no stylus path at all, so a tablet
//! reaches the JVM as an ordinary mouse at best, and on some machines not at all. This bridge
//! reads the pen from the OS itself — Windows Ink (RealTimeStylus) through `octotablet` — and
//! hands the events to the JVM, which turns them into input the canvas can use.
//!
//! The JVM side polls: `nativePoll` drains whatever has arrived since the last call into a flat
//! float array, four floats per event, so one JNI call carries a whole frame of pen motion.

use jni::objects::JClass;
use jni::sys::{jfloatArray, jlong};
use jni::JNIEnv;

/// Event kinds, as sent to the JVM. Kept as floats so one array carries everything.
const KIND_DOWN: f32 = 0.0;
const KIND_UP: f32 = 1.0;
const KIND_MOVE: f32 = 2.0;
const KIND_IN: f32 = 3.0;
const KIND_OUT: f32 = 4.0;

/// Floats per event: kind, x, y, pressure, tool.
const STRIDE: usize = 5;

/// Which nib is in use. The eraser end of a stylus is a tool in its own right, so flipping the pen
/// over is something the app can see rather than something it has to be told.
const TOOL_UNKNOWN: f32 = -1.0;
const TOOL_DRAW: f32 = 0.0;
const TOOL_ERASER: f32 = 1.0;
const TOOL_EMULATED: f32 = 2.0;

/// Pressure we report when the tool does not have a pressure axis.
const NO_PRESSURE: f32 = -1.0;

#[cfg(windows)]
mod platform {
    use super::*;
    use octotablet::builder::Builder;
    use octotablet::events::{Event, ToolEvent};
    use octotablet::tool::Type as ToolType;
    use octotablet::Manager;
    use raw_window_handle::{
        DisplayHandle, HandleError, HasDisplayHandle, HasWindowHandle, RawWindowHandle,
        Win32WindowHandle, WindowHandle,
    };
    use std::num::NonZeroIsize;

    /// The AWT window we were handed, as something `octotablet` can build against.
    ///
    /// The JVM owns this window and outlives the manager: the bridge is disposed when the window
    /// closes, which is what makes `build_raw` sound here.
    struct AwtWindow(NonZeroIsize);

    impl HasWindowHandle for AwtWindow {
        fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
            let handle = Win32WindowHandle::new(self.0);
            // SAFETY: the HWND belongs to the AWT window that asked for this bridge, and the
            // bridge is disposed before that window goes away.
            unsafe { Ok(WindowHandle::borrow_raw(RawWindowHandle::Win32(handle))) }
        }
    }

    impl HasDisplayHandle for AwtWindow {
        fn display_handle(&self) -> Result<DisplayHandle<'_>, HandleError> {
            Ok(DisplayHandle::windows())
        }
    }

    pub struct Bridge {
        /// The window the manager claims Ink on, kept so a broken manager can be rebuilt.
        hwnd: NonZeroIsize,
        /// None only between tearing a broken manager down and a rebuild that did not succeed.
        manager: Option<Manager>,
        /// The last position seen, so Down and Up — which carry no coordinates of their own —
        /// can be reported where the pen actually was.
        last_position: [f32; 2],
        last_pressure: f32,
    }

    impl Bridge {
        pub fn new(hwnd: isize) -> Option<Self> {
            let hwnd = NonZeroIsize::new(hwnd)?;
            let manager = build(hwnd)?;
            Some(Self {
                hwnd,
                manager: Some(manager),
                last_position: [0.0, 0.0],
                last_pressure: NO_PRESSURE,
            })
        }

        /// Throws the manager away and builds a new one on the same window, after it panicked.
        ///
        /// octotablet 0.1 can panic inside `pump` while it holds its shared frame's lock (seen
        /// live: "removal index (is 5) should be < len (is 4)" as it drops tablets Windows
        /// removed). That lock is then poisoned, and every later pump quietly gets no events:
        /// the pen was dead for the rest of the session, and touch stopped with it. Dropping the
        /// manager disables its RealTimeStylus and removes its plugin, so the new one starts
        /// from nothing, as at launch. Returns where the pen last was, to report it lifted.
        pub fn rebuild(&mut self) -> [f32; 2] {
            if let Some(broken) = self.manager.take() {
                // Its drop glue may trip over the same bad state; that must not stop the rebuild.
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || drop(broken)));
            }
            self.manager = build(self.hwnd);
            self.last_pressure = NO_PRESSURE;
            self.last_position
        }

        /// Everything that has happened since the last call, flattened.
        pub fn drain(&mut self) -> Vec<f32> {
            let mut out = Vec::new();
            let Some(manager) = self.manager.as_mut() else {
                // A rebuild that failed is tried again, once a frame, rather than given up on.
                self.manager = build(self.hwnd);
                return out;
            };
            let events = match manager.pump() {
                Ok(events) => events,
                Err(_) => return out,
            };
            for event in events {
                let Event::Tool { tool, event } = event else { continue };
                let kind_of_tool = match tool.tool_type {
                    Some(ToolType::Eraser) => TOOL_ERASER,
                    Some(ToolType::Pen | ToolType::Pencil | ToolType::Brush | ToolType::Airbrush) => TOOL_DRAW,
                    Some(ToolType::Emulated) => TOOL_EMULATED,
                    Some(_) => TOOL_UNKNOWN,
                    None => TOOL_UNKNOWN,
                };
                match event {
                    ToolEvent::Pose(pose) => {
                        self.last_position = pose.position;
                        self.last_pressure = pose.pressure.get().unwrap_or(NO_PRESSURE);
                        push(&mut out, encode_event(KIND_MOVE, self.last_position, self.last_pressure, kind_of_tool));
                    }
                    ToolEvent::Down => {
                        push(&mut out, encode_event(KIND_DOWN, self.last_position, self.last_pressure, kind_of_tool))
                    }
                    ToolEvent::Up => {
                        push(&mut out, encode_event(KIND_UP, self.last_position, self.last_pressure, kind_of_tool))
                    }
                    ToolEvent::In { .. } => {
                        push(&mut out, encode_event(KIND_IN, self.last_position, NO_PRESSURE, kind_of_tool))
                    }
                    // Removed is a termination event in its own right: octotablet 0.1 does not
                    // promise an Up or an Out before it, so dropping it leaves the pen pressed
                    // for good - the nib lifts and the canvas keeps drawing.
                    ToolEvent::Out | ToolEvent::Removed => {
                        push(&mut out, encode_event(KIND_OUT, self.last_position, NO_PRESSURE, kind_of_tool))
                    }
                    _ => {}
                }
            }
            out
        }
    }

    /// A manager on [hwnd], with the defaults the standalone probe uses. The probe receives this
    /// tablet; the app did not, so the bridge stops differing from the thing that works. Mouse
    /// emulation stays on: the JVM side can tell an emulated tool from a real one by its lack of
    /// pressure, and turning it off was one of two differences between us and a working reference.
    fn build(hwnd: NonZeroIsize) -> Option<Manager> {
        // SAFETY: see AwtWindow.
        unsafe { Builder::default().build_raw(AwtWindow(hwnd)) }.ok()
    }

    /// One event in the flattened layout the Kotlin side reads: kind, x, y, pressure, tool.
    fn encode_event(kind: f32, position: [f32; 2], pressure: f32, tool: f32) -> [f32; STRIDE] {
        [kind, position[0], position[1], pressure, tool]
    }

    fn push(out: &mut Vec<f32>, event: [f32; STRIDE]) {
        out.extend_from_slice(&event);
    }
}

#[cfg(not(windows))]
mod platform {
    /// Pen input is Windows-only for now: that is where the tablet is, and where AWT's silence
    /// was measured. The bridge reports "no device" everywhere else rather than pretending.
    pub struct Bridge;

    impl Bridge {
        pub fn new(_hwnd: isize) -> Option<Self> {
            None
        }

        pub fn drain(&mut self) -> Vec<f32> {
            Vec::new()
        }

        pub fn rebuild(&mut self) -> [f32; 2] {
            [0.0, 0.0]
        }
    }
}

use platform::Bridge;

/// Opens a bridge against the AWT window's native handle. Returns 0 when there is no tablet
/// service to talk to, which the JVM side treats as "no pen on this machine".
#[no_mangle]
pub extern "system" fn Java_com_letta_mobile_desktop_input_TabletBridge_nativeOpen(
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) -> jlong {
    // As in nativePoll: a panic here would abort the JVM rather than unwind into it.
    match std::panic::catch_unwind(|| Bridge::new(hwnd as isize)) {
        Ok(Some(bridge)) => Box::into_raw(Box::new(bridge)) as jlong,
        Ok(None) => 0,
        Err(_) => {
            eprintln!("TABLET: the native bridge panicked while opening; reporting no tablet");
            0
        }
    }
}

/// Drains pending events into a flat array of [kind, x, y, pressure].
#[no_mangle]
pub extern "system" fn Java_com_letta_mobile_desktop_input_TabletBridge_nativePoll(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloatArray {
    let empty = env.new_float_array(0).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut());
    if handle == 0 {
        return empty;
    }
    // SAFETY: the handle is one we returned from nativeOpen and the JVM side does not use it
    // after nativeClose.
    let bridge = unsafe { &mut *(handle as *mut Bridge) };
    match drain_or_recover(bridge) {
        Some(events) => to_java(&env, &events).unwrap_or(empty),
        None => empty,
    }
}

/// Everything since the last poll, or what a panic leaves behind.
///
/// A panic must not cross back into the JVM. The poll is an `extern "system"` function, so an
/// unwind through it is undefined and Rust aborts the process instead - the whole app dies with a
/// bare NTSTATUS and no stack worth reading. A tablet that misbehaves for one frame should cost that
/// frame, not the session, so a panic here becomes a new manager, since the old one is left broken
/// (see Bridge::rebuild), and the pen reported lifted: whatever Up that frame held is gone, and a
/// stroke left open would keep drawing. None when even the rebuild panicked; the next frame tries again.
fn drain_or_recover(bridge: &mut Bridge) -> Option<Vec<f32>> {
    if let Ok(events) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| bridge.drain())) {
        return Some(events);
    }
    eprintln!("TABLET: the native bridge panicked while draining; rebuilding it");
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| bridge.rebuild())) {
        Ok(at) => Some(lifted_at(at)),
        Err(_) => {
            eprintln!("TABLET: rebuilding the native bridge panicked too; trying again next frame");
            None
        }
    }
}

/// An Up then an Out at [at]: the pen lifted and left, wherever it was last seen.
fn lifted_at(at: [f32; 2]) -> Vec<f32> {
    let mut out = Vec::with_capacity(2 * STRIDE);
    out.extend_from_slice(&[KIND_UP, at[0], at[1], NO_PRESSURE, TOOL_UNKNOWN]);
    out.extend_from_slice(&[KIND_OUT, at[0], at[1], NO_PRESSURE, TOOL_UNKNOWN]);
    out
}

/// [events] as a Java float array, or None when the JVM could not take it.
fn to_java(env: &JNIEnv, events: &[f32]) -> Option<jfloatArray> {
    let array = env.new_float_array(events.len() as i32).ok()?;
    env.set_float_array_region(&array, 0, events).ok()?;
    Some(array.into_raw())
}

/// Closes the bridge. Safe to call twice; the JVM side zeroes its handle.
#[no_mangle]
pub extern "system" fn Java_com_letta_mobile_desktop_input_TabletBridge_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: as above; this consumes the box the handle came from.
    unsafe { drop(Box::from_raw(handle as *mut Bridge)) };
}
