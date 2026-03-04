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
