//! Pen input for the desktop canvas.
//!
//! AWT never reports a stylus: Compose Desktop's runtime has no stylus path at all, so a tablet
//! reaches the JVM as an ordinary mouse at best, and on some machines not at all. This bridge
//! reads the pen from the OS itself — Windows Ink (RealTimeStylus) through `octotablet` — and
//! hands the events to the JVM, which turns them into input the canvas can use.
//!
//! The JVM side polls: `nativePoll` drains whatever has arrived since the last call into a flat
//! float array, six floats per event, so one JNI call carries a whole frame of pen motion.

use jni::objects::JClass;
use jni::sys::{jboolean, jfloatArray, jlong};
use jni::JNIEnv;

/// Event kinds, as sent to the JVM. Kept as floats so one array carries everything.
const KIND_DOWN: f32 = 0.0;
const KIND_UP: f32 = 1.0;
const KIND_MOVE: f32 = 2.0;
const KIND_IN: f32 = 3.0;
const KIND_OUT: f32 = 4.0;
/// Drops a contact without a tap or a fling. An Up would do one of those.
const KIND_CANCEL: f32 = 5.0;

/// Floats per event: kind, x, y, pressure, tool, contact.
const STRIDE: usize = 6;

/// Which nib is in use. The eraser end of a stylus is a tool in its own right, so flipping the pen
/// over is something the app can see rather than something it has to be told.
const TOOL_UNKNOWN: f32 = -1.0;
const TOOL_DRAW: f32 = 0.0;
const TOOL_ERASER: f32 = 1.0;
const TOOL_EMULATED: f32 = 2.0;
/// A finger from the pointer stack, not a stylus. Ink must not be asked to report it.
const TOOL_TOUCH: f32 = 3.0;

/// Pressure we report when the tool does not have a pressure axis.
const NO_PRESSURE: f32 = -1.0;

