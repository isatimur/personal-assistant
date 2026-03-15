# MCP Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Connect the assistant to any MCP server via stdio, making its tools available to the agent loop through the existing `ToolPort` interface.

**Architecture:** `McpServerLoader` reads `~/.assistant/mcp-servers.json` (Claude Code-compatible format) and creates one `McpToolPort` per server. Each `McpToolPort` launches the server as a subprocess, negotiates the MCP protocol, and exposes its tools as `CommandSpec` entries. Tools are appended to `baseTools` in `Main.kt`.

**Tech Stack:** `io.modelcontextprotocol.sdk:mcp` (official MCP Java SDK), JUnit 5 + Mockk for tests.

---

### Task 1: Add MCP SDK + config parsing in McpServerLoader

**Files:**
- Modify: `tools/build.gradle.kts`
- Create: `tools/src/main/kotlin/com/assistant/mcp/McpServerLoader.kt`
- Create: `tools/src/test/kotlin/com/assistant/mcp/McpServerLoaderTest.kt`

**Background — JSON format to parse:**
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/user"],
      "env": { "API_KEY": "secret" }
    }
  }
}
```

**Step 1: Add the MCP SDK dependency**

In `tools/build.gradle.kts`, add inside `dependencies {}`:
```kotlin
implementation("io.modelcontextprotocol.sdk:mcp:0.10.0")
```

Then run:
```bash
./gradlew :tools:dependencies --configuration compileClasspath 2>&1 | grep modelcontext
```
Expected: `io.modelcontextprotocol.sdk:mcp:0.10.0` appears. If version 0.10.0 is not on Maven Central, check https://central.sonatype.com/search?q=io.modelcontextprotocol.sdk and use the latest available version.

**Step 2: Write the failing test**

Create `tools/src/test/kotlin/com/assistant/mcp/McpServerLoaderTest.kt`:
```kotlin
package com.assistant.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class McpServerLoaderTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `loadConfigs returns empty list when file missing`() {
        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadConfigs parses single server correctly`() {
        File(dir, "mcp-servers.json").writeText("""
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home"],
                  "env": { "FOO": "bar" }
                }
              }
            }
        """.trimIndent())

        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))

        assertEquals(1, result.size)
        val cfg = result[0]
        assertEquals("filesystem", cfg.name)
        assertEquals("npx", cfg.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/home"), cfg.args)
        assertEquals(mapOf("FOO" to "bar"), cfg.env)
    }

    @Test
    fun `loadConfigs parses multiple servers`() {
        File(dir, "mcp-servers.json").writeText("""
            {
              "mcpServers": {
                "filesystem": { "command": "npx", "args": [], "env": {} },
                "notion":     { "command": "npx", "args": [], "env": {} }
              }
            }
        """.trimIndent())

        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertEquals(2, result.size)
        assertEquals(setOf("filesystem", "notion"), result.map { it.name }.toSet())
    }

    @Test
    fun `loadConfigs returns empty list on malformed JSON`() {
        File(dir, "mcp-servers.json").writeText("not json {{{")
        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadConfigs handles missing args and env`() {
        File(dir, "mcp-servers.json").writeText("""
            { "mcpServers": { "minimal": { "command": "echo" } } }
        """.trimIndent())

        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertEquals(1, result.size)
        assertEquals(emptyList<String>(), result[0].args)
        assertEquals(emptyMap<String, String>(), result[0].env)
    }
}
```

**Step 3: Run test — verify it fails**
```bash
./gradlew :tools:test --tests "com.assistant.mcp.McpServerLoaderTest" 2>&1 | tail -20
```
Expected: compilation error — `McpServerLoader` not found.

**Step 4: Implement McpServerLoader**

Create `tools/src/main/kotlin/com/assistant/mcp/McpServerLoader.kt`:
```kotlin
package com.assistant.mcp

import org.slf4j.LoggerFactory
import java.io.File
import java.util.logging.Logger

data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

object McpServerLoader {
    private val logger = Logger.getLogger(McpServerLoader::class.java.name)

