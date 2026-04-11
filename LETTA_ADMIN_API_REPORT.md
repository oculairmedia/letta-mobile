# Letta Admin/Configuration API Capabilities Report

**Report Date**: April 2026
**API Version**: 1.0+ (as tested in letta-ai/letta repository)
**Documentation Sources**: 
- Official Letta API Client Skill Documentation
- Letta GitHub Repository Source Code
- REST API Router Implementations

---

## Executive Summary

This report documents comprehensive admin and configuration capabilities available through the Letta API. The Letta platform provides a robust set of REST/SDK endpoints for:

- **Agent Management**: Creation, configuration, deployment, and lifecycle management
- **Memory Architecture**: Core memory blocks, archival memory (passages), and memory templates
- **LLM/Model Configuration**: Provider setup, model selection, temperature, token limits, reasoning settings
- **Tool Management**: Custom tool creation, attachment, configuration, and MCP server integration
- **Source/Document Management**: File handling, folder/archive creation, document ingestion
- **Multi-Identity Support**: User context, identity properties, agent associations
- **Conversation Management**: Multi-user sessions, conversation history, message management

---

## 1. AGENT CONFIGURATION OPTIONS

### 1.1 Agent Creation

**API Endpoint**: `POST /agents`

**Primary Operation**: `agents.create()`

**Configuration Fields** (via `CreateAgent` schema):

#### Core Identity & Naming
- **name**: `str` - Auto-generated random username if not provided
- **description**: `Optional[str]` - Human-readable description
- **metadata**: `Optional[Dict]` - Custom metadata dictionary
- **agent_type**: `AgentType` - Agent behavior template:
  - `letta_v1_agent` (default) - Simplified loop, no heartbeats
  - `memgpt_agent` - Original MemGPT tool set
  - `memgpt_v2_agent` - Refreshed MemGPT tools
  - `react_agent` - Basic ReAct pattern, no memory tools
  - `workflow_agent` - Auto-clearing message buffer
  - `split_thread_agent`
  - `sleeptime_agent` - Background memory processing
  - `voice_convo_agent` - Voice-based conversations
  - `voice_sleeptime_agent` - Voice + background memory

#### System & Behavior Configuration
- **system**: `Optional[str]` - System prompt for agent (custom persona)
- **timezone**: `Optional[str]` - IANA format timezone (e.g., "America/New_York")
- **message_buffer_autoclear**: `bool` - If True, agent forgets previous messages (retains memory blocks)
- **enable_sleeptime**: `Optional[bool]` - Background memory refinement between conversations

#### Memory Configuration
- **memory_blocks**: `Optional[List[CreateBlock]]` - Initial memory blocks to create:
  - **label**: Block label (e.g., "human", "persona", custom)
  - **value**: Block content/text
  - **limit**: Character limit (default: 12000)
  - **description**: Block description
  - **tags**: List of tags
  
- **block_ids**: `Optional[List[BlockId]]` - Attach existing block IDs to agent

#### Tool Configuration
- **tool_ids**: `Optional[List[ToolId]]` - Attach specific tools by ID
- **include_base_tools**: `bool` (default: True) - Attach Letta core memory tools
- **include_multi_agent_tools**: `bool` (default: False) - Enable multi-agent communication tools
- **include_base_tool_rules**: `Optional[bool]` - Attach default tool execution rules

#### Model & LLM Configuration
- **model**: `Optional[str]` - Model handle (format: `provider/model-name`)
  - Examples: `anthropic/claude-sonnet-4-5-20250929`, `openai/gpt-4`, `google_vertex/gemini-2-flash`
- **embedding**: `Optional[str]` - Embedding model (format: `provider/model-name`)
  - Examples: `openai/text-embedding-3-small`, `anthropic/claude-embedding`
- **model_settings**: `Optional[ModelSettingsUnion]` - Provider-specific settings:
  - Temperature, max_tokens, reasoning settings, tool_call_parser, etc.
- **compaction_settings**: `Optional[CompactionSettings]` - Memory compaction/summarization behavior
- **context_window_limit**: `Optional[int]` - Override model's context window

#### File Handling Configuration
- **max_files_open**: `Optional[int]` - Maximum simultaneous open files (default: auto-calculated)
- **per_file_view_window_char_limit**: `Optional[int]` - Max chars per file view (default: auto-calculated)

#### Multi-User & Identity
- **identity_ids**: `Optional[List[IdentityId]]` - Associate with identity profiles

#### Folder & Source Attachment
- **folder_ids**: `Optional[List[SourceId]]` - Attach document folders
- **include_default_source**: `bool` (deprecated, default: False)

#### Tool Execution & Secrets
- **secrets**: `Optional[Dict[str, str]]` - Environment variables for tool execution

#### Initial Messages
- **initial_message_sequence**: `Optional[List[MessageCreate]]` - Pre-populate agent with messages

#### Deprecated Fields (for backwards compatibility)
- llm_config, embedding_config, response_format, max_tokens, max_reasoning_tokens, enable_reasoner, etc.