#[cfg(windows)]
mod platform {
    use super::*;
    use octotablet::builder::Builder;
    use octotablet::events::{Event, ToolEvent};
    use octotablet::tool::ID as ToolId;
    use octotablet::tool::Type as ToolType;
    use std::collections::HashMap;
use octotablet::Manager;
use raw_window_handle::{
    DisplayHandle, HandleError, HasDisplayHandle, HasWindowHandle, RawWindowHandle,
    Win32WindowHandle, WindowHandle,
};
use std::num::NonZeroIsize;
use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU8, AtomicUsize, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

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
        /// One RealTimeStylus per pen tablet. A failed entry is retried next frame.
        managers: Vec<InkManager>,
        /// Where each tool last was. Down and Up carry no coordinates, and one shared
        /// position makes a second finger jump to the first. The ink index is part of the
        /// key because two stylus services can reuse the same cursor id.
        tools: HashMap<ToolKey, ToolRuntime>,
        next_slot: f32,
        /// The last position any tool reported, so a rebuilt bridge can still lift the pen.
        last_seen: [f32; 2],
        /// Pointer-touch contacts copied into this window, keyed by pointer id.
        touch_state: Mutex<TouchState>,
        touch_generation: AtomicU64,
        touch_generation_seen: u64,
        touch_down: HashMap<u32, [f32; 2]>,
        /// True once a real touch frame has been seen. Until then, a pressureless Ink sample
        /// is still the only finger we have, so one-finger pan keeps working.
        touch_latched: bool,
        /// Milliseconds of the last pointer frame this bridge copied.
        last_pointer_millis: u64,
        /// After the pointer frames go quiet, the next Ink finger needs a down or it is ignored.
        rearm_finger: bool,
        /// Contact count last written to the log, so a still finger does not reprint every frame.
        logged_contacts: Option<usize>,
        shared_seen: u64,
        /// True when this machine has no pen tablet, so Ink is left off and drain does not
        /// rebuild a manager every frame.
        ink_off: bool,
        /// Debug escape hatch: every tablet, including the touchscreen.
        ink_all: bool,
        /// The pen tablet that first reported a pressure axis. The others are ignored.
        pen_latch: Option<i32>,
        pressureless_logged: Vec<i32>,
        duplicate_logged: Vec<i32>,
    }

    struct InkManager {
        index: i32,
        name: String,
        manager: Option<Manager>,
    }

    #[derive(Clone, PartialEq, Eq, Hash)]
    struct ToolKey {
        ink: i32,
        id: ToolId,
    }

    struct TouchState {
        positions: HashMap<u32, [f32; 2]>,
        ups: Vec<u32>,
    }

    struct ToolRuntime {
        position: [f32; 2],
        pressure: f32,
        slot: f32,
        /// Down arrived before any pose, so it must not be emitted at the origin.
        awaiting_pose: bool,
    }

    enum Pending {
        Pose {
            ink: i32,
            id: ToolId,
            tool: f32,
            position: [f32; 2],
            pressure: f32,
        },
        At {
            ink: i32,
            id: ToolId,
            tool: f32,
            kind: f32,
            clear: bool,
        },
    }

    impl Bridge {
        pub fn new(hwnd: isize) -> Option<Self> {
            let hwnd = NonZeroIsize::new(hwnd)?;
            log_build_tag();
            let plan = ink_plan();
            let managers = plan.slots.iter().map(|(index, name)| InkManager {
                index: *index,
                name: name.clone(),
                manager: if plan.ink_off { None } else { build_manager(hwnd, *index, name) },
            }).collect();
            LIVE_BRIDGES.fetch_add(1, Ordering::AcqRel);
            ensure_pointer_hook(hwnd);
            Some(Self {
                hwnd,
                managers,
                tools: HashMap::new(),
                next_slot: 0.0,
                last_seen: [0.0, 0.0],
                touch_state: Mutex::new(TouchState { positions: HashMap::new(), ups: Vec::new() }),
                touch_generation: AtomicU64::new(0),
                touch_generation_seen: 0,
                touch_down: HashMap::new(),
                touch_latched: false,
                last_pointer_millis: 0,
                rearm_finger: false,
                logged_contacts: None,
                shared_seen: 0,
                ink_off: plan.ink_off,
                ink_all: plan.ink_all,
                pen_latch: None,
                pressureless_logged: Vec::new(),
                duplicate_logged: Vec::new(),
            })
        }

        /// Throws the manager away and builds a new one on the same window, after it panicked.
        ///
        /// octotablet 0.1 can panic inside `pump` while it holds its shared frame's lock (seen
        /// live: "removal index (is 5) should be < len (is 4)" as it drops tablets Windows
        /// removed). That lock is then poisoned, and every later pump quietly gets no events:
        /// the pen was dead for the rest of the session, and touch stopped with it. Dropping the
        /// manager disables its RealTimeStylus and removes its plugin, so the new one starts
        /// from nothing, as at launch. Returns an Up and an Out for every contact still down,
        /// so a finger the panic interrupted is not left pinching the board.
        pub fn rebuild(&mut self) -> Vec<f32> {
            let broken: Vec<Manager> = self.managers.iter_mut().filter_map(|slot| slot.manager.take()).collect();
            if !broken.is_empty() {
                // Its drop glue may trip over the same bad state; that must not stop the rebuild.
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || drop(broken)));
            }
            let mut out = Vec::new();
            if self.tools.is_empty() {
                push(&mut out, encode_event(KIND_UP, self.last_seen, NO_PRESSURE, TOOL_UNKNOWN, 0.0));
                push(&mut out, encode_event(KIND_OUT, self.last_seen, NO_PRESSURE, TOOL_UNKNOWN, 0.0));
            } else {
                for runtime in self.tools.values() {
                    push(&mut out, encode_event(KIND_UP, runtime.position, NO_PRESSURE, TOOL_UNKNOWN, runtime.slot));
                    push(&mut out, encode_event(KIND_OUT, runtime.position, NO_PRESSURE, TOOL_UNKNOWN, runtime.slot));
                }
            }
            if !self.ink_off {
                for slot in &mut self.managers {
                    slot.manager = build_manager(self.hwnd, slot.index, &slot.name);
                }
            }
            self.tools.clear();
            self.next_slot = 0.0;
            self.pen_latch = None;
            out
        }

        /// Everything that has happened since the last call, flattened.
        pub fn drain(&mut self) -> Vec<f32> {
            // Pump borrows the manager, which borrows self. Copy the samples out
            // before updating per-tool state, or the two borrows cannot coexist.
            let mut pending = Vec::new();
            for slot in &mut self.managers {
                if slot.manager.is_none() {
                    if !self.ink_off {
                        slot.manager = build_manager(self.hwnd, slot.index, &slot.name);
                    }
                    continue;
                }
                let ink = slot.index;
                if let Ok(events) = slot.manager.as_mut().expect("manager checked").pump() {
                    for event in events {
                        let Event::Tool { tool, event } = event else { continue };
                        let kind_of_tool = match tool.tool_type {
                            Some(ToolType::Eraser) => TOOL_ERASER,
                            Some(ToolType::Pen | ToolType::Pencil | ToolType::Brush | ToolType::Airbrush) => TOOL_DRAW,
                            Some(ToolType::Emulated) => TOOL_EMULATED,
                            Some(_) => TOOL_UNKNOWN,
                            None => TOOL_UNKNOWN,
                        };
                        let id = tool.id();
                        match event {
                            ToolEvent::Pose(pose) => pending.push(Pending::Pose {
                                ink,
                                id,
                                tool: kind_of_tool,
                                position: pose.position,
                                pressure: pose.pressure.get().unwrap_or(NO_PRESSURE),
                            }),
                            ToolEvent::Down => pending.push(Pending::At { ink, id, tool: kind_of_tool, kind: KIND_DOWN, clear: false }),
                            ToolEvent::Up => pending.push(Pending::At { ink, id, tool: kind_of_tool, kind: KIND_UP, clear: false }),
                            ToolEvent::In { .. } => pending.push(Pending::At { ink, id, tool: kind_of_tool, kind: KIND_IN, clear: false }),
                            // Removed is a termination event in its own right: octotablet 0.1 does not
                            // promise an Up or an Out before it, so dropping it leaves the pen pressed
                            // for good - the nib lifts and the canvas keeps drawing.
                            ToolEvent::Out | ToolEvent::Removed => {
                                pending.push(Pending::At { ink, id, tool: kind_of_tool, kind: KIND_OUT, clear: true })
                            }
                            _ => {}
                        }
                    }
                }
            }
            let mut out = Vec::new();
            // Pointer frames first. They own the finger once they are flowing, and the Ink copy
            // of that finger is cancelled so it is not a second point and does not fling.
            self.emit_pointer_touch(&mut out);
            if self.touch_latched && !self.pointer_is_current() {
                self.touch_latched = false;
                self.rearm_finger = true;
            }
            let ink_finger_suppressed = self.touch_latched;
            for item in pending {
                match item {
                    Pending::Pose { ink, id, tool, position, pressure } => {
                        let (latch, fate) = classify_ink_sample(self.pen_latch, ink, pressure, !self.ink_all);
                        if self.pen_latch.is_none() && latch.is_some() {
                            self.pen_latch = latch;
                            let name = self.managers.iter().find(|slot| slot.index == ink).map(|slot| slot.name.as_str()).unwrap_or("");
                            eprintln!("TABLET: pen latched ink #{ink} \"{name}\"");
                        } else {
                            self.pen_latch = latch;
                        }
                        if !self.accept_ink(ink, fate) {
                            continue;
                        }
                        let (slot, emit_down) = {
                            let runtime = self.runtime(ink, id);
                            runtime.position = position;
                            runtime.pressure = pressure;
                            let emit_down = runtime.awaiting_pose && pressure != NO_PRESSURE;
                            runtime.awaiting_pose = false;
                            (runtime.slot, emit_down)
                        };
                        self.last_seen = position;
                        if self.ink_all && ink_finger_suppressed && pressure == NO_PRESSURE {
                            continue;
                        }
                        if self.ink_all && self.rearm_finger && pressure == NO_PRESSURE {
                            push(&mut out, encode_event(KIND_DOWN, position, NO_PRESSURE, tool, slot));
                            self.rearm_finger = false;
                        }
                        // The down was held because it arrived with no pose. Emit it at this
                        // point so the stroke starts where the pen actually is.
                        if emit_down {
                            push(&mut out, encode_event(KIND_DOWN, position, pressure, tool, slot));
                        }
                        push(&mut out, encode_event(KIND_MOVE, position, pressure, tool, slot));
                    }
                    Pending::At { ink, id, tool, kind, clear } => {
                        if let Some(winner) = self.pen_latch {
                            if winner != ink {
                                self.note_duplicate(ink);
                                continue;
                            }
                        }
                        let key = ToolKey { ink, id: id.clone() };
                        let (encoded, pressure) = {
                            let runtime = self.runtime(ink, id);
                            if kind == KIND_DOWN && pen_down_needs_a_pose(runtime.pressure) {
                                runtime.awaiting_pose = true;
                                (None, NO_PRESSURE)
                            } else {
                                if kind == KIND_DOWN {
                                    runtime.awaiting_pose = false;
                                }
                                let pressure = if kind == KIND_IN || kind == KIND_OUT { NO_PRESSURE } else { runtime.pressure };
                                (Some(encode_event(kind, runtime.position, pressure, tool, runtime.slot)), pressure)
                            }
                        };
                        if clear {
                            self.tools.remove(&key);
                        }
                        let Some(encoded) = encoded else { continue };
                        if self.ink_all && ink_finger_suppressed && pressure == NO_PRESSURE {
                            continue;
                        }
                        if self.ink_all && self.rearm_finger && pressure == NO_PRESSURE && kind == KIND_DOWN {
                            self.rearm_finger = false;
                        }
                        push(&mut out, encoded);
                    }
                }
            }
            out
        }

        fn pointer_is_current(&self) -> bool {
            self.last_pointer_millis > 0 && now_millis().saturating_sub(self.last_pointer_millis) < POINTER_FRAME_GAP_MILLIS
        }

        /// The pointer's window, or a child of it. A touch aimed at the frame still belongs to the canvas.
        fn targets_touch(&self, hwnd: isize) -> bool {
            let ours = self.hwnd.get();
            hwnd == ours || unsafe { IsChild(ours, hwnd) } != 0
        }

        /// Copies the latest pointer-touch frame into finger events with their own contact ids.
        fn emit_pointer_touch(&mut self, out: &mut Vec<f32>) {
            self.absorb_shared_touch();
            let generation = self.touch_generation.load(Ordering::Acquire);
            if generation == self.touch_generation_seen {
                return;
            }
            self.touch_generation_seen = generation;
            let (positions, ups) = {
                let mut state = self.touch_state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                let positions = state.positions.clone();
                let ups = std::mem::take(&mut state.ups);
                (positions, ups)
            };
            if !positions.is_empty() && !self.touch_latched {
                self.cancel_pressureless_tools(out);
                self.touch_latched = true;
            }
            if self.logged_contacts != Some(positions.len()) {
                self.logged_contacts = Some(positions.len());
                eprintln!(
                    "TABLET: touch frame contacts={} source={} bridge={}",
                    positions.len(),
                    touch_source_name(),
                    self.hwnd.get()
                );
            }
            for id in ups {
                if let Some(pos) = self.touch_down.remove(&id) {
                    push(out, encode_event(KIND_UP, pos, NO_PRESSURE, TOOL_TOUCH, touch_slot(id)));
                }
            }
            for (id, pos) in positions {
                let slot = touch_slot(id);
                match self.touch_down.get(&id).copied() {
                    Some(prev) if prev == pos => {}
                    Some(_) => {
                        self.touch_down.insert(id, pos);
                        push(out, encode_event(KIND_MOVE, pos, NO_PRESSURE, TOOL_TOUCH, slot));
                    }
                    None => {
                        self.touch_down.insert(id, pos);
                        push(out, encode_event(KIND_DOWN, pos, NO_PRESSURE, TOOL_TOUCH, slot));
                        push(out, encode_event(KIND_MOVE, pos, NO_PRESSURE, TOOL_TOUCH, slot));
                    }
                }
            }
        }

        /// Ink's copy of a finger is not a lift. An Up would fling or tap as the real fingers arrive.
        fn cancel_pressureless_tools(&mut self, out: &mut Vec<f32>) {
            let lifts: Vec<(f32, [f32; 2])> = self
                .tools
                .values()
                .filter(|runtime| runtime.pressure == NO_PRESSURE)
                .map(|runtime| (runtime.slot, runtime.position))
                .collect();
            for (slot, pos) in lifts {
                push(out, encode_event(KIND_CANCEL, pos, NO_PRESSURE, TOOL_UNKNOWN, slot));
            }
        }

        /// Copies the process-wide touch frame into this window's client coordinates.
        fn absorb_shared_touch(&mut self) {
            let (generation, screen) = {
                let shared = shared_touch().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                if shared.generation == self.shared_seen {
                    return;
                }
                (shared.generation, shared.screen.clone())
            };
            self.shared_seen = generation;
            self.last_pointer_millis = now_millis();
            let mut converted = HashMap::new();
            for (id, pos) in screen {
                if !self.targets_touch(pos.hwnd) {
                    continue;
                }
                let mut point = WinPoint { x: pos.x.round() as i32, y: pos.y.round() as i32 };
                unsafe { ScreenToClient(self.hwnd.get(), &mut point) };
                converted.insert(id, [point.x as f32, point.y as f32]);
            }
            {
                let mut state = self.touch_state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                let gone: Vec<u32> = state.positions.keys().copied().filter(|id| !converted.contains_key(id)).collect();
                for id in gone {
                    state.ups.push(id);
                }
                state.positions = converted;
            }
            self.touch_generation.fetch_add(1, Ordering::Release);
        }

        fn runtime(&mut self, ink: i32, id: ToolId) -> &mut ToolRuntime {
            let key = ToolKey { ink, id };
            if !self.tools.contains_key(&key) {
                let slot = self.next_slot;
                self.next_slot += 1.0;
                self.tools.insert(
                    key.clone(),
                    ToolRuntime {
                        position: [0.0, 0.0],
                        pressure: NO_PRESSURE,
                        slot,
                        awaiting_pose: false,
                    },
                );
            }
            self.tools.get_mut(&key).expect("tool runtime inserted above")
        }

        fn accept_ink(&mut self, ink: i32, fate: SampleFate) -> bool {
            match fate {
                SampleFate::Keep => true,
                SampleFate::Pressureless => {
                    if !self.pressureless_logged.contains(&ink) {
                        self.pressureless_logged.push(ink);
                        eprintln!("TABLET: ink #{ink} sent a pressureless contact; ignored");
                    }
                    false
                }
                SampleFate::Duplicate => {
                    self.note_duplicate(ink);
                    false
                }
            }
        }

        fn note_duplicate(&mut self, ink: i32) {
            if self.duplicate_logged.contains(&ink) {
                return;
            }
            self.duplicate_logged.push(ink);
            eprintln!("TABLET: ignoring duplicate pen stream from ink #{ink}");
        }
    }

    impl Drop for Bridge {
        fn drop(&mut self) {
            if LIVE_BRIDGES.fetch_sub(1, Ordering::AcqRel) == 1 {
                let hook = POINTER_HOOK.swap(0, Ordering::AcqRel);
                if hook != 0 {
                    unsafe { UnhookWindowsHookEx(hook) };
                }
                HOOK_STARTED.store(false, Ordering::Release);
            }
        }
    }

    struct InkPlan {
        slots: Vec<(i32, String)>,
        ink_off: bool,
        ink_all: bool,
    }

    fn log_build_tag() {
        static LOGGED: AtomicBool = AtomicBool::new(false);
        if LOGGED.swap(true, Ordering::Relaxed) {
            return;
        }
        eprintln!("TABLET: native tablet_input build={} pid={}", env!("TABLET_BUILD_TAG"), std::process::id());
    }

    /// Which pens get a RealTimeStylus. The touchscreen stays out, even when Windows calls it a pen.
    fn ink_plan() -> InkPlan {
        static LOGGED: AtomicBool = AtomicBool::new(false);
        let tablets = octotablet::builder::ink_tablets();
        let log = !LOGGED.swap(true, Ordering::Relaxed);
        if log {
            let described: Vec<String> = tablets
                .iter()
                .map(|tablet| format!("#{} \"{}\" {}", tablet.index, tablet.name, kind_name(tablet.kind)))
                .collect();
            eprintln!("TABLET: ink tablets: {}", if described.is_empty() { "(none)".into() } else { described.join(", ") });
        }
        if std::env::var("LETTA_TABLET_INK_ALL").ok().as_deref() == Some("1") {
            if log {
                eprintln!("TABLET: ink on all tablets (LETTA_TABLET_INK_ALL)");
            }
            return InkPlan { slots: vec![(-1, "all".to_string())], ink_off: false, ink_all: true };
        }
        let index_filter = std::env::var("LETTA_TABLET_PEN_INDEX").ok().and_then(|value| value.parse().ok());
        let name_filter = std::env::var("LETTA_TABLET_PEN").ok();
        let chosen = choose_ink_tablets(&tablets, name_filter.as_deref(), index_filter);
        if log {
            for tablet in &tablets {
                if chosen.contains(&tablet.index) {
                    continue;
                }
                eprintln!("TABLET: ink skip #{} \"{}\" {}", tablet.index, tablet.name, skip_reason(tablet));
            }
        }
        if chosen.is_empty() {
            if log {
                eprintln!("TABLET: no pen tablet; ink off, fingers from the pointer hook only");
            }
            return InkPlan { slots: Vec::new(), ink_off: true, ink_all: false };
        }
        // A window can only host one RealTimeStylus. Build the named pen first so a later
        // failure does not leave Ink on a HID collection that is not the stylus.
        let mut slots: Vec<(i32, String)> = chosen
            .into_iter()
            .filter_map(|index| {
                let name = tablets.iter().find(|tablet| tablet.index == index).map(|tablet| tablet.name.clone()).unwrap_or_default();
                Some((index, name))
            })
            .collect();
        slots.sort_by_key(|(_, name)| if pen_name_is_preferred(name) { 0 } else { 1 });
        InkPlan { slots, ink_off: false, ink_all: false }
    }

    fn skip_reason(tablet: &octotablet::builder::InkTabletInfo) -> &'static str {
        use octotablet::builder::InkTabletKind;
        match tablet.kind {
            InkTabletKind::Mouse => "mouse",
            InkTabletKind::Touch => "touch",
            InkTabletKind::Pen if pen_name_is_touch(&tablet.name) => "touch digitizer reporting as a pen",
            InkTabletKind::Pen => "not selected",
            InkTabletKind::Unknown => "unknown",
        }
    }

    fn kind_name(kind: octotablet::builder::InkTabletKind) -> &'static str {
        match kind {
            octotablet::builder::InkTabletKind::Mouse => "mouse",
            octotablet::builder::InkTabletKind::Pen => "pen",
            octotablet::builder::InkTabletKind::Touch => "touch",
            octotablet::builder::InkTabletKind::Unknown => "unknown",
        }
    }

    /// Every real pen. A touchscreen that reports itself as a pen is left out.
    fn choose_ink_tablets(
        tablets: &[octotablet::builder::InkTabletInfo],
        name_filter: Option<&str>,
        index_filter: Option<i32>,
    ) -> Vec<i32> {
        if let Some(index) = index_filter {
            return vec![index];
        }
        let pens: Vec<&octotablet::builder::InkTabletInfo> = tablets
            .iter()
            .filter(|tablet| tablet.kind == octotablet::builder::InkTabletKind::Pen && !pen_name_is_touch(&tablet.name))
            .collect();
        if let Some(filter) = name_filter {
            let filter = filter.to_ascii_lowercase();
            return pens.into_iter().filter(|tablet| tablet.name.to_ascii_lowercase().contains(&filter)).map(|tablet| tablet.index).collect();
        }
        pens.into_iter().map(|tablet| tablet.index).collect()
    }

    fn pen_name_is_preferred(name: &str) -> bool {
        let name = name.to_ascii_lowercase();
        name.contains("wacom") || name.contains("cintiq")
    }

    fn pen_name_is_touch(name: &str) -> bool {
        let name = name.to_ascii_lowercase();
        name.contains("virtual multitouch") || name.contains("multitouch") || name.contains("touch")
    }

    enum SampleFate {
        Keep,
        Pressureless,
        Duplicate,
    }

    /// A down with no pose yet is still at the origin with [NO_PRESSURE]. Hold it.
    /// Hover pressure 0.0 already has a pose, so it is emitted.
    fn pen_down_needs_a_pose(pressure: f32) -> bool {
        pressure == NO_PRESSURE
    }

    /// The first pressure-bearing tablet wins. Hover pressure 0.0 counts. A pressureless
    /// pose is never a finger once Ink is limited to pens.
    fn classify_ink_sample(latch: Option<i32>, ink: i32, pressure: f32, pen_only: bool) -> (Option<i32>, SampleFate) {
        if pen_only && pressure == NO_PRESSURE {
            return (latch, SampleFate::Pressureless);
        }
        let latched = if pressure != NO_PRESSURE { latch.or(Some(ink)) } else { latch };
        match latched {
            Some(winner) if winner != ink => (latched, SampleFate::Duplicate),
            _ => (latched, SampleFate::Keep),
        }
    }

    fn build_manager(hwnd: NonZeroIsize, ink_index: i32, name: &str) -> Option<Manager> {
        let mut builder = Builder::default();
        if ink_index >= 0 {
            builder = builder.ink_single_tablet(Some(ink_index));
        }
        // SAFETY: see AwtWindow.
        match unsafe { builder.build_raw(AwtWindow(hwnd)) } {
            Ok(manager) => {
                eprintln!("TABLET: ink manager hwnd={} #{ink_index} \"{name}\" ok", hwnd.get());
                Some(manager)
            }
            Err(error) => {
                eprintln!("TABLET: ink manager hwnd={} #{ink_index} \"{name}\" failed {error}", hwnd.get());
                None
            }
        }
    }

    /// One event in the flattened layout the Kotlin side reads: kind, x, y, pressure, tool, contact.
    fn encode_event(kind: f32, position: [f32; 2], pressure: f32, tool: f32, contact: f32) -> [f32; STRIDE] {
        [kind, position[0], position[1], pressure, tool, contact]
    }

    fn push(out: &mut Vec<f32>, event: [f32; STRIDE]) {
        out.extend_from_slice(&event);
    }

    /// Touch ids sit above Ink's slots so the two never name the same finger.
    fn touch_slot(id: u32) -> f32 {
        TOUCH_CONTACT_BASE + id as f32
    }

