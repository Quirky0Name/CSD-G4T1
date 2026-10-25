// The signed-in user's session, kept in localStorage so a refresh stays signed in.
// The token is User Management's JWT (docs/CONTRACTS.md, "Auth"), valid 24 hours.
export type SessionUser = { id: string; email: string; created_at: string }
export type Session = { token: string; user: SessionUser }

const STORAGE_KEY = 'session'

function expiresAtMs(token: string): number | null {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const { exp } = JSON.parse(atob(payload)) as { exp?: number }
    return typeof exp === 'number' ? exp * 1000 : null
  } catch {
    return null
  }
}

export function loadSession(): Session | null {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    const session = JSON.parse(raw) as Session
    const expiresAt = expiresAtMs(session.token)
    if (expiresAt !== null && expiresAt > Date.now()) return session
  } catch {
    // a corrupt entry is treated like no session
  }
  localStorage.removeItem(STORAGE_KEY)
  return null
}

export function saveSession(session: Session): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function clearSession(): void {
  localStorage.removeItem(STORAGE_KEY)
}

export function getToken(): string | null {
  return loadSession()?.token ?? null
}

// api.ts calls this on a 401 from Storage or Updating; AuthProvider registers what happens then
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export function notifyUnauthorized(): void {
  clearSession()
  unauthorizedHandler?.()
}