    /** Parses [configFile] in Claude Code mcpServers format. Returns empty list on any error. */
    fun loadConfigs(configFile: File): List<McpServerConfig> {
        if (!configFile.exists()) return emptyList()
        return try {
            val root = org.json.JSONObject(configFile.readText())
            val servers = root.optJSONObject("mcpServers") ?: return emptyList()
            servers.keys().asSequence().map { name ->
                val obj = servers.getJSONObject(name)
                val args = obj.optJSONArray("args")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                val env = obj.optJSONObject("env")?.let { e ->
                    e.keys().asSequence().associateWith { k -> e.getString(k) }
                } ?: emptyMap()
                McpServerConfig(
                    name = name,
                    command = obj.getString("command"),
                    args = args,
                    env = env
                )
            }.toList()
        } catch (e: Exception) {
            logger.warning("Failed to parse mcp-servers.json: ${e.message}")
            emptyList()
        }
    }
}
```

Note: this uses `org.json:json` for JSON parsing since the project already has OkHttp (which bundles it transitively). If `org.json.JSONObject` is not available at compile time, add `implementation("org.json:json:20240303")` to `tools/build.gradle.kts`.

**Step 5: Run test — verify it passes**
```bash
./gradlew :tools:test --tests "com.assistant.mcp.McpServerLoaderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 5 tests pass.

**Step 6: Commit**
```bash
git add tools/build.gradle.kts \
        tools/src/main/kotlin/com/assistant/mcp/McpServerLoader.kt \
        tools/src/test/kotlin/com/assistant/mcp/McpServerLoaderTest.kt
git commit -m "feat: add McpServerLoader — parses ~/.assistant/mcp-servers.json"
```

---

### Task 2: McpToolPort — wraps one MCP server as ToolPort

**Files:**
- Create: `tools/src/main/kotlin/com/assistant/mcp/McpToolPort.kt`
- Create: `tools/src/test/kotlin/com/assistant/mcp/McpToolPortTest.kt`

