# WebChat Dashboard Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the minimal single-page chat with a full Linear-inspired React dashboard: login, chat with session history, memory browser, monitoring, workspace editor, and agents view.

**Architecture:** Vite+React 18 SPA in `channels/webchat-ui/`. Gradle runs `npm run build` before `processResources`, placing the output into `channels/src/main/resources/static/` which Ktor serves. `WebChatAdapter.kt` gains REST API routes (`/api/*`) and single-password auth with an in-memory token store.

**Tech Stack:** React 18, React Router v6, TypeScript, Vite, recharts, Ktor 2.3.12, Kotlin, SQLite (Exposed)

**Design:** Linear-inspired dark theme. CSS custom properties on `:root`. No UI library — plain CSS Modules.

---

### Task 1: Scaffold `channels/webchat-ui/` Vite+React project

**Files:**
- Create: `channels/webchat-ui/package.json`
- Create: `channels/webchat-ui/vite.config.ts`
- Create: `channels/webchat-ui/tsconfig.json`
- Create: `channels/webchat-ui/tsconfig.app.json`
- Create: `channels/webchat-ui/index.html`
- Create: `channels/webchat-ui/src/main.tsx`
- Create: `channels/webchat-ui/src/App.tsx`
- Create: `channels/webchat-ui/src/vite-env.d.ts`

**Step 1: Create `package.json`**

```json
{
  "name": "webchat-ui",
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.28.0",
    "recharts": "^2.14.1"
  },
  "devDependencies": {
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^4.3.4",
    "typescript": "^5.6.2",
    "vite": "^6.0.5"
  }
}
```

**Step 2: Create `vite.config.ts`**

```ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/chat': { target: 'ws://localhost:8080', ws: true },
    },
  },
})
```

**Step 3: Create `tsconfig.json`**

```json
{
  "files": [],
  "references": [{ "path": "./tsconfig.app.json" }]
}
```

**Step 4: Create `tsconfig.app.json`**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "moduleDetection": "force",
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true
  },
  "include": ["src"]
}
```

**Step 5: Create `index.html`**

```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Assistant</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

**Step 6: Create `src/vite-env.d.ts`**

```ts
/// <reference types="vite/client" />
```

**Step 7: Create `src/main.tsx`**

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

**Step 8: Create `src/App.tsx`** (placeholder — expanded in Task 5)

```tsx
export default function App() {
  return <div>Loading…</div>
}
```

**Step 9: Create `src/index.css`** (global CSS variables — design tokens)

```css
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

:root {
  --bg: #0F0F10;
  --surface: #1A1A1C;
  --sidebar: #111113;
  --border: #2A2A2E;
  --text: #EDEDEF;
  --muted: #6B6B78;
  --accent: #7C3AED;
  --accent-hover: #6D28D9;
  --danger: #EF4444;
  --success: #22C55E;
  --font: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  --radius: 8px;
}

html, body, #root {
  height: 100%;
  background: var(--bg);
  color: var(--text);
  font-family: var(--font);
  font-size: 14px;
}

a { color: inherit; text-decoration: none; }
button { cursor: pointer; font-family: inherit; }

::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
```

**Step 10: Install dependencies**

```bash
cd channels/webchat-ui && npm install
```

**Step 11: Verify it builds**

```bash
cd channels/webchat-ui && npm run build
```

Expected: `channels/src/main/resources/static/index.html` created with no errors.

**Step 12: Add static output to `.gitignore`**

Add to root `.gitignore`:
```
channels/src/main/resources/static/
channels/webchat-ui/node_modules/
```

**Step 13: Commit**

```bash
git add channels/webchat-ui/ .gitignore
git commit -m "feat: scaffold webchat-ui Vite+React project"
```

---

### Task 2: Gradle `npmBuild` integration

**Files:**
- Modify: `channels/build.gradle.kts`

**Step 1: Add the two Gradle tasks to `channels/build.gradle.kts`**

Append before the closing brace:

```kotlin
// ── WebChat UI build ──────────────────────────────────────────────────────────
val npmInstall by tasks.registering(Exec::class) {
    workingDir = file("webchat-ui")
    inputs.file("webchat-ui/package-lock.json")
    outputs.dir("webchat-ui/node_modules")
    commandLine("npm", "ci", "--prefer-offline")
}

val npmBuild by tasks.registering(Exec::class) {
    dependsOn(npmInstall)
    workingDir = file("webchat-ui")
    inputs.dir("webchat-ui/src")
    inputs.files("webchat-ui/package.json", "webchat-ui/vite.config.ts", "webchat-ui/tsconfig.app.json")
    outputs.dir("src/main/resources/static")
    commandLine("npm", "run", "build")
}

tasks.named("processResources") {
    dependsOn(npmBuild)
}
```

**Step 2: Build the full project to verify wiring**

```bash
./gradlew :channels:processResources
```

Expected: BUILD SUCCESSFUL, `channels/src/main/resources/static/index.html` exists.

**Step 3: Verify fat JAR build still works**

```bash
./gradlew shadowJar
```

Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add channels/build.gradle.kts
git commit -m "build: integrate Vite npm build into Gradle processResources"
```

---

### Task 3: Auth — Config + WebChatAdapter constructor + `POST /api/auth`

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Config.kt`
- Modify: `channels/build.gradle.kts` (add `:memory` dependency)
- Modify: `channels/src/main/kotlin/com/assistant/webchat/WebChatAdapter.kt`

**Step 1: Add `password` to `WebChatConfig` in `Config.kt`**