const WM_POINTERUPDATE: u32 = 0x0245;
const WM_POINTERDOWN: u32 = 0x0246;
const WM_POINTERUP: u32 = 0x0247;
const WM_POINTERLEAVE: u32 = 0x024A;
const WM_POINTERCAPTURECHANGED: u32 = 0x024C;
const WH_GETMESSAGE: i32 = 3;
const PM_REMOVE: usize = 1;
const PT_TOUCH: u32 = 2;
const POINTER_FLAG_INCONTACT: u32 = 0x0000_0004;
const POINTER_FLAG_DOWN: u32 = 0x0001_0000;
const POINTER_FLAG_UP: u32 = 0x0004_0000;
const TOUCH_CONTACT_BASE: f32 = 10_000.0;
const POINTER_FRAME_GAP_MILLIS: u64 = 300;
const TOUCH_CONTACT_SILENCE_MILLIS: u64 = 250;
const WM_TOUCH: u32 = 0x0240;
const TOUCHEVENTF_MOVE: u32 = 0x0001;
const TOUCHEVENTF_DOWN: u32 = 0x0002;
const TOUCHEVENTF_UP: u32 = 0x0004;
const TOUCHEVENTF_PEN: u32 = 0x0040;
const TOUCHEVENTF_PALM: u32 = 0x0080;
const SOURCE_NONE: u8 = 0;
const SOURCE_WM_TOUCH: u8 = 1;
const SOURCE_WM_POINTER: u8 = 2;
const ERROR_HOOK_NEEDS_HMOD: u32 = 1428;
const GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS: u32 = 0x00000004;
const GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT: u32 = 0x00000002;
static TOUCH_READ_FAILED: AtomicBool = AtomicBool::new(false);
static LOGGED_POINTER_FRAME: AtomicBool = AtomicBool::new(false);
static LOGGED_TWO_FINGERS: AtomicBool = AtomicBool::new(false);
/// While this is in the future, AWT's touch-pan wheels must not also scroll.
/// The hook sets it before the message is dispatched, which is earlier than the poll that marks a window owned.
static TOUCH_GESTURE_UNTIL: AtomicU64 = AtomicU64::new(0);
const TOUCH_GESTURE_HOLD_MILLIS: u64 = 300;
static POINTER_HOOK: AtomicIsize = AtomicIsize::new(0);
static HOOK_STARTED: AtomicBool = AtomicBool::new(false);
static LIVE_BRIDGES: AtomicUsize = AtomicUsize::new(0);
static TOUCH_SOURCE: AtomicU8 = AtomicU8::new(SOURCE_NONE);
static SAW_WM_TOUCH: AtomicBool = AtomicBool::new(false);
static SAW_WM_POINTER: AtomicBool = AtomicBool::new(false);

