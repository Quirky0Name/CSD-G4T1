// Renewing the access token with the refresh token (docs/CONTRACTS.md, "Auth"). A refresh token
// works once, so two refreshes at once would sign one of them out: within a tab, calls made during
// a refresh share it; across tabs, a lock (navigator.locks in the browser) lets one run at a time,
// and the next tab then refreshes with the cookie the first one left. Pure, so it's testable
// without a browser: api.ts gives it the real request and lock.

export const REFRESH_LOCK = 'refresh-session'

export type Lock = <T>(name: string, task: () => Promise<T>) => Promise<T>

export function createRefresher<T>(refresh: () => Promise<T>, lock: Lock): () => Promise<T> {
  let inFlight: Promise<T> | null = null
  return () => {
    if (inFlight) return inFlight
    const current = lock(REFRESH_LOCK, refresh).finally(() => {
      inFlight = null
    })
    inFlight = current
    return current
  }
}

// navigator.locks where the browser has it (every current one does, over HTTPS or on localhost);
// otherwise the task runs at once, which still keeps one refresh per tab
export const browserLock: Lock = (name, task) =>
  typeof navigator !== 'undefined' && navigator.locks
    ? navigator.locks.request(name, task)
    : task()
