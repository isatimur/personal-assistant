# MCP Integration Design

## Goal

Connect the assistant to any MCP (Model Context Protocol) server via stdio transport, giving it access to thousands of community-built tools without writing new `ToolPort` implementations.

## Architecture

**Transport:** stdio only. Each MCP server runs as a child process; the assistant communicates with it over stdin/stdout using JSON-RPC 2.0.

**SDK:** `io.modelcontextprotocol.sdk:mcp` — the official Anthropic MCP Java SDK. Handles process lifecycle, protocol handshake (`initialize` / `initialized`), `tools/list`, and `tools/call`. No changes to LangChain4j or `providers/`.

**Config:** `~/.assistant/mcp-servers.json` in Claude Code-compatible format:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/timur"],
      "env": {}
    },
    "notion": {
      "command": "npx",
      "args": ["-y", "@notionhq/notion-mcp-server"],
      "env": { "NOTION_API_KEY": "secret_..." }
    }
  }
}
```

## Components

### `McpToolPort` (`tools/src/main/kotlin/com/assistant/mcp/McpToolPort.kt`)

Implements `ToolPort` and `Closeable`. One instance per MCP server.

- `name`: `"mcp:{serverName}"` (e.g. `"mcp:filesystem"`)
- `description`: first tool's server description, or `"MCP server: {serverName}"`
- Constructor: takes `serverName: String` + `ServerParameters` (command, args, env)
- `start()`: creates `StdioClientTransport`, calls `McpClient.sync(transport).initialize()`, then `listTools()` to build `CommandSpec` list
- `commands()`: returns specs built from `tools/list` — maps JSON Schema `properties` → `ParamSpec` (type mapped: `integer`→`"integer"`, `boolean`→`"boolean"`, everything else→`"string"`)
- `execute(call)`: calls `callTool(name, args)`, returns `Observation.Success(content)` or `Observation.Error(message)`
- `close()`: calls `client.close()`, kills subprocess

### `McpServerLoader` (`tools/src/main/kotlin/com/assistant/mcp/McpServerLoader.kt`)

Reads `~/.assistant/mcp-servers.json`. Returns `List<McpToolPort>`. If file missing or malformed — logs warning and returns empty list. Each server is started eagerly (so tool lists are resolved at startup).

### `tools/build.gradle.kts`

Add:
```kotlin
implementation("io.modelcontextprotocol.sdk:mcp:0.10.0")
```

### `Main.kt`

```kotlin
val mcpTools = McpServerLoader.load(globalDir)  // globalDir = ~/.assistant/
val allTools = listOf(...existing tools...) + mcpTools
```

`mcpTools` are `Closeable` — add to shutdown hook alongside other adapters.

## Naming

Each MCP server is a single `ToolPort` entry named `"mcp:{serverName}"`. Its commands are the server's tools by their original names (e.g. `read_file`, `list_directory`). No cross-server collision because `AgentEngine` scopes commands per `ToolPort`.

## Error Handling

- Server binary not found → log warning at startup, skip server
- `tools/list` fails → log warning, skip server
- `tools/call` error → `Observation.Error(message)`
- Unknown JSON Schema type → map to `"string"`