#[repr(C)]
struct WinPoint {
    x: i32,
    y: i32,
}

#[repr(C)]
struct WinMsg {
    hwnd: isize,
    message: u32,
    _pad: u32,
    wparam: usize,
    lparam: isize,
    time: u32,
    pt_x: i32,
    pt_y: i32,
    lprivate: u32,
}

/// `POINTER_INFO` as Windows lays it out on 64-bit. `ptPixelLocation` is physical screen pixels.
#[repr(C)]
#[derive(Clone, Copy)]
struct PointerInfo {
    pointer_type: u32,
    pointer_id: u32,
    frame_id: u32,
    pointer_flags: u32,
    source_device: isize,
    hwnd_target: isize,
    pixel_x: i32,
    pixel_y: i32,
    himetric_x: i32,
    himetric_y: i32,
    pixel_raw_x: i32,
    pixel_raw_y: i32,
    himetric_raw_x: i32,
    himetric_raw_y: i32,
    time: u32,
    history_count: u32,
    input_data: i32,
    key_states: u32,
    performance_count: u64,
    button_change: i32,
    _pad: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct PointerTouchInfo {
    pointer_info: PointerInfo,
    touch_flags: u32,
    touch_mask: u32,
    contact_left: i32,
    contact_top: i32,
    contact_right: i32,
    contact_bottom: i32,
    contact_raw_left: i32,
    contact_raw_top: i32,
    contact_raw_right: i32,
    contact_raw_bottom: i32,
    orientation: u32,
    pressure: u32,
}

#[derive(Clone, Copy)]
struct ScreenTouch {
    x: f32,
    y: f32,
    hwnd: isize,
    seen_millis: u64,
}

struct SharedTouch {
    screen: HashMap<u32, ScreenTouch>,
    generation: u64,
}

fn shared_touch() -> &'static Mutex<SharedTouch> {
    static SHARED: OnceLock<Mutex<SharedTouch>> = OnceLock::new();
    SHARED.get_or_init(|| Mutex::new(SharedTouch { screen: HashMap::new(), generation: 0 }))
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis() as u64)
        .unwrap_or(0)
}