**Background — MCP SDK API (verify against SDK source if anything doesn't compile):**

The SDK lives in package `io.modelcontextprotocol.client` (client classes) and `io.modelcontextprotocol.spec` (schema classes).

Key classes:
- `McpClient.sync(transport).requestTimeout(Duration).build()` → `McpSyncClient`
- `StdioClientTransport(ServerParameters)` — transport over subprocess stdin/stdout
- `ServerParameters.builder(command).args(listOf(...)).env(mapOf(...)).build()`
- `client.initialize()` — handshake
- `client.listTools()` → `McpSchema.ListToolsResult` with `.tools: List<McpSchema.Tool>`
  - `tool.name: String`, `tool.description: String?`
  - `tool.inputSchema` — a map/object with `properties` (map of param-name → `{type, description}`) and `required` (list of required param names)
- `client.callTool(McpSchema.CallToolRequest(name, arguments))` → `McpSchema.CallToolResult`
  - `result.isError: Boolean?` — true if the tool reported an error
  - `result.content: List<McpSchema.Content>` — text pieces; cast to `McpSchema.TextContent` to get `.text`
- `client.close()`

**Step 1: Write the failing tests**

Create `tools/src/test/kotlin/com/assistant/mcp/McpToolPortTest.kt`:
```kotlin
package com.assistant.mcp

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpToolPortTest {

    private fun makeTool(name: String, description: String, properties: Map<String, Any>, required: List<String>) =
        McpSchema.Tool(name, description, McpSchema.JsonSchema("object", properties, required, null))

    private fun makeTextContent(text: String): McpSchema.TextContent =
        McpSchema.TextContent(null, null, text)

    @Test
    fun `name is prefixed with mcp colon`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } returns McpSchema.ListToolsResult(emptyList(), null)
        val port = McpToolPort("filesystem", client)
        assertEquals("mcp:filesystem", port.name)
    }

    @Test
    fun `commands returns specs from listTools`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        val props = mapOf(
            "path" to mapOf("type" to "string", "description" to "File path"),
            "limit" to mapOf("type" to "integer", "description" to "Max lines")
        )
        every { client.listTools() } returns McpSchema.ListToolsResult(
            listOf(makeTool("read_file", "Read a file", props, listOf("path"))),
            null
        )
        val port = McpToolPort("filesystem", client)
        val cmds = port.commands()

        assertEquals(1, cmds.size)
        val cmd = cmds[0]
        assertEquals("read_file", cmd.name)
        assertEquals("Read a file", cmd.description)
        assertEquals(2, cmd.params.size)

        val pathParam = cmd.params.first { it.name == "path" }
        assertEquals("string", pathParam.type)
        assertEquals("File path", pathParam.description)
        assertTrue(pathParam.required)

        val limitParam = cmd.params.first { it.name == "limit" }
        assertEquals("integer", limitParam.type)
        assertFalse(limitParam.required)
    }

    @Test
    fun `execute calls tool and returns success`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } returns McpSchema.ListToolsResult(emptyList(), null)
        every { client.callTool(any()) } returns McpSchema.CallToolResult(
            listOf(makeTextContent("file contents here")), false
        )
        val port = McpToolPort("filesystem", client)

        val result = port.execute(ToolCall("read_file", mapOf("path" to "/etc/hosts")))

        assertTrue(result is Observation.Success)
        assertEquals("file contents here", (result as Observation.Success).result)
        verify { client.callTool(match { it.name == "read_file" && it.arguments?.get("path") == "/etc/hosts" }) }
    }

    @Test
    fun `execute returns error when tool reports isError true`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } returns McpSchema.ListToolsResult(emptyList(), null)
        every { client.callTool(any()) } returns McpSchema.CallToolResult(
            listOf(makeTextContent("permission denied")), true
        )
        val port = McpToolPort("filesystem", client)
        val result = port.execute(ToolCall("read_file", mapOf("path" to "/root/secret")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("permission denied"))
    }

    @Test
    fun `execute returns error when callTool throws`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } returns McpSchema.ListToolsResult(emptyList(), null)
        every { client.callTool(any()) } throws RuntimeException("connection lost")
        val port = McpToolPort("filesystem", client)
        val result = port.execute(ToolCall("read_file", mapOf("path" to "/etc/hosts")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `close calls client close`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } returns McpSchema.ListToolsResult(emptyList(), null)
        val port = McpToolPort("filesystem", client)
        port.close()
        verify { client.close() }
    }
}
```

**Step 2: Run tests — verify they fail**
```bash
./gradlew :tools:test --tests "com.assistant.mcp.McpToolPortTest" 2>&1 | tail -20
```
Expected: compilation error — `McpToolPort` not found.

**Step 3: Implement McpToolPort**

Create `tools/src/main/kotlin/com/assistant/mcp/McpToolPort.kt`:
```kotlin
package com.assistant.mcp

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema
import java.io.Closeable
import java.time.Duration
import java.util.logging.Logger

class McpToolPort(
    private val serverName: String,
    private val client: McpSyncClient
) : ToolPort, Closeable {

    private val logger = Logger.getLogger(McpToolPort::class.java.name)
    override val name = "mcp:$serverName"
    override val description = "MCP server: $serverName"

    private val cachedCommands: List<CommandSpec> by lazy {
        try {
            client.listTools().tools.map { tool -> tool.toCommandSpec() }
        } catch (e: Exception) {
            logger.warning("[$serverName] listTools failed: ${e.message}")
            emptyList()
        }
    }

    override fun commands(): List<CommandSpec> = cachedCommands

    override suspend fun execute(call: ToolCall): Observation {
        return try {
            val result = client.callTool(McpSchema.CallToolRequest(call.name, call.arguments))
            val text = result.content
                .filterIsInstance<McpSchema.TextContent>()
                .joinToString("\n") { it.text ?: "" }
                .ifBlank { "(no output)" }
            if (result.isError == true) Observation.Error(text) else Observation.Success(text)
        } catch (e: Exception) {
            logger.warning("[$serverName] callTool '${call.name}' failed: ${e.message}")
            Observation.Error("MCP tool error: ${e.message}")
        }
    }

    override fun close() {
        try { client.close() } catch (_: Exception) {}
    }

    companion object {
        /** Creates McpToolPort by launching the server subprocess and performing MCP handshake. */
        fun create(config: McpServerConfig): McpToolPort? {
            return try {
                val params = ServerParameters.builder(config.command)
                    .args(config.args)
                    .env(config.env)
                    .build()
                val transport = StdioClientTransport(params)
                val client = io.modelcontextprotocol.client.McpClient
                    .sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build()
                client.initialize()
                McpToolPort(config.name, client)
            } catch (e: Exception) {
                Logger.getLogger(McpToolPort::class.java.name)
                    .warning("Failed to start MCP server '${config.name}': ${e.message}")
                null
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun McpSchema.Tool.toCommandSpec(): CommandSpec {
    val schema = this.inputSchema
    val properties = (schema?.properties as? Map<String, Any>) ?: emptyMap()
    val required = (schema?.required as? List<String>) ?: emptyList()

    val params = properties.map { (paramName, paramDef) ->
        val def = paramDef as? Map<String, Any> ?: emptyMap()
        val type = when (def["type"]?.toString()) {
            "integer", "number" -> "integer"
            "boolean" -> "boolean"
            else -> "string"
        }
        val desc = def["description"]?.toString() ?: paramName
        ParamSpec(name = paramName, type = type, description = desc, required = paramName in required)
    }
    return CommandSpec(name = this.name, description = this.description ?: this.name, params = params)
}
```

> **Note on API compatibility:** If `McpSchema.Tool`, `McpSchema.TextContent`, or `McpSchema.CallToolRequest` have different field names in the actual SDK version you installed, check the SDK's source at `~/.gradle/caches/` or https://github.com/modelcontextprotocol/java-sdk and adjust field access accordingly. The logic stays the same; only the exact field names may differ.

**Step 4: Run tests — verify they pass**
```bash
./gradlew :tools:test --tests "com.assistant.mcp.McpToolPortTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all 6 tests pass.

If tests fail due to wrong `McpSchema` constructor signatures (the mock `makeTextContent` / `makeTool` helpers use specific constructors), adjust the helper methods to match the actual constructor from the SDK. Use:
```bash
./gradlew :tools:dependencies --configuration compileClasspath
find ~/.gradle/caches -name "mcp-*.jar" | head -1 | xargs jar tf | grep "McpSchema"
```
to locate the JAR and inspect actual class structure.

**Step 5: Run all tools tests**
```bash
./gradlew :tools:test 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

**Step 6: Commit**
```bash
git add tools/src/main/kotlin/com/assistant/mcp/McpToolPort.kt \
        tools/src/test/kotlin/com/assistant/mcp/McpToolPortTest.kt
git commit -m "feat: add McpToolPort — wraps MCP server as ToolPort"
```

---

### Task 3: Wire McpServerLoader.load() into Main.kt

**Files:**
- Modify: `tools/src/main/kotlin/com/assistant/mcp/McpServerLoader.kt` — add `load()` method
- Modify: `app/src/main/kotlin/com/assistant/Main.kt` — append MCP tools to baseTools

**Step 1: Add `load()` to McpServerLoader**

Open `tools/src/main/kotlin/com/assistant/mcp/McpServerLoader.kt`. Add this method to the `object McpServerLoader` body, after `loadConfigs()`:

```kotlin
/** Reads ~/.assistant/mcp-servers.json and starts all configured MCP servers. */
fun load(globalDir: File): List<McpToolPort> {
    val configFile = File(globalDir, "mcp-servers.json")
    return loadConfigs(configFile).mapNotNull { config ->
        McpToolPort.create(config).also {
            if (it != null) logger.info("MCP server '${config.name}' started (${it.commands().size} tools)")
        }
    }
}
```

**Step 2: Write test for load() with missing file**

Add to `McpServerLoaderTest.kt`:
```kotlin
@Test
fun `load returns empty list when file missing`() {
    val result = McpServerLoader.load(dir)
    assertTrue(result.isEmpty())
}
```

Run:
```bash
./gradlew :tools:test --tests "com.assistant.mcp.McpServerLoaderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

**Step 3: Wire into Main.kt**

Open `app/src/main/kotlin/com/assistant/Main.kt`.

Add import at the top (with other tool imports):
```kotlin
import com.assistant.mcp.McpServerLoader
```

Find the `baseTools` `buildList {}` block. It ends with `addAll(pluginLoader.loadTools())`. Add right after that line, still inside `buildList {}`:
```kotlin
addAll(McpServerLoader.load(globalDir))
```

The block will look like:
```kotlin
val baseTools: List<ToolPort> = buildList {
    // ... existing tools ...
    addAll(pluginLoader.loadTools())
    addAll(McpServerLoader.load(globalDir))  // ← add this
}
```

**Step 4: Add MCP ports to shutdown hook**

Find the shutdown hook in `Main.kt` (it calls `.close()` on adapters). Add MCP cleanup. Search for `Runtime.getRuntime().addShutdownHook` or similar. Add:
```kotlin
// inside the shutdown hook lambda, with other .close() calls:
baseTools.filterIsInstance<Closeable>().forEach { runCatching { it.close() } }
```

If there's no explicit shutdown hook for tools already, add this block right before the `server?.stop()` or at the end of the shutdown section:
```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    baseTools.filterIsInstance<java.io.Closeable>().forEach { runCatching { it.close() } }
})
```

**Step 5: Build**
```bash
./gradlew build -x test 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

**Step 6: Run all tests**
```bash
./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`.

**Step 7: Commit**
```bash
git add tools/src/main/kotlin/com/assistant/mcp/McpServerLoader.kt \
        tools/src/test/kotlin/com/assistant/mcp/McpServerLoaderTest.kt \
        app/src/main/kotlin/com/assistant/Main.kt
git commit -m "feat: wire MCP servers into baseTools from ~/.assistant/mcp-servers.json"
```

---

## Manual Verification

After building and starting:
```bash
./gradlew shadowJar -x test
```

Create `~/.assistant/mcp-servers.json`:
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/timur"],
      "env": {}
    }
  }
}
```

Restart the assistant. Send a Telegram message: _"list the files in my home directory"_. The agent should use `read_file` or `list_directory` from the `mcp:filesystem` server rather than the built-in FileSystemTool.
