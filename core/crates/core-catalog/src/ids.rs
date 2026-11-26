//! Catalog document ids, URLs and classes.

use std::fmt;
use std::str::FromStr;

use core_store::DocId;
use samod::DocumentId;

use crate::error::CatalogError;

/// The URL a catalog document is referenced by: `automerge:<bs58check of the 16 id bytes>`.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct DocUrl(DocId);

impl DocUrl {
    /// The URL of a document id.
    #[must_use]
    pub const fn new(id: DocId) -> Self {
        Self(id)
    }

    /// The document id.
    #[must_use]
    pub const fn doc_id(&self) -> DocId {
        self.0
    }

    /// The bs58check form of the id, as samod's `documentId` field carries it.
    #[must_use]
    pub fn bs58(&self) -> String {
        samod_id(&self.0).to_string()
    }

    /// Parses the bs58check form of an id (samod's `documentId` field).
    ///
    /// # Errors
    ///
    /// [`CatalogError::BadDocumentId`] when it is not bs58check of 16 bytes.
    pub fn from_bs58(text: &str) -> Result<Self, CatalogError> {
        let id =
            DocumentId::from_str(text).map_err(|_| CatalogError::BadDocumentId(text.to_owned()))?;
        Ok(Self(doc_id_of(&id)?))
    }
}

impl fmt::Display for DocUrl {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "automerge:{}", self.bs58())
    }
}

impl fmt::Debug for DocUrl {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Display::fmt(self, formatter)
    }
}

impl FromStr for DocUrl {
    type Err = CatalogError;

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        let rest = text
            .strip_prefix("automerge:")
            .ok_or_else(|| CatalogError::BadDocumentId(text.to_owned()))?;
        Self::from_bs58(rest)
    }
}

/// samod's id for a document id.
pub(crate) fn samod_id(id: &DocId) -> DocumentId {
    DocumentId::try_from(id.as_bytes().to_vec()).expect("16 bytes is a samod document id")
}

/// The document id of a samod id.
pub(crate) fn doc_id_of(id: &DocumentId) -> Result<DocId, CatalogError> {
    DocId::from_slice(id.as_bytes()).ok_or_else(|| CatalogError::BadDocumentId(id.to_string()))
}

/// The class of a catalog document, which decides its schema and who may read or write it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum DocClass {
    /// The constellation document: meta, doc index, membership mirror, nodes, agent directory.
    Constellation,
    /// The shell document: shell-state entries, pins, archive flags, per-device prefs.
    Shell,
    /// One standard agent's document: placement copy, session tree, memory divergences.
    Agent,
    /// An existing `notebook/1` document.
    Notebook,
}

impl DocClass {
    /// Every class.
    pub const ALL: [Self; 4] = [
        Self::Constellation,
        Self::Shell,
        Self::Agent,
        Self::Notebook,
    ];

    /// The registry name: `constellation`, `shell`, `agent` or `notebook`.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Constellation => "constellation",
            Self::Shell => "shell",
            Self::Agent => "agent",
            Self::Notebook => "notebook",
        }
    }

    /// Parses a registry name.
    #[must_use]
    pub fn parse(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|class| class.as_str() == name)
    }

    /// Whether this is one of the three catalog classes (not a notebook).
    #[must_use]
    pub const fn is_catalog(self) -> bool {
        !matches!(self, Self::Notebook)
    }

    /// The root `schema` value of major 1, or `None` for notebooks (`notebook/1`, not ours).
    #[must_use]
    pub const fn schema_v1(self) -> Option<&'static str> {
        match self {
            Self::Constellation => Some("baken.catalog.constellation/1"),
            Self::Shell => Some("baken.catalog.shell/1"),
            Self::Agent => Some("baken.catalog.agent/1"),
            Self::Notebook => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls_round_trip_through_bs58check() {
        let url = DocUrl::new(DocId::from_bytes([7; 16]));
        let text = url.to_string();
        assert!(text.starts_with("automerge:"));
        assert_eq!(text.parse::<DocUrl>().expect("parses"), url);
        assert!("automerge:abc".parse::<DocUrl>().is_err());
        assert!("x".parse::<DocUrl>().is_err());
    }

    #[test]
    fn classes_round_trip() {
        for class in DocClass::ALL {
            assert_eq!(DocClass::parse(class.as_str()), Some(class));
        }
        assert_eq!(DocClass::parse("notes"), None);
    }
}
