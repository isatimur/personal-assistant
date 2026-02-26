# Contributing to Personal Assistant

## Writing a Plugin Tool

1. Add `com.assistant:core:LATEST` as a dependency (Maven Central, once published).
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

    private var onMessage: (suspend (String, String, String, String?) -> String)? = null

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        this.onMessage = onMessage
        // start your polling/webhook loop here
    }

    override fun send(sessionId: String, text: String) {
        // send proactive message to the session
    }
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
