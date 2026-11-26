//! Membership capabilities: opaque strings the core checks and apps interpret, plus the UI presets.

use std::collections::BTreeSet;
use std::fmt;

use thiserror::Error;

/// A capability string matching `^[a-z][a-z0-9._*-]{0,63}$`.
#[derive(Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Capability(String);

/// Text that is not a valid capability string.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
#[error("a capability matches ^[a-z][a-z0-9._*-]{{0,63}}$")]
pub struct CapabilityError;

impl Capability {
    /// Issue any membership op. Implies [`Capability::DEVICES_APPROVE`].
    pub const ADMIN_FULL: &'static str = "admin.full";
    /// Add devices with capabilities inside [`DEVICE_CEILING`].
    pub const DEVICES_APPROVE: &'static str = "devices.approve";
    /// Claim placement for genesis, move or rekey; run turns.
    pub const NODE_EXECUTE: &'static str = "node.execute";
    /// Claim placement by failover or takeover (with [`Capability::NODE_EXECUTE`]).
    pub const NODE_TAKEOVER: &'static str = "node.takeover";
    /// Open the log and blob protocols; pull and push agent logs.
    pub const NODE_REPLICATE: &'static str = "node.replicate";
    /// Keep a full replica of every agent log.
    pub const STORE: &'static str = "store";
    /// Run the shell.
    pub const UI: &'static str = "ui";
    /// Hold the catalog replica.
    pub const CATALOG: &'static str = "catalog";
    /// Write catalog documents. Reading needs only active membership.
    pub const CATALOG_WRITE: &'static str = "catalog.write";
    /// Answer approvals and app-tool requests.
    pub const APPROVE: &'static str = "approve";

    /// Parses `text` as a capability.
    ///
    /// # Errors
    ///
    /// [`CapabilityError`] when the text does not match the capability pattern.
    pub fn new(text: &str) -> Result<Self, CapabilityError> {
        let bytes = text.as_bytes();
        let first_ok = bytes.first().is_some_and(u8::is_ascii_lowercase);
        let rest_ok = bytes.iter().skip(1).all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || b"._*-".contains(byte)
        });
        if first_ok && rest_ok && bytes.len() <= 64 {
            Ok(Self(text.to_owned()))
        } else {
            Err(CapabilityError)
        }
    }

    /// The capability's text.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    fn known(text: &'static str) -> Self {
        Self(text.to_owned())
    }
}

impl fmt::Display for Capability {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl fmt::Debug for Capability {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "Capability({})", self.0)
    }
}

/// A set of capabilities, ordered by text.
pub type CapSet = BTreeSet<Capability>;

/// The ten capabilities the protocol defines, in the order of the spec's table.
pub const ALL: [&str; 10] = [
    Capability::ADMIN_FULL,
    Capability::DEVICES_APPROVE,
    Capability::NODE_EXECUTE,
    Capability::NODE_TAKEOVER,
    Capability::NODE_REPLICATE,
    Capability::STORE,
    Capability::UI,
    Capability::CATALOG,
    Capability::CATALOG_WRITE,
    Capability::APPROVE,
];

/// What a `devices.approve` holder may grant in an `add`.
pub const DEVICE_CEILING: [&str; 6] = [
    Capability::UI,
    Capability::CATALOG,
    Capability::CATALOG_WRITE,
    Capability::APPROVE,
    Capability::STORE,
    Capability::NODE_REPLICATE,
];

/// Builds a [`CapSet`] from known capability strings.
#[must_use]
pub fn cap_set(names: &[&'static str]) -> CapSet {
    names.iter().map(|name| Capability::known(name)).collect()
}

/// Whether every capability in `caps` is inside [`DEVICE_CEILING`].
#[must_use]
pub fn within_device_ceiling(caps: &CapSet) -> bool {
    caps.iter()
        .all(|cap| DEVICE_CEILING.contains(&cap.as_str()))
}

/// The capability sets the UI offers when it adds a device. Presets are not part of the protocol:
/// an `add` carries whatever capabilities it names.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Preset {
    /// A desktop node: shell, catalog, approvals, full store, replication and execution.
    DesktopNode,
    /// An always-on Linux node: catalog, full store, replication and execution, no shell.
    AlwaysOnLinux,
    /// A read-only member such as a kiosk: shell and catalog replica, no catalog writes.
    ReadOnly,
    /// A phone: a full store node. Execution is added only when the user opts the phone in.
    Phone {
        /// The user opted this phone into running turns.
        execution: bool,
    },
    /// The first device, which writes the genesis op: all ten capabilities.
    FirstDevice,
}

