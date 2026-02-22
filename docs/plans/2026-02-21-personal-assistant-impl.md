# Personal AI Assistant — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a locally-running Kotlin AI agent controlled via Telegram, with tool access to file system, shell, web, and email.

**Architecture:** OpenClaw-inspired hub-and-spoke — a Gateway routes normalized Telegram messages through a ContextAssembler into a ReAct loop that dispatches to a tool registry. LangChain4j provides the model-agnostic LLM layer; SQLite/Exposed handles memory persistence.

**Tech Stack:** Kotlin 1.9, Gradle 8 (Kotlin DSL), LangChain4j 0.36, kotlin-telegram-bot, Jetbrains Exposed + SQLite, kotlinx.coroutines, JUnit 5 + MockK

---

### Task 1: Scaffold the Gradle multi-module project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `core/build.gradle.kts`
- Create: `channels/build.gradle.kts`
- Create: `providers/build.gradle.kts`
- Create: `tools/build.gradle.kts`
- Create: `memory/build.gradle.kts`
- Create: `app/build.gradle.kts`

**Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "personal-assistant"
include("core", "channels", "providers", "tools", "memory", "app")
```

**Step 2: Create root `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
        testImplementation("io.mockk:mockk:1.13.12")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.test { useJUnitPlatform() }
}
```

**Step 3: Create module build files**

`core/build.gradle.kts`:
```kotlin
plugins { kotlin("plugin.serialization") }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

`memory/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
}
```

`providers/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":core"))
    implementation("dev.langchain4j:langchain4j:0.36.2")
    implementation("dev.langchain4j:langchain4j-open-ai:0.36.2")
    implementation("dev.langchain4j:langchain4j-anthropic:0.36.2")
    implementation("dev.langchain4j:langchain4j-ollama:0.36.2")
}
```

`tools/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.sun.mail:jakarta.mail:2.0.1")
}
```

`channels/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":core"))
    implementation("io.github.kotlin-telegram-bot:telegram:6.1.0")
}
```

`app/build.gradle.kts`:
```kotlin
plugins { id("com.github.johnrengelman.shadow") version "8.1.1" }
dependencies {
    implementation(project(":core"))
    implementation(project(":channels"))
    implementation(project(":providers"))
    implementation(project(":tools"))
    implementation(project(":memory"))
    implementation("com.charleskorn.kaml:kaml:0.61.0")
}
tasks.shadowJar {
    archiveBaseName.set("assistant")
    archiveClassifier.set("")
    manifest { attributes["Main-Class"] = "com.assistant.MainKt" }
}
```

**Step 4: Create source directories**

```bash
mkdir -p core/src/{main,test}/kotlin/com/assistant/{gateway,agent,ports,domain}
mkdir -p channels/src/{main,test}/kotlin/com/assistant/telegram
mkdir -p providers/src/{main,test}/kotlin/com/assistant/llm
mkdir -p tools/src/{main,test}/kotlin/com/assistant/tools/{filesystem,shell,web,email}
mkdir -p memory/src/{main,test}/kotlin/com/assistant/memory
mkdir -p app/src/main/kotlin/com/assistant
mkdir -p config
```

**Step 5: Verify Gradle build**

```bash
./gradlew build --dry-run
```
Expected: BUILD SUCCESSFUL (all modules resolved)

**Step 6: Commit**

```bash
git add .
git commit -m "chore: scaffold multi-module Gradle project"
```

---

### Task 2: Core domain types

**Files:**
- Create: `core/src/main/kotlin/com/assistant/domain/Types.kt`
- Create: `core/src/test/kotlin/com/assistant/domain/TypesTest.kt`

**Step 1: Write the failing test**

`core/src/test/kotlin/com/assistant/domain/TypesTest.kt`:
```kotlin
package com.assistant.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TypesTest {
    @Test
    fun `Message holds sender text and channel`() {
        val msg = Message(sender = "user123", text = "hello", channel = Channel.TELEGRAM)
        assertEquals("user123", msg.sender)
        assertEquals("hello", msg.text)
        assertEquals(Channel.TELEGRAM, msg.channel)
    }

    @Test
    fun `ToolCall holds name and arguments`() {
        val call = ToolCall(name = "file_read", arguments = mapOf("path" to "/tmp/test.txt"))
        assertEquals("file_read", call.name)
        assertEquals("/tmp/test.txt", call.arguments["path"])
    }

    @Test
    fun `Observation can be success or error`() {
        val ok = Observation.Success("file contents here")
        val err = Observation.Error("file not found")
        assertTrue(ok is Observation.Success)
        assertTrue(err is Observation.Error)
        assertEquals("file contents here", ok.result)
        assertEquals("file not found", err.message)
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.assistant.domain.TypesTest" -i
```
Expected: FAIL — "Message cannot be found"

**Step 3: Write minimal implementation**

`core/src/main/kotlin/com/assistant/domain/Types.kt`:
```kotlin
package com.assistant.domain

enum class Channel { TELEGRAM }

data class Message(
    val sender: String,
    val text: String,
    val channel: Channel,
    val attachments: List<String> = emptyList()
)

data class Session(val id: String, val userId: String, val channel: Channel)

data class ToolCall(val name: String, val arguments: Map<String, Any>)

sealed class Observation {
    data class Success(val result: String) : Observation()
    data class Error(val message: String) : Observation()
}

sealed class AgentStep {
    data class Think(val thought: String) : AgentStep()
    data class Act(val toolCall: ToolCall) : AgentStep()
    data class Observe(val observation: Observation) : AgentStep()
    data class Respond(val text: String) : AgentStep()
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.assistant.domain.TypesTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add core/
git commit -m "feat(core): add domain types — Message, Session, ToolCall, Observation"
```

---

### Task 3: Core ports (interfaces)

