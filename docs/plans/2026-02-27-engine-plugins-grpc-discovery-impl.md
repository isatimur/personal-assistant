# Engine Plugins, gRPC Agent Bus & Discovery — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a structured `EnginePlugin` hook system to `AgentEngine`, ephemeral inter-agent sessions on `AskAgentTool`, and a `GrpcAgentBus` with `AgentRegistry` discovery for cross-process agent communication.

**Architecture:** `EnginePlugin` is an interface with default no-op methods injected into `AgentEngine` as a list; hooks fire at every ReAct loop stage with exceptions swallowed per-plugin. `AgentBus` gains an `ephemeral` flag routed through to the session key. `GrpcAgentBus` wraps generated gRPC stubs, resolving addresses via an `AgentRegistry` interface backed by either static config or file-system self-registration.

**Tech Stack:** Kotlin coroutines, JUL logging, gRPC-Kotlin 1.4.1, Protobuf 3.25.3, gRPC-Netty-shaded 1.63.0, `com.google.protobuf` Gradle plugin 0.9.4.

**Design doc:** `docs/plans/2026-02-27-engine-plugins-grpc-discovery-design.md`

---

## PART A — EnginePlugin System

### Task 1: Add `EnginePlugin` interface to `Ports.kt`

**Files:**
- Modify: `core/src/main/kotlin/com/assistant/ports/Ports.kt`

**Step 1: Append the interface at the end of the file**

```kotlin
interface EnginePlugin {
    val name: String get() = this::class.simpleName ?: "plugin"
    suspend fun beforeTool(session: Session, call: ToolCall) {}
    suspend fun afterTool(session: Session, call: ToolCall, result: Observation, durationMs: Long) {}
    suspend fun beforeLlm(session: Session, stepIndex: Int) {}
    suspend fun afterLlm(session: Session, stepIndex: Int, usage: TokenUsage?, durationMs: Long) {}
    suspend fun onResponse(session: Session, text: String, steps: Int) {}
    suspend fun onError(session: Session, error: Exception) {}
}
```

**Step 2: Verify it compiles**

```bash
./gradlew :core:compileKotlin
```
Expected: `BUILD SUCCESSFUL`

---

### Task 2: Write failing plugin tests in `AgentEngineTest`

**Files:**
- Modify: `core/src/test/kotlin/com/assistant/agent/AgentEngineTest.kt`

**Step 1: Add these three tests to the existing `AgentEngineTest` class**

```kotlin
@Test
fun `beforeTool and afterTool fire with correct args`() = runTest {
    setupCommon()
    val plugin = mockk<EnginePlugin>(relaxed = true)
    coEvery { plugin.name } returns "test"
    coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "do it"))
    coEvery { llm.completeWithFunctionsFast(any(), any()) } returnsMany listOf(
        FunctionCompletion.FunctionCall("shell_run", "{\"command\": \"echo hi\"}"),
        FunctionCompletion.Text("placeholder")
    )
    coEvery { llm.completeWithFunctions(any(), any()) } returns FunctionCompletion.Text("done")
    coEvery { toolRegistry.execute(any()) } returns Observation.Success("hi")

    val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
    engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "do it", Channel.TELEGRAM))

    coVerify { plugin.beforeTool(any(), ToolCall("shell_run", mapOf("command" to "echo hi"))) }
    coVerify { plugin.afterTool(any(), ToolCall("shell_run", mapOf("command" to "echo hi")), Observation.Success("hi"), any()) }
}

@Test
fun `onResponse fires with final text and step count`() = runTest {
    setupCommon()
    val plugin = mockk<EnginePlugin>(relaxed = true)
    coEvery { plugin.name } returns "test"
    coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")

    val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
    engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

    coVerify { plugin.onResponse(any(), "Hello!", any()) }
}

@Test
fun `plugin exception does not fail the request`() = runTest {
    setupCommon()
    val plugin = mockk<EnginePlugin>(relaxed = true)
    coEvery { plugin.name } returns "bad-plugin"
    coEvery { plugin.onResponse(any(), any(), any()) } throws RuntimeException("plugin exploded")
    coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")

    val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
    val result = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

    assertEquals("Hello!", result)
}
```

