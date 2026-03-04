# WebChat Dashboard Design

## Goal

Replace the minimal single-page chat UI with a full Linear-inspired dashboard: login, streaming chat with session history, memory browser, monitoring metrics, workspace file editor, and agents overview.

## Architecture

**Build**: `channels/webchat-ui/` is a standard Vite + React 18 project. Gradle's `processResources` task depends on `npmBuild`, which runs `npm ci && npm run build` inside `webchat-ui/`. Build output lands in `channels/src/main/resources/static/` and is included in the fat JAR automatically. In dev, run `npm run dev` in `webchat-ui/` for HMR against the live backend.

**Backend**: `WebChatAdapter.kt` gains new Ktor REST routes (`/api/*`) and token-validated WebSocket alongside the existing endpoints. New constructor parameters supply the password, memory store, workspace loader, token tracker, and routing config.

**Auth**: Single password set via `webchat.password` in `secrets.yml`. `POST /api/auth {password}` returns an HMAC-SHA256 signed token (24h expiry). Token stored in localStorage; sent as `Authorization: Bearer <token>` on all API calls and as `?token=xxx` on the WebSocket URL.

## Backend API

| Route | Purpose |
|---|---|
| `POST /api/auth` | Validate password, return signed token |
| `GET /api/sessions` | Recent session IDs + last message timestamp |
| `GET /api/memory?q=&limit=` | Full-text search over memory store |
| `DELETE /api/memory/{id}` | Delete a memory entry |
| `GET /api/metrics` | Token usage totals, message counts, active sessions |
| `GET /api/workspace` | List files in `~/.assistant/` |
| `GET /api/workspace/{file}` | Read a workspace file |
| `PUT /api/workspace/{file}` | Write a workspace file |
| `GET /api/agents` | Agent names + status from routing config |
| `WS /chat?token=xxx` | Existing streaming WebSocket, now token-validated |

## Frontend

**Stack**: React 18 + React Router v6 + Vite. `recharts` for the monitoring chart. Plain CSS modules — no UI library.

**Design language**: Linear-inspired dark theme. `#0F0F10` page background, `#1A1A1C` sidebar, `#7C3AED` purple accent, Inter font.

**Layout**: Fixed 240px left sidebar (icon + label nav, collapses to icons on narrow screens) + main content area.

**Views**:

| View | Content |
|---|---|
| Login | Full-screen centered card, password input |
| Chat | Session list panel + streaming chat (WebSocket, existing protocol) |
| Memory | Searchable/filterable table, delete per row |
| Monitoring | Token/message/session cards + recharts bar chart for token usage over time |
| Workspace | File list left + textarea editor right for soul.md, identity.md, skills.md, user.md |
| Agents | Card grid: agent name, channel bindings, memory DB path, status dot |

## Config Changes

```yaml
# config/secrets.yml (user adds this)
webchat:
  password: "your-password-here"
```

```kotlin
// WebChatConfig gains:
val password: String = ""
```

`WebChatAdapter` constructor gains: `password`, `memory: SqliteMemoryStore`, `workspace: WorkspaceLoader`, `tokenTracker: TokenTracker`, `routingConfig: RoutingConfig?`.

## File Layout

```
channels/
  webchat-ui/
    src/
      main.tsx
      App.tsx
      views/Login.tsx, Chat.tsx, Memory.tsx, Monitoring.tsx, Workspace.tsx, Agents.tsx
      components/Sidebar.tsx, ChatBubble.tsx, ...
      api.ts          ← typed fetch wrappers
      auth.ts         ← token storage
    package.json
    vite.config.ts
    tsconfig.json
  src/main/resources/static/   ← gitignored build output
```
