import { getToken, clearToken } from './auth'

export interface Session { id: string; messages: number; lastActivity: number }
export interface MemoryChunk { id: number; text: string; sessionId: string; createdAt: number }
export interface Metrics { inputTokens: number; outputTokens: number; totalTokens: number; sessions: number }
export interface WorkspaceFile { name: string; exists: boolean }
export interface Agent { name: string; channels: string[]; dbPath: string }

function headers(): HeadersInit {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function apiFetch(path: string, opts: RequestInit = {}) {
  let res: Response
  try {
    res = await fetch(path, { ...opts, headers: { ...headers(), ...(opts.headers ?? {}) } })
  } catch (err) {
    throw new Error(`Network error: ${err instanceof Error ? err.message : String(err)}`)
  }
  if (res.status === 401) { clearToken(); window.location.href = '/login'; throw new Error('Unauthorized') }
  return res
}

export async function getSessions(): Promise<Session[]> {
  return (await apiFetch('/api/sessions')).json()
}

export async function getMemory(q = '', limit = 50): Promise<MemoryChunk[]> {
  return (await apiFetch(`/api/memory?q=${encodeURIComponent(q)}&limit=${limit}`)).json()
}

export async function deleteMemory(id: number) {
  await apiFetch(`/api/memory/${id}`, { method: 'DELETE' })
}

export async function getMetrics(): Promise<Metrics> {
  return (await apiFetch('/api/metrics')).json()
}

export async function getWorkspaceFiles(): Promise<WorkspaceFile[]> {
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

export async function getAgents(): Promise<Agent[]> {
  return (await apiFetch('/api/agents')).json()
}