**Step 2: Run to verify they fail (compile ok, but `plugins` param doesn't exist yet)**

```bash
./gradlew :core:compileTestKotlin 2>&1 | grep -i error
```
Expected: `error: unresolved reference: plugins`

---

### Task 3: Add `plugins` param and fire hooks in `AgentEngine`

**Files:**
- Modify: `core/src/main/kotlin/com/assistant/agent/AgentEngine.kt`

**Step 1: Replace the entire file**

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import kotlinx.serialization.json.*
import java.util.logging.Logger

class AgentEngine(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val assembler: ContextAssembler,
    private val maxSteps: Int = 10,
    private val compactionService: CompactionService? = null,
    private val tokenTracker: TokenTracker? = null,
    private val plugins: List<EnginePlugin> = emptyList()
) {
    private val logger = Logger.getLogger(AgentEngine::class.java.name)

    suspend fun process(
        session: Session,
        message: Message,
        onProgress: ((String) -> Unit)? = null
    ): String {
        memory.append(session.id, message)
        try {
            compactionService?.maybeCompact(session.id, session.userId)
        } catch (e: Exception) {
            logger.warning("Compaction failed for session ${session.id}: ${e.message}")
        }
        val context = assembler.build(session, message).toMutableList()
        val commands = toolRegistry.allCommands()

        var toolCallsMade = false
        repeat(maxSteps) { stepIndex ->
            plugins.fireBeforeLlm(session, stepIndex, logger)
            val llmStart = System.currentTimeMillis()
            val completion = llm.completeWithFunctionsFast(context, commands)
            plugins.fireAfterLlm(session, stepIndex, completion.usage(), System.currentTimeMillis() - llmStart, logger)

            when (completion) {
                is FunctionCompletion.FunctionCall -> {
                    toolCallsMade = true
                    tokenTracker?.record(session.id, completion.usage)
                    onProgress?.invoke("Using ${completion.name}...")
                    val args = parseArgsJson(completion.argsJson)
                    val call = ToolCall(completion.name, args)
                    plugins.fireBeforeTool(session, call, logger)
                    val toolStart = System.currentTimeMillis()
                    val observation = toolRegistry.execute(call)
                    plugins.fireAfterTool(session, call, observation, System.currentTimeMillis() - toolStart, logger)
                    val obs = when (observation) {
                        is Observation.Success -> "Result: ${observation.result}"
                        is Observation.Error   -> "Error: ${observation.message}"
                    }
                    context.add(ChatMessage("assistant", "Used ${completion.name}"))
                    context.add(ChatMessage("user", obs))
                }
                is FunctionCompletion.Text -> {
                    val finalCompletion = if (toolCallsMade) {
                        plugins.fireBeforeLlm(session, stepIndex, logger)
                        val start = System.currentTimeMillis()
                        val result = llm.completeWithFunctions(context, commands)
                        plugins.fireAfterLlm(session, stepIndex, result.usage(), System.currentTimeMillis() - start, logger)
                        result
                    } else {
                        completion
                    }
                    val finalText = if (finalCompletion is FunctionCompletion.Text) finalCompletion.content else completion.content
                    tokenTracker?.record(session.id, finalCompletion.let { if (it is FunctionCompletion.Text) it.usage else null })
                    memory.append(session.id, Message("assistant", finalText, session.channel))
                    plugins.fireOnResponse(session, finalText, stepIndex + 1, logger)
                    return finalText
                }
            }
        }

        return "I reached the maximum reasoning steps. Please try a simpler request."
    }

    private fun parseArgsJson(json: String): Map<String, Any> = runCatching {
        Json.parseToJsonElement(json).jsonObject.entries.associate { (k, v) ->
            k to (if (v is JsonPrimitive) v.content else v.toString())
        }
    }.getOrElse { emptyMap() }

    private fun FunctionCompletion.usage(): TokenUsage? = when (this) {
        is FunctionCompletion.Text         -> usage
        is FunctionCompletion.FunctionCall -> usage
    }
}

// --- plugin fire helpers (package-private, swallow all exceptions) ---

private suspend fun List<EnginePlugin>.fireBeforeTool(session: Session, call: ToolCall, log: Logger) {
    for (p in this) runCatching { p.beforeTool(session, call) }
        .onFailure { log.warning("Plugin '${p.name}' beforeTool: ${it.message}") }
}

private suspend fun List<EnginePlugin>.fireAfterTool(session: Session, call: ToolCall, obs: Observation, ms: Long, log: Logger) {
    for (p in this) runCatching { p.afterTool(session, call, obs, ms) }
        .onFailure { log.warning("Plugin '${p.name}' afterTool: ${it.message}") }
}

private suspend fun List<EnginePlugin>.fireBeforeLlm(session: Session, step: Int, log: Logger) {
    for (p in this) runCatching { p.beforeLlm(session, step) }
        .onFailure { log.warning("Plugin '${p.name}' beforeLlm: ${it.message}") }
}

private suspend fun List<EnginePlugin>.fireAfterLlm(session: Session, step: Int, usage: TokenUsage?, ms: Long, log: Logger) {
    for (p in this) runCatching { p.afterLlm(session, step, usage, ms) }
        .onFailure { log.warning("Plugin '${p.name}' afterLlm: ${it.message}") }
}

private suspend fun List<EnginePlugin>.fireOnResponse(session: Session, text: String, steps: Int, log: Logger) {
    for (p in this) runCatching { p.onResponse(session, text, steps) }
        .onFailure { log.warning("Plugin '${p.name}' onResponse: ${it.message}") }
}
```

Note: the `for (p in this) runCatching { ... }.onFailure { ... }` pattern has a scoping issue — `p` is not in scope inside `.onFailure`. Fix: capture name before the call:

```kotlin
private suspend fun List<EnginePlugin>.fireBeforeTool(session: Session, call: ToolCall, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.beforeTool(session, call) }
            .onFailure { log.warning("Plugin '$pluginName' beforeTool: ${it.message}") }
    }
}
// apply same pattern to all fire* functions
```

**Step 2: Run the new tests**

```bash
./gradlew :core:test --tests "com.assistant.agent.AgentEngineTest" --rerun-tasks 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, all tests pass including the 3 new ones.

**Step 3: Commit**