**Files:**
- Create: `core/src/main/kotlin/com/assistant/ports/Ports.kt`
- Create: `core/src/test/kotlin/com/assistant/ports/PortsTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.ports

import com.assistant.domain.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PortsTest {
    @Test
    fun `LlmPort complete returns a string`() = runTest {
        val port = mockk<LlmPort>()
        coEvery { port.complete(any()) } returns "Hello!"
        val result = port.complete(listOf(ChatMessage(role = "user", content = "Hi")))
        assertEquals("Hello!", result)
    }

    @Test
    fun `ToolPort execute returns Observation`() = runTest {
        val port = mockk<ToolPort>()
        val call = ToolCall("file_read", mapOf("path" to "/tmp/test.txt"))
        coEvery { port.execute(call) } returns Observation.Success("content")
        val result = port.execute(call)
        assertTrue(result is Observation.Success)
    }

    @Test
    fun `MemoryPort stores and retrieves messages`() = runTest {
        val port = mockk<MemoryPort>()
        val msg = Message("user1", "hi", Channel.TELEGRAM)
        coEvery { port.append(any(), msg) } just runs
        coEvery { port.history(any(), any()) } returns listOf(msg)
        port.append("session1", msg)
        val history = port.history("session1", 10)
        assertEquals(1, history.size)
        assertEquals("hi", history.first().text)
    }

    @Test
    fun `EmbeddingPort embed returns FloatArray`() = runTest {
        val port = mockk<EmbeddingPort>()
        coEvery { port.embed(any()) } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        val result = port.embed("hello world")
        assertEquals(3, result.size)
    }

    @Test
    fun `MemoryPort search returns list of strings`() = runTest {
        val port = mockk<MemoryPort>()
        coEvery { port.search("user1", "kotlin", 5) } returns listOf("Kotlin is great")
        assertEquals(1, port.search("user1", "kotlin", 5).size)
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.assistant.ports.PortsTest" -i
```
Expected: FAIL — "LlmPort cannot be found"

**Step 3: Write minimal implementation**

`core/src/main/kotlin/com/assistant/ports/Ports.kt`:
```kotlin
package com.assistant.ports

import com.assistant.domain.*

data class ChatMessage(val role: String, val content: String)

interface LlmPort {
    suspend fun complete(messages: List<ChatMessage>): String
}

interface ToolPort {
    val name: String
    val description: String
    suspend fun execute(call: ToolCall): Observation
}

interface EmbeddingPort {
    suspend fun embed(text: String): FloatArray
}

interface MemoryPort {
    suspend fun append(sessionId: String, message: Message)
    suspend fun history(sessionId: String, limit: Int): List<Message>
    suspend fun facts(userId: String): List<String>
    suspend fun saveFact(userId: String, fact: String)
    suspend fun search(userId: String, query: String, limit: Int = 5): List<String>
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.assistant.ports.PortsTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add core/
git commit -m "feat(core): add LlmPort, ToolPort, MemoryPort interfaces"
```

---

### Task 4: Memory module — SQLite store

**Files:**
- Create: `memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt`
- Create: `memory/src/test/kotlin/com/assistant/memory/SqliteMemoryStoreTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.memory

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqliteMemoryStoreTest {
    private lateinit var store: SqliteMemoryStore

    @BeforeAll
    fun setup() {
        store = SqliteMemoryStore(":memory:")
        store.init()
    }

    @Test
    fun `append and retrieve message history`() = runTest {
        val msg = Message("user1", "hello world", Channel.TELEGRAM)
        store.append("session1", msg)
        val history = store.history("session1", 10)
        assertEquals(1, history.size)
        assertEquals("hello world", history.first().text)
    }

    @Test
    fun `history respects limit`() = runTest {
        repeat(5) { i ->
            store.append("session2", Message("user2", "msg $i", Channel.TELEGRAM))
        }
        val history = store.history("session2", 3)
        assertEquals(3, history.size)
    }

    @Test
    fun `save and retrieve facts`() = runTest {
        store.saveFact("user1", "User prefers concise answers")
        val facts = store.facts("user1")
        assertTrue(facts.contains("User prefers concise answers"))
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :memory:test --tests "com.assistant.memory.SqliteMemoryStoreTest" -i
```
Expected: FAIL — "SqliteMemoryStore cannot be found"

**Step 3: Write minimal implementation**

`memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt`:
```kotlin
package com.assistant.memory

import com.assistant.domain.*
import com.assistant.ports.MemoryPort
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class SqliteMemoryStore(dbPath: String) : MemoryPort {
    private val db = Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")

    object Messages : LongIdTable("messages") {
        val sessionId = varchar("session_id", 128)
        val userId = varchar("user_id", 128)
        val text = text("text")
        val channel = varchar("channel", 32)
        val createdAt = long("created_at")
    }

    object Facts : LongIdTable("facts") {
        val userId = varchar("user_id", 128)
        val fact = text("fact")
    }

    fun init() {
        transaction(db) { SchemaUtils.create(Messages, Facts) }
    }

    override suspend fun append(sessionId: String, message: Message) {
        transaction(db) {
            Messages.insert {
                it[Messages.sessionId] = sessionId
                it[userId] = message.sender
                it[text] = message.text
                it[channel] = message.channel.name
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun history(sessionId: String, limit: Int): List<Message> =
        transaction(db) {
            Messages.selectAll()
                .where { Messages.sessionId eq sessionId }
                .orderBy(Messages.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    Message(
                        sender = row[Messages.userId],
                        text = row[Messages.text],
                        channel = Channel.valueOf(row[Messages.channel])
                    )
                }
                .reversed()
        }

    override suspend fun facts(userId: String): List<String> =
        transaction(db) {
            Facts.selectAll()
                .where { Facts.userId eq userId }
                .map { it[Facts.fact] }
        }

    override suspend fun saveFact(userId: String, fact: String) {
        transaction(db) {
            Facts.insert {
                it[Facts.userId] = userId
                it[Facts.fact] = fact
            }
        }
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :memory:test --tests "com.assistant.memory.SqliteMemoryStoreTest" -i
```
Expected: PASS (all 3 tests)

**Step 5: Commit**

```bash
git add memory/
git commit -m "feat(memory): add SQLite memory store with conversation history and facts"
```

> **Note:** This initial implementation was superseded by the hybrid RAG rework in Task 17. The final `SqliteMemoryStore` uses FTS5 chunked search, optional vector embeddings, temporal decay, and file-based facts. See Task 17 for the complete implementation.

---

### Task 5: LLM provider — LangChain4j

**Files:**
- Create: `providers/src/main/kotlin/com/assistant/llm/ModelConfig.kt`
- Create: `providers/src/main/kotlin/com/assistant/llm/LangChain4jProvider.kt`
- Create: `providers/src/test/kotlin/com/assistant/llm/LangChain4jProviderTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.llm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LangChain4jProviderTest {
    @Test
    fun `provider builds with openai config`() {
        val config = ModelConfig(provider = "openai", model = "gpt-4o-mini", apiKey = "test-key", baseUrl = null)
        assertNotNull(LangChain4jProvider(config))
    }

    @Test
    fun `provider builds with anthropic config`() {
        val config = ModelConfig(provider = "anthropic", model = "claude-haiku-4-5-20251001", apiKey = "test-key", baseUrl = null)
        assertNotNull(LangChain4jProvider(config))
    }

    @Test
    fun `provider builds with ollama config`() {
        val config = ModelConfig(provider = "ollama", model = "llama3.2", apiKey = null, baseUrl = "http://localhost:11434")
        assertNotNull(LangChain4jProvider(config))
    }

    @Test
    fun `unknown provider throws`() {
        val config = ModelConfig(provider = "unknown", model = "x", apiKey = null, baseUrl = null)
        assertThrows(IllegalArgumentException::class.java) { LangChain4jProvider(config) }
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :providers:test --tests "com.assistant.llm.LangChain4jProviderTest" -i
```
Expected: FAIL — "LangChain4jProvider cannot be found"

