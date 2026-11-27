//! The doc registry (catalog-v1 §1.3) on core-store's [`DocRegistry`]: the documents this node knows
//! the constellation lists, rebuilt from the root pointer, the constellation doc's index and the
//! notebook index. The gate serves only registered documents.

use core_membership::MembershipView;
use core_proto::AgentId;
use core_store::{CatalogDoc, DocId, DocRegistry, DocState, StoreError};

use crate::ids::{DocClass, DocUrl};
use crate::view::ConstellationView;

/// What the registry should hold, from the sources a node has.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RegistrySources {
    /// The current root (C1's `catalog_root`).
    pub root: Option<DocId>,
    /// Earlier roots (superseded by `catalog.root.move`).
    pub superseded_roots: Vec<DocId>,
    /// The shell doc and every agent doc the constellation doc lists, with the agent.
    pub listed: Vec<(DocClass, DocId, Option<AgentId>)>,
    /// The notebooks the local notebook index holds.
    pub notebooks: Vec<DocId>,
}

impl RegistrySources {
    /// The sources from a membership evaluation and (once synced) the constellation view.
    #[must_use]
    pub fn from_views(
        membership: &MembershipView,
        constellation: Option<&ConstellationView>,
    ) -> Self {
        let mut sources = Self {
            root: membership
                .catalog_root()
                .map(|root| DocId::from_bytes(root.0)),
            superseded_roots: membership
                .superseded_roots()
                .iter()
                .map(|root| DocId::from_bytes(root.0))
                .collect(),
            ..Self::default()
        };
        if let Some(view) = constellation {
            if let Some(shell) = view
                .shell_doc
                .as_deref()
                .and_then(|u| u.parse::<DocUrl>().ok())
            {
                sources.listed.push((DocClass::Shell, shell.doc_id(), None));
            }
            for (agent, entry) in &view.agents {
                for url in std::iter::once(entry.entry.doc_url.as_deref())
                    .flatten()
                    .chain(entry.superseded_doc_urls.iter().map(String::as_str))
                {
                    if let Ok(url) = url.parse::<DocUrl>() {
                        sources.listed.push((
                            DocClass::Agent,
                            url.doc_id(),
                            Some(AgentId::new(agent.clone())),
                        ));
                    }
                }
            }
        }
        sources
    }
}

/// Registers every listed document not yet registered and marks superseded roots. Never removes a
/// registration (retiring is the rollover's job). Returns the newly registered ids, for `find`.
///
/// # Errors
///
/// A store error.
pub fn sync_registry(
    registry: &mut dyn DocRegistry,
    sources: &RegistrySources,
    now_ms: u64,
) -> Result<Vec<DocId>, StoreError> {
    let mut wanted: Vec<(DocClass, DocId, Option<AgentId>, DocState)> = Vec::new();
    if let Some(root) = sources.root {
        wanted.push((DocClass::Constellation, root, None, DocState::Live));
    }
    for old in &sources.superseded_roots {
        wanted.push((DocClass::Constellation, *old, None, DocState::Superseded));
    }
    for (class, id, agent) in &sources.listed {
        wanted.push((*class, *id, agent.clone(), DocState::Live));
    }
    for id in &sources.notebooks {
        wanted.push((DocClass::Notebook, *id, None, DocState::Live));
    }
    let mut added = Vec::new();
    for (class, doc_id, agent_id, state) in wanted {
        if let Some(existing) = registry.catalog_doc(&doc_id)? {
            if state == DocState::Superseded && existing.state == DocState::Live {
                registry.set_doc_state(&doc_id, DocState::Superseded)?;
            }
        } else {
            registry.register_doc(&CatalogDoc {
                doc_id,
                class: class.as_str().to_owned(),
                agent_id,
                state,
                registered_at_ms: now_ms,
            })?;
            added.push(doc_id);
        }
    }
    Ok(added)
}
