//! `node.info` v1 (the shape the Kotlin node writes, `docs/reference/contracts/node.info/info-v1.md`
//! on the N7 work) and the I6 runtime parity string.

use core_log::allowlist::STORE_FORMAT;
use core_log::hashes::sha256;
use core_membership::{Capability, MembershipView};
use core_placement::NodeClass;
use core_proto::NodeId;
use serde::{Deserialize, Serialize};

use crate::host::RuntimeInfo;

/// The node.info schema this build writes.
pub const NODE_INFO_SCHEMA: u32 = 1;

/// `store_format {major, minor}`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoreFormat {
    /// A different major blocks `execute` and `store`.
    pub major: u64,
    /// Additive revisions.
    pub minor: u64,
}

/// `runtime_patches`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimePatches {
    /// `boot_line` when read from the patch loader, else `unknown`.
    pub source: String,
    /// Patches applied, or null.
    pub applied: Option<u32>,
    /// Patches skipped, or null.
    pub skipped: Option<u32>,
    /// SHA-256 of the sorted patch-id list, or null.
    pub patch_ids_sha256: Option<String>,
}

/// The `node.info` result, every field present.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodeInfo {
    /// The schema.
    pub schema: u32,
    /// `windows`, `linux`, `macos`, `android`, `ios` or `unknown`.
    pub os: String,
    /// The Baken build, or `unknown`.
    pub app_version: String,
    /// letta-code's version, or `unknown`.
    pub letta_code_version: String,
    /// The agent store layout this node writes.
    pub store_format: StoreFormat,
    /// Any of `ui`, `catalog`, `approve`, `execute`, `store`, `always-on`.
    pub capabilities: Vec<String>,
    /// Model handles the runtime offers.
    pub models: Vec<String>,
    /// The runtime patch set.
    pub runtime_patches: RuntimePatches,
}

/// The OS family node.info reports.
#[must_use]
pub const fn os_family() -> &'static str {
    match std::env::consts::OS.as_bytes() {
        b"windows" => "windows",
        b"linux" => "linux",
        b"macos" => "macos",
        b"android" => "android",
        b"ios" => "ios",
        _ => "unknown",
    }
}

/// SHA-256 (hex) of the sorted patch ids, one per line.
#[must_use]
pub fn patch_ids_sha256(ids: &[String]) -> String {
    let mut sorted = ids.to_vec();
    sorted.sort();
    hex::encode(sha256(sorted.join("\n").as_bytes()))
}

/// I6 parity (E7): `letta-code@<version>+lcp:<sha256 of the sorted patch-id list>`, with
/// `unknown` for what the host cannot see.
#[must_use]
pub fn runtime_parity(runtime: &RuntimeInfo) -> String {
    format!(
        "letta-code@{}+lcp:{}",
        runtime.letta_code_version.as_deref().unwrap_or("unknown"),
        runtime
            .patch_ids
            .as_deref()
            .map_or_else(|| "unknown".to_owned(), patch_ids_sha256)
    )
}

/// Reduces a free-text value to a version-like token (no host names or paths).
fn token(value: Option<&str>) -> String {
    value
        .map(|v| {
            v.split_whitespace()
                .next()
                .unwrap_or_default()
                .chars()
                .filter(|c| c.is_ascii_alphanumeric() || ".-+_".contains(*c))
                .take(64)
                .collect::<String>()
        })
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "unknown".to_owned())
}

/// Builds node.info for `node`.
#[must_use]
pub fn node_info(
    node: &NodeId,
    view: Option<&MembershipView>,
    class: NodeClass,
    app_version: &str,
    runtime: &RuntimeInfo,
) -> NodeInfo {
    let mut capabilities = Vec::new();
    if let Some(view) = view {
        for (capability, name) in [
            (Capability::UI, "ui"),
            (Capability::CATALOG, "catalog"),
            (Capability::APPROVE, "approve"),
            (Capability::NODE_EXECUTE, "execute"),
            (Capability::STORE, "store"),
        ] {
            if view.has_capability(node, capability) {
                capabilities.push(name.to_owned());
            }
        }
    }
    if class == NodeClass::AlwaysOn {
        capabilities.push("always-on".to_owned());
    }
    NodeInfo {
        schema: NODE_INFO_SCHEMA,
        os: os_family().to_owned(),
        app_version: token(Some(app_version)),
        letta_code_version: token(runtime.letta_code_version.as_deref()),
        store_format: StoreFormat {
            major: STORE_FORMAT,
            minor: 0,
        },
        capabilities,
        models: runtime.models.clone(),
        runtime_patches: match runtime.patches_applied {
            Some((applied, skipped)) => RuntimePatches {
                source: "boot_line".to_owned(),
                applied: Some(applied),
                skipped: Some(skipped),
                patch_ids_sha256: runtime.patch_ids.as_deref().map(patch_ids_sha256),
            },
            None => RuntimePatches {
                source: "unknown".to_owned(),
                applied: None,
                skipped: None,
                patch_ids_sha256: None,
            },
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parity_and_shape() {
        let runtime = RuntimeInfo {
            letta_code_version: Some("0.34.70".into()),
            patch_ids: Some(vec!["lcp-b".into(), "lcp-a".into()]),
            patches_applied: Some((40, 0)),
            models: vec!["profile/main".into()],
        };
        let parity = runtime_parity(&runtime);
        assert!(parity.starts_with("letta-code@0.34.70+lcp:"));
        assert_eq!(
            patch_ids_sha256(&["lcp-a".into(), "lcp-b".into()]),
            patch_ids_sha256(&["lcp-b".into(), "lcp-a".into()])
        );
        let info = node_info(
            &NodeId::from_bytes([1; 32]),
            None,
            NodeClass::AlwaysOn,
            "1.2.3 (C:\\x)",
            &runtime,
        );
        let json = serde_json::to_value(&info).expect("json");
        for key in [
            "schema",
            "os",
            "app_version",
            "letta_code_version",
            "store_format",
            "capabilities",
            "models",
            "runtime_patches",
        ] {
            assert!(json.get(key).is_some(), "{key}");
        }
        assert_eq!(json["app_version"], "1.2.3");
        assert_eq!(json["store_format"]["major"], 1);
        assert_eq!(json["runtime_patches"]["source"], "boot_line");
        assert_eq!(json["capabilities"][0], "always-on");
        let unknown = node_info(
            &NodeId::from_bytes([1; 32]),
            None,
            NodeClass::Desktop,
            "",
            &RuntimeInfo::default(),
        );
        assert_eq!(unknown.letta_code_version, "unknown");
        assert_eq!(unknown.runtime_patches.applied, None);
        assert_eq!(
            runtime_parity(&RuntimeInfo::default()),
            "letta-code@unknown+lcp:unknown"
        );
    }
}