---

### 1.2 Agent Update

**API Endpoint**: `PATCH /agents/{agent_id}`

**Primary Operation**: `agents.update()`

**Updateable Fields** (via `UpdateAgent` schema):

- **name**: Update agent name
- **system**: Update system prompt
- **description**: Update description
- **metadata**: Update metadata
- **timezone**: Update timezone
- **tags**: Update tags list
- **tool_ids**: Update attached tools
- **folder_ids**: Update attached folders
- **block_ids**: Update attached blocks
- **message_ids**: Prune specific messages from context
- **model**: Change LLM model
- **embedding**: Change embedding model
- **model_settings**: Update model-specific settings
- **compaction_settings**: Update memory compaction behavior
- **secrets**: Update tool execution environment variables
- **message_buffer_autoclear**: Toggle message history clearing
- **enable_sleeptime**: Toggle background memory processing
- **max_files_open**: Adjust max open files
- **per_file_view_window_char_limit**: Adjust per-file char limit
- **hidden**: Hide agent from listings
- **identity_ids**: Associate with identities
- **tool_rules**: Update tool execution rules
- **last_run_completion**, **last_run_duration_ms**, **last_stop_reason**: Metadata from last execution

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/agent.py#L206-L561](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/agent.py#L206-L561)

---

## 2. MODEL/LLM CONFIGURATION

### 2.1 Model Handles & Selection

Models are specified using handles in the format: **`provider/model-name`**

**Supported Providers** (via `LLMConfig.model_endpoint_type`):
- openai, anthropic, google_ai, google_vertex, azure, groq
- ollama, webui, webui-legacy, lmstudio, lmstudio-legacy, llamacpp, koboldcpp, vllm
- hugging-face, mistral, together, bedrock, deepseek, xai, zai, zai_coding
- baseten, fireworks, openrouter, chatgpt_oauth

### 2.2 Model Settings Configuration

**Schema**: `ModelSettingsUnion` - Provider-specific configuration classes:

#### OpenAI Models
```python
OpenAIModelSettings:
  - temperature: float (0.0-2.0)
  - top_p: Optional[float]
  - frequency_penalty: Optional[float] (-2.0-2.0)
  - presence_penalty: Optional[float] (-2.0-2.0)
  - parallel_tool_calls: Optional[bool]
  - max_tokens: Optional[int]
  - reasoning_effort: Optional["low" | "medium" | "high"]
  - max_reasoning_tokens: Optional[int] (min: 1024)
```

#### Anthropic Models
```python
AnthropicModelSettings:
  - temperature: Optional[float]
  - max_tokens: Optional[int]
  - thinking: Optional[dict] - Extended thinking config
    - type: "enabled"
    - budget_tokens: int
  - effort: Optional["low" | "medium" | "high"]
```

#### Google Models
```python
GoogleAIModelSettings / GoogleVertexModelSettings:
  - temperature: Optional[float]
  - max_tokens: Optional[int]
  - top_p: Optional[float]
  - top_k: Optional[int]
  - reasoning_effort: Optional["low" | "medium" | "high" | "max"]
```

#### Model-Specific Fields:
- **temperature**: Controls randomness (0=deterministic, 1+=creative)
- **max_tokens**: Output length limit
- **max_reasoning_tokens**: Budget for extended thinking
- **reasoning_effort**: Thinking quality/depth
- **parallel_tool_calls**: Enable concurrent tool execution
- **response_format**: Structured output (json_object, json_schema, etc.)
- **top_p**, **top_k**: Sampling parameters
- **frequency_penalty**, **presence_penalty**: Repetition control

### 2.3 Embedding Configuration

**Schema**: `EmbeddingConfig` or handle string `provider/model-name`

**Configuration Fields**:
- **embedding_endpoint_type**: Provider type
- **embedding_model**: Model identifier
- **embedding_dim**: Embedding vector dimension
- **embedding_chunk_size**: Default chunk size for document splitting
- **provider_name**: Explicit provider name

**Supported Embedding Providers**:
- openai, anthropic, bedrock, google_ai, google_vertex, azure
- groq, ollama, webui, lmstudio, llamacpp, vllm, hugging-face, mistral

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/llm_config.py#L19-L733](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/llm_config.py#L19-L733)

---

## 3. MEMORY MANAGEMENT

### 3.1 Core Memory Blocks

**API Endpoint**: `/blocks` (List, Create, Update, Delete, Retrieve)

**Block Structure** (via `Block` schema):

#### Core Fields
- **id**: Unique identifier (auto-generated)
- **label**: Memory block label (e.g., "human", "persona", custom)
- **value**: Block content/text (up to 12,000 characters by default)
- **limit**: Character limit for block
- **description**: Block description/purpose

#### Access Control
- **read_only**: `bool` - If True, agent cannot modify
- **hidden**: `Optional[bool]` - Hide from listings

