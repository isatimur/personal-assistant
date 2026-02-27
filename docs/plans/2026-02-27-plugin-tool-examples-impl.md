# Plugin Tool Examples — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add three self-contained example tool plugins (`sysinfo`, `calculator`, `weather`) as Gradle subprojects under `examples/` to demonstrate how plugin tools are built, tested, and deployed.

**Architecture:** Each example is a Gradle subproject that inherits Kotlin, JUnit 5, MockK, and coroutines from the root `build.gradle.kts` `subprojects { }` block. Each has exactly one `ToolPort` implementation, one test class, and a `META-INF/services` declaration. A fat JAR is produced via the Shadow plugin.

**Tech Stack:** Kotlin 1.9.25 (inherited), Shadow 8.1.1, OkHttp 4.12.0 (weather only), MockWebServer 4.12.0 (weather test only), JUnit 5 + MockK (inherited from root).

---

## Task 1: Register example subprojects in settings.gradle.kts

**Files:**
- Modify: `settings.gradle.kts`

**Step 1: Add the three includes**

Current content of `settings.gradle.kts`:
```kotlin
rootProject.name = "personal-assistant"
include("core", "channels", "providers", "tools", "memory", "app")
```

Change to:
```kotlin
rootProject.name = "personal-assistant"
include("core", "channels", "providers", "tools", "memory", "app")
include(
    ":examples:sysinfo-tool-plugin",
    ":examples:calculator-tool-plugin",
    ":examples:weather-tool-plugin"
)
```

**Step 2: Verify Gradle can resolve the projects (they don't exist yet — that's fine)**

```bash
./gradlew projects
```