impl Preset {
    /// The preset's capabilities.
    #[must_use]
    pub fn capabilities(self) -> CapSet {
        use Capability as C;
        match self {
            Self::DesktopNode => cap_set(&[
                C::UI,
                C::CATALOG,
                C::CATALOG_WRITE,
                C::APPROVE,
                C::STORE,
                C::NODE_REPLICATE,
                C::NODE_EXECUTE,
                C::NODE_TAKEOVER,
            ]),
            Self::AlwaysOnLinux => cap_set(&[
                C::CATALOG,
                C::CATALOG_WRITE,
                C::STORE,
                C::NODE_REPLICATE,
                C::NODE_EXECUTE,
                C::NODE_TAKEOVER,
            ]),
            Self::ReadOnly => cap_set(&[C::UI, C::CATALOG]),
            Self::Phone { execution } => {
                let mut caps = cap_set(&[
                    C::UI,
                    C::CATALOG,
                    C::CATALOG_WRITE,
                    C::APPROVE,
                    C::STORE,
                    C::NODE_REPLICATE,
                ]);
                if execution {
                    caps.insert(C::known(C::NODE_EXECUTE));
                }
                caps
            }
            Self::FirstDevice => cap_set(&ALL),
        }
    }

    /// The preset named `name` in fixtures: `desktop_node`, `always_on_linux`, `read_only`,
    /// `phone`, `phone_execution` or `first_device`.
    #[must_use]
    pub fn from_name(name: &str) -> Option<Self> {
        Some(match name {
            "desktop_node" => Self::DesktopNode,
            "always_on_linux" => Self::AlwaysOnLinux,
            "read_only" => Self::ReadOnly,
            "phone" => Self::Phone { execution: false },
            "phone_execution" => Self::Phone { execution: true },
            "first_device" => Self::FirstDevice,
            _ => return None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capability_pattern() {
        for good in ALL {
            assert!(Capability::new(good).is_ok(), "{good}");
        }
        assert!(Capability::new("a").is_ok());
        assert!(Capability::new(&"a".repeat(64)).is_ok());
        assert!(Capability::new(&"a".repeat(65)).is_err());
        assert!(Capability::new("").is_err());
        assert!(Capability::new("1abc").is_err());
        assert!(Capability::new("Admin").is_err());
        assert!(Capability::new("a b").is_err());
    }

    #[test]
    fn privileged_capabilities_are_in_no_default_role() {
        let privileged = [
            Capability::NODE_EXECUTE,
            Capability::NODE_REPLICATE,
            Capability::NODE_TAKEOVER,
            Capability::DEVICES_APPROVE,
        ];
        // Only the explicit node presets carry execution or replication; none carries approval of
        // devices, and the read-only preset carries none of them.
        let read_only = Preset::ReadOnly.capabilities();
        assert!(
            privileged
                .iter()
                .all(|cap| !read_only.iter().any(|c| c.as_str() == *cap))
        );
        for preset in [
            Preset::DesktopNode,
            Preset::AlwaysOnLinux,
            Preset::Phone { execution: true },
        ] {
            assert!(
                !preset
                    .capabilities()
                    .iter()
                    .any(|c| c.as_str() == Capability::DEVICES_APPROVE)
            );
        }
    }

    #[test]
    fn the_phone_is_a_full_store_node_with_execution_opt_in() {
        let phone = Preset::Phone { execution: false }.capabilities();
        assert!(within_device_ceiling(&phone));
        assert!(phone.contains(&Capability::known(Capability::STORE)));
        assert!(!phone.contains(&Capability::known(Capability::NODE_EXECUTE)));
        let opted = Preset::Phone { execution: true }.capabilities();
        assert!(opted.contains(&Capability::known(Capability::NODE_EXECUTE)));
        assert_eq!(Preset::FirstDevice.capabilities().len(), 10);
    }
}
