# Contributing to Personal Assistant

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
