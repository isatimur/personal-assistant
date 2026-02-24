# UX + Memory Upgrade Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add tool execution feedback, response streaming, context compaction, and /status command.

**Architecture:** All changes are additive — no existing interface is removed. Two new methods added to `MemoryPort` (`trimHistory`, `stats`). New `CompactionService` class. `AgentEngine` gains `onProgress` callback param. `TelegramAdapter` gains `streamToChat` helper and two new commands.

**Tech Stack:** Kotlin coroutines, LangChain4j, kotlin-telegram-bot, Exposed/SQLite, MockK + JUnit5 for tests.

---

### Task 1: Add `trimHistory` and `stats` to MemoryPort + SqliteMemoryStore

**Files:**
- Modify: `core/src/main/kotlin/com/assistant/ports/Ports.kt`
- Modify: `memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt`
- Modify: `memory/src/test/kotlin/com/assistant/memory/SqliteMemoryStoreTest.kt`

**Background:** `MemoryPort` currently has 7 methods. We add 2 more:
- `trimHistory(sessionId, deleteCount)` — deletes the N oldest messages for a session (needed by CompactionService)
- `stats(userId)` — returns counts for /status command

---

**Step 1: Write the failing tests**

Open `memory/src/test/kotlin/com/assistant/memory/SqliteMemoryStoreTest.kt` and add these tests at the end of the class:

```kotlin
@Test
fun `trimHistory deletes oldest N messages`() = runTest {
    val store = SqliteMemoryStore(":memory:").also { it.init() }
    val session = "s1"
    repeat(5) { i ->
        store.append(session, Message("user", "msg$i", Channel.TELEGRAM))
    }
    store.trimHistory(session, deleteCount = 3)
    val remaining = store.history(session, limit = 10)
    assertEquals(2, remaining.size)
    assertEquals("msg3", remaining[0].text)
    assertEquals("msg4", remaining[1].text)
}

@Test
fun `trimHistory is a no-op when deleteCount exceeds history size`() = runTest {
    val store = SqliteMemoryStore(":memory:").also { it.init() }
    store.append("s1", Message("user", "only", Channel.TELEGRAM))
    store.trimHistory("s1", deleteCount = 10)
    val remaining = store.history("s1", limit = 10)
    assertEquals(1, remaining.size)
}

@Test
fun `stats returns correct counts`() = runTest {
    val store = SqliteMemoryStore(":memory:").also { it.init() }
    val userId = "user1"
    store.append("s1", Message(userId, "hello world", Channel.TELEGRAM))
    store.saveFact(userId, "likes coffee")
    store.saveFact(userId, "works in tech")
    val stats = store.stats(userId)
    assertEquals(2, stats.factsCount)
    assertTrue(stats.messageCount >= 1)
    assertTrue(stats.chunkCount >= 1)
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew :memory:test --tests "com.assistant.memory.SqliteMemoryStoreTest.trimHistory*" --tests "com.assistant.memory.SqliteMemoryStoreTest.stats*" 2>&1 | tail -20
```

Expected: compilation error `Unresolved reference: trimHistory` and `Unresolved reference: stats`.

**Step 3: Add `MemoryStats` data class and two methods to `MemoryPort`**

In `core/src/main/kotlin/com/assistant/ports/Ports.kt`, add after the existing imports and before the `ChatMessage` class:

```kotlin
data class MemoryStats(val factsCount: Int, val chunkCount: Int, val messageCount: Int)
```

Then add to the `MemoryPort` interface (after `clearHistory`):

```kotlin
suspend fun trimHistory(sessionId: String, deleteCount: Int)
suspend fun stats(userId: String): MemoryStats
```

Full updated `MemoryPort`:

