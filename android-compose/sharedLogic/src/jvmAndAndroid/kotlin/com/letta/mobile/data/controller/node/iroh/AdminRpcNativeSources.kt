package com.letta.mobile.data.controller.node.iroh

/**
 * Native command/catalog sources and local persistence locations for admin handlers. Missing sources keep the
 * existing capability-unavailable behavior.
 */
class AdminRpcNativeSources(
    /** Existing App Server session for native commands; null keeps command-backed routes unavailable. */
    val nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
    /**
     * The letta-code on-disk backend root (`LETTA_LOCAL_BACKEND_DIR`).
     * When set, admin READS the App Server exposes no command for — run/step
     * history, agent context, memory blocks — are served from it directly,
     * which is what lettashim did with the same directory. When null those
     * methods fail closed with a typed capability error; they never dial a
     * REST admin host.
     */
    val localBackendDir: String? = null,
    /**
     * Optional skills listing projection (device-status / skills_updated).
     * When null, skill.list returns an empty skills array until a catalog is wired.
     */
    val skillsListing: SkillsListingSource? = null,
    /**
     * The App Server's slash commands, observed from
     * `update_device_status`. Null falls back to a catalog that answers the built-in list
     * when a [nativeClient] is wired, and `capability_unavailable` when none is.
     */
    val slashCommands: NativeSlashCommandCatalog? = null,
    /**
     * JSON file holding the model exposure decisions
     * (`model.exposure.*`, filters `model.list`). Null keeps them in memory
     * for the router's lifetime (tests, stub CLI).
     */
    val modelExposureFile: String? = null,
)