```bash
git add core/src/main/kotlin/com/assistant/ports/Ports.kt \
        core/src/main/kotlin/com/assistant/agent/AgentEngine.kt \
        core/src/test/kotlin/com/assistant/agent/AgentEngineTest.kt
git commit -m "feat: add EnginePlugin hook system to AgentEngine"
```

---

### Task 4: Create `LoggingPlugin`

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/LoggingPlugin.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/LoggingPluginTest.kt`

**Step 1: Write the test first**

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.TokenUsage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.logging.*

class LoggingPluginTest {
    private val session = Session("s1", "user1", Channel.TELEGRAM)
    private val call = ToolCall("shell_run", mapOf("command" to "echo hi"))

    @Test
    fun `beforeTool does not throw`() = runTest {
        LoggingPlugin().beforeTool(session, call)
    }

    @Test
    fun `afterTool does not throw on success`() = runTest {
        LoggingPlugin().afterTool(session, call, Observation.Success("hi"), 42L)
    }

    @Test
    fun `afterTool does not throw on error`() = runTest {
        LoggingPlugin().afterTool(session, call, Observation.Error("boom"), 10L)
    }

    @Test
    fun `afterLlm does not throw with null usage`() = runTest {
        LoggingPlugin().afterLlm(session, 0, null, 100L)
    }

    @Test
    fun `afterLlm does not throw with real usage`() = runTest {
        LoggingPlugin().afterLlm(session, 1, TokenUsage(100, 50), 200L)
    }

    @Test
    fun `onResponse does not throw`() = runTest {
        LoggingPlugin().onResponse(session, "Hello!", 2)
    }

    @Test
    fun `name returns class simple name`() {
        assertEquals("LoggingPlugin", LoggingPlugin().name)
    }
}
```

**Step 2: Run to see it fail (class not found)**

```bash
./gradlew :core:compileTestKotlin 2>&1 | grep error
```

**Step 3: Implement `LoggingPlugin`**

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import java.util.logging.Logger

class LoggingPlugin(
    private val logger: Logger = Logger.getLogger("AgentEngine")
) : EnginePlugin {

    override suspend fun beforeTool(session: Session, call: ToolCall) {
        logger.info("[${session.id}] → tool: ${call.name}")
    }

    override suspend fun afterTool(session: Session, call: ToolCall, result: Observation, durationMs: Long) {
        val status = if (result is Observation.Success) "ok" else "error"
        logger.info("[${session.id}] ← tool: ${call.name} ($status, ${durationMs}ms)")
    }

    override suspend fun afterLlm(session: Session, stepIndex: Int, usage: TokenUsage?, durationMs: Long) {
        logger.fine("[${session.id}] llm step=$stepIndex in=${usage?.inputTokens} out=${usage?.outputTokens} (${durationMs}ms)")
    }

    override suspend fun onResponse(session: Session, text: String, steps: Int) {
        logger.info("[${session.id}] responded after $steps step(s)")
    }
}
```

**Step 4: Run tests**

```bash
./gradlew :core:test --tests "com.assistant.agent.LoggingPluginTest" --rerun-tasks 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/LoggingPlugin.kt \
        core/src/test/kotlin/com/assistant/agent/LoggingPluginTest.kt
git commit -m "feat: add LoggingPlugin for AgentEngine observability"
```

---

### Task 5: Wire `plugins` into `AgentStack` and `buildAgentEngine`

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`

**Step 1: Add `plugins` field to `AgentStack` data class (line ~41)**

```kotlin
data class AgentStack(
    val engine: AgentEngine,
    val memory: SqliteMemoryStore,
    val tokenTracker: TokenTracker,
    val tools: List<ToolPort>,
    val workspace: WorkspaceLoader,
    val compaction: CompactionService,
    val plugins: List<EnginePlugin>                    // ADD THIS
)
```

**Step 2: Add `plugins` param to `buildAgentEngine` and pass through**

Change the function signature from:
```kotlin
fun buildAgentEngine(agentName: String, config: AppConfig, llm: LangChain4jProvider,
    baseTools: List<ToolPort>, embeddingPort: LangChain4jEmbeddingProvider?, globalDir: File): AgentStack {
```
To:
```kotlin
fun buildAgentEngine(agentName: String, config: AppConfig, llm: LangChain4jProvider,
    baseTools: List<ToolPort>, embeddingPort: LangChain4jEmbeddingProvider?, globalDir: File,
    plugins: List<EnginePlugin> = emptyList()): AgentStack {
```

In the body, change:
```kotlin
val engine = AgentEngine(llm, memory, registry, assembler, compactionService = compaction, tokenTracker = tracker)
return AgentStack(engine, memory, tracker, tools, workspace, compaction)
```
To:
```kotlin
val engine = AgentEngine(llm, memory, registry, assembler, compactionService = compaction, tokenTracker = tracker, plugins = plugins)
return AgentStack(engine, memory, tracker, tools, workspace, compaction, plugins)
```

**Step 3: Add default plugins in `main()` and pass to call sites**

In `main()`, just before the `if (config.routing == null)` block, add:
```kotlin
val defaultPlugins: List<EnginePlugin> = listOf(LoggingPlugin())
```

In the legacy path engine construction (around line 117), add `plugins = defaultPlugins`:
```kotlin
val engine = AgentEngine(llm, memory, registry, assembler, compactionService = compaction, tokenTracker = tokenTracker, plugins = defaultPlugins)
```

