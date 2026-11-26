//! Path rules and the store_format 1 replicated allowlist (C1 §3.5, H1).

/// The store format this build exports and materializes.
pub const STORE_FORMAT: u64 = 1;
/// Maximum path length in bytes.
pub const MAX_PATH: usize = 512;

/// Whether `path` is well formed: relative, `/`-separated, non-empty segments, no `.` or `..`
/// segment, no backslash or NUL, at most [`MAX_PATH`] bytes.
#[must_use]
pub fn is_well_formed(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= MAX_PATH
        && !path.contains(['\\', '\0'])
        && path
            .split('/')
            .all(|segment| !segment.is_empty() && segment != "." && segment != "..")
}

/// Whether `path` is on the store_format 1 allowlist: an agent record, a conversation record, a
/// transcript or an attachment. Memfs travels as git objects and refs, never as paths.
#[must_use]
#[allow(clippy::case_sensitive_file_extension_comparisons)] // Paths are compared exactly.
pub fn is_replicated(path: &str) -> bool {
    if !is_well_formed(path) {
        return false;
    }
    let segments: Vec<&str> = path.split('/').collect();
    match segments.as_slice() {
        ["agents", file] => file.len() > ".json".len() && file.ends_with(".json"),
        ["conversations", _, "conversation.json" | "messages.jsonl"]
        | ["conversations", _, "blobs", _] => true,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allowlist() {
        assert!(is_replicated("agents/YWdlbnQ.json"));
        assert!(is_replicated("conversations/abc/messages.jsonl"));
        assert!(is_replicated("conversations/abc/conversation.json"));
        assert!(is_replicated("conversations/abc/blobs/img.png"));
        assert!(!is_replicated("settings.json"));
        assert!(!is_replicated("memfs/agent/.git/config"));
        assert!(!is_replicated("conversations/abc/system-prompt.json"));
        assert!(!is_well_formed("/agents/a.json"));
        assert!(!is_well_formed("agents/../x"));
        assert!(!is_well_formed("agents//x"));
        assert!(!is_well_formed(&"a".repeat(513)));
    }
}
