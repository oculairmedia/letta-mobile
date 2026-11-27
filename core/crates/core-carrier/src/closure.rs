//! How a link ends.

use std::fmt;

use core_proto::closure::Closure;

/// An application close: the code and reason one end closed a link with.
///
/// A carrier delivers both unchanged; it never maps or filters them. A peer treats an unknown code
/// as a closure like any other.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct AppClose {
    /// The application close code (QUIC application error codes are below 2^62).
    pub code: u64,
    /// The close reason, as text (the node protocol's reasons are ASCII).
    pub reason: String,
}

impl AppClose {
    /// A close with this code and reason.
    #[must_use]
    pub fn new(code: u64, reason: impl Into<String>) -> Self {
        Self {
            code,
            reason: reason.into(),
        }
    }

    /// The orderly close, `0 closed`: a detach, which never cancels node work.
    #[must_use]
    pub fn closed() -> Self {
        Closure::CLOSED.into()
    }
}

impl From<Closure> for AppClose {
    fn from(closure: Closure) -> Self {
        Self::new(closure.code, closure.reason)
    }
}

impl fmt::Debug for AppClose {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{} {}", self.code, self.reason)
    }
}

/// How a link ended, as both ends see it.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum LinkClosure {
    /// One end closed it with this code and reason. Both ends see the same value.
    App(AppClose),
    /// The transport was lost with no close from either end.
    Lost,
}

impl LinkClosure {
    /// The application close, if one end closed the link.
    #[must_use]
    pub const fn app(&self) -> Option<&AppClose> {
        match self {
            Self::App(close) => Some(close),
            Self::Lost => None,
        }
    }
}

impl From<AppClose> for LinkClosure {
    fn from(close: AppClose) -> Self {
        Self::App(close)
    }
}

impl From<Closure> for LinkClosure {
    fn from(closure: Closure) -> Self {
        Self::App(closure.into())
    }
}

impl fmt::Display for LinkClosure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::App(close) => write!(formatter, "{close:?}"),
            Self::Lost => formatter.write_str("lost"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_closures_convert_unchanged() {
        for closure in Closure::ALL {
            let link: LinkClosure = closure.into();
            let app = link.app().expect("an application close");
            assert_eq!(app.code, closure.code);
            assert_eq!(app.reason, closure.reason);
        }
        assert_eq!(LinkClosure::Lost.app(), None);
        assert_eq!(LinkClosure::Lost.to_string(), "lost");
        assert_eq!(
            LinkClosure::from(Closure::PEER_REVOKED).to_string(),
            "4403 peer_revoked"
        );
    }
}
