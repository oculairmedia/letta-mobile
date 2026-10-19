# Module Letta Mobile API

Letta Mobile shares conversation state, transport, rendering and plugin contracts
across Android, desktop and browser hosts. Start with the module that owns the
boundary you are integrating: `:core:ids` supplies typed identities,
`:core:runtime` defines turn and delivery contracts, `:sharedLogic` implements
domain behavior, `:sharedUI` renders shared pages, and `:plugin-api` defines the
capabilities available to plugins.

Each module overview describes its packages and ownership boundaries. Declaration
pages describe parameters and behavior; architecture decisions and deployment
procedures live in the repository's documentation directory.

## Reading platform tags

The common source set defines portable contracts. Android, JVM, native and Wasm
tags identify the source sets that provide or consume a declaration. Repeated
signatures often represent the same common declaration in several targets, or
an `expect` contract with platform `actual` implementations. Select a platform
to inspect its available API; a platform tag does not promise a particular host
has exposed that feature in its user interface.
