# Phase 3 — Capability Parity: Design

**Date:** 2026-02-27
**Goal:** Close the capability gap with OpenClaw by adding full browser automation, a second real channel (Discord), and document/knowledge ingestion — all building on the Phase 1/2 OSS foundation.

---

## Architecture Overview

Three independent, additive changes. No existing modules are restructured.

### 1. Browser Automation (Playwright JVM)

`WebBrowserTool.web_fetch()` currently uses OkHttp + JSoup for static HTML fetching. This fails on JS-heavy pages. Replacing it with Playwright JVM gives full Chromium rendering.

**Changes:**
- Add `com.microsoft.playwright:playwright:1.49.0` to `tools/build.gradle.kts`
- `WebBrowserTool` gets a lazy `Browser` (Chromium headless) opened on first `web_fetch` call
- Each fetch: create `Page`, navigate, wait for network idle, extract `body` text, close `Page`
- JSoup still used for text cleaning after Playwright delivers the rendered HTML
- `WebBrowserTool` implements `Closeable` to shut down the `Browser` cleanly
- All Playwright calls dispatched via `withContext(Dispatchers.IO)` — library is blocking
- Search commands (Brave/Tavily/DuckDuckGo) are unchanged
- Setup: `playwright install chromium` run once (documented in `CONTRIBUTING.md`)

**Why not a persistent page pool:** Stateless per-request pages avoid session bleed between tool calls. Performance is acceptable for an assistant use case (one fetch at a time).

---

### 2. Discord Channel

New `DiscordAdapter` in `channels/` implementing `ChannelPort` via JDA 5.x.

**Changes:**
- Add `net.dv8tion:JDA:5.2.1` to `channels/build.gradle.kts`
- New `channels/src/main/kotlin/com/assistant/discord/DiscordAdapter.kt`
  - `name = "discord"`
  - `start(onMessage)` — registers JDA `MessageReceivedEvent` listener, ignores bot's own messages, calls `onMessage` with `sessionId = "DISCORD:<channelId>"`
  - `send(sessionId, text)` — parses channel ID, calls `jda.getTextChannelById(id)?.sendMessage(text)?.queue()`
- Add `DISCORD` to `Channel` enum in `core/domain/Types.kt`
- New `DiscordConfig(token: String, enabled: Boolean = false)` in `app/Config.kt`
- Wire in `Main.kt` guarded by `config.discord.enabled`
- **Slack** shown as a plugin example in `CONTRIBUTING.md` — skeleton `SlackChannelAdapter` using Bolt-Kotlin, `META-INF/services` declaration, JAR drop instructions

**Session ID format:** `DISCORD:<channelId>` — consistent with `TELEGRAM:<chatId>`.

---

### 3. Document Ingestion

New `KnowledgeIngestTool` in `tools/` — takes `MemoryPort` as a constructor param.

**Changes:**
- Add `org.apache.pdfbox:pdfbox:3.0.3` to `tools/build.gradle.kts`
- New `tools/src/main/kotlin/com/assistant/tools/knowledge/KnowledgeIngestTool.kt`
  - Single command: `knowledge_ingest(path: String)`
  - Reads file, detects type by extension (`.txt`, `.md` → UTF-8 read; `.pdf` → PDFBox extraction)
  - Chunks text: 512-char segments, 80-char overlap (matches memory layer's own chunking)
  - Stores each chunk via `memory.saveFact(userId, chunk)`
  - Returns `"Ingested N chunks from <filename>"`
- `userId` passed at construction time from `Main.kt` — defaults to `"knowledge"` system ID so ingested docs are globally searchable across sessions
- `KnowledgeConfig(enabled: Boolean = true)` in `ToolsConfig` — enabled by default (no credentials needed)
- Wire in `Main.kt` alongside other tools

**Why `saveFact` not a new storage path:** Chunks land in the same hybrid BM25+vector index already in place. No new retrieval logic needed — ingested content is automatically surfaced by `ContextAssembler.search()` during conversations.

---

## Phase Roadmap

| Item | Module(s) touched | New deps |
|------|------------------|----------|
| Playwright browser | `tools/` | `com.microsoft.playwright:playwright:1.49.0` |
| Discord channel | `channels/`, `core/`, `app/` | `net.dv8tion:JDA:5.2.1` |
| Document ingestion | `tools/`, `app/` | `org.apache.pdfbox:pdfbox:3.0.3` |

All three are independently shippable. Suggested order: Discord → Document ingestion → Browser automation (Playwright download is the heaviest setup step, save it for last).

---

## Key Constraints

- No existing module restructured — all changes additive
- Discord guarded by `enabled: false` — zero impact until configured
- Playwright only downloaded if `web_fetch` is actually called (lazy init)
- Document ingestion reuses existing memory search — no new retrieval path
