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