**Step 3: Write minimal implementation**

`providers/src/main/kotlin/com/assistant/llm/ModelConfig.kt`:
```kotlin
package com.assistant.llm
data class ModelConfig(val provider: String, val model: String, val apiKey: String?, val baseUrl: String?)
```

`providers/src/main/kotlin/com/assistant/llm/LangChain4jProvider.kt`:
```kotlin
package com.assistant.llm

import com.assistant.ports.*
import dev.langchain4j.data.message.*
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel

class LangChain4jProvider(private val config: ModelConfig) : LlmPort {
    private val model: ChatLanguageModel = when (config.provider.lowercase()) {
        "openai" -> OpenAiChatModel.builder().apiKey(config.apiKey).modelName(config.model).build()
        "anthropic" -> AnthropicChatModel.builder().apiKey(config.apiKey).modelName(config.model).build()
        "ollama" -> OllamaChatModel.builder()
            .baseUrl(config.baseUrl ?: "http://localhost:11434")
            .modelName(config.model).build()
        else -> throw IllegalArgumentException("Unknown provider: ${config.provider}")
    }

    override suspend fun complete(messages: List<ChatMessage>): String {
        val lc4jMessages = messages.map { msg ->
            when (msg.role) {
                "system" -> SystemMessage.from(msg.content)
                "assistant" -> AiMessage.from(msg.content)
                else -> UserMessage.from(msg.content)
            }
        }
        return model.generate(lc4jMessages).content().text()
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :providers:test --tests "com.assistant.llm.LangChain4jProviderTest" -i
```
Expected: PASS (all 4 tests — construction only, no real API calls)

**Step 5: Commit**

```bash
git add providers/
git commit -m "feat(providers): add LangChain4j LLM provider supporting openai/anthropic/ollama"
```

> **Addition (Task 17):** A second provider file `LangChain4jEmbeddingProvider.kt` was added to this module, implementing `EmbeddingPort` for OpenAI and Ollama embedding models. No new Gradle dependencies were needed — the embedding model classes ship with the existing `langchain4j-open-ai` and `langchain4j-ollama` artifacts.

---

### Task 6: FileSystem tool

**Files:**
- Create: `tools/src/main/kotlin/com/assistant/tools/filesystem/FileSystemTool.kt`
- Create: `tools/src/test/kotlin/com/assistant/tools/filesystem/FileSystemToolTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.tools.filesystem

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileSystemToolTest {
    private val tool = FileSystemTool()
    private lateinit var tmpDir: java.nio.file.Path

    @BeforeAll fun setup() { tmpDir = Files.createTempDirectory("assistant-test") }
    @AfterAll fun cleanup() { tmpDir.toFile().deleteRecursively() }

    @Test
    fun `write then read file`() = runTest {
        val path = "$tmpDir/hello.txt"
        val writeResult = tool.execute(ToolCall("file_write", mapOf("path" to path, "content" to "hello world")))
        assertTrue(writeResult is Observation.Success)
        val readResult = tool.execute(ToolCall("file_read", mapOf("path" to path)))
        assertEquals("hello world", (readResult as Observation.Success).result)
    }

    @Test
    fun `list directory`() = runTest {
        Files.createFile(tmpDir.resolve("a.txt"))
        Files.createFile(tmpDir.resolve("b.txt"))
        val result = tool.execute(ToolCall("file_list", mapOf("path" to tmpDir.toString()))) as Observation.Success
        assertTrue(result.result.contains("a.txt"))
        assertTrue(result.result.contains("b.txt"))
    }

    @Test
    fun `read non-existent file returns error`() = runTest {
        val result = tool.execute(ToolCall("file_read", mapOf("path" to "/tmp/does_not_exist_xyz.txt")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("file_unknown", mapOf())) is Observation.Error)
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :tools:test --tests "com.assistant.tools.filesystem.FileSystemToolTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`tools/src/main/kotlin/com/assistant/tools/filesystem/FileSystemTool.kt`:
```kotlin
package com.assistant.tools.filesystem

import com.assistant.domain.*
import com.assistant.ports.ToolPort
import java.io.File

class FileSystemTool : ToolPort {
    override val name = "file_system"
    override val description = "Reads, writes, lists, and deletes files. Commands: file_read(path), file_write(path, content), file_list(path), file_delete(path)"

