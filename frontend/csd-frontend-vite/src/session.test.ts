import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadSession, saveSession, type Session } from './session'

// a JWT-shaped token whose payload has this exp (seconds); the signature isn't checked here
function token(exp: number): string {
  const payload = btoa(JSON.stringify({ sub: 'u1', exp })).replace(/=+$/, '')
  return `header.${payload}.signature`
}

const user = { id: 'u1', email: 'a@b.c', role: 'user' as const, created_at: '2026-11-10T00:00:00Z' }

describe('loadSession', () => {
  beforeEach(() => {
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key),
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps a session whose access token has expired, since the refresh token can renew it', () => {
    const expired: Session = { token: token(Math.floor(Date.now() / 1000) - 3600), user }
    saveSession(expired)

    expect(loadSession()).toEqual(expired)
    expect(localStorage.getItem('session')).not.toBeNull()
  })

  it('keeps a session whose token is still valid', () => {
    const valid: Session = { token: token(Math.floor(Date.now() / 1000) + 3600), user }
    saveSession(valid)

    expect(loadSession()).toEqual(valid)
  })

  it.each([
    ['not JSON', '{not json'],
    ['no token', JSON.stringify({ user })],
    ['no user', JSON.stringify({ token: 'x.y.z' })],
    ['null', 'null'],
  ])('drops a corrupt entry (%s)', (_, raw) => {
    localStorage.setItem('session', raw)

    expect(loadSession()).toBeNull()
    expect(localStorage.getItem('session')).toBeNull()
  })

  it('is null with nothing stored', () => {
    expect(loadSession()).toBeNull()
  })
})