#### Metadata
- **tags**: `List[str]` - Tag associations
- **metadata**: `Optional[Dict]` - Custom metadata dictionary
- **created_by_id**: User who created
- **last_updated_by_id**: User who last updated

#### Templating
- **is_template**: `bool` - Whether this is a reusable template
- **template_name**: `Optional[str]` - Template name
- **template_id**, **base_template_id**: Template associations

#### Organization & Project
- **project_id**: Project association

### 3.2 Archival Memory (Passages)

**API Endpoints**: 
- `/passages` - Create, list, search, update, delete
- `/archives` - Manage passage collections

**Passage Structure** (via `Passage` schema):

#### Core Fields
- **id**: Unique identifier
- **text**: Passage content
- **archive_id**: Parent archive ID
- **file_id**: Optional file association

#### Embedding & Search
- **embedding**: Vector embedding (auto-generated or provided)
- **embedding_config**: Embedding model configuration
- **created_at**: Creation timestamp

#### Metadata & Organization
- **file_name**: Original file name (if from file)
- **metadata**: Custom metadata dictionary
- **tags**: List of tags for filtering
- **organization_id**: Organization association

### 3.3 Archives (Passage Collections)

**API Endpoint**: `/archives` (CRUD operations)

**Archive Structure** (via `Archive` schema):

#### Core Fields
- **id**: Unique identifier
- **name**: Archive name
- **description**: Archive description
- **organization_id**: Owning organization

#### Configuration
- **vector_db_provider**: `VectorDBProvider` enum:
  - `NATIVE` (default) - PostgreSQL pgvector
  - `PINECONE` - Pinecone vector database
  - Other vector DB providers
  
#### Embedding
- **embedding_config**: Embedding model for passages

#### Metadata
- **metadata**: Custom metadata dictionary

### 3.4 Block Operations

**Create Block**:
```
POST /blocks
  label: str (required)
  value: str (required)
  limit: Optional[int]
  description: Optional[str]
  tags: Optional[List[str]]
  read_only: Optional[bool]
  is_template: Optional[bool]
```

**Update Block**:
```
PATCH /blocks/{block_id}
  value: Optional[str]
  limit: Optional[int]
  description: Optional[str]
  tags: Optional[List[str]]
```

**List Blocks**:
```
GET /blocks
  label: Optional[str]
  templates_only: Optional[bool]
  tags: Optional[List[str]]
  match_all_tags: Optional[bool]
  value_search: Optional[str] - Full-text search
  label_search: Optional[str]
  description_search: Optional[str]
  limit, offset, order, order_by - Pagination
```

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/block.py](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/block.py)

---

## 4. TOOL MANAGEMENT

### 4.1 Tool Creation

**API Endpoint**: `POST /tools`

**Tool Creation Schema** (via `ToolCreate`):

#### Source Code
- **source_code**: `str` (required) - Python or TypeScript source
- **source_type**: `str` (default: "python") - "python" or "typescript"
- **json_schema**: `Optional[Dict]` - Explicit JSON schema (required for TypeScript)
- **args_json_schema**: `Optional[Dict]` - Argument schema

#### Metadata
- **description**: Optional tool description
- **tags**: List of metadata tags
- **name**: Function name (auto-extracted from source_code)

#### Configuration
- **return_char_limit**: `int` (default: 500000, max: 1000000) - Max output size
- **pip_requirements**: `Optional[List[PipRequirement]]` - Python dependencies
- **npm_requirements**: `Optional[List[NpmRequirement]]` - JavaScript dependencies
- **default_requires_approval**: `Optional[bool]` - Require approval before execution
- **enable_parallel_execution**: `Optional[bool]` - Allow concurrent execution with other tools

### 4.2 Tool Configuration

**Tool Object** (via `Tool` schema):

#### Identification
- **id**: Unique tool ID (auto-generated)
- **name**: Function name
- **tool_type**: `ToolType` enum:
  - `CUSTOM` - User-created tool
  - `LETTA_CORE` - Core memory tools (core_memory_insert, etc.)
  - `LETTA_MEMORY_CORE` - Memory operations
  - `LETTA_MULTI_AGENT_CORE` - Multi-agent communication
  - `LETTA_FILES_CORE` - File operations
  - `LETTA_VOICE_SLEEPTIME_CORE` - Voice tools
  - `MCP` - Model Context Protocol tools

#### Metadata
- **description**: Tool description
- **tags**: Metadata tags (includes MCP server name for MCP tools)
- **metadata_**: Additional metadata dictionary

#### Implementation
- **source_code**: Tool implementation
- **source_type**: "python" or "typescript"
- **json_schema**: JSON Schema for tool parameters
- **args_json_schema**: Detailed argument schema

#### Execution Settings
- **return_char_limit**: Max output length
- **default_requires_approval**: Approval requirement
- **enable_parallel_execution**: Parallel execution allowed
- **pip_requirements**: Python dependencies
- **npm_requirements**: JavaScript dependencies

### 4.3 Tool Attachment to Agents

**API Endpoint**: `POST /agents/{agent_id}/tools/{tool_id}` or via `tool_ids` in agent creation

