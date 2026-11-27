//! The exporter's checks before a payload is appended: the H1 lint (E5) and the secret
//! deny-scanner (E6).

use std::sync::LazyLock;

use regex::Regex;
use regex::bytes::Regex as BytesRegex;
use serde_json::Value;

use crate::layout::{AGENTS_DIR, CONVERSATIONS_DIR};

static ABSOLUTE_PATH: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(^|[\s(\x22'=])([A-Za-z]:[\\/]|/(home|Users|root|tmp)/)").expect("valid regex")
});

static COUNTER_CONVERSATION: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^local-conv-\d+$").expect("valid regex"));

static SECRET: LazyLock<BytesRegex> = LazyLock::new(|| {
    BytesRegex::new(concat!(
        r"sk-ant-[A-Za-z0-9_-]{16,}",
        r"|sk-(proj-)?[A-Za-z0-9_-]{32,}",
        r"|ghp_[A-Za-z0-9]{30,}",
        r"|xox[abp]-[A-Za-z0-9-]{10,}",
        r"|AKIA[0-9A-Z]{16}",
        r"|-----BEGIN [A-Z ]*PRIVATE KEY-----",
        r"|(?i-u:authorization:\s*(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,})",
    ))
    .expect("valid regex")
});

/// Bindings shorter than this are not scanned for (they would match ordinary text).
const MIN_BINDING_LEN: usize = 8;

/// The H1 lint of an agent record or conversation record about to be exported (E5). Returns one
/// finding per broken rule, as `I<n>:<path>:<field>`. Transcripts are never linted: H1 treats
/// persisted reminders as history.
#[must_use]
pub fn h1_lint(path: &str, content: &[u8]) -> Vec<String> {
    let is_agent = path.starts_with(&format!("{AGENTS_DIR}/"));
    let is_conversation =
        path.starts_with(&format!("{CONVERSATIONS_DIR}/")) && path.ends_with("/conversation.json");
    if !is_agent && !is_conversation {
        return Vec::new();
    }
    let Ok(Value::Object(record)) = serde_json::from_slice::<Value>(content) else {
        return Vec::new();
    };
    let mut findings = Vec::new();
    if let Some(Value::String(model)) = record.get("model")
        && !model.is_empty()
        && !model.starts_with("profile/")
    {
        findings.push(format!("I2:{path}:model"));
    }
    for field in [
        "cwd",
        "description",
        "memory_dir",
        "memoryDir",
        "MEMORY_DIR",
    ] {
        if let Some(Value::String(text)) = record.get(field)
            && ABSOLUTE_PATH.is_match(text)
        {
            findings.push(format!("I5:{path}:{field}"));
        }
    }
    if is_conversation
        && let Some(Value::String(id)) = record.get("id")
        && COUNTER_CONVERSATION.is_match(id)
    {
        findings.push(format!("I1:{path}:id"));
    }
    findings
}

/// The secret deny-scanner (E6): provider key shapes, `Authorization:` headers, PEM private keys,
/// and the node's own binding values.
#[derive(Clone, Debug, Default)]
pub struct SecretScanner {
    bindings: Vec<Vec<u8>>,
}

impl SecretScanner {
    /// A scanner that also refuses any of `bindings` (the node's binding values).
    #[must_use]
    pub fn new(bindings: impl IntoIterator<Item = String>) -> Self {
        Self {
            bindings: bindings
                .into_iter()
                .filter(|value| value.len() >= MIN_BINDING_LEN)
                .map(String::into_bytes)
                .collect(),
        }
    }

    /// The rule a payload's bytes break, if any.
    #[must_use]
    pub fn check(&self, bytes: &[u8]) -> Option<&'static str> {
        if SECRET.is_match(bytes) {
            return Some("secret_pattern");
        }
        self.bindings
            .iter()
            .any(|value| {
                bytes
                    .windows(value.len())
                    .any(|window| window == value.as_slice())
            })
            .then_some("binding_value")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_lint_flags_each_rule() {
        let agent = br#"{"model":"anthropic/claude","description":"created in /tmp/x0"}"#;
        assert_eq!(
            h1_lint("agents/YQ.json", agent),
            vec!["I2:agents/YQ.json:model", "I5:agents/YQ.json:description"]
        );
        let clean = br#"{"model":"profile/main","description":"a helper"}"#;
        assert!(h1_lint("agents/YQ.json", clean).is_empty());
        let conversation = br#"{"id":"local-conv-12","model":null}"#;
        assert_eq!(
            h1_lint("conversations/x/conversation.json", conversation),
            vec!["I1:conversations/x/conversation.json:id"]
        );
        assert!(h1_lint("conversations/x/messages.jsonl", agent).is_empty());
    }

    #[test]
    fn secrets_and_bindings_are_refused() {
        let scanner = SecretScanner::new(["lmstudio/a-main-key".to_owned(), "short".to_owned()]);
        assert_eq!(
            scanner.check(b"key sk-ant-abcdefghijklmnopqrstu"),
            Some("secret_pattern")
        );
        assert_eq!(
            scanner.check(b"Authorization: Bearer abcdefghij"),
            Some("secret_pattern")
        );
        assert_eq!(
            scanner.check(b"-----BEGIN OPENSSH PRIVATE KEY-----"),
            Some("secret_pattern")
        );
        assert_eq!(
            scanner.check(b"routed to lmstudio/a-main-key"),
            Some("binding_value")
        );
        assert_eq!(scanner.check(b"a short note about Bearer tokens"), None);
    }
}
