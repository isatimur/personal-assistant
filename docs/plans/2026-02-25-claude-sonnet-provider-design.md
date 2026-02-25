# Design: Switch LLM Brain to Claude Sonnet 4.6

**Date:** 2026-02-25
**Status:** Approved

## Goal

Replace `gpt-5-nano-2025-08-07` (OpenAI) with `claude-sonnet-4-6` (Anthropic) as the active LLM provider, keeping LangChain4j as the integration layer.

## Approach

Config-only change. The `"anthropic"` branch in `LangChain4jProvider.kt` already builds `AnthropicChatModel` with `.apiKey()` + `.modelName()` — no code changes required.

## Changes

### `config/application.yml` — `llm` section

```yaml
llm:
  provider: anthropic
  model: claude-sonnet-4-6
  api-key: "PLACEHOLDER"   # overridden by secrets.yml
```

Keep the previous OpenAI block as a comment for easy rollback.

### `config/secrets.yml`

Replace the `llm.api-key` value with the Anthropic API key (same structure, different key).

### `CLAUDE.local.md`

Remove the "OpenAI model quirks" section (temperature=1.0 requirement) since it no longer applies. Note that no temperature override is needed for Anthropic — its default is already 1.0.

## What Does Not Change

- `LangChain4jProvider.kt` — zero code changes
- Tool calling — `model.generate(messages, specs)` path is the same
- LangChain4j version stays at 0.36.2
- All other config sections (telegram, memory, tools, heartbeat, voice)

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| LangChain4j 0.36.2 Anthropic adapter rejects `claude-sonnet-4-6` model name | Bump langchain4j to 1.x (Option B) |
| Tool-use format differences in newer Claude models | Monitor logs after first run; fall back via config |

## Rollback

Change `provider` back to `openai` and `model` back to `gpt-5-nano-2025-08-07` in `application.yml`, restore OpenAI key in `secrets.yml`.