**Operations**:
- Attach tool: `POST /agents/{agent_id}/tools/{tool_id}`
- Detach tool: `DELETE /agents/{agent_id}/tools/{tool_id}`
- List agent tools: `GET /agents/{agent_id}/tools`
- Bulk attach: `POST /agents/{agent_id}/tools/bulk`

### 4.4 Tool Search & Discovery

**API Endpoint**: `GET /tools`

**Query Parameters**:
- **name**: Filter by tool name
- **names**: Filter by multiple names
- **tool_ids**: Filter by specific IDs
- **search**: Case-insensitive partial match
- **tool_types**: Filter by tool types
- **exclude_tool_types**: Exclude specific types
- **return_only_letta_tools**: Letta built-ins only
- **exclude_letta_tools**: Exclude built-ins
- **tags**: Filter by tags
- **limit, offset, order**: Pagination

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/tool.py](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/tool.py)

---

## 5. SOURCE & DOCUMENT MANAGEMENT

### 5.1 Folder Management (Modern Approach)

**API Endpoint**: `/folders` (preferred over deprecated `/sources`)

**Folder Structure**:
- **id**: Unique folder ID
- **name**: Folder name
- **description**: Description
- **organization_id**: Owner organization

**File Operations**:
- **list_files**: List files in folder
- **upload**: Add files to folder
  - Supports: PDF, TXT, MD, DOCX, XLSX, CSV, etc.
  - Content extracted and chunked for embedding
  
- **delete_files**: Remove files from folder
- **get_folder_contents**: Browse folder contents

### 5.2 File Processing

**File Upload Process**:
1. Upload file to folder: `POST /folders/{folder_id}/files`
2. Automatic extraction (via MarkitDown or Mistral parser)
3. Chunking based on embedding_chunk_size
4. Embedding generation
5. Passage creation in archival memory

**Supported File Types**:
- Documents: PDF, DOCX, PPTX, TXT, MD
- Spreadsheets: XLSX, CSV, ODS
- Code: PY, JS, TS, JSON, XML
- Web: HTML, MHTML

### 5.3 Folder Attachment to Agents

**API Endpoint**: `POST /agents/{agent_id}/folders/{folder_id}`

**Operations**:
- Attach folder: `POST /agents/{agent_id}/folders/{folder_id}`
- Detach folder: `DELETE /agents/{agent_id}/folders/{folder_id}`
- List attached folders: `GET /agents/{agent_id}/folders`

**Folder-Agent Relationship**:
- Agent can search across all attached folders
- File passages automatically created in agent's archival memory
- Passages tagged with file name and folder ID

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/server/rest_api/routers/v1/sources.py](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/server/rest_api/routers/v1/sources.py)

---

## 6. MCP SERVER INTEGRATION

### 6.1 MCP Server Configuration

**API Endpoint**: `/mcp-servers` (CRUD operations)

**MCP Server Types**:

#### Stdio Server Configuration
```python
StdioMCPServer:
  - type: "stdio" (required)
  - command: str - Executable path
  - args: Optional[List[str]] - Command arguments
  - env: Optional[Dict[str, str]] - Environment variables
  - timeout: Optional[int] - Request timeout (ms)
```

#### SSE Server Configuration
```python
SSEMCPServer:
  - type: "sse" (required)
  - url: str - Server endpoint URL
  - timeout: Optional[int] - Request timeout (ms)
  - headers: Optional[Dict[str, str]] - Custom headers
```

#### Streamable HTTP Server Configuration
```python
StreamableHTTPMCPServer:
  - type: "http" (required)
  - url: str - Server endpoint
  - timeout: Optional[int] - Request timeout
  - headers: Optional[Dict[str, str]]
```

### 6.2 MCP Server Operations

**Create Server**:
```
POST /mcp-servers
  type: "stdio" | "sse" | "http"
  [server-specific config]
```

**List Servers**:
```
GET /mcp-servers
```

**Update Server**:
```
PATCH /mcp-servers/{mcp_server_id}
  [updatable fields]
```

**Delete Server**:
```
DELETE /mcp-servers/{mcp_server_id}
```

**Test Connection**:
```
POST /mcp-servers/{mcp_server_id}/test
```

### 6.3 MCP Tool Registration

**List Tools from MCP Server**:
```
GET /mcp-servers/{mcp_server_id}/tools
```

**Register Tool from MCP**:
```
POST /mcp-servers/{mcp_server_id}/tools/{tool_name}
  - Auto-generates schema from MCP tool definition
  - Creates Tool object attached to MCP server
```

**Execute MCP Tool**:
```
POST /mcp-servers/{mcp_server_id}/tools/{tool_id}/execute
  arguments: Dict[str, Any]
```

### 6.4 OAuth Integration for MCP

**OAuth Flow**:
- Initiate OAuth: `POST /mcp-servers` with oauth_config
- Stream events: SSE stream for authentication progress
- Complete: Tool becomes available for agent use

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/server/rest_api/routers/v1/mcp_servers.py](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/server/rest_api/routers/v1/mcp_servers.py)