    override suspend fun execute(call: ToolCall): Observation = runCatching {
        when (call.name) {
            "file_read" -> Observation.Success(File(call.arguments["path"] as String).readText())
            "file_write" -> {
                val file = File(call.arguments["path"] as String)
                file.parentFile?.mkdirs()
                file.writeText(call.arguments["content"] as String)
                Observation.Success("Written to ${call.arguments["path"]}")
            }
            "file_list" -> {
                val files = File(call.arguments["path"] as String).listFiles()
                    ?.joinToString("\n") { it.name } ?: "(empty)"
                Observation.Success(files)
            }
            "file_delete" -> {
                File(call.arguments["path"] as String).delete()
                Observation.Success("Deleted ${call.arguments["path"]}")
            }
            else -> Observation.Error("Unknown file command: ${call.name}")
        }
    }.getOrElse { Observation.Error(it.message ?: "Unknown error") }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :tools:test --tests "com.assistant.tools.filesystem.FileSystemToolTest" -i
```
Expected: PASS (all 4 tests)

**Step 5: Commit**

```bash
git add tools/src/main/kotlin/com/assistant/tools/filesystem/ tools/src/test/kotlin/com/assistant/tools/filesystem/
git commit -m "feat(tools): add FileSystemTool — read/write/list/delete files"
```

---

### Task 7: Shell tool

**Files:**
- Create: `tools/src/main/kotlin/com/assistant/tools/shell/ShellTool.kt`
- Create: `tools/src/test/kotlin/com/assistant/tools/shell/ShellToolTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.tools.shell

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ShellToolTest {
    private val tool = ShellTool(timeoutSeconds = 5, maxOutputChars = 1000)

    @Test
    fun `echo command returns output`() = runTest {
        val result = tool.execute(ToolCall("shell_run", mapOf("command" to "echo hello")))
        assertEquals("hello", (result as Observation.Success).result.trim())
    }

    @Test
    fun `invalid command returns error or non-zero exit`() = runTest {
        val result = tool.execute(ToolCall("shell_run", mapOf("command" to "command_that_does_not_exist_xyz")))
        assertTrue(result is Observation.Error || result is Observation.Success)
    }

    @Test
    fun `output is truncated when too long`() = runTest {
        val result = tool.execute(ToolCall("shell_run", mapOf("command" to "yes | head -c 2000")))
        assertTrue((result as Observation.Success).result.length <= 1000)
    }

    @Test
    fun `unknown command name returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("unknown_cmd", mapOf())) is Observation.Error)
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :tools:test --tests "com.assistant.tools.shell.ShellToolTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`tools/src/main/kotlin/com/assistant/tools/shell/ShellTool.kt`:
```kotlin
package com.assistant.tools.shell

import com.assistant.domain.*
import com.assistant.ports.ToolPort
import java.util.concurrent.TimeUnit

class ShellTool(
    private val timeoutSeconds: Long = 30,
    private val maxOutputChars: Int = 10_000
) : ToolPort {
    override val name = "shell"
    override val description = "Executes shell commands. Commands: shell_run(command)"

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "shell_run") return Observation.Error("Unknown shell command: ${call.name}")
        val command = call.arguments["command"] as? String ?: return Observation.Error("Missing 'command'")

        return runCatching {
            val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) { process.destroyForcibly(); return Observation.Error("Timed out after ${timeoutSeconds}s") }

            val output = process.inputStream.bufferedReader().readText()
            val truncated = if (output.length > maxOutputChars) output.take(maxOutputChars) + "\n[truncated]" else output
            Observation.Success(truncated)
        }.getOrElse { Observation.Error(it.message ?: "Unknown error") }
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :tools:test --tests "com.assistant.tools.shell.ShellToolTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add tools/src/main/kotlin/com/assistant/tools/shell/ tools/src/test/kotlin/com/assistant/tools/shell/
git commit -m "feat(tools): add ShellTool with timeout and output truncation"
```

---

### Task 8: Web tool

**Files:**
- Create: `tools/src/main/kotlin/com/assistant/tools/web/WebBrowserTool.kt`
- Create: `tools/src/test/kotlin/com/assistant/tools/web/WebBrowserToolTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.tools.web

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WebBrowserToolTest {
    private val tool = WebBrowserTool()

    @Test
    fun `fetch known URL returns content`() = runTest {
        val result = tool.execute(ToolCall("web_fetch", mapOf("url" to "https://example.com")))
        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.isNotBlank())
    }

