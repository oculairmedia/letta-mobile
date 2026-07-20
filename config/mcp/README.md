# Serena MCP client examples

Run `scripts/mcp/serena.sh setup`, then configure an MCP client to invoke the
wrapper. The wrapper resolves the repository root itself, pins Serena, confines
all caches/configuration to ignored `.local/`, and applies process limits.

Replace `/absolute/path/to/letta-mobile` below. Do not commit a generated client
configuration: it may contain other servers, tokens, or user-specific paths.

Claude/Cursor-style JSON:

```json
{
  "mcpServers": {
    "serena-letta-mobile": {
      "command": "/absolute/path/to/letta-mobile/scripts/mcp/serena.sh",
      "args": ["serve"]
    }
  }
}
```

Codex TOML:

```toml
[mcp_servers.serena-letta-mobile]
command = "/absolute/path/to/letta-mobile/scripts/mcp/serena.sh"
args = ["serve"]
startup_timeout_sec = 60
tool_timeout_sec = 120
```

No environment variables or credentials are required. `serve` uses stdio and
starts with the checked-in project file. Use `scripts/mcp/serena.sh clean` to
remove this worktree's Serena/uv cache, logs, and project data. It only removes
`.local/serena` and `.local/uv`, never `~/.serena` or another worktree.
