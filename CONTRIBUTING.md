# Contributing to Personal Assistant

## One-Time Setup

After cloning, download the Playwright Chromium binary (required for `web_fetch`):

```bash
./gradlew shadowJar
java -cp app/build/libs/assistant.jar com.microsoft.playwright.CLI install chromium
```

This installs Chromium (~130MB) to `~/.cache/ms-playwright/`. Only needed once per machine.

## Writing a Plugin Tool

1. Add `com.assistant:core:LATEST` as a dependency.
   **Note:** `com.assistant:core` is not yet published to Maven Central. For now, build the project locally
   with `./gradlew publishToMavenLocal` and reference the local artifact.
2. Implement `com.assistant.ports.ToolPort`:

```kotlin
class MyTool : ToolPort {
    override val name = "my_tool"
    override val description = "What this tool does"
    override fun commands() = listOf(
        CommandSpec("my_command", "Description", listOf(
            ParamSpec("arg", "string", "Argument description")
        ))
    )
    override suspend fun execute(call: ToolCall): Observation {
        val arg = call.arguments["arg"] as? String ?: return Observation.Error("Missing arg")
        return Observation.Success("Result: $arg")
    }
}
```

3. Declare the implementation in `META-INF/services/com.assistant.ports.ToolPort` (one class per line).
4. Build a fat JAR and drop it into `~/.assistant/plugins/`.

## Writing a Plugin Channel

Implement `com.assistant.ports.ChannelPort`. See `TelegramAdapter` in `channels/` for a reference implementation.

```kotlin
class MyChannel : ChannelPort {
    override val name = "my_channel"

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        // Launch your polling/webhook loop in a background coroutine.
        // Call onMessage for every inbound message and deliver the returned reply to the user.
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val inbound = pollForNextMessage() // your transport-specific receive
                val reply = onMessage(
                    sessionId = inbound.sessionId,
                    userId    = inbound.userId,
                    text      = inbound.text,
                    imageUrl  = inbound.imageUrl
                )
                sendToUser(inbound.sessionId, reply)
            }
        }
    }

    override fun send(sessionId: String, text: String) {
        // send a proactive/outbound message to an existing session
    }

    // Implement these with your actual transport logic:
    private suspend fun pollForNextMessage(): InboundMessage = TODO()
    private fun sendToUser(sessionId: String, text: String): Unit = TODO()

    private data class InboundMessage(val sessionId: String, val userId: String, val text: String, val imageUrl: String?)
}
```

Declare in `META-INF/services/com.assistant.ports.ChannelPort`.

## Example: Slack Plugin Channel (Bolt-Kotlin)

A complete Slack channel plugin using [Bolt-Kotlin](https://github.com/slackapi/java-slack-sdk/tree/main/bolt-kotlin-coroutines):

```kotlin
// slack-channel-plugin/src/main/kotlin/com/example/SlackChannelAdapter.kt
package com.example

import com.assistant.ports.ChannelPort
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp

class SlackChannelAdapter : ChannelPort {
    override val name = "slack"
    private val app = App(AppConfig.builder()
        .singleTeamBotToken(System.getenv("SLACK_BOT_TOKEN"))
        .build())

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        // bolt-kotlin-coroutines handlers are suspend — call onMessage directly, no runBlocking needed
        app.message(".*") { payload, ctx ->
            val event = payload.event
            val sessionId = "SLACK:${event.channel}"
            val reply = onMessage(sessionId, event.user, event.text, null)
            ctx.say(reply)
            ctx.ack()
        }
        // SLACK_APP_TOKEN is read automatically from the environment by SocketModeApp
        SocketModeApp(app).startAsync() // non-blocking — the assistant process keeps the JVM alive
    }

    override fun send(sessionId: String, text: String) {
        val channelId = sessionId.removePrefix("SLACK:")
        app.client().chatPostMessage { it.channel(channelId).text(text) }
    }
}
```

Declare the implementation in `META-INF/services/com.assistant.ports.ChannelPort`:
```
com.example.SlackChannelAdapter
```

Build a fat JAR and drop it into `~/.assistant/plugins/`. Requires `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN` environment variables with Socket Mode enabled in your Slack app settings.

## Plugin JAR structure

```
my-plugin.jar
├── com/example/MyTool.class
└── META-INF/services/
      └── com.assistant.ports.ToolPort    ← "com.example.MyTool"
```

Drop the JAR into `~/.assistant/plugins/` and restart the assistant.

## Build & Test

```bash
./gradlew test          # run all tests
./gradlew shadowJar     # build the fat JAR
```

## Building and Running the Example Plugins

Three self-contained example plugins live under `examples/`. Each demonstrates how to build, test, and deploy a tool plugin.

### Build a plugin fat JAR

```bash
./gradlew :examples:sysinfo-tool-plugin:shadowJar
./gradlew :examples:calculator-tool-plugin:shadowJar
./gradlew :examples:weather-tool-plugin:shadowJar
```

### Deploy a plugin

Copy the fat JAR to `~/.assistant/plugins/` and restart the assistant:

```bash
cp examples/sysinfo-tool-plugin/build/libs/sysinfo-tool-plugin.jar ~/.assistant/plugins/
# Restart the assistant — sysinfo_get command is now available
```

### Run plugin tests

```bash
./gradlew :examples:sysinfo-tool-plugin:test
./gradlew :examples:calculator-tool-plugin:test
./gradlew :examples:weather-tool-plugin:test
```

### Writing your own plugin

1. Create a new Gradle project with `build.gradle.kts` using the Shadow plugin and `project(":core")` (or `com.assistant:core:VERSION` for standalone projects).
2. Implement `com.assistant.ports.ToolPort`.
3. Declare your implementation in `src/main/resources/META-INF/services/com.assistant.ports.ToolPort`.
4. Build a fat JAR with `./gradlew shadowJar` and copy it to `~/.assistant/plugins/`.

See `examples/sysinfo-tool-plugin/` for the simplest complete example.
