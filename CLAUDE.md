# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build fat JAR
./gradlew shadowJar

# Run the assistant
java -jar app/build/libs/assistant.jar

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :core:test
./gradlew :channels:test
./gradlew :tools:test
./gradlew :providers:test
./gradlew :memory:test

# Run a single test class
./gradlew :core:test --tests "com.assistant.domain.AgentEngineTest"

# Run a single test method
./gradlew :core:test --tests "com.assistant.domain.AgentEngineTest.someMethod"

# Build without tests
./gradlew build -x test
```

## Module Architecture

Six Gradle modules with clear separation:

- **`app`** — Entry point (`Main.kt`), config loading, wires all modules together
- **`core`** — Domain logic: `AgentEngine`, ports/interfaces, `ContextAssembler`, `CompactionService`, `TokenTracker`, `Gateway`
- **`channels`** — Channel adapters: Telegram (voice, image, commands, reminders), Discord, Slack (Socket Mode), WebChat (Ktor WebSocket), WhatsApp (Meta Cloud API)
- **`providers`** — LangChain4j LLM providers (OpenAI, Anthropic, Ollama) + `OpenAiTtsProvider`
- **`tools`** — Tool implementations: Shell, Web, FileSystem, Email, GitHub, Jira, Linear
- **`memory`** — SQLite-backed memory with FTS5 full-text search and optional embedding vectors

## Key Architectural Patterns

### Ports/Adapters
All cross-module communication goes through interfaces in `core/src/main/kotlin/com/assistant/ports/Ports.kt`:
- `LlmPort` — LLM completion with and without function calling; `stream()` for token-by-token delivery
- `TtsPort` — Text-to-speech synthesis returning MP3 bytes
- `ToolPort` — Tool registry entries (`name`, `description`, `commands()`, `execute()`)
- `MemoryPort` — Conversation history, facts, semantic search
- `EmbeddingPort` — Optional vector embeddings

### Agent Loop
`AgentEngine.kt` runs up to 10 steps: Think → Act (tool call) → Observe → repeat → Respond. The loop terminates when LLM returns text instead of a function call. The final synthesis step uses `LlmPort.stream()`, forwarding tokens to the channel via `onProgress` prefixed with `STREAM_TOKEN_PREFIX` (`\u0001`). Tool-call steps remain non-streaming.

### Config System
Two-tier YAML loading via KAML + kotlinx-serialization:
1. `config/application.yml` — base config
2. `config/secrets.yml` — API keys overlay (not in git)

`ConfigWatcher` monitors both files with a 5-second debounce and triggers JVM exit on change (process manager handles restart).

### Workspace Personalization
At startup, `ContextAssembler` loads behavioral files from `~/.assistant/`:
- `bootstrap.md`, `identity.md`, `soul.md`, `user.md`, `skills.md` → system prompt
- `agent-profiles.yaml` → multi-agent routing rules

### Runtime Data
All runtime state lives in `~/.assistant/`:
- `memory.db` — SQLite database
- `reminders.json` — scheduled reminders
- `last-chat-id` — heartbeat target

## Critical Quirks

**OpenAI model `gpt-5-nano-2025-08-07`**: Must set `temperature(1.0)` explicitly in `LangChain4jProvider.kt`. The model rejects any other temperature value with HTTP 400. LangChain4j defaults to 0.7, which breaks this model.

**Config hot-reload**: Changing `config/application.yml` or `config/secrets.yml` causes the JVM to exit — the process manager (e.g., `launchd`, `systemd`) must be configured to restart it.

**SQLite thread safety**: `SqliteMemoryStore` uses `Mutex` locks — never bypass them when modifying the DB layer.

## Testing Patterns

- JUnit 5 + Mockk throughout; coroutine tests use `runTest`
- Mock tools via `ToolPort` interface — no real API calls in unit tests
- Agent engine tests wire mock `LlmPort`, `MemoryPort`, and `ToolPort` directly
- Config tests parse YAML strings to validate deserialization

## Adding a New Tool

1. Create class in `tools/` implementing `ToolPort`
2. Add a config data class in `app/src/main/kotlin/com/assistant/Config.kt` if needed
3. Register in `Main.kt` where the tool list is assembled
4. Add config section to `config/application.yml` with `enabled: false` as default

## Adding a New Channel

1. Create adapter in `channels/` implementing `ChannelPort` (and `Closeable` if it owns a server)
2. Add `Channel.YOUR_CHANNEL` to the enum in `core/src/main/kotlin/com/assistant/domain/Types.kt`
3. Add a config data class + secrets class in `Config.kt`; wire secrets in `loadConfig()`
4. Instantiate and call `start()` in `Main.kt` guarded by `config.yourChannel.enabled`
5. Add disabled-by-default stanza to `config/application.yml`

**Streaming convention**: channels detect `STREAM_TOKEN_PREFIX` (`\u0001`) in the `onProgress` callback to identify LLM tokens vs. tool-use progress messages. Use `outgoing.trySend()` / non-blocking calls from inside the `(String) -> Unit` lambda; reserve suspend calls for the surrounding coroutine body.

**TTS**: `TtsPort` is wired into `TelegramAdapter` only. Pass `ttsPort` from `Main.kt` when constructing the adapter.