fn touch_source_name() -> &'static str {
    match TOUCH_SOURCE.load(Ordering::Relaxed) {
        SOURCE_WM_TOUCH => "WM_TOUCH",
        SOURCE_WM_POINTER => "WM_POINTER",
        _ => "none",
    }
}

/// The first family that produces a frame owns touch for the process.
fn claim_touch_source(source: u8) -> bool {
    match TOUCH_SOURCE.compare_exchange(SOURCE_NONE, source, Ordering::AcqRel, Ordering::Acquire) {
        Ok(_) => {
            eprintln!("TABLET: touch source {}", if source == SOURCE_WM_TOUCH { "WM_TOUCH" } else { "WM_POINTER" });
            true
        }
        Err(current) => current == source,
    }
}

fn ensure_pointer_hook(hwnd: NonZeroIsize) {
    if HOOK_STARTED.swap(true, Ordering::AcqRel) {
        return;
    }
    let thread = unsafe { GetWindowThreadProcessId(hwnd.get(), std::ptr::null_mut()) };
    let caller = unsafe { GetCurrentThreadId() };
    // A thread hook on the window's pump sees posted touch messages without replacing
    // the window procedure, so AWT still promotes them and RealTimeStylus is left alone.
    let mut hook = unsafe { SetWindowsHookExW(WH_GETMESSAGE, pointer_getmsg, 0, thread) };
    if hook == 0 && unsafe { GetLastError() } == ERROR_HOOK_NEEDS_HMOD {
        hook = unsafe { SetWindowsHookExW(WH_GETMESSAGE, pointer_getmsg, hook_module(), thread) };
    }
    if hook == 0 {
        eprintln!("TABLET: pointer hook was not installed ({})", unsafe { GetLastError() });
        HOOK_STARTED.store(false, Ordering::Release);
        return;
    }
    POINTER_HOOK.store(hook, Ordering::Release);
    eprintln!("TABLET: pointer hook installed on window thread {thread} (caller thread {caller})");
}

