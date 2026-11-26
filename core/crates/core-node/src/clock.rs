//! Time for the node: a monotonic clock for timers (core-log's driver, the move pipeline's
//! deadlines, failover windows) and the wall clock for entry timestamps.

use std::future::Future;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio::time::Instant;

/// The node's clocks. Monotonic milliseconds count from the node's start.
#[derive(Clone, Copy, Debug)]
pub struct NodeClock {
    origin: Instant,
}

impl NodeClock {
    /// A clock starting now.
    #[must_use]
    pub fn new() -> Self {
        Self {
            origin: Instant::now(),
        }
    }

    /// Monotonic milliseconds since the node started.
    #[must_use]
    pub fn mono_ms(&self) -> u64 {
        u64::try_from(self.origin.elapsed().as_millis()).unwrap_or(u64::MAX)
    }

    /// Wall-clock milliseconds since the Unix epoch (entry timestamps; informational only).
    #[must_use]
    pub fn wall_ms(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
    }
}

impl Default for NodeClock {
    fn default() -> Self {
        Self::new()
    }
}

impl core_log::driver::Clock for NodeClock {
    fn now_ms(&self) -> u64 {
        self.mono_ms()
    }

    fn sleep_until(&self, at_ms: u64) -> impl Future<Output = ()> {
        tokio::time::sleep_until(self.origin + Duration::from_millis(at_ms))
    }
}