```kotlin
interface MemoryPort {
    suspend fun append(sessionId: String, message: Message)
    suspend fun history(sessionId: String, limit: Int): List<Message>
    suspend fun facts(userId: String): List<String>
    suspend fun saveFact(userId: String, fact: String)
    suspend fun deleteFact(userId: String, fact: String)
    suspend fun search(userId: String, query: String, limit: Int = 5): List<String>
    suspend fun clearHistory(sessionId: String)
    suspend fun trimHistory(sessionId: String, deleteCount: Int)
    suspend fun stats(userId: String): MemoryStats
}
```

**Step 4: Implement `trimHistory` and `stats` in `SqliteMemoryStore`**

In `memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt`, add these two methods after `clearHistory`:

```kotlin
override suspend fun trimHistory(sessionId: String, deleteCount: Int) {
    withContext(Dispatchers.IO) {
        transaction(db) {
            val oldestIds = Messages
                .selectAll()
                .where { Messages.sessionId eq sessionId }
                .orderBy(Messages.createdAt, SortOrder.ASC)
                .limit(deleteCount)
                .map { it[Messages.id].value }
            if (oldestIds.isNotEmpty()) {
                Messages.deleteWhere { Messages.id inList oldestIds }
            }
        }
    }
}

override suspend fun stats(userId: String): MemoryStats {
    val factsCount = facts(userId).size
    return withContext(Dispatchers.IO) {
        transaction(db) {
            val messageCount = Messages.selectAll()
                .where { Messages.userId eq userId }
                .count().toInt()
            val chunkCount = Chunks.selectAll()
                .where { Chunks.userId eq userId }
                .count().toInt()
            MemoryStats(factsCount = factsCount, chunkCount = chunkCount, messageCount = messageCount)
        }
    }
}
```

**Step 5: Run tests to verify they pass**

```bash
./gradlew :memory:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with all memory tests passing.

**Step 6: Commit**

```bash
cd ~/Dev/personal-assistant
git add core/src/main/kotlin/com/assistant/ports/Ports.kt \
        memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt \
        memory/src/test/kotlin/com/assistant/memory/SqliteMemoryStoreTest.kt
git commit -m "feat: add trimHistory and stats to MemoryPort and SqliteMemoryStore"
```

---

### Task 2: CompactionService

New class that triggers before context is built when history is getting long. It takes the oldest messages, asks the LLM to summarize them, saves as facts, then trims.

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/CompactionService.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/CompactionServiceTest.kt`

---

**Step 1: Write the failing tests**