In the multi-agent path, change:
```kotlin
val baseStacks = allAgentNames.associateWith { buildAgentEngine(it, config, llm, baseTools, embeddingPort, globalDir) }
```
To:
```kotlin
val baseStacks = allAgentNames.associateWith { buildAgentEngine(it, config, llm, baseTools, embeddingPort, globalDir, defaultPlugins) }
```

Also update the engine rebuild in the bus wiring block (the `.copy(engine = newEngine)` section) to pass `plugins`:
```kotlin
val newEngine = AgentEngine(llm, stack.memory, newRegistry, newAssembler,
    compactionService = stack.compaction, tokenTracker = stack.tokenTracker,
    plugins = stack.plugins)
```

Also add the import at the top of Main.kt:
```kotlin
import com.assistant.agent.EnginePlugin
import com.assistant.agent.LoggingPlugin
```

**Step 4: Compile and run all tests**

```bash
./gradlew :core:test :app:compileKotlin --rerun-tasks 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/assistant/Main.kt
git commit -m "feat: wire LoggingPlugin into all AgentEngine instances"
```

---

## PART B — Ephemeral Mode on `AskAgentTool`

### Task 6: Update `AgentBus` interface and `InProcessAgentBus`

**Files:**
- Modify: `core/src/main/kotlin/com/assistant/agent/AgentBus.kt`

**Step 1: Replace the entire file**

```kotlin
package com.assistant.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

interface AgentBus {
    fun registerAgent(name: String, handler: suspend (from: String, message: String, ephemeral: Boolean) -> String)
    suspend fun request(from: String, to: String, message: String, timeoutMs: Long = 30_000, ephemeral: Boolean = false): String
}

class InProcessAgentBus(private val scope: CoroutineScope) : AgentBus {

    private data class AgentRequest(
        val from: String,
        val message: String,
        val ephemeral: Boolean,
        val reply: CompletableDeferred<String>
    )

    private val queues = ConcurrentHashMap<String, Channel<AgentRequest>>()

    override fun registerAgent(name: String, handler: suspend (from: String, message: String, ephemeral: Boolean) -> String) {
        val queue = Channel<AgentRequest>(capacity = 64)
        queues[name] = queue
        scope.launch {
            for (req in queue) {
                val result = runCatching { handler(req.from, req.message, req.ephemeral) }
                if (result.isSuccess) req.reply.complete(result.getOrThrow())
                else req.reply.completeExceptionally(result.exceptionOrNull()!!)
            }
        }
    }

    override suspend fun request(from: String, to: String, message: String, timeoutMs: Long, ephemeral: Boolean): String {
        val queue = queues[to] ?: return "Error: no agent named '$to'"
        val reply = CompletableDeferred<String>()
        queue.send(AgentRequest(from, message, ephemeral, reply))
        return withTimeoutOrNull(timeoutMs) { reply.await() }
            ?: "Error: agent '$to' timed out after ${timeoutMs}ms"
    }
}
```

**Step 2: Fix `AgentBusTest` — update all `registerAgent` lambdas to 3 params**

In `core/src/test/kotlin/com/assistant/agent/AgentBusTest.kt`, every `registerAgent` lambda changes from `{ from, message -> ... }` to `{ from, message, _ -> ... }` (or `{ from, message, ephemeral -> ... }` where ephemeral is used).

Full updated file:

```kotlin
package com.assistant.agent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentBusTest {

    @Test
    fun `request routes to registered agent`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("worker") { _, message, _ -> "response to: $message" }

        val result = bus.request(from = "caller", to = "worker", message = "hello")
        assertEquals("response to: hello", result)
    }

    @Test
    fun `unknown agent returns error string`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)

        val result = bus.request(from = "caller", to = "no-such-agent", message = "hello")
        assertTrue(result.startsWith("Error:"), "Expected error but got: $result")
        assertTrue(result.contains("no-such-agent"))
    }

    @Test
    fun `timeout returns error string`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("slow") { _, _, _ ->
            kotlinx.coroutines.delay(60_000)
            "too late"
        }

        val result = bus.request(from = "caller", to = "slow", message = "hurry", timeoutMs = 100)
        assertTrue(result.startsWith("Error:"), "Expected timeout error but got: $result")
        assertTrue(result.contains("timed out"))
    }

    @Test
    fun `passes caller name to handler`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("worker") { from, _, _ -> "echo:$from" }

        val result = bus.request(from = "personal", to = "worker", message = "ping")
        assertEquals("echo:personal", result)
    }

    @Test
    fun `multiple agents can be registered independently`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("agent-a") { _, _, _ -> "from-a" }
        bus.registerAgent("agent-b") { _, _, _ -> "from-b" }

        assertEquals("from-a", bus.request(from = "caller", to = "agent-a", message = "ping"))
        assertEquals("from-b", bus.request(from = "caller", to = "agent-b", message = "ping"))
    }

    @Test
    fun `ephemeral flag is passed to handler`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("worker") { _, _, ephemeral -> "ephemeral=$ephemeral" }

        assertEquals("ephemeral=true",  bus.request("c", "worker", "hi", ephemeral = true))
        assertEquals("ephemeral=false", bus.request("c", "worker", "hi", ephemeral = false))
    }
}
```

**Step 3: Run core tests**