---

## 7. SYSTEM PROMPTS & PERSONA CONFIGURATION

### 7.1 System Prompt

**Configuration**: Via `system` field in agent creation/update

**Structure**:
- **Dynamic Compilation**: System prompt is compiled at runtime from:
  - Agent's **system** field (base prompt)
  - All **memory blocks** (automatically injected)
  - Tool definitions (injected by agent loop)

**Best Practices**:
- Define agent personality in base system prompt
- Use memory blocks for human info, agent persona, custom context
- System prompt recompiled on each agent message

**Example**:
```
You are Claude, a helpful AI assistant.

# CORE MEMORY
## Persona
Friendly, thoughtful, detail-oriented AI.

## Human
Name: Alice, prefers concise responses, based in San Francisco.

# Your Capabilities
- Memory: Can recall conversation history via memory blocks
- Tools: Available tools [list injected automatically]
```

### 7.2 Memory Block Integration

**Block Labels** (standard):
- **"persona"**: Agent's personality and character
- **"human"**: Information about the user/human
- Custom labels: Agent-defined memory blocks

**Memory Operations**:
- Blocks automatically included in each message context
- Core memory blocks always in-context (count toward context window)
- Archival memory searched separately for long-term storage

**System Prompt Recompilation**:
- Blocks injected at request time
- Supports dynamic memory updates
- Character-limited per block (default 12,000 chars)

---

## 8. AGENT METADATA & IDENTITIES

### 8.1 Agent Metadata

**Metadata Fields**:
- **id**: Unique agent identifier
- **name**: Agent name
- **description**: Human-readable description
- **metadata**: Custom key-value dictionary
- **tags**: String tags for filtering/organization
- **hidden**: Boolean to hide from listings
- **project_id**: Project association
- **template_id**: Template source
- **deployment_id**: Deployment association

### 8.2 Identity System

**API Endpoint**: `/identities` (List, Create, Update, Delete)

**Identity Structure** (via `Identity` schema):

#### Core Fields
- **id**: Unique identity ID
- **identifier_key**: External identifier (e.g., user email, external ID)
- **name**: Display name
- **identity_type**: `IdentityType` enum:
  - `org` - Organization identity
  - `user` - User identity
  - `other` - Custom identity type

#### Properties
- **properties**: List of `IdentityProperty` objects:
  - **key**: Property name
  - **value**: Property value (string, number, boolean, or JSON)
  - **type**: `IdentityPropertyType` enum (string, number, boolean, json)

#### Associations
- **agent_ids**: Agents associated with identity (deprecated)
- **block_ids**: Memory blocks associated with identity (deprecated)
- **project_id**: Project scope
- **organization_id**: Organization ownership

### 8.3 Identity Creation

**API Endpoint**: `POST /identities`

**Fields**:
```python
IdentityCreate:
  - identifier_key: str (external ID, e.g., "user@example.com")
  - name: str (display name)
  - identity_type: IdentityType ("org", "user", "other")
  - project_id: Optional[str]
  - agent_ids: Optional[List[str]] (deprecated)
  - block_ids: Optional[List[str]] (deprecated)
  - properties: Optional[List[IdentityProperty]]
```

### 8.4 Agent-Identity Association

**Methods**:
1. At agent creation: Pass `identity_ids` list
2. Via agent update: Update `identity_ids`
3. Via identity API: Associate agent via properties

**Use Cases**:
- Multi-tenant agent sharing (same agent, different users via identities)
- User context injection (user properties in memory)
- Access control scoping

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/identity.py](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/schemas/identity.py)

---

## 9. SLEEP/TIME SETTINGS & SCHEDULING

### 9.1 Sleeptime Agents

**Configuration**: `enable_sleeptime: bool` in agent creation/update

**Features**:
- **Background Memory Processing**: Agent refines memory between conversations
- **Automatic Learning**: Agent extracts insights and updates core memory blocks
- **Compaction**: Summarization of message history
- **Agent Type**: `sleeptime_agent`, `voice_sleeptime_agent`

**Use Cases**:
- Long-running agents that learn over time
- Agents with many conversations needing memory consolidation
- Systems where agents improve their responses based on patterns

### 9.2 Compaction Settings

**Schema**: `CompactionSettings`

**Configuration**:
- **compression_mode**: Summarization strategy
- **max_messages_per_compaction**: Threshold for triggering compaction
- **compaction_schedule**: Background job scheduling

### 9.3 Timezone Configuration

**Field**: `timezone` in agent creation/update

**Format**: IANA timezone identifier (e.g., "America/New_York", "Europe/London", "Asia/Tokyo")

**Usage**:
- Timestamp generation
- Scheduling-aware responses
- Time-based agent behavior

### 9.4 Conversation Timestamps

**Tracked Fields**:
- **created_at**: Conversation creation timestamp
- **last_run_completion**: Last message completion time
- **last_run_duration_ms**: Previous message duration
- **last_message_at**: Timestamp of latest message

