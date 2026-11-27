//! The secrets and paths check (catalog-v1 §6.1, M3 acceptance): no catalog document may carry a
//! host path or a secret. The fixture test walks every string of every fixture with it, and the
//! writers run it on every value before it reaches Automerge.
//!
//! - Structural strings: no absolute path, no secret shape, and no high-entropy run (32 or more
//!   characters above 4.5 bits per character) unless the field holds an id, a URL or a ref.
//! - Prose fields (`title`, `name`, `description`, and shell `valueJson`): the secret checks only,
//!   so a user may title a conversation "fix C:\foo".

use std::collections::HashMap;
use std::sync::LazyLock;

use regex::Regex;
use serde_json::Value;

/// One failed check.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Finding {
    /// The key path of the string, `/`-separated.
    pub path: String,
    /// Which check failed: `absolute_path`, `secret` or `high_entropy`.
    pub reason: &'static str,
}

/// How a field's strings are checked.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FieldKind {
    /// Free text a person or agent wrote: secret checks only.
    Prose,
    /// An id, URL, ref name or position: path and secret checks, no entropy check.
    Id,
    /// Everything else: every check.
    Structural,
}

static ABSOLUTE_PATH: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[A-Za-z]:[\\/]|^\\\\|^/(home|Users|root|var|tmp|opt|etc|mnt|data)/|file://")
        .expect("valid regex")
});

static SECRET: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"sk-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{8,}|xox[abp]-|Bearer |-----BEGIN|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}",
    )
    .expect("valid regex")
});

static RUN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[A-Za-z0-9+/=_-]{32,}").expect("valid regex"));

/// The kind of a field from its key.
#[must_use]
pub fn field_kind(key: &str) -> FieldKind {
    const PROSE: [&str; 4] = ["title", "name", "description", "valueJson"];
    const ID_FIELDS: [&str; 12] = [
        "id",
        "holder",
        "prevHolder",
        "signer",
        "copiedBy",
        "issuer",
        "strandedHolder",
        "detectedBy",
        "memfsBranch",
        "pos",
        "shell",
        "constellationId",
    ];
    if PROSE.contains(&key) {
        FieldKind::Prose
    } else if ID_FIELDS.contains(&key)
        || key.ends_with("Id")
        || key.ends_with("Url")
        || key.ends_with("Claimant")
        || key == "supersededBy"
    {
        FieldKind::Id
    } else {
        FieldKind::Structural
    }
}

/// Checks one string of the given kind.
#[must_use]
pub fn check_str(text: &str, kind: FieldKind) -> Option<&'static str> {
    if SECRET.is_match(text) {
        return Some("secret");
    }
    if kind == FieldKind::Prose {
        return None;
    }
    if ABSOLUTE_PATH.is_match(text) {
        return Some("absolute_path");
    }
    if kind == FieldKind::Structural && RUN.find_iter(text).any(|run| entropy(run.as_str()) > 4.5) {
        return Some("high_entropy");
    }
    None
}

/// Shannon entropy in bits per character.
#[must_use]
#[allow(
    clippy::cast_precision_loss,
    reason = "character counts are far below 2^52"
)]
pub fn entropy(text: &str) -> f64 {
    let mut counts: HashMap<char, usize> = HashMap::new();
    let mut total = 0_usize;
    for ch in text.chars() {
        *counts.entry(ch).or_default() += 1;
        total += 1;
    }
    if total == 0 {
        return 0.0;
    }
    counts
        .values()
        .map(|&count| {
            let p = count as f64 / total as f64;
            -p * p.log2()
        })
        .sum()
}

/// Walks every string in a canonical JSON value (map keys included) and reports each failed check.
/// `$bytes` values are skipped (signatures and record bytes are typed as bytes).
#[must_use]
pub fn scan(value: &Value) -> Vec<Finding> {
    let mut findings = Vec::new();
    walk(value, "", FieldKind::Structural, &mut findings);
    findings
}

fn walk(value: &Value, path: &str, kind: FieldKind, out: &mut Vec<Finding>) {
    match value {
        Value::String(text) => {
            if let Some(reason) = check_str(text, kind) {
                out.push(Finding {
                    path: path.to_owned(),
                    reason,
                });
            }
        }
        Value::Array(items) => {
            for (index, item) in items.iter().enumerate() {
                walk(item, &format!("{path}/{index}"), kind, out);
            }
        }
        Value::Object(map) => {
            if map.contains_key("$bytes") {
                return;
            }
            for (key, item) in map {
                let child = format!("{path}/{key}");
                // Map keys are ids (conversation ids, node ids, op hashes, entry keys).
                if !key.starts_with('$')
                    && let Some(reason) = check_str(key, FieldKind::Id)
                {
                    out.push(Finding {
                        path: child.clone(),
                        reason,
                    });
                }
                let inner = if key.starts_with('$') {
                    kind
                } else {
                    field_kind(key)
                };
                walk(item, &child, inner, out);
            }
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn paths_are_refused_in_structure_but_allowed_in_prose() {
        assert_eq!(
            check_str(r"C:\Users\me\x", FieldKind::Structural),
            Some("absolute_path")
        );
        assert_eq!(check_str("/home/me", FieldKind::Id), Some("absolute_path"));
        assert_eq!(
            check_str("file://x", FieldKind::Structural),
            Some("absolute_path")
        );
        assert_eq!(check_str(r"fix C:\foo", FieldKind::Prose), None);
    }

    #[test]
    fn secrets_are_refused_everywhere() {
        for text in [
            "sk-abcdefghijk",
            "ghp_abcdefghij",
            "xoxb-1",
            "Bearer x",
            "-----BEGIN KEY",
        ] {
            assert_eq!(check_str(text, FieldKind::Prose), Some("secret"), "{text}");
        }
    }

    #[test]
    fn high_entropy_runs_are_refused_outside_ids() {
        let random = "q8Zr3LmX0vT7bN2cK9wPf4Ys6Hd1Ju5G";
        assert_eq!(
            check_str(random, FieldKind::Structural),
            Some("high_entropy")
        );
        assert_eq!(check_str(random, FieldKind::Id), None);
        assert_eq!(check_str(&"a".repeat(40), FieldKind::Structural), None);
    }

    #[test]
    fn scan_walks_keys_values_and_skips_bytes() {
        let doc = json!({
            "title": r"fix C:\foo",
            "nodes": { "/tmp/x": { "appVersion": "1.0" } },
            "sig": { "$bytes": "AAAA" },
            "ext": { "token": "Bearer abc" }
        });
        let findings = scan(&doc);
        assert_eq!(findings.len(), 2, "{findings:?}");
    }
}
