# UX + Memory Upgrade Design

**Date:** 2026-02-24

## Goal

Four improvements that close the remaining UX and memory gaps vs OpenClaw for single-user scenario.

## Architecture

The existing ReAct loop (`AgentEngine`) and port interfaces remain intact. All changes are additive.

---

## Feature 1 — Tool Execution Feedback

**Problem:** Users see nothing during tool execution (5–30s silence).

**Design:** Add `onProgress: ((String) -> Unit)? = null` parameter to `AgentEngine.process()`. When a tool call is detected, call `onProgress("🔧 Using $toolName…")` before executing. TelegramAdapter passes a lambda that sends a Telegram message.

**Files:**
- `core/src/main/kotlin/com/assistant/agent/AgentEngine.kt` — add `onProgress` param; call before tool execution
- `channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt` — pass lambda to gateway/agent

**Risk:** Low. Zero interface changes.

---

## Feature 2 — Response Streaming (Presentation)

**Problem:** Full response appears after 10–30s silence; no incremental feedback.

**Design:** After `AgentEngine` returns the final answer string, TelegramAdapter delivers it via progressive message edits:

1. Send typing action
2. Send first ~40 chars as initial message
3. Edit message every 80ms appending more chars
4. Final edit = full text

Rate-limit: Telegram allows ~1 editMessage/sec per chat. At 80ms intervals we stay within limit. For short responses (< 80 chars), send directly without streaming.

True token-level streaming (requires `StreamingChatLanguageModel`) deferred to a future phase — it would improve first-token latency but requires refactoring `AgentEngine` to split THOUGHT/ACTION steps from FINAL delivery.

**Files:**
- `channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt` — `streamToChat(bot, chatId, text)` helper

**Risk:** Low. Purely in TelegramAdapter delivery path.

---

## Feature 3 — Context Compaction

**Problem:** When history > windowSize (20), old messages are silently dropped. Important context is lost.

**Design:** New `CompactionService(llm: LlmPort, memory: MemoryPort)`. Called from `AgentEngine.process()` before building context.

```
if history.size >= COMPACTION_THRESHOLD (15):
    take oldest 10 messages
    ask LLM: "Summarize key facts from this conversation in 3-5 bullet points. One fact per line."
    save each bullet as memory.saveFact(userId, bullet)
    delete compacted messages via memory.trimHistory(sessionId, deleteCount=10)
```

New `MemoryPort` method:
```kotlin
suspend fun trimHistory(sessionId: String, deleteCount: Int)
```

`SqliteMemoryStore` implements by deleting the N oldest messages by `created_at`.

**Files:**
- `core/src/main/kotlin/com/assistant/agent/CompactionService.kt` — new
- `core/src/main/kotlin/com/assistant/ports/Ports.kt` — add `trimHistory`
- `memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt` — implement `trimHistory`
- `core/src/main/kotlin/com/assistant/agent/AgentEngine.kt` — inject and call CompactionService

**Risk:** Medium. Adds LLM call per compaction event (~every 20 messages). Compaction is idempotent.

---

## Feature 4 — /status Command

**Problem:** No visibility into bot health or memory state.

**Design:** New `MemoryPort.stats(userId: String): MemoryStats` where:

```kotlin
data class MemoryStats(val factsCount: Int, val chunkCount: Int, val messageCount: Int)
```

`SqliteMemoryStore` runs two DB queries. `/status` in TelegramAdapter formats output:

```
📊 Bot status
Facts: 12
Chunks: 347
Messages: 89
Model: gpt-5-nano-2025-08-07
Heartbeat: enabled (every 1h)
Uptime: 3h 42m
```

Uptime tracked via `startTime = System.currentTimeMillis()` in TelegramAdapter constructor.

**Files:**
- `core/src/main/kotlin/com/assistant/ports/Ports.kt` — add `stats`
- `memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt` — implement `stats`
- `channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt` — `/status` command; `startTime` field
- `app/src/main/kotlin/com/assistant/Config.kt` — expose model name to TelegramAdapter

**Risk:** Low. Read-only queries.

---

## Files Changed Summary

| File | Change |
|---|---|
| `core/.../agent/AgentEngine.kt` | Add `onProgress` param; inject `CompactionService`; call compaction before context build |
| `core/.../agent/CompactionService.kt` | **New** — compaction logic |
| `core/.../ports/Ports.kt` | Add `trimHistory`, `stats` to `MemoryPort` |
| `memory/.../SqliteMemoryStore.kt` | Implement `trimHistory`, `stats` |
| `channels/.../TelegramAdapter.kt` | Tool feedback lambda; `streamToChat()`; `/status` command; `startTime` |
| `app/.../Config.kt` | Expose model name |

---

## Testing

- `AgentEngineTest`: `onProgress` callback fires with tool name; compaction triggered at threshold; no compaction below threshold
- `CompactionServiceTest`: compacts oldest N messages; saves facts; does not compact if below threshold
- `SqliteMemoryStoreTest`: `trimHistory` deletes N oldest; `stats` returns correct counts
- `TelegramAdapterTest`: `/status` sends formatted message; streaming helper splits text correctly
