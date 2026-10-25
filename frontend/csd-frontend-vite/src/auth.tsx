import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Navigate, Outlet } from 'react-router-dom'
import { login as apiLogin, register as apiRegister } from './api'
import { clearSession, loadSession, saveSession, setUnauthorizedHandler, type Session } from './session'

type AuthValue = {
  session: Session | null
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(loadSession)

  useEffect(() => {
    setUnauthorizedHandler(() => setSession(null))
    return () => setUnauthorizedHandler(null)
  }, [])

  const start = useCallback((next: Session) => {
    saveSession(next)
    setSession(next)
  }, [])

  const value = useMemo<AuthValue>(
    () => ({
      session,
      login: async (email, password) => start(await apiLogin(email, password)),
      register: async (email, password) => start(await apiRegister(email, password)),
      logout: () => {
        clearSession()
        setSession(null)
      },
    }),
    [session, start],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components -- context hook, not a component
export function useAuth(): AuthValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}

export function RequireAuth() {
  const { session } = useAuth()
  return session ? <Outlet /> : <Navigate to="/signin" replace />
}

export function GuestOnly() {
  const { session } = useAuth()
  return session ? <Navigate to="/" replace /> : <Outlet />
}