fn hook_module() -> isize {
    let mut module = 0isize;
    unsafe {
        GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            pointer_getmsg as *const () as *const u16,
            &mut module,
        );
    }
    module
}

fn publish_contacts(source: u8, hwnd: isize, contacts: HashMap<u32, [f32; 2]>) {
    if !claim_touch_source(source) {
        return;
    }
    let mut shared = shared_touch().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut next = HashMap::new();
    for (id, pos) in contacts {
        next.insert(id, ScreenTouch { x: pos[0], y: pos[1], hwnd, seen_millis: now_millis() });
    }
    shared.screen = next;
    shared.generation = shared.generation.wrapping_add(1);
}

struct PointerFrame {
    hwnd: isize,
    down: HashMap<u32, [f32; 2]>,
    lifted: Vec<u32>,
}

fn arm_touch_gesture() {
    TOUCH_GESTURE_UNTIL.store(now_millis().saturating_add(TOUCH_GESTURE_HOLD_MILLIS), Ordering::Relaxed);
}

pub fn touch_gesture_active() -> bool {
    now_millis() < TOUCH_GESTURE_UNTIL.load(Ordering::Relaxed)
}

fn contacts_from(infos: &[PointerTouchInfo]) -> PointerFrame {
    let hwnd = infos.first().map(|info| info.pointer_info.hwnd_target).unwrap_or(0);
    let mut down = HashMap::new();
    let mut lifted = Vec::new();
    for info in infos {
        let flags = info.pointer_info.pointer_flags;
        let id = info.pointer_info.pointer_id;
        if flags & POINTER_FLAG_UP != 0 {
            lifted.push(id);
            continue;
        }
        if flags & (POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN) == 0 {
            continue;
        }
        down.insert(id, [info.pointer_info.pixel_x as f32, info.pointer_info.pixel_y as f32]);
    }
    PointerFrame { hwnd, down, lifted }
}

/// One pointer message describes one finger. Replacing the whole map dropped the other
/// finger, so a pinch never had two contacts. Merge this message, then ask Windows
/// whether every finger we already know is still down.
fn apply_pointer_update(hwnd: isize, down: HashMap<u32, [f32; 2]>, lifted: &[u32]) {
    if !claim_touch_source(SOURCE_WM_POINTER) {
        return;
    }
    let now = now_millis();
    let mut shared = shared_touch().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    for id in lifted {
        shared.screen.remove(id);
    }
    for (id, pos) in &down {
        shared.screen.insert(
            *id,
            ScreenTouch { x: pos[0], y: pos[1], hwnd, seen_millis: now },
        );
    }
    let others: Vec<u32> = shared.screen.keys().copied().filter(|id| !down.contains_key(id)).collect();
    for id in others {
        let mut info = unsafe { std::mem::zeroed::<PointerTouchInfo>() };
        if unsafe { GetPointerTouchInfo(id, &mut info) } == 0 {
            continue;
        }
        let flags = info.pointer_info.pointer_flags;
        let in_contact = flags & POINTER_FLAG_UP == 0 && flags & (POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN) != 0;
        if !in_contact {
            shared.screen.remove(&id);
            continue;
        }
        if let Some(slot) = shared.screen.get_mut(&id) {
            slot.x = info.pointer_info.pixel_x as f32;
            slot.y = info.pointer_info.pixel_y as f32;
            slot.seen_millis = now;
            if info.pointer_info.hwnd_target != 0 {
                slot.hwnd = info.pointer_info.hwnd_target;
            }
        }
    }
    let live = shared.screen.len();
    shared.generation = shared.generation.wrapping_add(1);
    if live >= 2 && !LOGGED_TWO_FINGERS.swap(true, Ordering::Relaxed) {
        eprintln!("TABLET: pointer contacts live={live}");
    }
}

/// The count-with-a-null-buffer probe returns access denied here and leaves the count at 0,
/// so the frame is read straight into a buffer. One pointer is the fallback when the frame call fails.
fn read_pointer_frame(id: u32) -> Option<PointerFrame> {
    let mut count = 16u32;
    let mut infos = vec![unsafe { std::mem::zeroed::<PointerTouchInfo>() }; 16];
    if unsafe { GetPointerFrameTouchInfo(id, &mut count, infos.as_mut_ptr()) } != 0 && count > 0 {
        let count = count.min(16) as usize;
        return Some(contacts_from(&infos[..count]));
    }
    let mut one = unsafe { std::mem::zeroed::<PointerTouchInfo>() };
    if unsafe { GetPointerTouchInfo(id, &mut one) } != 0 {
        return Some(contacts_from(std::slice::from_ref(&one)));
    }
    None
}

fn forget_pointer(id: u32) {
    if TOUCH_SOURCE.load(Ordering::Relaxed) != SOURCE_WM_POINTER {
        return;
    }
    let mut shared = shared_touch().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if shared.screen.remove(&id).is_some() {
        shared.generation = shared.generation.wrapping_add(1);
    }
}

fn note_pointer_message(msg: &WinMsg) {
    let id = (msg.wparam & 0xFFFF) as u32;
    let mut kind = 0u32;
    if unsafe { GetPointerType(id, &mut kind) } == 0 || kind != PT_TOUCH {
        return;
    }
    // Before AWT turns this message into a pan wheel. A leave is not a source:
    // claiming it blocked WM_TOUCH after a failed frame read.
    arm_touch_gesture();
    if msg.message == WM_POINTERLEAVE || msg.message == WM_POINTERCAPTURECHANGED {
        if !SAW_WM_POINTER.swap(true, Ordering::Relaxed) {
            eprintln!("TABLET: hook saw WM_POINTER touch hwnd=0");
        }
        forget_pointer(id);
        return;
    }
    if let Some(frame) = read_pointer_frame(id) {
        let hwnd = if frame.hwnd != 0 { frame.hwnd } else { msg.hwnd };
        if !SAW_WM_POINTER.swap(true, Ordering::Relaxed) {
            eprintln!("TABLET: hook saw WM_POINTER touch hwnd={hwnd}");
        }
        if !LOGGED_POINTER_FRAME.swap(true, Ordering::Relaxed) {
            eprintln!("TABLET: pointer frame contacts={} hwnd={hwnd}", frame.down.len());
        }
        apply_pointer_update(hwnd, frame.down, &frame.lifted);
        return;
    }
    if !TOUCH_READ_FAILED.swap(true, Ordering::Relaxed) {
        eprintln!("TABLET: pointer frame unread ({}); using the message point", unsafe { GetLastError() });
    }
    if msg.message == WM_POINTERUP {
        forget_pointer(id);
        return;
    }
    if !SAW_WM_POINTER.swap(true, Ordering::Relaxed) {
        eprintln!("TABLET: hook saw WM_POINTER touch hwnd={}", msg.hwnd);
    }
    let mut contacts = HashMap::new();
    contacts.insert(id, [msg.pt_x as f32, msg.pt_y as f32]);
    apply_pointer_update(msg.hwnd, contacts, &[]);
}