```bash
./gradlew :core:test --rerun-tasks 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

---

### Task 7: Add ephemeral to `AskAgentTool`, `AgentMessagingConfig`, and `Main.kt`

**Files:**
- Modify: `tools/src/main/kotlin/com/assistant/tools/agent/AskAgentTool.kt`
- Modify: `app/src/main/kotlin/com/assistant/Config.kt`
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`

**Step 1: Update `AskAgentTool`**

Replace:
```kotlin
class AskAgentTool(
    private val bus: AgentBus,
    private val callerName: String,
    private val timeoutMs: Long = 30_000
) : ToolPort {
```
With:
```kotlin
class AskAgentTool(
    private val bus: AgentBus,
    private val callerName: String,
    private val timeoutMs: Long = 30_000,
    private val ephemeral: Boolean = false
) : ToolPort {
```

Replace:
```kotlin
return Observation.Success(bus.request(from = callerName, to = to, message = message, timeoutMs = timeoutMs))
```
With:
```kotlin
return Observation.Success(bus.request(from = callerName, to = to, message = message, timeoutMs = timeoutMs, ephemeral = ephemeral))
```

**Step 2: Update `AgentMessagingConfig` in `Config.kt`**

Replace:
```kotlin
@Serializable data class AgentMessagingConfig(
    val enabled: Boolean = true,
    @SerialName("timeout-ms") val timeoutMs: Long = 30_000
)
```
With:
```kotlin
@Serializable data class AgentMessagingConfig(
    val enabled: Boolean = true,
    @SerialName("timeout-ms") val timeoutMs: Long = 30_000,
    val ephemeral: Boolean = false
)
```

**Step 3: Update `Main.kt` — bus handler and `AskAgentTool` construction**

In the multi-agent path, replace the `registerAgent` lambda:
```kotlin
bus.registerAgent(agentName) { from, text ->
    val sessionKey = "AGENT:$from→$agentName"
```
With:
```kotlin
bus.registerAgent(agentName) { from, text, ephemeral ->
    val sessionKey = if (ephemeral)
        "AGENT:$from→$agentName:${java.util.UUID.randomUUID()}"
    else
        "AGENT:$from→$agentName"
```

Replace:
```kotlin
AskAgentTool(bus, agentName, routing.messaging.timeoutMs)
```
With:
```kotlin
AskAgentTool(bus, agentName, routing.messaging.timeoutMs, routing.messaging.ephemeral)
```

**Step 4: Update `AskAgentToolTest` — add ephemeral tests and fix existing**

In `tools/src/test/kotlin/com/assistant/tools/agent/AskAgentToolTest.kt`, the mock `bus.request` calls need `ephemeral` in their matchers. Update all `coEvery { bus.request(...) }` to include `ephemeral = any()` or the specific value. Also add:

```kotlin
@Test
fun `passes ephemeral flag to bus`() = runTest {
    coEvery { bus.request(from = "personal", to = "work-agent", message = "ping", timeoutMs = any(), ephemeral = true) } returns "pong"

    val tool = AskAgentTool(bus, callerName = "personal", ephemeral = true)
    val result = tool.execute(ToolCall("agent_ask", mapOf("to" to "work-agent", "message" to "ping")))

    assertTrue(result is Observation.Success)
    coVerify { bus.request(from = "personal", to = "work-agent", message = "ping", timeoutMs = any(), ephemeral = true) }
}
```

**Step 5: Run all tests**

```bash
./gradlew :core:test :tools:test --rerun-tasks 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

**Step 6: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/AgentBus.kt \
        core/src/test/kotlin/com/assistant/agent/AgentBusTest.kt \
        tools/src/main/kotlin/com/assistant/tools/agent/AskAgentTool.kt \
        tools/src/test/kotlin/com/assistant/tools/agent/AskAgentToolTest.kt \
        app/src/main/kotlin/com/assistant/Config.kt \
        app/src/main/kotlin/com/assistant/Main.kt
git commit -m "feat: add ephemeral session mode to AskAgentTool and AgentBus"
```

---

## PART C — gRPC Agent Bus & Discovery

### Task 8: Add gRPC Gradle dependencies and proto file

**Files:**
- Modify: `build.gradle.kts` (root)
- Modify: `core/build.gradle.kts`
- Create: `core/src/main/proto/agent.proto`

**Step 1: Add protobuf plugin to root `build.gradle.kts`**

Replace:
```kotlin
plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
}
```
With:
```kotlin
plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}
```

**Step 2: Rewrite `core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("plugin.serialization")
    id("com.google.protobuf")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.cronutils:cron-utils:9.2.1")
    // gRPC
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-netty-shaded:1.63.0")
    implementation("io.grpc:grpc-stub:1.63.0")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.3")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation("io.grpc:grpc-testing:1.63.0")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.63.0" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc") {}
                id("grpckt") {}
            }
            task.builtins {
                id("kotlin") {}
            }
        }
    }
}
```

**Step 3: Create `core/src/main/proto/agent.proto`**

```proto
syntax = "proto3";

package com.assistant.agent.grpc;

option java_multiple_files = true;
option java_package = "com.assistant.agent.grpc";

service AgentService {
    rpc Ask (AgentRequest) returns (AgentResponse);
}

message AgentRequest {
    string from     = 1;
    string message  = 2;
    bool   ephemeral = 3;
}

message AgentResponse {
    string text = 1;
}
```

