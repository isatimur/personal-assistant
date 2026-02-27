# Engine Plugins, gRPC Agent Bus & Discovery — Design

**Date:** 2026-02-27
**Status:** Approved

---

## Context

Three complementary improvements to the agent framework, motivated by the ADK-Java architectural review:

1. **`EnginePlugin` system** — structured hooks at every stage of the ReAct loop (before/after tool, before/after LLM, on response, on error). Enables cost tracking, tracing, and custom instrumentation without modifying `AgentEngine`.
2. **Ephemeral mode on `AskAgentTool`** — adds `ephemeral: Boolean = false` so inter-agent calls can be one-shot (no history accumulation) when the caller doesn't want context to persist across delegations.
3. **`GrpcAgentBus` + `AgentRegistry` discovery** — extends the `AgentBus` interface to support cross-process agent communication via gRPC, with a clean `AgentRegistry` abstraction backed by static config or file-system self-registration.

---

## 1. EnginePlugin System

### Interface (in `core/src/main/kotlin/com/assistant/ports/Ports.kt`)

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

All methods default to no-ops — implementors override only the hooks they need.

### AgentEngine changes (`core/src/main/kotlin/com/assistant/agent/AgentEngine.kt`)

- Add `plugins: List<EnginePlugin> = emptyList()` constructor param.
- Before each tool execution: `plugins.fireBeforeTool(session, call)`
- After each tool execution: `plugins.fireAfterTool(session, call, result, durationMs)`
- Before each LLM call: `plugins.fireBeforeLlm(session, stepIndex)`
- After each LLM call: `plugins.fireAfterLlm(session, stepIndex, usage, durationMs)`
- Before returning: `plugins.fireOnResponse(session, text, steps)`
- In any exception path: `plugins.fireOnError(session, error)`
- Each `fire*` extension iterates the list; exceptions inside a plugin are caught, logged with plugin name, and swallowed — they never break the agent loop.

### Built-in: `LoggingPlugin` (`core/src/main/kotlin/com/assistant/agent/LoggingPlugin.kt`)

```kotlin
class LoggingPlugin(private val logger: Logger = Logger.getLogger("AgentEngine")) : EnginePlugin {
    override suspend fun beforeTool(session: Session, call: ToolCall) {
        logger.info("[${session.id}] → tool: ${call.name}")
    }
    override suspend fun afterTool(session: Session, call: ToolCall, result: Observation, durationMs: Long) {
        val status = if (result is Observation.Success) "ok" else "error"
        logger.info("[${session.id}] ← tool: ${call.name} ($status, ${durationMs}ms)")
    }
    override suspend fun afterLlm(session: Session, stepIndex: Int, usage: TokenUsage?, durationMs: Long) {
        logger.fine("[${session.id}] llm step=$stepIndex tokens=${usage?.inputTokens}+${usage?.outputTokens} (${durationMs}ms)")
    }
    override suspend fun onResponse(session: Session, text: String, steps: Int) {
        logger.info("[${session.id}] responded after $steps steps")
    }
}
```

### Wiring

- `AgentStack` gains `plugins: List<EnginePlugin>` field.
- `buildAgentEngine` accepts `plugins: List<EnginePlugin> = emptyList()`.
- `Main.kt` default: `listOf(LoggingPlugin())` for every stack.

---

## 2. Ephemeral Mode on `AskAgentTool`

### `AgentBus` interface change

```kotlin
interface AgentBus {
    fun registerAgent(name: String, handler: suspend (from: String, message: String, ephemeral: Boolean) -> String)
    suspend fun request(from: String, to: String, message: String, timeoutMs: Long = 30_000, ephemeral: Boolean = false): String
}
```

### `InProcessAgentBus` change

- `AgentRequest` gains `ephemeral: Boolean` field.
- Passes `ephemeral` to the handler lambda.

### `Main.kt` handler

