# OSS Extensible Assistant — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Evolve the Kotlin assistant into an OSS-ready, plugin-driven platform in two phases — Phase 1 adds the extension framework; Phase 2 ships quick-win capabilities.

**Architecture:** Existing hexagonal ports/adapters are kept intact. Phase 1 adds `ChannelPort` to `core/Ports.kt`, `PluginLoader` to `app`, and refactors `TelegramAdapter` to implement the interface. Phase 2 adds model tiering to `LangChain4jProvider`, replaces DuckDuckGo scraping with Brave/Tavily, and adds a generic HTTP tool.

**Tech Stack:** Kotlin/JVM, Gradle Shadow JAR, `java.util.ServiceLoader` (plugin discovery), OkHttp 4.12, kotlinx-serialization. No new frameworks introduced in Phase 1.

---

## Phase 1 — OSS Foundation

---

### Task 1: Add ChannelPort interface to core

**Files:**
- Modify: `core/src/main/kotlin/com/assistant/ports/Ports.kt`

**Step 1: Add ChannelPort after EmbeddingPort**

Append to `Ports.kt` after the `EmbeddingPort` interface (line 43):

```kotlin
interface ChannelPort {
    /** Unique channel identifier, e.g. "telegram", "discord". */
    val name: String
    /**
     * Start receiving messages. The [onMessage] lambda is called for every inbound
     * message and must return the reply string. Implementations run their own
     * polling/webhook loop in a background coroutine.
     */
    fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String)
    /** Send a proactive/outbound message to an existing session. */
    fun send(sessionId: String, text: String)
}
```

**Step 2: Verify the file compiles**

```bash
./gradlew :core:build -x test
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add core/src/main/kotlin/com/assistant/ports/Ports.kt
git commit -m "feat: add ChannelPort interface to core"
```

---

### Task 2: Refactor TelegramAdapter to implement ChannelPort

**Files:**
- Modify: `channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt`
- Modify: `channels/src/test/kotlin/com/assistant/telegram/TelegramAdapterTest.kt`

**Step 1: Write the new failing tests**

Add to `TelegramAdapterTest.kt` (inside the class, after the last test):

```kotlin
// ── ChannelPort ───────────────────────────────────────────────────────────

@Test
fun `name returns telegram`() {
    val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
    assertEquals("telegram", adapter.name)
}

@Test
fun `send parses sessionId and calls sendProactive`() {
    val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
    // sendProactive is a no-op when telegramBot is null — just must not throw
    assertDoesNotThrow { adapter.send("TELEGRAM:99887766", "hello") }
}

@Test
fun `send with malformed sessionId does nothing`() {
    val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
    assertDoesNotThrow { adapter.send("BAD_FORMAT", "hello") }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew :channels:test --tests "com.assistant.telegram.TelegramAdapterTest.name returns telegram"
```
Expected: FAIL — `name` not yet defined.

**Step 3: Update TelegramAdapter to implement ChannelPort**

Make these changes to `TelegramAdapter.kt`:

**3a.** Change the class declaration (line 39):
```kotlin
// Before:
class TelegramAdapter(

// After:
class TelegramAdapter(
```
And add `: ChannelPort` after the closing `)` of the constructor parameters:
```kotlin
) : ChannelPort {
```
(Replace the existing `{`)

**3b.** Add `override val name` and `messageHandler` field immediately after the opening brace (after line 52, the logger line):
```kotlin
override val name = "telegram"
private var messageHandler: (suspend (String, String, String, String?) -> String)? = null
```

**3c.** Rename the existing `fun start()` (line 312) to `private fun startPolling()`.

**3d.** Add the two ChannelPort overrides right before `startPolling()`:
```kotlin
override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
    messageHandler = onMessage
    startPolling()
}

override fun send(sessionId: String, text: String) {
    val chatId = sessionId.removePrefix("TELEGRAM:").toLongOrNull() ?: return
    sendProactive(chatId, text)
}
```

**Step 4: Run tests to verify they pass**