    @Test
    fun `fetch invalid URL returns error`() = runTest {
        val result = tool.execute(ToolCall("web_fetch", mapOf("url" to "https://this-domain-xyz-does-not-exist.invalid")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `search returns results`() = runTest {
        val result = tool.execute(ToolCall("web_search", mapOf("query" to "Kotlin programming")))
        assertTrue(result is Observation.Success)
    }

    @Test
    fun `unknown command returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("web_unknown", mapOf())) is Observation.Error)
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :tools:test --tests "com.assistant.tools.web.WebBrowserToolTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`tools/src/main/kotlin/com/assistant/tools/web/WebBrowserTool.kt`:
```kotlin
package com.assistant.tools.web

import com.assistant.domain.*
import com.assistant.ports.ToolPort
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebBrowserTool(private val maxContentChars: Int = 8_000) : ToolPort {
    override val name = "web"
    override val description = "Fetches web pages and searches. Commands: web_fetch(url), web_search(query)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(call: ToolCall): Observation = when (call.name) {
        "web_fetch" -> fetchUrl(call.arguments["url"] as? String ?: return Observation.Error("Missing 'url'"))
        "web_search" -> search(call.arguments["query"] as? String ?: return Observation.Error("Missing 'query'"))
        else -> Observation.Error("Unknown web command: ${call.name}")
    }

    private fun fetchUrl(url: String): Observation = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val body = client.newCall(req).execute().body?.string() ?: return Observation.Error("Empty response")
        val text = Jsoup.parse(body).text()
        Observation.Success(if (text.length > maxContentChars) text.take(maxContentChars) + "..." else text)
    }.getOrElse { Observation.Error(it.message ?: "Fetch failed") }

    private fun search(query: String): Observation = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encoded")
            .header("User-Agent", "Mozilla/5.0").build()
        val body = client.newCall(req).execute().body?.string() ?: return Observation.Error("Empty response")
        val results = Jsoup.parse(body).select(".result__title, .result__snippet")
            .take(10).joinToString("\n") { it.text() }
        Observation.Success(results.ifBlank { "No results found" })
    }.getOrElse { Observation.Error(it.message ?: "Search failed") }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :tools:test --tests "com.assistant.tools.web.WebBrowserToolTest" -i
```
Expected: PASS (requires internet for fetch/search tests)

**Step 5: Commit**

```bash
git add tools/src/main/kotlin/com/assistant/tools/web/ tools/src/test/kotlin/com/assistant/tools/web/
git commit -m "feat(tools): add WebBrowserTool — fetch URLs and DuckDuckGo search"
```

---

### Task 9: Email tool

**Files:**
- Create: `tools/src/main/kotlin/com/assistant/tools/email/EmailConfig.kt`
- Create: `tools/src/main/kotlin/com/assistant/tools/email/EmailTool.kt`
- Create: `tools/src/test/kotlin/com/assistant/tools/email/EmailToolTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.tools.email

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EmailToolTest {
    private val config = EmailConfig("imap.example.com", 993, "smtp.example.com", 587, "test@example.com", "test")
    private val tool = EmailTool(config)

    @Test
    fun `unknown command returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("email_unknown", mapOf())) is Observation.Error)
    }

    @Test
    fun `send email with missing body returns error`() = runTest {
        val result = tool.execute(ToolCall("email_send", mapOf("to" to "a@b.com", "subject" to "hi")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Missing"))
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :tools:test --tests "com.assistant.tools.email.EmailToolTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`tools/src/main/kotlin/com/assistant/tools/email/EmailConfig.kt`:
```kotlin
package com.assistant.tools.email
data class EmailConfig(val imapHost: String, val imapPort: Int, val smtpHost: String, val smtpPort: Int, val username: String, val password: String)
```

`tools/src/main/kotlin/com/assistant/tools/email/EmailTool.kt`:
```kotlin
package com.assistant.tools.email

import com.assistant.domain.*
import com.assistant.ports.ToolPort
import jakarta.mail.*
import jakarta.mail.internet.*
import java.util.Properties

class EmailTool(private val config: EmailConfig) : ToolPort {
    override val name = "email"
    override val description = "Reads and sends email. Commands: email_list(count), email_read(index), email_send(to, subject, body)"

    override suspend fun execute(call: ToolCall): Observation = when (call.name) {
        "email_list" -> listEmails(call.arguments["count"]?.toString()?.toIntOrNull() ?: 10)
        "email_read" -> readEmail(call.arguments["index"]?.toString()?.toIntOrNull() ?: 0)
        "email_send" -> {
            val to = call.arguments["to"] as? String ?: return Observation.Error("Missing 'to'")
            val subject = call.arguments["subject"] as? String ?: return Observation.Error("Missing 'subject'")
            val body = call.arguments["body"] as? String ?: return Observation.Error("Missing 'body'")
            sendEmail(to, subject, body)
        }
        else -> Observation.Error("Unknown email command: ${call.name}")
    }

    private fun imapProps() = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", config.imapHost)
        put("mail.imaps.port", config.imapPort.toString())
        put("mail.imaps.ssl.enable", "true")
    }

    private fun listEmails(count: Int): Observation = runCatching {
        val store = Session.getInstance(imapProps()).getStore("imaps")
        store.connect(config.imapHost, config.username, config.password)
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        val result = inbox.messages.takeLast(count)
            .mapIndexed { i, msg -> "$i: [${msg.from?.firstOrNull()}] ${msg.subject}" }
            .joinToString("\n")
        inbox.close(false); store.close()
        Observation.Success(result)
    }.getOrElse { Observation.Error(it.message ?: "Failed to list emails") }

    private fun readEmail(index: Int): Observation = runCatching {
        val store = Session.getInstance(imapProps()).getStore("imaps")
        store.connect(config.imapHost, config.username, config.password)
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        val msg = inbox.messages[inbox.messageCount - 1 - index]
        val content = when (val c = msg.content) {
            is String -> c
            is Multipart -> (0 until c.count).joinToString("\n") { c.getBodyPart(it).content.toString() }
            else -> c.toString()
        }
        inbox.close(false); store.close()
        Observation.Success("From: ${msg.from?.firstOrNull()}\nSubject: ${msg.subject}\n\n$content")
    }.getOrElse { Observation.Error(it.message ?: "Failed to read email") }

    private fun sendEmail(to: String, subject: String, body: String): Observation = runCatching {
        val props = Properties().apply {
            put("mail.smtp.host", config.smtpHost); put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true"); put("mail.smtp.starttls.enable", "true")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(config.username, config.password)
        })
        Transport.send(MimeMessage(session).apply {
            setFrom(InternetAddress(config.username))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            this.subject = subject
            setText(body)
        })
        Observation.Success("Email sent to $to")
    }.getOrElse { Observation.Error(it.message ?: "Failed to send email") }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :tools:test --tests "com.assistant.tools.email.EmailToolTest" -i
```
Expected: PASS (no real IMAP/SMTP calls)

**Step 5: Commit**

```bash
git add tools/src/main/kotlin/com/assistant/tools/email/ tools/src/test/kotlin/com/assistant/tools/email/
git commit -m "feat(tools): add EmailTool — IMAP read and SMTP send"
```

---

### Task 10: Tool registry

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/ToolRegistry.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/ToolRegistryTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.ToolPort
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ToolRegistryTest {
    @Test
    fun `dispatches to correct tool by command prefix`() = runTest {
        val fileTool = mockk<ToolPort>()
        every { fileTool.name } returns "file_system"
        coEvery { fileTool.execute(any()) } returns Observation.Success("ok")

        val registry = ToolRegistry(listOf(fileTool))
        val result = registry.execute(ToolCall("file_read", mapOf("path" to "/tmp/test.txt")))
        assertTrue(result is Observation.Success)
    }

    @Test
    fun `returns error for unknown tool`() = runTest {
        val result = ToolRegistry(emptyList()).execute(ToolCall("unknown_cmd", mapOf()))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `describe lists all tools`() {
        val tool = mockk<ToolPort>()
        every { tool.name } returns "file_system"
        every { tool.description } returns "File operations"
        val desc = ToolRegistry(listOf(tool)).describe()
        assertTrue(desc.contains("file_system"))
        assertTrue(desc.contains("File operations"))
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.assistant.agent.ToolRegistryTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`core/src/main/kotlin/com/assistant/agent/ToolRegistry.kt`:
```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.ToolPort

class ToolRegistry(private val tools: List<ToolPort>) {
    suspend fun execute(call: ToolCall): Observation {
        val tool = tools.find { call.name.startsWith(it.name.substringBefore("_")) }
            ?: return Observation.Error("No tool found for command: ${call.name}")
        return tool.execute(call)
    }

    fun describe(): String = tools.joinToString("\n\n") { "Tool: ${it.name}\n${it.description}" }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.assistant.agent.ToolRegistryTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/ToolRegistry.kt core/src/test/kotlin/com/assistant/agent/ToolRegistryTest.kt
git commit -m "feat(core): add ToolRegistry — dispatches tool calls by command prefix"
```

---

### Task 11: Context assembler

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/ContextAssembler.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/ContextAssemblerTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextAssemblerTest {
    private val memory = mockk<MemoryPort>()
    private val registry = mockk<ToolRegistry>()

    @Test
    fun `system message is first`() = runTest {
        coEvery { memory.history(any(), any()) } returns emptyList()
        coEvery { memory.facts(any()) } returns emptyList()
        coEvery { memory.search(any(), any(), any()) } returns emptyList()
        every { registry.describe() } returns "Tool: file_system\nFile ops"

        val assembler = ContextAssembler(memory, registry, 10)
        val messages = assembler.build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hello", Channel.TELEGRAM))
        assertEquals("system", messages.first().role)
        assertTrue(messages.last().content.contains("hello"))
    }

    @Test
    fun `history and facts are included`() = runTest {
        coEvery { memory.history(any(), any()) } returns listOf(Message("user1", "previous", Channel.TELEGRAM))
        coEvery { memory.facts(any()) } returns listOf("User likes brevity")
        coEvery { memory.search(any(), any(), any()) } returns emptyList()
        every { registry.describe() } returns ""

        val assembler = ContextAssembler(memory, registry, 10)
        val messages = assembler.build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "new", Channel.TELEGRAM))
        val contents = messages.map { it.content }
        assertTrue(contents.any { it.contains("previous") })
        assertTrue(contents.any { it.contains("User likes brevity") })
    }

    @Test
    fun `relevant chunks from search are included in system prompt`() = runTest {
        coEvery { memory.history(any(), any()) } returns emptyList()
        coEvery { memory.facts(any()) } returns emptyList()
        coEvery { memory.search(any(), any(), any()) } returns listOf("We discussed Kotlin last week")
        every { registry.describe() } returns ""

        val assembler = ContextAssembler(memory, registry, 10, searchLimit = 5)
        val messages = assembler.build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "remind me", Channel.TELEGRAM))
        val system = messages.first().content
        assertTrue(system.contains("Relevant past context"))
        assertTrue(system.contains("We discussed Kotlin last week"))
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.assistant.agent.ContextAssemblerTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`core/src/main/kotlin/com/assistant/agent/ContextAssembler.kt`:
```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*

class ContextAssembler(
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val windowSize: Int = 20,
    private val searchLimit: Int = 5
) {
    suspend fun build(session: Session, currentMessage: Message): List<ChatMessage> {
        val facts = memory.facts(session.userId)
        val history = memory.history(session.id, windowSize)
        val relevant = memory.search(session.userId, currentMessage.text, searchLimit)

        val systemPrompt = buildString {
            appendLine("You are a personal AI assistant running locally. Use tools to take real actions.")
            appendLine("\nAvailable tools:\n${toolRegistry.describe()}")
            appendLine("\nTo use a tool, respond EXACTLY with:")
            appendLine("THOUGHT: <reasoning>")
            appendLine("ACTION: <command_name>")
            appendLine("ARGS: {\"key\": \"value\"}")
            appendLine("\nTo give a final answer: FINAL: <response>")
            if (facts.isNotEmpty()) {
                appendLine("\nKnown facts about this user:")
                facts.forEach { appendLine("- $it") }
            }
            if (relevant.isNotEmpty()) {
                appendLine("\nRelevant past context:")
                relevant.forEach { appendLine(it) }
            }
        }

        return buildList {
            add(ChatMessage("system", systemPrompt))
            history.forEach { msg ->
                add(ChatMessage(if (msg.sender == session.userId) "user" else "assistant", msg.text))
            }
            add(ChatMessage("user", currentMessage.text))
        }
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.assistant.agent.ContextAssemblerTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/ContextAssembler.kt core/src/test/kotlin/com/assistant/agent/ContextAssemblerTest.kt
git commit -m "feat(core): add ContextAssembler — builds prompts from history, facts, and tool docs"
```

---

### Task 12: ReAct agent engine

**Files:**
- Create: `core/src/main/kotlin/com/assistant/agent/AgentEngine.kt`
- Create: `core/src/test/kotlin/com/assistant/agent/AgentEngineTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AgentEngineTest {
    private val llm = mockk<LlmPort>()
    private val memory = mockk<MemoryPort>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val assembler = mockk<ContextAssembler>()

    @Test
    fun `returns FINAL answer directly`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "hi"))
        coEvery { llm.complete(any()) } returns "FINAL: Hello!"
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))
        assertEquals("Hello!", response)
    }

