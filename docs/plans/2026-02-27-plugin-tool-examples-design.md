# Plugin Tool Examples — Design

**Date:** 2026-02-27
**Goal:** Add three self-contained example tool plugins inside the repo so developers can see exactly how to build, test, and deploy a plugin tool.

---

## Context

`PluginLoader.loadTools()` already uses `ServiceLoader` + `URLClassLoader` over `~/.assistant/plugins/`. `Main.kt` already calls `addAll(pluginLoader.loadTools())`. The mechanical loading is done. What's missing is a complete, working reference that plugin authors can copy.

---

## Architecture

Three new Gradle subprojects under `examples/`, each independently buildable into a fat JAR via the Shadow plugin.

```
examples/
  weather-tool-plugin/
  calculator-tool-plugin/
  sysinfo-tool-plugin/
```

Each project follows the same shape:
```
build.gradle.kts
src/main/kotlin/com/example/<Name>Tool.kt
src/main/resources/META-INF/services/com.assistant.ports.ToolPort
src/test/kotlin/com/example/<Name>ToolTest.kt
```

### Gradle wiring

Add three `include()` lines to `settings.gradle.kts`. No changes to root `build.gradle.kts`.

Each `build.gradle.kts` uses:
```kotlin
// For a standalone plugin outside this repo, replace with:
// implementation("com.assistant:core:VERSION")
implementation(project(":core"))
```

The `shadow` plugin produces `<name>-all.jar` — a fat JAR with all dependencies bundled.

---

## The Three Tools

### WeatherTool
- **Command:** `weather_current(city: String)`
- **Implementation:** HTTP GET `https://wttr.in/<city>?format=3` via OkHttp, returns plain-text response
- **Dependencies:** `com.squareup.okhttp3:okhttp:4.12.0` (injectable base URL for testing)
- **Test:** MockWebServer intercepts the call; asserts `Observation.Success` containing city name

### CalculatorTool
- **Command:** `calculator_evaluate(expression: String)`
- **Implementation:** Recursive descent parser supporting `+`, `-`, `*`, `/`, parentheses, integer and decimal literals
- **Dependencies:** none beyond `core`
- **Tests:** `2+2=4`, `(3+4)*2=14`, division-by-zero → `Observation.Error`, invalid token → `Observation.Error`

### SysInfoTool
- **Command:** `sysinfo_get()`
- **Implementation:** Reads `Runtime.getRuntime()` and `System.getProperty()` — OS name, arch, JVM version, available processors, total/free heap in MB
- **Dependencies:** none beyond `core`
- **Test:** calls `execute()`, asserts `Observation.Success` contains OS name

---

## META-INF/services

Each plugin declares its implementation in:
```
src/main/resources/META-INF/services/com.assistant.ports.ToolPort
```
Contents (one line): the fully qualified class name, e.g. `com.example.WeatherTool`.

---

## CONTRIBUTING.md Update

New **"Building and Running the Example Plugins"** section:
```bash
./gradlew :examples:weather-tool-plugin:shadowJar
cp examples/weather-tool-plugin/build/libs/weather-tool-plugin-all.jar ~/.assistant/plugins/
# Restart the assistant — weather_current command is now available
```

---

## Key Constraints

- No changes to `core/`, `app/`, `channels/`, `tools/`, `memory/`, `providers/`
- Example projects use `project(":core")` internally with a comment showing standalone usage
- All three plugins are independently buildable and testable via `:examples:<name>:test`
- Weather test uses MockWebServer — no real network calls in tests