fn note_touch_message(hwnd: isize, wparam: usize, lparam: isize) {
    arm_touch_gesture();
    if !SAW_WM_TOUCH.swap(true, Ordering::Relaxed) {
        eprintln!("TABLET: hook saw WM_TOUCH hwnd={hwnd}");
    }
    let count = (wparam & 0xFFFF) as u32;
    if count == 0 || count > 16 || lparam == 0 {
        return;
    }
    let mut inputs = vec![unsafe { std::mem::zeroed::<TouchInput>() }; count as usize];
    let read = unsafe { GetTouchInputInfo(lparam, count, inputs.as_mut_ptr(), std::mem::size_of::<TouchInput>() as i32) };
    if read == 0 {
        if !TOUCH_READ_FAILED.swap(true, Ordering::Relaxed) {
            eprintln!("TABLET: touch points could not be read ({})", unsafe { GetLastError() });
        }
        return;
    }
    let entries: Vec<(u32, u32, i32, i32)> = inputs.iter().take(count as usize).map(|input| (input.id, input.flags, input.x, input.y)).collect();
    let mut frames = touch_frames().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let contacts = frames.apply(&entries, now_millis());
    drop(frames);
    publish_contacts(SOURCE_WM_TOUCH, hwnd, contacts);
}

/// One contact per raw id, with a small stable ordinal the rest of the app can name.
struct TouchFrames {
    live: HashMap<u32, TouchContact>,
    next_ordinal: u32,
    free_ordinals: Vec<u32>,
}

struct TouchContact {
    ordinal: u32,
    x: f32,
    y: f32,
    seen_millis: u64,
}

impl TouchFrames {
    fn new() -> Self {
        Self { live: HashMap::new(), next_ordinal: 0, free_ordinals: Vec::new() }
    }

    /// `entries` are `(raw id, flags, x in hundredths of a screen pixel, y the same)`.
    /// A contact lifts on UP, or after 250ms with no sample. A frame that omits a contact
    /// does not lift it: Windows often sends only the contact that moved.
    fn apply(&mut self, entries: &[(u32, u32, i32, i32)], now_millis: u64) -> HashMap<u32, [f32; 2]> {
        for &(id, flags, x, y) in entries {
            if flags & (TOUCHEVENTF_PEN | TOUCHEVENTF_PALM) != 0 {
                continue;
            }
            if flags & TOUCHEVENTF_UP != 0 {
                if let Some(contact) = self.live.remove(&id) {
                    self.free_ordinals.push(contact.ordinal);
                }
                continue;
            }
            if flags & (TOUCHEVENTF_DOWN | TOUCHEVENTF_MOVE) == 0 {
                continue;
            }
            let px = x as f32 / 100.0;
            let py = y as f32 / 100.0;
            if let Some(slot) = self.live.get_mut(&id) {
                slot.x = px;
                slot.y = py;
                slot.seen_millis = now_millis;
            } else {
                let ordinal = self.free_ordinals.pop().unwrap_or_else(|| {
                    let ordinal = self.next_ordinal;
                    self.next_ordinal += 1;
                    ordinal
                });
                self.live.insert(id, TouchContact { ordinal, x: px, y: py, seen_millis: now_millis });
            }
        }
        let stale: Vec<u32> = self
            .live
            .iter()
            .filter(|(_, contact)| now_millis.saturating_sub(contact.seen_millis) > TOUCH_CONTACT_SILENCE_MILLIS)
            .map(|(id, _)| *id)
            .collect();
        for id in stale {
            if let Some(contact) = self.live.remove(&id) {
                self.free_ordinals.push(contact.ordinal);
            }
        }
        self.live.iter().map(|(_, contact)| (contact.ordinal, [contact.x, contact.y])).collect()
    }
}

fn touch_frames() -> &'static Mutex<TouchFrames> {
    static FRAMES: OnceLock<Mutex<TouchFrames>> = OnceLock::new();
    FRAMES.get_or_init(|| Mutex::new(TouchFrames::new()))
}

/// Sees pointer and touch messages, then always forwards them.
unsafe extern "system" fn pointer_getmsg(code: i32, wparam: usize, lparam: isize) -> isize {
    if code >= 0 && wparam == PM_REMOVE && lparam != 0 {
        let msg = &*(lparam as *const WinMsg);
        if msg.message == WM_TOUCH {
            note_touch_message(msg.hwnd, msg.wparam, msg.lparam);
        } else if matches!(
            msg.message,
            WM_POINTERDOWN | WM_POINTERUPDATE | WM_POINTERUP | WM_POINTERLEAVE | WM_POINTERCAPTURECHANGED
        ) {
            note_pointer_message(msg);
        }
    }
    CallNextHookEx(POINTER_HOOK.load(Ordering::Relaxed), code, wparam, lparam)
}

#[link(name = "user32")]
extern "system" {
    fn ScreenToClient(hwnd: isize, point: *mut WinPoint) -> i32;
    fn IsChild(parent: isize, child: isize) -> i32;
    fn SetWindowsHookExW(
        id: i32,
        hook: unsafe extern "system" fn(i32, usize, isize) -> isize,
        module: isize,
        thread: u32,
    ) -> isize;
    fn CallNextHookEx(hook: isize, code: i32, wparam: usize, lparam: isize) -> isize;
    fn GetPointerType(id: u32, kind: *mut u32) -> i32;
    fn GetPointerFrameTouchInfo(id: u32, count: *mut u32, info: *mut PointerTouchInfo) -> i32;
    fn GetPointerTouchInfo(id: u32, info: *mut PointerTouchInfo) -> i32;
    fn GetTouchInputInfo(handle: isize, count: u32, inputs: *mut TouchInput, size: i32) -> i32;
    fn GetWindowThreadProcessId(hwnd: isize, process: *mut u32) -> u32;
    fn UnhookWindowsHookEx(hook: isize) -> i32;
}

