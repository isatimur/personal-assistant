# Claude Sonnet 4.6 Provider Switch — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Switch the active LLM brain from `gpt-5-nano-2025-08-07` (OpenAI) to `claude-sonnet-4-6` (Anthropic) by updating config only — no code changes needed.

**Architecture:** The existing `LangChain4jProvider` already has an `"anthropic"` branch that builds `AnthropicChatModel` with `.apiKey()` + `.modelName()`. Changing `provider` and `model` in `application.yml` is sufficient. Voice transcription (Whisper) requires a separate OpenAI key because it can no longer fall back to the LLM key.

**Tech Stack:** KAML + kotlinx-serialization for config, LangChain4j 0.36.2 `langchain4j-anthropic`, OpenAI Whisper API for voice.

---

### Task 1: Update `config/application.yml`

**Files:**
- Modify: `config/application.yml`

**Step 1: Edit the `llm` block**

Replace lines 5–9:

```yaml
llm:
  provider: openai       # openai | anthropic | ollama
  model: gpt-5-nano-2025-08-07
  api-key: "PLACEHOLDER"
  base-url: null
```

With:

```yaml
llm:
  provider: anthropic    # openai | anthropic | ollama
  model: claude-sonnet-4-6
  api-key: "PLACEHOLDER"  # overridden by secrets.yml
  # openai fallback: provider: openai, model: gpt-5-nano-2025-08-07
```

**Step 2: Fix the `voice` comment**

The current comment says "(works when provider=openai)". Update the voice block to make it clear a separate OpenAI key is now required:

```yaml
voice:
  enabled: true
  api-key: ""   # must set a separate OpenAI key here for Whisper — cannot share the Anthropic llm.api-key
```

**Step 3: Commit**

```bash
git add config/application.yml
git commit -m "config: switch LLM provider to claude-sonnet-4-6"
```

---

### Task 2: Update `config/secrets.yml` with Anthropic key

**Files:**
- Modify: `config/secrets.yml` (not in git — edit manually on the machine where the app runs)

**Step 1: Set the Anthropic API key**

In `config/secrets.yml`, update `llm.api-key` to your Anthropic key:

```yaml
llm:
  api-key: "sk-ant-..."

voice:
  api-key: "sk-..."    # OpenAI key — required for Whisper transcription
```

> If you don't have a separate OpenAI key for voice, set `voice.enabled: false` in `application.yml` to skip transcription.

No commit — `secrets.yml` is not tracked by git.

---

### Task 3: Update `CLAUDE.local.md`

**Files:**
- Modify: `CLAUDE.local.md`

**Step 1: Remove the OpenAI model quirks section**

Delete the entire "OpenAI model quirks" block (lines 3–8). It no longer applies.

**Step 2: Add Anthropic notes**

Replace with:

```markdown
## Active LLM provider

- Provider: `anthropic`, Model: `claude-sonnet-4-6`
- No temperature override needed — Anthropic defaults to 1.0
- Voice transcription (Whisper) uses a **separate** OpenAI key set under `voice.api-key` in `secrets.yml`
```

**Step 3: Commit**

```bash
git add CLAUDE.local.md
git commit -m "docs: update local notes for claude-sonnet-4-6 provider"
```

---

### Task 4: Smoke-test the running app

**Step 1: Build the fat JAR**

```bash
./gradlew shadowJar
```

Expected: `BUILD SUCCESSFUL`

**Step 2: Start the app**

```bash
java -jar app/build/libs/assistant.jar
```

Watch startup logs for:
- No `IllegalArgumentException: Unknown provider` errors
- `AnthropicChatModel` initialisation without HTTP 4xx at startup
- Telegram bot poll starting successfully

**Step 3: Send a test message via Telegram**

Send any message to the bot. Expected in logs:
- A call going to the Anthropic API (not OpenAI)
- A coherent reply from `claude-sonnet-4-6`
- No `400 Bad Request` or auth errors

**Step 4: Send a voice message (if voice is enabled)**

Confirm Whisper transcription works with the separate OpenAI key set in `secrets.yml`.

---

### Rollback

If something breaks at runtime (e.g. LangChain4j 0.36.2 rejects the model name):

```bash
# In config/application.yml, revert the llm block:
# provider: openai
# model: gpt-5-nano-2025-08-07
# Then restore the OpenAI key in secrets.yml
```

If LangChain4j rejects the model name, the fix is bumping `langchain4j-*` to `1.x` in `providers/build.gradle.kts` (see design doc Option B).