---

## 10. CONVERSATION & MESSAGE MANAGEMENT

### 10.1 Conversation Management

**API Endpoint**: `/conversations` (CRUD + advanced operations)

**Conversation Operations**:

#### Create Conversation
```
POST /conversations?agent_id={agent_id}
  agent_id: str (required)
  conversation_create: CreateConversation (optional)
    - summary: Optional[str]
```

#### List Conversations
```
GET /conversations
  agent_id: Optional[str] - Filter by agent
  limit: int (default: 50)
  after: Optional[str] - Pagination cursor
  summary_search: Optional[str] - Search summaries
  order: "asc" | "desc" (default: "desc")
  order_by: "created_at" | "last_run_completion" | "last_message_at"
```

#### Retrieve Conversation
```
GET /conversations/{conversation_id}
```

#### Update Conversation
```
PATCH /conversations/{conversation_id}
  conversation_update: UpdateConversation
    - summary: Optional[str]
```

#### Fork Conversation
```
POST /conversations/{conversation_id}/fork
  agent_id: Optional[str] - For "default" conversation mode
```

### 10.2 Message Management

**API Endpoint**: `/agents/{agent_id}/messages` (with conversation support)

**Message Operations**:

#### Send Message to Agent
```
POST /agents/{agent_id}/messages
  messages: List[Dict] - Message history
  max_steps: Optional[int] - Step limit
  max_retries: Optional[int]
  stream_tokens: Optional[bool]
  include_pings: Optional[bool]
```

#### Send to Conversation
```
POST /conversations/{conversation_id}/messages
  messages: List[Dict]
  max_steps: Optional[int]
  stream_tokens: Optional[bool]
  include_pings: Optional[bool]
```

#### Search Messages
```
GET /agents/{agent_id}/messages/search
  query: str - Full-text search
  limit: int
  offset: int
  date_range: Optional[Dict]
```

#### List Agent Messages (Direct Mode)
```
GET /agents/{agent_id}/messages
  limit: int
  offset: int
  order: "asc" | "desc"
```

### 10.3 Message Structure

**Message Object**:
```python
Message:
  - id: str
  - agent_id: str
  - conversation_id: Optional[str]
  - user_id: Optional[str]
  - role: "user" | "assistant" | "system"
  - content: str | List[Dict] (can be text or tool calls)
  - created_at: datetime
  - run_id: Optional[str] - Associated run ID
  - tool_calls: Optional[List[ToolCall]]
```

### 10.4 Conversation States

**Conversation Metadata**:
- **id**: Unique conversation ID
- **agent_id**: Associated agent
- **summary**: User-provided summary
- **created_at**: Creation timestamp
- **last_run_completion**: Last message timestamp
- **last_message_at**: Most recent message
- **message_count**: Number of messages

**Message Lifecycle**:
- User message added
- Agent processes (potentially multiple steps)
- Tool calls executed
- Response generated
- Messages persisted