```kotlin
bus.registerAgent(agentName) { from, text, ephemeral ->
    val sessionKey = if (ephemeral)
        "AGENT:$from→$agentName:${java.util.UUID.randomUUID()}"
    else
        "AGENT:$from→$agentName"
    val session = Session(id = sessionKey, userId = from, channel = Channel.AGENT)
    stack.engine.process(session, Message(sender = from, text = text, channel = Channel.AGENT))
}
```

### `AskAgentTool` change

- Add `ephemeral: Boolean = false` constructor param.
- Pass to `bus.request(..., ephemeral = ephemeral)`.
- Config: add `ephemeral: Boolean = false` to `AgentMessagingConfig`.

---

## 3. gRPC Agent Bus + AgentRegistry Discovery

### Dependencies (new module: `grpc` or added to `core`)

```kotlin
// build.gradle.kts additions
implementation("io.grpc:grpc-kotlin-stub:1.4.1")
implementation("io.grpc:grpc-netty-shaded:1.63.0")
implementation("com.google.protobuf:protobuf-kotlin:3.25.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")  // already present
```

Gradle plugin: `com.google.protobuf` for code generation.

### Proto definition (`core/src/main/proto/agent.proto`)

```proto
syntax = "proto3";
option java_package = "com.assistant.agent.grpc";

service AgentService {
    rpc Ask(AgentRequest) returns (AgentResponse);
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

### `AgentRegistry` interface (`core/src/main/kotlin/com/assistant/agent/AgentRegistry.kt`)

```kotlin
interface AgentRegistry {
    /** Called by a server-side agent on startup. */
    fun register(name: String, address: String)  // "host:port"
    /** Called by a client to resolve an agent's address. Returns null if unknown. */
    fun resolve(name: String): String?
    /** All known agents at this moment. */
    fun all(): Map<String, String>
}
```

#### `StaticAgentRegistry`

Reads from a `Map<String, String>` (populated from `routing.remote-agents` YAML). Immutable — no writes.

#### `FileSystemAgentRegistry`

- **Register:** writes `~/.assistant/agents/{name}.address` containing `"host:port"`.
- **Resolve:** reads the file on demand (or caches with a short TTL, e.g. 5s).
- **All:** lists `*.address` files in the directory.
- No external dependencies — pure `java.nio.file` I/O.
- On JVM shutdown, the file is deleted (shutdown hook).

### `GrpcAgentBus` (`core/src/main/kotlin/com/assistant/agent/GrpcAgentBus.kt`)

```kotlin
class GrpcAgentBus(private val registry: AgentRegistry) : AgentBus {
    private val stubs = ConcurrentHashMap<String, AgentServiceGrpcKt.AgentServiceCoroutineStub>()

    override fun registerAgent(name: String, handler: ...) {} // no-op: handled by AgentGrpcServer

    override suspend fun request(from: String, to: String, message: String, timeoutMs: Long, ephemeral: Boolean): String {
        val address = registry.resolve(to) ?: return "Error: agent '$to' not found in registry"
        val stub = stubs.getOrPut(to) { buildStub(address) }
        return runCatching {
            withTimeout(timeoutMs) {
                stub.ask(agentRequest {
                    this.from = from
                    this.message = message
                    this.ephemeral = ephemeral
                }).text
            }
        }.getOrElse { e -> "Error: gRPC call to '$to' failed: ${e.message}" }
    }

