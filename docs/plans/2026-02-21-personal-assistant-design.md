# Personal AI Assistant — Design Document

**Date:** 2026-02-21
**Inspired by:** OpenClaw architecture
**Stack:** Kotlin, LangChain4j, Telegram, SQLite

---

## Overview

A locally-running autonomous AI agent that you control via Telegram. It acts as a personal digital employee — reading and writing files, running shell commands, browsing the web, and managing email. All data stays on your machine. Model-agnostic: switch between OpenAI, Anthropic, Ollama, or any LangChain4j-supported provider via config.

---

## Architecture

OpenClaw-inspired hub-and-spoke pattern:

```
┌─────────────────────────────────┐
│           Gateway               │  ← orchestration, session management
└────────────────┬────────────────┘
                 │
      ┌──────────▼──────────┐
      │   Channel Adapter   │  ← Telegram (normalized message format)
      └──────────┬──────────┘
                 │
      ┌──────────▼──────────┐
      │  Context Assembly   │  ← history + memory + system prompt
      └──────────┬──────────┘
                 │
      ┌──────────▼──────────┐
      │    ReAct Loop       │  ← reason → tool call → observe → repeat
      └──────────┬──────────┘
        ┌────────┴────────┐
        │                 │
  ┌─────▼─────┐   ┌──────▼──────┐
  │ LLM Layer │   │  Tool Layer │
  │ (model-   │   │  fs/shell/  │
  │  agnostic)│   │  web/email  │
  └───────────┘   └─────────────┘
```

---

## Project Structure

Multi-module Gradle project (Kotlin DSL):

```
personal-assistant/
├── build.gradle.kts
├── settings.gradle.kts
├── config/
│   └── application.yml           # API keys, model config, Telegram token
│
├── core/                         # Gateway, ReAct loop, interfaces
│   └── src/main/kotlin/
│       ├── gateway/Gateway.kt
│       ├── agent/AgentEngine.kt
│       ├── agent/ContextAssembler.kt
│       └── ports/                # LlmPort, ToolPort, MemoryPort, EmbeddingPort
│
├── channels/                     # Telegram adapter
│   └── src/main/kotlin/
│       └── telegram/TelegramAdapter.kt
│
├── providers/                    # LLM provider wiring via LangChain4j
│   └── src/main/kotlin/
│       ├── llm/LangChain4jProvider.kt
│       └── llm/LangChain4jEmbeddingProvider.kt
│
├── tools/                        # Concrete tool implementations
│   └── src/main/kotlin/
│       ├── filesystem/FileSystemTool.kt
│       ├── shell/ShellTool.kt
│       ├── web/WebBrowserTool.kt
│       └── email/EmailTool.kt
│
├── memory/                       # Conversation history + long-term memory
│   └── src/main/kotlin/
│       └── SqliteMemoryStore.kt
│
└── app/                          # Entry point, wires everything together
    └── src/main/kotlin/
        └── Main.kt
```

---

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `langchain4j` | Model-agnostic LLM + embedding client + ReAct agent loop + tool calling |
| `kotlin-telegram-bot` | Telegram Bot API integration |
| `jetbrains/exposed` | SQLite ORM for memory persistence |
| `kotlinx.coroutines` | Async throughout |
| `kaml` | YAML config parsing |
| `sqlite-jdbc` | FTS5 full-text search + vector blob storage |

---

## Data Flow

1. Telegram message arrives
2. `TelegramAdapter` normalizes → `Message(sender, text, attachments, channelMeta)`
3. `Gateway` routes to `AgentEngine`, creates/resumes `Session`
4. `ContextAssembler` builds prompt: system prompt + known facts + semantically relevant past context (hybrid search) + last N messages + user message
5. **ReAct Loop:**
   - LLM reasons → produces `Thought + Action` (tool call) or `FinalAnswer`
   - If tool call → `ToolRegistry` dispatches to correct tool
   - Tool returns `Observation`
   - Observation appended to context → loop repeats
   - If `FinalAnswer` → send back via `TelegramAdapter`
6. Memory updated (new messages + extracted facts)

---

## Tools

| Tool | Capabilities |
|---|---|
| `FileSystemTool` | Read, write, list, delete files and directories |
| `ShellTool` | Run shell commands and scripts, capture output (with timeout + output cap) |
| `WebBrowserTool` | Search web (DuckDuckGo/SerpAPI), fetch and scrape URLs |
| `EmailTool` | Read inbox via IMAP, send email via SMTP |

---

## Memory System

Three-tier hybrid RAG (OpenClaw-inspired):

- **Short-term**: sliding window of last N messages per session (configurable via `window-size`)
- **Long-term search**: message text is chunked (512 chars, 80-char overlap) and stored in a `chunks` table. Retrieval uses a hybrid score:
  - **FTS5 BM25** keyword score (always active)
  - **Vector cosine similarity** against a query embedding (when `embedding:` config is present)
  - Hybrid weight: `0.3 × vector + 0.7 × BM25`
  - **Temporal decay**: score halves every 30 days (`exp(-ln2 × ageDays / 30)`)
  - Top-K results injected into the system prompt under "Relevant past context:"
- **Durable facts**: bullet-point file at `~/.assistant/memory/MEMORY.md`, written by `saveFact()` and read at every turn. Daily conversation log written to `~/.assistant/memory/YYYY-MM-DD.md`.

Embedding is optional. Without it, retrieval is FTS5-only (no new infrastructure required). Enable by adding an `embedding:` block to `application.yml`:

```yaml
embedding:
  provider: openai          # openai | ollama
  model: text-embedding-3-small
  api-key: "YOUR_KEY"
# Or local:
# embedding:
#   provider: ollama
#   model: nomic-embed-text
#   base-url: http://localhost:11434
```

---

## Error Handling

- Tool failures return structured `Observation(error=...)` back into ReAct loop — LLM decides to retry, pivot, or report
- `ShellTool` has configurable timeout + output size cap
- Telegram adapter retries transient failures with exponential backoff
- All tool calls logged to `~/.assistant/audit.log`

---

## Configuration

```yaml
telegram:
  token: "YOUR_BOT_TOKEN"

llm:
  provider: openai          # openai | anthropic | ollama
  model: gpt-4o
  api-key: "YOUR_API_KEY"

memory:
  db-path: ~/.assistant/memory.db
  window-size: 20
  search-limit: 5              # max relevant chunks injected per turn

# embedding:                   # optional — enables vector search
#   provider: openai           # openai | ollama
#   model: text-embedding-3-small
#   api-key: "YOUR_KEY"

tools:
  shell:
    timeout-seconds: 30
    max-output-chars: 10000
  web:
    max-content-chars: 8000
```

---

## Deployment

Single fat JAR, runs as a background process:

```bash
# Build
./gradlew :app:shadowJar

# Run
java -jar app/build/libs/assistant.jar

# Auto-start on macOS login via launchd
~/.assistant/com.assistant.plist
```

No Docker. No cloud. Just a fat JAR + SQLite file in `~/.assistant/`.

---

## Success Criteria

- Send a Telegram message and get a response within 5 seconds
- Agent correctly routes tool calls (e.g. "list my Downloads folder" → FileSystemTool)
- Switch LLM provider by changing one line in `application.yml`, no code changes
- Conversation context persists across restarts
- All tool actions visible in audit log