**Step 4: Generate proto stubs and verify**

```bash
./gradlew :core:generateProto 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`. Generated files appear in `core/build/generated/source/proto/`.

**Step 5: Full compile check**

```bash
./gradlew :core:compileKotlin 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

**Step 6: Commit**

```bash
git add build.gradle.kts core/build.gradle.kts core/src/main/proto/agent.proto
git commit -m "build: add gRPC + protobuf dependencies and agent.proto"
```

---

### Task 9: `AgentRegistry` — interface + `StaticAgentRegistry` + `FileSystemAgentRegistry`

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/AgentRegistry.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/AgentRegistryTest.kt`

**Step 1: Write the tests first**

```kotlin
package com.assistant.agent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AgentRegistryTest {

    // --- StaticAgentRegistry ---

    @Test
    fun `static registry resolves known agent`() {
        val registry = StaticAgentRegistry(mapOf("worker" to "localhost:9090"))
        assertEquals("localhost:9090", registry.resolve("worker"))
    }

    @Test
    fun `static registry returns null for unknown agent`() {
        val registry = StaticAgentRegistry(emptyMap())
        assertNull(registry.resolve("ghost"))
    }

    @Test
    fun `static registry all returns full map`() {
        val entries = mapOf("a" to "h:1", "b" to "h:2")
        assertEquals(entries, StaticAgentRegistry(entries).all())
    }

    @Test
    fun `static registry register is a no-op`() {
        val registry = StaticAgentRegistry(emptyMap())
        registry.register("anything", "localhost:9999")  // must not throw
        assertNull(registry.resolve("anything"))
    }

    // --- FileSystemAgentRegistry ---

    @Test
    fun `filesystem registry resolve returns null before register`(@TempDir dir: File) {
        val registry = FileSystemAgentRegistry(dir)
        assertNull(registry.resolve("worker"))
    }

    @Test
    fun `filesystem registry register then resolve`(@TempDir dir: File) {
        val registry = FileSystemAgentRegistry(dir)
        registry.register("worker", "localhost:9090")
        assertEquals("localhost:9090", registry.resolve("worker"))
        // cleanup: delete the file to avoid JVM shutdown hook running in wrong context
        File(dir, "worker.address").delete()
    }

    @Test
    fun `filesystem registry all lists registered agents`(@TempDir dir: File) {
        val registry = FileSystemAgentRegistry(dir)
        File(dir, "a.address").writeText("localhost:9001")
        File(dir, "b.address").writeText("localhost:9002")
        val all = registry.all()
        assertEquals("localhost:9001", all["a"])
        assertEquals("localhost:9002", all["b"])
    }
}
```

**Step 2: Run to confirm compile failure**

```bash
./gradlew :core:compileTestKotlin 2>&1 | grep error | head -5
```

**Step 3: Implement `AgentRegistry.kt`**

```kotlin
package com.assistant.agent

import java.io.File

interface AgentRegistry {
    fun register(name: String, address: String)
    fun resolve(name: String): String?
    fun all(): Map<String, String>
}

class StaticAgentRegistry(private val entries: Map<String, String>) : AgentRegistry {
    override fun register(name: String, address: String) { /* read-only */ }
    override fun resolve(name: String): String? = entries[name]
    override fun all(): Map<String, String> = entries
}

class FileSystemAgentRegistry(
    private val registryDir: File = File(System.getProperty("user.home"), ".assistant/agents")
) : AgentRegistry {

    init { registryDir.mkdirs() }

    override fun register(name: String, address: String) {
        val file = File(registryDir, "$name.address")
        file.writeText(address)
        Runtime.getRuntime().addShutdownHook(Thread { file.delete() })
    }

    override fun resolve(name: String): String? =
        File(registryDir, "$name.address").takeIf { it.exists() }?.readText()?.trim()

    override fun all(): Map<String, String> =
        registryDir.listFiles { f -> f.extension == "address" }
            ?.associate { f -> f.nameWithoutExtension to f.readText().trim() }
            ?: emptyMap()
}
```

**Step 4: Run tests**

```bash
./gradlew :core:test --tests "com.assistant.agent.AgentRegistryTest" --rerun-tasks 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/AgentRegistry.kt \
        core/src/test/kotlin/com/assistant/agent/AgentRegistryTest.kt
git commit -m "feat: add AgentRegistry with Static and FileSystem implementations"
```

---

### Task 10: `GrpcAgentBus`

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/GrpcAgentBus.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/GrpcAgentBusTest.kt`

**Step 1: Write the integration test first**

Uses `io.grpc:grpc-testing`'s `InProcessServerBuilder` / `InProcessChannelBuilder` — no real network.

```kotlin
package com.assistant.agent

