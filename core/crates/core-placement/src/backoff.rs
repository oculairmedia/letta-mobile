//! Retry schedules, behind [`RetrySchedule`]. The adapter over the `backon` crate is the only code
//! that names it.

use core::time::Duration;

use backon::{BackoffBuilder as _, ExponentialBuilder};

/// When to retry after a failed attempt. Pure: the caller adds the delay to its own clock.
pub trait RetrySchedule {
    /// The delay before attempt `attempt + 1`, after `attempt` failures (0 = the first retry), or
    /// `None` when the schedule gives up.
    fn delay_ms(&self, attempt: u32) -> Option<u64>;
}

/// Exponential backoff without jitter: `base_ms × 2^attempt`, at most `max_attempts` retries.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Exponential {
    /// The first delay.
    pub base_ms: u64,
    /// Retries before giving up.
    pub max_attempts: u32,
    /// The largest delay.
    pub max_delay_ms: u64,
}

impl RetrySchedule for Exponential {
    fn delay_ms(&self, attempt: u32) -> Option<u64> {
        if attempt >= self.max_attempts {
            return None;
        }
        if self.base_ms == 0 {
            return Some(0);
        }
        let mut backoff = ExponentialBuilder::default()
            .with_min_delay(Duration::from_millis(self.base_ms))
            .with_max_delay(Duration::from_millis(self.max_delay_ms))
            .with_factor(2.0)
            .with_max_times(usize::try_from(self.max_attempts).unwrap_or(usize::MAX))
            .build();
        let delay = backoff.nth(usize::try_from(attempt).unwrap_or(usize::MAX))?;
        Some(u64::try_from(delay.as_millis()).unwrap_or(u64::MAX))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn doubles_from_the_base_and_gives_up() {
        let schedule = Exponential {
            base_ms: 30_000,
            max_attempts: 5,
            max_delay_ms: 3_600_000,
        };
        let delays: Vec<_> = (0..6).map(|a| schedule.delay_ms(a)).collect();
        assert_eq!(
            delays,
            vec![
                Some(30_000),
                Some(60_000),
                Some(120_000),
                Some(240_000),
                Some(480_000),
                None
            ]
        );
    }

    #[test]
    fn caps_at_the_max_delay() {
        let schedule = Exponential {
            base_ms: 1_000,
            max_attempts: 10,
            max_delay_ms: 4_000,
        };
        assert_eq!(schedule.delay_ms(5), Some(4_000));
    }
}