Create `core/src/test/kotlin/com/assistant/agent/CompactionServiceTest.kt`:

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CompactionServiceTest {
    private val llm = mockk<LlmPort>()
    private val memory = mockk<MemoryPort>()

    @Test
    fun `does not compact when history is below threshold`() = runTest {
        val svc = CompactionService(llm, memory, threshold = 15)
        coEvery { memory.history(any(), any()) } returns List(10) {
            Message("user", "msg$it", Channel.TELEGRAM)
        }
        svc.maybeCompact("s1", "user1")
        coVerify(exactly = 0) { llm.complete(any()) }
    }

    @Test
    fun `compacts when history meets threshold`() = runTest {
        val svc = CompactionService(llm, memory, threshold = 15)
        val oldMessages = List(15) { Message("user", "old msg $it", Channel.TELEGRAM) }
        coEvery { memory.history("s1", 100) } returns oldMessages
        coEvery { llm.complete(any()) } returns "- User is a Kotlin developer\n- User is building an AI assistant"
        coEvery { memory.saveFact(any(), any()) } just runs
        coEvery { memory.trimHistory(any(), any()) } just runs

        svc.maybeCompact("s1", "user1")

        coVerify { memory.saveFact("user1", "User is a Kotlin developer") }
        coVerify { memory.saveFact("user1", "User is building an AI assistant") }
        coVerify { memory.trimHistory("s1", 10) }
    }

    @Test
    fun `handles blank or unparseable LLM response gracefully`() = runTest {
        val svc = CompactionService(llm, memory, threshold = 15)
        val oldMessages = List(15) { Message("user", "msg$it", Channel.TELEGRAM) }
        coEvery { memory.history("s1", 100) } returns oldMessages
        coEvery { llm.complete(any()) } returns "   "
        coEvery { memory.trimHistory(any(), any()) } just runs

        svc.maybeCompact("s1", "user1")  // must not throw

        coVerify(exactly = 0) { memory.saveFact(any(), any()) }
        coVerify { memory.trimHistory("s1", 10) }
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew :core:test --tests "com.assistant.agent.CompactionServiceTest" 2>&1 | tail -20
```

Expected: compilation error `Unresolved reference: CompactionService`.

**Step 3: Implement `CompactionService`**

Create `core/src/main/kotlin/com/assistant/agent/CompactionService.kt`:

```kotlin
package com.assistant.agent

import com.assistant.ports.*

class CompactionService(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val threshold: Int = 15,
    private val compactCount: Int = 10
) {
    suspend fun maybeCompact(sessionId: String, userId: String) {
        val history = memory.history(sessionId, 100)
        if (history.size < threshold) return

        val oldest = history.take(compactCount)
        val conversation = oldest.joinToString("\n") { "${it.sender}: ${it.text}" }

        val prompt = listOf(
            ChatMessage("system", "You extract key facts from conversations."),
            ChatMessage("user",
                "Summarize the key facts from this conversation in 3-5 bullet points. " +
                "Each fact on its own line starting with '- '. Be concise.\n\n$conversation"
            )
        )

        val summary = llm.complete(prompt)
        val facts = summary.lines()
            .map { it.trimStart('-', ' ') }
            .filter { it.isNotBlank() }

        facts.forEach { memory.saveFact(userId, it) }
        memory.trimHistory(sessionId, compactCount)
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
./gradlew :core:test --tests "com.assistant.agent.CompactionServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 3 tests passing.

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/CompactionService.kt \
        core/src/test/kotlin/com/assistant/agent/CompactionServiceTest.kt
git commit -m "feat: add CompactionService to summarize and trim old conversation history"
```

---

### Task 3: Wire CompactionService + onProgress into AgentEngine

**Files:**
- Modify: `core/src/main/kotlin/com/assistant/agent/AgentEngine.kt`
- Modify: `core/src/test/kotlin/com/assistant/agent/AgentEngineTest.kt`

---

**Step 1: Write the failing tests**

Add to `core/src/test/kotlin/com/assistant/agent/AgentEngineTest.kt`:

```kotlin
@Test
fun `onProgress callback fires with tool name before execution`() = runTest {
    coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "list files"))
    coEvery { llm.complete(any()) } returnsMany listOf(
        "THOUGHT: need files\nACTION: file_list\nARGS: {\"path\": \"/tmp\"}",
        "FINAL: done"
    )
    coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt")
    coEvery { memory.append(any(), any()) } just runs

    val progressMessages = mutableListOf<String>()
    val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
    engine.process(
        Session("s1", "user1", Channel.TELEGRAM),
        Message("user1", "list files", Channel.TELEGRAM),
        onProgress = { progressMessages.add(it) }
    )
    assertTrue(progressMessages.any { it.contains("file_list") })
}

@Test
fun `compaction is called before context build`() = runTest {
    val compaction = mockk<CompactionService>()
    coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "hi"))
    coEvery { llm.complete(any()) } returns "FINAL: Hello!"
    coEvery { memory.append(any(), any()) } just runs
    coEvery { compaction.maybeCompact(any(), any()) } just runs

    val engine = AgentEngine(llm, memory, toolRegistry, assembler, compactionService = compaction)
    engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

    coVerify { compaction.maybeCompact("s1", "user1") }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew :core:test --tests "com.assistant.agent.AgentEngineTest.onProgress*" --tests "com.assistant.agent.AgentEngineTest.compaction*" 2>&1 | tail -20
```

Expected: compilation errors about unknown params.

**Step 3: Update `AgentEngine`**

Replace the full content of `core/src/main/kotlin/com/assistant/agent/AgentEngine.kt`:

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import kotlinx.serialization.json.*

class AgentEngine(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val assembler: ContextAssembler,
    private val maxSteps: Int = 10,
    private val compactionService: CompactionService? = null
) {
    private val actionRegex = Regex("""ACTION\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val argsRegex   = Regex("""ARGS\s*:\s*([\s\S]+?)(?=\nACTION|\nFINAL|\nTHOUGHT|$)""", RegexOption.IGNORE_CASE)
    private val finalRegex  = Regex("""FINAL\s*(?:ANSWER)?\s*:\s*([\s\S]+)""", RegexOption.IGNORE_CASE)

    suspend fun process(
        session: Session,
        message: Message,
        onProgress: ((String) -> Unit)? = null
    ): String {
        memory.append(session.id, message)
        compactionService?.maybeCompact(session.id, session.userId)
        val context = assembler.build(session, message).toMutableList()

        repeat(maxSteps) {
            val response = llm.complete(context)
            context.add(ChatMessage("assistant", response))

            val finalAnswer = finalRegex.find(response)?.groupValues?.get(1)?.trim()
            if (finalAnswer != null) {
                memory.append(session.id, Message("assistant", finalAnswer, session.channel))
                return finalAnswer
            }

            val toolName = actionRegex.find(response)?.groupValues?.get(1)?.trim()
            if (toolName != null) {
                onProgress?.invoke("🔧 Using $toolName…")
                val argsText = argsRegex.find(response)?.groupValues?.get(1)?.trim() ?: "{}"
                val args = parseArgs(argsText)
                val observation = toolRegistry.execute(ToolCall(toolName, args))
                val obs = when (observation) {
                    is Observation.Success -> "OBSERVATION: ${observation.result}"
                    is Observation.Error -> "OBSERVATION ERROR: ${observation.message}"
                }
                context.add(ChatMessage("user", obs))
            } else {
                // No recognised marker — treat entire response as final answer
                memory.append(session.id, Message("assistant", response, session.channel))
                return response
            }
        }

        return "I reached the maximum reasoning steps. Please try a simpler request."
    }

    private fun parseArgs(json: String): Map<String, Any> = runCatching {
        Json.parseToJsonElement(json).jsonObject.entries.associate { (k, v) ->
            k to (if (v is JsonPrimitive) v.content else v.toString())
        }
    }.getOrElse { emptyMap() }
}
```

**Step 4: Run all core tests**

```bash
./gradlew :core:test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/AgentEngine.kt \
        core/src/test/kotlin/com/assistant/agent/AgentEngineTest.kt
git commit -m "feat: add onProgress callback and CompactionService wiring to AgentEngine"
```

---

### Task 4: TelegramAdapter — streaming delivery, tool feedback, /status

**Files:**
- Modify: `channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt`
- Modify: `channels/src/test/kotlin/com/assistant/telegram/TelegramAdapterTest.kt`

---

**Step 1: Write the failing tests**

Add to `channels/src/test/kotlin/com/assistant/telegram/TelegramAdapterTest.kt`:

```kotlin
@Test
fun `streamToChat splits text into chunks`() {
    val chunks = mutableListOf<String>()
    val adapter = TelegramAdapter("tok", mockk(), mockk())
    val result = adapter.buildStreamChunks("Hello world how are you", chunkSize = 5)
    // Each chunk should be progressively longer
    assertTrue(result.size > 1)
    assertEquals("Hello world how are you", result.last())
    result.zipWithNext { a, b -> assertTrue(b.length > a.length) }
}

@Test
fun `streamToChat returns single element for short text`() {
    val adapter = TelegramAdapter("tok", mockk(), mockk())
    val result = adapter.buildStreamChunks("Hi", chunkSize = 40)
    assertEquals(1, result.size)
    assertEquals("Hi", result[0])
}

@Test
fun `status command sends formatted message`() = runTest {
    val memory = mockk<MemoryPort>()
    val gateway = mockk<Gateway>()
    val bot = mockk<Bot>()
    coEvery { memory.stats(any()) } returns MemoryStats(factsCount = 5, chunkCount = 100, messageCount = 42)
    every { bot.sendMessage(any<ChatId>(), any(), any(), any(), any(), any(), any(), any(), any()) } returns mockk()

    val adapter = TelegramAdapter("tok", gateway, memory, modelName = "gpt-5-nano")
    adapter.handleCommand(bot, 123L, "/status")

    verify { bot.sendMessage(ChatId.fromId(123L), match { it.contains("Facts: 5") && it.contains("Chunks: 100") }) }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew :channels:test --tests "com.assistant.telegram.TelegramAdapterTest.streamToChat*" --tests "com.assistant.telegram.TelegramAdapterTest.status*" 2>&1 | tail -20
```

Expected: compilation errors about `buildStreamChunks` and `modelName` param.

**Step 3: Update `TelegramAdapter`**

Make these changes to `channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt`:

**3a.** Add `modelName: String = ""` and `startTime: Long = System.currentTimeMillis()` to the constructor:

```kotlin
class TelegramAdapter(
    private val token: String,
    private val gateway: Gateway,
    private val memory: MemoryPort,
    private val timeoutMs: Long = 120_000L,
    private val lastChatIdFile: File = File(System.getProperty("user.home"), ".assistant/last-chat-id"),
    private val workspaceDir: File = File(System.getProperty("user.home"), ".assistant"),
    private val modelName: String = "",
    private val startTime: Long = System.currentTimeMillis()
) {
```

**3b.** Add `buildStreamChunks` and `streamToChat` helpers (add before `start()`):

```kotlin
/** Splits text into progressively longer strings for a "live typing" effect. */
internal fun buildStreamChunks(text: String, chunkSize: Int = 40): List<String> {
    if (text.length <= chunkSize) return listOf(text)
    val chunks = mutableListOf<String>()
    var pos = chunkSize
    while (pos < text.length) {
        chunks.add(text.substring(0, pos))
        pos += chunkSize
    }
    chunks.add(text)
    return chunks
}

/** Sends text as a new message then edits it progressively to simulate streaming. */
internal suspend fun streamToChat(bot: Bot, chatId: Long, text: String) {
    val chunks = buildStreamChunks(text)
    if (chunks.size == 1) {
        bot.sendMessage(ChatId.fromId(chatId), text)
        return
    }
    val sent = bot.sendMessage(ChatId.fromId(chatId), chunks[0])
    val messageId = sent.first?.body()?.result?.messageId ?: run {
        bot.sendMessage(ChatId.fromId(chatId), text)
        return
    }
    for (chunk in chunks.drop(1)) {
        delay(80)
        bot.editMessageText(
            chatId = ChatId.fromId(chatId),
            messageId = messageId,
            text = chunk
        )
    }
}
```

**3c.** Add `/status` case to `handleCommand`, after the `/help` block and before `else`:

```kotlin
text == "/status" -> {
    val stats = memory.stats(chatId.toString())
    val uptimeMs = System.currentTimeMillis() - startTime
    val uptimeMin = uptimeMs / 60_000
    val uptimeStr = if (uptimeMin < 60) "${uptimeMin}m" else "${uptimeMin / 60}h ${uptimeMin % 60}m"
    bot.sendMessage(
        ChatId.fromId(chatId),
        "📊 Bot status\n" +
        "Facts: ${stats.factsCount}\n" +
        "Chunks: ${stats.chunkCount}\n" +
        "Messages: ${stats.messageCount}\n" +
        (if (modelName.isNotBlank()) "Model: $modelName\n" else "") +
        "Uptime: $uptimeStr"
    )
}
```

**3d.** Update `/help` text to include `/status`:

Replace the `/help` handler's message string to add at the end:
```
"/status — show memory stats and bot info\n" +
```

**3e.** Update the regular message handler to use `streamToChat` and pass `onProgress`:

In the `start()` function, replace the inner `try` block (inside `semaphore.withPermit`) with:

```kotlin
try {
    semaphore.withPermit {
        try {
            val response = withTimeout(this@TelegramAdapter.timeoutMs) {
                gateway.handle(normalizedMsg) { progressMsg ->
                    scope.launch {
                        bot.sendMessage(ChatId.fromId(chatId), progressMsg)
                    }
                }
            }
            typingJob.cancel()
            streamToChat(bot, chatId, response)
        } catch (e: TimeoutCancellationException) {
            logger.severe("Request timed out for chat $chatId")
            bot.sendMessage(ChatId.fromId(chatId), "Request timed out. Please try again.")
        } catch (e: Exception) {
            logger.severe("handle failed for chat $chatId: ${e.message}")
            bot.sendMessage(ChatId.fromId(chatId), "Something went wrong. Try again.")
        }
    }
} finally {
    typingJob.cancel()
}
```

**Step 4: Update `Gateway` to accept and forward `onProgress`**

`gateway.handle(msg)` currently takes one argument. We need to forward the onProgress callback.

Open `core/src/main/kotlin/com/assistant/gateway/Gateway.kt` and update the `handle` signature:

```kotlin
suspend fun handle(message: Message, onProgress: ((String) -> Unit)? = null): String
```

And inside, pass it to `engine.process`:

```kotlin
return engine.process(session, message, onProgress)
```

**Step 5: Run all tests**

```bash
./gradlew test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`.

**Step 6: Commit**

```bash
git add channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt \
        channels/src/test/kotlin/com/assistant/telegram/TelegramAdapterTest.kt \
        core/src/main/kotlin/com/assistant/gateway/Gateway.kt
git commit -m "feat: add streaming delivery, tool progress feedback, and /status command"
```

---

### Task 5: Wire CompactionService in Main.kt + expose model name

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`

No new tests needed — this is wiring. Verified by running the bot manually.

---

**Step 1: Update `Main.kt`**

In `app/src/main/kotlin/com/assistant/Main.kt`, after constructing `engine`, add:

```kotlin
val compaction = CompactionService(llm, memory, threshold = 15)
val engine = AgentEngine(llm, memory, registry, assembler, compactionService = compaction)
```

(Replace the existing `val engine = AgentEngine(llm, memory, registry, assembler)` line.)

Also update `TelegramAdapter` construction to pass `modelName`:

```kotlin
val telegram = TelegramAdapter(
    config.telegram.token,
    gateway,
    memory,
    config.telegram.timeoutMs,
    modelName = config.llm.model
)
```

Add the missing import at the top:

```kotlin
import com.assistant.agent.CompactionService
```

**Step 2: Build and verify**

```bash
./gradlew :app:shadowJar 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

**Step 3: Run all tests one final time**

```bash
./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 0 failures.

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/assistant/Main.kt
git commit -m "feat: wire CompactionService and model name into Main"
```

---

## Verification

```bash
# Build
./gradlew clean :app:shadowJar

# Restart bot
launchctl unload ~/Library/LaunchAgents/com.assistant.plist
launchctl load ~/Library/LaunchAgents/com.assistant.plist

# Test tool feedback:
# Send "list files in /tmp" → should see "🔧 Using file_list…" message before response

# Test streaming:
# Send a long question → response should appear to type in progressively

# Test /status:
# /status → should show Facts/Chunks/Messages/Model/Uptime

# Test compaction:
# /new → send 16+ messages → check ~/.assistant/memory/MEMORY.md grows with new facts
```