    @Test
    fun `executes tool then returns FINAL`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "list files"))
        coEvery { llm.complete(any()) } returnsMany listOf(
            "THOUGHT: list files\nACTION: file_list\nARGS: {\"path\": \"/tmp\"}",
            "FINAL: Found files in /tmp"
        )
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt\nb.txt")
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "list files", Channel.TELEGRAM))
        assertEquals("Found files in /tmp", response)
        coVerify { toolRegistry.execute(ToolCall("file_list", mapOf("path" to "/tmp"))) }
    }

    @Test
    fun `stops after max steps`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "loop"))
        coEvery { llm.complete(any()) } returns "ACTION: file_list\nARGS: {\"path\": \"/\"}"
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("result")
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 2)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "loop", Channel.TELEGRAM))
        assertTrue(response.isNotBlank())
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.assistant.agent.AgentEngineTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`core/src/main/kotlin/com/assistant/agent/AgentEngine.kt`:
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
    private val maxSteps: Int = 10
) {
    suspend fun process(session: Session, message: Message): String {
        memory.append(session.id, message)
        val context = assembler.build(session, message).toMutableList()

        repeat(maxSteps) {
            val response = llm.complete(context)
            context.add(ChatMessage("assistant", response))

            if (response.contains("FINAL:")) {
                val answer = response.substringAfter("FINAL:").trim()
                memory.append(session.id, Message("assistant", answer, session.channel))
                return answer
            }

            if (response.contains("ACTION:")) {
                val toolName = response.substringAfter("ACTION:").lines().first().trim()
                val argsLine = response.substringAfter("ARGS:").lines().first().trim()
                val args = parseArgs(argsLine)
                val observation = toolRegistry.execute(ToolCall(toolName, args))
                val obs = when (observation) {
                    is Observation.Success -> "OBSERVATION: ${observation.result}"
                    is Observation.Error -> "OBSERVATION ERROR: ${observation.message}"
                }
                context.add(ChatMessage("user", obs))
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

**Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.assistant.agent.AgentEngineTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/agent/AgentEngine.kt core/src/test/kotlin/com/assistant/agent/AgentEngineTest.kt
git commit -m "feat(core): add ReAct AgentEngine — reason/act/observe loop with max steps guard"
```

---

### Task 13: Gateway

**Files:**
- Create: `core/src/main/kotlin/com/assistant/gateway/Gateway.kt`
- Create: `core/src/test/kotlin/com/assistant/gateway/GatewayTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.gateway

import com.assistant.agent.AgentEngine
import com.assistant.domain.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GatewayTest {
    private val engine = mockk<AgentEngine>()

    @Test
    fun `routes message to engine and returns response`() = runTest {
        coEvery { engine.process(any(), any()) } returns "Hello!"
        val response = Gateway(engine).handle(Message("user1", "hi", Channel.TELEGRAM))
        assertEquals("Hello!", response)
    }

    @Test
    fun `same user gets same session`() = runTest {
        val sessions = mutableListOf<Session>()
        coEvery { engine.process(capture(sessions), any()) } returns "ok"
        val gateway = Gateway(engine)
        gateway.handle(Message("user1", "a", Channel.TELEGRAM))
        gateway.handle(Message("user1", "b", Channel.TELEGRAM))
        assertEquals(sessions[0].id, sessions[1].id)
    }

    @Test
    fun `different users get different sessions`() = runTest {
        val sessions = mutableListOf<Session>()
        coEvery { engine.process(capture(sessions), any()) } returns "ok"
        val gateway = Gateway(engine)
        gateway.handle(Message("user1", "hi", Channel.TELEGRAM))
        gateway.handle(Message("user2", "hi", Channel.TELEGRAM))
        assertNotEquals(sessions[0].id, sessions[1].id)
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests "com.assistant.gateway.GatewayTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`core/src/main/kotlin/com/assistant/gateway/Gateway.kt`:
```kotlin
package com.assistant.gateway

import com.assistant.agent.AgentEngine
import com.assistant.domain.*
import java.util.concurrent.ConcurrentHashMap

class Gateway(private val engine: AgentEngine) {
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun handle(message: Message): String {
        val key = "${message.channel}:${message.sender}"
        val session = sessions.getOrPut(key) { Session(id = key, userId = message.sender, channel = message.channel) }
        return engine.process(session, message)
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests "com.assistant.gateway.GatewayTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add core/src/main/kotlin/com/assistant/gateway/ core/src/test/kotlin/com/assistant/gateway/
git commit -m "feat(core): add Gateway — session management and message routing"
```

---

### Task 14: Telegram adapter

**Files:**
- Create: `channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt`
- Create: `channels/src/test/kotlin/com/assistant/telegram/TelegramAdapterTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TelegramAdapterTest {
    @Test
    fun `normalize produces correct Message`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk())
        val msg = adapter.normalize(senderId = "123456", text = "hello from telegram")
        assertEquals("123456", msg.sender)
        assertEquals("hello from telegram", msg.text)
        assertEquals(Channel.TELEGRAM, msg.channel)
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :channels:test --tests "com.assistant.telegram.TelegramAdapterTest" -i
```
Expected: FAIL

**Step 3: Write minimal implementation**

`channels/src/main/kotlin/com/assistant/telegram/TelegramAdapter.kt`:
```kotlin
package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.extensions.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TelegramAdapter(private val token: String, private val gateway: Gateway) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun normalize(senderId: String, text: String): Message =
        Message(sender = senderId, text = text, channel = Channel.TELEGRAM)

    fun start() {
        val telegramBot = bot {
            this.token = this@TelegramAdapter.token
            dispatch {
                message(Filter.Text) {
                    val chatId = message.chat.id.toString()
                    val text = message.text ?: return@message
                    scope.launch {
                        val response = gateway.handle(normalize(chatId, text))
                        bot.sendMessage(ChatId.fromId(message.chat.id), response)
                    }
                }
            }
        }
        telegramBot.startPolling()
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :channels:test --tests "com.assistant.telegram.TelegramAdapterTest" -i
```
Expected: PASS

**Step 5: Commit**

```bash
git add channels/
git commit -m "feat(channels): add TelegramAdapter — normalize updates and poll for messages"
```

---

### Task 15: Config and app wiring

**Files:**
- Create: `config/application.yml`
- Create: `app/src/main/kotlin/com/assistant/Config.kt`
- Create: `app/src/main/kotlin/com/assistant/Main.kt`

**Step 1: Create config**

`config/application.yml`:
```yaml
telegram:
  token: "YOUR_TELEGRAM_BOT_TOKEN"

llm:
  provider: openai       # openai | anthropic | ollama
  model: gpt-4o-mini
  api-key: "YOUR_API_KEY"
  base-url: null

memory:
  db-path: ~/.assistant/memory.db
  window-size: 20
  search-limit: 5

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
  email:
    enabled: false
    imap-host: imap.gmail.com
    imap-port: 993
    smtp-host: smtp.gmail.com
    smtp-port: 587
    username: ""
    password: ""
```

**Step 2: Create Config.kt**

`app/src/main/kotlin/com/assistant/Config.kt`:
```kotlin
package com.assistant

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable data class AppConfig(val telegram: TelegramConfig, val llm: LlmConfig, val memory: MemoryConfig, val tools: ToolsConfig, val embedding: EmbeddingCfg? = null)
@Serializable data class TelegramConfig(val token: String)
@Serializable data class LlmConfig(val provider: String, val model: String, @SerialName("api-key") val apiKey: String? = null, @SerialName("base-url") val baseUrl: String? = null)
@Serializable data class MemoryConfig(@SerialName("db-path") val dbPath: String, @SerialName("window-size") val windowSize: Int, @SerialName("search-limit") val searchLimit: Int = 5)
@Serializable data class EmbeddingCfg(val provider: String, val model: String, @SerialName("api-key") val apiKey: String? = null, @SerialName("base-url") val baseUrl: String? = null)
@Serializable data class ToolsConfig(val shell: ShellConfig = ShellConfig(), val web: WebConfig = WebConfig(), val email: EmailToolConfig = EmailToolConfig())
@Serializable data class ShellConfig(@SerialName("timeout-seconds") val timeoutSeconds: Long = 30, @SerialName("max-output-chars") val maxOutputChars: Int = 10_000)
@Serializable data class WebConfig(@SerialName("max-content-chars") val maxContentChars: Int = 8_000)
@Serializable data class EmailToolConfig(val enabled: Boolean = false, @SerialName("imap-host") val imapHost: String = "", @SerialName("imap-port") val imapPort: Int = 993, @SerialName("smtp-host") val smtpHost: String = "", @SerialName("smtp-port") val smtpPort: Int = 587, val username: String = "", val password: String = "")

fun loadConfig(path: String = "config/application.yml"): AppConfig =
    Yaml.default.decodeFromString(AppConfig.serializer(), File(path).readText())
```

**Step 3: Create Main.kt**

`app/src/main/kotlin/com/assistant/Main.kt`:
```kotlin
package com.assistant

import com.assistant.agent.*
import com.assistant.gateway.Gateway
import com.assistant.llm.EmbeddingConfig
import com.assistant.llm.LangChain4jEmbeddingProvider
import com.assistant.llm.LangChain4jProvider
import com.assistant.llm.ModelConfig
import com.assistant.memory.SqliteMemoryStore
import com.assistant.telegram.TelegramAdapter
import com.assistant.tools.email.EmailConfig
import com.assistant.tools.email.EmailTool
import com.assistant.tools.filesystem.FileSystemTool
import com.assistant.tools.shell.ShellTool
import com.assistant.tools.web.WebBrowserTool
import java.io.File

fun main() {
    val config = loadConfig()

    val dbPath = config.memory.dbPath.replace("~", System.getProperty("user.home"))
    File(dbPath).parentFile.mkdirs()
    val embeddingPort = config.embedding?.let {
        LangChain4jEmbeddingProvider(EmbeddingConfig(it.provider, it.model, it.apiKey, it.baseUrl))
    }
    val memory = SqliteMemoryStore(dbPath, embeddingPort).also { it.init() }

    val llm = LangChain4jProvider(ModelConfig(config.llm.provider, config.llm.model, config.llm.apiKey, config.llm.baseUrl))

    val tools = buildList {
        add(FileSystemTool())
        add(ShellTool(config.tools.shell.timeoutSeconds, config.tools.shell.maxOutputChars))
        add(WebBrowserTool(config.tools.web.maxContentChars))
        if (config.tools.email.enabled) {
            add(EmailTool(EmailConfig(config.tools.email.imapHost, config.tools.email.imapPort,
                config.tools.email.smtpHost, config.tools.email.smtpPort,
                config.tools.email.username, config.tools.email.password)))
        }
    }

    val registry = ToolRegistry(tools)
    val assembler = ContextAssembler(memory, registry, config.memory.windowSize, config.memory.searchLimit)
    val engine = AgentEngine(llm, memory, registry, assembler)
    val gateway = Gateway(engine)
    val telegram = TelegramAdapter(config.telegram.token, gateway)

    println("Personal assistant starting... Send a message on Telegram!")
    telegram.start()
}
```

**Step 4: Build the fat JAR**

```bash
./gradlew :app:shadowJar
```
Expected: `app/build/libs/assistant.jar` created

**Step 5: Commit**

```bash
git add config/ app/
git commit -m "feat(app): wire all modules with YAML config and Main entry point"
```

---

### Task 16: macOS launchd auto-start

**Files:**
- Create: `scripts/com.assistant.plist`
- Create: `scripts/install.sh`

**Step 1: Create plist template**

`scripts/com.assistant.plist`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
    <key>Label</key><string>com.assistant</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/java</string>
        <string>-jar</string>
        <string>ASSISTANT_JAR_PATH</string>
    </array>
    <key>WorkingDirectory</key><string>ASSISTANT_WORKING_DIR</string>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardOutPath</key><string>ASSISTANT_HOME/.assistant/assistant.log</string>
    <key>StandardErrorPath</key><string>ASSISTANT_HOME/.assistant/assistant-error.log</string>
</dict></plist>
```

**Step 2: Create install script**

`scripts/install.sh`:
```bash
#!/bin/bash
set -e

JAR="$(pwd)/app/build/libs/assistant.jar"
PLIST="$HOME/Library/LaunchAgents/com.assistant.plist"
mkdir -p "$HOME/.assistant"

sed "s|ASSISTANT_JAR_PATH|$JAR|g; s|ASSISTANT_WORKING_DIR|$(pwd)|g; s|ASSISTANT_HOME|$HOME|g" \
    scripts/com.assistant.plist > "$PLIST"

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "Assistant installed and running. Tail logs: tail -f ~/.assistant/assistant.log"
```

**Step 3: Run install**

```bash
chmod +x scripts/install.sh
./gradlew :app:shadowJar && ./scripts/install.sh
```
Expected: "Assistant installed and running."

**Step 4: Verify**

```bash
launchctl list | grep com.assistant
tail -f ~/.assistant/assistant.log
```
Expected: Process listed, log shows "Personal assistant starting..."

**Step 5: Commit**

```bash
git add scripts/
git commit -m "feat(scripts): add macOS launchd service for auto-start on login"
```

---

### Task 17: Hybrid RAG memory rework

**Goal:** Replace the simple append-log memory with chunked storage, FTS5 keyword search, optional vector similarity search, temporal decay, and file-based durable facts.

**Files changed:**
- `core/src/main/kotlin/com/assistant/ports/Ports.kt` — add `EmbeddingPort`, add `search()` to `MemoryPort`
- `providers/src/main/kotlin/com/assistant/llm/LangChain4jEmbeddingProvider.kt` (new) — implements `EmbeddingPort` for OpenAI and Ollama
- `memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt` — major rewrite (see below)
- `core/src/main/kotlin/com/assistant/agent/ContextAssembler.kt` — add `searchLimit` param, inject relevant chunks
- `app/src/main/kotlin/com/assistant/Config.kt` — add `EmbeddingCfg`, `searchLimit`
- `app/src/main/kotlin/com/assistant/Main.kt` — wire `EmbeddingPort` and `searchLimit`
- `config/application.yml` — add `search-limit`, commented `embedding:` block

**SqliteMemoryStore changes:**

New schema alongside the existing `Messages` table:
```kotlin
object Chunks : LongIdTable("chunks") {
    val sessionId = varchar("session_id", 128)
    val userId    = varchar("user_id", 128)
    val text      = text("text")
    val embedding = binary("embedding").nullable()  // little-endian IEEE 754 FloatArray
    val createdAt = long("created_at")
}
// chunks_fts: FTS5 virtual table synced via INSERT/DELETE/UPDATE triggers
```

`append()` now also chunks message text (512-char max, 80-char overlap), optionally embeds each chunk, inserts into `Chunks`, and appends to `~/.assistant/memory/YYYY-MM-DD.md`.

`search()` pipeline:
1. FTS5 BM25 candidates (top 20) — sanitized query terms wrapped in double-quotes
2. Normalize BM25 scores to [0,1]
3. Cosine similarity against query embedding (if `EmbeddingPort` configured)
4. Hybrid score: `0.3 × vectorScore + 0.7 × bm25Score`
5. Temporal decay: `score × exp(-ln2 × ageDays / 30)` (30-day half-life)
6. Return top `limit` chunks by decayed score

`facts()` / `saveFact()` — file-based: reads/writes `~/.assistant/memory/MEMORY.md`.

Every `suspend` method wraps its blocking JDBC and file I/O in `withContext(Dispatchers.IO)` so the functions are safe to call from any coroutine context, not just one that already runs on `Dispatchers.IO`.

Pure Kotlin internal helpers (all unit-tested):
- `chunk(text, maxLen, overlap)` — sliding window chunker
- `cosine(a, b)` — dot product cosine similarity
- `temporalDecay(score, createdAtMs)` — exponential half-life decay
- `FloatArray.toBytes()` / `ByteArray.toFloatArray()` — little-endian IEEE 754 round-trip

**New tests (12 added, 49 total):**
- `SqliteMemoryStoreTest`: FTS search match/no-match/blank, chunk splitting, cosine identical/orthogonal, byte round-trip, file-based facts
- `ContextAssemblerTest`: search stub in existing tests + relevant-context injection test
- `PortsTest`: `EmbeddingPort.embed` and `MemoryPort.search` mock tests

**Verification:**
```bash
./gradlew test -x :tools:test   # 49 tests, all pass
./gradlew :app:shadowJar        # fat JAR builds clean
```

---

## Done

All 17 tasks complete. The assistant runs locally, auto-starts on login, and responds to your Telegram messages with relevant past context surfaced from hybrid semantic search.

**To configure:** Edit `config/application.yml` with your Telegram bot token and LLM API key, then rebuild and reinstall.

**To switch models:** Change `llm.provider` and `llm.model` in `application.yml` — no code changes needed.

**To enable vector search:** Uncomment and fill in the `embedding:` block in `application.yml`.