Expected: lists the three new projects (possibly with a warning about missing `build.gradle.kts` files — that's normal).

**Step 3: Commit**

```bash
git add settings.gradle.kts
git commit -m "chore: register example plugin subprojects in settings"
```

---

## Task 2: SysInfoTool plugin

**Files:**
- Create: `examples/sysinfo-tool-plugin/build.gradle.kts`
- Create: `examples/sysinfo-tool-plugin/src/main/kotlin/com/example/SysInfoTool.kt`
- Create: `examples/sysinfo-tool-plugin/src/main/resources/META-INF/services/com.assistant.ports.ToolPort`
- Create: `examples/sysinfo-tool-plugin/src/test/kotlin/com/example/SysInfoToolTest.kt`

### Step 1: Create `build.gradle.kts`

```kotlin
plugins {
    // For a standalone plugin outside this repo, also add:
    // kotlin("jvm") version "1.9.25"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    // For a standalone plugin outside this repo, replace with:
    // implementation("com.assistant:core:VERSION")
    implementation(project(":core"))
}

tasks.shadowJar {
    archiveBaseName.set("sysinfo-tool-plugin")
    archiveClassifier.set("")
    mergeServiceFiles()
}
```

### Step 2: Write the failing test

Create `examples/sysinfo-tool-plugin/src/test/kotlin/com/example/SysInfoToolTest.kt`:

```kotlin
package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SysInfoToolTest {

    private val tool = SysInfoTool()

    @Test
    fun `sysinfo_get returns OS, CPU and memory info`() = runBlocking {
        val result = tool.execute(ToolCall("sysinfo_get", emptyMap()))
        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("OS:"), "Expected OS info, got: $text")
        assertTrue(text.contains("CPUs:"), "Expected CPU info, got: $text")
        assertTrue(text.contains("Memory:"), "Expected memory info, got: $text")
    }

    @Test
    fun `unknown command returns error`() = runBlocking {
        val result = tool.execute(ToolCall("sysinfo_unknown", emptyMap()))
        assertTrue(result is Observation.Error)
    }
}
```

### Step 3: Run test to verify it fails

```bash
./gradlew :examples:sysinfo-tool-plugin:test
```

Expected: FAIL — `SysInfoTool` not found.

### Step 4: Implement SysInfoTool

Create `examples/sysinfo-tool-plugin/src/main/kotlin/com/example/SysInfoTool.kt`:

```kotlin
package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ToolPort

class SysInfoTool : ToolPort {
    override val name = "sysinfo"
    override val description = "Returns OS, CPU, and memory details for the current system"

    override fun commands() = listOf(
        CommandSpec(
            name = "sysinfo_get",
            description = "Get current system information (OS, CPUs, heap memory)",
            params = emptyList()
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "sysinfo_get") return Observation.Error("Unknown command: ${call.name}")
        val rt = Runtime.getRuntime()
        val totalMb = rt.totalMemory() / 1_048_576
        val freeMb = rt.freeMemory() / 1_048_576
        val usedMb = totalMb - freeMb
        return Observation.Success("""
            OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})
            JVM: ${System.getProperty("java.version")}
            CPUs: ${rt.availableProcessors()}
            Memory: ${usedMb}MB used / ${totalMb}MB total
        """.trimIndent())
    }
}
```

### Step 5: Create the services declaration

Create `examples/sysinfo-tool-plugin/src/main/resources/META-INF/services/com.assistant.ports.ToolPort`:

```
com.example.SysInfoTool
```

(One line, no trailing newline issues — just the fully qualified class name.)

### Step 6: Run test to verify it passes

```bash
./gradlew :examples:sysinfo-tool-plugin:test
```

Expected: 2/2 PASS.

### Step 7: Commit

```bash
git add examples/sysinfo-tool-plugin/
git commit -m "feat(examples): add SysInfoTool plugin example"
```

---

## Task 3: CalculatorTool plugin

**Files:**
- Create: `examples/calculator-tool-plugin/build.gradle.kts`
- Create: `examples/calculator-tool-plugin/src/main/kotlin/com/example/CalculatorTool.kt`
- Create: `examples/calculator-tool-plugin/src/main/resources/META-INF/services/com.assistant.ports.ToolPort`
- Create: `examples/calculator-tool-plugin/src/test/kotlin/com/example/CalculatorToolTest.kt`

### Step 1: Create `build.gradle.kts`

```kotlin
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    // For a standalone plugin outside this repo, replace with:
    // implementation("com.assistant:core:VERSION")
    implementation(project(":core"))
}

tasks.shadowJar {
    archiveBaseName.set("calculator-tool-plugin")
    archiveClassifier.set("")
    mergeServiceFiles()
}
```

### Step 2: Write the failing tests

Create `examples/calculator-tool-plugin/src/test/kotlin/com/example/CalculatorToolTest.kt`:

```kotlin
package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CalculatorToolTest {

    private val tool = CalculatorTool()

    @Test
    fun `addition`() = runBlocking {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "2+2")))
        assertEquals(Observation.Success("4"), result)
    }

    @Test
    fun `operator precedence and parentheses`() = runBlocking {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "(3+4)*2")))
        assertEquals(Observation.Success("14"), result)
    }

    @Test
    fun `decimal result`() = runBlocking {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "10/4")))
        assertEquals(Observation.Success("2.5"), result)
    }

    @Test
    fun `division by zero returns error`() = runBlocking {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "5/0")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("zero"))
    }

    @Test
    fun `invalid expression returns error`() = runBlocking {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "abc")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `missing expression param returns error`() = runBlocking {
        val result = tool.execute(ToolCall("calculator_evaluate", emptyMap()))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() = runBlocking {
        val result = tool.execute(ToolCall("calculator_unknown", emptyMap()))
        assertTrue(result is Observation.Error)
    }
}
```

### Step 3: Run tests to verify they fail

```bash
./gradlew :examples:calculator-tool-plugin:test
```

Expected: FAIL — `CalculatorTool` not found.

### Step 4: Implement CalculatorTool

Create `examples/calculator-tool-plugin/src/main/kotlin/com/example/CalculatorTool.kt`:

```kotlin
package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort

class CalculatorTool : ToolPort {
    override val name = "calculator"
    override val description = "Evaluates arithmetic expressions: +, -, *, /, parentheses, decimals"

    override fun commands() = listOf(
        CommandSpec(
            name = "calculator_evaluate",
            description = "Evaluate an arithmetic expression and return the result",
            params = listOf(
                ParamSpec("expression", "string", "Arithmetic expression, e.g. \"(3+4)*2\"")
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "calculator_evaluate") return Observation.Error("Unknown command: ${call.name}")
        val expression = call.arguments["expression"] as? String
            ?: return Observation.Error("Missing required parameter: expression")
        return runCatching {
            val result = Parser(expression.trim()).parse()
            // Strip trailing zeros: 2.50 → 2.5, 4.0 → 4
            val formatted = result.toBigDecimal().stripTrailingZeros().toPlainString()
            Observation.Success(formatted)
        }.getOrElse { Observation.Error(it.message ?: "Evaluation failed") }
    }

    /** Recursive descent parser for: expr := addSub; addSub := mulDiv ((+|-) mulDiv)* etc. */
    private class Parser(private val input: String) {
        private var pos = 0

        fun parse(): Double = parseAddSub().also {
            skipSpaces()
            if (pos < input.length)
                throw IllegalArgumentException("Unexpected character at position $pos: '${input[pos]}'")
        }

        private fun parseAddSub(): Double {
            var left = parseMulDiv()
            while (true) {
                skipSpaces()
                if (pos >= input.length) break
                val op = input[pos]
                if (op != '+' && op != '-') break
                pos++
                val right = parseMulDiv()
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseMulDiv(): Double {
            var left = parseUnary()
            while (true) {
                skipSpaces()
                if (pos >= input.length) break
                val op = input[pos]
                if (op != '*' && op != '/') break
                pos++
                val right = parseUnary()
                if (op == '/' && right == 0.0) throw ArithmeticException("Division by zero")
                left = if (op == '*') left * right else left / right
            }
            return left
        }

        private fun parseUnary(): Double {
            skipSpaces()
            if (pos < input.length && input[pos] == '-') { pos++; return -parsePrimary() }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of expression")
            if (input[pos] == '(') {
                pos++
                val value = parseAddSub()
                skipSpaces()
                if (pos >= input.length || input[pos] != ')')
                    throw IllegalArgumentException("Expected ')' at position $pos")
                pos++
                return value
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            skipSpaces()
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (pos == start)
                throw IllegalArgumentException("Expected number at position $pos")
            return input.substring(start, pos).toDouble()
        }

        private fun skipSpaces() { while (pos < input.length && input[pos] == ' ') pos++ }
    }
}
```

### Step 5: Create the services declaration

Create `examples/calculator-tool-plugin/src/main/resources/META-INF/services/com.assistant.ports.ToolPort`:

```
com.example.CalculatorTool
```

### Step 6: Run tests to verify they pass

```bash
./gradlew :examples:calculator-tool-plugin:test
```

Expected: 7/7 PASS.

### Step 7: Commit

```bash
git add examples/calculator-tool-plugin/
git commit -m "feat(examples): add CalculatorTool plugin example"
```

---

## Task 4: WeatherTool plugin

**Files:**
- Create: `examples/weather-tool-plugin/build.gradle.kts`
- Create: `examples/weather-tool-plugin/src/main/kotlin/com/example/WeatherTool.kt`
- Create: `examples/weather-tool-plugin/src/main/resources/META-INF/services/com.assistant.ports.ToolPort`
- Create: `examples/weather-tool-plugin/src/test/kotlin/com/example/WeatherToolTest.kt`

### Step 1: Create `build.gradle.kts`

```kotlin
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    // For a standalone plugin outside this repo, replace with:
    // implementation("com.assistant:core:VERSION")
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.shadowJar {
    archiveBaseName.set("weather-tool-plugin")
    archiveClassifier.set("")
    mergeServiceFiles()
}
```

### Step 2: Write the failing test

Create `examples/weather-tool-plugin/src/test/kotlin/com/example/WeatherToolTest.kt`:

```kotlin
package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WeatherToolTest {

    @Test
    fun `weather_current returns city weather`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("London: ⛅ +12°C"))
        server.start()
        try {
            val tool = WeatherTool(baseUrl = server.url("/").toString().trimEnd('/'))
            val result = runBlocking {
                tool.execute(ToolCall("weather_current", mapOf("city" to "London")))
            }
            assertTrue(result is Observation.Success)
            assertTrue((result as Observation.Success).result.contains("London"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `missing city param returns error`() {
        val tool = WeatherTool()
        val result = runBlocking {
            tool.execute(ToolCall("weather_current", emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() {
        val tool = WeatherTool()
        val result = runBlocking {
            tool.execute(ToolCall("weather_unknown", emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }
}
```

### Step 3: Run tests to verify they fail

```bash
./gradlew :examples:weather-tool-plugin:test
```

Expected: FAIL — `WeatherTool` not found.

### Step 4: Implement WeatherTool

Create `examples/weather-tool-plugin/src/main/kotlin/com/example/WeatherTool.kt`:

```kotlin
package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WeatherTool(
    private val baseUrl: String = "https://wttr.in",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : ToolPort {
    override val name = "weather"
    override val description = "Fetches current weather conditions for a city (no API key required)"

    override fun commands() = listOf(
        CommandSpec(
            name = "weather_current",
            description = "Get current weather for a city",
            params = listOf(
                ParamSpec("city", "string", "City name, e.g. \"London\" or \"New York\"")
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "weather_current") return Observation.Error("Unknown command: ${call.name}")
        val city = call.arguments["city"] as? String
            ?: return Observation.Error("Missing required parameter: city")
        return runCatching {
            val encoded = URLEncoder.encode(city, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/$encoded?format=3")
                .header("User-Agent", "personal-assistant/1.0")
                .build()
            val body = client.newCall(request).execute().body?.string()
                ?: return@runCatching Observation.Error("Empty response from weather service")
            Observation.Success(body.trim())
        }.getOrElse { Observation.Error(it.message ?: "Weather fetch failed") }
    }
}
```

### Step 5: Create the services declaration

Create `examples/weather-tool-plugin/src/main/resources/META-INF/services/com.assistant.ports.ToolPort`:

```
com.example.WeatherTool
```

### Step 6: Run tests to verify they pass

```bash
./gradlew :examples:weather-tool-plugin:test
```

Expected: 3/3 PASS.

### Step 7: Commit

```bash
git add examples/weather-tool-plugin/
git commit -m "feat(examples): add WeatherTool plugin example"
```

---

## Task 5: Update CONTRIBUTING.md

**Files:**
- Modify: `CONTRIBUTING.md`

### Step 1: Read the current CONTRIBUTING.md

Read `CONTRIBUTING.md` to find the right insertion point (after the existing "Writing a Plugin Tool" section and before "Plugin JAR structure").

### Step 2: Add "Building and Running the Example Plugins" section

Insert after the "Plugin JAR structure" section:

```markdown
## Building and Running the Example Plugins

Three ready-to-run example plugins live in `examples/`. Build and install any of them:

```bash
# Build the fat JAR
./gradlew :examples:sysinfo-tool-plugin:shadowJar
./gradlew :examples:calculator-tool-plugin:shadowJar
./gradlew :examples:weather-tool-plugin:shadowJar

# Install (creates ~/.assistant/plugins/ if needed)
mkdir -p ~/.assistant/plugins
cp examples/sysinfo-tool-plugin/build/libs/sysinfo-tool-plugin.jar ~/.assistant/plugins/
cp examples/calculator-tool-plugin/build/libs/calculator-tool-plugin.jar ~/.assistant/plugins/
cp examples/weather-tool-plugin/build/libs/weather-tool-plugin.jar ~/.assistant/plugins/

# Restart the assistant — new commands are now available:
# sysinfo_get, calculator_evaluate, weather_current
```

To build a standalone plugin outside this repo, change the `core` dependency in your `build.gradle.kts`:
```kotlin
// Replace project reference with the published artifact:
implementation("com.assistant:core:VERSION")
```
```

### Step 3: Run the full test suite to verify nothing is broken

```bash
./gradlew test
```

Expected: ALL PASS (existing 29 tests + 12 new example tests = 41 total).

### Step 4: Commit

```bash
git add CONTRIBUTING.md
git commit -m "docs: add example plugin build/install instructions to CONTRIBUTING"
```

---

## Final Verification

```bash
./gradlew test
./gradlew :examples:sysinfo-tool-plugin:shadowJar
./gradlew :examples:calculator-tool-plugin:shadowJar
./gradlew :examples:weather-tool-plugin:shadowJar
```

Expected: all tests green, three fat JARs produced in their respective `build/libs/` directories.
