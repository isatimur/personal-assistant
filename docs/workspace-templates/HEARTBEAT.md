# Heartbeat Configuration Guide

The heartbeat is an autonomous loop that fires a prompt through the agent on a schedule and sends the result to your Telegram chat.

Configure it in `config/application.yml` (or `config/secrets.yml`) under the `heartbeat:` key.

---

## Priority order

The runner picks the **first** matching configuration in this order:

1. `agents` (multi-agent list) — each agent fires on its own cron
2. `cron` (single 5-field UNIX cron expression)
3. `time` (HH:MM daily)
4. `every` (simple interval)

---

## Option 1 — Simple interval

Fires every N seconds / minutes / hours / days.

```yaml
heartbeat:
  enabled: true
  every: "2h"
  prompt: "Any proactive actions I should take right now?"
```

Supported units: `s`, `m`, `h`, `d`
Examples: `30m`, `6h`, `1d`

---

## Option 2 — Daily at a fixed time

Fires once per day at HH:MM (local system time).

```yaml
heartbeat:
  enabled: true
  time: "08:00"
  prompt: "Good morning. What are my top 3 priorities today?"
```

---

## Option 3 — Full UNIX cron expression

Use any standard 5-field cron expression.

```yaml
heartbeat:
  enabled: true
  cron: "0 6 * * 1-5"   # weekdays at 06:00
  prompt: "Morning brief: top 3 priorities and first action."
```

Field order: `minute hour day-of-month month day-of-week`
Tool used: [cron-utils](https://github.com/jmrozanec/cron-utils) with `CronType.UNIX`

---

## Option 4 — Multiple named agents

Each agent has its own cron schedule and prompt. Useful for separating morning check-in from an end-of-day review.

```yaml
heartbeat:
  enabled: true
  agents:
    - name: morning
      cron: "0 7 * * 1-5"
      prompt: "Morning brief: what are my top 3 priorities today?"
      timezone: "Europe/London"
    - name: evening
      cron: "0 20 * * 1-5"
      prompt: "End of day: what did I ship today, and what carries over?"
      timezone: "Europe/London"
```

The `timezone` field is optional; it defaults to the system timezone.

---

## Disabling

```yaml
heartbeat:
  enabled: false
```

---

## How it works

When the heartbeat fires, it creates a synthetic `Message` with `sender = "heartbeat"` and sends it through the same agent pipeline as a regular user message. The response is delivered to the last known Telegram chat ID (stored in `~/.assistant/last-chat-id`).

If `last-chat-id` does not exist (no message has been sent yet), the heartbeat skips silently.