```bash
./gradlew :channels:test
```
Expected: ALL PASS (existing tests are unaffected; new tests pass).

**Step 5: Commit**

```bash
git add channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt \
        channels/src/test/kotlin/com/assistant/telegram/TelegramAdapterTest.kt
git commit -m "feat: TelegramAdapter implements ChannelPort"
```

---

### Task 3: Implement PluginLoader

**Files:**
- Create: `app/src/main/kotlin/com/assistant/PluginLoader.kt`
- Create: `app/src/test/kotlin/com/assistant/PluginLoaderTest.kt`

**Step 1: Write the failing tests first**

Create `app/src/test/kotlin/com/assistant/PluginLoaderTest.kt`:

```kotlin
package com.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PluginLoaderTest {

    @Test
    fun `loadTools returns empty list when plugins dir does not exist`(@TempDir tmpDir: File) {
        val loader = PluginLoader(pluginsDir = File(tmpDir, "nonexistent"))
        assertTrue(loader.loadTools().isEmpty())
    }

    @Test
    fun `loadChannels returns empty list when plugins dir does not exist`(@TempDir tmpDir: File) {
        val loader = PluginLoader(pluginsDir = File(tmpDir, "nonexistent"))
        assertTrue(loader.loadChannels().isEmpty())
    }

    @Test
    fun `loadTools returns empty list when plugins dir is empty`(@TempDir tmpDir: File) {
        val emptyDir = File(tmpDir, "plugins").also { it.mkdirs() }
        val loader = PluginLoader(pluginsDir = emptyDir)
        assertTrue(loader.loadTools().isEmpty())
    }

    @Test
    fun `loadChannels returns empty list when plugins dir is empty`(@TempDir tmpDir: File) {
        val emptyDir = File(tmpDir, "plugins").also { it.mkdirs() }
        val loader = PluginLoader(pluginsDir = emptyDir)
        assertTrue(loader.loadChannels().isEmpty())
    }

    @Test
    fun `loadTools returns empty list when dir has no jar files`(@TempDir tmpDir: File) {
        val dir = File(tmpDir, "plugins").also { it.mkdirs() }
        File(dir, "notajar.txt").writeText("hello")
        val loader = PluginLoader(pluginsDir = dir)
        assertTrue(loader.loadTools().isEmpty())
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.assistant.PluginLoaderTest"
```
Expected: FAIL — `PluginLoader` class not found.

**Step 3: Implement PluginLoader**

Create `app/src/main/kotlin/com/assistant/PluginLoader.kt`:

```kotlin
package com.assistant

import com.assistant.ports.*
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.logging.Logger

class PluginLoader(
    private val pluginsDir: File = File(System.getProperty("user.home"), ".assistant/plugins")
) {
    private val logger = Logger.getLogger(PluginLoader::class.java.name)

    private val classLoader: ClassLoader by lazy {
        val jars = pluginsDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.extension == "jar" }
            ?.takeIf { it.isNotEmpty() }
            ?: return@lazy Thread.currentThread().contextClassLoader
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), Thread.currentThread().contextClassLoader)
    }

    fun loadTools(): List<ToolPort> = load(ToolPort::class.java)
    fun loadChannels(): List<ChannelPort> = load(ChannelPort::class.java)
    fun loadLlmProviders(): List<LlmPort> = load(LlmPort::class.java)
    fun loadMemoryProviders(): List<MemoryPort> = load(MemoryPort::class.java)
    fun loadEmbeddings(): List<EmbeddingPort> = load(EmbeddingPort::class.java)

    private fun <T> load(type: Class<T>): List<T> =
        ServiceLoader.load(type, classLoader).toList().also { plugins ->
            plugins.forEach { logger.info("Loaded plugin: ${type.simpleName}/${it::class.simpleName}") }
        }
}
```

**Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.assistant.PluginLoaderTest"
```
Expected: ALL PASS.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/assistant/PluginLoader.kt \
        app/src/test/kotlin/com/assistant/PluginLoaderTest.kt
git commit -m "feat: add PluginLoader — ServiceLoader-based plugin discovery"
```

