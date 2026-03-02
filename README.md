# Personal Assistant

A modular, self-hosted AI personal assistant delivered as a Telegram bot. Built on Kotlin coroutines with a clean ports/adapters architecture — swap LLM providers, add tool plugins as plain JARs, and extend with new channels without touching the core.

## Features

- **Multi-provider LLM** — OpenAI, Anthropic, Ollama (switchable via config)
- **Tool plugins** — Shell, Web fetch/search, Filesystem, Email, GitHub, Jira, Linear, HTTP, Knowledge base. Drop a JAR into `~/.assistant/plugins/` to add more.
- **Persistent memory** — SQLite with FTS5 full-text search and optional vector embeddings
- **Self-learning loop** — nightly reflection updates `soul.md`, `skills.md`, and `user.md` from conversation history
- **Multi-agent routing** — multiple named agents, each with its own memory, routed by channel
- **Voice input** — Whisper transcription (OpenAI key required)
- **Reminders & heartbeat** — cron-scheduled proactive messages
- **Hot config reload** — edit `application.yml` or `secrets.yml`; process restarts automatically

## Requirements

- JDK 21+
- A Telegram bot token ([BotFather](https://t.me/BotFather))
- An LLM API key (Anthropic, OpenAI, or a local Ollama instance)

## Quick Start

```bash
# 1. Build the fat JAR
./gradlew shadowJar

# 2. Install Playwright Chromium (required for web_fetch, one-time)
java -cp app/build/libs/assistant.jar com.microsoft.playwright.CLI install chromium

# 3. Create your secrets file
mkdir -p config
cat > config/secrets.yml <<EOF
telegram:
  token: "YOUR_TELEGRAM_BOT_TOKEN"
llm:
  api-key: "YOUR_LLM_API_KEY"
EOF

# 4. Run
java -jar app/build/libs/assistant.jar
```

Configuration is loaded from `config/application.yml` (base) overlaid with `config/secrets.yml` (secrets, not in git). See [`config/application.yml`](config/application.yml) for all options and [`config/secrets.yml.example`](config/secrets.yml.example) for the secrets template.

## Configuration

Key settings in `config/application.yml`:

```yaml
llm:
  provider: anthropic    # openai | anthropic | ollama
  model: claude-sonnet-4-6

tools:
  shell:
    timeout-seconds: 120
  web:
    search-provider: duckduckgo
  email:
    enabled: false       # set to true and fill credentials to enable
  github:
    enabled: false
  linear:
    enabled: false

voice:
  enabled: false         # requires OpenAI key under voice.api-key
```

## Plugin System

Add tools or channels without modifying the core. Build a fat JAR that implements `ToolPort` (or `ChannelPort`) and declare it via `META-INF/services`:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.assistant:core:0.1.0")
}
```

```kotlin
class MyTool : ToolPort {
    override val name = "my_tool"
    override val description = "Does something useful"
    override fun commands() = listOf(
        CommandSpec("run", "Run it", listOf(ParamSpec("input", "string", "Input text")))
    )
    override suspend fun execute(call: ToolCall): Observation {
        val input = call.arguments["input"] as? String ?: return Observation.Error("missing input")
        return Observation.Success("Result: $input")
    }
}
```

Drop the JAR into `~/.assistant/plugins/` and restart. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full guide and examples.

## Module Structure

| Module | Description |
|--------|-------------|
| `app` | Entry point, config loading, wires all modules |
| `core` | `AgentEngine`, ports/interfaces, context assembly, memory compaction |
| `channels` | Telegram adapter (voice, images, commands, reminders) |
| `providers` | LangChain4j-based LLM providers |
| `tools` | Built-in tool implementations |
| `memory` | SQLite-backed memory with FTS5 and optional embeddings |
| `examples/` | Reference plugin implementations |

## Running as a Service

**macOS (launchd)**

```xml
<!-- ~/Library/LaunchAgents/com.assistant.plist -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>        <string>com.assistant</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/java</string>
        <string>-jar</string>
        <string>/path/to/assistant.jar</string>
    </array>
    <key>WorkingDirectory</key> <string>/path/to/personal-assistant</string>
    <key>KeepAlive</key>        <true/>
    <key>RunAtLoad</key>        <true/>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/com.assistant.plist
```

**Linux (systemd)**

```ini
[Unit]
Description=Personal Assistant
After=network.target

[Service]
ExecStart=java -jar /path/to/assistant.jar
WorkingDirectory=/path/to/personal-assistant
Restart=always

[Install]
WantedBy=multi-user.target
```

## License

MIT