import com.assistant.agent.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GrpcAgentBusTest {

    @Test
    fun `routes request to registered gRPC server and returns response`() = runTest {
        val serverName = InProcessServerBuilder.generateName()

        // Start a minimal in-process gRPC server
        val server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(object : AgentServiceGrpcKt.AgentServiceCoroutineImplBase() {
                override suspend fun ask(request: AgentRequest): AgentResponse =
                    AgentResponse.newBuilder().setText("echo:${request.message}").build()
            })
            .build().start()

        // Build a channel and stub for GrpcAgentBus to use
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()

        // Inject via a custom registry that returns a fake address
        // and supply the channel directly by overriding buildStub
        val registry = StaticAgentRegistry(mapOf("worker" to "inprocess:$serverName"))
        val bus = GrpcAgentBus(registry, channelOverride = mapOf("worker" to channel))

        val result = bus.request(from = "caller", to = "worker", message = "hello")
        assertEquals("echo:hello", result)

        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `returns error for unknown agent`() = runTest {
        val bus = GrpcAgentBus(StaticAgentRegistry(emptyMap()))
        val result = bus.request(from = "c", to = "ghost", message = "hi")
        assertTrue(result.startsWith("Error:"))
        assertTrue(result.contains("ghost"))
    }
}
```

**Step 2: Implement `GrpcAgentBus.kt`**

```kotlin
package com.assistant.agent

import com.assistant.agent.grpc.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class GrpcAgentBus(
    private val registry: AgentRegistry,
    /** Test seam: pre-built channels injected by name, bypasses address resolution. */
    internal val channelOverride: Map<String, ManagedChannel> = emptyMap()
) : AgentBus {

    private val stubs = ConcurrentHashMap<String, AgentServiceGrpcKt.AgentServiceCoroutineStub>()

    override fun registerAgent(name: String, handler: suspend (String, String, Boolean) -> String) {
        // No-op: server-side registration is handled by AgentGrpcServer
    }

    override suspend fun request(from: String, to: String, message: String, timeoutMs: Long, ephemeral: Boolean): String {
        val stub = stubs.getOrPut(to) {
            val channel = channelOverride[to] ?: buildChannel(to) ?: return "Error: agent '$to' not found in registry"
            AgentServiceGrpcKt.AgentServiceCoroutineStub(channel)
        }
        return runCatching {
            withTimeoutOrNull(timeoutMs) {
                stub.ask(AgentRequest.newBuilder()
                    .setFrom(from)
                    .setMessage(message)
                    .setEphemeral(ephemeral)
                    .build()).text
            } ?: "Error: agent '$to' timed out after ${timeoutMs}ms"
        }.getOrElse { e -> "Error: gRPC call to '$to' failed: ${e.message}" }
    }

    private fun buildChannel(name: String): ManagedChannel? {
        val address = registry.resolve(name) ?: return null
        val parts = address.split(":")
        return ManagedChannelBuilder
            .forAddress(parts[0], parts[1].toInt())
            .usePlaintext()
            .build()
    }
}
```

**Step 3: Run tests**

```bash
./gradlew :core:test --tests "com.assistant.agent.GrpcAgentBusTest" --rerun-tasks 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/GrpcAgentBus.kt \
        core/src/test/kotlin/com/assistant/agent/GrpcAgentBusTest.kt
git commit -m "feat: add GrpcAgentBus for cross-process agent communication"
```

---

### Task 11: `AgentGrpcServer`

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/AgentGrpcServer.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/AgentGrpcServerTest.kt`

**Step 1: Write the integration test**

```kotlin
package com.assistant.agent

import com.assistant.agent.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AgentGrpcServerTest {

    @Test
    fun `server handles ask and returns handler response`() = runTest {
        val registry = StaticAgentRegistry(emptyMap())  // not used for resolution in this test
        val serverName = InProcessServerBuilder.generateName()
        val server = AgentGrpcServer(registry, serverName = serverName)

        server.registerHandler("worker") { from, message, ephemeral ->
            "from=$from msg=$message eph=$ephemeral"
        }
        server.startInProcess()  // test-only: starts on in-process transport

        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        val stub = AgentServiceGrpcKt.AgentServiceCoroutineStub(channel)

        val response = stub.ask(AgentRequest.newBuilder()
            .setFrom("caller").setMessage("hello").setEphemeral(true).build())

        assertEquals("from=caller msg=hello eph=true", response.text)

        channel.shutdownNow()
        server.stop()
    }
}
```

**Step 2: Implement `AgentGrpcServer.kt`**

```kotlin
package com.assistant.agent

import com.assistant.agent.grpc.*
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.ConcurrentHashMap

class AgentGrpcServer(
    private val registry: AgentRegistry,
    private val port: Int = 0,
    /** Test seam: non-null uses InProcessServerBuilder with this name instead of port. */
    internal val serverName: String? = null
) {
    private val handlers = ConcurrentHashMap<String, suspend (String, String, Boolean) -> String>()
    private lateinit var server: Server

    private val serviceImpl = object : AgentServiceGrpcKt.AgentServiceCoroutineImplBase() {
        override suspend fun ask(request: AgentRequest): AgentResponse {
            val handler = handlers.values.firstOrNull()
                ?: error("No handlers registered on this AgentGrpcServer")
            val text = handler(request.from, request.message, request.ephemeral)
            return AgentResponse.newBuilder().setText(text).build()
        }
    }

    fun registerHandler(name: String, handler: suspend (from: String, message: String, ephemeral: Boolean) -> String) {
        handlers[name] = handler
        registry.register(name, "localhost:$port")
    }

    fun start() {
        server = ServerBuilder.forPort(port).addService(serviceImpl).build().start()
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /** For tests: start on in-process transport (no real port needed). */
    fun startInProcess() {
        requireNotNull(serverName) { "serverName must be set to use startInProcess()" }
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build().start()
    }

    fun stop() { if (::server.isInitialized) server.shutdownNow() }
}
```

**Step 3: Run tests**

```bash
./gradlew :core:test --tests "com.assistant.agent.AgentGrpcServerTest" --rerun-tasks 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/AgentGrpcServer.kt \
        core/src/test/kotlin/com/assistant/agent/AgentGrpcServerTest.kt
git commit -m "feat: add AgentGrpcServer to serve local agents over gRPC"
```

---

### Task 12: Config additions for gRPC

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Config.kt`

**Step 1: Add `GrpcServerConfig` data class and update `RoutingConfig`**

After the `AgentMessagingConfig` class, add:
```kotlin
@Serializable data class GrpcServerConfig(
    val enabled: Boolean = false,
    val port: Int = 9090
)
```

Replace `RoutingConfig`:
```kotlin
@Serializable data class RoutingConfig(
    val channels: Map<String, String> = emptyMap(),
    val default: String = "default",
    val messaging: AgentMessagingConfig = AgentMessagingConfig(),
    val grpc: GrpcServerConfig = GrpcServerConfig(),
    @SerialName("remote-agents") val remoteAgents: Map<String, String> = emptyMap(),
    val discovery: String = "static"   // "static" | "filesystem"
)
```

**Step 2: Compile**

```bash
./gradlew :app:compileKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/assistant/Config.kt
git commit -m "feat: add GrpcServerConfig and remote-agents to RoutingConfig"
```

---

### Task 13: Wire gRPC in `Main.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`

**Step 1: Add imports at the top of `Main.kt`**

```kotlin
import com.assistant.agent.AgentGrpcServer
import com.assistant.agent.AgentRegistry
import com.assistant.agent.FileSystemAgentRegistry
import com.assistant.agent.GrpcAgentBus
import com.assistant.agent.StaticAgentRegistry
import java.util.UUID
```

**Step 2: Replace the multi-agent bus creation block**

Find the existing block (starts with `val busScope = ...`):

```kotlin
val busScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
val bus = InProcessAgentBus(busScope)
```

Replace with:

```kotlin
val busScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

val grpcEnabled = routing.grpc.enabled
val registry: AgentRegistry = when {
    grpcEnabled && routing.discovery == "filesystem" -> FileSystemAgentRegistry()
    grpcEnabled -> StaticAgentRegistry(routing.remoteAgents)
    else -> StaticAgentRegistry(emptyMap())  // unused for InProcessAgentBus
}

val bus: AgentBus = if (grpcEnabled) GrpcAgentBus(registry) else InProcessAgentBus(busScope)
val grpcServer: AgentGrpcServer? = if (grpcEnabled) AgentGrpcServer(registry, port = routing.grpc.port) else null
```

**Step 3: Update the `registerAgent` call to also register with the gRPC server**

Find the existing loop:
```kotlin
finalStacks.forEach { (agentName, stack) ->
    bus.registerAgent(agentName) { from, text, ephemeral ->
        val sessionKey = ...
        stack.engine.process(...)
    }
}
```

Replace with:
```kotlin
finalStacks.forEach { (agentName, stack) ->
    val handler: suspend (String, String, Boolean) -> String = { from, text, ephemeral ->
        val sessionKey = if (ephemeral)
            "AGENT:$from→$agentName:${UUID.randomUUID()}"
        else
            "AGENT:$from→$agentName"
        val session = Session(id = sessionKey, userId = from, channel = Channel.AGENT)
        stack.engine.process(session, Message(sender = from, text = text, channel = Channel.AGENT))
    }
    bus.registerAgent(agentName, handler)
    grpcServer?.registerHandler(agentName, handler)
}
grpcServer?.start()
```

**Step 4: Final compile**

```bash
./gradlew :app:compileKotlin 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/assistant/Main.kt
git commit -m "feat: wire GrpcAgentBus and AgentGrpcServer into multi-agent path"
```

---

### Task 14: Full test suite + fat JAR verification

**Step 1: Run all tests**

```bash
./gradlew test --rerun-tasks 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, no failures.

**Step 2: Build fat JAR**

```bash
./gradlew shadowJar 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Smoke test gRPC config (optional — requires two JVM processes)**

Add to `config/application.yml` under `routing:`:
```yaml
routing:
  grpc:
    enabled: false    # flip to true in separate process experiments
    port: 9090
  remote-agents: {}
  discovery: static
```
Confirm the app still starts normally (in-process path unchanged).

---

## Sample `application.yml` snippet for cross-process gRPC

```yaml
# Process A — "personal" agent
routing:
  channels:
    telegram: personal
  default: personal
  messaging:
    enabled: true
    timeout-ms: 30000
    ephemeral: false
  grpc:
    enabled: true
    port: 9090
  remote-agents:
    work-agent: "localhost:9091"
  discovery: filesystem   # reads ~/.assistant/agents/work-agent.address

# Process B — "work-agent" (separate JVM, different config file)
routing:
  default: work-agent
  grpc:
    enabled: true
    port: 9091
  remote-agents:
    personal: "localhost:9090"
  discovery: filesystem
```

Process B writes `~/.assistant/agents/work-agent.address = localhost:9091` on startup. Process A's `FileSystemAgentRegistry` picks it up on the next `resolve("work-agent")` call.