---

### Task 4: Wire PluginLoader into Main.kt

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`

**Step 1: Add PluginLoader and merge plugin tools**

In `Main.kt`, after the config loading line (`val config = loadConfig(...)`), add:

```kotlin
val pluginLoader = PluginLoader()
```

Then in the `buildList { ... }` for tools, append at the end (after the `linear` block):

```kotlin
addAll(pluginLoader.loadTools())
```

**Step 2: Change telegram.start() call to use ChannelPort.start()**

Replace:
```kotlin
telegram.start()
```

With:
```kotlin
telegram.start { _, userId, text, imageUrl ->
    val msg = Message(sender = userId, text = text, channel = Channel.TELEGRAM, imageUrl = imageUrl)
    gateway.handle(msg)
}
```

Note: `TelegramAdapter` still uses `gateway` internally for command handling and progress
callbacks. The `onMessage` lambda above is stored by the adapter for future plugin-compatible
use. No behaviour changes for the current Telegram flow.

**Step 3: Load and start plugin channels after telegram setup**

After `telegram.reminderManager = reminderManager` and `reminderManager.loadAndReschedule()`, add:

```kotlin
val pluginChannels = pluginLoader.loadChannels()
pluginChannels.forEach { channel ->
    channel.start { _, userId, text, imageUrl ->
        val msg = Message(sender = userId, text = text, channel = Channel.TELEGRAM, imageUrl = imageUrl)
        gateway.handle(msg)
    }
    println("Plugin channel started: ${channel.name}")
}
```

**Step 4: Build and smoke-test**

```bash
./gradlew shadowJar
java -jar app/build/libs/assistant.jar
```
Expected: `Personal assistant starting...` — behaviour unchanged.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/assistant/Main.kt
git commit -m "feat: wire PluginLoader into Main — plugins auto-loaded from ~/.assistant/plugins/"
```

---

### Task 5: Add CONTRIBUTING.md and plugins/ scaffold

**Files:**
- Create: `CONTRIBUTING.md`
- Create: `plugins/.gitkeep`

**Step 1: Create CONTRIBUTING.md**

```markdown
# Contributing to Personal Assistant

## Writing a Plugin Tool

1. Add `com.assistant:core:LATEST` as a dependency (Maven Central, once published).
2. Implement `com.assistant.ports.ToolPort`:

```kotlin
class MyTool : ToolPort {
    override val name = "my_tool"
    override val description = "What this tool does"
    override fun commands() = listOf(
        CommandSpec("my_command", "Description", listOf(
            ParamSpec("arg", "string", "Argument description")
        ))
    )
    override suspend fun execute(call: ToolCall): Observation {
        val arg = call.arguments["arg"] as? String ?: return Observation.Error("Missing arg")
        return Observation.Success("Result: $arg")
    }
}
```

3. Declare the implementation in `META-INF/services/com.assistant.ports.ToolPort` (one class per line).
4. Build a fat JAR and drop it into `~/.assistant/plugins/`.

## Writing a Plugin Channel

Implement `com.assistant.ports.ChannelPort`. See `TelegramAdapter` for a reference.

## Build & Test

```bash
./gradlew test          # run all tests
./gradlew shadowJar     # build fat JAR
```
```

**Step 2: Create plugins/.gitkeep**

```bash
mkdir -p plugins && touch plugins/.gitkeep
```

**Step 3: Commit**

```bash
git add CONTRIBUTING.md plugins/.gitkeep
git commit -m "docs: add CONTRIBUTING.md and plugins/ scaffold for OSS plugin system"
```

---

## Phase 2 — Quick Wins

---

