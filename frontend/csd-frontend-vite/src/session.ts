// The signed-in user's session, kept in localStorage so reloading the page stays signed in.
// The token is User Management's access JWT (docs/CONTRACTS.md, "Auth"), valid 1 hour. A session
// whose token has expired is kept: the first request answered 401 renews it with the refresh
// token, an HttpOnly cookie this code never sees (api.ts, refresh.ts).
export type UserRole = 'user' | 'admin'
// role is absent on a session saved before dev mode existed, which means a researcher
export type SessionUser = { id: string; email: string; role?: UserRole; created_at: string }
export type Session = { token: string; user: SessionUser }

const STORAGE_KEY = 'session'

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) return false
  const { token, user } = value as Partial<Session>
  return typeof token === 'string' && typeof user === 'object' && user !== null
}

export function loadSession(): Session | null {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    const session: unknown = JSON.parse(raw)
    if (isSession(session)) return session
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

// api.ts calls this when a 401 can't be fixed by a refresh; AuthProvider registers what happens then
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export function notifyUnauthorized(): void {
  clearSession()
  unauthorizedHandler?.()
}