#[link(name = "kernel32")]
extern "system" {
    fn GetLastError() -> u32;
    fn GetCurrentThreadId() -> u32;
    fn GetModuleHandleExW(flags: u32, address: *const u16, module: *mut isize) -> i32;
}

#[repr(C)]
#[derive(Clone, Copy)]
struct TouchInput {
    x: i32,
    y: i32,
    source: isize,
    id: u32,
    flags: u32,
    mask: u32,
    time: u32,
    extra: usize,
    cx: u32,
    cy: u32,
}

#[cfg(test)]
mod tests {
    use super::*;
    use octotablet::builder::{InkTabletInfo, InkTabletKind};

    fn tablet(index: i32, name: &str, kind: InkTabletKind) -> InkTabletInfo {
        InkTabletInfo { index, name: name.to_string(), kind }
    }

    fn this_machine() -> Vec<InkTabletInfo> {
        vec![
            tablet(0, r"\\.\DISPLAY1", InkTabletKind::Mouse),
            tablet(1, "Virtual Multitouch Device", InkTabletKind::Pen),
            tablet(2, r"\??\Microsoft HID RID\000D_0002\2", InkTabletKind::Pen),
            tablet(3, "Cintiq Pro 16 Touch", InkTabletKind::Touch),
            tablet(4, "Cintiq Pro 16 Touch", InkTabletKind::Touch),
            tablet(5, "Cintiq Pro 16", InkTabletKind::Pen),
            tablet(6, "Cintiq Pro 16", InkTabletKind::Pen),
        ]
    }

    #[test]
    fn this_machine_listens_to_the_real_pens_only() {
        let chosen = choose_ink_tablets(&this_machine(), None, None);
        assert_eq!(chosen, vec![2, 5, 6]);
        for index in &chosen {
            let tablet = this_machine().into_iter().find(|tablet| tablet.index == *index).unwrap();
            assert!(!tablet.name.to_ascii_lowercase().contains("touch"));
        }
    }

    #[test]
    fn an_index_override_is_exact_and_a_name_filter_keeps_every_match() {
        assert_eq!(choose_ink_tablets(&this_machine(), None, Some(6)), vec![6]);
        assert_eq!(choose_ink_tablets(&this_machine(), Some("cintiq"), None), vec![5, 6]);
    }

    #[test]
    fn a_pen_that_touches_before_it_hovers_waits_for_the_pose() {
        assert!(pen_down_needs_a_pose(NO_PRESSURE));
        assert!(!pen_down_needs_a_pose(0.0));
        assert!(!pen_down_needs_a_pose(0.4));
    }

    #[test]
    fn the_first_pressure_bearing_pen_wins_and_a_finger_is_rejected() {
        let (latch, fate) = classify_ink_sample(None, 5, 0.0, true);
        assert!(matches!(fate, SampleFate::Keep));
        assert_eq!(latch, Some(5));
        let (latch, fate) = classify_ink_sample(latch, 6, 0.4, true);
        assert!(matches!(fate, SampleFate::Duplicate));
        assert_eq!(latch, Some(5));
        let (latch, fate) = classify_ink_sample(latch, 2, NO_PRESSURE, true);
        assert!(matches!(fate, SampleFate::Pressureless));
        assert_eq!(latch, Some(5));
    }

    #[test]
    fn two_fingers_keep_stable_ordinals_and_a_lift_frees_one() {
        let mut frames = TouchFrames::new();
        let down = frames.apply(&[
            (40, TOUCHEVENTF_DOWN, 10000, 20000),
            (41, TOUCHEVENTF_DOWN, 30000, 40000),
        ], 0);
        assert_eq!(down.len(), 2);
        assert_eq!(down.get(&0), Some(&[100.0, 200.0]));
        assert_eq!(down.get(&1), Some(&[300.0, 400.0]));
        let moved = frames.apply(&[
            (40, TOUCHEVENTF_MOVE, 11000, 20000),
            (41, TOUCHEVENTF_MOVE, 36000, 40000),
        ], 16);
        assert_eq!(moved.get(&0), Some(&[110.0, 200.0]));
        assert_eq!(moved.get(&1), Some(&[360.0, 400.0]));
        let one = frames.apply(&[(40, TOUCHEVENTF_MOVE, 11000, 20000), (41, TOUCHEVENTF_UP, 36000, 40000)], 32);
        assert_eq!(one.len(), 1);
        assert!(one.contains_key(&0));
    }

    #[test]
    fn a_partial_frame_keeps_the_other_finger_and_silence_lifts_it() {
        let mut frames = TouchFrames::new();
        frames.apply(&[(7, TOUCHEVENTF_DOWN, 5000, 5000), (8, TOUCHEVENTF_DOWN, 8000, 8000)], 0);
        let partial = frames.apply(&[(7, TOUCHEVENTF_MOVE, 5100, 5000)], 16);
        assert_eq!(partial.len(), 2);
        let palm = frames.apply(&[(9, TOUCHEVENTF_PALM | TOUCHEVENTF_DOWN, 1000, 1000)], 32);
        assert_eq!(palm.len(), 2);
        let quiet = frames.apply(&[], 16 + TOUCH_CONTACT_SILENCE_MILLIS + 1);
        assert!(quiet.is_empty() || quiet.len() < 2);
        let later = frames.apply(&[], 32 + TOUCH_CONTACT_SILENCE_MILLIS + 1);
        assert!(later.is_empty());
    }

    #[test]
    fn a_freed_ordinal_is_reused() {
        let mut frames = TouchFrames::new();
        frames.apply(&[(1, TOUCHEVENTF_DOWN, 100, 100)], 0);
        frames.apply(&[(1, TOUCHEVENTF_UP, 100, 100)], 16);
        let again = frames.apply(&[(2, TOUCHEVENTF_DOWN, 200, 200)], 32);
        assert_eq!(again.get(&0), Some(&[2.0, 2.0]));
        assert_eq!(again.len(), 1);
    }
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

        pub fn rebuild(&mut self) -> Vec<f32> {
            Vec::new()
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
        Ok(events) => Some(events),
        Err(_) => {
            eprintln!("TABLET: rebuilding the native bridge panicked too; trying again next frame");
            None
        }
    }
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

/// True while a finger message is in hand, including a short hold after it so the pan wheel
/// AWT synthesizes from that same message does not also scroll.
#[no_mangle]
pub extern "system" fn Java_com_letta_mobile_desktop_input_TabletBridge_nativeTouchGestureActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    #[cfg(windows)]
    {
        return u8::from(platform::touch_gesture_active());
    }
    #[cfg(not(windows))]
    {
        0
    }
}