```kotlin
// Before:
@Serializable data class WebChatConfig(
    val enabled: Boolean = false,
    val port: Int = 8080,
    @SerialName("base-path") val basePath: String = ""
)

// After:
@Serializable data class WebChatConfig(
    val enabled: Boolean = false,
    val port: Int = 8080,
    @SerialName("base-path") val basePath: String = "",
    val password: String = ""
)
```

**Step 2: Add `WebChatSecrets` and wire into `SecretsConfig` + `loadConfig()`**

In `SecretsConfig`:
```kotlin
@Serializable data class WebChatSecrets(val password: String? = null)

// Add to SecretsConfig:
val webchat: WebChatSecrets? = null
```

In `loadConfig()`, add inside the `return base.copy(...)`:
```kotlin
webchat = run {
    var w = base.webchat
    if (secrets.webchat?.password != null) w = w.copy(password = secrets.webchat.password)
    w
},
```

**Step 3: Add `:memory` dependency to `channels/build.gradle.kts`**

```kotlin
// Add to dependencies block:
implementation(project(":memory"))
```

**Step 4: Refactor `WebChatAdapter` constructor + add in-memory token store**

Replace the class declaration and add token store. Full new constructor:

```kotlin
import com.assistant.memory.SqliteMemoryStore
import com.assistant.agent.TokenTracker
import com.assistant.workspace.WorkspaceLoader
import com.assistant.AppConfig
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

data class AgentInfo(val name: String, val channels: List<String>, val dbPath: String)

class WebChatAdapter(
    private val gateway: Gateway,
    private val port: Int = 8080,
    private val basePath: String = "",
    private val password: String = "",
    private val memory: SqliteMemoryStore? = null,
    private val workspace: WorkspaceLoader? = null,
    private val tokenTracker: TokenTracker? = null,
    private val agents: List<AgentInfo> = emptyList()
) : ChannelPort, Closeable {
    // ... (keep existing fields)

    // Token store: token -> expiry millis
    private val validTokens = ConcurrentHashMap<String, Long>()

    private fun generateToken(): String {
        val token = UUID.randomUUID().toString()
        validTokens[token] = System.currentTimeMillis() + 24 * 3_600_000L
        return token
    }

    private fun isValidToken(token: String?): Boolean {
        if (token == null) return false
        val expiry = validTokens[token] ?: return false
        if (System.currentTimeMillis() > expiry) { validTokens.remove(token); return false }
        return true
    }

    private fun extractToken(call: io.ktor.server.application.ApplicationCall): String? =
        call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: call.request.queryParameters["token"]
```

**Step 5: Add `POST /api/auth` route inside `start()`, within the `routing { }` block**

```kotlin
post("$prefix/api/auth") {
    val body = call.receiveText()
    val json = try { Json.parseToJsonElement(body).jsonObject } catch (_: Exception) {
        call.respond(HttpStatusCode.BadRequest, """{"error":"invalid json"}"""); return@post
    }
    val pwd = json["password"]?.jsonPrimitive?.content ?: ""
    if (password.isNotBlank() && pwd != password) {
        call.respond(HttpStatusCode.Unauthorized, """{"error":"wrong password"}""")
        return@post
    }
    val token = generateToken()
    call.respond("""{"token":"$token"}""")
}
```

**Step 6: Add auth check helper used by all other `/api/*` routes**

```kotlin
// Inside the routing { } block, before individual routes:
fun io.ktor.server.routing.Route.requireAuth(block: io.ktor.server.routing.Route.() -> Unit): io.ktor.server.routing.Route {
    return route("") {
        install(createRouteScopedPlugin("WebChatAuth") {
            onCall { call ->
                if (password.isNotBlank() && !isValidToken(extractToken(call))) {
                    call.respond(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""")
                    finish()
                }
            }
        })
        block()
    }
}
```

Note: `createRouteScopedPlugin` is available from `io.ktor.server.plugins.*`. Add the import. All subsequent `/api/*` routes will be nested inside `requireAuth { }`.

**Step 7: Update WebSocket to validate token**

In the existing `webSocket("$prefix/chat")` handler, add at the top:
```kotlin
val token = call.request.queryParameters["token"]
if (password.isNotBlank() && !isValidToken(token)) {
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
    return@webSocket
}
```

**Step 8: Compile check**

```bash
./gradlew :channels:compileKotlin
```

Expected: BUILD SUCCESSFUL.

**Step 9: Commit**

```bash
git add app/src/main/kotlin/com/assistant/Config.kt \
        channels/build.gradle.kts \
        channels/src/main/kotlin/com/assistant/webchat/WebChatAdapter.kt
git commit -m "feat: webchat auth — password config, token store, POST /api/auth"
```

---

### Task 4: Data API routes + `SqliteMemoryStore` additions

**Files:**
- Modify: `memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt`
- Modify: `channels/src/main/kotlin/com/assistant/webchat/WebChatAdapter.kt`

**Step 1: Add data classes + query methods to `SqliteMemoryStore`**

Add after the `Chunks` object definition:

```kotlin
data class ChunkRow(val id: Long, val text: String, val sessionId: String, val createdAt: Long)
data class SessionRow(val sessionId: String, val messageCount: Int, val lastActivity: Long)
```

Add these methods to the class body:

```kotlin
fun listChunks(limit: Int = 100): List<ChunkRow> = transaction(db) {
    Chunks.selectAll().orderBy(Chunks.createdAt, SortOrder.DESC).limit(limit).map {
        ChunkRow(it[Chunks.id].value, it[Chunks.text], it[Chunks.sessionId], it[Chunks.createdAt])
    }
}

fun searchChunks(query: String, limit: Int = 20): List<ChunkRow> = transaction(db) {
    val q = sanitizeFtsQuery(query)
    if (q.isBlank()) return@transaction listChunks(limit)
    exec("""
        SELECT c.id, c.text, c.session_id, c.created_at
        FROM chunks c
        JOIN chunks_fts fts ON fts.rowid = c.id
        WHERE chunks_fts MATCH '$q'
        ORDER BY rank LIMIT $limit
    """) { rs ->
        val results = mutableListOf<ChunkRow>()
        while (rs.next()) results.add(ChunkRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
        results
    } ?: emptyList()
}

fun deleteChunk(id: Long) = transaction(db) {
    Chunks.deleteWhere { Chunks.id eq id }
}

fun listSessions(limit: Int = 30): List<SessionRow> = transaction(db) {
    exec("""
        SELECT session_id, COUNT(*) as cnt, MAX(created_at) as last
        FROM messages
        GROUP BY session_id
        ORDER BY last DESC
        LIMIT $limit
    """) { rs ->
        val results = mutableListOf<SessionRow>()
        while (rs.next()) results.add(SessionRow(rs.getString(1), rs.getInt(2), rs.getLong(3)))
        results
    } ?: emptyList()
}
```

**Step 2: Add API routes inside `requireAuth { }` block in `WebChatAdapter.start()`**

Add Ktor content-type header helper at top of the auth block. Routes return JSON strings directly (no serialization library needed — hand-built JSON for these simple payloads):

```kotlin
requireAuth {
    // Sessions
    get("$prefix/api/sessions") {
        val rows = memory?.listSessions() ?: emptyList()
        val json = rows.joinToString(",", "[", "]") {
            """{"id":${Json.encodeToString(it.sessionId)},"messages":${it.messageCount},"lastActivity":${it.lastActivity}}"""
        }
        call.respondText(json, ContentType.Application.Json)
    }

    // Memory
    get("$prefix/api/memory") {
        val q = call.request.queryParameters["q"] ?: ""
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val rows = if (q.isBlank()) memory?.listChunks(limit) ?: emptyList()
                   else memory?.searchChunks(q, limit) ?: emptyList()
        val json = rows.joinToString(",", "[", "]") {
            """{"id":${it.id},"text":${Json.encodeToString(it.text)},"sessionId":${Json.encodeToString(it.sessionId)},"createdAt":${it.createdAt}}"""
        }
        call.respondText(json, ContentType.Application.Json)
    }

    delete("$prefix/api/memory/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: run { call.respond(HttpStatusCode.BadRequest, """{"error":"bad id"}"""); return@delete }
        memory?.deleteChunk(id)
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // Metrics
    get("$prefix/api/metrics") {
        val stats = tokenTracker?.globalStats()
        val json = """{"inputTokens":${stats?.inputTokens ?: 0},"outputTokens":${stats?.outputTokens ?: 0},"totalTokens":${stats?.totalTokens ?: 0},"sessions":${memory?.listSessions(1000)?.size ?: 0}}"""
        call.respondText(json, ContentType.Application.Json)
    }

    // Workspace files
    val workspaceFiles = listOf("soul.md", "identity.md", "skills.md", "user.md", "bootstrap.md")
    get("$prefix/api/workspace") {
        val dir = workspace?.workspaceDir
        val json = workspaceFiles.joinToString(",", "[", "]") { name ->
            val exists = dir?.let { java.io.File(it, name).exists() } ?: false
            """{"name":${Json.encodeToString(name)},"exists":$exists}"""
        }
        call.respondText(json, ContentType.Application.Json)
    }

    get("$prefix/api/workspace/{file}") {
        val name = call.parameters["file"] ?: run { call.respond(HttpStatusCode.BadRequest); return@get }
        if (!workspaceFiles.contains(name)) { call.respond(HttpStatusCode.Forbidden); return@get }
        val content = workspace?.workspaceDir?.let { java.io.File(it, name).takeIf { f -> f.exists() }?.readText() } ?: ""
        call.respondText("""{"content":${Json.encodeToString(content)}}""", ContentType.Application.Json)
    }

    put("$prefix/api/workspace/{file}") {
        val name = call.parameters["file"] ?: run { call.respond(HttpStatusCode.BadRequest); return@put }
        if (!workspaceFiles.contains(name)) { call.respond(HttpStatusCode.Forbidden); return@put }
        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
        val content = body["content"]?.jsonPrimitive?.content ?: ""
        workspace?.workspaceDir?.let { java.io.File(it, name).writeText(content) }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // Agents
    get("$prefix/api/agents") {
        val json = agents.joinToString(",", "[", "]") {
            """{"name":${Json.encodeToString(it.name)},"channels":${it.channels.joinToString(",","[","]"){c -> Json.encodeToString(c)}},"dbPath":${Json.encodeToString(it.dbPath)}}"""
        }
        call.respondText(json, ContentType.Application.Json)
    }
}
```

Also add `import io.ktor.server.request.receiveText` and `io.ktor.http.ContentType` if not already imported.

**Step 3: Compile check**

```bash
./gradlew :channels:compileKotlin :memory:compileKotlin
```

Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add memory/src/main/kotlin/com/assistant/memory/SqliteMemoryStore.kt \
        channels/src/main/kotlin/com/assistant/webchat/WebChatAdapter.kt
git commit -m "feat: webchat API routes — sessions, memory, metrics, workspace, agents"
```

---

### Task 5: React app shell — routing, Sidebar, auth helpers

**Files:**
- Create: `channels/webchat-ui/src/auth.ts`
- Create: `channels/webchat-ui/src/api.ts`
- Create: `channels/webchat-ui/src/components/Sidebar.tsx`
- Create: `channels/webchat-ui/src/components/Sidebar.module.css`
- Modify: `channels/webchat-ui/src/App.tsx`

**Step 1: Create `src/auth.ts`**

```ts
const KEY = 'assistant_token'

