//! samod (automerge-repo) messages as the gate sees them: a CBOR map with a `type`, read and
//! rewritten with `ciborium`. Messages the gate does not modify are never re-encoded.
//!
//! samod-core 0.9.0 keeps its own message codec private, so the gate reads only the fields it rules
//! on: `type`, `senderId`, `targetId`, `documentId`, `data`, `add` and `remove`. Every other key is
//! kept as received when a message is rewritten.

use ciborium::Value;

/// The largest frame on `meridian/automerge-repo/1` (the Kotlin side's `AutomergeFrame.MAX_BYTES`).
pub const MAX_FRAME: usize = 4 * 1024 * 1024;

/// One decoded message.
#[derive(Clone, Debug, PartialEq)]
pub struct Message {
    entries: Vec<(Value, Value)>,
}

impl Message {
    /// Decodes a frame payload. `None` for bad CBOR, a non-map, or a map with no text `type`.
    #[must_use]
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let value: Value = ciborium::from_reader(bytes).ok()?;
        let Value::Map(entries) = value else {
            return None;
        };
        let message = Self { entries };
        message.text("type")?;
        Some(message)
    }

    /// Builds a message from `(key, value)` pairs, in order.
    #[must_use]
    pub fn new(entries: Vec<(&str, Value)>) -> Self {
        Self {
            entries: entries
                .into_iter()
                .map(|(key, value)| (Value::Text(key.to_owned()), value))
                .collect(),
        }
    }

    /// Encodes the message.
    ///
    /// # Panics
    ///
    /// Never: writing CBOR into a `Vec` cannot fail.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        ciborium::into_writer(&Value::Map(self.entries.clone()), &mut out)
            .expect("writing to a Vec cannot fail");
        out
    }

    fn get(&self, key: &str) -> Option<&Value> {
        self.entries
            .iter()
            .find(|(k, _)| k.as_text() == Some(key))
            .map(|(_, v)| v)
    }

    /// A text field.
    #[must_use]
    pub fn text(&self, key: &str) -> Option<&str> {
        self.get(key)?.as_text()
    }

    /// Whether a field is present.
    #[must_use]
    pub fn has(&self, key: &str) -> bool {
        self.get(key).is_some()
    }

    /// The message type.
    #[must_use]
    pub fn kind(&self) -> &str {
        self.text("type").unwrap_or_default()
    }

    /// The `data` bytes.
    #[must_use]
    pub fn data(&self) -> Option<&[u8]> {
        self.get("data")?.as_bytes().map(Vec::as_slice)
    }

    /// A list of text values (`add`, `remove`); `None` when absent or not a list of text.
    #[must_use]
    pub fn text_list(&self, key: &str) -> Option<Vec<String>> {
        self.get(key)?
            .as_array()?
            .iter()
            .map(|item| item.as_text().map(str::to_owned))
            .collect()
    }

    /// Replaces (or appends) a field, keeping the position of an existing key.
    pub fn set(&mut self, key: &str, value: Value) {
        match self
            .entries
            .iter_mut()
            .find(|(k, _)| k.as_text() == Some(key))
        {
            Some((_, slot)) => *slot = value,
            None => self.entries.push((Value::Text(key.to_owned()), value)),
        }
    }

    /// A `doc-unavailable` from `sender` to `target`, in samod's field order.
    #[must_use]
    pub fn doc_unavailable(sender: &str, target: &str, document: &str) -> Self {
        Self::new(vec![
            ("type", Value::Text("doc-unavailable".into())),
            ("senderId", Value::Text(sender.into())),
            ("targetId", Value::Text(target.into())),
            ("documentId", Value::Text(document.into())),
        ])
    }
}

/// A text list as a CBOR array.
#[must_use]
pub fn text_array(items: &[String]) -> Value {
    Value::Array(items.iter().map(|item| Value::Text(item.clone())).collect())
}