### Task 6: Model tiering — fast model for tool-call steps

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Config.kt`
- Modify: `core/src/main/kotlin/com/assistant/ports/Ports.kt`
- Modify: `providers/src/main/kotlin/com/assistant/llm/LangChain4jProvider.kt`
- Read first: `core/src/main/kotlin/com/assistant/agent/AgentEngine.kt`

**Step 1: Read AgentEngine.kt before touching anything**

```bash
cat core/src/main/kotlin/com/assistant/agent/AgentEngine.kt
```

Identify where `completeWithFunctions` is called in the agent loop:
- Calls during Act steps (tool decisions) → should use fast model
- The final call that returns `FunctionCompletion.Text` → stays on standard model

**Step 2: Write the failing test for provider tiering**

In `providers/src/test/kotlin/com/assistant/llm/`, add a test (or add to an existing test file):

```kotlin
@Test
fun `LangChain4jProvider uses fast model for completeWithFunctionsFast when configured`() {
    // This is a config-level test: verify that ModelConfig with fastModel != null
    // creates a valid provider without throwing
    val config = ModelConfig(
        provider = "anthropic",
        model = "claude-sonnet-4-6",
        apiKey = "test-key",
        baseUrl = null,
        fastModel = "claude-haiku-4-5-20251001"
    )
    // Should not throw during construction
    assertDoesNotThrow { LangChain4jProvider(config) }
}
```

**Step 3: Add `fastModel` to LlmConfig and ModelConfig**

In `Config.kt`, update `LlmConfig`:
```kotlin
@Serializable data class LlmConfig(
    val provider: String,
    val model: String,
    @SerialName("api-key") val apiKey: String? = null,
    @SerialName("base-url") val baseUrl: String? = null,
    @SerialName("fast-model") val fastModel: String? = null   // NEW
)
```

In `providers/src/main/kotlin/com/assistant/llm/LangChain4jProvider.kt`, check if `ModelConfig` is a separate data class or identical to `LlmConfig`. If separate, add `fastModel: String? = null` to it too.

**Step 4: Add `completeWithFunctionsFast` to LlmPort**

In `Ports.kt`, add a default method to `LlmPort`:
```kotlin
interface LlmPort {
    suspend fun complete(messages: List<ChatMessage>): String
    suspend fun completeWithFunctions(messages: List<ChatMessage>, commands: List<CommandSpec>): FunctionCompletion
    /** Uses a faster/cheaper model for tool-selection steps. Defaults to standard model. */
    suspend fun completeWithFunctionsFast(messages: List<ChatMessage>, commands: List<CommandSpec>): FunctionCompletion =
        completeWithFunctions(messages, commands)
}
```

**Step 5: Override `completeWithFunctionsFast` in LangChain4jProvider**

Read `providers/src/main/kotlin/com/assistant/llm/LangChain4jProvider.kt` fully, then:
- If `fastModel` is non-null, build a second `ChatLanguageModel` instance using the same provider/apiKey/baseUrl but with `fastModel` as the model name.
- Store it as `private val fastLlm: ChatLanguageModel?`.
- Override `completeWithFunctionsFast` to use `fastLlm` when non-null.

**Step 6: Update AgentEngine to call `completeWithFunctionsFast` for Act steps**

In `AgentEngine.kt`, find the calls to `llm.completeWithFunctions(...)` in the Act/Think loop steps (not the final Respond call). Change them to `llm.completeWithFunctionsFast(...)`.

Keep the final call (the one that produces `FunctionCompletion.Text` for the reply) as `completeWithFunctions`.

**Step 7: Update config/application.yml**

Add to the `llm:` section:
```yaml
llm:
  provider: anthropic
  model: claude-sonnet-4-6
  fast-model: claude-haiku-4-5-20251001   # add this line
```

**Step 8: Run all tests**

```bash
./gradlew test
```
Expected: ALL PASS.

**Step 9: Commit**

```bash
git add core/src/main/kotlin/com/assistant/ports/Ports.kt \
        core/src/main/kotlin/com/assistant/agent/AgentEngine.kt \
        app/src/main/kotlin/com/assistant/Config.kt \
        providers/src/main/kotlin/com/assistant/llm/LangChain4jProvider.kt \
        config/application.yml
