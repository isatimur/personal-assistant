# OSS Extensible Assistant — Design

**Date:** 2026-02-26
**Approach:** Phased OSS Evolution (Approach A)
**Goal:** Evolve the Kotlin assistant into an open-source, extensible platform rivalling OpenClaw — keeping the hexagonal architecture, shipping incrementally.

---

## Architecture Overview

The existing module structure (app, core, channels, providers, tools, memory) stays intact. Three additive changes form the OSS foundation:

### 1. ChannelPort (new interface in `core`)

Right now Telegram wires up directly in `Main.kt` with no channel abstraction. Add to `core/Ports.kt`:

```kotlin
interface ChannelPort {
    val name: String
    suspend fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String)
    suspend fun send(sessionId: String, text: String)
}
```

`TelegramAdapter` is refactored to implement it. All future channels implement the same interface.

### 2. Plugin Discovery via Java ServiceLoader

Plugins are fat JARs dropped into `~/.assistant/plugins/`. On startup, `PluginLoader` (new class in `app`):

1. Scans `~/.assistant/plugins/*.jar`
2. Creates a `URLClassLoader` parented to the app classloader
3. Calls `ServiceLoader.load(ToolPort::class.java, classLoader)` (and same for `ChannelPort`, `LlmPort`, `MemoryPort`, `EmbeddingPort`)
4. Merges discovered plugins with built-in implementations from `Main.kt`
5. Logs each loaded plugin by name at INFO level

**Plugin JAR structure:**

```
my-discord-channel.jar
  ├── com/example/DiscordChannelAdapter.class
  └── META-INF/services/
        └── com.assistant.ports.ChannelPort   ← "com.example.DiscordChannelAdapter"
```

Plugin authors depend on `com.assistant:core:1.x`, implement one interface, declare it in `META-INF/services` (or use `@AutoService`). That is the entire contract.

**Config:** Plugins read their own config from `config/application.yml` under a key matching their `name`, or from `~/.assistant/plugins/<name>.yml`.

### 3. Publish `core` to Maven Central

`core` becomes the public SDK. Published as `com.assistant:core` via GitHub Actions → Sonatype. The app remains a reference implementation.

---

## Phase Roadmap

### Phase 1 — OSS Foundation
*Enables community contributions.*

- Add `ChannelPort` to `core/Ports.kt`
- Refactor `TelegramAdapter` to implement `ChannelPort`
- Implement `PluginLoader` in `app`
- GitHub Actions workflow: publish `core` to Maven Central on tag
- Add `plugins/` scaffold to repo + `CONTRIBUTING.md`

### Phase 2 — Quick Wins
*Ship fast, build credibility.*

- **Model tiering**: add `tier: fast|standard` to tool config; route fast-tier steps to haiku, standard to sonnet/opus in `LangChain4jProvider`
- **Better web search**: replace DuckDuckGo HTML scrape in `WebBrowserTool` with Brave Search API or Tavily (config-switchable via `web.search-provider: brave|tavily|duckduckgo`)
- **Generic HTTP tool**: `http_get(url, headers)` / `http_post(url, body, headers)` — unlocks any REST API without a dedicated tool

### Phase 3 — Capability Parity
*Close the gap with OpenClaw.*

- **Browser automation**: replace JSoup static fetch with Playwright JVM (`com.microsoft.playwright`) — JS rendering, screenshots, form fills, vision model integration
- **Second channel**: Discord via JDA or Slack via Bolt-Kotlin, proving `ChannelPort` works across real platforms
- **Document ingestion**: `knowledge_ingest(path)` tool — chunks files, stores embeddings via `EmbeddingPort`, enables RAG over local docs

### Phase 4 — Community & Polish
*Long-tail value.*

- **Web admin UI**: read-only Ktor dashboard — memory stats, token usage, heartbeat log, session viewer
- **TTS voice output**: ElevenLabs or OpenAI TTS; respond with audio when user sends voice
- **Plugin registry**: community `PLUGINS.md` list initially; vector-search registry later

---

## Key Constraints

- Each phase is independently shippable.
- No existing module is deleted or restructured — all changes are additive.
- Built-in tools/channels keep working exactly as today.
- Plugin system uses only JVM-standard `ServiceLoader` — no extra DI framework.
