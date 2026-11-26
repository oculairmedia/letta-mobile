//! The in-memory store runs the full store contract suite.

core_store::store_contract_suite!(core_store::MemoryStore::new);