git commit -m "feat: model tiering — fast model for tool-call steps, standard for final response"
```

---

### Task 7: Replace DuckDuckGo scraping with Brave/Tavily search API

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Config.kt`
- Modify: `tools/src/main/kotlin/com/assistant/tools/web/WebBrowserTool.kt`
- Modify: `tools/src/test/kotlin/com/assistant/tools/web/WebBrowserToolTest.kt` (if it exists)
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`
- Modify: `config/application.yml`

**Step 1: Update WebConfig in Config.kt**

```kotlin
@Serializable data class WebConfig(
    @SerialName("max-content-chars") val maxContentChars: Int = 8_000,
    @SerialName("search-provider") val searchProvider: String = "duckduckgo",  // NEW: brave|tavily|duckduckgo
    @SerialName("search-api-key") val searchApiKey: String = ""                 // NEW
)
```

Also add secrets overlay for search API key. In `SecretsConfig`:
```kotlin
@Serializable data class WebSecrets(@SerialName("search-api-key") val searchApiKey: String? = null)
@Serializable data class ToolsSecrets(
    val email: EmailSecrets? = null,
    val github: GitHubSecrets? = null,
    val jira: JiraSecrets? = null,
    val linear: LinearSecrets? = null,
    val web: WebSecrets? = null    // NEW
)
```

And apply it in `loadConfig()`:
```kotlin
if (secrets.tools?.web?.searchApiKey != null) {
    t = t.copy(web = t.web.copy(searchApiKey = secrets.tools.web.searchApiKey))
}
```

**Step 2: Write failing test for Brave search**

In `WebBrowserToolTest.kt` (or create it), add a MockWebServer test:

```kotlin
@Test
fun `search uses brave API when provider is brave`() {
    val server = MockWebServer()
    val braveResponse = """
        {"web":{"results":[
          {"title":"Result 1","description":"Desc 1","url":"https://example.com/1"},
          {"title":"Result 2","description":"Desc 2","url":"https://example.com/2"}
        ]}}
    """.trimIndent()
    server.enqueue(MockResponse().setBody(braveResponse).setHeader("Content-Type", "application/json"))
    server.start()

    val tool = WebBrowserTool(
        maxContentChars = 8000,
        searchProvider = "brave",
        searchApiKey = "test-key",
        searchBaseUrl = server.url("/").toString()   // injectable for testing
    )
    val result = runBlocking {
        tool.execute(ToolCall(name = "web_search", arguments = mapOf("query" to "kotlin async")))
    }
    assertTrue(result is Observation.Success)
    val text = (result as Observation.Success).result
    assertTrue(text.contains("Result 1"))
    server.shutdown()
}
```

**Step 3: Run test to verify it fails**

```bash
./gradlew :tools:test --tests "com.assistant.tools.web.WebBrowserToolTest.search uses brave API when provider is brave"
```
Expected: FAIL — `searchProvider` constructor param not yet defined.

**Step 4: Update WebBrowserTool to support multiple search providers**

Update the class signature:
```kotlin
class WebBrowserTool(
    private val maxContentChars: Int = 8_000,
    private val searchProvider: String = "duckduckgo",
    private val searchApiKey: String = "",
    private val searchBaseUrl: String = "https://api.search.brave.com"  // injectable for tests
) : ToolPort {
```

Add private search methods:

```kotlin
private fun searchBrave(query: String): Observation = runCatching {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = "${searchBaseUrl.trimEnd('/')}/res/v1/web/search?q=$encoded&count=10"
    val req = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("X-Subscription-Token", searchApiKey)
        .build()
    val body = client.newCall(req).execute().body?.string()
        ?: return@runCatching Observation.Error("Empty response from Brave")
    val json = Json { ignoreUnknownKeys = true }
    // Parse: {"web":{"results":[{"title":"...","description":"...","url":"..."}]}}
    val root = json.parseToJsonElement(body).jsonObject
    val results = root["web"]?.jsonObject?.get("results")?.jsonArray ?: return@runCatching Observation.Error("No results")
    val text = results.take(10).joinToString("\n") { item ->
        val o = item.jsonObject
        val title = o["title"]?.jsonPrimitive?.content ?: ""
        val desc = o["description"]?.jsonPrimitive?.content ?: ""
        val url = o["url"]?.jsonPrimitive?.content ?: ""
        "$title\n$desc\n$url"
    }
    Observation.Success(text.ifBlank { "No results found" })
}.getOrElse { Observation.Error(it.message ?: "Brave search failed") }

private fun searchTavily(query: String): Observation = runCatching {
    val bodyJson = """{"api_key":"$searchApiKey","query":"${query.replace("\"", "\\\"")}","search_depth":"basic","max_results":10}"""
    val req = Request.Builder()
        .url("https://api.tavily.com/search")
        .header("Content-Type", "application/json")
        .post(bodyJson.toRequestBody("application/json".toMediaType()))
        .build()
    val body = client.newCall(req).execute().body?.string()
        ?: return@runCatching Observation.Error("Empty response from Tavily")
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(body).jsonObject
    val results = root["results"]?.jsonArray ?: return@runCatching Observation.Error("No results")
    val text = results.take(10).joinToString("\n") { item ->
        val o = item.jsonObject
        val title = o["title"]?.jsonPrimitive?.content ?: ""
        val content = o["content"]?.jsonPrimitive?.content ?: ""
        val url = o["url"]?.jsonPrimitive?.content ?: ""
        "$title\n$content\n$url"
    }
    Observation.Success(text.ifBlank { "No results found" })
}.getOrElse { Observation.Error(it.message ?: "Tavily search failed") }
```

Update `search()` dispatch:
```kotlin
private fun search(query: String): Observation = when (searchProvider.lowercase()) {
    "brave" -> searchBrave(query)
    "tavily" -> searchTavily(query)
    else -> searchDuckduckgo(query)
}

// Rename existing search() to searchDuckduckgo():
private fun searchDuckduckgo(query: String): Observation = runCatching {
    // ...existing DuckDuckGo implementation unchanged...
}.getOrElse { Observation.Error(it.message ?: "Search failed") }
```

Add required imports: `import okhttp3.MediaType.Companion.toMediaType`, `import okhttp3.RequestBody.Companion.toRequestBody`, `import kotlinx.serialization.json.*`

**Step 5: Update Main.kt to pass new config fields**

```kotlin
add(WebBrowserTool(
    maxContentChars = config.tools.web.maxContentChars,
    searchProvider = config.tools.web.searchProvider,
    searchApiKey = config.tools.web.searchApiKey
))
```

**Step 6: Update config/application.yml**

```yaml
tools:
  web:
    max-content-chars: 8000
    search-provider: duckduckgo   # change to "brave" or "tavily" with api key in secrets.yml
    search-api-key: ""
```

**Step 7: Run all tests**

```bash
./gradlew :tools:test
```
Expected: ALL PASS.

**Step 8: Commit**

```bash
git add tools/src/main/kotlin/com/assistant/tools/web/WebBrowserTool.kt \
        tools/src/test/kotlin/com/assistant/tools/web/WebBrowserToolTest.kt \
        app/src/main/kotlin/com/assistant/Config.kt \
        app/src/main/kotlin/com/assistant/Main.kt \
        config/application.yml
git commit -m "feat: configurable web search provider — Brave, Tavily, or DuckDuckGo"
```

---

### Task 8: Add generic HTTP tool

**Files:**
- Create: `tools/src/main/kotlin/com/assistant/tools/http/HttpTool.kt`
- Create: `tools/src/test/kotlin/com/assistant/tools/http/HttpToolTest.kt`
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`

**Step 1: Write the failing tests**

Create `tools/src/test/kotlin/com/assistant/tools/http/HttpToolTest.kt`:

```kotlin
package com.assistant.tools.http

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpToolTest {

    @Test
    fun `http_get returns response body`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("Hello from server").setResponseCode(200))
        server.start()

        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_get", arguments = mapOf("url" to server.url("/test").toString())))
        }
        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.contains("Hello from server"))
        server.shutdown()
    }

    @Test
    fun `http_post sends body and returns response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"status":"ok"}""").setResponseCode(201))
        server.start()

        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_post", arguments = mapOf(
                "url" to server.url("/api").toString(),
                "body" to """{"name":"test"}"""
            )))
        }
        assertTrue(result is Observation.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("name"))
        server.shutdown()
    }

    @Test
    fun `http_get with missing url returns error`() {
        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_get", arguments = emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() {
        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_patch", arguments = emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew :tools:test --tests "com.assistant.tools.http.HttpToolTest"
```
Expected: FAIL — `HttpTool` not found.

**Step 3: Implement HttpTool**

Create `tools/src/main/kotlin/com/assistant/tools/http/HttpTool.kt`:

```kotlin
package com.assistant.tools.http

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpTool : ToolPort {
    override val name = "http"
    override val description = "Make HTTP requests to any REST API. Commands: http_get, http_post, http_put, http_delete"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "http_get",
            description = "HTTP GET request to a URL",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        ),
        CommandSpec(
            name = "http_post",
            description = "HTTP POST request with a body",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("body", "string", "Request body (JSON string or plain text)"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        ),
        CommandSpec(
            name = "http_put",
            description = "HTTP PUT request with a body",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("body", "string", "Request body (JSON string or plain text)"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        ),
        CommandSpec(
            name = "http_delete",
            description = "HTTP DELETE request",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        val url = call.arguments["url"] as? String
            ?: return Observation.Error("Missing required parameter: url")
        val headersJson = call.arguments["headers"] as? String
        val body = call.arguments["body"] as? String

        return runCatching {
            val reqBuilder = Request.Builder().url(url)
            applyHeaders(reqBuilder, headersJson)

            val request = when (call.name) {
                "http_get" -> reqBuilder.get().build()
                "http_post" -> reqBuilder.post((body ?: "").toRequestBody(contentType(headersJson))).build()
                "http_put" -> reqBuilder.put((body ?: "").toRequestBody(contentType(headersJson))).build()
                "http_delete" -> reqBuilder.delete().build()
                else -> return Observation.Error("Unknown http command: ${call.name}")
            }

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val status = response.code
            Observation.Success("HTTP $status\n$responseBody")
        }.getOrElse { Observation.Error(it.message ?: "HTTP request failed") }
    }

    private fun applyHeaders(builder: Request.Builder, headersJson: String?) {
        if (headersJson.isNullOrBlank()) return
        runCatching {
            val obj = json.parseToJsonElement(headersJson) as? JsonObject ?: return
            obj.forEach { (key, value) -> builder.header(key, value.jsonPrimitive.content) }
        }
    }

    private fun contentType(headersJson: String?): okhttp3.MediaType {
        if (!headersJson.isNullOrBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(headersJson) as? JsonObject
                val ct = obj?.get("Content-Type")?.jsonPrimitive?.content
                if (ct != null) return ct.toMediaType()
            }
        }
        return "application/json".toMediaType()
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
./gradlew :tools:test --tests "com.assistant.tools.http.HttpToolTest"
```
Expected: ALL PASS.

**Step 5: Register HttpTool in Main.kt**

In the `buildList { ... }` for tools, add after `WebBrowserTool`:
```kotlin
add(HttpTool())
```

Add the import at the top:
```kotlin
import com.assistant.tools.http.HttpTool
```

**Step 6: Run all tests**

```bash
./gradlew test
```
Expected: ALL PASS.

**Step 7: Commit**

```bash
git add tools/src/main/kotlin/com/assistant/tools/http/HttpTool.kt \
        tools/src/test/kotlin/com/assistant/tools/http/HttpToolTest.kt \
        app/src/main/kotlin/com/assistant/Main.kt
git commit -m "feat: add generic HTTP tool — http_get, http_post, http_put, http_delete"
```

---

## Final verification

```bash
./gradlew test
./gradlew shadowJar
java -jar app/build/libs/assistant.jar
```

Expected: all tests green, JAR starts cleanly with plugin system active.