**Source**: [https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/server/rest_api/routers/v1/conversations.py](https://github.com/letta-ai/letta/blob/f33324768950e6752f80d6c725873cc92d22f8b2/letta/server/rest_api/routers/v1/conversations.py)

---

## 11. ADDITIONAL ADMIN API ENDPOINTS

### 11.1 Agent List & Search

**API Endpoint**: `GET /agents`

**Query Parameters**:
- **name**: Filter by name
- **tags**: Filter by tags
- **match_all_tags**: AND vs OR tag matching
- **template_id**: Filter by template
- **base_template_id**: Filter by base template
- **identity_id**: Filter by identity
- **project_id**: Filter by project
- **query_text**: Full-text search
- **limit, before, after**: Pagination with cursors
- **include_relationships**: Optimize by specifying which relationships to load

### 11.2 Agent Statistics

**API Endpoint**: `GET /agents/{agent_id}/context-window`

**Returns**:
- Current context usage
- Message count
- Block sizes
- Available space

### 11.3 Tags Management

**API Endpoint**: `/tags` (List, Create, Delete)

**Operations**:
- Create tags: `POST /tags`
- List tags: `GET /tags`
- Delete tags: `DELETE /tags/{tag_id}`
- Search tags: `GET /tags?search={query}`

### 11.4 User & Organization Management

**API Endpoints**:
- `GET /users` - List users
- `GET /users/{user_id}` - Get user details
- `GET /organizations` - List organizations
- `GET /organizations/{org_id}` - Get org details

### 11.5 Streaming & Async Operations

**Streaming**:
- `POST /agents/{agent_id}/messages?stream=true` - SSE stream
- `stream_tokens: true` - Token-level streaming
- `include_pings: true` - Keep-alive pings

**Async Operations**:
- All endpoints support `async=true` parameter
- Returns async request handle for polling
- Status endpoint: `GET /runs/{run_id}`

### 11.6 Response Format Configuration

**Field**: `response_format` in agent model_settings

**Options**:
- `text` (default) - Plain text response
- `json_object` - Structured JSON output
- `json_schema` - JSON matching provided schema
- Provider-specific formats (e.g., OpenAI's structured output modes)

---

## 12. EXAMPLE PAYLOADS

### 12.1 Create Agent with Full Configuration

```python
import requests

payload = {
    "name": "Research Assistant",
    "description": "Conducts research and synthesizes information",
    "system": "You are a thorough research assistant. You systematically gather and analyze information.",
    "agent_type": "letta_v1_agent",
    "model": "anthropic/claude-sonnet-4-5-20250929",
    "embedding": "openai/text-embedding-3-small",
    "model_settings": {
        "temperature": 0.7,
        "max_tokens": 2048,
        "thinking": {
            "type": "enabled",
            "budget_tokens": 5000
        }
    },
    "memory_blocks": [
        {
            "label": "persona",
            "value": "Expert researcher with deep domain knowledge",
            "limit": 12000,
            "description": "Agent personality"
        },
        {
            "label": "human",
            "value": "User is a PhD student studying climate science",
            "limit": 8000,
            "description": "User context"
        }
    ],
    "tool_ids": ["tool_id_1", "tool_id_2"],
    "folder_ids": ["folder_id_1"],
    "tags": ["research", "production"],
    "timezone": "America/New_York",
    "enable_sleeptime": True,
    "max_files_open": 10,
    "per_file_view_window_char_limit": 20000,
    "include_base_tools": True,
    "secrets": {
        "API_KEY": "secret_key_value"
    }
}

response = requests.post(
    "https://api.letta.com/agents",
    json=payload,
    headers={"Authorization": "Bearer YOUR_API_KEY"}
)
```

### 12.2 Update Agent Configuration

```python
update_payload = {
    "system": "Updated system prompt with new instructions",
    "model_settings": {
        "temperature": 0.5,
        "max_tokens": 4096
    },
    "tags": ["updated", "v2"],
    "enable_sleeptime": True
}

response = requests.patch(
    f"https://api.letta.com/agents/{agent_id}",
    json=update_payload,
    headers={"Authorization": "Bearer YOUR_API_KEY"}
)
```

### 12.3 Create Custom Tool

```python
tool_payload = {
    "source_code": '''
def analyze_sentiment(text: str) -> str:
    """Analyze sentiment of given text.
    
    Args:
        text: The text to analyze
        
    Returns:
        Sentiment analysis result
    """
    from textblob import TextBlob
    blob = TextBlob(text)
    polarity = blob.sentiment.polarity
    if polarity > 0.1:
        return "positive"
    elif polarity < -0.1:
        return "negative"
    else:
        return "neutral"
''',
    "source_type": "python",
    "description": "Analyzes sentiment of text using TextBlob",
    "tags": ["nlp", "sentiment"],
    "return_char_limit": 500,
    "pip_requirements": [
        {"name": "textblob", "version": ">=0.17.0"}
    ]
}

response = requests.post(
    "https://api.letta.com/tools",
    json=tool_payload,
    headers={"Authorization": "Bearer YOUR_API_KEY"}
)
```

### 12.4 Create Memory Block

```python
block_payload = {
    "label": "project_context",
    "value": "Working on Q2 2026 marketing campaign. Key focus areas: social media, email, content marketing",
    "limit": 12000,
    "description": "Current project information",
    "tags": ["marketing", "2026"]
}

response = requests.post(
    "https://api.letta.com/blocks",
    json=block_payload,
    headers={"Authorization": "Bearer YOUR_API_KEY"}
)
```

### 12.5 Create Archive for Shared Passages

```python
archive_payload = {
    "name": "Company Knowledge Base",
    "description": "Shared archival memory for all company-wide information",
    "vector_db_provider": "NATIVE",
    "metadata": {
        "department": "engineering",
        "retention_days": 365
    }
}

response = requests.post(
    "https://api.letta.com/archives",
    json=archive_payload,
    headers={"Authorization": "Bearer YOUR_API_KEY"}
)
```

### 12.6 Send Message to Agent

```python
message_payload = {
    "messages": [
        {
            "role": "user",
            "content": "What are the key points about climate change from the attached documents?"
        }
    ],
    "max_steps": 10,
    "stream_tokens": False
}

response = requests.post(
    f"https://api.letta.com/agents/{agent_id}/messages",
    json=message_payload,
    headers={"Authorization": "Bearer YOUR_API_KEY"}
)
```

---

## 13. API REFERENCE SUMMARY TABLE

| Capability | Endpoint | Method | Key Fields |
|-----------|----------|--------|-----------|
| **Create Agent** | `/agents` | POST | name, system, model, memory_blocks, tool_ids, folder_ids |
| **Update Agent** | `/agents/{id}` | PATCH | system, model, tags, secrets, enable_sleeptime |
| **List Agents** | `/agents` | GET | tags, query_text, project_id, template_id |
| **Create Block** | `/blocks` | POST | label, value, limit, tags, description |
| **Update Block** | `/blocks/{id}` | PATCH | value, limit, tags |
| **Create Archive** | `/archives` | POST | name, description, embedding_config, vector_db_provider |
| **Create Tool** | `/tools` | POST | source_code, source_type, json_schema, pip_requirements |
| **Attach Tool** | `/agents/{id}/tools/{tool_id}` | POST | (no body) |
| **Create Folder** | `/folders` | POST | name, description |
| **Upload File** | `/folders/{id}/files` | POST | file, metadata |
| **Create Passage** | `/passages` | POST | text, archive_id, tags, metadata |
| **Search Passages** | `/passages/search` | POST | query, archive_id, tags, limit |
| **Create MCP Server** | `/mcp-servers` | POST | type, command/url, timeout |
| **Register MCP Tool** | `/mcp-servers/{id}/tools/{name}` | POST | (auto-schema generation) |
| **Create Identity** | `/identities` | POST | identifier_key, name, identity_type, properties |
| **Create Conversation** | `/conversations?agent_id={id}` | POST | summary |
| **Send Message** | `/agents/{id}/messages` | POST | messages, max_steps, stream_tokens |
| **Fork Conversation** | `/conversations/{id}/fork` | POST | agent_id (optional) |

---

## 14. CONFIGURATION BEST PRACTICES

### 14.1 Memory Management
- **Core Memory**: Use for frequently accessed information (persona, user context)
- **Archival Memory**: Use for long-term storage and semantic search
- **Block Limits**: Keep within 12,000 characters for performance
- **Search**: Always specify embedding config for passage search

### 14.2 Model Selection
- Match model to task complexity
- Use reasoning models (claude-opus, gpt-4o) for complex analysis
- Configure temperature based on determinism needs (0.0=deterministic, 1.0+=creative)
- Set max_tokens conservatively to control costs

### 14.3 Tool Configuration
- Keep tools focused and single-purpose
- Use proper docstrings for schema auto-generation (Python)
- Require explicit json_schema for TypeScript tools
- Configure return_char_limit to prevent context overflow

### 14.4 Folder & Document Management
- Organize documents logically across folders
- Tag passages for better searchability
- Set appropriate embedding_chunk_size (default: 1024)
- Use file_metadata for custom tracking

### 14.5 Scaling Considerations
- Use Conversations API for multi-user/multi-session per agent
- Enable sleeptime for agents with 100+ messages
- Archive old conversations to save context
- Use tags for agent organization and discovery

---

## 15. AUTHENTICATION & HEADERS

**All Admin API Calls Require**:

```http
Authorization: Bearer YOUR_API_KEY
X-Project: project_id (optional, defaults to user's default project)
```

**SDK Initialization**:

```python
from letta_client import Letta

# Cloud
client = Letta(api_key=os.getenv("LETTA_API_KEY"))

# Self-hosted
client = Letta(base_url="http://localhost:8283")
```

---

## 16. SOURCES & DOCUMENTATION

**Primary Sources**:
1. Letta GitHub Repository: https://github.com/letta-ai/letta
   - Schemas: `/letta/schemas/`
   - API Routes: `/letta/server/rest_api/routers/v1/`
   
2. Letta Official Documentation: https://docs.letta.com
   - Guides, API reference, examples
   
3. Letta API Client Skill: `/opt/skills/letta-api-client`
   - Working examples, patterns, best practices

**Key Files Reviewed**:
- `agent.py` - Agent schemas and configuration
- `llm_config.py` - LLM and model configuration
- `block.py` - Memory block definitions
- `tool.py` - Tool schemas and creation
- `passage.py` - Archival memory passages
- `archive.py` - Passage archive containers
- `identity.py` - Identity and multi-tenant support
- `agents.py` (router) - Agent API endpoints
- `tools.py` (router) - Tool API endpoints
- `blocks.py` (router) - Block API endpoints
- `conversations.py` (router) - Conversation API endpoints
- `mcp_servers.py` (router) - MCP integration endpoints

**Repository Commit**: f33324768950e6752f80d6c725873cc92d22f8b2

---

## 17. QUICK REFERENCE: MODEL HANDLES

**Popular LLM Models**:
```
anthropic/claude-opus-4-1
anthropic/claude-sonnet-4-5-20250929
anthropic/claude-haiku-3-5-20241022
openai/gpt-4-turbo
openai/gpt-4o
openai/gpt-4o-mini
google_vertex/gemini-2-0-flash
google_vertex/gemini-2-0-pro
google_ai/gemini-2-0-flash
groq/llama-3-1-70b-versatile
```

**Popular Embedding Models**:
```
openai/text-embedding-3-small (1536-dim)
openai/text-embedding-3-large (3072-dim)
anthropic/claude-embedding (1024-dim)
google_ai/text-embedding-004 (768-dim)
```

---

**Report Compiled**: April 8, 2026
**API Version Tested**: 1.0+ (Letta self-hosted)
**Status**: Comprehensive coverage of admin/configuration capabilities