export function getToken(): string | null {
  return localStorage.getItem(KEY)
}

export function setToken(token: string) {
  localStorage.setItem(KEY, token)
}

export function clearToken() {
  localStorage.removeItem(KEY)
}

export function isAuthenticated(): boolean {
  return !!getToken()
}

export async function login(password: string): Promise<boolean> {
  const res = await fetch('/api/auth', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  if (!res.ok) return false
  const { token } = await res.json()
  setToken(token)
  return true
}
```

**Step 2: Create `src/api.ts`**

```ts
import { getToken } from './auth'

function headers(): HeadersInit {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function apiFetch(path: string, opts: RequestInit = {}) {
  const res = await fetch(path, { ...opts, headers: { ...headers(), ...(opts.headers ?? {}) } })
  if (res.status === 401) { window.location.href = '/login'; throw new Error('Unauthorized') }
  return res
}

export async function getSessions() {
  return (await apiFetch('/api/sessions')).json()
}

export async function getMemory(q = '', limit = 50) {
  return (await apiFetch(`/api/memory?q=${encodeURIComponent(q)}&limit=${limit}`)).json()
}

export async function deleteMemory(id: number) {
  await apiFetch(`/api/memory/${id}`, { method: 'DELETE' })
}

export async function getMetrics() {
  return (await apiFetch('/api/metrics')).json()
}

export async function getWorkspaceFiles() {
  return (await apiFetch('/api/workspace')).json()
}

export async function getWorkspaceFile(name: string): Promise<{ content: string }> {
  return (await apiFetch(`/api/workspace/${name}`)).json()
}

export async function putWorkspaceFile(name: string, content: string) {
  await apiFetch(`/api/workspace/${name}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })
}

export async function getAgents() {
  return (await apiFetch('/api/agents')).json()
}
```

**Step 3: Create `src/components/Sidebar.tsx`**

```tsx
import { NavLink, useNavigate } from 'react-router-dom'
import { clearToken } from '../auth'
import styles from './Sidebar.module.css'

const NAV = [
  { to: '/chat',       label: 'Chat',        icon: '💬' },
  { to: '/memory',     label: 'Memory',      icon: '🧠' },
  { to: '/monitoring', label: 'Monitoring',  icon: '📊' },
  { to: '/workspace',  label: 'Workspace',   icon: '📝' },
  { to: '/agents',     label: 'Agents',      icon: '🤖' },
]

export default function Sidebar() {
  const navigate = useNavigate()

  function handleLogout() {
    clearToken()
    navigate('/login')
  }

  return (
    <nav className={styles.sidebar}>
      <div className={styles.logo}>Assistant</div>
      <ul className={styles.nav}>
        {NAV.map(({ to, label, icon }) => (
          <li key={to}>
            <NavLink to={to} className={({ isActive }) => `${styles.link} ${isActive ? styles.active : ''}`}>
              <span className={styles.icon}>{icon}</span>
              <span className={styles.label}>{label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
      <button className={styles.logout} onClick={handleLogout}>Sign out</button>
    </nav>
  )
}
```

**Step 4: Create `src/components/Sidebar.module.css`**

```css
.sidebar {
  width: 220px;
  min-height: 100vh;
  background: var(--sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 16px 0;
  flex-shrink: 0;
}

.logo {
  padding: 0 20px 20px;
  font-size: 16px;
  font-weight: 700;
  color: var(--text);
  letter-spacing: -0.3px;
}

.nav {
  list-style: none;
  flex: 1;
}

.link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 20px;
  color: var(--muted);
  transition: color .15s, background .15s;
  border-radius: 0;
}

.link:hover { color: var(--text); background: rgba(255,255,255,.04); }

.active { color: var(--text) !important; background: rgba(124,58,237,.15) !important; }

.icon { font-size: 16px; width: 20px; text-align: center; }
.label { font-size: 13px; font-weight: 500; }

.logout {
  margin: 16px 20px 0;
  padding: 8px 12px;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--muted);
  font-size: 13px;
  transition: color .15s, border-color .15s;
}
.logout:hover { color: var(--danger); border-color: var(--danger); }
```

**Step 5: Replace `src/App.tsx` with router + layout**

```tsx
import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router-dom'
import { isAuthenticated } from './auth'
import Sidebar from './components/Sidebar'
import Login from './views/Login'
import Chat from './views/Chat'
import Memory from './views/Memory'
import Monitoring from './views/Monitoring'
import Workspace from './views/Workspace'
import Agents from './views/Agents'

function ProtectedLayout() {
  if (!isAuthenticated()) return <Navigate to="/login" replace />
  return (
    <div style={{ display: 'flex', height: '100vh' }}>
      <Sidebar />
      <main style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        <Outlet />
      </main>
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<ProtectedLayout />}>
          <Route path="/chat" element={<Chat />} />
          <Route path="/memory" element={<Memory />} />
          <Route path="/monitoring" element={<Monitoring />} />
          <Route path="/workspace" element={<Workspace />} />
          <Route path="/agents" element={<Agents />} />
          <Route path="*" element={<Navigate to="/chat" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
```

**Step 6: Create stub view files** (so the app compiles before each view is implemented)

Create each with a minimal stub:

`src/views/Login.tsx`:
```tsx
export default function Login() { return <div>Login</div> }
```

Repeat for `Chat.tsx`, `Memory.tsx`, `Monitoring.tsx`, `Workspace.tsx`, `Agents.tsx`.

**Step 7: Build to verify**

```bash
cd channels/webchat-ui && npm run build
```

Expected: no TypeScript errors, output in `channels/src/main/resources/static/`.

**Step 8: Commit**

```bash
git add channels/webchat-ui/src/
git commit -m "feat: React app shell — routing, Sidebar, auth helpers, API client"
```

---

### Task 6: Login view + Chat view

**Files:**
- Modify: `channels/webchat-ui/src/views/Login.tsx`
- Create: `channels/webchat-ui/src/views/Login.module.css`
- Modify: `channels/webchat-ui/src/views/Chat.tsx`
- Create: `channels/webchat-ui/src/views/Chat.module.css`

**Step 1: Implement `Login.tsx`**

```tsx
import { useState, FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { login } from '../auth'
import styles from './Login.module.css'

export default function Login() {
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    const ok = await login(password)
    setLoading(false)
    if (ok) navigate('/chat')
    else setError('Wrong password')
  }

  return (
    <div className={styles.page}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <h1 className={styles.title}>Assistant</h1>
        <p className={styles.sub}>Enter your password to continue</p>
        <input
          className={styles.input}
          type="password"
          placeholder="Password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          autoFocus
        />
        {error && <p className={styles.error}>{error}</p>}
        <button className={styles.btn} type="submit" disabled={loading}>
          {loading ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
```

`Login.module.css`:
```css
.page { display: flex; align-items: center; justify-content: center; height: 100vh; background: var(--bg); }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 40px; width: 360px; display: flex; flex-direction: column; gap: 16px; }
.title { font-size: 22px; font-weight: 700; text-align: center; }
.sub { color: var(--muted); font-size: 13px; text-align: center; }
.input { padding: 10px 14px; border-radius: var(--radius); border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 14px; outline: none; }
.input:focus { border-color: var(--accent); }
.error { color: var(--danger); font-size: 13px; }
.btn { padding: 10px; border-radius: var(--radius); border: none; background: var(--accent); color: #fff; font-size: 14px; font-weight: 600; transition: background .15s; }
.btn:hover:not(:disabled) { background: var(--accent-hover); }
.btn:disabled { opacity: .5; cursor: default; }
```

**Step 2: Implement `Chat.tsx`**

Port the existing WebSocket chat logic to React. Uses the stored token in the WebSocket URL:

```tsx
import { useState, useEffect, useRef, useCallback } from 'react'
import { getSessions } from '../api'
import { getToken } from '../auth'
import styles from './Chat.module.css'

// Minimal markdown: wrap code blocks, bold, newlines
function renderMarkdown(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, m => `<pre><code>${m.slice(3, -3).replace(/^[^\n]*\n/, '')}</code></pre>`)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br/>')
}

interface Msg { role: 'user' | 'bot'; text: string }
interface Session { id: string; messages: number; lastActivity: number }

export default function Chat() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [messages, setMessages] = useState<Msg[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const chatRef = useRef<HTMLDivElement>(null)
  const botTextRef = useRef('')

  useEffect(() => {
    getSessions().then(setSessions)
    connect()
    return () => wsRef.current?.close()
  }, [])

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const token = getToken() ?? ''
    const ws = new WebSocket(`${proto}//${location.host}/chat?token=${token}`)

    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.token !== undefined) {
        botTextRef.current += msg.token
        setMessages(prev => {
          const last = prev[prev.length - 1]
          if (last?.role === 'bot') {
            return [...prev.slice(0, -1), { role: 'bot', text: botTextRef.current }]
          }
          return [...prev, { role: 'bot', text: botTextRef.current }]
        })
        setTimeout(() => { chatRef.current?.scrollTo(0, chatRef.current.scrollHeight) }, 0)
      } else if (msg.done) {
        botTextRef.current = ''
        setSending(false)
      } else if (msg.error) {
        setMessages(prev => [...prev, { role: 'bot', text: `⚠️ ${msg.error}` }])
        setSending(false)
      }
    }

    ws.onclose = () => setTimeout(connect, 2000)
    ws.onerror = () => ws.close()
    wsRef.current = ws
  }

  function send() {
    const text = input.trim()
    if (!text || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return
    setMessages(prev => [...prev, { role: 'user', text }])
    wsRef.current.send(JSON.stringify({ text }))
    setInput('')
    setSending(true)
    botTextRef.current = ''
    setTimeout(() => { chatRef.current?.scrollTo(0, chatRef.current.scrollHeight) }, 0)
  }

  return (
    <div className={styles.layout}>
      <aside className={styles.sessions}>
        <div className={styles.sessionsHeader}>Sessions</div>
        {sessions.map(s => (
          <div key={s.id} className={styles.sessionItem}>
            <span className={styles.sessionId}>{s.id.slice(0, 20)}…</span>
            <span className={styles.sessionMeta}>{s.messages} msgs</span>
          </div>
        ))}
      </aside>
      <div className={styles.chatArea}>
        <div className={styles.messages} ref={chatRef}>
          {messages.map((m, i) => (
            <div key={i} className={`${styles.msg} ${styles[m.role]}`}>
              {m.role === 'bot'
                ? <div dangerouslySetInnerHTML={{ __html: renderMarkdown(m.text) }} />
                : m.text}
              {m.role === 'bot' && sending && i === messages.length - 1 && (
                <span className={styles.cursor} />
              )}
            </div>
          ))}
        </div>
        <div className={styles.footer}>
          <textarea
            className={styles.input}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
            placeholder="Message… (Shift+Enter for newline)"
            rows={1}
          />
          <button className={styles.sendBtn} onClick={send} disabled={sending}>Send</button>
        </div>
      </div>
    </div>
  )
}
```

`Chat.module.css`:
```css
.layout { display: flex; height: 100%; }
.sessions { width: 220px; background: var(--sidebar); border-right: 1px solid var(--border); overflow-y: auto; padding: 12px 0; }
.sessionsHeader { padding: 8px 16px; font-size: 11px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .5px; }
.sessionItem { padding: 8px 16px; cursor: pointer; }
.sessionItem:hover { background: rgba(255,255,255,.04); }
.sessionId { font-size: 12px; color: var(--text); display: block; }
.sessionMeta { font-size: 11px; color: var(--muted); }
.chatArea { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.messages { flex: 1; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 16px; }
.msg { max-width: 75%; padding: 12px 16px; border-radius: 12px; line-height: 1.6; word-break: break-word; }
.user { align-self: flex-end; background: var(--accent); color: #fff; border-radius: 12px 12px 2px 12px; }
.bot { align-self: flex-start; background: var(--surface); border: 1px solid var(--border); border-radius: 12px 12px 12px 2px; }
.bot pre { background: var(--bg); border-radius: 6px; padding: 10px; overflow-x: auto; font-size: 12px; margin: 8px 0; }
.bot code { font-family: 'JetBrains Mono', 'Courier New', monospace; font-size: 12px; }
.cursor { display: inline-block; width: 7px; height: 1em; background: var(--accent); vertical-align: text-bottom; animation: blink .8s step-end infinite; margin-left: 2px; }
@keyframes blink { 50% { opacity: 0 } }
.footer { padding: 16px 24px; border-top: 1px solid var(--border); display: flex; gap: 12px; }
.input { flex: 1; padding: 10px 14px; border-radius: var(--radius); border: 1px solid var(--border); background: var(--surface); color: var(--text); font-size: 14px; outline: none; resize: none; min-height: 42px; max-height: 140px; font-family: var(--font); }
.input:focus { border-color: var(--accent); }
.sendBtn { padding: 10px 20px; border-radius: var(--radius); border: none; background: var(--accent); color: #fff; font-size: 14px; font-weight: 600; align-self: flex-end; transition: background .15s; }
.sendBtn:hover:not(:disabled) { background: var(--accent-hover); }
.sendBtn:disabled { opacity: .5; cursor: default; }
```

**Step 3: Build**

```bash
cd channels/webchat-ui && npm run build
```

**Step 4: Commit**

```bash
git add channels/webchat-ui/src/views/
git commit -m "feat: webchat Login and Chat views"
```

---

### Task 7: Memory + Agents views

**Files:**
- Modify: `channels/webchat-ui/src/views/Memory.tsx`
- Create: `channels/webchat-ui/src/views/Memory.module.css`
- Modify: `channels/webchat-ui/src/views/Agents.tsx`
- Create: `channels/webchat-ui/src/views/Agents.module.css`

**Step 1: Implement `Memory.tsx`**

```tsx
import { useState, useEffect, useCallback } from 'react'
import { getMemory, deleteMemory } from '../api'
import styles from './Memory.module.css'

interface MemoryRow { id: number; text: string; sessionId: string; createdAt: number }

export default function Memory() {
  const [rows, setRows] = useState<MemoryRow[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setRows(await getMemory(query))
    setLoading(false)
  }, [query])

  useEffect(() => { load() }, [load])

  async function handleDelete(id: number) {
    await deleteMemory(id)
    setRows(r => r.filter(x => x.id !== id))
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h2 className={styles.title}>Memory</h2>
        <input
          className={styles.search}
          placeholder="Search memory…"
          value={query}
          onChange={e => setQuery(e.target.value)}
        />
      </div>
      {loading ? <p className={styles.muted}>Loading…</p> : (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Text</th>
              <th>Session</th>
              <th>Date</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.id}>
                <td className={styles.textCell}>{r.text}</td>
                <td className={styles.meta}>{r.sessionId.slice(0, 24)}…</td>
                <td className={styles.meta}>{new Date(r.createdAt).toLocaleDateString()}</td>
                <td><button className={styles.del} onClick={() => handleDelete(r.id)}>×</button></td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={4} className={styles.muted}>No results</td></tr>}
          </tbody>
        </table>
      )}
    </div>
  )
}
```

`Memory.module.css`:
```css
.page { padding: 32px; overflow-y: auto; height: 100%; }
.header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
.title { font-size: 20px; font-weight: 700; }
.search { flex: 1; max-width: 360px; padding: 8px 12px; border-radius: var(--radius); border: 1px solid var(--border); background: var(--surface); color: var(--text); font-size: 13px; outline: none; }
.search:focus { border-color: var(--accent); }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th { text-align: left; padding: 8px 12px; color: var(--muted); border-bottom: 1px solid var(--border); font-weight: 500; }
.table td { padding: 10px 12px; border-bottom: 1px solid var(--border); vertical-align: top; }
.table tr:hover td { background: rgba(255,255,255,.03); }
.textCell { max-width: 500px; line-height: 1.5; }
.meta { color: var(--muted); white-space: nowrap; }
.del { background: transparent; border: none; color: var(--muted); font-size: 18px; padding: 0 4px; transition: color .15s; }
.del:hover { color: var(--danger); }
.muted { color: var(--muted); padding: 16px 0; }
```

**Step 2: Implement `Agents.tsx`**

```tsx
import { useState, useEffect } from 'react'
import { getAgents } from '../api'
import styles from './Agents.module.css'

interface Agent { name: string; channels: string[]; dbPath: string }

export default function Agents() {
  const [agents, setAgents] = useState<Agent[]>([])

  useEffect(() => { getAgents().then(setAgents) }, [])

  return (
    <div className={styles.page}>
      <h2 className={styles.title}>Agents</h2>
      <div className={styles.grid}>
        {agents.length === 0 && <p className={styles.muted}>No agents configured.</p>}
        {agents.map(a => (
          <div key={a.name} className={styles.card}>
            <div className={styles.cardHeader}>
              <span className={styles.dot} />
              <span className={styles.name}>{a.name}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.label}>Channels</span>
              <span>{a.channels.length ? a.channels.join(', ') : 'default'}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.label}>DB</span>
              <span className={styles.mono}>{a.dbPath}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
```

`Agents.module.css`:
```css
.page { padding: 32px; overflow-y: auto; height: 100%; }
.title { font-size: 20px; font-weight: 700; margin-bottom: 24px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 20px; display: flex; flex-direction: column; gap: 12px; }
.cardHeader { display: flex; align-items: center; gap: 10px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--success); flex-shrink: 0; }
.name { font-size: 15px; font-weight: 600; }
.row { display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
.label { color: var(--muted); font-size: 11px; font-weight: 500; text-transform: uppercase; letter-spacing: .4px; }
.mono { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--muted); word-break: break-all; }
.muted { color: var(--muted); }
```

**Step 3: Build**

```bash
cd channels/webchat-ui && npm run build
```

**Step 4: Commit**

```bash
git add channels/webchat-ui/src/views/Memory.tsx channels/webchat-ui/src/views/Memory.module.css \
        channels/webchat-ui/src/views/Agents.tsx channels/webchat-ui/src/views/Agents.module.css
git commit -m "feat: webchat Memory and Agents views"
```

---

### Task 8: Monitoring + Workspace views

**Files:**
- Modify: `channels/webchat-ui/src/views/Monitoring.tsx`
- Create: `channels/webchat-ui/src/views/Monitoring.module.css`
- Modify: `channels/webchat-ui/src/views/Workspace.tsx`
- Create: `channels/webchat-ui/src/views/Workspace.module.css`

**Step 1: Implement `Monitoring.tsx`**

```tsx
import { useState, useEffect } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts'
import { getMetrics } from '../api'
import styles from './Monitoring.module.css'

interface Metrics { inputTokens: number; outputTokens: number; totalTokens: number; sessions: number }

function Card({ label, value }: { label: string; value: string | number }) {
  return (
    <div className={styles.card}>
      <div className={styles.cardLabel}>{label}</div>
      <div className={styles.cardValue}>{value.toLocaleString()}</div>
    </div>
  )
}

export default function Monitoring() {
  const [metrics, setMetrics] = useState<Metrics | null>(null)

  useEffect(() => { getMetrics().then(setMetrics) }, [])

  const chartData = metrics ? [
    { name: 'Input', tokens: metrics.inputTokens },
    { name: 'Output', tokens: metrics.outputTokens },
  ] : []

  return (
    <div className={styles.page}>
      <h2 className={styles.title}>Monitoring</h2>
      {metrics && (
        <>
          <div className={styles.cards}>
            <Card label="Total Tokens" value={metrics.totalTokens} />
            <Card label="Input Tokens" value={metrics.inputTokens} />
            <Card label="Output Tokens" value={metrics.outputTokens} />
            <Card label="Sessions" value={metrics.sessions} />
          </div>
          <div className={styles.chart}>
            <div className={styles.chartTitle}>Token Breakdown</div>
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                <XAxis dataKey="name" stroke="var(--muted)" tick={{ fill: 'var(--muted)', fontSize: 12 }} />
                <YAxis stroke="var(--muted)" tick={{ fill: 'var(--muted)', fontSize: 12 }} />
                <Tooltip contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text)' }} />
                <Bar dataKey="tokens" fill="var(--accent)" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </div>
  )
}
```

`Monitoring.module.css`:
```css
.page { padding: 32px; overflow-y: auto; height: 100%; }
.title { font-size: 20px; font-weight: 700; margin-bottom: 24px; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px; margin-bottom: 32px; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 20px; }
.cardLabel { font-size: 12px; color: var(--muted); font-weight: 500; text-transform: uppercase; letter-spacing: .4px; margin-bottom: 8px; }
.cardValue { font-size: 28px; font-weight: 700; }
.chart { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 20px; }
.chartTitle { font-size: 14px; font-weight: 600; margin-bottom: 16px; }
```

**Step 2: Implement `Workspace.tsx`**

```tsx
import { useState, useEffect } from 'react'
import { getWorkspaceFiles, getWorkspaceFile, putWorkspaceFile } from '../api'
import styles from './Workspace.module.css'

interface FileEntry { name: string; exists: boolean }

export default function Workspace() {
  const [files, setFiles] = useState<FileEntry[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  useEffect(() => { getWorkspaceFiles().then(setFiles) }, [])

  async function selectFile(name: string) {
    setSelected(name)
    const { content: c } = await getWorkspaceFile(name)
    setContent(c)
    setSaved(false)
  }

  async function save() {
    if (!selected) return
    setSaving(true)
    await putWorkspaceFile(selected, content)
    setSaving(false)
    setSaved(true)
    setTimeout(() => setSaved(false), 2000)
  }

  return (
    <div className={styles.layout}>
      <aside className={styles.sidebar}>
        <div className={styles.sidebarHeader}>Files</div>
        {files.map(f => (
          <div
            key={f.name}
            className={`${styles.fileItem} ${selected === f.name ? styles.selected : ''}`}
            onClick={() => selectFile(f.name)}
          >
            <span className={`${styles.exists} ${f.exists ? styles.present : styles.absent}`} />
            {f.name}
          </div>
        ))}
      </aside>
      <div className={styles.editor}>
        {selected ? (
          <>
            <div className={styles.editorHeader}>
              <span className={styles.filename}>{selected}</span>
              <button className={styles.saveBtn} onClick={save} disabled={saving}>
                {saved ? '✓ Saved' : saving ? 'Saving…' : 'Save'}
              </button>
            </div>
            <textarea
              className={styles.textarea}
              value={content}
              onChange={e => { setContent(e.target.value); setSaved(false) }}
              spellCheck={false}
            />
          </>
        ) : (
          <div className={styles.placeholder}>Select a file to edit</div>
        )}
      </div>
    </div>
  )
}
```

`Workspace.module.css`:
```css
.layout { display: flex; height: 100%; }
.sidebar { width: 200px; background: var(--sidebar); border-right: 1px solid var(--border); padding: 12px 0; }
.sidebarHeader { padding: 8px 16px; font-size: 11px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .5px; }
.fileItem { padding: 8px 16px; cursor: pointer; font-size: 13px; display: flex; align-items: center; gap: 8px; color: var(--muted); transition: color .15s, background .15s; }
.fileItem:hover { color: var(--text); background: rgba(255,255,255,.04); }
.selected { color: var(--text) !important; background: rgba(124,58,237,.15) !important; }
.exists { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.present { background: var(--success); }
.absent { background: var(--border); }
.editor { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.editorHeader { display: flex; align-items: center; justify-content: space-between; padding: 12px 20px; border-bottom: 1px solid var(--border); }
.filename { font-size: 13px; font-weight: 600; }
.saveBtn { padding: 6px 16px; border-radius: var(--radius); border: none; background: var(--accent); color: #fff; font-size: 13px; font-weight: 600; transition: background .15s; }
.saveBtn:hover:not(:disabled) { background: var(--accent-hover); }
.saveBtn:disabled { opacity: .7; cursor: default; }
.textarea { flex: 1; padding: 20px; background: var(--bg); color: var(--text); border: none; outline: none; font-family: 'JetBrains Mono', 'Courier New', monospace; font-size: 13px; line-height: 1.7; resize: none; }
.placeholder { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--muted); font-size: 14px; }
```

**Step 3: Build**

```bash
cd channels/webchat-ui && npm run build
```

**Step 4: Commit**

```bash
git add channels/webchat-ui/src/views/Monitoring.tsx channels/webchat-ui/src/views/Monitoring.module.css \
        channels/webchat-ui/src/views/Workspace.tsx channels/webchat-ui/src/views/Workspace.module.css
git commit -m "feat: webchat Monitoring and Workspace views"
```

---

### Task 9: Wire `Main.kt` — pass new args to WebChatAdapter + full integration

**Files:**
- Modify: `app/src/main/kotlin/com/assistant/Main.kt`
- Modify: `config/application.yml`
- Modify: `config/secrets.yml` (document, not commit)

**Step 1: Update `WebChatAdapter` instantiation in `Main.kt`**

Find the existing block:
```kotlin
if (config.webchat.enabled) {
    val webchat = WebChatAdapter(gateway, config.webchat.port, config.webchat.basePath)
```

Replace with:
```kotlin
if (config.webchat.enabled) {
    // Build agent info list for the Agents view
    val agentInfoList: List<AgentInfo> = if (config.routing == null) {
        listOf(AgentInfo("default", listOf("telegram"), config.memory.dbPath.replace("~", System.getProperty("user.home"))))
    } else {
        config.routing.channels.entries.groupBy({ it.value }, { it.key })
            .map { (agentName, channels) ->
                val dbPath = File(globalDir, "agents/$agentName/memory.db").absolutePath
                AgentInfo(agentName, channels, dbPath)
            }
    }
    val webchat = WebChatAdapter(
        gateway = gateway,
        port = config.webchat.port,
        basePath = config.webchat.basePath,
        password = config.webchat.password,
        memory = activeMemory,
        workspace = workspace,
        tokenTracker = activeTokenTracker,
        agents = agentInfoList
    )
```

Note: `AgentInfo` is defined in `WebChatAdapter.kt` in the `channels` module. Add the import in `Main.kt`:
```kotlin
import com.assistant.webchat.AgentInfo
```

**Step 2: Add `password` to `config/application.yml` `webchat` stanza**

```yaml
webchat:
  enabled: true
  port: 8080
  base-path: ""
  password: ""   # set in secrets.yml
```

**Step 3: Document `secrets.yml` entry** (do NOT commit secrets.yml)

Tell the user to add to `config/secrets.yml`:
```yaml
webchat:
  password: "your-secure-password"
```

If `password` is left empty, auth is bypassed (open access) — useful for local-only deployments.

**Step 4: Build everything**

```bash
./gradlew shadowJar
```

Expected: BUILD SUCCESSFUL.

**Step 5: Run tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, all tests pass.

**Step 6: Smoke test manually**

```bash
java -jar app/build/libs/assistant.jar
# Open http://localhost:8080 in browser
# Should see the Login screen (or chat directly if password is empty)
# Navigate through all 5 views — no JS console errors
```

**Step 7: Commit**

```bash
git add app/src/main/kotlin/com/assistant/Main.kt config/application.yml
git commit -m "feat: wire WebChatAdapter with dashboard args — memory, workspace, tokens, agents"
```

---

## Verification

```bash
# All tests pass
./gradlew test

# Fat JAR builds
./gradlew shadowJar

# Start assistant and open browser
java -jar app/build/libs/assistant.jar
# http://localhost:8080 → Login → Chat → Memory → Monitoring → Workspace → Agents
```
