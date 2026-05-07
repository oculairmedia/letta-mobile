# Letta Mobile Architecture Diagrams

## 1. Overall Module Structure

```mermaid
flowchart TB
    subgraph App[":app"]
        A1["MainActivity"]
        A2["LettaApplication"]
        A3["DI Modules"]
        A4["Navigation"]
    end

    subgraph Features[":feature-*"]
        F1[":feature-chat"]
        F2[":feature-admin"]
        F3[":feature-home"]
        F4[":feature-settings"]
    end

    subgraph Foundation[":core :designsystem :bot"]
        FND["Foundation Modules"]
    end

    subgraph Plugins[":plugin-api :plugin-host"]
        PLG["Plugin System"]
    end

    App --> Features
    Features --> Foundation
    Features --> Plugins
    App --> Plugins
```

## 2. Foundation Modules

```mermaid
flowchart LR
    subgraph Core[":core"]
        D1["data/"]
        D2["domain/"]
        D3["util/"]
    end

    subgraph DS[":designsystem"]
        DS1["ui/components/"]
        DS2["ui/icons/"]
        DS3["ui/theme/"]
    end

    subgraph Bot[":bot"]
        B1["WsBotClient"]
        B2["Protocol"]
    end

    Core --> DS
    Core --> Bot
```

## 3. Feature Modules

```mermaid
flowchart TB
    subgraph Chat[":feature-chat"]
        C1["ChatScreen"]
        C2["AgentScaffold"]
        C3["ConversationsScreen"]
    end

    subgraph Admin[":feature-admin"]
        A1["AgentListScreen"]
        A2["ConfigScreen"]
        A3["TemplatesScreen"]
    end

    subgraph Home[":feature-home"]
        H1["HomeScreen"]
        H2["ProjectsScreen"]
    end

    Chat -.-> Bot[":bot"]
    Chat -.-> DS[":designsystem"]
    Admin -.-> DS
    Home -.-> DS
```

## 4. Plugin System

```mermaid
flowchart TB
    subgraph PluginAPI[":plugin-api"]
        I1["LettaPlugin"]
        I2["PluginExtension"]
        I3["ScreenExtension"]
        I4["ThemeExtension"]
    end

    subgraph PluginHost[":plugin-host"]
        H1["PluginManager"]
        H2["PluginDiscoverer"]
        H3["PluginHostModule"]
    end

    subgraph Lettacode[":plugins-lettacode"]
        L1["LettaCodePlugin"]
    end

    PluginAPI --> PluginHost
    PluginHost --> App[":app"]
    Lettacode -.-> PluginAPI
    PluginHost -.-> Lettacode
```

## 5. Extension Points

```mermaid
flowchart TB
    subgraph NavExt["Navigation"]
        N1["AdaptiveScaffold"]
        N2["LettaBottomBar"]
        N3["AppNavGraph"]
    end

    subgraph ThemeExt["Theme"]
        T1["LettaTheme"]
        T2["ThemePreset"]
    end

    subgraph EventExt["Events"]
        E1["PluginEventListener"]
        E2["EventBus"]
    end

    NavExt --> App[":app"]
    ThemeExt --> App
    EventExt --> App
```

## 6. Migration Phases

```mermaid
flowchart LR
    P1["1. Extract Chat"] --> P2["2. Extract Admin"]
    P2 --> P3["3. Extract Home"]
    P3 --> P4["4. Plugin System"]
    P4 --> P5["5. Native Modules"]

    style P1 fill:#e8f5e9
    style P2 fill:#e8f5e9
    style P3 fill:#fff3e0
    style P4 fill:#fff3e0
    style P5 fill:#ffebee
```

## 7. WebSocket Connection Flow

```mermaid
flowchart TB
    subgraph Client["WsBotClient"]
        HTTP["HTTP Client"]
        WS["WebSocket Client"]
        State["ConnectionState"]
        Routes["Routing Tables"]
    end

    subgraph Gateway["Gateway"]
        G1["Receive frame"]
        G2["Parse type"]
        G3["Route session"]
    end

    WS <--> Gateway

    subgraph States["ConnectionState"]
        CLOSED["CLOSED"]
        CONNECTING["CONNECTING"]
        READY["READY"]
        PROCESSING["PROCESSING"]
        RECONNECTING["RECONNECTING"]
    end

    CONNECTING --> |"session_init"| READY
    READY --> |"send"| PROCESSING
    PROCESSING --> |"result"| READY
    PROCESSING --> |"disconnect"| RECONNECTING
```

## 8. Inbound Demux

```mermaid
flowchart TB
    IN["Inbound Frame"] --> PARSE["Parse conv_id + request_id"]

    PARSE --> T1{"activeRoutes[convId]<br/>exact match?"}
    T1 --> |"Hit"| OK1["Route to channel"]
    T1 --> |"Miss"| T2A

    T2A{"request_id match<br/>activeRoutes?"}
    T2A --> |"Hit"| OK2["Route to channel"]
    T2A --> |"Miss"| T2B

    T2B{"request_id match<br/>pendingRoutes?"}
    T2B --> |"Hit"| OK3["Route to channel"]
    T2B --> |"Miss"| T3

    T3{"Sole in-flight<br/>route?"}
    T3 --> |"Yes"| OK4["Route to channel"]
    T3 --> |"No"| DROP["Drop frame"]
```

## 9. Session Initiation

```mermaid
flowchart TB
    A["ensureSession()"] --> B{"needsNewSession?"}
    B --> |"No"| DONE["return"]
    B --> |"Yes"| C{"Agent switch?"}
    C --> |"Yes"| H1["send session_close"]
    H1 --> H2["close socket"]
    H2 --> H3["openSocketLocked()"]
    C --> |"No"| I1["send session_close"]
    I1 --> I2["openSocketLocked()"]
    H3 --> J["initializeSessionLocked()"]
    I2 --> J
    J --> K{"session_init?"}
    K --> |"Success"| N["update state"]
    N --> DONE
    K --> |"Timeout"| ERROR["throw"]
```

## 10. Reconnection Logic

```mermaid
flowchart TB
    DISCONNECT["Unexpected disconnect"] --> G1{"isUserClosing?"}
    G1 --> |"Yes"| STOP["CLOSED"]
    G1 --> |"No"| START["RECONNECTING"]

    START --> LOOP["Retry loop"]
    LOOP --> DELAY["delay backoff"]
    DELAY --> CONNECT["openSocket + init"]
    CONNECT --> SUCCESS{"Success?"}
    SUCCESS --> |"Yes"| DONE["READY"]
    SUCCESS --> |"No"| INC["attempt++"]
    INC --> LOOP

    DELAY_LABELS["1s → 2s → 5s → 10s → 30s (capped)"]
    SUCCESS -.-> DELAY_LABELS
```