    private fun buildStub(address: String): AgentServiceGrpcKt.AgentServiceCoroutineStub {
        val (host, port) = address.split(":")
        val channel = ManagedChannelBuilder.forAddress(host, port.toInt()).usePlaintext().build()
        return AgentServiceGrpcKt.AgentServiceCoroutineStub(channel)
    }
}
```

### `AgentGrpcServer` (`core/src/main/kotlin/com/assistant/agent/AgentGrpcServer.kt`)

```kotlin
class AgentGrpcServer(
    private val port: Int,
    private val registry: AgentRegistry,
    private val agentName: String
) {
    private lateinit var server: Server
    private val handlers = ConcurrentHashMap<String, suspend (String, String, Boolean) -> String>()

    fun registerLocalAgent(name: String, handler: suspend (from: String, message: String, ephemeral: Boolean) -> String) {
        handlers[name] = handler
    }

    fun start() {
        server = ServerBuilder.forPort(port)
            .addService(object : AgentServiceGrpcKt.AgentServiceCoroutineImplBase() {
                override suspend fun ask(request: AgentRequest): AgentResponse {
                    val handler = handlers[agentName] ?: error("No handler for $agentName")
                    val text = handler(request.from, request.message, request.ephemeral)
                    return agentResponse { this.text = text }
                }
            }).build().start()
        registry.register(agentName, "localhost:$port")
        Runtime.getRuntime().addShutdownHook(Thread { server.shutdown() })
    }
}
```

### Config additions (`app/src/main/kotlin/com/assistant/Config.kt`)

```kotlin
@Serializable
data class GrpcServerConfig(
    val enabled: Boolean = false,
    val port: Int = 9090
)

@Serializable
data class RemoteAgentEntry(
    val address: String   // "host:port"
)

@Serializable
data class RoutingConfig(
    val channels: Map<String, String> = emptyMap(),
    val default: String = "default",
    val messaging: AgentMessagingConfig = AgentMessagingConfig(),
    val grpc: GrpcServerConfig = GrpcServerConfig(),
    @SerialName("remote-agents") val remoteAgents: Map<String, String> = emptyMap(),
    @SerialName("discovery") val discovery: String = "static"  // "static" | "filesystem"
)
```

### `Main.kt` wiring logic

```
if routing.grpc.enabled:
    registry = FileSystemAgentRegistry | StaticAgentRegistry (per routing.discovery)
    bus = GrpcAgentBus(registry)
    server = AgentGrpcServer(port, registry, agentName)
    server.registerLocalAgent(agentName, handler)
    server.start()
else:
    bus = InProcessAgentBus(busScope)
    // existing path
```

When `routing.remoteAgents` is non-empty and `StaticAgentRegistry` is used, those entries are pre-loaded. `GrpcAgentBus` stubs are created lazily on first call.

---

## Module Placement

| Artifact | Module |
|---|---|
| `EnginePlugin` interface | `core` (ports) |
| `LoggingPlugin` | `core` (agent) |
| `AgentRegistry` interface + impls | `core` (agent) |
| `GrpcAgentBus` | `core` (agent) |
| `AgentGrpcServer` | `core` (agent) |
| `agent.proto` | `core` (src/main/proto) |
| gRPC Gradle config | `core/build.gradle.kts` |
| `AskAgentTool` ephemeral flag | `tools` |
| Config additions | `app` |
| `Main.kt` wiring | `app` |

---

## Testing Plan

- `EnginePluginTest` — mock plugin, verify all hooks fire in correct order with correct args; verify plugin exceptions don't propagate.
- `LoggingPluginTest` — verify log messages contain session ID, tool name, duration.
- `AgentEngineTest` — extend existing tests to cover plugin invocation.
- `AgentRegistryTest` — `FileSystemAgentRegistry` register/resolve/cleanup; `StaticAgentRegistry` reads from map.
- `GrpcAgentBusTest` — integration test: start in-process `AgentGrpcServer`, call via `GrpcAgentBus`, assert response.
- `AskAgentToolTest` — extend existing tests to cover ephemeral session key distinctness.

---

## Known Limitations

- **gRPC stubs are not refreshed** if an agent moves to a new address. The `FileSystemAgentRegistry` resolves fresh on each call (no caching), so stubs should be rebuilt on address change — future improvement.
- **No TLS** in gRPC for now (plaintext). Add `useTransportSecurity()` when deploying across machines.
- **Single agent per gRPC server process** in v1 — `AgentGrpcServer` routes all requests to one named agent. Multi-agent server support is a future extension.
- **No cycle detection** in gRPC path — same as in-process bus; prevent via design.
